package com.example.myapplicationkoG

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class ChatMessage(
    val role: String,
    val text: String,
    val isUser: Boolean
)

@Serializable
private data class OrMessage(val role: String, val content: String)

@Serializable
private data class SupabaseFunctionRequest(
    val messages: List<OrMessage>
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

    suspend fun sendMessage(
        userMessage: String,
        history: List<ChatMessage>
    ): String = withContext(Dispatchers.IO) {
        val systemPrompt = """
            You are AltThread AI Stylist, a friendly fashion design assistant inside a garment customization app.
            Help users with color matching, fabric suggestions, style advice, and creative ideas for their clothing designs.
            Keep answers concise, helpful, and fashion-focused. Use a warm, encouraging tone.
        """.trimIndent()

        val messages = mutableListOf<OrMessage>()
        messages.add(OrMessage("system", systemPrompt))
        history.forEach { msg ->
            messages.add(OrMessage(if (msg.isUser) "user" else "assistant", msg.text))
        }
        messages.add(OrMessage("user", userMessage))

        // Only send messages to Edge Function, Model and API Key are managed server-side
        val req = SupabaseFunctionRequest(messages = messages)
        val bodyStr = json.encodeToString(SupabaseFunctionRequest.serializer(), req)

        val request = Request.Builder()
            .url(AIConfig.SUPABASE_FUNCTION_URL)
            .post(bodyStr.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${AIConfig.SUPABASE_ANON_KEY}")
            .header("apikey", AIConfig.SUPABASE_ANON_KEY)
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: error("Empty response from AI")

        if (!response.isSuccessful) {
            val errMsg = try {
                val errResp = json.decodeFromString<OrResponse>(body)
                errResp.error?.message ?: body
            } catch (_: Exception) {
                body
            }
            error("AI API error (${response.code}): $errMsg")
        }

        val orResp = json.decodeFromString<OrResponse>(body)
        val text = orResp.choices?.firstOrNull()?.message?.content
            ?: error("No content in AI response: $body")
        text
    }
}