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
        val online = GrandmaNetworkUtil.isOnline(context)
        val effective = if (online) config.provider else "none"

        try {
            return@withContext when (effective) {
                "gemini" -> callGemini(config.apiKey, systemPrompt, history, userText, maxTokens)
                "deepseek" -> callOpenAiCompat(
                    "https://api.deepseek.com/v1",
                    config.apiKey,
                    config.model.ifBlank { "deepseek-chat" },
                    systemPrompt,
                    history,
                    userText,
                    maxTokens,
                    "DeepSeek"
                )
                "claude" -> callClaude(config, systemPrompt, history, userText, maxTokens)
                "custom" -> {
                    val base = config.baseUrl.trim().trimEnd('/')
                    if (base.isBlank()) throw IOException("Укажите адрес сервера")
                    callOpenAiCompat(
                        base,
                        config.apiKey,
                        config.model.ifBlank { "gpt-3.5-turbo" },
                        systemPrompt,
                        history,
                        userText,
                        maxTokens,
                        "Своя нейросеть"
                    )
                }
                else -> offlineAnswer(userText)
            }
        } catch (e: IOException) {
            if (isNetworkError(e)) {
                offlineAnswer(userText) + "\n\n(Связь пропала, отвечаю в упрощённом режиме)"
            } else {
                throw e
            }
        }
    }

    private fun isNetworkError(e: IOException): Boolean {
        if (e is UnknownHostException) return true
        if (e is SocketTimeoutException) return true
        val msg = e.message ?: return false
        if (msg.contains("timeout", ignoreCase = true)) return true
        if (msg.contains("unable to resolve", ignoreCase = true)) return true
        if (msg.contains("failed to connect", ignoreCase = true)) return true
        return false
    }

    private fun callGemini(
        apiKey: String,
        systemPrompt: String,
        history: List<GrandmaMsg>,
        userText: String,
        maxTokens: Int
    ): String {
        if (apiKey.isBlank()) throw IOException("Нет ключа Gemini")

        val model = "gemini-2.0-flash"
        val contents = JSONArray()

        for (m in history) {
            val parts = JSONArray()
            val part = JSONObject()
            part.put("text", m.text)
            parts.put(part)

            val entry = JSONObject()
            entry.put("role", if (m.isUser) "user" else "model")
            entry.put("parts", parts)
            contents.put(entry)
        }

        val userParts = JSONArray()
        val userPart = JSONObject()
        userPart.put("text", userText)
        userParts.put(userPart)

        val userEntry = JSONObject()
        userEntry.put("role", "user")
        userEntry.put("parts", userParts)
        contents.put(userEntry)

        val sysParts = JSONArray()
        val sysPart = JSONObject()
        sysPart.put("text", systemPrompt)
        sysParts.put(sysPart)

        val systemInstruction = JSONObject()
        systemInstruction.put("parts", sysParts)

        val genConfig = JSONObject()
        genConfig.put("temperature", 0.75)
        genConfig.put("maxOutputTokens", maxTokens)

        val safety = JSONArray()
        val cats = listOf(
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_DANGEROUS_CONTENT"
        )
        for (cat in cats) {
            val s = JSONObject()
            s.put("category", cat)
            s.put("threshold", "BLOCK_ONLY_HIGH")
            safety.put(s)
        }

        val body = JSONObject()
        body.put("systemInstruction", systemInstruction)
        body.put("contents", contents)
        body.put("generationConfig", genConfig)
        body.put("safetySettings", safety)

        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
                model + ":generateContent?key=" + apiKey

        val req = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(jsonType))
            .build()

        val resp = client.newCall(req).execute()
        val txt = resp.body?.string() ?: ""
        if (!resp.isSuccessful) {
            resp.close()
            throw IOException(humanError("Gemini", resp.code, txt))
        }
        resp.close()

        val json = JSONObject(txt)
        val candidates = json.optJSONArray("candidates") ?: throw IOException("Пустой ответ")
        val first = candidates.optJSONObject(0) ?: throw IOException("Пустой ответ")
        val content = first.optJSONObject("content") ?: throw IOException("Пустой ответ")
        val parts = content.optJSONArray("parts") ?: throw IOException("Пустой ответ")
        val text = parts.optJSONObject(0)?.optString("text") ?: ""

        if (text.isBlank()) {
            return "Извините, не смогла ответить."
        }
        return text.trim()
    }

    private fun callOpenAiCompat(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<GrandmaMsg>,
        userText: String,
        maxTokens: Int,
        providerName: String
    ): String {
        if (apiKey.isBlank()) throw IOException("Нет ключа " + providerName)

        val messages = JSONArray()

        val sysMsg = JSONObject()
        sysMsg.put("role", "system")
        sysMsg.put("content", systemPrompt)
        messages.put(sysMsg)

        for (m in history) {
            val msg = JSONObject()
            msg.put("role", if (m.isUser) "user" else "assistant")
            msg.put("content", m.text)
            messages.put(msg)
        }

        val userMsg = JSONObject()
        userMsg.put("role", "user")
        userMsg.put("content", userText)
        messages.put(userMsg)

        val body = JSONObject()
        body.put("model", model)
        body.put("messages", messages)
        body.put("temperature", 0.75)
        body.put("max_tokens", maxTokens)
        body.put("stream", false)

        val url = baseUrl.trimEnd('/') + "/chat/completions"

        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer " + apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonType))
            .build()

        val resp = client.newCall(req).execute()
        val txt = resp.body?.string() ?: ""
        if (!resp.isSuccessful) {
            resp.close()
            throw IOException(humanError(providerName, resp.code, txt))
        }
        resp.close()

        val json = JSONObject(txt)
        val choices = json.optJSONArray("choices") ?: throw IOException("Пустой ответ")
        val first = choices.optJSONObject(0) ?: throw IOException("Пустой ответ")
        val msg = first.optJSONObject("message") ?: throw IOException("Пустой ответ")
        val text = msg.optString("content")

        if (text.isBlank()) {
            return "Извините, не смогла ответить."
        }
        return text.trim()
    }

    private fun callClaude(
        config: GrandmaAiConfig,
        systemPrompt: String,
        history: List<GrandmaMsg>,
        userText: String,
        maxTokens: Int
    ): String {
        if (config.apiKey.isBlank()) throw IOException("Нет ключа Claude")

        val model = config.model.ifBlank { "claude-3-5-haiku-latest" }
        val messages = JSONArray()

        for (m in history) {
            val msg = JSONObject()
            msg.put("role", if (m.isUser) "user" else "assistant")
            msg.put("content", m.text)
            messages.put(msg)
        }

        val userMsg = JSONObject()
        userMsg.put("role", "user")
        userMsg.put("content", userText)
        messages.put(userMsg)

        val body = JSONObject()
        body.put("model", model)
        body.put("max_tokens", maxTokens)
        body.put("temperature", 0.75)
        body.put("system", systemPrompt)
        body.put("messages", messages)

        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", config.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonType))
            .build()

        val resp = client.newCall(req).execute()
        val txt = resp.body?.string() ?: ""
        if (!resp.isSuccessful) {
            resp.close()
            throw IOException(humanError("Claude", resp.code, txt))
        }
        resp.close()

        val json = JSONObject(txt)
        val content = json.optJSONArray("content") ?: throw IOException("Пустой ответ")

        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") {
                sb.append(block.optString("text"))
            }
        }

        val result = sb.toString().trim()
        if (result.isBlank()) {
            return "Извините, не смогла ответить."
        }
        return result
    }

    private fun offlineAnswer(userText: String): String {
        val q = userText.lowercase(Locale("ru", "RU")).trim()
        val time = SimpleDateFormat("HH:mm", Locale("ru", "RU")).format(Date())
        val date = SimpleDateFormat("d MMMM yyyy", Locale("ru", "RU")).format(Date())
        val weekday = SimpleDateFormat("EEEE", Locale("ru", "RU")).format(Date())

        if (q.contains("привет") || q.contains("здравств") || q.contains("добрый")) {
            return "Здравствуйте! Сейчас я без интернета и могу подсказать только время и дату."
        }
        if (q.contains("который час") || q.contains("сколько время") || q.contains("время")) {
            return "Сейчас " + time + "."
        }
        if (q.contains("какое число") || q.contains("какой день") ||
            q.contains("какая дата") || q.contains("дата")) {
            return "Сегодня " + weekday + ", " + date + "."
        }
        if (q.contains("как дела")) {
            return "У меня всё хорошо, спасибо! А у вас как дела?"
        }
        if (q.contains("спасибо") || q.contains("благодар")) {
            return "Пожалуйста, всегда рада помочь!"
        }
        if (q.contains("пока") || q.contains("до свидания")) {
            return "До свидания! Хорошего дня!"
        }
        if (q.contains("как тебя зовут") || q.contains("кто ты")) {
            return "Я ваш голосовой помощник. Сейчас без интернета."
        }
        return "Извините, без интернета я могу ответить только про время и дату. Проверьте, включён ли Wi-Fi."
    }

    private fun humanError(provider: String, code: Int, body: String): String {
        val shortBody = body.take(200).replace("\n", " ")
        return when (code) {
            400 -> provider + ": неверный запрос. Проверьте ключ и модель."
            401 -> provider + ": ключ не подходит."
            403 -> provider + ": доступ запрещён. Ключ активирован?"
            404 -> provider + ": модель не найдена."
            429 -> provider + ": слишком много запросов, подождите минуту."
            else -> {
                if (code in 500..599) {
                    provider + ": сервер недоступен, попробуйте позже."
                } else {
                    provider + ": ошибка " + code + ". " + shortBody
                }
            }
        }
    }
}
