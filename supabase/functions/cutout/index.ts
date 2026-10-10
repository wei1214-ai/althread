import { serve } from "https://deno.land/std@0.224.0/http/server.ts";

// Garment cutout via Roboflow instance segmentation.
// Request:  { "imageB64": "data:image/jpeg;base64,..." }
// Response: { "polygons": [{ "label": "dress", "score": 0.9,
//                            "points": [{ "x": 12.5, "y": 30.0 }, ...] }],
//             "imgW": 1280, "imgH": 960,
//             "topGarment": "dress" | null }
// Points are in the source image pixel space. Error codes QUOTA_EXHAUSTED and
// NO_GARMENT are part of the client contract — keep their names stable.
// Security: the caller JWT is verified in code. Cost control: inputs are
// size-capped before forwarding. Upstream failures map to generic codes, and a
// best-effort per-user daily quota applies.

const MODEL_ID = "clothes-segmentation-final/1";
const INFERENCE_URL = `https://serverless.roboflow.com/${MODEL_ID}`;

// Labels that count as "kept" for the cutout (everything else = background).
const GARMENT_LABELS = new Set([
  "vest", "jacket", "top", "shirt", "dress",
  "pants", "shorts", "skirt", "sweater", "tshirt",
]);

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization,apikey,content-type",
  "Access-Control-Allow-Methods": "POST,OPTIONS",
};

// ~3MB decoded; larger inputs are rejected before forwarding.
const MAX_B64_CHARS = 4_200_000;
const MAX_BODY_CHARS = 5_000_000;
const DAILY_LIMIT = 50;

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
      body: JSON.stringify({ p_user: userId, p_fn: "cutout", p_limit: DAILY_LIMIT }),
      signal: AbortSignal.timeout(8_000),
    });
    if (!r.ok) return true;
    const count = (await r.json()) as number;
    return typeof count === "number" ? count <= DAILY_LIMIT : true;
  } catch {
    return true;
  }
}

serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") {
    return json(405, { error: "METHOD_NOT_ALLOWED", detail: "Method not allowed." });
  }
  try {
    const raw = await req.text();
    if (!raw || raw.length > MAX_BODY_CHARS) {
      return json(413, { error: "PAYLOAD_TOO_LARGE", detail: "Photo too large, try a smaller one." });
    }

    const userId = await verifyUserId(req);
    if (!userId) {
      return json(401, { error: "UNAUTHORIZED", detail: "Please log in to continue." });
    }
    if (!(await checkRateLimit(userId))) {
      return json(429, {
        error: "QUOTA_EXHAUSTED",
        detail: "Daily cutout limit reached, try again tomorrow.",
      });
    }

    let parsed: { imageB64?: unknown };
    try {
      parsed = JSON.parse(raw);
    } catch {
      return json(400, { error: "BAD_INPUT", detail: "Invalid request." });
    }
    if (typeof parsed.imageB64 !== "string" || parsed.imageB64.length === 0) {
      return json(400, { error: "BAD_INPUT", detail: "A photo is required." });
    }
    const clean = parsed.imageB64.includes(",")
      ? (parsed.imageB64.split(",").pop() as string)
      : parsed.imageB64;
    if (
      !clean || clean.length > MAX_B64_CHARS ||
      !/^[A-Za-z0-9+/=\s]+$/.test(clean)
    ) {
      return json(400, {
        error: "BAD_INPUT",
        detail: "Photo is too large or invalid, try a smaller one.",
      });
    }

    const apiKey = Deno.env.get("ROBOFLOW_API_KEY") ?? "";
    if (!apiKey) {
      console.error("[cutout] ROBOFLOW_API_KEY secret missing");
      return json(503, { error: "UNAVAILABLE", detail: "Cutout service unavailable, try again later." });
    }

    // Roboflow versioned-model contract: raw base64 body (not a JSON envelope),
    // Bearer auth, `application/x-www-form-urlencoded` content type.
    let r: Response;
    try {
      r = await fetch(INFERENCE_URL, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${apiKey}`,
          "Content-Type": "application/x-www-form-urlencoded",
        },
        body: clean,
        signal: AbortSignal.timeout(75_000),
      });
    } catch (e) {
      console.error(`[cutout] upstream fetch failed: ${(e as Error).message}`);
      return json(502, { error: "TIMEOUT", detail: "Cutout service timed out, try again." });
    }
    const text = await r.text();
    if (!r.ok) {
      console.error(`[cutout] roboflow ${r.status}: ${text.slice(0, 500)}`);
      if (r.status === 401 || r.status === 403) {
        return json(502, {
          error: "UPSTREAM_ERROR",
          detail: "Cutout service unavailable, try again later.",
        });
      }
      if (r.status === 429) {
        return json(429, {
          error: "QUOTA_EXHAUSTED",
          detail: "Roboflow rate limit, try again later.",
        });
      }
      return json(502, {
        error: "UPSTREAM_ERROR",
        detail: "Cutout service failed, try again later.",
      });
    }

    const data = JSON.parse(text) as {
      predictions?: Array<{
        class?: string; confidence?: number;
        points?: Array<{ x: number; y: number }>;
      }>;
      image?: { width?: number; height?: number };
    };
    const preds = Array.isArray(data.predictions) ? data.predictions : [];
    console.error(
      `[cutout] preds=${preds.length} ` +
      preds.slice(0, 12).map((p) =>
        `${p.class ?? "?"}:${Math.round((p.confidence ?? 0) * 100)}%/${Array.isArray(p.points) ? p.points.length : 0}pts`
      ).join(", "),
    );
    const garments = preds
      .filter((p) =>
        p.class && GARMENT_LABELS.has(p.class.toLowerCase()) &&
        Array.isArray(p.points) && p.points.length >= 3
      )
      .map((p) => ({
        label: p.class as string,
        score: p.confidence ?? 0,
        points: (p.points as Array<{ x: number; y: number }>).map((pt) => ({ x: pt.x, y: pt.y })),
      }));
    // No early NO_GARMENT return: empty results flow into the largest-polygon
    // fallback below; only truly shapeless results report NO_GARMENT.
    garments.sort((a, b) => b.score - a.score);
    let out = garments;
    if (out.length === 0) {
      // Lenient fallback: flat-lay photos are often labeled non-garment, so keep
      // the largest polygon instead of failing; a retryable cutout beats a dead end.
      const cands = preds
        .filter((p) => Array.isArray(p.points) && p.points.length >= 3)
        .map((p) => ({
          label: String(p.class ?? "unknown"),
          score: p.confidence ?? 0,
          points: (p.points as Array<{ x: number; y: number }>).map((pt) => ({ x: pt.x, y: pt.y })),
          area: Math.abs(
            (p.points as Array<{ x: number; y: number }>).reduce(
              (s, pt, i, a) => s + (a[(i + 1) % a.length].x - pt.x) * (a[(i + 1) % a.length].y + pt.y), 0,
            ),
          ) / 2,
        }))
        .filter((p) => p.area > 0)
        .sort((a, b) => b.area - a.area);
      if (cands.length === 0) {
        const seen = [...new Set(preds.map((p) => String(p.class ?? "?")))].join(", ");
        return json(422, {
          error: "NO_GARMENT",
          detail: `Model saw [${seen || "nothing"}] but no usable shape. Try a plainer background.`,
        });
      }
      console.error(`[cutout] fallback to largest non-garment: ${cands[0].label}`);
      out = [{ label: cands[0].label, score: cands[0].score, points: cands[0].points }];
    }

    return new Response(
      JSON.stringify({
        polygons: out,
        imgW: data.image?.width ?? 0,
        imgH: data.image?.height ?? 0,
        topGarment: out[0].label,
      }),
      { headers: { ...cors, "Content-Type": "application/json" } },
    );
  } catch (e) {
    console.error(`[cutout] ${(e as Error).message}`);
    return json(500, { error: "INTERNAL", detail: "Cutout failed, try again later." });
  }
});
