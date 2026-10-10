package com.example.myapplicationkoG

import android.util.Base64
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * One cloud-cutout polygon (Roboflow instance segmentation).
 * Points are in the source image pixel space (see imgW/imgH for scaling).
 */
data class CutoutPolygon(
    val label: String,
    val points: List<Point>,
    val score: Float
)

data class Point(val x: Float, val y: Float)

/**
 * Calls the `cutout` Edge Function (Roboflow instance segmentation).
 * The call is synchronous and single-shot: success, NO_GARMENT, QUOTA, or key error.
 */
class CutoutRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * @return polygons + source size + top garment label (e.g. "dress").
     */
    suspend fun fetchPolygons(imageBytes: ByteArray): Triple<List<CutoutPolygon>, Pair<Int, Int>, String?> =
        withContext(Dispatchers.IO) {
            val b64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            val payload = JSONObject().put("imageB64", "data:image/jpeg;base64,$b64").toString()
            // All functions enforce JWT: no session = clear login prompt, no wasted call.
            val authHeader = supabase.auth.currentSessionOrNull()?.accessToken
                ?: error("Please log in to continue.")

            val req = Request.Builder()
                .url(AIConfig.CUTOUT_URL)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $authHeader")
                .header("apikey", AIConfig.SUPABASE_ANON_KEY)
                .build()

            val raw = client.newCall(req).execute().use { resp ->
                resp.body?.string().orEmpty()
            }
            if (raw.isBlank()) error("Empty cutout response")

            val obj = JSONObject(raw)
            if (!obj.has("polygons")) {
                val err = obj.optString("error", raw.take(200))
                error(
                    when (err) {
                        "QUOTA_EXHAUSTED" -> "AI quota used up (${obj.optString("detail", "").take(100)}), please try again later."
                        "NO_GARMENT" -> obj.optString(
                            "detail",
                            "No clothing detected. Lay the garment flat and fill the frame."
                        )
                        else -> err.take(200)
                    }
                )
            }
            val arr = obj.getJSONArray("polygons")
            val polys = (0 until arr.length()).mapNotNull { i ->
                val p = arr.getJSONObject(i)
                val pts = p.optJSONArray("points") ?: return@mapNotNull null
                if (pts.length() < 3) return@mapNotNull null
                CutoutPolygon(
                    label = p.optString("label", ""),
                    points = (0 until pts.length()).map { j ->
                        val pt = pts.getJSONObject(j)
                        Point(pt.optDouble("x", 0.0).toFloat(), pt.optDouble("y", 0.0).toFloat())
                    },
                    score = p.optDouble("score", 0.0).toFloat()
                )
            }
            if (polys.isEmpty()) error("Cutout returned no usable shapes")
            val size = obj.optInt("imgW", 0) to obj.optInt("imgH", 0)
            val top = obj.optString("topGarment", "").takeIf { it.isNotBlank() }
            Triple(polys, size, top)
        }
}
