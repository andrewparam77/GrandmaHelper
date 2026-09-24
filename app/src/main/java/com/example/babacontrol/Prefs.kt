package com.example.babacontrol

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("grandma_helper_prefs", Context.MODE_PRIVATE)

    fun getProvider(c: Context): String =
        sp(c).getString("provider", "gemini") ?: "gemini"

    fun setProvider(c: Context, v: String) {
        sp(c).edit().putString("provider", v).apply()
    }

    fun getKey(c: Context, provider: String): String {
        val k = when (provider) {
            "gemini" -> "key_gemini"
            "deepseek" -> "key_deepseek"
            "claude" -> "key_claude"
            "custom" -> "key_custom"
            else -> return ""
        }
        return sp(c).getString(k, "").orEmpty()
    }

    fun setKey(c: Context, provider: String, value: String) {
        val k = when (provider) {
            "gemini" -> "key_gemini"
            "deepseek" -> "key_deepseek"
            "claude" -> "key_claude"
            "custom" -> "key_custom"
            else -> return
        }
        sp(c).edit().putString(k, value.trim()).apply()
    }

    fun getBaseUrl(c: Context): String =
        sp(c).getString("base_custom", "").orEmpty()

    fun setBaseUrl(c: Context, v: String) {
        sp(c).edit().putString("base_custom", v.trim()).apply()
    }

    fun getCustomModel(c: Context): String =
        sp(c).getString("model_custom", "").orEmpty()

    fun setCustomModel(c: Context, v: String) {
        sp(c).edit().putString("model_custom", v.trim()).apply()
    }

    fun getGrandmaName(c: Context): String =
        sp(c).getString("grandma_name", "").orEmpty()

    fun setGrandmaName(c: Context, v: String) {
        sp(c).edit().putString("grandma_name", v.trim()).apply()
    }
}
