package com.ping.verbead

import android.content.Context
import org.json.JSONArray

/**
 * Manages user-defined quick phrases (常用語 / 片語) and recent input history
 * from other modes (語音 / 條碼 / OCR 回溯) stored in SharedPreferences.
 * Provides default preset phrases upon initial launch and persists list modifications.
 */
object QuickPhrasesManager {

    private const val PREF_NAME = "quick_phrases_pref"
    private const val KEY_PHRASES = "phrases_json"
    private const val KEY_HISTORY = "input_history_json"
    private const val KEY_INITIALIZED = "has_initialized_defaults_v1"

    const val MAX_HISTORY_SIZE = 6

    val DEFAULT_PHRASES = listOf(
        "好的，收到！",
        "稍等我一下，馬上處理。",
        "非常感謝！辛苦了！",
        "已發送，請查收。",
        "請稍候，我稍後回覆您。",
        "沒問題，我來處理。"
    )

    // ─── Custom Quick Phrases ───

    fun load(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_INITIALIZED, false)) {
            save(context, DEFAULT_PHRASES)
            prefs.edit().putBoolean(KEY_INITIALIZED, true).apply()
            return DEFAULT_PHRASES
        }

        val json = prefs.getString(KEY_PHRASES, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            val list = mutableListOf<String>()
            for (i in 0 until array.length()) {
                val item = array.optString(i, "")
                if (item.isNotBlank()) {
                    list.add(item)
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, phrases: List<String>) {
        val array = JSONArray()
        phrases.forEach { phrase ->
            if (phrase.isNotBlank()) {
                array.put(phrase.trim())
            }
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PHRASES, array.toString())
            .apply()
    }

    fun add(context: Context, phrase: String) {
        val trimmed = phrase.trim()
        if (trimmed.isBlank()) return
        val list = load(context).toMutableList()
        list.add(0, trimmed)
        save(context, list)
    }

    fun update(context: Context, index: Int, newPhrase: String) {
        val trimmed = newPhrase.trim()
        if (trimmed.isBlank()) return
        val list = load(context).toMutableList()
        if (index in list.indices) {
            list[index] = trimmed
            save(context, list)
        }
    }

    fun removeAt(context: Context, index: Int) {
        val list = load(context).toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            save(context, list)
        }
    }

    fun movePhrase(context: Context, fromIndex: Int, toIndex: Int) {
        val list = load(context).toMutableList()
        if (fromIndex in list.indices && toIndex in list.indices && fromIndex != toIndex) {
            val item = list.removeAt(fromIndex)
            list.add(toIndex, item)
            save(context, list)
        }
    }

    // ─── Input History (從其他模式輸入的內容，最多保留六項) ───

    fun loadHistory(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            val list = mutableListOf<String>()
            for (i in 0 until array.length()) {
                val item = array.optString(i, "")
                if (item.isNotBlank()) {
                    list.add(item)
                }
            }
            list.take(MAX_HISTORY_SIZE)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveHistory(context: Context, history: List<String>) {
        val array = JSONArray()
        history.take(MAX_HISTORY_SIZE).forEach { item ->
            if (item.isNotBlank()) {
                array.put(item.trim())
            }
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HISTORY, array.toString())
            .apply()
    }

    fun addHistory(context: Context, text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        val current = loadHistory(context).toMutableList()
        // 去除重複，移至最前端
        current.removeAll { it == trimmed }
        current.add(0, trimmed)
        val trimmedList = current.take(MAX_HISTORY_SIZE)
        saveHistory(context, trimmedList)
    }

    fun removeHistoryAt(context: Context, index: Int) {
        val current = loadHistory(context).toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            saveHistory(context, current)
        }
    }

    fun clearHistory(context: Context) {
        saveHistory(context, emptyList())
    }
}
