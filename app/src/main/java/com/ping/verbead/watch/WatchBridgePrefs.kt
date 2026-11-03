package com.ping.verbead.watch

import android.content.Context
import java.security.SecureRandom

/** 手錶橋接的設定與配對 token。 */
object WatchBridgePrefs {

    private const val PREF_NAME = "watch_bridge"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_AUTO_STOP = "auto_stop"
    private const val KEY_TOKEN = "token"
    private const val KEY_PAIRING_UNTIL = "pairing_until"

    const val PAIRING_WINDOW_MS = 120_000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()

    /** 說完話後由 Verbead 偵測靜音並自動結束，手錶不必再按鍵。預設開啟。 */
    fun isAutoStop(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_STOP, true)

    fun setAutoStop(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_AUTO_STOP, enabled).apply()

    /** 128 位元隨機 token，首次使用時產生。 */
    @Synchronized
    fun token(context: Context): String {
        val p = prefs(context)
        p.getString(KEY_TOKEN, null)?.takeIf { it.length == 32 }?.let { return it }
        return newToken().also { p.edit().putString(KEY_TOKEN, it).apply() }
    }

    /** 重設後，已配對的手錶需要重新配對。 */
    @Synchronized
    fun regenerateToken(context: Context): String =
        newToken().also { prefs(context).edit().putString(KEY_TOKEN, it).apply() }

    fun openPairing(context: Context, durationMs: Long = PAIRING_WINDOW_MS) {
        prefs(context).edit().putLong(KEY_PAIRING_UNTIL, System.currentTimeMillis() + durationMs).apply()
    }

    fun closePairing(context: Context) {
        prefs(context).edit().putLong(KEY_PAIRING_UNTIL, 0L).apply()
    }

    fun isPairingOpen(context: Context): Boolean =
        System.currentTimeMillis() < prefs(context).getLong(KEY_PAIRING_UNTIL, 0L)

    private fun newToken(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
