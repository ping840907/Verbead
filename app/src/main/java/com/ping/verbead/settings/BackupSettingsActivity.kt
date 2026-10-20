package com.ping.verbead.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.ping.verbead.engine.ModelZipInstaller
import com.ping.verbead.R
import com.ping.verbead.engine.ModelConfig
import com.ping.verbead.util.HapticUtil
import kotlinx.coroutines.launch
import java.io.File

class BackupSettingsActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvAllModelsBadge: TextView
    private lateinit var progressImportModels: LinearProgressIndicator
    private lateinit var tvImportModelsStatus: TextView
    private lateinit var btnImportModelsZip: MaterialButton
    private lateinit var btnQuickImportDownload: MaterialButton

    private val pickZipFileLauncher = registerForActivityResult(
        object : ActivityResultContracts.OpenDocument() {
            override fun createIntent(context: Context, input: Array<String>): Intent {
                val intent = super.createIntent(context, input)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val downloadUri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")
                    intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloadUri)
                }
                return intent
            }
        }
    ) { uri: Uri? ->
        uri?.let {
            runModelPackageImport(uri = it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_backup)

        bindViews()
        setupToolbar()
        setupListeners()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        updateAllStatus()
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)
        tvAllModelsBadge = findViewById(R.id.tv_all_models_badge)
        progressImportModels = findViewById(R.id.progress_import_models)
        tvImportModelsStatus = findViewById(R.id.tv_import_models_status)
        btnImportModelsZip = findViewById(R.id.btn_import_models_zip)
        btnQuickImportDownload = findViewById(R.id.btn_quick_import_download)
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            HapticUtil.click(this)
            finish()
        }
    }

    private fun setupListeners() {
        btnImportModelsZip.setOnClickListener {
            HapticUtil.click(this)
            try {
                pickZipFileLauncher.launch(
                    arrayOf(
                        "application/zip",
                        "application/x-zip-compressed",
                        "application/octet-stream"
                    )
                )
            } catch (ex: Exception) {
                Toast.makeText(this, getString(R.string.toast_file_picker_error, ex.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }

        btnQuickImportDownload.setOnClickListener {
            HapticUtil.click(this)
            val quickFile = ModelZipInstaller.findDefaultZipPackage(this)
            if (quickFile != null) {
                runModelPackageImport(file = quickFile)
            } else {
                Toast.makeText(this, "未在 Download 目錄中找到預設模型 ZIP 包", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra("auto_import_default_zip", false)) {
            val quickFile = ModelZipInstaller.findDefaultZipPackage(this)
            if (quickFile != null) {
                runModelPackageImport(file = quickFile)
            }
        } else if (intent.hasExtra("import_zip_path")) {
            val path = intent.getStringExtra("import_zip_path")
            if (!path.isNullOrEmpty()) {
                val f = File(path)
                if (f.exists()) {
                    runModelPackageImport(file = f)
                }
            }
        }
    }

    private fun updateAllStatus() {
        val allReady = ModelConfig.areAllModelsReady(this)
        if (allReady) {
            tvAllModelsBadge.text = "全部模型已就緒"
            tvAllModelsBadge.setTextColor(ContextCompat.getColor(this, R.color.status_success_text))
            tvAllModelsBadge.setBackgroundResource(R.drawable.bg_status_badge_success)
        } else {
            tvAllModelsBadge.text = "未完全就緒"
            tvAllModelsBadge.setTextColor(ContextCompat.getColor(this, R.color.status_warning_text))
            tvAllModelsBadge.setBackgroundResource(R.drawable.bg_status_badge_warning)
        }

        val quickZip = ModelZipInstaller.findDefaultZipPackage(this)
        if (quickZip != null) {
            btnQuickImportDownload.visibility = View.VISIBLE
            val sizeMb = quickZip.length() / (1024 * 1024)
            btnQuickImportDownload.text = "快速載入內部儲存模型包 (${sizeMb}MB)"
        } else {
            btnQuickImportDownload.visibility = View.GONE
        }
    }

    private fun runModelPackageImport(file: File? = null, uri: Uri? = null) {
        btnImportModelsZip.isEnabled = false
        btnQuickImportDownload.isEnabled = false
        progressImportModels.visibility = View.VISIBLE
        progressImportModels.progress = 0
        tvImportModelsStatus.visibility = View.VISIBLE
        tvImportModelsStatus.text = "正在準備解壓模型包…"

        lifecycleScope.launch {
            val result = if (file != null) {
                ModelZipInstaller.installFromFile(this@BackupSettingsActivity, file) { currentFile, pct ->
                    runOnUiThread {
                        progressImportModels.progress = pct
                        tvImportModelsStatus.text = "解壓中：$currentFile ($pct%)"
                    }
                }
            } else if (uri != null) {
                ModelZipInstaller.installFromUri(this@BackupSettingsActivity, uri) { currentFile, pct ->
                    runOnUiThread {
                        progressImportModels.progress = pct
                        tvImportModelsStatus.text = "解壓中：$currentFile ($pct%)"
                    }
                }
            } else {
                ModelZipInstaller.InstallResult(
                    isSuccess = false,
                    xAsrReady = false,
                    qwen3Ready = false,
                    ocrReady = false,
                    fileCount = 0,
                    totalBytes = 0,
                    message = "無效的檔案來源"
                )
            }

            btnImportModelsZip.isEnabled = true
            btnQuickImportDownload.isEnabled = true
            progressImportModels.visibility = View.GONE

            if (result.isSuccess) {
                tvImportModelsStatus.text = result.message
                updateAllStatus()
                val restoredListText = if (result.restoredModels.isNotEmpty()) {
                    result.restoredModels.joinToString("\n") { "• $it" }
                } else {
                    "• 離線模型核心檔案"
                }
                AlertDialog.Builder(this@BackupSettingsActivity)
                    .setTitle("離線模型一鍵復原成功")
                    .setMessage(
                        "已根據導入包內容智慧辨識並復原共 ${result.fileCount} 個模型核心檔案！\n\n" +
                                "本次復原就緒項目：\n$restoredListText\n\n" +
                                "所有對應功能均已自動啟用，可立即使用。"
                    )
                    .setPositiveButton("太棒了", null)
                    .show()
            } else {
                tvImportModelsStatus.text = "匯入失敗：${result.message}"
                AlertDialog.Builder(this@BackupSettingsActivity)
                    .setTitle("導入失敗")
                    .setMessage("無法順利解壓或安裝此模型包：\n${result.message}")
                    .setPositiveButton("我知道了", null)
                    .show()
            }
        }
    }
}
