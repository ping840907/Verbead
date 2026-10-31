package com.ping.verbead.engine

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Liquid AI LFM3-Audio-2B Byte-Level BPE Tokenizer.
 *
 * 直接解析官方 `tokenizer.json`（124,893 基礎詞表 + 140 特殊控制 Token + BPE Merges），
 * 提供與 HuggingFace Tokenizers (`ByteLevel` pre-tokenizer & decoder) 100% 對齊之編解碼能力。
 */
object Lfm3Tokenizer {

    private const val TAG = "Lfm3Tokenizer"

    // LFM3 官方特殊控制 Token ID (對齊 configuration_lfm3_audio.py & tokenizer.json)
    const val PAD_TOKEN_ID = 124_893L          // <|pad|>
    const val BOS_TOKEN_ID = 124_894L          // <|startoftext|>
    const val EOS_TEXT_TOKEN_ID = 124_895L     // <|endoftext|>
    const val IM_START_TOKEN_ID = 124_899L     // <|im_start|>
    const val IM_END_TOKEN_ID = 124_900L       // <|im_end|>
    const val THINK_START_TOKEN_ID = 124_901L  // <think>
    const val THINK_END_TOKEN_ID = 124_902L    // </think>
    const val AUDIO_START_TOKEN_ID = 125_012L  // <|audio_start|>
    const val TEXT_START_TOKEN_ID = 125_013L   // <|text_start|>
    const val TEXT_END_TOKEN_ID = 125_014L     // <|text_end|>
    const val MIXED_START_TOKEN_ID = 125_015L  // <|mixed_start|>
    const val MIXED_END_TOKEN_ID = 125_016L    // <|mixed_end|>
    const val AUDIO_TOKEN_ID = 125_031L        // <|audio|>
    const val AUDIO_OUT_TOKEN_ID = 125_032L    // <|audio_out|>

    const val VOCAB_SIZE = 128_000
    const val SPECIAL_TOKEN_START = 124_893

    @Volatile
    private var isLoaded = false

    private val tokenToId = HashMap<String, Int>(131_072)
    private val idToToken = arrayOfNulls<String>(VOCAB_SIZE)
    private val mergeRanks = HashMap<String, Int>(300_000)

    // GPT-2 / HuggingFace ByteLevel 256-byte <-> Unicode char 對照表
    private val byteToUnicode = CharArray(256)
    private val unicodeToByte = IntArray(512) { -1 }

    // Pre-tokenizer 切分規則 (對齊 tokenizer.json Split Regex)
    private val preTokenizeRegex = Regex(
        """(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\r\n\p{L}\p{N}]?\p{L}+|\p{N}{1,3}| ?[^\s\p{L}\p{N}]+[\r\n]*|\s*[\r\n]+|\s+(?!\S)|\s+"""
    )

    // 特殊 Token 切分正則
    private val specialTokens = listOf(
        "<|startoftext|>" to BOS_TOKEN_ID.toInt(),
        "<|endoftext|>" to EOS_TEXT_TOKEN_ID.toInt(),
        "<|pad|>" to PAD_TOKEN_ID.toInt(),
        "<|im_start|>" to IM_START_TOKEN_ID.toInt(),
        "<|im_end|>" to IM_END_TOKEN_ID.toInt(),
        "<think>" to THINK_START_TOKEN_ID.toInt(),
        "</think>" to THINK_END_TOKEN_ID.toInt(),
        "<|audio_start|>" to AUDIO_START_TOKEN_ID.toInt(),
        "<|text_start|>" to TEXT_START_TOKEN_ID.toInt(),
        "<|text_end|>" to TEXT_END_TOKEN_ID.toInt(),
        "<|mixed_start|>" to MIXED_START_TOKEN_ID.toInt(),
        "<|mixed_end|>" to MIXED_END_TOKEN_ID.toInt(),
        "<|audio|>" to AUDIO_TOKEN_ID.toInt(),
        "<|audio_out|>" to AUDIO_OUT_TOKEN_ID.toInt(),
    )
    private val specialTokenMap = specialTokens.toMap()
    private val specialTokenRegex = Regex(
        specialTokens.joinToString("|") { Regex.escape(it.first) }
    )

    init {
        initByteLevelTables()
    }

    private fun initByteLevelTables() {
        val bs = ArrayList<Int>(256)
        val cs = ArrayList<Int>(256)

        for (b in '!'.code..'~'.code) {
            bs.add(b)
            cs.add(b)
        }
        for (b in '¡'.code..'¬'.code) {
            bs.add(b)
            cs.add(b)
        }
        for (b in '®'.code..'ÿ'.code) {
            bs.add(b)
            cs.add(b)
        }

        val present = BooleanArray(256)
        for (b in bs) present[b] = true

        var n = 0
        for (b in 0 until 256) {
            if (!present[b]) {
                bs.add(b)
                cs.add(256 + n)
                n++
            }
        }

        for (i in 0 until 256) {
            val b = bs[i]
            val c = cs[i].toChar()
            byteToUnicode[b] = c
            if (c.code < unicodeToByte.size) {
                unicodeToByte[c.code] = b
            }
        }
    }

    fun isReady(): Boolean = isLoaded

    /**
     * 串流解析 `tokenizer.json`，載入完整 vocab、added_tokens 與 merges。
     */
    @Synchronized
    fun load(tokenizerFile: File): Boolean {
        if (isLoaded) return true
        if (!tokenizerFile.exists() || tokenizerFile.length() < 1024L) {
            Log.w(TAG, "Tokenizer file missing or invalid: ${tokenizerFile.absolutePath}")
            return false
        }

        val t0 = System.currentTimeMillis()
        try {
            tokenToId.clear()
            mergeRanks.clear()
            for (i in idToToken.indices) idToToken[i] = null

            // 預先註冊核心特殊 Token
            for ((str, id) in specialTokens) {
                tokenToId[str] = id
                if (id in idToToken.indices) {
                    idToToken[id] = str
                }
            }

            BufferedReader(
                InputStreamReader(FileInputStream(tokenizerFile), StandardCharsets.UTF_8),
                65536
            ).use { reader ->
                parseTokenizerJson(reader)
            }

            isLoaded = tokenToId.size > 100_000
            val elapsed = System.currentTimeMillis() - t0
            Log.i(
                TAG,
                "Loaded LFM3 tokenizer.json in ${elapsed}ms (vocab=${tokenToId.size}, merges=${mergeRanks.size})"
            )
            return isLoaded
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to parse tokenizer.json: ${e.message}", e)
            return false
        }
    }

    /**
     * 將包含特殊控制標籤的文字編碼為 Token ID 陣列。
     */
    fun encode(text: String): LongArray {
        if (text.isEmpty()) return LongArray(0)
        val out = ArrayList<Long>(text.length + 8)
        var lastIdx = 0

        for (match in specialTokenRegex.findAll(text)) {
            if (match.range.first > lastIdx) {
                val segment = text.substring(lastIdx, match.range.first)
                encodeOrdinaryText(segment, out)
            }
            val specialId = specialTokenMap[match.value]
            if (specialId != null) {
                out.add(specialId.toLong())
            }
            lastIdx = match.range.last + 1
        }

        if (lastIdx < text.length) {
            encodeOrdinaryText(text.substring(lastIdx), out)
        }

        return LongArray(out.size) { out[it] }
    }

    private fun encodeOrdinaryText(text: String, out: MutableList<Long>) {
        if (text.isEmpty()) return
        for (match in preTokenizeRegex.findAll(text)) {
            val piece = match.value
            val utf8 = piece.toByteArray(StandardCharsets.UTF_8)
            encodeBytePiece(utf8, out)
        }
    }

    private fun encodeBytePiece(bytes: ByteArray, out: MutableList<Long>) {
        if (bytes.isEmpty()) return

        // 先檢查整個單詞是否直接命中詞表
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            sb.append(byteToUnicode[b.toInt() and 0xFF])
        }
        val whole = sb.toString()
        val wholeId = tokenToId[whole]
        if (wholeId != null) {
            out.add(wholeId.toLong())
            return
        }

        // 若有載入 BPE merges 則執行標準 BPE 合併
        var parts = Array(bytes.size) { i ->
            byteToUnicode[bytes[i].toInt() and 0xFF].toString()
        }

        if (mergeRanks.isNotEmpty()) {
            while (parts.size > 1) {
                var bestRank = Int.MAX_VALUE
                var bestIdx = -1
                for (i in 0 until parts.size - 1) {
                    val key = "${parts[i]}\u0000${parts[i + 1]}"
                    val rank = mergeRanks[key] ?: Int.MAX_VALUE
                    if (rank < bestRank) {
                        bestRank = rank
                        bestIdx = i
                    }
                }
                if (bestIdx == -1 || bestRank == Int.MAX_VALUE) break

                val first = parts[bestIdx]
                val second = parts[bestIdx + 1]
                val merged = first + second
                val nextParts = ArrayList<String>(parts.size - 1)
                var i = 0
                while (i < parts.size) {
                    if (i < parts.size - 1 && parts[i] == first && parts[i + 1] == second) {
                        nextParts.add(merged)
                        i += 2
                    } else {
                        nextParts.add(parts[i])
                        i++
                    }
                }
                parts = nextParts.toTypedArray()
            }

            for (p in parts) {
                val id = tokenToId[p]
                if (id != null) {
                    out.add(id.toLong())
                } else {
                    // 回退至單 byte 查找
                    for (ch in p) {
                        val bId = tokenToId[ch.toString()]
                        if (bId != null) out.add(bId.toLong())
                    }
                }
            }
        } else {
            // Greedy 最長前綴匹配回退
            var i = 0
            val n = whole.length
            while (i < n) {
                var matched = false
                val maxEnd = minOf(n, i + 24)
                for (j in maxEnd downTo i + 1) {
                    val sub = whole.substring(i, j)
                    val id = tokenToId[sub]
                    if (id != null) {
                        out.add(id.toLong())
                        i = j
                        matched = true
                        break
                    }
                }
                if (!matched) i++
            }
        }
    }

    /**
     * 將模型輸出的 Token ID 序列還原為 UTF-8 繁/簡中文與英文文字。
     */
    fun decode(tokenIds: List<Int>, skipSpecialTokens: Boolean = true): String {
        if (tokenIds.isEmpty()) return ""
        val byteOut = java.io.ByteArrayOutputStream(tokenIds.size * 4)

        for (id in tokenIds) {
            if (id < 0 || id >= VOCAB_SIZE) continue
            if (skipSpecialTokens && id >= SPECIAL_TOKEN_START) continue

            val tokenStr = idToToken[id] ?: continue
            if (!skipSpecialTokens && id >= SPECIAL_TOKEN_START) {
                val rawBytes = tokenStr.toByteArray(StandardCharsets.UTF_8)
                byteOut.write(rawBytes, 0, rawBytes.size)
                continue
            }

            for (i in 0 until tokenStr.length) {
                val code = tokenStr[i].code
                if (code < unicodeToByte.size && unicodeToByte[code] != -1) {
                    byteOut.write(unicodeToByte[code])
                } else {
                    val fallback = tokenStr[i].toString().toByteArray(StandardCharsets.UTF_8)
                    byteOut.write(fallback, 0, fallback.size)
                }
            }
        }

        return String(byteOut.toByteArray(), StandardCharsets.UTF_8)
    }

    /**
     * 建構 LFM3-Audio 官方 ASR 提示詞 Token 序列：
     * `<|startoftext|><|im_start|>system\nPerform ASR.<|im_end|>\n<|im_start|>user\n` + `<|audio|>` * numAudioTokens + `<|im_end|>\n<|im_start|>assistant\n`
     */
    fun buildAsrPrompt(numAudioTokens: Int, languageHint: String? = null): LongArray {
        val sysInstruction = if (languageHint.isNullOrBlank()) {
            "Perform ASR."
        } else {
            "Perform ASR in $languageHint."
        }
        val prefix = encode("<|startoftext|><|im_start|>system\n$sysInstruction<|im_end|>\n<|im_start|>user\n")
        val suffix = encode("<|im_end|>\n<|im_start|>assistant\n")
        val result = LongArray(prefix.size + numAudioTokens + suffix.size)
        System.arraycopy(prefix, 0, result, 0, prefix.size)
        for (i in 0 until numAudioTokens) {
            result[prefix.size + i] = AUDIO_TOKEN_ID
        }
        System.arraycopy(suffix, 0, result, prefix.size + numAudioTokens, suffix.size)
        return result
    }

    /**
     * 建構 LFM3-Audio 官方 TTS 語音合成提示詞 Token 序列：
     * `<|startoftext|><|im_start|>system\nPerform TTS in Chinese using a female voice.<|im_end|>\n<|im_start|>user\n{text}<|im_end|>\n<|im_start|>assistant\n<|audio_start|>`
     */
    fun buildTtsPrompt(text: String, language: String = "Chinese", voice: String = "female"): LongArray {
        val fullPrompt = "<|startoftext|><|im_start|>system\nPerform TTS in $language using a $voice voice.<|im_end|>\n" +
            "<|im_start|>user\n$text<|im_end|>\n" +
            "<|im_start|>assistant\n<|audio_start|>"
        return encode(fullPrompt)
    }

    /**
     * 建構 LFM3-Audio 語音對話 (Speech-to-Text Chat) 提示詞 Token 序列。
     */
    fun buildSpeechChatPrompt(numAudioTokens: Int, systemPrompt: String): LongArray {
        val prefix = encode("<|startoftext|><|im_start|>system\n$systemPrompt<|im_end|>\n<|im_start|>user\n")
        val suffix = encode("<|im_end|>\n<|im_start|>assistant\n<think></think>")
        val result = LongArray(prefix.size + numAudioTokens + suffix.size)
        System.arraycopy(prefix, 0, result, 0, prefix.size)
        for (i in 0 until numAudioTokens) {
            result[prefix.size + i] = AUDIO_TOKEN_ID
        }
        System.arraycopy(suffix, 0, result, prefix.size + numAudioTokens, suffix.size)
        return result
    }

    // ── 高速串流 JSON 解析器 (針對 tokenizer.json 結構最佳化) ──────────────────────

    private fun parseTokenizerJson(reader: BufferedReader) {
        var inVocab = false
        var inMerges = false
        var mergeIdx = 0
        var pendingFirstMerge: String? = null

        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val rawLine = line!!
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue

            if (!inVocab && !inMerges) {
                if (trimmed.startsWith("\"vocab\"")) {
                    inVocab = true
                    continue
                }
                if (trimmed.startsWith("\"merges\"")) {
                    inMerges = true
                    pendingFirstMerge = null
                    continue
                }
                continue
            }

            if (inVocab) {
                if (trimmed.startsWith("}")) {
                    inVocab = false
                    continue
                }
                // 格式: "token": 12345,
                val colonIdx = trimmed.lastIndexOf(':')
                if (colonIdx > 1 && trimmed[0] == '"') {
                    val rawKey = trimmed.substring(1, colonIdx).trim()
                    if (rawKey.endsWith('"')) {
                        val key = unescapeJsonString(rawKey.substring(0, rawKey.length - 1))
                        val valStr = trimmed.substring(colonIdx + 1).trim().removeSuffix(",")
                        val id = valStr.toIntOrNull()
                        if (id != null && id in 0 until VOCAB_SIZE) {
                            tokenToId[key] = id
                            idToToken[id] = key
                        }
                    }
                }
            } else if (inMerges) {
                if (rawLine.startsWith("    ]")) {
                    inMerges = false
                    continue
                }
                if (trimmed == "[" || trimmed == "]," || trimmed == "]") {
                    pendingFirstMerge = null
                    continue
                }
                if (trimmed.startsWith("\"")) {
                    val endQuote = findClosingQuote(trimmed, 1)
                    if (endQuote > 0) {
                        val str = unescapeJsonString(trimmed.substring(1, endQuote))
                        if (pendingFirstMerge == null) {
                            pendingFirstMerge = str
                        } else {
                            mergeRanks["$pendingFirstMerge\u0000$str"] = mergeIdx++
                            pendingFirstMerge = null
                        }
                    }
                } else if (trimmed.startsWith("[") && trimmed.contains(",")) {
                    val pair = parseTwoElementStringArray(trimmed)
                    if (pair != null) {
                        mergeRanks["${pair.first}\u0000${pair.second}"] = mergeIdx++
                    }
                }
            }
        }
    }

    private fun parseTwoElementStringArray(s: String): Pair<String, String>? {
        val q1 = s.indexOf('"')
        if (q1 < 0) return null
        val q2 = findClosingQuote(s, q1 + 1)
        if (q2 < 0) return null
        val q3 = s.indexOf('"', q2 + 1)
        if (q3 < 0) return null
        val q4 = findClosingQuote(s, q3 + 1)
        if (q4 < 0) return null

        val first = unescapeJsonString(s.substring(q1 + 1, q2))
        val second = unescapeJsonString(s.substring(q3 + 1, q4))
        return first to second
    }

    private fun findClosingQuote(s: String, start: Int): Int {
        var i = start
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == '"') return i
            i++
        }
        return -1
    }

    private fun unescapeJsonString(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val next = s[i + 1]) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'u' -> {
                        if (i + 5 < s.length) {
                            val hex = s.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 6
                                continue
                            }
                        }
                        sb.append(c)
                        i++
                    }
                    else -> {
                        sb.append(next)
                        i += 2
                    }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
