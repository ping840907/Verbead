package com.ping.verbead.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.ping.verbead.R
import com.ping.verbead.engine.AudioRecorder
import com.ping.verbead.engine.FarganProcessor
import com.ping.verbead.engine.ModelConfig
import com.ping.verbead.engine.Qwen3AsrEngine
import com.ping.verbead.util.HapticUtil
import com.ping.verbead.util.PcmPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AudioCompareActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AudioCompareActivity"
    }

    private lateinit var toolbar: MaterialToolbar

    // Track 1: Phone Mic Raw
    private lateinit var tvDurationPhone: TextView
    private lateinit var tvBadgePhone: TextView
    private lateinit var progressPhone: LinearProgressIndicator
    private lateinit var btnRecordPhone: MaterialButton
    private lateinit var btnPlayPhone: MaterialButton
    private lateinit var tvAsrPhone: TextView

    // Track 2: Headset Mic Raw
    private lateinit var tvDurationHeadsetRaw: TextView
    private lateinit var tvBadgeHeadsetRaw: TextView
    private lateinit var progressHeadsetRaw: LinearProgressIndicator
    private lateinit var btnRecordHeadset: MaterialButton
    private lateinit var btnPlayHeadsetRaw: MaterialButton
    private lateinit var tvAsrHeadsetRaw: TextView

    // Track 3: Headset + FARGAN
    private lateinit var tvDurationHeadsetFargan: TextView
    private lateinit var tvBadgeHeadsetFargan: TextView
    private lateinit var progressHeadsetFargan: LinearProgressIndicator
    private lateinit var btnPlayHeadsetFargan: MaterialButton
    private lateinit var btnReprocessFargan: MaterialButton
    private lateinit var tvAsrHeadsetFargan: TextView

    // Quick Switch Controls
    private lateinit var btnQuickPhone: MaterialButton
    private lateinit var btnQuickHeadsetRaw: MaterialButton
    private lateinit var btnQuickHeadsetFargan: MaterialButton
    private lateinit var btnStopAll: MaterialButton

    private val audioRecorder by lazy { AudioRecorder(this) }
    private val pcmPlayer = PcmPlayer()
    private val qwen3Engine by lazy { Qwen3AsrEngine(this) }

    // Audio buffers
    private var phoneSamples: FloatArray? = null
    private var headsetRawSamples: FloatArray? = null
    private var headsetFarganSamples: FloatArray? = null

    private var activeRecordingJob: Job? = null
    private var activePlaybackJob: Job? = null
    private var isRecordingPhone = false
    private var isRecordingHeadset = false

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "需要麥克風權限以進行錄音對比", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio_compare)

        bindViews()
        setupToolbar()
        setupListeners()
    }

    override fun onDestroy() {
        super.onDestroy()
        audioRecorder.stopEarly()
        pcmPlayer.stop()
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)

        // Track 1
        tvDurationPhone = findViewById(R.id.tv_duration_phone)
        tvBadgePhone = findViewById(R.id.tv_badge_phone)
        progressPhone = findViewById(R.id.progress_phone)
        btnRecordPhone = findViewById(R.id.btn_record_phone)
        btnPlayPhone = findViewById(R.id.btn_play_phone)
        tvAsrPhone = findViewById(R.id.tv_asr_phone)

        // Track 2
        tvDurationHeadsetRaw = findViewById(R.id.tv_duration_headset_raw)
        tvBadgeHeadsetRaw = findViewById(R.id.tv_badge_headset_raw)
        progressHeadsetRaw = findViewById(R.id.progress_headset_raw)
        btnRecordHeadset = findViewById(R.id.btn_record_headset)
        btnPlayHeadsetRaw = findViewById(R.id.btn_play_headset_raw)
        tvAsrHeadsetRaw = findViewById(R.id.tv_asr_headset_raw)

        // Track 3
        tvDurationHeadsetFargan = findViewById(R.id.tv_duration_headset_fargan)
        tvBadgeHeadsetFargan = findViewById(R.id.tv_badge_headset_fargan)
        progressHeadsetFargan = findViewById(R.id.progress_headset_fargan)
        btnPlayHeadsetFargan = findViewById(R.id.btn_play_headset_fargan)
        btnReprocessFargan = findViewById(R.id.btn_reprocess_fargan)
        tvAsrHeadsetFargan = findViewById(R.id.tv_asr_headset_fargan)

        // Quick Controls
        btnQuickPhone = findViewById(R.id.btn_quick_phone)
        btnQuickHeadsetRaw = findViewById(R.id.btn_quick_headset_raw)
        btnQuickHeadsetFargan = findViewById(R.id.btn_quick_headset_fargan)
        btnStopAll = findViewById(R.id.btn_stop_all)
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            HapticUtil.click(this)
            finish()
        }
    }

    private fun setupListeners() {
        // Track 1: Phone Mic Record
        btnRecordPhone.setOnClickListener {
            HapticUtil.click(this)
            if (isRecordingPhone) {
                audioRecorder.stopEarly()
            } else {
                startRecordingPhone()
            }
        }
        btnPlayPhone.setOnClickListener {
            HapticUtil.click(this)
            playTrack(1)
        }

        // Track 2 & 3: Headset Record (Dual generation)
        btnRecordHeadset.setOnClickListener {
            HapticUtil.click(this)
            if (isRecordingHeadset) {
                audioRecorder.stopEarly()
            } else {
                startRecordingHeadset()
            }
        }
        btnPlayHeadsetRaw.setOnClickListener {
            HapticUtil.click(this)
            playTrack(2)
        }

        // Track 3: FARGAN Play & Reprocess
        btnPlayHeadsetFargan.setOnClickListener {
            HapticUtil.click(this)
            playTrack(3)
        }
        btnReprocessFargan.setOnClickListener {
            HapticUtil.click(this)
            reprocessFargan()
        }

        // Quick controls
        btnQuickPhone.setOnClickListener { HapticUtil.click(this); playTrack(1) }
        btnQuickHeadsetRaw.setOnClickListener { HapticUtil.click(this); playTrack(2) }
        btnQuickHeadsetFargan.setOnClickListener { HapticUtil.click(this); playTrack(3) }
        btnStopAll.setOnClickListener {
            HapticUtil.click(this)
            stopAllPlayback()
        }
    }

    private fun checkPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return false
        }
        return true
    }

    private fun startRecordingPhone() {
        if (!checkPermission()) return
        stopAllPlayback()

        isRecordingPhone = true
        btnRecordPhone.text = "停止錄音"
        btnRecordPhone.setIconResource(R.drawable.ic_stop)
        tvBadgePhone.text = "錄音中..."
        tvBadgePhone.setBackgroundResource(R.drawable.bg_status_badge_recommend)
        tvBadgePhone.setTextColor(ContextCompat.getColor(this, R.color.tag_recommend))
        btnRecordHeadset.isEnabled = false

        activeRecordingJob = lifecycleScope.launch {
            try {
                val rec = audioRecorder.recordUntilSilence(
                    maxSeconds = 15f,
                    silenceSeconds = 1.2f,
                    forceExternal = false,
                    forceFargan = false,
                    onRmsUpdate = { rms ->
                        val p = (rms * 1000f).toInt().coerceIn(0, 100)
                        progressPhone.post {
                            progressPhone.setProgressCompat(p, false)
                        }
                    }
                )

                if (rec.samples.isNotEmpty()) {
                    phoneSamples = rec.samples
                    val sec = rec.samples.size / 16000f
                    tvDurationPhone.text = "已錄製 · 時長：%.1f 秒".format(sec)
                    tvBadgePhone.text = "已就緒"
                    tvBadgePhone.setBackgroundResource(R.drawable.bg_status_badge_recommend)
                    tvBadgePhone.setTextColor(ContextCompat.getColor(this@AudioCompareActivity, R.color.tag_recommend))
                    btnPlayPhone.isEnabled = true
                    btnQuickPhone.isEnabled = true
                    runAsrTranscription(rec.samples, tvAsrPhone)
                } else {
                    tvBadgePhone.text = "未錄得聲音"
                    tvBadgePhone.setBackgroundResource(R.drawable.bg_status_badge_neutral)
                    tvBadgePhone.setTextColor(ContextCompat.getColor(this@AudioCompareActivity, R.color.text_tertiary))
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Recording phone error: ${e.message}", e)
                Toast.makeText(this@AudioCompareActivity, "錄音失敗：${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isRecordingPhone = false
                btnRecordPhone.text = "錄製手機麥克風"
                btnRecordPhone.setIconResource(R.drawable.ic_mic)
                btnRecordHeadset.isEnabled = true
                progressPhone.post {
                    progressPhone.setProgressCompat(0, false)
                }
            }
        }
    }

    private fun startRecordingHeadset() {
        if (!checkPermission()) return
        stopAllPlayback()

        isRecordingHeadset = true
        btnRecordHeadset.text = "停止錄音"
        btnRecordHeadset.setIconResource(R.drawable.ic_stop)
        tvBadgeHeadsetRaw.text = "耳機錄音中..."
        tvBadgeHeadsetRaw.setBackgroundResource(R.drawable.bg_status_badge_recommend)
        tvBadgeHeadsetRaw.setTextColor(ContextCompat.getColor(this, R.color.tag_recommend))
        btnRecordPhone.isEnabled = false

        activeRecordingJob = lifecycleScope.launch {
            try {
                val rec = audioRecorder.recordUntilSilence(
                    maxSeconds = 15f,
                    silenceSeconds = 1.2f,
                    forceExternal = true,
                    forceFargan = false,
                    onRmsUpdate = { rms ->
                        val p = (rms * 1000f).toInt().coerceIn(0, 100)
                        progressHeadsetRaw.post {
                            progressHeadsetRaw.setProgressCompat(p, false)
                        }
                    }
                )

                if (rec.samples.isNotEmpty()) {
                    headsetRawSamples = rec.samples
                    val sec = rec.samples.size / 16000f

                    // Track 2: Raw
                    tvDurationHeadsetRaw.text = "耳機原音 · 時長：%.1f 秒".format(sec)
                    tvBadgeHeadsetRaw.text = "已就緒"
                    btnPlayHeadsetRaw.isEnabled = true
                    btnQuickHeadsetRaw.isEnabled = true

                    // Track 3: Process through FARGAN using the exact same acoustic samples
                    val farganSamples = FarganProcessor.processStreaming(rec.samples.copyOf())
                    headsetFarganSamples = farganSamples
                    tvDurationHeadsetFargan.text = "FARGAN 修復 · 時長：%.1f 秒".format(sec)
                    tvBadgeHeadsetFargan.text = "已增強"
                    tvBadgeHeadsetFargan.setBackgroundResource(R.drawable.bg_status_badge_recommend)
                    tvBadgeHeadsetFargan.setTextColor(ContextCompat.getColor(this@AudioCompareActivity, R.color.tag_recommend))
                    btnPlayHeadsetFargan.isEnabled = true
                    btnReprocessFargan.isEnabled = true
                    btnQuickHeadsetFargan.isEnabled = true

                    // Run ASR for both to compare textual accuracy
                    runAsrTranscription(rec.samples, tvAsrHeadsetRaw)
                    runAsrTranscription(farganSamples, tvAsrHeadsetFargan)
                } else {
                    tvBadgeHeadsetRaw.text = "未錄得聲音"
                    tvBadgeHeadsetRaw.setBackgroundResource(R.drawable.bg_status_badge_neutral)
                    tvBadgeHeadsetRaw.setTextColor(ContextCompat.getColor(this@AudioCompareActivity, R.color.text_tertiary))
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Recording headset error: ${e.message}", e)
                Toast.makeText(this@AudioCompareActivity, "耳機錄音失敗：${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isRecordingHeadset = false
                btnRecordHeadset.text = "錄製耳機音訊"
                btnRecordHeadset.setIconResource(R.drawable.ic_tune)
                btnRecordPhone.isEnabled = true
                progressHeadsetRaw.post {
                    progressHeadsetRaw.setProgressCompat(0, false)
                }
            }
        }
    }

    private fun reprocessFargan() {
        val raw = headsetRawSamples ?: return
        FarganProcessor.reset()
        val farganSamples = FarganProcessor.processStreaming(raw.copyOf())
        headsetFarganSamples = farganSamples
        tvBadgeHeadsetFargan.text = "重新增強就緒"
        Toast.makeText(this, "已重新以 FARGAN 增強耳機原音", Toast.LENGTH_SHORT).show()
        runAsrTranscription(farganSamples, tvAsrHeadsetFargan)
    }

    private fun playTrack(trackId: Int) {
        stopAllPlayback()

        val samples = when (trackId) {
            1 -> phoneSamples
            2 -> headsetRawSamples
            3 -> headsetFarganSamples
            else -> null
        } ?: run {
            Toast.makeText(this, "該軌道尚未錄音", Toast.LENGTH_SHORT).show()
            return
        }

        val progressIndicator = when (trackId) {
            1 -> progressPhone
            2 -> progressHeadsetRaw
            else -> progressHeadsetFargan
        }
        val playButton = when (trackId) {
            1 -> btnPlayPhone
            2 -> btnPlayHeadsetRaw
            else -> btnPlayHeadsetFargan
        }

        playButton.setIconResource(R.drawable.ic_stop)
        playButton.text = "停止播放"

        activePlaybackJob = lifecycleScope.launch {
            pcmPlayer.playFloats(
                floats = samples,
                sampleRate = ModelConfig.ASR_SAMPLE_RATE,
                onProgress = { p ->
                    val progressVal = (p * 100f).toInt().coerceIn(0, 100)
                    progressIndicator.post {
                        progressIndicator.setProgressCompat(progressVal, false)
                    }
                },
                onCompletion = {
                    progressIndicator.post {
                        progressIndicator.setProgressCompat(0, false)
                    }
                    playButton.setIconResource(R.drawable.ic_play_arrow)
                    playButton.text = when (trackId) {
                        1 -> "播放原音"
                        2 -> "播放耳機原音"
                        else -> "播放 FARGAN 增強"
                    }
                }
            )
        }
    }

    private fun stopAllPlayback() {
        activePlaybackJob?.cancel()
        activePlaybackJob = null
        pcmPlayer.stop()

        progressPhone.post { progressPhone.setProgressCompat(0, false) }
        progressHeadsetRaw.post { progressHeadsetRaw.setProgressCompat(0, false) }
        progressHeadsetFargan.post { progressHeadsetFargan.setProgressCompat(0, false) }

        btnPlayPhone.setIconResource(R.drawable.ic_play_arrow)
        btnPlayPhone.text = "播放原音"
        btnPlayHeadsetRaw.setIconResource(R.drawable.ic_play_arrow)
        btnPlayHeadsetRaw.text = "播放耳機原音"
        btnPlayHeadsetFargan.setIconResource(R.drawable.ic_play_arrow)
        btnPlayHeadsetFargan.text = "播放 FARGAN 增強"
    }

    private fun runAsrTranscription(samples: FloatArray, targetView: TextView) {
        if (!ModelConfig.isQwen3Ready(this)) {
            targetView.text = "ASR 辨識：(Qwen3 模型未就緒，可至設定下載)"
            return
        }
        targetView.text = "ASR 辨識中..."
        lifecycleScope.launch {
            try {
                if (!qwen3Engine.isLoaded()) {
                    withContext(Dispatchers.IO) {
                        qwen3Engine.load()
                    }
                }
                val text = qwen3Engine.transcribe(samples)
                val display = if (text.isNotBlank()) "ASR 辨識：$text" else "ASR 辨識：(無識別字元)"
                targetView.text = display
            } catch (e: Exception) {
                targetView.text = "ASR 辨識失敗：${e.message}"
            }
        }
    }
}
