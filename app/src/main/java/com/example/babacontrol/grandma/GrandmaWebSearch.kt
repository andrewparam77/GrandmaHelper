package com.example.babacontrol.grandma

import android.content.Context
import android.location.LocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object GrandmaWebSearch {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ===================== WIKIPEDIA =====================
    suspend fun wikipedia(query: String): String? = withContext(Dispatchers.IO) {
        try {
            val clean = query
                .replace("почему ", "")
                .replace("зачем ", "")
                .replace("что такое ", "")
                .replace("кто такой ", "")
                .replace("кто такая ", "")
                .replace("расскажи про ", "")
                .replace("расскажи о ", "")
                .trim()
            if (clean.length < 2) return@withContext null

            val encoded = URLEncoder.encode(clean, "UTF-8")
            val url = "https://ru.wikipedia.org/api/rest_v1/page/summary/" + encoded
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "GrandmaHelper/1.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val txt = resp.body?.string() ?: return@withContext null
                val json = JSONObject(txt)
                val extract = json.optString("extract")
                if (extract.isBlank()) return@withContext null
                extract
            }
        } catch (e: Exception) {
            null
        }
    }

    // ===================== ПОГОДА =====================
    suspend fun weather(context: Context): String? = withContext(Dispatchers.IO) {
        try {
            val loc = getLastLocation(context) ?: return@withContext null
            val lat = loc.first
            val lon = loc.second

            val url = "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=" + lat +
                    "&longitude=" + lon +
                    "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
                    "&daily=temperature_2m_max,temperature_2m_min" +
                    "&timezone=auto" +
                    "&forecast_days=1"

            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val txt = resp.body?.string() ?: return@withContext null
                val json = JSONObject(txt)
                val current = json.optJSONObject("current") ?: return@withContext null
                val temp = current.optDouble("temperature_2m", Double.NaN)
                val feelsLike = current.optDouble("apparent_temperature", Double.NaN)
                val code = current.optInt("weather_code", -1)
                val wind = current.optDouble("wind_speed_10m", Double.NaN)

                val daily = json.optJSONObject("daily")
                val maxT = daily?.optJSONArray("temperature_2m_max")?.optDouble(0, Double.NaN)
                    ?: Double.NaN
                val minT = daily?.optJSONArray("temperature_2m_min")?.optDouble(0, Double.NaN)
                    ?: Double.NaN

                val sb = StringBuilder()
                sb.append("Сейчас ").append(temp.toInt()).append(" градусов, ")
                sb.append(describeWeatherCode(code)).append(". ")
                if (!feelsLike.isNaN() && Math.abs(feelsLike - temp) > 2) {
                    sb.append("Ощущается как ").append(feelsLike.toInt()).append(". ")
                }
                if (!maxT.isNaN() && !minT.isNaN()) {
                    sb.append("Днём от ").append(minT.toInt())
                        .append(" до ").append(maxT.toInt()).append(" градусов. ")
                }
                if (!wind.isNaN() && wind > 5) {
                    sb.append("Ветер ").append(wind.toInt()).append(" метров в секунду.")
                }
                sb.toString().trim()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun describeWeatherCode(code: Int): String {
        return when (code) {
            0 -> "ясно"
            1, 2 -> "малооблачно"
            3 -> "облачно"
            45, 48 -> "туман"
            51, 53, 55 -> "морось"
            61, 63, 65 -> "дождь"
            66, 67 -> "ледяной дождь"
            71, 73, 75 -> "снег"
            77 -> "снежная крупа"
            80, 81, 82 -> "ливень"
            85, 86 -> "снегопад"
            95 -> "гроза"
            96, 99 -> "гроза с градом"
            else -> "непонятная погода"
        }
    }

    private fun getLastLocation(context: Context): Pair<Double, Double>? {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var best: android.location.Location? = null
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            for (provider in providers) {
                if (!lm.isProviderEnabled(provider)) continue
                val l = lm.getLastKnownLocation(provider) ?: continue
                if (best == null || l.time > best!!.time) best = l
            }
            best?.let { it.latitude to it.longitude }
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    // ===================== НОВОСТИ =====================
    suspend fun news(): String? = withContext(Dispatchers.IO) {
        try {
            val url = "https://lenta.ru/rss/news"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "GrandmaHelper/1.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val txt = resp.body?.string() ?: return@withContext null
                val titles = parseRssTitles(txt, 5)
                if (titles.isEmpty()) return@withContext null
                val sb = StringBuilder("Главные новости:\n")
                for ((i, t) in titles.withIndex()) {
                    sb.append(i + 1).append(". ").append(t).append("\n")
                }
                sb.toString().trim()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseRssTitles(xml: String, max: Int): List<String> {
        val result = mutableListOf<String>()
        val regex = Regex("<title><!\\[CDATA\\[(.*?)]]></title>|<title>(.*?)</title>",
            RegexOption.DOT_MATCHES_ALL)
        val matches = regex.findAll(xml)
        for (m in matches) {
            val t = (m.groupValues[1].ifBlank { m.groupValues[2] })
                .replace("\n", " ").replace("\r", " ").trim()
            if (t.isNotBlank() && !t.equals("Lenta.ru : Новости", true) && result.size < max) {
                result.add(t)
            }
        }
        return result
    }

    // ===================== DUCKDUCKGO INSTANT =====================
    suspend fun duckDuckGo(query: String): String? = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.duckduckgo.com/?q=" + encoded +
                    "&format=json&no_html=1&skip_disambig=1"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "GrandmaHelper/1.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val txt = resp.body?.string() ?: return@withContext null
                val json = JSONObject(txt)
                val abstract = json.optString("AbstractText")
                if (abstract.isNotBlank()) return@withContext abstract

                val answer = json.optString("Answer")
                if (answer.isNotBlank()) return@withContext answer

                val definition = json.optString("Definition")
                if (definition.isNotBlank()) return@withContext definition

                null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ===================== УНИВЕРСАЛЬНЫЙ ПОИСК =====================
    suspend fun search(context: Context, question: String): String? {
        val q = question.lowercase()

        // 1. Погода?
        if (q.contains("погод")) {
            val w = weather(context)
            if (w != null) return w
        }

        // 2. Новости?
        if (q.contains("новост") || q.contains("что нового") ||
            q.contains("что случилось")) {
            val n = news()
            if (n != null) return n
        }

        // 3. Wikipedia
        val wiki = wikipedia(question)
        if (wiki != null) return wiki

        // 4. DuckDuckGo
        val ddg = duckDuckGo(question)
        if (ddg != null) return ddg

        return null
    }
}
