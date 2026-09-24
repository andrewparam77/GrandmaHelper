package com.example.babacontrol.grandma

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.babacontrol.Prefs

class GrandmaActivity : AppCompatActivity() {

    private lateinit var statusOverlay: TextView
    private lateinit var statusA11y: TextView
    private lateinit var statusMic: TextView
    private lateinit var statusNotif: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }

        val hasA11y = GrandmaScreenReader.isRunning()

        val hasMic = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        statusOverlay.text = (if (hasOverlay) "[+] " else "[-] ") + "1. Поверх других приложений"
        statusA11y.text = (if (hasA11y) "[+] " else "[-] ") + "2. Чтение экрана (Accessibility)"
        statusMic.text = (if (hasMic) "[+] " else "[-] ") + "3. Микрофон"
        statusNotif.text = (if (hasNotif) "[+] " else "[-] ") + "4. Уведомления"
    }

    private fun buildUi() {
        val ll = LinearLayout(this)
        ll.orientation = LinearLayout.VERTICAL
        ll.setPadding(dp(20), dp(40), dp(20), dp(20))

        val title = TextView(this)
        title.text = "Помощник ИИ для бабушки"
        title.textSize = 22f
        title.gravity = Gravity.CENTER
        ll.addView(title)

        val hint = TextView(this)
        hint.text = "Включите 4 пункта ниже, чтобы помощник работал.\n" +
                "После этого фиолетовая лампочка появится поверх всех приложений."
        hint.textSize = 14f
        hint.setPadding(0, dp(16), 0, dp(20))
        ll.addView(hint)

        statusOverlay = makeStatus()
        statusA11y = makeStatus()
        statusMic = makeStatus()
        statusNotif = makeStatus()

        ll.addView(statusOverlay)
        ll.addView(makeButton("Включить «поверх приложений»") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val i = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + packageName)
                )
                startActivity(i)
            }
        })

        ll.addView(statusA11y)
        ll.addView(makeButton("Открыть Accessibility") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        ll.addView(statusMic)
        ll.addView(makeButton("Разрешить микрофон") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            }
        })

        ll.addView(statusNotif)
        ll.addView(makeButton("Разрешить уведомления") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102)
            }
        })

        ll.addView(space(20))

        ll.addView(makePrimaryButton("Включить помощника поверх экрана") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Сначала разрешите пункт 1", Toast.LENGTH_SHORT).show()
                val i = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + packageName)
                )
                startActivity(i)
            } else {
                GrandmaOverlay.start(this)
                Toast.makeText(this, "Помощник включён", Toast.LENGTH_SHORT).show()
            }
        })

        ll.addView(makeButton("Остановить помощника") {
            GrandmaOverlay.stop(this)
            Toast.makeText(this, "Остановлен", Toast.LENGTH_SHORT).show()
        })

        ll.addView(space(20))

        val divider = View(this)
        divider.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        )
        divider.setBackgroundColor(0xFFDDDDDD.toInt())
        ll.addView(divider)

        ll.addView(space(20))

        ll.addView(makeButton("Настройки (ключ, имя бабушки)") {
            showSettingsDialog()
        })

        val scroll = ScrollView(this)
        scroll.addView(ll)
        setContentView(scroll)
    }

    private fun showSettingsDialog() {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(dp(16), dp(16), dp(16), dp(16))

        val providers = listOf(
            "gemini" to "Google Gemini",
            "deepseek" to "DeepSeek",
            "claude" to "Claude",
            "custom" to "Своя нейросеть",
            "none" to "Без интернета"
        )
        val currentProvider = Prefs.getProvider(this)

        val providerSpinner = Spinner(this)
        val labels = mutableListOf<String>()
        for (p in providers) {
            labels.add(p.second)
        }
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
        providerSpinner.adapter = adapter

        var initialIndex = 0
        for (i in providers.indices) {
            if (providers[i].first == currentProvider) {
                initialIndex = i
            }
        }
        providerSpinner.setSelection(initialIndex)

        val geminiKey = EditText(this)
        geminiKey.hint = "Ключ Gemini (AIza...)"
        geminiKey.setText(Prefs.getKey(this, "gemini"))

        val deepseekKey = EditText(this)
        deepseekKey.hint = "Ключ DeepSeek (sk-...)"
        deepseekKey.setText(Prefs.getKey(this, "deepseek"))

        val claudeKey = EditText(this)
        claudeKey.hint = "Ключ Claude (sk-ant-...)"
        claudeKey.setText(Prefs.getKey(this, "claude"))

        val customKey = EditText(this)
        customKey.hint = "Ключ своей нейросети"
        customKey.setText(Prefs.getKey(this, "custom"))

        val customBase = EditText(this)
        customBase.hint = "Base URL (https://api.example.com/v1)"
        customBase.setText(Prefs.getBaseUrl(this))

        val customModel = EditText(this)
        customModel.hint = "Модель (gpt-3.5-turbo)"
        customModel.setText(Prefs.getCustomModel(this))

        val grandmaName = EditText(this)
        grandmaName.hint = "Имя бабушки (необязательно)"
        grandmaName.setText(Prefs.getGrandmaName(this))

        val pTitle = TextView(this)
        pTitle.text = "Провайдер ИИ:"
        container.addView(pTitle)
        container.addView(providerSpinner)
        container.addView(space(8))
        container.addView(geminiKey)
        container.addView(deepseekKey)
        container.addView(claudeKey)
        container.addView(customBase)
        container.addView(customModel)
        container.addView(customKey)
        container.addView(space(8))
        container.addView(grandmaName)
        container.addView(space(8))

        val urlHint = TextView(this)
        urlHint.text = "Ключ Gemini бесплатно:\naistudio.google.com/apikey"
        urlHint.textSize = 12f
        urlHint.setTextColor(0xFF888888.toInt())
        container.addView(urlHint)

        val scroll = ScrollView(this)
        scroll.addView(container)

        android.app.AlertDialog.Builder(this)
            .setTitle("Настройки")
            .setView(scroll)
            .setPositiveButton("Сохранить") { _, _ ->
                val idx = providerSpinner.selectedItemPosition
                val newProvider = providers[idx].first
                Prefs.setProvider(this, newProvider)
                Prefs.setKey(this, "gemini", geminiKey.text.toString())
                Prefs.setKey(this, "deepseek", deepseekKey.text.toString())
                Prefs.setKey(this, "claude", claudeKey.text.toString())
                Prefs.setKey(this, "custom", customKey.text.toString())
                Prefs.setBaseUrl(this, customBase.text.toString())
                Prefs.setCustomModel(this, customModel.text.toString())
                Prefs.setGrandmaName(this, grandmaName.text.toString())
                Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .setNeutralButton("Забыть всё") { _, _ ->
                GrandmaMemory(this).clearAll()
                Toast.makeText(this, "Память очищена", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun makeStatus(): TextView {
        val tv = TextView(this)
        tv.textSize = 15f
        tv.setPadding(0, dp(10), 0, dp(4))
        return tv
    }

    private fun makeButton(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 14f
        b.setOnClickListener { onClick() }
        return b
    }

    private fun makePrimaryButton(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 16f
        b.setPadding(0, dp(16), 0, dp(16))
        b.setBackgroundColor(0xFF7C3AED.toInt())
        b.setTextColor(0xFFFFFFFF.toInt())
        b.setOnClickListener { onClick() }
        return b
    }

    private fun space(h: Int): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(h)
        )
        return v
    }

    private fun dp(v: Int): Int {
        return (v * resources.displayMetrics.density).toInt()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }
}
