package com.samaira.assistant

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Talks to any OpenAI-compatible chat completions endpoint
 * (OpenAI, OpenRouter, Groq, Gemini's OpenAI endpoint, a local server, etc.)
 */
class LlmClient(
    private val url: String,
    private val apiKey: String,
    private val model: String
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json".toMediaType()

    /** Sends the conversation + tools, returns the assistant "message" object. */
    fun send(messages: JSONArray, tools: JSONArray): JSONObject {
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("tools", tools)
            .put("tool_choice", "auto")

        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonType))
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: throw IOException("Empty response from server")
            if (!resp.isSuccessful) {
                throw IOException("API error ${resp.code}: $text")
            }
            val json = JSONObject(text)
            return json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
        }
    }
}
