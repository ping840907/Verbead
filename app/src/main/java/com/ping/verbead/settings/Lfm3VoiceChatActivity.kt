package com.ping.verbead.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.ping.verbead.R
import com.ping.verbead.engine.AudioRecorder
import com.ping.verbead.engine.Lfm3AudioEngine
import com.ping.verbead.engine.Lfm3ChatMessage
import com.ping.verbead.util.HapticUtil
import com.ping.verbead.util.PcmPlayer
import com.ping.verbead.util.TtsSpeaker
import com.ping.verbead.ModelDownloadService
import com.ping.verbead.engine.ModelDownloadState
import com.ping.verbead.engine.ModelConfig
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * LFM3 Audio 即時來回語音對話實驗室 Activity。
 * 實現語音輪替對話狀態機 (Listening -> Thinking -> Speaking -> Listening 循環)。
 */
class Lfm3VoiceChatActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Lfm3VoiceChatActivity"
    }

    private enum class ChatState {
        IDLE,
        LISTENING,
        THINKING,
        SPEAKING
    }

    @Volatile private var currentState = ChatState.IDLE

    // UI 元件
    private lateinit var toolbar: MaterialToolbar
    private lateinit var btnClearChat: ImageButton
    private lateinit var switchContinuousLoop: MaterialSwitch
    private lateinit var rvChatMessages: RecyclerView
    private lateinit var viewPulseRing: View
    private lateinit var ivStatusIcon: ImageView
    private lateinit var tvAcousticStatus: TextView
    private lateinit var progressRms: LinearProgressIndicator
    private lateinit var btnStopSpeech: MaterialButton
    private lateinit var btnMicAction: MaterialButton
    private lateinit var cardDownloadPrompt: MaterialCardView
    private lateinit var tvChatDownloadStatus: TextView
    private lateinit var btnChatDownloadAction: MaterialButton
    private lateinit var progressChatDownload: LinearProgressIndicator
    private lateinit var tvSynthesisModeTitle: TextView
    private lateinit var tvSynthesisModeDesc: TextView
    private lateinit var btnToggleSynthesisMode: MaterialButton

    private lateinit var chatAdapter: Lfm3ChatAdapter
    private val audioRecorder by lazy { AudioRecorder(this) }
    private val pcmPlayer = PcmPlayer()
    private val ttsSpeaker by lazy { TtsSpeaker(this) }

    private var recordingJob: Job? = null
    private var playbackJob: Job? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            startListening()
        } else {
            Toast.makeText(this, "需要麥克風權限以進行語音對話", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lfm3_voice_chat)

        initViews()
        setupListeners()
        setupChatList()
        setupDownloadObserver()

        // 歡迎訊息
        lifecycleScope.launch {
            chatAdapter.addMessage(
                Lfm3ChatMessage(
                    isUser = false,
                    text = "您好！我是 Verbead LFM3 端側語音對話助理。您可以點擊下方按鈕或開啟「連續對話模式」，我會即時透過 16kHz 音訊進行語音理解與生成！"
                )
            )
        }
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        btnClearChat = findViewById(R.id.btn_clear_chat)
        switchContinuousLoop = findViewById(R.id.switch_continuous_loop)
        rvChatMessages = findViewById(R.id.rv_chat_messages)
        viewPulseRing = findViewById(R.id.view_pulse_ring)
        ivStatusIcon = findViewById(R.id.iv_status_icon)
        tvAcousticStatus = findViewById(R.id.tv_acoustic_status)
        progressRms = findViewById(R.id.progress_rms)
        btnStopSpeech = findViewById(R.id.btn_stop_speech)
        btnMicAction = findViewById(R.id.btn_mic_action)
        cardDownloadPrompt = findViewById(R.id.card_download_prompt)
        tvChatDownloadStatus = findViewById(R.id.tv_chat_download_status)
        btnChatDownloadAction = findViewById(R.id.btn_chat_download_action)
        progressChatDownload = findViewById(R.id.progress_chat_download)
        tvSynthesisModeTitle = findViewById(R.id.tv_synthesis_mode_title)
        tvSynthesisModeDesc = findViewById(R.id.tv_synthesis_mode_desc)
        btnToggleSynthesisMode = findViewById(R.id.btn_toggle_synthesis_mode)
    }

    private fun setupChatList() {
        chatAdapter = Lfm3ChatAdapter(mutableListOf()) { msg ->
            if (msg.audioSamples != null && msg.audioSamples.isNotEmpty()) {
                playAudio(msg.audioSamples, if (msg.isUser) 16_000 else 24_000)
            } else if (msg.text.isNotBlank()) {
                speakText(msg.text)
            }
        }
        rvChatMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        rvChatMessages.adapter = chatAdapter
    }

    private fun setupDownloadObserver() {
        btnChatDownloadAction.setOnClickListener {
            HapticUtil.click(this)
            val active = ModelDownloadState.active.value
            if (active != null && (active.engine == ModelConfig.ENGINE_LFM3_AUDIO || active.engine == ModelConfig.ENGINE_QWEN3_TTS)) {
                ModelDownloadService.cancel(this)
            } else {
                val currentMode = ModelConfig.getLfm3SynthesisMode(this)
                val targetEngine = if (currentMode == ModelConfig.SYNTHESIS_MODE_QWEN3_TTS && !ModelConfig.isQwen3TtsReady(this)) {
                    ModelConfig.ENGINE_QWEN3_TTS
                } else {
                    ModelConfig.ENGINE_LFM3_AUDIO
                }
                ModelDownloadService.start(this, targetEngine)
            }
        }

        lifecycleScope.launch {
            ModelDownloadState.active.collect { active ->
                if (active != null && (active.engine == ModelConfig.ENGINE_LFM3_AUDIO || active.engine == ModelConfig.ENGINE_QWEN3_TTS)) {
                    cardDownloadPrompt.visibility = View.VISIBLE
                    btnChatDownloadAction.text = "取消下載"
                    progressChatDownload.visibility = View.VISIBLE
                    progressChatDownload.isIndeterminate = active.progress.percent < 0
                    if (active.progress.percent >= 0) progressChatDownload.progress = active.progress.percent
                    tvChatDownloadStatus.text = "下載中：${active.progress.label} (${active.progress.percent}%)"
                } else {
                    val lfm3Ready = ModelConfig.isLfm3AudioReady(this@Lfm3VoiceChatActivity)
                    val currentMode = ModelConfig.getLfm3SynthesisMode(this@Lfm3VoiceChatActivity)
                    val qwen3TtsReady = ModelConfig.isQwen3TtsReady(this@Lfm3VoiceChatActivity)
                    if (!lfm3Ready) {
                        cardDownloadPrompt.visibility = View.VISIBLE
                        btnChatDownloadAction.text = "一鍵下載"
                        progressChatDownload.visibility = View.GONE
                        tvChatDownloadStatus.text = "LFM3 GGUF 官方權重尚未下載 (約 1.29 GB，支援 NPU/GPU)"
                    } else if (currentMode == ModelConfig.SYNTHESIS_MODE_QWEN3_TTS && !qwen3TtsReady) {
                        cardDownloadPrompt.visibility = View.VISIBLE
                        btnChatDownloadAction.text = "下載 Qwen3"
                        progressChatDownload.visibility = View.GONE
                        tvChatDownloadStatus.text = "Qwen3-TTS 權重尚未下載 (約 1.41 GB，支援 NPU/GPU)"
                    } else {
                        cardDownloadPrompt.visibility = View.GONE
                    }
                }
            }
        }

        lifecycleScope.launch {
            ModelDownloadState.results.collect { (engine, result) ->
                if (engine == ModelConfig.ENGINE_LFM3_AUDIO || engine == ModelConfig.ENGINE_QWEN3_TTS) {
                    val name = if (engine == ModelConfig.ENGINE_QWEN3_TTS) "Qwen3-TTS" else "LFM3"
                    if (result.isSuccess) {
                        Toast.makeText(this@Lfm3VoiceChatActivity, "$name 模型下載完成！", Toast.LENGTH_SHORT).show()
                        cardDownloadPrompt.visibility = View.GONE
                        updateSynthesisModeUi()
                    } else {
                        Toast.makeText(this@Lfm3VoiceChatActivity, "$name 下載失敗，請檢查網路連線", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun setupListeners() {
        toolbar.setNavigationOnClickListener {
            HapticUtil.click(this)
            finish()
        }

        btnClearChat.setOnClickListener {
            HapticUtil.click(this)
            stopAll()
            chatAdapter.clearMessages()
            setState(ChatState.IDLE)
        }

        btnMicAction.setOnClickListener {
            HapticUtil.click(this)
            when (currentState) {
                ChatState.IDLE -> checkPermissionAndListen()
                ChatState.LISTENING -> audioRecorder.stop()
                ChatState.THINKING -> {} // 思考中忽略
                ChatState.SPEAKING -> {
                    pcmPlayer.stop()
                    checkPermissionAndListen()
                }
            }
        }

        btnStopSpeech.setOnClickListener {
            HapticUtil.click(this)
            stopAll()
            setState(ChatState.IDLE)
        }

        updateSynthesisModeUi()
        btnToggleSynthesisMode.setOnClickListener {
            HapticUtil.click(this)
            val modes = arrayOf(
                ModelConfig.SYNTHESIS_MODE_NATIVE_CODEC,
                ModelConfig.SYNTHESIS_MODE_QWEN3_TTS,
                ModelConfig.SYNTHESIS_MODE_SYSTEM_TTS
            )
            val modeLabels = arrayOf(
                "LFM3 原生神經聲學 (LiquidCodec 24kHz，端到端對話)",
                "Qwen3-TTS 旗艦語音 (Alibaba 12Hz Codec，支援聲音克隆與 NPU)",
                "系統端側類神經朗讀 (Google SeaNet TTS)"
            )
            val currentMode = ModelConfig.getLfm3SynthesisMode(this)
            val currentIndex = modes.indexOf(currentMode).coerceAtLeast(0)

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("選擇語音合成來源")
                .setSingleChoiceItems(modeLabels, currentIndex) { dialog, which ->
                    val selectedMode = modes[which]
                    ModelConfig.setLfm3SynthesisMode(this@Lfm3VoiceChatActivity, selectedMode)
                    updateSynthesisModeUi()
                    HapticUtil.click(this@Lfm3VoiceChatActivity)
                    dialog.dismiss()
                    if (selectedMode == ModelConfig.SYNTHESIS_MODE_QWEN3_TTS && !ModelConfig.isQwen3TtsReady(this@Lfm3VoiceChatActivity)) {
                        Toast.makeText(this@Lfm3VoiceChatActivity, "已切換為 Qwen3-TTS (點選上方下載卡片以取得模型)", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@Lfm3VoiceChatActivity, "已切換發聲來源為：${modeLabels[which].substringBefore('(').trim()}", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun updateSynthesisModeUi() {
        val currentMode = ModelConfig.getLfm3SynthesisMode(this)
        when (currentMode) {
            ModelConfig.SYNTHESIS_MODE_QWEN3_TTS -> {
                val ready = ModelConfig.isQwen3TtsReady(this)
                tvSynthesisModeTitle.text = "發聲：Qwen3-TTS 旗艦語音"
                tvSynthesisModeDesc.text = if (ready) "Alibaba Qwen3-TTS 12Hz GGUF (NPU/GPU 已就緒)" else "尚未下載 Qwen3-TTS 權重 (約 1.41 GB)"
                btnToggleSynthesisMode.text = "切換引擎"
            }
            ModelConfig.SYNTHESIS_MODE_NATIVE_CODEC -> {
                tvSynthesisModeTitle.text = "發聲：LFM3 原生神經聲學"
                tvSynthesisModeDesc.text = "LiquidCodec 24kHz 端到端神經聲學合成"
                btnToggleSynthesisMode.text = "切換引擎"
            }
            else -> {
                tvSynthesisModeTitle.text = "發聲：系統端側類神經朗讀"
                tvSynthesisModeDesc.text = "Google SeaNet 高傳真離線神經 TTS"
                btnToggleSynthesisMode.text = "切換引擎"
            }
        }
    }

    private fun checkPermissionAndListen() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            startListening()
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun startListening() {
        if (currentState == ChatState.LISTENING) return
        stopAll()
        setState(ChatState.LISTENING)

        recordingJob = lifecycleScope.launch {
            Log.i(TAG, "Starting audio recording session...")
            val recording = audioRecorder.recordUntilSilence(
                maxSeconds = 12.0f,
                silenceSeconds = 1.2f, // 1.2 秒無聲音自動切入思考
                onRmsUpdate = { rms ->
                    val progress = (rms * 400).toInt().coerceIn(0, 100)
                    progressRms.post {
                        progressRms.setProgressCompat(progress, false)
                    }
                }
            )

            if (recording.samples.isNotEmpty()) {
                handleUserSpeech(recording.samples)
            } else {
                setState(ChatState.IDLE)
            }
        }
    }

    private fun handleUserSpeech(audioSamples: FloatArray) {
        setState(ChatState.THINKING)

        lifecycleScope.launch {
            try {
                // 呼叫 LFM3 對話核心
                val history = chatAdapter.getMessages()
                val response = Lfm3AudioEngine.chat(
                    context = this@Lfm3VoiceChatActivity,
                    userAudio = audioSamples,
                    history = history
                )

                // 更新聊天列表
                chatAdapter.addMessage(
                    Lfm3ChatMessage(
                        isUser = true,
                        text = response.userText,
                        audioSamples = audioSamples
                    )
                )

                chatAdapter.addMessage(
                    Lfm3ChatMessage(
                        isUser = false,
                        text = response.replyText,
                        audioSamples = response.replyAudioSamples
                    )
                )

                rvChatMessages.scrollToPosition(chatAdapter.itemCount - 1)

                // 播放語音回覆 (若有神經編碼音訊則回放，否則以高傳真繁中 TTS 自然朗讀)
                if (response.replyAudioSamples != null && response.replyAudioSamples.isNotEmpty()) {
                    playAudio(response.replyAudioSamples, response.audioSampleRate) {
                        onAssistantSpeechFinished()
                    }
                } else if (response.replyText.isNotBlank()) {
                    speakText(response.replyText) {
                        onAssistantSpeechFinished()
                    }
                } else {
                    onAssistantSpeechFinished()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error in LFM3 chat round: ${e.message}", e)
                setState(ChatState.IDLE)
            }
        }
    }

    private fun speakText(text: String, onFinished: (() -> Unit)? = null) {
        stopAll()
        setState(ChatState.SPEAKING)
        ttsSpeaker.speak(
            text = text,
            onStart = {
                runOnUiThread {
                    if (currentState == ChatState.SPEAKING) {
                        startPulseAnimation()
                    }
                }
            },
            onDone = {
                runOnUiThread {
                    stopPulseAnimation()
                    onFinished?.invoke()
                }
            }
        )
    }

    private fun playAudio(samples: FloatArray, sampleRate: Int = 24_000, onFinished: (() -> Unit)? = null) {
        setState(ChatState.SPEAKING)
        playbackJob?.cancel()
        playbackJob = lifecycleScope.launch {
            pcmPlayer.play(samples, sampleRate) {
                onFinished?.invoke()
            }
        }
    }

    private fun onAssistantSpeechFinished() {
        if (!switchContinuousLoop.isChecked) {
            setState(ChatState.IDLE)
            return
        }

        // 連續對話模式：稍候 350ms 避開聲音餘波，立即自動開啟下一輪聆聽
        lifecycleScope.launch {
            delay(350L)
            if (switchContinuousLoop.isChecked && (currentState == ChatState.SPEAKING || currentState == ChatState.IDLE)) {
                HapticUtil.tick(this@Lfm3VoiceChatActivity)
                startListening()
            }
        }
    }

    private fun stopAll() {
        recordingJob?.cancel()
        recordingJob = null
        playbackJob?.cancel()
        playbackJob = null
        audioRecorder.stop()
        pcmPlayer.stop()
        ttsSpeaker.stop()
    }

    private fun setState(state: ChatState) {
        currentState = state
        runOnUiThread {
            when (state) {
                ChatState.IDLE -> {
                    tvAcousticStatus.text = "點擊下方麥克風開始對話"
                    ivStatusIcon.setImageResource(R.drawable.ic_mic)
                    ivStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.md_theme_light_primary))
                    btnMicAction.text = "開始說話"
                    btnMicAction.isEnabled = true
                    btnStopSpeech.isEnabled = false
                    progressRms.setProgressCompat(0, false)
                    stopPulseAnimation()
                }
                ChatState.LISTENING -> {
                    tvAcousticStatus.text = "正在聆聽... 請說話 (停頓自動發送)"
                    ivStatusIcon.setImageResource(R.drawable.ic_mic)
                    ivStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_success))
                    btnMicAction.text = "點擊發送"
                    btnMicAction.isEnabled = true
                    btnStopSpeech.isEnabled = true
                    startPulseAnimation()
                }
                ChatState.THINKING -> {
                    tvAcousticStatus.text = "LFM3 基礎模型思考中..."
                    ivStatusIcon.setImageResource(R.drawable.ic_ai_sparkle)
                    ivStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.tag_recommend))
                    btnMicAction.text = "處理中..."
                    btnMicAction.isEnabled = false
                    btnStopSpeech.isEnabled = true
                    stopPulseAnimation()
                }
                ChatState.SPEAKING -> {
                    tvAcousticStatus.text = "正在播放 LFM3 語音回覆..."
                    ivStatusIcon.setImageResource(R.drawable.ic_play_arrow)
                    ivStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.tag_recommend))
                    btnMicAction.text = "插話打斷"
                    btnMicAction.isEnabled = true
                    btnStopSpeech.isEnabled = true
                    startPulseAnimation()
                }
            }
        }
    }

    private fun startPulseAnimation() {
        viewPulseRing.visibility = View.VISIBLE
        val anim = ScaleAnimation(
            1.0f, 1.35f, 1.0f, 1.35f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 800L
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
        }
        viewPulseRing.startAnimation(anim)
    }

    private fun stopPulseAnimation() {
        viewPulseRing.clearAnimation()
        viewPulseRing.visibility = View.INVISIBLE
    }

    override fun onPause() {
        super.onPause()
        stopAll()
        setState(ChatState.IDLE)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAll()
        ttsSpeaker.release()
        Lfm3AudioEngine.onTrimMemory()
    }
}
