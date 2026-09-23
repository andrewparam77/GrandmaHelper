package com.example.babacontrol

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.MotionEvent
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

class ControlActivity : AppCompatActivity() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val json = "application/json; charset=utf-8".toMediaType()
    private lateinit var room: String
    private lateinit var screen: ImageView
    private lateinit var info: TextView
    private lateinit var textInput: EditText

    private var lastTs = 0L
    private var imgW = 1
    private var imgH = 1
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var lastTapT = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        room = Prefs.getRoom(this)
        buildUI()
        scope.launch { loop() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF000000.toInt())
        }
        info = TextView(this).apply {
            setTextColor(0xFFAAAAAA.toInt()); textSize = 11f
            setPadding(16, 16, 16, 8); text = "Загрузка…"
        }
        screen = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF000000.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            setOnTouchListener { _, e -> onScreenTouch(e) }
        }

        fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text; textSize = 13f
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(8, 4, 8, 4)
        }
        row1.addView(btn("◀") { cmd("back") })
        row1.addView(btn("🏠") { cmd("home") })
        row1.addView(btn("▢") { cmd("recents") })
        row1.addView(btn("🔔") { cmd("notif") })
        row1.addView(btn("📳") { cmd("vibrate") })
        row1.addView(btn("🚨") { cmd("ring") })

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(8, 4, 8, 8)
        }
        textInput = EditText(this).apply {
            hint = "текст"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF666666.toInt())
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row2.addView(textInput)
        row2.addView(Button(this).apply {
            text = "Ввод"; textSize = 12f
            setOnClickListener { cmd("text", mapOf("text" to textInput.text.toString())) }
        })
        row2.addView(Button(this).apply {
            text = "Сказать"; textSize = 12f
            setOnClickListener { cmd("say", mapOf("text" to textInput.text.toString())) }
        })

        root.addView(info); root.addView(screen)
        root.addView(row1); root.addView(row2)
        setContentView(root)
    }

    private fun onScreenTouch(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; downT = System.currentTimeMillis()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = e.x - downX; val dy = e.y - downY
                val dt = System.currentTimeMillis() - downT

                val vw = screen.width.toFloat(); val vh = screen.height.toFloat()
                val cw = screen.drawable?.intrinsicWidth?.toFloat() ?: vw
                val ch = screen.drawable?.intrinsicHeight?.toFloat() ?: vh
                val scale = minOf(vw / cw, vh / ch)
                val offX = (vw - cw * scale) / 2f
                val offY = (vh - ch * scale) / 2f
                fun rx(x: Float) = ((x - offX) / scale).toInt().coerceIn(0, imgW)
                fun ry(y: Float) = ((y - offY) / scale).toInt().coerceIn(0, imgH)

                if (hypot(dx, dy) < 30f) {
                    val now = System.currentTimeMillis()
                    if (now - lastTapT < 300)
                        cmd("long", mapOf("x" to rx(e.x), "y" to ry(e.y)))
                    else
                        cmd("tap", mapOf("x" to rx(e.x), "y" to ry(e.y)))
                    lastTapT = now
                } else {
                    cmd("swipe", mapOf(
                        "x1" to rx(downX), "y1" to ry(downY),
                        "x2" to rx(e.x), "y2" to ry(e.y),
                        "ms" to dt.coerceIn(150, 800)))
                }
                return true
            }
        }
        return false
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
    } catch (_: Exception) { null }

    private fun sbPut(kind: String, payloadJson: String): Boolean = try {
        val body = """[{"room":"$room","kind":"$kind","payload":$payloadJson}]"""
        val b = Request.Builder().url("${Prefs.SB_URL}/rest/v1/messages")
        sbHeaders().forEach { (k, v) -> b.header(k, v) }
        b.post(body.toRequestBody(json))
        http.newCall(b.build()).execute().use { it.isSuccessful }
    } catch (_: Exception) { false }

    private fun cmd(type: String, extra: Map<String, Any> = emptyMap()) {
        scope.launch(Dispatchers.IO) {
            try {
                val o = JSONObject().apply {
                    put("type", type)
                    put("id", System.currentTimeMillis().toString() + (0..9999).random())
                    extra.forEach { (k, v) -> put(k, v) }
                }
                sbPut("cmd", o.toString())
            } catch (_: Exception) {}
        }
    }

    private suspend fun loop() {
        while (scope.isActive) {
            withContext(Dispatchers.IO) {
                try {
                    val sRaw = sbGet("screen")
                    if (!sRaw.isNullOrEmpty() && sRaw != "null") {
                        val s = JSONObject(sRaw)
                        val ts = s.optLong("ts")
                        if (ts > lastTs) {
                            lastTs = ts
                            imgW = s.optInt("w", 1); imgH = s.optInt("h", 1)
                            val bytes = Base64.decode(s.optString("img"), Base64.DEFAULT)
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            withContext(Dispatchers.Main) {
                                screen.setImageBitmap(bmp)
                            }
                        }
                    }
                    val stRaw = sbGet("status")
                    if (!stRaw.isNullOrEmpty() && stRaw != "null") {
                        val st = JSONObject(stRaw)
                        val ts = st.optLong("ts")
                        val online = System.currentTimeMillis() - ts < 25000
                        val b = st.optInt("battery", -1)
                        val la = st.optDouble("lat", Double.NaN)
                        val lo = st.optDouble("lon", Double.NaN)
                        withContext(Dispatchers.Main) {
                            info.text = buildString {
                                append(if (online) "🟢 онлайн" else "🔴 нет связи")
                                if (b >= 0) append("  •  🔋$b%")
                                if (!la.isNaN()) append("  •  📍%.4f, %.4f".format(la, lo))
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            delay(1400)
        }
    }

    override fun onDestroy() {
        scope.cancel(); super.onDestroy()
    }
}