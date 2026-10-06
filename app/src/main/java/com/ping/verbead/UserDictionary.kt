package com.ping.verbead

import android.content.Context
import org.json.JSONObject
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Reader

/**
 * Stores user-defined ASR post-correction entries in SharedPreferences.
 *
 * Each entry maps a recognition error (or shorthand) → the desired output.
 * Applied after ASR + OpenCC, longest-match-first to avoid partial overlaps.
 *
 * Storage format: JSON object  {"wrong": "correct", ...}
 */
object UserDictionary {

    private const val PREF_NAME = "user_dict"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_CLEANED_SEEDED = "has_cleaned_seeded_v1"

    fun load(context: Context): Map<String, String> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_ENTRIES, null) ?: return emptyMap()
        val userMap = try {
            val obj = JSONObject(json)
            buildMap { obj.keys().forEach { k -> put(k, obj.getString(k)) } }
        } catch (_: Exception) {
            emptyMap()
        }

        // 自動清理先前版本寫入自定義詞庫的預設蘸/沾規則，回歸純淨正則管線處理
        if (!prefs.getBoolean(KEY_CLEANED_SEEDED, false)) {
            val cleaned = userMap.filter { (k, v) ->
                !(k.contains("蘸") || (k.startsWith("沾") && v.startsWith("蘸")))
            }
            save(context, cleaned)
            prefs.edit().putBoolean(KEY_CLEANED_SEEDED, true).apply()
            return cleaned
        }

        return userMap
    }

    fun save(context: Context, entries: Map<String, String>) {
        val obj = JSONObject()
        entries.forEach { (k, v) -> obj.put(k, v) }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ENTRIES, obj.toString()).apply()
    }

    fun add(context: Context, from: String, to: String) {
        val entries = load(context).toMutableMap()
        entries[from] = to
        save(context, entries)
    }

    fun remove(context: Context, from: String) {
        val entries = load(context).toMutableMap()
        entries.remove(from)
        save(context, entries)
    }

    /** Apply dictionary substitutions to [text], longest key first. */
    fun apply(text: String, entries: Map<String, String>): String {
        if (entries.isEmpty() || text.isEmpty()) return text
        var result = text
        entries.entries.sortedByDescending { it.key.length }.forEach { (from, to) ->
            if (from != to && from.isNotEmpty()) {
                result = result.replace(from, to)
            }
        }
        return result
    }

    data class Suggestion(val matchedText: String, val replacement: String, val from: String)

    /**
     * Find near-miss ASR errors in [text]: substrings that are NOT an exact
     * dictionary key but are within edit-distance 1 of one. Exact matches are
     * skipped (nothing to suggest). Only keys of length 2+ are considered to
     * avoid noisy single-character matches.
     */
    fun findFuzzySuggestions(text: String, entries: Map<String, String>): List<Suggestion> {
        if (text.isEmpty() || entries.isEmpty()) return emptyList()
        val results = mutableListOf<Suggestion>()
        val seen = mutableSetOf<String>()
        for ((from, to) in entries) {
            if (from.length < 2 || from == to) continue
            // Compare windows of length from.length-1 .. from.length+1 to tolerate
            // one insertion/deletion in addition to one substitution.
            for (winLen in (from.length - 1)..(from.length + 1)) {
                if (winLen < 1 || winLen > text.length) continue
                for (start in 0..(text.length - winLen)) {
                    val window = text.substring(start, start + winLen)
                    if (window == from || !seen.add(window)) continue
                    if (levenshtein(window, from) == 1) {
                        results += Suggestion(window, to, from)
                    }
                }
            }
        }
        return results
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            }
        }
        return dp[a.length][b.length]
    }

    private fun escapeCsvField(field: String): String {
        return if (field.contains(",") || field.contains("\"") || field.contains("\n") || field.contains("\r")) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }
    }

    /**
     * Exports [entries] to [outputStream] in RFC 4180 CSV format (UTF-8).
     */
    fun exportToCsv(entries: Map<String, String>, outputStream: OutputStream) {
        val writer = OutputStreamWriter(outputStream, Charsets.UTF_8).buffered()
        writer.write("原詞,替換詞\r\n")
        entries.toSortedMap().forEach { (from, to) ->
            writer.write("${escapeCsvField(from)},${escapeCsvField(to)}\r\n")
        }
        writer.flush()
    }

    private fun parseCsv(reader: Reader): List<List<String>> {
        val records = mutableListOf<List<String>>()
        val currentRecord = mutableListOf<String>()
        val currentField = StringBuilder()
        var inQuotes = false

        var r = reader.read()
        // Skip UTF-8 BOM if present
        if (r == 0xFEFF) {
            r = reader.read()
        }

        while (r != -1) {
            val c = r.toChar()
            if (inQuotes) {
                if (c == '"') {
                    val next = reader.read()
                    if (next != -1 && next.toChar() == '"') {
                        currentField.append('"')
                    } else {
                        inQuotes = false
                        r = next
                        continue
                    }
                } else {
                    currentField.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> {
                        currentRecord.add(currentField.toString().trim())
                        currentField.setLength(0)
                    }
                    '\r' -> {
                        // ignore carriage return
                    }
                    '\n' -> {
                        currentRecord.add(currentField.toString().trim())
                        currentField.setLength(0)
                        if (currentRecord.any { it.isNotEmpty() }) {
                            records.add(ArrayList(currentRecord))
                        }
                        currentRecord.clear()
                    }
                    else -> currentField.append(c)
                }
            }
            r = reader.read()
        }
        if (currentField.isNotEmpty() || currentRecord.isNotEmpty()) {
            currentRecord.add(currentField.toString().trim())
            if (currentRecord.any { it.isNotEmpty() }) {
                records.add(currentRecord)
            }
        }
        return records
    }

    /**
     * Parses CSV from [inputStream] into a Map of (from -> to).
     * Supports single-column (to=from) and two-column (from, to), skips header lines.
     */
    fun importFromCsv(inputStream: InputStream): Map<String, String> {
        val records = parseCsv(InputStreamReader(inputStream, Charsets.UTF_8).buffered())
        val result = mutableMapOf<String, String>()
        for (row in records) {
            if (row.isEmpty()) continue
            val col0 = row.getOrNull(0)?.trim() ?: ""
            if (col0.isEmpty()) continue
            // Skip headers
            if ((col0 == "原詞" || col0.equals("from", ignoreCase = true) || col0 == "錯誤詞") &&
                (row.getOrNull(1)?.trim() in listOf("替換詞", "to", "正確詞", "目標詞", null, ""))) {
                continue
            }
            val col1 = row.getOrNull(1)?.trim()
            val from = col0
            val to = if (!col1.isNullOrEmpty()) col1 else col0
            result[from] = to
        }
        return result
    }

    /**
     * Imports CSV from [inputStream], merges into UserDictionary SharedPreferences, and returns imported count.
     */
    fun importAndMergeFromCsv(context: Context, inputStream: InputStream): Int {
        val imported = importFromCsv(inputStream)
        if (imported.isEmpty()) return 0
        val current = load(context).toMutableMap()
        current.putAll(imported)
        save(context, current)
        return imported.size
    }
}
