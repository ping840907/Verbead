package com.ping.verbead

import android.content.Context
import com.ping.verbead.engine.ModelConfig
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

    /** 預設常用語的字串資源 ID；首次啟動時依當下語言解析並存入偏好 */
    val DEFAULT_PHRASE_RES = listOf(
        R.string.quick_phrase_default_1,
        R.string.quick_phrase_default_2,
        R.string.quick_phrase_default_3,
        R.string.quick_phrase_default_4,
        R.string.quick_phrase_default_5,
        R.string.quick_phrase_default_6
    )

    fun defaultPhrases(context: Context): List<String> = DEFAULT_PHRASE_RES.map { context.getString(it) }

    // ─── Custom Quick Phrases ───

    fun load(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_INITIALIZED, false)) {
            val defaults = defaultPhrases(context)
            save(context, defaults)
            prefs.edit().putBoolean(KEY_INITIALIZED, true).apply()
            return defaults
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

    // ─── Input History (從其他模式輸入的內容，最多保留六項) ───

    fun loadHistory(context: Context): List<String> {
        val limit = ModelConfig.getHistoryLimit(context)
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
            list.take(limit)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveHistory(context: Context, history: List<String>) {
        val limit = ModelConfig.getHistoryLimit(context)
        val array = JSONArray()
        history.take(limit).forEach { item ->
            if (item.isNotBlank()) {
                array.put(item.trim())
            }
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HISTORY, array.toString())
            .apply()
    }

    /** [force]: keep the text even when history is switched off (used for text that could not be filled in). */
    fun addHistory(context: Context, text: String, force: Boolean = false) {
        if (!force && !ModelConfig.isHistoryEnabled(context)) return
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        val limit = ModelConfig.getHistoryLimit(context)
        val current = loadHistory(context).toMutableList()
        // 去除重複，移至最前端
        current.removeAll { it == trimmed }
        current.add(0, trimmed)
        val trimmedList = current.take(limit)
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
