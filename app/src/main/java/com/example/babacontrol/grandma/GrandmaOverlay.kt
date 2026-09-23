package com.example.babacontrol.grandma

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.babacontrol.MainActivity
import com.example.babacontrol.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

class GrandmaOverlay : Service() {

    private lateinit var wm: WindowManager
    private var bubbleView: View? = null
    private var panelView: View? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var panelParams: WindowManager.LayoutParams

    private var tts: TextToSpeech? = null
    private var voice: GrandmaVoice? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var isPanelOpen = false
    private var isListening = false
    private var isLoading = false

    private var answerText: TextView? = null
    private var statusText: TextView? = null
    private var micButton: TextView? = null

    private val memory by lazy { GrandmaMemory(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForegroundInternal()
        initTts()
        initVoice()
        createBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        try { voice?.destroy() } catch (_: Exception) {}
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        bubbleView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panelView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubbleView = null; panelView = null
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ru", "RU")
                tts?.setSpeechRate(0.95f)
                tts?.setPitch(1.05f)
            }
        }
    }

    private fun initVoice() {
        voice = GrandmaVoice(this).apply {
            onListeningChanged = { listening ->
                isListening = listening
                micButton?.text = if (listening) "⏹ Стоп" else "🎤 Спросить"
                statusText?.text = if (listening) "Слушаю..." else ""
            }
            onResult = { text -> handleQuestion(text) }
            onError = { err ->
                statusText?.text = err
                isListening = false
                micButton?.text = "🎤 Спросить"
            }
        }
    }

    private fun createBubble() {
        val size = dp(64)
        val view = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#7C3AED"))
                setStroke(dp(2), Color.parseColor("#5B21B6"))
            }
            elevation = dp(8).toFloat()
        }
        val icon = TextView(this).apply {
            text = "💡"
            textSize = 30f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        view.addView(icon)

        bubbleParams = WindowManager.LayoutParams(
            size, size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(10)
            y = dp(120)
        }

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        view.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = bubbleParams.x
                    initialY = bubbleParams.y
                    touchX = ev.rawX
                    touchY = ev.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - touchX).toInt()
                    val dy = (ev.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) moved = true
                    bubbleParams.x = initialX + dx
                    bubbleParams.y = initialY + dy
                    try { wm.updateViewLayout(view, bubbleParams) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) togglePanel()
                    true
                }
                else -> false
            }
        }

        wm.addView(view, bubbleParams)
        bubbleView = view
    }

    private fun togglePanel() {
        if (isPanelOpen) closePanel() else openPanel()
    }

    private fun openPanel() {
        if (isPanelOpen) return
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(2), Color.parseColor("#7C3AED"))
            }
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(ctx).apply {
            text = "💡 Помощник"
            textSize = 16f
            setTextColor(Color.parseColor("#5B21B6"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val minimizeBtn = TextView(ctx).apply {
            text = "—"
            textSize = 22f
            setTextColor(Color.parseColor("#5B21B6"))
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { closePanel() }
        }
        val exitBtn = TextView(ctx).apply {
            text = "✕"
            textSize = 20f
            setTextColor(Color.parseColor("#B91C1C"))
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { confirmExit() }
        }
        header.addView(title)
        header.addView(minimizeBtn)
        header.addView(exitBtn)
        root.addView(header)

        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(8); bottomMargin = dp(8) }
        }
        answerText = TextView(ctx).apply {
            text = "Нажмите «🎤 Спросить» и говорите.\nНапример: «Который час?» или «Куда нажать, чтобы позвонить сыну?»"
            textSize = 16f
            setTextColor(Color.parseColor("#111827"))
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        scroll.addView(answerText)
        root.addView(scroll)

        statusText = TextView(ctx).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#6B7280"))
        }
        root.addView(statusText)

        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        micButton = TextView(ctx).apply {
            text = "🎤 Спросить"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(30).toFloat()
                setColor(Color.parseColor("#7C3AED"))
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onMicClick() }
        }
        buttons.addView(micButton)
        root.addView(buttons)

        val widthPx = (resources.displayMetrics.widthPixels * 0.9f).toInt().coerceAtMost(dp(400))
        val heightPx = dp(460)
        panelParams = WindowManager.LayoutParams(
            widthPx, heightPx,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(100)
        }

        var pX = 0
        var pY = 0
        var tX = 0f
        var tY = 0f
        header.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    pX = panelParams.x
                    pY = panelParams.y
                    tX = ev.rawX
                    tY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    panelParams.x = pX + (ev.rawX - tX).toInt()
                    panelParams.y = pY + (ev.rawY - tY).toInt()
                    try { wm.updateViewLayout(root, panelParams) } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }

        wm.addView(root, panelParams)
        panelView = root
        isPanelOpen = true
        bubbleView?.visibility = View.GONE
    }

    private fun closePanel() {
        if (!isPanelOpen) return
        panelView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panelView = null
        isPanelOpen = false
        bubbleView?.visibility = View.VISIBLE
    }

    private fun confirmExit() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Закрыть помощника?")
            .setMessage("Кружок исчезнет с экрана. Чтобы вернуть — откройте приложение «Помощь».")
            .setPositiveButton("Закрыть") { _, _ -> stopSelf() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun onMicClick() {
        val v = voice ?: return
        if (isListening) {
            v.stop()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            statusText?.text = "Разрешите микрофон в настройках телефона"
            return
        }
        answerText?.text = ""
        statusText?.text = ""
        v.start()
    }

    private fun handleQuestion(text: String) {
        if (isLoading) return
        if (text.isBlank()) return

        val provider = Prefs.getProvider(this)
        val config = buildConfig(provider)

        val needsScreen = looksLikeHowToQuestion(text)
        val screenText = if (needsScreen && GrandmaScreenReader.isRunning()) {
            GrandmaScreenReader.readCurrentScreen()
        } else ""

        isLoading = true
        statusText?.text = "Думаю..."
        micButton?.text = "🎤 Спросить"

        scope.launch {
            try {
                val facts = memory.loadFacts()
                val history = memory.load().takeLast(20)
                val name = Prefs.getGrandmaName(this@GrandmaOverlay)
                val systemPrompt = buildOverlayPrompt(name, facts, screenText)
                val answer = GrandmaAiClient.ask(this@GrandmaOverlay, config, systemPrompt, history, text)

                answerText?.text = answer
                tts?.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "ov_${System.currentTimeMillis()}")

                val updated = memory.load().toMutableList().apply {
                    add(GrandmaMsg(text, true)); add(GrandmaMsg(answer, false))
                }
                memory.save(updated)
            } catch (e: Exception) {
                statusText?.text = "Ошибка: ${e.message}"
            } finally {
                isLoading = false
                if (statusText?.text?.startsWith("Ошибка") != true) statusText?.text = ""
            }
        }
    }

    private fun looksLikeHowToQuestion(text: String): Boolean {
        val q = text.lowercase(Locale("ru", "RU"))
        return listOf(
            "куда нажать", "куда тыкнуть", "где нажать", "где кнопка",
            "как нажать", "как открыть", "как позвонить", "как включить",
            "куда зайти", "как найти", "где находится", "как сделать",
            "помоги", "подскажи"
        ).any { q.contains(it) }
    }

    private fun buildConfig(provider: String): GrandmaAiConfig = when (provider) {
        "gemini" -> GrandmaAiConfig("gemini", Prefs.getKey(this, "gemini"))
        "deepseek" -> GrandmaAiConfig("deepseek", Prefs.getKey(this, "deepseek"))
        "claude" -> GrandmaAiConfig("claude", Prefs.getKey(this, "claude"))
        "custom" -> GrandmaAiConfig(
            "custom",
            Prefs.getKey(this, "custom"),
            Prefs.getBaseUrl(this),
            Prefs.getCustomModel(this)
        )
        else -> GrandmaAiConfig("none", "")
    }

    private fun buildOverlayPrompt(
        name: String, facts: List<String>, screenText: String
    ): String = buildString {
        append("Ты — голосовой помощник для пожилого человека")
        if (name.isNotBlank()) append(" по имени $name")
        append(". Отвечай просто, тепло, коротко — 2-4 предложения. Только по-русски. ")
        append("Без markdown, без списков, без смайликов — говори как вслух. ")
        if (screenText.isNotBlank()) {
            append("\n\nСейчас у пользователя на экране следующее (текст с экрана):\n")
            append(screenText).append("\n")
            append("Если пользователь спрашивает куда нажать — подскажи конкретно, ")
            append("называя текст кнопки так, как он виден на экране. ")
        }
        if (facts.isNotEmpty()) {
            append("\n\nЧто ты уже знаешь о собеседнике:\n")
            facts.forEach { append("— ").append(it).append("\n") }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Помощник ИИ", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Плавающий помощник"
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun startForegroundInternal() {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Помощник работает")
            .setContentText("Нажмите на лампочку 💡 на экране")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val CHANNEL_ID = "grandma_overlay_channel"
        const val NOTIF_ID = 2001

        fun start(ctx: Context) {
            val i = Intent(ctx, GrandmaOverlay::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, GrandmaOverlay::class.java))
        }
    }
}