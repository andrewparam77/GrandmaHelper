package com.example.babacontrol.grandma

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

sealed class BrainResult {
    data class Answer(val text: String, val handledLocally: Boolean = true) : BrainResult()
    data class NeedWebSearch(val query: String) : BrainResult()
    object Unknown : BrainResult()
}

object GrandmaOfflineBrain {

    fun think(context: Context, userText: String): BrainResult {
        val q = userText.lowercase(Locale("ru", "RU")).trim()
        if (q.isBlank()) return BrainResult.Answer("Я вас не расслышала, повторите.")

        // ===================== ПРИВЕТСТВИЯ =====================
        if (startsAny(q, "привет", "здравствуй", "здравствуйте", "добрый день",
                "доброе утро", "добрый вечер", "хай")) {
            return BrainResult.Answer(greetingByTime())
        }
        if (q == "как дела" || q == "как ты" || q.contains("как дела")) {
            return BrainResult.Answer("У меня всё хорошо, спасибо! А у вас как дела?")
        }
        if (q.contains("спасибо") || q.contains("благодар")) {
            return BrainResult.Answer("Пожалуйста, всегда рада помочь!")
        }
        if (q.contains("пока") || q.contains("до свидания") || q.contains("прощай")) {
            return BrainResult.Answer("До свидания! Хорошего дня!")
        }
        if (q.contains("как тебя зовут") || q.contains("кто ты") ||
            q.contains("что ты умеешь") || q.contains("что ты можешь")) {
            return BrainResult.Answer(capabilitiesText())
        }

        // ===================== ВРЕМЯ И ДАТА =====================
        if (q.contains("который час") || q.contains("сколько времени") ||
            q.contains("сколько время") || q == "время" || q.contains("время сейчас")) {
            val t = SimpleDateFormat("HH:mm", Locale("ru", "RU")).format(Date())
            return BrainResult.Answer("Сейчас " + t + ".")
        }
        if (q.contains("какое число") || q.contains("какой день") ||
            q.contains("какая дата") || q.contains("какое сегодня") ||
            q == "дата" || q == "число") {
            val weekday = SimpleDateFormat("EEEE", Locale("ru", "RU")).format(Date())
            val date = SimpleDateFormat("d MMMM yyyy", Locale("ru", "RU")).format(Date())
            return BrainResult.Answer("Сегодня " + weekday + ", " + date + ".")
        }
        if (q.contains("какой сегодня день") || q.contains("какой день недели")) {
            val weekday = SimpleDateFormat("EEEE", Locale("ru", "RU")).format(Date())
            return BrainResult.Answer("Сегодня " + weekday + ".")
        }

        // ===================== МАТЕМАТИКА =====================
        calculateExpression(q)?.let { return BrainResult.Answer(it) }

        // ===================== ФОНАРИК =====================
        if (q.contains("фонарик") || q.contains("фонарь")) {
            val turnOn = when {
                q.contains("включ") || q.contains("вруб") -> true
                q.contains("выключ") || q.contains("отключ") -> false
                else -> null
            }
            val result = GrandmaActions.toggleFlashlight(context, turnOn)
            return BrainResult.Answer(result)
        }

        // ===================== ЗВОНОК =====================
        if (q.startsWith("позвони") || q.startsWith("набери") ||
            q.contains("набрать номер") || q.contains("позвонить")) {
            val name = extractNameAfter(q, listOf("позвони", "набери", "позвонить", "набрать"))
            if (name.isNotBlank()) {
                val result = GrandmaActions.callByName(context, name)
                return BrainResult.Answer(result)
            }
        }

        // ===================== SMS =====================
        if (q.startsWith("напиши") || q.startsWith("отправь смс") ||
            q.startsWith("отправь сообщение") || q.startsWith("смс")) {
            val text = extractSmsText(q)
            if (text != null) {
                val result = GrandmaActions.sendSms(context, text.first, text.second)
                return BrainResult.Answer(result)
            }
        }

        // ===================== ОТКРЫТИЕ ПРИЛОЖЕНИЙ =====================
        if (q.startsWith("открой") || q.startsWith("запусти") || q.startsWith("включи приложение")) {
            val appName = extractNameAfter(q, listOf("открой", "запусти", "включи приложение", "включи"))
            if (appName.isNotBlank()) {
                val result = GrandmaActions.openApp(context, appName)
                return BrainResult.Answer(result)
            }
        }

        // ===================== БУДИЛЬНИК / НАПОМИНАНИЕ =====================
        if (q.contains("напомни") || q.contains("будильник") || q.contains("поставь будильник")) {
            val time = parseTime(q)
            if (time != null) {
                val label = extractReminderLabel(q)
                val result = GrandmaActions.setAlarm(context, time.first, time.second, label)
                return BrainResult.Answer(result)
            } else {
                return BrainResult.Answer(
                    "На какое время поставить? Скажите, например: «напомни в 6 выпить таблетки»."
                )
            }
        }

        // ===================== ГРОМКОСТЬ =====================
        if (q.contains("громче") || q.contains("громкость выше") ||
            q.contains("прибавь звук")) {
            return BrainResult.Answer(GrandmaActions.volumeUp(context))
        }
        if (q.contains("тише") || q.contains("громкость ниже") ||
            q.contains("убавь звук")) {
            return BrainResult.Answer(GrandmaActions.volumeDown(context))
        }
        if (q.contains("максимальная громкость") || q.contains("на всю громкость") ||
            q.contains("громкость на максимум")) {
            return BrainResult.Answer(GrandmaActions.volumeMax(context))
        }

        // ===================== ЯРКОСТЬ =====================
        if (q.contains("ярче") || q.contains("яркость выше")) {
            return BrainResult.Answer(GrandmaActions.brightnessUp(context))
        }
        if (q.contains("темнее") || q.contains("яркость ниже")) {
            return BrainResult.Answer(GrandmaActions.brightnessDown(context))
        }

        // ===================== WI-FI =====================
        if (q.contains("wi-fi") || q.contains("вайфай") || q.contains("вай-фай")) {
            val turnOn = when {
                q.contains("включ") -> true
                q.contains("выключ") -> false
                else -> null
            }
            return BrainResult.Answer(GrandmaActions.toggleWifi(context, turnOn))
        }

        // ===================== BLUETOOTH =====================
        if (q.contains("блютуз") || q.contains("bluetooth")) {
            val turnOn = when {
                q.contains("включ") -> true
                q.contains("выключ") -> false
                else -> null
            }
            return BrainResult.Answer(GrandmaActions.toggleBluetooth(context, turnOn))
        }

        // ===================== НАСТРОЙКИ =====================
        if (q == "настройки" || q.contains("открой настройки")) {
            return BrainResult.Answer(GrandmaActions.openSettings(context))
        }

        // ===================== ЗАГОТОВКИ БЫТОВЫХ ВОПРОСОВ =====================
        quickFact(q)?.let { return BrainResult.Answer(it) }

        // ===================== ПОГОДА / НОВОСТИ / ПОИСК =====================
        if (q.contains("погода")) {
            return BrainResult.NeedWebSearch("погода")
        }
        if (q.contains("новост") || q.contains("что нового в мире") ||
            q.contains("что случилось в мире")) {
            return BrainResult.NeedWebSearch("новости")
        }

        // Если вопрос начинается с вопросительного слова — ищем в интернете
        if (isQuestion(q)) {
            return BrainResult.NeedWebSearch(userText)
        }

        return BrainResult.Unknown
    }

    // ===================== ВСПОМОГАТЕЛЬНЫЕ =====================

    private fun startsAny(s: String, vararg prefixes: String): Boolean {
        for (p in prefixes) if (s.startsWith(p)) return true
        return false
    }

    private fun greetingByTime(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "Доброе утро!"
            in 12..17 -> "Добрый день!"
            in 18..22 -> "Добрый вечер!"
            else -> "Доброй ночи!"
        }
    }

    private fun capabilitiesText(): String {
        return "Я умею: подсказывать время и дату, звонить по контактам, писать SMS, " +
                "открывать приложения, включать фонарик, менять громкость и яркость, " +
                "ставить будильник и напоминания. Ещё я могу поискать ответ в интернете, " +
                "рассказать погоду и новости."
    }

    private fun extractNameAfter(q: String, prefixes: List<String>): String {
        for (p in prefixes) {
            val idx = q.indexOf(p)
            if (idx >= 0) {
                var rest = q.substring(idx + p.length).trim()
                rest = rest
                    .replace(Regex("^(номер|телефон|пожалуйста|ему|ей|мне)\\s+"), "")
                    .replace(Regex("\\s+(пожалуйста|сейчас|быстро)$"), "")
                    .trim()
                if (rest.isNotBlank()) return rest
            }
        }
        return ""
    }

    private fun extractSmsText(q: String): Pair<String, String>? {
        // «напиши сыну что я скоро приеду»
        val patterns = listOf(
            Regex("напиши\\s+([а-яёa-z]+)\\s+(?:что|и скажи|скажи)\\s+(.+)"),
            Regex("отправь\\s+смс\\s+([а-яёa-z]+)\\s+(.+)"),
            Regex("отправь\\s+сообщение\\s+([а-яёa-z]+)\\s+(.+)")
        )
        for (p in patterns) {
            val m = p.find(q)
            if (m != null) {
                val name = m.groupValues[1].trim()
                val text = m.groupValues[2].trim()
                if (name.isNotBlank() && text.isNotBlank()) return name to text
            }
        }
        return null
    }

    private fun parseTime(q: String): Pair<Int, Int>? {
        // «в 6», «в 18:30», «в 7 утра», «в 8 вечера»
        val regex = Regex("(?:в|на)\\s+(\\d{1,2})(?:[:.](\\d{2}))?\\s*(утра|вечера|дня|ночи)?")
        val m = regex.find(q) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: 0
        val period = m.groupValues[3]
        if (hour !in 0..23 || minute !in 0..59) return null

        when (period) {
            "вечера" -> if (hour < 12) hour += 12
            "дня" -> if (hour < 12 && hour != 12) hour += 12
            "ночи" -> if (hour < 12) hour += 12
            "утра" -> if (hour == 12) hour = 0
        }
        return hour to minute
    }

    private fun extractReminderLabel(q: String): String {
        // Убираем "напомни", "в 6", "будильник"
        var s = q
            .replace("напомни", "")
            .replace("поставь будильник", "")
            .replace("будильник", "")
            .replace(Regex("(?:в|на)\\s+\\d{1,2}(?:[:.]\\d{2})?\\s*(утра|вечера|дня|ночи)?"), "")
            .trim()
        if (s.startsWith("что")) s = s.substring(3).trim()
        if (s.startsWith("мне")) s = s.substring(3).trim()
        if (s.startsWith("я должна")) s = s.substring(8).trim()
        if (s.startsWith("я должен")) s = s.substring(8).trim()
        return if (s.isBlank()) "Напоминание" else s.replaceFirstChar { it.uppercase() }
    }

    private fun calculateExpression(q: String): String? {
        val m = Regex("сколько будет\\s+(.+)").find(q) ?: return null
        var expr = m.groupValues[1].trim()
        expr = expr
            .replace("плюс", "+").replace("минус", "-")
            .replace("умножить на", "*").replace("умножить", "*")
            .replace("разделить на", "/").replace("разделить", "/")
            .replace("делить на", "/").replace("делить", "/")
            .replace("икс", "*").replace(" x ", " * ")
            .replace(",", ".")
            .replace(Regex("[^0-9+\\-*/.() ]"), "")
            .trim()
        if (expr.isBlank()) return null

        return try {
            val result = evalExpression(expr)
            "Будет " + formatNumber(result) + "."
        } catch (e: Exception) {
            null
        }
    }

    private fun evalExpression(expr: String): Double {
        return object {
            var pos = -1
            var ch = 0

            fun nextChar() {
                ch = if (++pos < expr.length) expr[pos].code else -1
            }

            fun eat(charToEat: Int): Boolean {
                while (ch == ' '.code) nextChar()
                if (ch == charToEat) { nextChar(); return true }
                return false
            }

            fun parse(): Double {
                nextChar()
                val x = parseExpression()
                if (pos < expr.length) throw RuntimeException("Unexpected: " + ch.toChar())
                return x
            }

            fun parseExpression(): Double {
                var x = parseTerm()
                while (true) {
                    if (eat('+'.code)) x += parseTerm()
                    else if (eat('-'.code)) x -= parseTerm()
                    else return x
                }
            }

            fun parseTerm(): Double {
                var x = parseFactor()
                while (true) {
                    if (eat('*'.code)) x *= parseFactor()
                    else if (eat('/'.code)) x /= parseFactor()
                    else return x
                }
            }

            fun parseFactor(): Double {
                if (eat('+'.code)) return parseFactor()
                if (eat('-'.code)) return -parseFactor()
                var x: Double
                val startPos = pos
                if (eat('('.code)) {
                    x = parseExpression()
                    eat(')'.code)
                } else if ((ch >= '0'.code && ch <= '9'.code) || ch == '.'.code) {
                    while ((ch >= '0'.code && ch <= '9'.code) || ch == '.'.code) nextChar()
                    x = expr.substring(startPos, pos).toDouble()
                } else {
                    throw RuntimeException("Unexpected: " + ch.toChar())
                }
                return x
            }
        }.parse()
    }

    private fun formatNumber(d: Double): String {
        return if (d == d.toLong().toDouble()) d.toLong().toString()
        else String.format("%.2f", d).replace(",", ".")
    }

    private fun quickFact(q: String): String? {
        // Заготовки бытовых вопросов — можно расширять
        val facts = mapOf(
            "сколько сахара в ложке" to "В чайной ложке примерно 5 граммов сахара, в столовой — около 25 граммов.",
            "сколько соли в ложке" to "В чайной ложке примерно 7 граммов соли, в столовой — около 25 граммов.",
            "сколько муки в ложке" to "В чайной ложке примерно 5 граммов муки, в столовой — около 25 граммов.",
            "сколько грамм в стакане" to "В гранёном стакане 200 миллилитров. Муки — около 130 граммов, сахара — 180 граммов, воды — 200 граммов.",
            "сколько будет" to null,
            "как варить рис" to "Рис варят 15-20 минут на медленном огне, на 1 часть риса — 2 части воды.",
            "как варить гречку" to "Гречку варят 15-20 минут, на 1 часть гречки — 2 части воды.",
            "как варить картошку" to "Картошку варят 20-25 минут после закипания.",
            "как варить яйца" to "Яйца варят: всмятку — 3 минуты, в мешочек — 5 минут, вкрутую — 10 минут.",
            "как варить макароны" to "Макароны варят 8-12 минут, зависит от сорта.",
            "сколько градусов в морозилке" to "Обычно минус 18 градусов.",
            "сколько воды пить в день" to "Взрослому человеку желательно пить 1.5-2 литра воды в день.",
            "при какой температуре варить" to "Уточните что именно варите."
        )
        for ((k, v) in facts) {
            if (q.contains(k) && v != null) return v
        }
        return null
    }

    private fun isQuestion(q: String): Boolean {
        val questionWords = listOf(
            "почему", "зачем", "как ", "что такое", "кто такой", "кто такая",
            "где ", "когда ", "сколько лет", "расскажи", "объясни"
        )
        for (w in questionWords) if (q.contains(w)) return true
        return false
    }
}
