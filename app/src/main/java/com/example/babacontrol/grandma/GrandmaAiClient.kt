package com.example.babacontrol.grandma

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class GrandmaAiConfig(
    val provider: String,
    val apiKey: String,
    val baseUrl: String = "",
    val model: String = ""
)

object GrandmaAiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun ask(
        context: Context,
        config: GrandmaAiConfig,
        systemPrompt: String,
        history: List<GrandmaMsg>,
        userText: String,
        maxTokens: Int = 900
    ): String = withContext(Dispatchers.IO) {

        // 1. Офлайн-мозг (команды, время, звонки и т.д.)
        when (val brain = GrandmaOfflineBrain.think(context, userText)) {
            is BrainResult.Answer -> return@withContext brain.text

            is BrainResult.NeedWebSearch -> {
                // 2. Веб-поиск через бесплатные API
                val web = GrandmaWebSearch.search(context, brain.query)
                if (web != null) return@withContext web

                // 3. Если веб не помог и есть LLM-ключ — используем LLM
                val online = GrandmaNetworkUtil.isOnline(context)
                if (online && config.provider != "none" && config.apiKey.isNotBlank()) {
                    try {
                        return@withContext callLlm(context, config, systemPrompt, history, userText, maxTokens)
                    } catch (e: Exception) {
                        return@withContext "Не смогла найти ответ. Проверьте интернет."
                    }
                }
                return@withContext "Не смогла найти ответ. Проверьте интернет."
            }

            BrainResult.Unknown -> {
                // Не поняли — пробуем LLM
                val online = GrandmaNetworkUtil.isOnline(context)
                if (online && config.provider != "none" && config.apiKey.isNotBlank()) {
                    try {
                        return@withContext callLlm(context, config, systemPrompt, history, userText, maxTokens)
                    } catch (e: Exception) {
                        return@withContext fallbackUnknown()
                    }
                }
                return@withContext fallbackUnknown()
            }
        }
    }

    private fun fallbackUnknown(): String {
        return "Извините, я не поняла вопрос. Спросите, пожалуйста, по-другому " +
                "или скажите «что ты умеешь» — я расскажу."
    }

    private suspend fun callLlm(
        context: Context,
        config: GrandmaAiConfig,
        systemPrompt: String,
        history: List<GrandmaMsg>,
        userText: String,
        maxTokens: Int
    ): String {
        return when (config.provider) {
            "gemini" -> callGemini(config.apiKey, systemPrompt, history, userText, maxTokens)
            "deepseek" -> callOpenAiCompat(
                "https://api.deepseek.com/v1",
                config.apiKey,
                config.model.ifBlank { "deepseek-chat" },
                systemPrompt, history, userText, maxTokens, "DeepSeek"
            )
            "claude" -> callClaude(config, systemPrompt, history, userText, maxTokens)
            "custom" -> {
                val base = config.baseUrl.trim().trimEnd('/')
                if (base.isBlank()) throw IOException("Укажите адрес сервера")
                callOpenAiCompat(
                    base,
                    config.apiKey,
                    config.model.ifBlank { "gpt-3.5-turbo" },
                    systemPrompt, history, userText, maxTokens, "Своя нейросеть"
                )
            }
            else -> fallbackUnknown()
        }
    }

    // ==================== GEMINI ====================
    private fun callGemini(
        apiKey: String, systemPrompt: String,
        history: List<GrandmaMsg>, userText: String, maxTokens: Int
    ): String {
        if (apiKey.isBlank()) throw IOException("Нет ключа Gemini")
        val model = "gemini-flash-latest"
        val contents = JSONArray()
        for (m in history) {
            val parts = JSONArray().apply { put(JSONObject().put("text", m.text)) }
            contents.put(JSONObject().apply {
                put("role", if (m.isUser) "user" else "model")
                put("parts", parts)
            })
        }
        contents.put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", userText)))
        })
        val body = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            })
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.75)
                put("maxOutputTokens", maxTokens)
            })
        }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
                model + ":generateContent?key=" + apiKey
        val req = Request.Builder().url(url)
            .post(body.toString().toRequestBody(jsonType)).build()
        val resp = client.newCall(req).execute()
        val txt = resp.body?.string() ?: ""
        if (!resp.isSuccessful) { resp.close(); throw IOException("Gemini: " + resp.code) }
        resp.close()
        val json = JSONObject(txt)
        val candidates = json.optJSONArray("candidates") ?: throw IOException("Пусто")
        val first = candidates.optJSONObject(0) ?: throw IOException("Пусто")
        val content = first.optJSONObject("content") ?: throw IOException("Пусто")
        val parts = content.optJSONArray("parts") ?: throw IOException("Пусто")
        val text = parts.optJSONObject(0)?.optString("text") ?: ""
        return if (text.isBlank()) "Извините, не смогла ответить." else text.trim()
    }

    // ==================== OpenAI-совместимые ====================
    private fun callOpenAiCompat(
        baseUrl: String, apiKey: String, model: String,
        systemPrompt: String, history: List<GrandmaMsg>, userText: String,
        maxTokens: Int, providerName: String
    ): String {
        if (apiKey.isBlank()) throw IOException("Нет ключа " + providerName)
        val messages = JSONArray()
        messages.put(JSONObject().apply {
            put("role", "system"); put("content", systemPrompt)
        })
        for (m in history) {
            messages.put(JSONObject().apply {
                put("role", if (m.isUser) "user" else "assistant")
                put("content", m.text)
            })
        }
        messages.put(JSONObject().apply {
            put("role", "user"); put("content", userText)
        })
        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.75)
            put("max_tokens", maxTokens)
            put("stream", false)
        }
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val req = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer " + apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonType)).build()
        val resp = client.newCall(req).execute()
        val txt = resp.body?.string() ?: ""
        if (!resp.isSuccessful) { resp.close(); throw IOException(providerName + ": " + resp.code) }
        resp.close()
        val json = JSONObject(txt)
        val choices = json.optJSONArray("choices") ?: throw IOException("Пусто")
        val first = choices.optJSONObject(0) ?: throw IOException("Пусто")
        val msg = first.optJSONObject("message") ?: throw IOException("Пусто")
        val text = msg.optString("content")
        return if (text.isBlank()) "Извините, не смогла ответить." else text.trim()
    }

    // ==================== Claude ====================
    private fun callClaude(
        config: GrandmaAiConfig, systemPrompt: String,
        history: List<GrandmaMsg>, userText: String, maxTokens: Int
    ): String {
        if (config.apiKey.isBlank()) throw IOException("Нет ключа Claude")
        val model = config.model.ifBlank { "claude-3-5-haiku-latest" }
        val messages = JSONArray()
        for (m in history) {
            messages.put(JSONObject().apply {
                put("role", if (m.isUser) "user" else "assistant")
                put("content", m.text)
            })
        }
        messages.put(JSONObject().apply {
            put("role", "user"); put("content", userText)
        })
        val body = JSONObject().apply {
            put("model", model); put("max_tokens", maxTokens)
            put("temperature", 0.75); put("system", systemPrompt)
            put("messages", messages)
        }
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", config.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonType)).build()
        val resp = client.newCall(req).execute()
        val txt = resp.body?.string() ?: ""
        if (!resp.isSuccessful) { resp.close(); throw IOException("Claude: " + resp.code) }
        resp.close()
        val json = JSONObject(txt)
        val content = json.optJSONArray("content") ?: throw IOException("Пусто")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val b = content.optJSONObject(i) ?: continue
            if (b.optString("type") == "text") sb.append(b.optString("text"))
        }
        return sb.toString().trim().ifBlank { "Извините, не смогла ответить." }
    }
}
