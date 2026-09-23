package com.example.babacontrol.grandma

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class GrandmaMsg(
    val text: String,
    val isUser: Boolean,
    val ts: Long = System.currentTimeMillis()
)

class GrandmaMemory(context: Context) {
    private val historyFile = File(context.filesDir, "grandma_history.json")
    private val factsFile = File(context.filesDir, "grandma_facts.json")
    private val maxMessages = 200

    fun load(): MutableList<GrandmaMsg> {
        if (!historyFile.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(historyFile.readText())
            val list = mutableListOf<GrandmaMsg>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(GrandmaMsg(
                    o.getString("text"),
                    o.getBoolean("isUser"),
                    o.optLong("ts")
                ))
            }
            list
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun save(list: List<GrandmaMsg>) {
        val trimmed = if (list.size > maxMessages) list.takeLast(maxMessages) else list
        val arr = JSONArray()
        for (m in trimmed) {
            arr.put(JSONObject().apply {
                put("text", m.text)
                put("isUser", m.isUser)
                put("ts", m.ts)
            })
        }
        historyFile.writeText(arr.toString())
    }

    fun loadFacts(): List<String> {
        if (!factsFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(factsFile.readText())
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveFacts(facts: List<String>) {
        val arr = JSONArray()
        facts.forEach { arr.put(it) }
        factsFile.writeText(arr.toString())
    }

    fun clearAll() {
        historyFile.delete()
        factsFile.delete()
    }
}