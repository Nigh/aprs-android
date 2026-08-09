package com.nigh.aprstx

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class LogType { SUCCESS, ERROR, WARNING, INFO }

data class LogEntry(
    val id: String,
    val message: String,
    val type: LogType,
    val timestampMs: Long = System.currentTimeMillis(),
)

class LogStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("aprs-logs", Context.MODE_PRIVATE)
    private val _logs = MutableStateFlow(load())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun add(message: String, type: LogType = LogType.INFO) {
        val entry = LogEntry(id = System.currentTimeMillis().toString(), message = message, type = type)
        val next = (listOf(entry) + _logs.value).take(MAX_LOGS)
        _logs.value = next
        persist(next)
    }

    fun clear() {
        _logs.value = emptyList()
        prefs.edit().remove(KEY).apply()
    }

    private fun load(): List<LogEntry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        LogEntry(
                            id = o.getString("id"),
                            message = o.getString("message"),
                            type = LogType.valueOf(o.optString("type", "INFO")),
                            timestampMs = o.getLong("timestampMs"),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persist(logs: List<LogEntry>) {
        val arr = JSONArray()
        logs.forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("message", e.message)
                    .put("type", e.type.name)
                    .put("timestampMs", e.timestampMs),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "recent"
        private const val MAX_LOGS = 100
    }
}
