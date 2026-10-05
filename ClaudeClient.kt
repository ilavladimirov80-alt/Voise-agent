package com.example.voiceagent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, details: String) : Exception("API $code: $details")

/** Тонкая обёртка над Messages API. Единственное место, которое знает про Anthropic. */
class ClaudeClient(private val prefs: Prefs) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun send(system: String, tools: JSONArray, messages: JSONArray): JSONObject =
        withContext(Dispatchers.IO) {
            val allTools = JSONArray()
            for (i in 0 until tools.length()) allTools.put(tools.get(i))
            if (prefs.webSearch) {
                allTools.put(
                    JSONObject()
                        .put("type", "web_search_20250305")
                        .put("name", "web_search")
                        .put("max_uses", 3)
                )
            }

            val body = JSONObject()
                .put("model", prefs.model)
                .put("max_tokens", 1024)
                .put("system", system)
                .put("tools", allTools)
                .put("messages", messages)

            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", prefs.apiKey)
                .header("anthropic-version", "2023-06-01")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw ApiException(resp.code, text.take(300))
                JSONObject(text)
            }
        }
}
