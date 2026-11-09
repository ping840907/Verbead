package com.ping.verbead.i18n

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 防倒退：版面、選單與 Kotlin 原始碼裡寫死的中日韓字只許減少、不許增加。
 * 現有違規記在 app/src/test/resources/i18n-allowlist.txt（每行「相對路徑 數量」）；
 * 語言規則檔是永久例外。
 */
class NoHardcodedCjkTest {

    private companion object {
        const val ALLOWLIST = "app/src/test/resources/i18n-allowlist.txt"
    }

    private val cjk = Regex("[぀-ヿ㐀-鿿가-힯]")
    private val stringLiteral = Regex("\"(?:[^\"\\\\]|\\\\.)*\"")
    private val layoutAttr = Regex("android:(text|hint|contentDescription|title|label)=\"[^\"@][^\"]*\"")

    private val ruleFiles = setOf(
        "java/com/ping/verbead/engine/SpokenNumberConverter.kt",
        "java/com/ping/verbead/engine/lang/ZhTwRules.kt",
        "java/com/ping/verbead/engine/lang/JapaneseNumbers.kt",
        "java/com/ping/verbead/engine/lang/KoreanNumbers.kt",
        "java/com/ping/verbead/engine/lang/SpokenNumbers.kt"
    )

    private fun projectRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, ALLOWLIST).exists()) dir = dir.parentFile
        return requireNotNull(dir) { "找不到 $ALLOWLIST" }
    }

    private fun scan(): Map<String, Int> {
        val main = File(projectRoot(), "app/src/main")
        val result = sortedMapOf<String, Int>()
        main.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(main).invariantSeparatorsPath
            val n = when {
                rel in ruleFiles -> 0
                rel.endsWith(".kt") -> f.readLines().count { line ->
                    val t = line.trim()
                    !(t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) &&
                        stringLiteral.findAll(line).any { cjk.containsMatchIn(it.value) }
                }
                (rel.startsWith("res/layout/") || rel.startsWith("res/menu/")) && rel.endsWith(".xml") ->
                    layoutAttr.findAll(f.readText()).count { cjk.containsMatchIn(it.value) }
                else -> 0
            }
            if (n > 0) result[rel] = n
        }
        return result
    }

    private fun allowlist(): Map<String, Int> =
        File(projectRoot(), ALLOWLIST).readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val i = line.lastIndexOf(' ')
                line.substring(0, i).trim() to line.substring(i + 1).toInt()
            }

    @Test
    fun noNewHardcodedCjk() {
        val allowed = allowlist()
        val bad = scan().filter { (file, n) -> n > (allowed[file] ?: 0) }
            .map { (file, n) -> "$file：$n（允許 ${allowed[file] ?: 0}）" }
        assertTrue(
            "發現新增的寫死中日韓文字，請改用 string resource：\n" + bad.joinToString("\n"),
            bad.isEmpty()
        )
    }

    @Test
    fun allowlistIsNotStale() {
        val actual = scan()
        val stale = allowlist().filter { (file, n) -> (actual[file] ?: 0) < n }
            .map { (file, n) -> "$file：允許 $n，實際 ${actual[file] ?: 0}" }
        assertTrue(
            "違規已減少，請同步調降 $ALLOWLIST（只減不增）：\n" + stale.joinToString("\n"),
            stale.isEmpty()
        )
    }
}
