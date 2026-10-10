import { serve } from "https://deno.land/std@0.224.0/http/server.ts";

// Virtual try-on via the TryOnCloud Developer API.
// Request:  { "personB64": "data:image/jpeg;base64,...",
//             "garmentB64": "data:image/jpeg;base64,..." }
// Response: { "image": "data:image/png;base64,..." }
// Success carries raw PNG bytes re-encoded as JSON. Errors are { error, code }.
// Security: the caller JWT is verified in code. Cost control: inputs are
// size-capped before decoding. Upstream failures map to generic codes, and a
// best-effort per-user daily quota applies.

const TRYON_URL = "https://www.tryoncloud.com/api/v1/generate";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization,apikey,content-type",
  "Access-Control-Allow-Methods": "POST,OPTIONS",
};

// ~3MB decoded per photo; larger inputs are rejected before decoding.
const MAX_B64_CHARS = 4_200_000;
const MAX_BODY_CHARS = 9_000_000;
const DAILY_LIMIT = 10;

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...cors, "Content-Type": "application/json" },
  });
}

/**
 * Verifies the caller JWT against Auth and returns the user id.
 * Verified in code (defense in depth) so the function stays secure even if
 * gateway JWT enforcement is disabled.
 */
async function verifyUserId(req: Request): Promise<string | null> {
  try {
    const url = Deno.env.get("SUPABASE_URL") ?? "";
    const anon = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
    const token = (req.headers.get("authorization") ?? "").replace(/^Bearer\s+/i, "");
    if (!url || !anon || !token) return null;
    const r = await fetch(`${url}/auth/v1/user`, {
      headers: { apikey: anon, Authorization: `Bearer ${token}` },
      signal: AbortSignal.timeout(8_000),
    });
    if (!r.ok) return null;
    const user = (await r.json()) as { id?: string };
    return typeof user.id === "string" && user.id.length > 0 ? user.id : null;
  } catch {
    return null;
  }
}

/**
 * Best-effort per-user daily quota via the `bump_function_usage` RPC.
 * Fail-open when unconfigured so the app keeps working without billing infra.
 */
async function checkRateLimit(userId: string): Promise<boolean> {
  try {
    const url = Deno.env.get("SUPABASE_URL") ?? "";
    const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
    if (!url || !serviceKey) return true;
    const r = await fetch(`${url}/rest/v1/rpc/bump_function_usage`, {
      method: "POST",
      headers: {
        apikey: serviceKey,
        Authorization: `Bearer ${serviceKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ p_user: userId, p_fn: "vton", p_limit: DAILY_LIMIT }),
      signal: AbortSignal.timeout(8_000),
    });
    if (!r.ok) return true;
    const count = (await r.json()) as number;
    return typeof count === "number" ? count <= DAILY_LIMIT : true;
  } catch {
    return true;
  }
}

/**
 * Strips the data-URL prefix and rejects oversized/non-base64 input before
 * decoding, to bound memory and upstream cost.
 */
function cleanImageInput(value: unknown): string | null {
  if (typeof value !== "string" || value.length === 0 || value.length > MAX_B64_CHARS) {
    return null;
  }
  const payload = value.includes(",") ? (value.split(",").pop() as string) : value;
  if (!payload || payload.length > MAX_B64_CHARS) return null;
  if (!/^[A-Za-z0-9+/=\s]+$/.test(payload)) return null;
  return payload;
}

function b64ToBytes(clean: string): Uint8Array {
  const bin = atob(clean.replace(/\s+/g, ""));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/** Chunked encode to avoid stack overflow on large images. */
function bytesToB64(bytes: Uint8Array): string {
  let bin = "";
  const CHUNK = 8192;
  for (let i = 0; i < bytes.length; i += CHUNK) {
    bin += String.fromCharCode(...bytes.subarray(i, i + CHUNK));
  }
  return btoa(bin);
}

serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") {
    return json(405, { error: "Method not allowed.", code: "METHOD_NOT_ALLOWED" });
  }
  try {
    const raw = await req.text();
    if (!raw || raw.length > MAX_BODY_CHARS) {
      return json(413, { error: "Photos too large, try smaller ones.", code: "PAYLOAD_TOO_LARGE" });
    }

    const userId = await verifyUserId(req);
    if (!userId) {
      return json(401, { error: "Please log in to use try-on.", code: "UNAUTHORIZED" });
    }
    if (!(await checkRateLimit(userId))) {
      return json(429, {
        error: "Daily try-on limit reached, try again tomorrow.",
        code: "RATE_LIMITED",
      });
    }

    let parsed: { personB64?: unknown; garmentB64?: unknown };
    try {
      parsed = JSON.parse(raw);
    } catch {
      return json(400, { error: "Invalid request.", code: "BAD_INPUT" });
    }
    const person = cleanImageInput(parsed.personB64);
    const garment = cleanImageInput(parsed.garmentB64);
    if (!person || !garment) {
      return json(400, {
        error: "Two valid photos are required.",
        code: "BAD_INPUT",
      });
    }

    const apiKey = Deno.env.get("TRYONCLOUD_API_KEY") ?? "";
    if (!apiKey) {
      console.error("[vton] TRYONCLOUD_API_KEY secret missing");
      return json(503, {
        error: "Try-on service unavailable, try again later.",
        code: "UNAVAILABLE",
      });
    }

    const fd = new FormData();
    fd.append("person_image", new Blob([b64ToBytes(person)], { type: "image/jpeg" }), "person.jpg");
    fd.append("garment_image", new Blob([b64ToBytes(garment)], { type: "image/jpeg" }), "garment.jpg");

    let r: Response;
    try {
      r = await fetch(TRYON_URL, {
        method: "POST",
        headers: { "X-API-KEY": apiKey },
        body: fd,
        signal: AbortSignal.timeout(110_000),
      });
    } catch (e) {
      console.error(`[vton] upstream fetch failed: ${(e as Error).message}`);
      return json(502, {
        error: "Try-on service timed out, try again.",
        code: "TIMEOUT",
      });
    }

    if (!r.ok) {
      const t = await r.text();
      console.error(`[vton] tryoncloud ${r.status}: ${t.slice(0, 500)}`);
      let code = "UPSTREAM_ERROR";
      let message = "Try-on failed, try again later.";
      try {
        const body = JSON.parse(t) as { error?: string; code?: string };
        const providerCode = body.code ?? "";
        if (
          providerCode === "NO_CREDITS" || providerCode === "FREE_TRIAL_USED" ||
          providerCode === "QUOTA_EXCEEDED" || r.status === 402
        ) {
          code = "QUOTA_EXHAUSTED";
          message = "Try-on free trial used up, please top up later.";
        } else if (providerCode === "CONTENT_FILTERED" || r.status === 422) {
          message = "Photo blocked by content filter, try other photos.";
        } else if (
          providerCode === "NO_PERSON" || providerCode === "NO_GARMENT" ||
          providerCode === "BAD_INPUT" || r.status === 400
        ) {
          message = "Try-on rejected the photos, try clearer ones.";
        } else if (providerCode === "IMAGE_TOO_LARGE" || r.status === 413) {
          message = "Photos too large, try smaller ones.";
        } else if (providerCode === "RATE_LIMITED" || r.status === 429) {
          code = "RATE_LIMITED";
          message = "Too many requests, wait a minute and retry.";
        }
      } catch {
        // Non-JSON upstream body: keep the generic message.
      }
      return json(r.status === 429 ? 429 : 502, { error: message, code });
    }

    // Success body is raw PNG bytes (never JSON), so read as bytes.
    const bytes = new Uint8Array(await r.arrayBuffer());
    if (bytes.length === 0) {
      console.error("[vton] empty image from provider");
      return json(502, {
        error: "Try-on returned an empty image, try again.",
        code: "UPSTREAM_ERROR",
      });
    }
    return new Response(JSON.stringify({ image: `data:image/png;base64,${bytesToB64(bytes)}` }), {
      headers: { ...cors, "Content-Type": "application/json" },
    });
  } catch (e) {
    console.error(`[vton] ${(e as Error).message}`);
    return json(500, { error: "Try-on failed, try again later.", code: "INTERNAL" });
  }
});
