package com.example.myapplicationkoG

import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class ChatMessage(
    val role: String,
    val text: String,
    val isUser: Boolean,
    /** Data-URI images (data:image/jpeg;base64,...). Empty = text-only. */
    val images: List<String> = emptyList()
)

@Serializable
private data class OrMessage(val role: String, val content: JsonElement)

@Serializable
private data class SupabaseFunctionRequest(
    val messages: List<OrMessage>,
    val mode: String = "studio"
)

@Serializable
private data class OrChoiceMessage(val content: String? = null)

@Serializable
private data class OrChoice(val message: OrChoiceMessage? = null)

@Serializable
private data class OrResponse(
    val choices: List<OrChoice>? = null,
    val error: OrError? = null
)

@Serializable
private data class OrError(
    val message: String = "",
    val code: String? = null
)

class AiRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Builds OpenRouter content: plain string for text-only, parts array with images otherwise. */
    private fun contentFor(text: String, images: List<String>): JsonElement {
        if (images.isEmpty()) return JsonPrimitive(text)
        return JsonArray(
            listOf(
                buildJsonObject {
                    put("type", "text")
                    put("text", text.ifBlank { "Please look at the attached image(s)." })
                }
            ) + images.take(3).map { uri ->
                buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", uri) })
                }
            }
        )
    }

    suspend fun sendMessage(
        userMessage: String,
        history: List<ChatMessage>,
        images: List<String> = emptyList(),
        mode: String = "studio"
    ): String = withContext(Dispatchers.IO) {
        // The full Thread knowledge base lives server-side (Edge Function system
        // prompt). The client only sends a pointer so history stays cheap.
        val systemPointer = """
            You are Thread. The full AltThread Studio knowledge base (dye palette,
            patches, tools, style guidance) is in your system prompt — use it.
            Answer concisely in the user's language.
        """.trimIndent()

        val messages = mutableListOf<OrMessage>()
        messages.add(OrMessage("system", JsonPrimitive(systemPointer)))
        history.forEach { msg ->
            // Strip images from older turns: only the newest message carries
            // pictures, otherwise every turn re-bills all previous images.
            messages.add(
                OrMessage(if (msg.isUser) "user" else "assistant", contentFor(msg.text, emptyList()))
            )
        }
        messages.add(OrMessage("user", contentFor(userMessage, images)))

        // Model and API key are managed server-side; the client only sends messages.
        val req = SupabaseFunctionRequest(messages = messages, mode = mode)
        val bodyStr = json.encodeToString(SupabaseFunctionRequest.serializer(), req)

        val request = Request.Builder()
            .url(AIConfig.SUPABASE_FUNCTION_URL)
            .post(bodyStr.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            // User identity: Edge Function sees the real caller (RLS + per-user rate limit).
            // All functions enforce JWT: no session = clear login prompt, no wasted call.
            .header(
                "Authorization",
                "Bearer ${supabase.auth.currentSessionOrNull()?.accessToken
                    ?: error("Please log in to chat.")}"
            )
            .header("apikey", AIConfig.SUPABASE_ANON_KEY)
            .build()

        val body = client.newCall(request).execute().use { response ->
            val raw = response.body?.string() ?: error("Empty response from AI")
            if (!response.isSuccessful) {
                val errMsg = try {
                    val errResp = json.decodeFromString<OrResponse>(raw)
                    errResp.error?.message ?: raw
                } catch (_: Exception) {
                    raw
                }
                error("AI API error (${response.code}): $errMsg")
            }
            raw
        }

        val orResp = json.decodeFromString<OrResponse>(body)
        val text = orResp.choices?.firstOrNull()?.message?.content
            ?: error("No content in AI response: $body")
        text
    }
}