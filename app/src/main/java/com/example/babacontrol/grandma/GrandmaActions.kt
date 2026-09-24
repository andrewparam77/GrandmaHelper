package com.example.babacontrol.grandma

import android.app.AlarmManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import android.widget.Toast
import java.util.Calendar

object GrandmaActions {

    // ===================== ЗВОНОК =====================
    fun callByName(context: Context, name: String): String {
        val phone = findContactPhone(context, name)
        if (phone == null) {
            return "Не нашла контакт «" + name + "» в телефонной книге."
        }
        try {
            val intent = Intent(Intent.ACTION_CALL)
            intent.data = Uri.parse("tel:" + phone)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return "Звоню " + name + "."
        } catch (e: SecurityException) {
            return "Нет разрешения на звонки. Разрешите в настройках телефона."
        } catch (e: Exception) {
            return "Не смогла позвонить: " + (e.message ?: "")
        }
    }

    fun openDialerByName(context: Context, name: String): String {
        val phone = findContactPhone(context, name)
        if (phone == null) return "Не нашла контакт «" + name + "»."
        val intent = Intent(Intent.ACTION_DIAL)
        intent.data = Uri.parse("tel:" + phone)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Открыла набор номера для " + name + "."
    }

    // ===================== SMS =====================
    fun sendSms(context: Context, name: String, text: String): String {
        val phone = findContactPhone(context, name)
        if (phone == null) return "Не нашла контакт «" + name + "»."
        return try {
            val sms = SmsManager.getDefault()
            sms.sendTextMessage(phone, null, text, null, null)
            "Отправила SMS для " + name + ": " + text
        } catch (e: Exception) {
            "Не смогла отправить SMS: " + (e.message ?: "")
        }
    }

    private fun findContactPhone(context: Context, name: String): String? {
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val cursor: Cursor? = context.contentResolver.query(
                uri, projection, null, null, null
            )
            var bestMatch: String? = null
            var bestScore = 0
            val target = name.lowercase().trim()
            cursor?.use { c ->
                while (c.moveToNext()) {
                    val displayName = c.getString(0)?.lowercase()?.trim() ?: continue
                    val number = c.getString(1) ?: continue
                    if (displayName.contains(target) || target.contains(displayName)) {
                        val score = displayName.length
                        if (score > bestScore) {
                            bestScore = score
                            bestMatch = number
                        }
                    }
                }
            }
            return bestMatch
        } catch (e: Exception) {
            return null
        }
    }

    // ===================== ОТКРЫТИЕ ПРИЛОЖЕНИЙ =====================
    data class AppEntry(val name: String, val aliases: List<String>, val pkg: String)

    private val knownApps = listOf(
        AppEntry("WhatsApp", listOf("ватсап", "вотсап", "whatsapp"), "com.whatsapp"),
        AppEntry("Telegram", listOf("телеграм", "телега", "telegram"), "org.telegram.messenger"),
        AppEntry("YouTube", listOf("ютуб", "youtube"), "com.google.android.youtube"),
        AppEntry("Камера", listOf("камера", "фото"), "android.media.action.IMAGE_CAPTURE"),
        AppEntry("Галерея", listOf("галерея", "фотографии"), "android.intent.action.VIEW"),
        AppEntry("Телефон", listOf("телефон", "звонилка"), "android.intent.action.DIAL"),
        AppEntry("Сообщения", listOf("сообщения", "смс"), "android.intent.action.VIEW"),
        AppEntry("Контакты", listOf("контакты", "записная"), "android.intent.action.VIEW"),
        AppEntry("Часы", listOf("часы", "будильник"), "android.intent.action.SET_ALARM"),
        AppEntry("Калькулятор", listOf("калькулятор"), "android.intent.action.MAIN"),
        AppEntry("Настройки", listOf("настройки"), "android.settings.SETTINGS"),
        AppEntry("Почта", listOf("почта", "gmail"), "android.intent.action.SENDTO"),
        AppEntry("Погода", listOf("погода"), ""),
        AppEntry("Sberbank", listOf("сбер", "сбербанк"), "ru.sberbankmobile"),
        AppEntry("Госуслуги", listOf("госуслуги"), "ru.rostel")
    )

    fun openApp(context: Context, appName: String): String {
        val q = appName.lowercase().trim()
        val found = knownApps.firstOrNull { entry ->
            entry.aliases.any { q.contains(it) } || q.contains(entry.name.lowercase())
        } ?: return "Не знаю приложение «" + appName + "»."

        try {
            val launchIntent = if (found.pkg.contains(".")) {
                context.packageManager.getLaunchIntentForPackage(found.pkg)
            } else null

            val intent = when {
                launchIntent != null -> launchIntent
                found.name == "Камера" -> Intent("android.media.action.IMAGE_CAPTURE")
                found.name == "Телефон" -> Intent(Intent.ACTION_DIAL)
                found.name == "Настройки" -> Intent(Settings.ACTION_SETTINGS)
                found.name == "Погода" -> {
                    return webWeather(context, "погода")
                }
                else -> null
            }

            if (intent == null) return "Приложение «" + found.name + "» не установлено."
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return "Открываю " + found.name + "."
        } catch (e: Exception) {
            return "Не смогла открыть " + found.name + ": " + (e.message ?: "")
        }
    }

    // ===================== ФОНАРИК =====================
    private var flashlightOn = false

    fun toggleFlashlight(context: Context, turnOn: Boolean? = null): String {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { camId ->
                cm.getCameraCharacteristics(camId)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "Фонарика нет на этом телефоне."

            val newState = turnOn ?: !flashlightOn
            cm.setTorchMode(id, newState)
            flashlightOn = newState
            if (newState) "Фонарик включён." else "Фонарик выключен."
        } catch (e: Exception) {
            "Не смогла переключить фонарик: " + (e.message ?: "")
        }
    }

    // ===================== ГРОМКОСТЬ =====================
    fun volumeUp(context: Context): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (current + (max / 5)).coerceAtMost(max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        return "Сделала громче. Уровень " + target + " из " + max + "."
    }

    fun volumeDown(context: Context): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = (current - (am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 5)).coerceAtLeast(0)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        return "Сделала тише. Уровень " + target + "."
    }

    fun volumeMax(context: Context): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI)
        return "Включила максимальную громкость."
    }

    // ===================== ЯРКОСТЬ =====================
    fun brightnessUp(context: Context): String {
        return try {
            val current = Settings.System.getInt(
                context.contentResolver, Settings.System.SCREEN_BRIGHTNESS
            )
            val newVal = (current + 40).coerceAtMost(255)
            Settings.System.putInt(
                context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, newVal
            )
            "Яркость повышена."
        } catch (e: Exception) {
            "Нет разрешения менять яркость. Разрешите в настройках."
        }
    }

    fun brightnessDown(context: Context): String {
        return try {
            val current = Settings.System.getInt(
                context.contentResolver, Settings.System.SCREEN_BRIGHTNESS
            )
            val newVal = (current - 40).coerceAtLeast(30)
            Settings.System.putInt(
                context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, newVal
            )
            "Яркость понижена."
        } catch (e: Exception) {
            "Нет разрешения менять яркость."
        }
    }

    // ===================== WI-FI =====================
    fun toggleWifi(context: Context, turnOn: Boolean? = null): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // На Android 10+ нельзя переключать Wi-Fi программно (кроме системных приложений)
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "Открыла настройки Wi-Fi. Переключите там."
            } else {
                @Suppress("DEPRECATION")
                val wm = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val newState = turnOn ?: !wm.isWifiEnabled
                @Suppress("DEPRECATION")
                wm.isWifiEnabled = newState
                if (newState) "Wi-Fi включён." else "Wi-Fi выключен."
            }
        } catch (e: Exception) {
            "Не смогла переключить Wi-Fi."
        }
    }

    // ===================== BLUETOOTH =====================
    fun toggleBluetooth(context: Context, turnOn: Boolean? = null): String {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
                ?: return "Bluetooth не поддерживается."
            if (turnOn == true) {
                if (!adapter.isEnabled) adapter.enable()
                "Bluetooth включён."
            } else if (turnOn == false) {
                if (adapter.isEnabled) adapter.disable()
                "Bluetooth выключен."
            } else {
                if (adapter.isEnabled) { adapter.disable(); "Bluetooth выключен." }
                else { adapter.enable(); "Bluetooth включён." }
            }
        } catch (e: SecurityException) {
            "Нет разрешения на Bluetooth."
        } catch (e: Exception) {
            "Не смогла переключить Bluetooth."
        }
    }

    // ===================== БУДИЛЬНИК / НАПОМИНАНИЕ =====================
    fun setAlarm(context: Context, hour: Int, minute: Int, label: String): String {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Поставила будильник на " + hour + ":" + String.format("%02d", minute) + "."
        } catch (e: Exception) {
            "Не смогла поставить будильник."
        }
    }

    // ===================== ОТКРЫТИЕ НАСТРОЕК =====================
    fun openSettings(context: Context): String {
        val intent = Intent(Settings.ACTION_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Открываю настройки."
    }
}
