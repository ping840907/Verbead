package com.ping.verbead.settings

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ping.verbead.ImeSettingsActivity

/**
 * BackupSettingsActivity now delegates directly to [ImeSettingsActivity],
 * where offline model package importing is implemented inline on the main page.
 */
class BackupSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val forwardIntent = Intent(this, ImeSettingsActivity::class.java).apply {
            intent.extras?.let { putExtras(it) }
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(forwardIntent)
        finish()
    }
}
