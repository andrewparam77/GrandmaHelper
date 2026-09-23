package com.example.babacontrol

import android.content.Context

object Prefs {
    private const val NAME = "baba"
    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // ===== старое =====
    fun getMode(c: Context): String? = sp(c).getString("mode", null)
    fun setMode(c: Context, m: String?) {
        sp(c).edit().apply {
            if (m == null) remove("mode") else putString("mode", m)
        }.apply()
    }

    fun getRoom(c: Context): String = sp(c).getString("room", "") ?: ""
    fun setRoom(c: Context, r: String) {
        sp(c).edit().putString("room", r.trim()).apply()
    }

    const val SB_URL = "https://ТВОЙ-ПРОЕКТ.supabase.co"
    const val SB_KEY = "ТВОЙ-ANON-КЛЮЧ"

    // ===== grandma: добавили =====
    private fun gsp(c: Context) = c.getSharedPreferences("grandma_helper_prefs", Context.MODE_PRIVATE)

    fun getProvider(c: Context): String =
        gsp(c).getString("provider", "gemini") ?: "gemini"
    fun setProvider(c: Context, v: String) =
        gsp(c).edit().putString("provider", v).apply()

    fun getKey(c: Context, provider: String): String = when (provider) {
        "gemini" -> gsp(c).getString("key_gemini", "").orEmpty()
        "deepseek" -> gsp(c).getString("key_deepseek", "").orEmpty()
        "claude" -> gsp(c).getString("key_claude", "").orEmpty()
        "custom" -> gsp(c).getString("key_custom", "").orEmpty()
        else -> ""
    }
    fun setKey(c: Context, provider: String, value: String) {
        val k = when (provider) {
            "gemini" -> "key_gemini"
            "deepseek" -> "key_deepseek"
            "claude" -> "key_claude"
            "custom" -> "key_custom"
            else -> return
        }
        gsp(c).edit().putString(k, value.trim()).apply()
    }

    fun getBaseUrl(c: Context): String =
        gsp(c).getString("base_custom", "").orEmpty()
    fun setBaseUrl(c: Context, v: String) =
        gsp(c).edit().putString("base_custom", v.trim()).apply()

    fun getCustomModel(c: Context): String =
        gsp(c).getString("model_custom", "").orEmpty()
    fun setCustomModel(c: Context, v: String) =
        gsp(c).edit().putString("model_custom", v.trim()).apply()

    fun getGrandmaName(c: Context): String =
        gsp(c).getString("grandma_name", "").orEmpty()
    fun setGrandmaName(c: Context, v: String) =
        gsp(c).edit().putString("grandma_name", v.trim()).apply()
}