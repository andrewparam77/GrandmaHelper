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
import android.widget.*
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
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            Settings.canDrawOverlays(this) else true
        val hasA11y = GrandmaScreenReader.isRunning()
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
        val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED
        else true

        statusOverlay.text = (if (hasOverlay) "✅ " else "❌ ") + "1. Поверх других приложений"
        statusA11y.text = (if (hasA11y) "✅ " else "❌ ") + "2. Чтение экрана (Accessibility)"
        statusMic.text = (if (hasMic) "✅ " else "❌ ") + "3. Микрофон"
        statusNotif.text = (if (hasNotif) "✅ " else "❌ ") + "4. Уведомления"
    }

    private fun buildUi() {
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(20))
        }

        ll.addView(TextView(this).apply {
            text = "🟣 Помощник ИИ для бабушки"
            textSize = 22f
            gravity = Gravity.CENTER
        })

        ll.addView(TextView(this).apply {
            text = "Включите 4 пункта ниже, чтобы помощник работал.\n" +
                    "После этого фиолетовая лампочка 💡 появится поверх всех приложений."
            textSize = 14f
            setPadding(0, dp(16), 0, dp(20))
        })

        statusOverlay = makeStatus()
        statusA11y = makeStatus()
        statusMic = makeStatus()
        statusNotif = makeStatus()

        ll.addView(statusOverlay)
        ll.addView(makeButton("Включить «поверх приложений»") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
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

        ll.addView(makePrimaryButton("🟣 Включить помощника поверх экрана") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Сначала разрешите пункт 1", Toast.LENGTH_SHORT).show()
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
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
        ll.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            )
            setBackgroundColor(0xFFDDDDDD.toInt())
        })
        ll.addView(space(20))

        ll.addView(makeButton("⚙️ Настройки (ключ, имя бабушки)") {
            showSettingsDialog()
        })

        setContentView(ScrollView(this).apply { addView(ll) })
    }

    private fun showSettingsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val providers = listOf(
            "gemini" to "Google Gemini",
            "deepseek" to "DeepSeek",
            "claude" to "Claude",
            "custom" to "Своя нейросеть",
            "none" to "Без интернета"
        )
        val currentProvider = Prefs.getProvider(this)
        var selectedProvider = currentProvider

        val providerSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@GrandmaActivity,
                android.R.layout.simple_spinner_dropdown_item,
                providers.map { it.second }
            )
            setSelection(providers.indexOfFirst { it.first == currentProvider }
                .coerceAtLeast(0))
        }

        val geminiKey = EditText(this).apply {
            hint = "Ключ Gemini (AIza...)"
            setText(Prefs.getKey(this@GrandmaActivity, "gemini"))
        }
        val deepseekKey = EditText(this).apply {
            hint = "Ключ DeepSeek (sk-...)"
            setText(Prefs.getKey(this@GrandmaActivity, "deepseek"))
        }
        val claudeKey = EditText(this).apply {
            hint = "Ключ Claude (sk-ant-...)"
            setText(Prefs.getKey(this@GrandmaActivity, "claude"))
        }
        val customKey = EditText(this).apply {
            hint = "Ключ своей нейросети"
            setText(Prefs.getKey(this@GrandmaActivity, "custom"))
        }
        val customBase = EditText(this).apply {
            hint = "Base URL (https://api.example.com/v1)"
            setText(Prefs.getBaseUrl(this@GrandmaActivity))
        }
        val customModel = EditText(this).apply {
            hint = "Модель (gpt-3.5-turbo)"
            setText(Prefs.getCustomModel(this@GrandmaActivity))
        }
        val grandmaName = EditText(this).apply {
            hint = "Имя бабушки (необязательно)"
            setText(Prefs.getGrandmaName(this@GrandmaActivity))
        }

        container.addView(TextView(this).apply { text = "Провайдер ИИ:" })
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
        container.addView(TextView(this).apply {
            text = "Ключ Gemini можно получить бесплатно:\naistudio.google.com/apikey"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
        })

        android.app.AlertDialog.Builder(this)
            .setTitle("Настройки")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Сохранить") { _, _ ->
                val newProvider = providers[providerSpinner.selectedItemPosition].first
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

    private fun makeStatus() = TextView(this).apply {
        textSize = 15f
        setPadding(0, dp(10), 0, dp(4))
    }

    private fun makeButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 14f
        setOnClickListener { onClick() }
    }

    private fun makePrimaryButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 16f
        setPadding(0, dp(16), 0, dp(16))
        setBackgroundColor(0xFF7C3AED.toInt())
        setTextColor(0xFFFFFFFF.toInt())
        setOnClickListener { onClick() }
    }

    private fun space(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(h)
        )
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }
}