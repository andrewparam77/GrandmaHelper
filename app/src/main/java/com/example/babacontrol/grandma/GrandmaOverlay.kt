package com.example.babacontrol.grandma

import android.Manifest
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try { voice?.destroy() } catch (e: Exception) {}
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) {}

        val b = bubbleView
        if (b != null) {
            try { wm.removeView(b) } catch (e: Exception) {}
        }
        val p = panelView
        if (p != null) {
            try { wm.removeView(p) } catch (e: Exception) {}
        }
        bubbleView = null
        panelView = null
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
        voice = GrandmaVoice(this)
        voice?.onListeningChanged = { listening ->
            isListening = listening
            val mb = micButton
            if (mb != null) {
                mb.text = if (listening) "Стоп" else "Спросить"
            }
            val st = statusText
            if (st != null) {
                st.text = if (listening) "Слушаю..." else ""
            }
        }
        voice?.onResult = { text -> handleQuestion(text) }
        voice?.onError = { err ->
            val st = statusText
            if (st != null) st.text = err
            isListening = false
            val mb = micButton
            if (mb != null) mb.text = "Спросить"
        }
    }

    private fun createBubble() {
        val size = dp(64)
        val view = FrameLayout(this)

        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(Color.parseColor("#7C3AED"))
        bg.setStroke(dp(2), Color.parseColor("#5B21B6"))
        view.background = bg
        view.elevation = dp(8).toFloat()

        val icon = TextView(this)
        icon.text = "\uD83D\uDCA1"
        icon.textSize = 30f
        icon.gravity = Gravity.CENTER
        icon.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        view.addView(icon)

        bubbleParams = WindowManager.LayoutParams(
            size,
            size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        bubbleParams.gravity = Gravity.TOP or Gravity.START
        bubbleParams.x = dp(10)
        bubbleParams.y = dp(120)

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        view.setOnTouchListener { v, ev ->
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
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) {
                        moved = true
                    }
                    bubbleParams.x = initialX + dx
                    bubbleParams.y = initialY + dy
                    try { wm.updateViewLayout(v, bubbleParams) } catch (e: Exception) {}
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

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val rootBg = GradientDrawable()
        rootBg.cornerRadius = dp(20).toFloat()
        rootBg.setColor(Color.WHITE)
        rootBg.setStroke(dp(2), Color.parseColor("#7C3AED"))
        root.background = rootBg
        root.setPadding(dp(12), dp(10), dp(12), dp(12))

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL

        val title = TextView(this)
        title.text = "Помощник"
        title.textSize = 16f
        title.setTextColor(Color.parseColor("#5B21B6"))
        title.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        )

        val minimizeBtn = TextView(this)
        minimizeBtn.text = "—"
        minimizeBtn.textSize = 22f
        minimizeBtn.setTextColor(Color.parseColor("#5B21B6"))
        minimizeBtn.setPadding(dp(10), 0, dp(10), 0)
        minimizeBtn.setOnClickListener { closePanel() }

        val exitBtn = TextView(this)
        exitBtn.text = "X"
        exitBtn.textSize = 20f
        exitBtn.setTextColor(Color.parseColor("#B91C1C"))
        exitBtn.setPadding(dp(10), 0, dp(10), 0)
        exitBtn.setOnClickListener { confirmExit() }

        header.addView(title)
        header.addView(minimizeBtn)
        header.addView(exitBtn)
        root.addView(header)

        val scroll = ScrollView(this)
        val scrollParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        )
        scrollParams.topMargin = dp(8)
        scrollParams.bottomMargin = dp(8)
        scroll.layoutParams = scrollParams

        answerText = TextView(this)
        answerText?.text = "Нажмите Спросить и говорите.\nНапример: Который час?"
        answerText?.textSize = 16f
        answerText?.setTextColor(Color.parseColor("#111827"))
        answerText?.setLineSpacing(dp(4).toFloat(), 1f)

        val at = answerText
        if (at != null) scroll.addView(at)
        root.addView(scroll)

        statusText = TextView(this)
        statusText?.textSize = 13f
        statusText?.setTextColor(Color.parseColor("#6B7280"))
        val st = statusText
        if (st != null) root.addView(st)

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.gravity = Gravity.CENTER
        buttons.setPadding(0, dp(6), 0, 0)

        micButton = TextView(this)
        micButton?.text = "Спросить"
        micButton?.textSize = 18f
        micButton?.setTextColor(Color.WHITE)
        micButton?.gravity = Gravity.CENTER
        micButton?.setPadding(dp(20), dp(14), dp(20), dp(14))
        val micBg = GradientDrawable()
        micBg.cornerRadius = dp(30).toFloat()
        micBg.setColor(Color.parseColor("#7C3AED"))
        micButton?.background = micBg
        micButton?.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        )
        micButton?.setOnClickListener { onMicClick() }

        val mb = micButton
        if (mb != null) buttons.addView(mb)
        root.addView(buttons)

        val widthPx = (resources.displayMetrics.widthPixels * 0.9f).toInt()
        val widthFinal = Math.min(widthPx, dp(400))
        val heightPx = dp(460)

        panelParams = WindowManager.LayoutParams(
            widthFinal,
            heightPx,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        panelParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        panelParams.y = dp(100)

        var pX = 0
        var pY = 0
        var tX = 0f
        var tY = 0f
        header.setOnTouchListener { v, ev ->
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
                    try { wm.updateViewLayout(v, panelParams) } catch (e: Exception) {}
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
        val p = panelView
        if (p != null) {
            try { wm.removeView(p) } catch (e: Exception) {}
        }
        panelView = null
        isPanelOpen = false
        bubbleView?.visibility = View.VISIBLE
    }

    private fun confirmExit() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Закрыть помощника?")
            .setMessage("Кружок исчезнет с экрана. Чтобы вернуть - откройте приложение Помощь.")
            .setPositiveButton("Закрыть") { _, _ -> stopSelf() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun onMicClick() {
        val v = voice
        if (v == null) return
        if (isListening) {
            v.stop()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
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

        isLoading = true
        statusText?.text = "Думаю..."
        micButton?.text = "Спросить"

        scope.launch {
            try {
                val facts = memory.loadFacts()
                val history = memory.load().takeLast(20)
                val name = Prefs.getGrandmaName(this@GrandmaOverlay)
                val screenText = if (looksLikeHowToQuestion(text) && GrandmaScreenReader.isRunning()) {
                    GrandmaScreenReader.readCurrentScreen()
                } else {
                    ""
                }
                val systemPrompt = buildOverlayPrompt(name, facts, screenText)
                val answer = GrandmaAiClient.ask(
                    this@GrandmaOverlay, config, systemPrompt, history, text
                )

                answerText?.text = answer
                tts?.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "ov_" + System.currentTimeMillis())

                val updated = memory.load().toMutableList()
                updated.add(GrandmaMsg(text, true))
                updated.add(GrandmaMsg(answer, false))
                memory.save(updated)
            } catch (e: Exception) {
                statusText?.text = "Ошибка: " + (e.message ?: "")
            } finally {
                isLoading = false
                val st = statusText?.text ?: ""
                if (!st.startsWith("Ошибка")) {
                    statusText?.text = ""
                }
            }
        }
    }

    private fun looksLikeHowToQuestion(text: String): Boolean {
        val q = text.lowercase(Locale("ru", "RU"))
        val keys = listOf(
            "куда нажать", "куда тыкнуть", "где нажать", "где кнопка",
            "как нажать", "как открыть", "как позвонить", "как включить",
            "куда зайти", "как найти", "где находится", "как сделать",
            "помоги", "подскажи"
        )
        for (k in keys) {
            if (q.contains(k)) return true
        }
        return false
    }

    private fun buildConfig(provider: String): GrandmaAiConfig {
        return when (provider) {
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
    }

    private fun buildOverlayPrompt(
        name: String, facts: List<String>, screenText: String
    ): String {
        val sb = StringBuilder()
        sb.append("Ты - голосовой помощник для пожилого человека")
        if (name.isNotBlank()) {
            sb.append(" по имени ").append(name)
        }
        sb.append(". Отвечай просто, тепло, коротко - 2-4 предложения. Только по-русски. ")
        sb.append("Без markdown, без списков, без смайликов - говори как вслух. ")

        if (screenText.isNotBlank()) {
            sb.append("\n\nСейчас у пользователя на экране следующее:\n")
            sb.append(screenText).append("\n")
            sb.append("Если пользователь спрашивает куда нажать - подскажи конкретно, ")
            sb.append("называя текст кнопки так, как он виден на экране. ")
        }

        if (facts.isNotEmpty()) {
            sb.append("\n\nЧто ты уже знаешь о собеседнике:\n")
            for (f in facts) {
                sb.append("- ").append(f).append("\n")
            }
        }
        return sb.toString()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Помощник ИИ", NotificationManager.IMPORTANCE_LOW
            )
            ch.description = "Плавающий помощник"
            ch.setShowBadge(false)

            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(ch)
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
            .setContentText("Нажмите на лампочку на экране")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun dp(v: Int): Int {
        return (v * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val CHANNEL_ID = "grandma_overlay_channel"
        const val NOTIF_ID = 2001

        fun start(ctx: Context) {
            val i = Intent(ctx, GrandmaOverlay::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, GrandmaOverlay::class.java))
        }
    }
}
