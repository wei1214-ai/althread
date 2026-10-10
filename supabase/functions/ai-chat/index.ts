import { serve } from "https://deno.land/std@0.168.0/http/server.ts";

// Style advisor ("Thread") backed by OpenRouter.
// Home mode answers daily outfit questions; studio mode coaches Design Studio work.
// Security: the caller JWT is verified in code (defense in depth, independent of
// gateway enforcement). Cost control: message history, text length, and image
// payloads are capped to bound upstream token usage. Upstream failures map to
// generic codes, and a best-effort per-user daily quota applies.

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

// Request caps bound body size and upstream token cost before any provider call.
const MAX_BODY_CHARS = 300_000;
const MAX_MESSAGES = 13;
const MAX_TEXT_CHARS = 4_000;
const MAX_IMAGES_PER_MESSAGE = 3;
const MAX_IMAGE_CHARS = 2_000_000;
const DAILY_LIMIT = 50;

const SYSTEM_HOME = `You are Thread, AltThread's daily outfit stylist on the Home screen.
Focus ONLY on outfit advice: what to wear today, outfit matching, colors, layering,
occasion / weather appropriateness. If the user attached photos, comment on them.
Do NOT talk about Design Studio tools (dye palette, patches, cutouts) unless the
user explicitly asks. Answer concisely in the user's language, max ~150 words.`;

const SYSTEM_STUDIO = `You are Thread, the AltThread Design Studio coach.
Focus on Design Studio and design/style suggestions: dye colors, patches, fabrics,
upcycling, silhouette, style guidance, and how to use the studio tools.
If front/back cutout images are attached, review the current design and suggest
concrete dye + patch improvements. Answer concisely in the user's language,
max ~150 words.`;

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
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
    const user = await r.json() as { id?: string };
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
      body: JSON.stringify({ p_user: userId, p_fn: "ai-chat", p_limit: DAILY_LIMIT }),
      signal: AbortSignal.timeout(8_000),
    });
    if (!r.ok) return true;
    const count = await r.json() as number;
    return typeof count === "number" ? count <= DAILY_LIMIT : true;
  } catch {
    return true;
  }
}

/** Rejects oversized or non-data-URL images to bound upstream token cost. */
function isValidImageUrl(value: unknown): boolean {
  if (typeof value !== "string") return false;
  if (!value.startsWith("data:image/")) return false;
  if (value.length > MAX_IMAGE_CHARS) return false;
  const comma = value.indexOf(",");
  const payload = comma >= 0 ? value.slice(comma + 1) : value;
  return /^[A-Za-z0-9+/=\s]+$/.test(payload);
}

/**
 * Validates one chat message. Rejects oversized text/images to control
 * upstream cost and prevent malformed provider requests.
 */
function sanitizeContent(content: unknown): boolean {
  if (typeof content === "string") {
    return content.length <= MAX_TEXT_CHARS;
  }
  if (Array.isArray(content)) {
    if (content.length === 0 || content.length > MAX_IMAGES_PER_MESSAGE + 1) return false;
    let images = 0;
    for (const part of content) {
      if (typeof part !== "object" || part === null) return false;
      const type = (part as { type?: unknown }).type;
      if (type === "text") {
        const text = (part as { text?: unknown }).text;
        if (typeof text !== "string" || text.length > MAX_TEXT_CHARS) return false;
      } else if (type === "image_url") {
        images += 1;
        if (images > MAX_IMAGES_PER_MESSAGE) return false;
        const url = (part as { image_url?: { url?: unknown } }).image_url?.url;
        if (!isValidImageUrl(url)) return false;
      } else {
        return false;
      }
    }
    return true;
  }
  return false;
}

serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  if (req.method !== "POST") {
    return json(405, { error: { message: "Method not allowed.", code: "METHOD_NOT_ALLOWED" } });
  }

  try {
    const raw = await req.text();
    if (!raw || raw.length > MAX_BODY_CHARS) {
      return json(413, { error: { message: "Request too large.", code: "PAYLOAD_TOO_LARGE" } });
    }

    const userId = await verifyUserId(req);
    if (!userId) {
      return json(401, { error: { message: "Please log in to chat.", code: "UNAUTHORIZED" } });
    }
    if (!(await checkRateLimit(userId))) {
      return json(429, {
        error: { message: "Daily chat limit reached, try again tomorrow.", code: "RATE_LIMITED" },
      });
    }

    let parsed: { messages?: unknown; mode?: unknown };
    try {
      parsed = JSON.parse(raw);
    } catch {
      return json(400, { error: { message: "Invalid request.", code: "BAD_INPUT" } });
    }
    const incoming = Array.isArray(parsed.messages) ? parsed.messages : [];
    if (incoming.length === 0 || incoming.length > MAX_MESSAGES) {
      return json(400, { error: { message: "Invalid request.", code: "BAD_INPUT" } });
    }
    for (const m of incoming) {
      if (typeof m !== "object" || m === null) {
        return json(400, { error: { message: "Invalid request.", code: "BAD_INPUT" } });
      }
      const role = (m as { role?: unknown }).role;
      if (role !== "user" && role !== "assistant") {
        return json(400, { error: { message: "Invalid request.", code: "BAD_INPUT" } });
      }
      if (!sanitizeContent((m as { content?: unknown }).content)) {
        return json(400, { error: { message: "Message too large or invalid.", code: "BAD_INPUT" } });
      }
    }

    const normalizedMode = parsed.mode === "home" ? "home" : "studio";
    const systemPrompt = normalizedMode === "home" ? SYSTEM_HOME : SYSTEM_STUDIO;

    // Client-supplied system messages are dropped to prevent prompt injection.
    // Only recent history is forwarded to bound token usage.
    const userMsgs = (incoming as Array<{ role: string; content: unknown }>).filter(
      (m) => m.role !== "system",
    );
    const trimmed = userMsgs.length > 12 ? userMsgs.slice(-12) : userMsgs;
    const outgoing = [{ role: "system", content: systemPrompt }, ...trimmed];

    const apiKey = Deno.env.get("OPENROUTER_API_KEY");
    if (!apiKey) {
      console.error("[ai-chat] OPENROUTER_API_KEY secret missing");
      return json(503, {
        error: { message: "Chat service unavailable, try again later.", code: "UNAVAILABLE" },
      });
    }

    let upstream: Response;
    // OpenRouter chat-completions contract; reasoning/output token budgets cap cost.
    try {
      upstream = await fetch("https://openrouter.ai/api/v1/chat/completions", {
        method: "POST",
        headers: {
          Authorization: `Bearer ${apiKey}`,
          "Content-Type": "application/json",
          "HTTP-Referer": "https://opencode.ai",
          "X-Title": "AltThread",
        },
        body: JSON.stringify({
          model: "thinkingmachines/inkling:free",
          messages: outgoing,
          reasoning: { max_tokens: 1024 },
          max_tokens: 400,
        }),
        signal: AbortSignal.timeout(55_000),
      });
    } catch (e) {
      console.error(`[ai-chat] upstream fetch failed: ${(e as Error).message}`);
      return json(502, {
        error: { message: "Chat service timed out, try again.", code: "TIMEOUT" },
      });
    }

    const data = await upstream.json();
    if (!upstream.ok) {
      console.error(`[ai-chat] upstream ${upstream.status}`);
      if (upstream.status === 429) {
        return json(429, {
          error: { message: "Chat is busy, wait a minute and retry.", code: "UPSTREAM_BUSY" },
        });
      }
      return json(502, {
        error: { message: "Chat service failed, try again later.", code: "UPSTREAM_ERROR" },
      });
    }
    return json(200, data);
  } catch (e) {
    console.error(`[ai-chat] ${(e as Error).message}`);
    return json(500, {
      error: { message: "Chat service failed, try again later.", code: "INTERNAL" },
    });
  }
});
