package com.ping.verbead.watch

import org.json.JSONObject

/**
 * Pebble 設定頁慣例：頁面導向 `pebblejs://close#<encoded JSON>`，
 * Core Devices App 會把該字串以 `webviewclosed` 事件交給 PKJS。
 * 模擬器會在網址帶 `return_to`，此時改導回該位址。
 *
 * 純字串運算，不依賴 Android 之外的任何東西（org.json 僅用於跳脫）。
 */
object PairingPage {

    fun render(token: String): String {
        // 以 JSON 字串字面值嵌入，避免 token 內容影響 HTML/JS 語法
        val tokenLiteral = JSONObject.quote(token).replace("</", "<\\/")
        return """<!doctype html>
<html lang="zh-Hant"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>配對珠璣</title>
<style>
body{font-family:sans-serif;margin:0;padding:24px;background:#101418;color:#e6e9ed;text-align:center}
h1{font-size:20px}p{color:#9aa4ad;line-height:1.6}
button{margin-top:24px;padding:14px 32px;font-size:18px;border:0;border-radius:28px;background:#4f8cff;color:#fff}
</style></head><body>
<h1>配對珠璣 (Verbead)</h1>
<p>按下按鈕，手錶上的「珠璣語音」就能把語音送到這支手機的珠璣 App。</p>
<button id="b">完成配對</button>
<script>
var TOKEN = $tokenLiteral;
document.getElementById('b').onclick = function () {
  var m = /[?&]return_to=([^&]+)/.exec(location.search);
  var base = m ? decodeURIComponent(m[1]) : 'pebblejs://close#';
  location.href = base + encodeURIComponent(JSON.stringify({ token: TOKEN }));
};
</script>
</body></html>"""
    }
}
