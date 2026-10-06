package com.example.myapplicationkoG

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Base64
import kotlinx.coroutines.Dispatchers
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
    // kept for backwards compat, no longer used directly
    const val BASE_URL = "https://yisol-idm-vton.hf.space"
}

class VtonRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private fun padToRatio(file: File, ratioW: Int = 3, ratioH: Int = 4): File {
        val src = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            ?: return file
        val targetRatio = ratioW.toFloat() / ratioH
        val srcRatio = src.width.toFloat() / src.height
        if (kotlin.math.abs(srcRatio - targetRatio) < 0.01f) return file
        val (dw, dh) = if (srcRatio > targetRatio) {
            src.width to (src.width / targetRatio).toInt()
        } else {
            (src.height * targetRatio).toInt() to src.height
        }
        val out = android.graphics.Bitmap.createBitmap(dw, dh, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawBitmap(
            src,
            null,
            android.graphics.Rect((dw - src.width) / 2, (dh - src.height) / 2, (dw + src.width) / 2, (dh + src.height) / 2),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        )
        if (out !== src) runCatching { if (!src.isRecycled) src.recycle() }
        val tmp = File.createTempFile("vton_pad_", ".jpg")
        tmp.outputStream().use { o ->
            out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, o)
        }
        runCatching { if (!out.isRecycled) out.recycle() }
        return tmp
    }

    private fun fileToBase64(file: File): String {
        val bytes = file.readBytes()
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        // server accepts both with and without data URI prefix; we send with prefix for clarity
        return "data:image/jpeg;base64,$b64"
    }

    suspend fun runTryOn(
        personFile: File,
        garmentFile: File,
        prompt: String = "a photo of a garment"
    ): File = withContext(Dispatchers.IO) {
        val personB64 = fileToBase64(padToRatio(personFile))
        val garmentB64 = fileToBase64(padToRatio(garmentFile))

        val payload = buildJsonObject {
            put("personB64", JsonPrimitive(personB64))
            put("garmentB64", JsonPrimitive(garmentB64))
            put("prompt", JsonPrimitive(prompt))
        }.toString()

        val req = Request.Builder()
            .url(AIConfig.VTON_URL)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("apikey", AIConfig.SUPABASE_ANON_KEY)
            .header("Authorization", "Bearer ${AIConfig.SUPABASE_ANON_KEY}")
            .build()

        val resp = client.newCall(req).execute()
        val bodyStr = resp.body?.string() ?: error("Empty VTON response")
        if (!resp.isSuccessful) {
            val err = try {
                json.parseToJsonElement(bodyStr).jsonObject["error"]?.jsonPrimitive?.content ?: bodyStr
            } catch (_: Exception) { bodyStr }
            error("VTON failed (${resp.code}): $err")
        }
        val obj = json.parseToJsonElement(bodyStr).jsonObject
        if (obj["error"] != null && obj["error"].toString() != "null") {
            val msg = obj["error"]?.jsonPrimitive?.content ?: obj["error"].toString()
            error("VTON failed: $msg")
        }
        val imageData = obj["image"]?.jsonPrimitive?.content
            ?: error("No image in VTON response: $bodyStr")
        // image is data:image/png;base64,...
        val b64Part = imageData.substringAfter(",", imageData)
        val bytes = Base64.decode(b64Part, Base64.DEFAULT)
        val outFile = File.createTempFile("vton_result_", ".png")
        outFile.writeBytes(bytes)
        outFile
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
