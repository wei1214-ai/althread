package com.example.myapplicationkoG

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Base64
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

object VtonConfig {
    // Retained for compatibility; no longer used directly.
    const val BASE_URL = "https://yisol-idm-vton.hf.space"
}

class VtonRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    // Cancelling the coroutine Job does not abort a blocking execute(), so the
    // caller cancels both the Job and this call; otherwise the socket runs the full timeout.
    @Volatile private var activeCall: okhttp3.Call? = null

    /** Aborts the in-flight request immediately (safe to call anytime). */
    fun cancelActive() {
        runCatching { activeCall?.cancel() }
        activeCall = null
    }

    suspend fun runTryOn(
        personFile: File,
        garmentFile: File,
        prompt: String = "a photo of a garment",
        // Kept for call compatibility (PiAPI maps garmentLabel itself).
        category: String = "tops",
        photoType: String = "flat-lay",
        garmentLabel: String? = null
    ): File = withContext(Dispatchers.IO) {
        // TryOnCloud takes file bytes (multipart) — base64 them into the
        // function call; no Storage staging needed.
        val userId = supabase.auth.currentUserOrNull()?.id
            ?: error("Please log in to use virtual try-on.")
        try {
            if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException("VTON cancelled")
            val personB64 = "data:image/jpeg;base64," +
                android.util.Base64.encodeToString(personFile.readBytes(), android.util.Base64.NO_WRAP)
            if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException("VTON cancelled")
            val garmentB64 = "data:image/jpeg;base64," +
                android.util.Base64.encodeToString(garmentFile.readBytes(), android.util.Base64.NO_WRAP)
            if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException("VTON cancelled")

            val payload = buildJsonObject {
                put("personB64", JsonPrimitive(personB64))
                put("garmentB64", JsonPrimitive(garmentB64))
                put("prompt", JsonPrimitive(prompt))
                put("category", JsonPrimitive(category))
                put("photoType", JsonPrimitive(photoType))
                if (garmentLabel != null) put("garmentLabel", JsonPrimitive(garmentLabel))
            }.toString()

            val req = Request.Builder()
                .url(AIConfig.VTON_URL)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .header("apikey", AIConfig.SUPABASE_ANON_KEY)
                // All functions enforce JWT: no session = clear login prompt, no wasted call.
                .header(
                    "Authorization",
                    "Bearer ${supabase.auth.currentSessionOrNull()?.accessToken
                        ?: error("Please log in to use virtual try-on.")}"
                )
                .build()

            val bodyStr = try {
                val call = client.newCall(req)
                activeCall = call
                try {
                    call.execute().use { resp ->
                        val raw = resp.body?.string() ?: error("Empty VTON response")
                        if (!resp.isSuccessful) {
                            val err = try {
                                json.parseToJsonElement(raw).jsonObject["error"]?.jsonPrimitive?.content ?: raw
                            } catch (_: Exception) { raw }
                            error("VTON failed (${resp.code}): $err")
                        }
                        raw
                    }
                } finally {
                    if (activeCall === call) activeCall = null
                }
            } catch (e: java.io.IOException) {
                // Timeouts and explicit cancellation surface as cancellation, not as errors.
                if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException("VTON cancelled")
                throw e
            }
            if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException("VTON cancelled")
            val obj = json.parseToJsonElement(bodyStr).jsonObject
            if (obj["error"] != null && obj["error"].toString() != "null") {
                val msg = obj["error"]?.jsonPrimitive?.content ?: obj["error"].toString()
                error("VTON failed: $msg")
            }
            val imageData = obj["image"]?.jsonPrimitive?.content
                ?: error("No image in VTON response: $bodyStr")
            // image is data:image/...;base64,...
            val b64Part = imageData.substringAfter(",", imageData)
            val bytes = Base64.decode(b64Part, Base64.DEFAULT)
            val outFile = File.createTempFile("vton_result_", ".png")
            outFile.writeBytes(bytes)
            outFile
        } finally {
            // No remote state to clean up; the caller owns the result file lifecycle.
        }
    }

    suspend fun saveToGallery(context: Context, file: File, name: String): String =
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AltThread")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: error("Could not create gallery entry")
            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: error("Could not write gallery file")
            uri.toString()
        }
}
