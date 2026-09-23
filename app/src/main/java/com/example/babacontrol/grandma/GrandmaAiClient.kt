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
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class GrandmaAiConfig(
    val provider: String,          // "gemini" | "deepseek" | "claude" | "custom" | "none"
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
        val online = GrandmaNetworkUtil.isOnline(context)
        val effective = if (online) config.provider else "none"

        try {
            when (effective) {
                "gemini" -> callGemini(config.apiKey, systemPrompt, history, userText, maxTokens)
                "deepseek" -> callOpenAiCompat(
                    baseUrl = "https://api.deepseek.com/v1",
                    apiKey = config.apiKey,
                    model = config.model.ifBlank { "deepseek-chat" },
                    systemPrompt = systemPrompt,
                    history = history, userText = userText,
                    maxTokens = maxTokens, providerName = "DeepSeek"
                )
                "claude" -> callClaude(config, systemPrompt, history, userText, maxTokens)
                "custom" -> callOpenAiCompat(
                    baseUrl = config.baseUrl.trim().trimEnd('/')
                        .ifBlank { throw IOException("Укажите адрес сервера") },
                    apiKey = config.apiKey,
                    model = config.model.ifBlank { "gpt-3.5-turbo" },
                    systemPrompt = systemPrompt,
                    history = history, userText = userText,
                    maxTokens = maxTokens, providerName = "Своя нейросеть"
                )
                else -> offlineAnswer(userText)
            }
        } catch (e: IOException) {
            if (isNetworkError(e)) {
                offlineAnswer(userText) + "\n\n(Связь пропала, отвечаю в упрощённом режиме)"
            } else throw e
        }
    }

    private fun isNetworkError(e: IOException): Boolean =
        e is UnknownHostException || e is SocketTimeoutException ||
        (e.message?.contains("timeout", true) == true) ||
        (e.message?.contains("unable to resolve", true) == true) ||
        (e.message?.contains("failed to connect", true) == true)

    private fun callGemini(
        apiKey: String, systemPrompt: String,
        history: List<GrandmaMsg>, userText: String, maxTokens: Int
    ): String {
        if (apiKey.isBlank()) throw IOException("Нет ключа Gemini")
        val model = "gemini-2.0-flash"
        val contents = JSONArray()
        for (m in history) {
            contents.put(JSONObject().apply {
                put("role", if (m.isUser) "user" else "model")
                put("parts", JSONArray().put(JSONObject().put("text", m.text)))
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
            val safety = JSONArray()
            listOf(
                "HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH",
                "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT"
            ).forEach { cat ->
                safety.put(JSONObject().apply {
                    put("category", cat)
                    put("threshold", "BLOCK_ONLY_HIGH")
                })
            }
            put("safetySettings", safety)
        }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val req = Request.Builder().url(url).post(body.toString().toRequestBody(jsonType)).build()
        client.newCall(req).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException(humanError("Gemini", resp.code, txt))
            val json = JSONObject(txt)
            val candidates = json.optJSONArray("candidates") ?: throw IOException("Пустой ответ")
            val first = candidates.optJSONObject(0) ?: throw IOException("Пустой ответ")
            val content = first.optJSONObject("content") ?: throw IOException("Пустой ответ")
            val parts = content.optJSONArray("parts") ?: throw IOException("Пустой ответ")
            val text = parts.optJSONObject(0)?.optString("text").orEmpty()
            if (text.isBlank()) "Извините, не смогла ответить." else text.trim()
        }
    }

    private fun callOpenAiCompat(
        baseUrl: String, apiKey: String, model: String,
        systemPrompt: String, history: List<GrandmaMsg>, userText: String,
        maxTokens: Int, providerName: String
    ): String {
        if (apiKey.isBlank()) throw IOException("Нет ключа $providerName")
        val messages = JSONArray()
        messages.put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
        for (m in history) {
            messages.put(JSONObject().apply {
                put("role", if (m.isUser) "user" else "assistant")
                put("content", m.text)
            })
        }
        messages.put(JSONObject().apply { put("role", "user"); put("content", userText) })

        val body = JSONObject().apply {
            put("model", model); put("messages", messages)
            put("temperature", 0.75); put("max_tokens", maxTokens); put("stream", false)
        }
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException(humanError(providerName, resp.code, txt))
            val json = JSONObject(txt)
            val choices = json.optJSONArray("choices") ?: throw IOException("Пустой ответ")
            val first = choices.optJSONObject(0) ?: throw IOException("Пустой ответ")
            val msg = first.optJSONObject("message") ?: throw IOException("Пустой ответ")
            val text = msg.optString("content").orEmpty()
            if (text.isBlank()) "Извините, не смогла ответить." else text.trim()
        }
    }

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
        messages.put(JSONObject().apply { put("role", "user"); put("content", userText) })

        val body = JSONObject().apply {
            put("model", model); put("max_tokens", maxTokens)
            put("temperature", 0.75); put("system", systemPrompt); put("messages", messages)
        }
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", config.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException(humanError("Claude", resp.code, txt))
            val json = JSONObject(txt)
            val content = json.optJSONArray("content") ?: throw IOException("Пустой ответ")
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                if (block.optString("type") == "text") sb.append(block.optString("text"))
            }
            sb.toString().trim().ifBlank { "Извините, не смогла ответить." }
        }
    }

    private fun offlineAnswer(userText: String): String {
        val q = userText.lowercase(Locale("ru", "RU")).trim()
        val time = SimpleDateFormat("HH:mm", Locale("ru", "RU")).format(Date())
        val date = SimpleDateFormat("d MMMM yyyy", Locale("ru", "RU")).format(Date())
        val weekday = SimpleDateFormat("EEEE", Locale("ru", "RU")).format(Date())
        return when {
            q.contains("привет") || q.contains("здравств") || q.contains("добрый") ->
                "Здравствуйте! Сейчас я без интернета и могу подсказать только время и дату."
            q.contains("который час") || q.contains("сколько время") || q.contains("время") ->
                "Сейчас $time."
            q.contains("какое число") || q.contains("какой день") || q.contains("какая дата") || q.contains("дата") ->
                "Сегодня $weekday, $date."
            q.contains("как дела") -> "У меня всё хорошо, спасибо! А у вас как дела?"
            q.contains("спасибо") || q.contains("благодар") -> "Пожалуйста, всегда рада помочь!"
            q.contains("пока") || q.contains("до свидания") -> "До свидания! Хорошего дня!"
            q.contains("как тебя зовут") || q.contains("кто ты") ->
                "Я ваш голосовой помощник. Сейчас без интернета."
            else ->
                "Извините, без интернета я могу ответить только про время и дату. Проверьте, включён ли Wi-Fi."
        }
    }

    private fun humanError(provider: String, code: Int, body: String): String {
        val shortBody = body.take(200).replace("\n", " ")
        return when (code) {
            400 -> "$provider: неверный запрос. Проверьте ключ и модель."
            401 -> "$provider: ключ не подходит."
            403 -> "$provider: доступ запрещён. Ключ активирован?"
            404 -> "$provider: модель не найдена."
            429 -> "$provider: слишком много запросов, подождите минуту."
            in 500..599 -> "$provider: сервер недоступен, попробуйте позже."
            else -> "$provider: ошибка $code. $shortBody"
        }
    }
}