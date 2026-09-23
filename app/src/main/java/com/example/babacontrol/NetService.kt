package com.example.babacontrol

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.location.LocationManager
import android.os.*
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

class NetService : Service() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = "application/json; charset=utf-8".toMediaType()
    private var tts: TextToSpeech? = null
    private var lastCmdId: String? = null
    private var lastScreenTs = 0L
    private lateinit var room: String

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        room = Prefs.getRoom(this)
        createChannel()
        startForeground(42, buildNotif("Запуск…"))
        initTts()
        scope.launch { loopCommands() }
        scope.launch { loopStatus() }
        scope.launch { loopScreenshots() }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel("baba_ch",
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotif(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "baba_ch")
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun initTts() {
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) tts?.language = Locale("ru", "RU")
        }
    }

    // ---------- SUPABASE ----------

    private fun sbHeaders(): Map<String, String> = mapOf(
        "apikey" to Prefs.SB_KEY,
        "Authorization" to "Bearer ${Prefs.SB_KEY}",
        "Content-Type" to "application/json",
        "Prefer" to "resolution=merge-duplicates"
    )

    private fun sbGet(kind: String): String? = try {
        val url = "${Prefs.SB_URL}/rest/v1/messages" +
                "?room=eq.$room&kind=eq.$kind&select=payload&limit=1"
        val b = Request.Builder().url(url)
        sbHeaders().forEach { (k, v) -> b.header(k, v) }
        http.newCall(b.build()).execute().use { resp ->
            val s = resp.body?.string()
            if (s.isNullOrBlank() || s == "[]") null
            else JSONArray(s).getJSONObject(0).getJSONObject("payload").toString()
        }
    } catch (e: Exception) { null }

    private fun sbPut(kind: String, payload: String): Boolean = try {
        val body = """[{"room":"$room","kind":"$kind","payload":$payload}]"""
        val b = Request.Builder().url("${Prefs.SB_URL}/rest/v1/messages")
        sbHeaders().forEach { (k, v) -> b.header(k, v) }
        b.post(body.toRequestBody(json))
        http.newCall(b.build()).execute().use { it.isSuccessful }
    } catch (e: Exception) { false }

    // ---------- ЦИКЛЫ ----------

    private suspend fun loopCommands() {
        while (scope.isActive) {
            try {
                val raw = sbGet("cmd")
                if (!raw.isNullOrEmpty() && raw != "null") {
                    val o = JSONObject(raw)
                    val id = o.optString("id")
                    if (id.isNotEmpty() && id != lastCmdId) {
                        lastCmdId = id
                        execute(o)
                    }
                }
            } catch (e: Exception) { Log.w("Net", "cmd: ${e.message}") }
            delay(1000)
        }
    }

    private fun execute(o: JSONObject) {
        val t = o.optString("type")
        val s = TapService.instance
        when (t) {
            "tap" -> s?.tap(o.optDouble("x").toFloat(), o.optDouble("y").toFloat())
            "long" -> s?.longPress(o.optDouble("x").toFloat(), o.optDouble("y").toFloat())
            "swipe" -> s?.swipe(
                o.optDouble("x1").toFloat(), o.optDouble("y1").toFloat(),
                o.optDouble("x2").toFloat(), o.optDouble("y2").toFloat(),
                o.optLong("ms", 300))
            "back" -> s?.back()
            "home" -> s?.home()
            "recents" -> s?.recents()
            "notif" -> s?.notifications()
            "text" -> s?.setText(o.optString("text"))
            "say" -> speak(o.optString("text"))
            "vibrate" -> vibrate()
            "ring" -> ring()
            "screenNow" -> scope.launch { uploadScreenshot() }
        }
    }

    private fun speak(text: String) {
        if (text.isBlank()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "baba")
    }

    private fun vibrate() {
        val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
        val pattern = longArrayOf(400, 200, 400, 200, 400)
        if (Build.VERSION.SDK_INT >= 26)
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        else @Suppress("DEPRECATION") v.vibrate(pattern, -1)
    }

    private fun ring() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM)
            val old = am.getStreamVolume(android.media.AudioManager.STREAM_ALARM)
            am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, max, 0)
            val uri = android.media.RingtoneManager.getDefaultUri(
                android.media.RingtoneManager.TYPE_ALARM)
            val r = android.media.RingtoneManager.getRingtone(this, uri)
            r.play()
            Handler(Looper.getMainLooper()).postDelayed({
                r.stop()
                am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, old, 0)
            }, 6000)
        } catch (_: Exception) {}
    }

    private suspend fun loopStatus() {
        while (scope.isActive) {
            try {
                val o = JSONObject().apply {
                    put("ts", System.currentTimeMillis())
                    val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
                    put("battery", bm.getIntProperty(
                        BatteryManager.BATTERY_PROPERTY_CAPACITY))
                    lastLocation()?.let { (la, lo) ->
                        put("lat", la); put("lon", lo)
                    }
                }
                sbPut("status", o.toString())
            } catch (_: Exception) {}
            delay(15000)
        }
    }

    private fun lastLocation(): Pair<Double, Double>? = try {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        var best: android.location.Location? = null
        for (p in listOf(LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER)) {
            if (!lm.isProviderEnabled(p)) continue
            val l = lm.getLastKnownLocation(p) ?: continue
            if (best == null || l.time > best!!.time) best = l
        }
        best?.let { it.latitude to it.longitude }
    } catch (_: SecurityException) { null }

    private suspend fun loopScreenshots() {
        while (scope.isActive) {
            uploadScreenshot()
            delay(1500)
        }
    }

    private fun uploadScreenshot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val svc = TapService.instance ?: return
        if (System.currentTimeMillis() - lastScreenTs < 700) return
        lastScreenTs = System.currentTimeMillis()

        svc.screenshot { bmp ->
            if (bmp == null) return@screenshot
            scope.launch {
                try {
                    val scaled = scale(bmp, 720)
                    val bos = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 40, bos)
                    val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                    val payload = JSONObject().apply {
                        put("ts", System.currentTimeMillis())
                        put("w", scaled.width); put("h", scaled.height)
                        put("img", b64)
                    }
                    sbPut("screen", payload.toString())
                } catch (_: Exception) {}
            }
        }
    }

    private fun scale(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width; val h = src.height
        if (w <= maxDim && h <= maxDim) return src
        val k = maxDim.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(src, (w*k).toInt(), (h*k).toInt(), true)
    }

    override fun onDestroy() {
        scope.cancel(); tts?.shutdown()
        super.onDestroy()
    }
}