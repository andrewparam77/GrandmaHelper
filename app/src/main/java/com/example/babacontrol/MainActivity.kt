package com.example.babacontrol

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (Prefs.getMode(this)) {
            "helper" -> { openControl(); return }
            "target" -> showAgent()
            else -> showChooser()
        }
    }

    private fun showChooser() {
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 120, 60, 60)
        }
        ll.addView(TextView(this).apply {
            text = "Кто вы?"; textSize = 28f; gravity = Gravity.CENTER
        })
        ll.addView(TextView(this).apply {
            text = "Выберите один раз"
            textSize = 14f; gravity = Gravity.CENTER
            setPadding(0, 20, 0, 60)
        })
        ll.addView(Button(this).apply {
            text = "Я — ПОМОЩНИК\n(управляю с этого телефона)"
            textSize = 16f
            setOnClickListener { askRoomThen("helper") }
        })
        ll.addView(Button(this).apply {
            text = "ЭТО ТЕЛЕФОН БАБУШКИ\n(удалённая помощь)"
            textSize = 16f
            setPadding(0, 40, 0, 0)
            setOnClickListener { askRoomThen("target") }
        })

        // ===== grandma: новая кнопка =====
        ll.addView(Button(this).apply {
            text = "🟣 ПОМОЩНИК ИИ ДЛЯ БАБУШКИ\n(работает на этом телефоне)"
            textSize = 15f
            setPadding(0, 40, 0, 0)
            setOnClickListener {
                startActivity(Intent(this@MainActivity,
                    com.example.babacontrol.grandma.GrandmaActivity::class.java))
            }
        })

        setContentView(ll)
    }

    private fun askRoomThen(mode: String) {
        val input = EditText(this).apply {
            hint = "секретный код"
            setText(Prefs.getRoom(this@MainActivity))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Код комнаты")
            .setMessage("Придумай длинный случайный код, например baba-7f3k9x2m")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val code = input.text.toString().trim()
                if (code.length < 6) {
                    toast("Минимум 6 символов"); return@setPositiveButton
                }
                Prefs.setRoom(this, code)
                Prefs.setMode(this, mode)
                if (mode == "helper") openControl() else showAgent()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun openControl() {
        startActivity(Intent(this, ControlActivity::class.java))
        finish()
    }

    private fun showAgent() {
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 100, 40, 40)
        }
        val status = TextView(this).apply { textSize = 16f; setPadding(0, 24, 0, 24) }

        fun refresh() {
            val a11y = TapService.instance != null
            status.text = buildString {
                append(if (a11y) "✅ Служба ВКЛ" else "❌ Служба ВЫКЛ — нажми кнопку 1")
                append("\nКомната: ${Prefs.getRoom(this@MainActivity)}")
                append("\nНе закрывайте приложение.")
            }
        }

        ll.addView(TextView(this).apply { text = "Телефон бабушки"; textSize = 24f })
        ll.addView(status)
        ll.addView(Button(this).apply {
            text = "1. Включить службу (Accessibility)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })
        ll.addView(Button(this).apply {
            text = "2. Разрешить уведомления"
            setOnClickListener { askPerm(android.Manifest.permission.POST_NOTIFICATIONS, 100) }
        })
        ll.addView(Button(this).apply {
            text = "3. Разрешить геолокацию"
            setOnClickListener { askPerm(android.Manifest.permission.ACCESS_FINE_LOCATION, 101) }
        })
        ll.addView(Button(this).apply {
            text = "Сменить режим"
            setOnClickListener {
                Prefs.setMode(this@MainActivity, null)
                stopService(Intent(this@MainActivity, NetService::class.java))
                recreate()
            }
        })
        setContentView(ll)

        val i = Intent(this, NetService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)

        status.postDelayed({ refresh() }, 800)
    }

    private fun askPerm(perm: String, code: Int) {
        if (Build.VERSION.SDK_INT < 23) return
        if (ContextCompat.checkSelfPermission(this, perm)
            == PackageManager.PERMISSION_GRANTED) {
            toast("Уже разрешено"); return
        }
        requestPermissions(arrayOf(perm), code)
    }

    private fun toast(s: String) =
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}