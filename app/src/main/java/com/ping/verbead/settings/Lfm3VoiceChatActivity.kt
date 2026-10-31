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
import android.widget.LinearLayout
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import com.google.android.material.textfield.TextInputEditText
import android.widget.Toast
import android.widget.RadioGroup
import android.widget.RadioButton
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
import com.ping.verbead.engine.Qwen3TtsRunner
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
    private lateinit var etInputText: TextInputEditText
    private lateinit var btnSpeakText: MaterialButton
    private lateinit var btnVoiceTuning: ImageButton
    private lateinit var layoutVoiceTuningIndicator: LinearLayout
    private lateinit var chipVoiceProfile: com.google.android.material.chip.Chip
    private lateinit var tvActiveStylePrompt: TextView

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
        etInputText = findViewById(R.id.et_input_text)
        btnSpeakText = findViewById(R.id.btn_speak_text)
        btnVoiceTuning = findViewById(R.id.btn_voice_tuning)
        layoutVoiceTuningIndicator = findViewById(R.id.layout_voice_tuning_indicator)
        chipVoiceProfile = findViewById(R.id.chip_voice_profile)
        tvActiveStylePrompt = findViewById(R.id.tv_active_style_prompt)
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

        btnSpeakText.setOnClickListener {
            HapticUtil.click(this)
            val text = etInputText.text?.toString().orEmpty()
            if (text.isNotBlank()) {
                etInputText.setText("")
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(etInputText.windowToken, 0)
                handleDirectTtsSpeak(text)
            } else {
                Toast.makeText(this, "請輸入欲朗讀的文字", Toast.LENGTH_SHORT).show()
            }
        }

        etInputText.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                val text = etInputText.text?.toString().orEmpty()
                if (text.isNotBlank()) {
                    etInputText.setText("")
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(etInputText.windowToken, 0)
                    handleDirectTtsSpeak(text)
                }
                true
            } else {
                false
            }
        }

        btnStopSpeech.setOnClickListener {
            HapticUtil.click(this)
            stopAll()
            setState(ChatState.IDLE)
        }

        updateSynthesisModeUi()
        updateVoiceTuningUi()

        val openTuningAction = View.OnClickListener {
            HapticUtil.click(this@Lfm3VoiceChatActivity)
            showVoiceTuningDialog()
        }
        btnVoiceTuning.setOnClickListener(openTuningAction)
        chipVoiceProfile.setOnClickListener(openTuningAction)
        layoutVoiceTuningIndicator.setOnClickListener(openTuningAction)

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

    private fun updateVoiceTuningUi() {
        val profile = ModelConfig.getTtsSpeakerProfile(this)
        val stylePrompt = ModelConfig.getTtsStylePrompt(this)

        chipVoiceProfile.text = when (profile) {
            ModelConfig.TTS_PROFILE_TAIWAN_CALM -> "🎙️ 台灣知性專業"
            ModelConfig.TTS_PROFILE_TAIWAN_MALE -> "☀️ 台灣陽光溫和"
            ModelConfig.TTS_PROFILE_TAIWAN_LIVELY -> "⚡ 台灣活潑元氣"
            ModelConfig.TTS_PROFILE_CUSTOM -> "✍️ 自訂音色與 Prompt"
            else -> "🌸 台灣甜美親切"
        }
        tvActiveStylePrompt.text = "風格: $stylePrompt"
    }

    private fun showVoiceTuningDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_tts_voice_tuning, null)
        val rgProfiles = dialogView.findViewById<RadioGroup>(R.id.rg_voice_profiles)
        val rbSweet = dialogView.findViewById<RadioButton>(R.id.rb_voice_taiwan_sweet)
        val rbCalm = dialogView.findViewById<RadioButton>(R.id.rb_voice_taiwan_calm)
        val rbMale = dialogView.findViewById<RadioButton>(R.id.rb_voice_taiwan_male)
        val rbLively = dialogView.findViewById<RadioButton>(R.id.rb_voice_taiwan_lively)
        val rbCustom = dialogView.findViewById<RadioButton>(R.id.rb_voice_custom)
        val etPrompt = dialogView.findViewById<TextInputEditText>(R.id.et_dialog_style_prompt)

        val chipTagSweet = dialogView.findViewById<View>(R.id.chip_tag_sweet)
        val chipTagCalm = dialogView.findViewById<View>(R.id.chip_tag_calm)
        val chipTagLively = dialogView.findViewById<View>(R.id.chip_tag_lively)
        val chipTagSmile = dialogView.findViewById<View>(R.id.chip_tag_smile)
        val chipTagNatural = dialogView.findViewById<View>(R.id.chip_tag_natural)

        val btnTestSpeak = dialogView.findViewById<MaterialButton>(R.id.btn_dialog_test_speak)
        val btnSave = dialogView.findViewById<MaterialButton>(R.id.btn_dialog_save_tuning)

        val currentProfile = ModelConfig.getTtsSpeakerProfile(this)
        val currentPrompt = ModelConfig.getTtsStylePrompt(this)

        when (currentProfile) {
            ModelConfig.TTS_PROFILE_TAIWAN_CALM -> rbCalm.isChecked = true
            ModelConfig.TTS_PROFILE_TAIWAN_MALE -> rbMale.isChecked = true
            ModelConfig.TTS_PROFILE_TAIWAN_LIVELY -> rbLively.isChecked = true
            ModelConfig.TTS_PROFILE_CUSTOM -> rbCustom.isChecked = true
            else -> rbSweet.isChecked = true
        }
        etPrompt.setText(currentPrompt)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        rgProfiles.setOnCheckedChangeListener { _, checkedId ->
            HapticUtil.click(this)
            when (checkedId) {
                R.id.rb_voice_taiwan_sweet -> etPrompt.setText("親切自然、帶有甜美微笑感的台灣女生")
                R.id.rb_voice_taiwan_calm -> etPrompt.setText("成熟知性、沉穩端莊的專業播報風格")
                R.id.rb_voice_taiwan_male -> etPrompt.setText("溫和誠懇、自然親切的台灣男聲")
                R.id.rb_voice_taiwan_lively -> etPrompt.setText("活潑開朗、輕快俏皮的元氣聲線")
                R.id.rb_voice_custom -> etPrompt.setText(etPrompt.text?.toString() ?: "")
            }
        }

        fun appendTag(tag: String) {
            HapticUtil.tick(this)
            val oldText = etPrompt.text?.toString().orEmpty().trim()
            if (oldText.isEmpty()) {
                etPrompt.setText(tag)
            } else if (!oldText.contains(tag)) {
                etPrompt.setText("$oldText, $tag")
            }
            etPrompt.setSelection(etPrompt.text?.length ?: 0)
        }

        chipTagSweet.setOnClickListener { appendTag("溫柔甜美") }
        chipTagCalm.setOnClickListener { appendTag("知性沉穩") }
        chipTagLively.setOnClickListener { appendTag("活潑熱情") }
        chipTagSmile.setOnClickListener { appendTag("帶微笑感") }
        chipTagNatural.setOnClickListener { appendTag("自然台灣腔") }

        btnTestSpeak.setOnClickListener {
            HapticUtil.click(this)
            val selectedProfile = when (rgProfiles.checkedRadioButtonId) {
                R.id.rb_voice_taiwan_calm -> ModelConfig.TTS_PROFILE_TAIWAN_CALM
                R.id.rb_voice_taiwan_male -> ModelConfig.TTS_PROFILE_TAIWAN_MALE
                R.id.rb_voice_taiwan_lively -> ModelConfig.TTS_PROFILE_TAIWAN_LIVELY
                R.id.rb_voice_custom -> ModelConfig.TTS_PROFILE_CUSTOM
                else -> ModelConfig.TTS_PROFILE_TAIWAN_SWEET
            }
            val styleText = etPrompt.text?.toString().orEmpty()
            val testSentence = "哈囉你好！這是我現在的語音音色與說話風格喔，企劃和品質都很棒呢！"

            lifecycleScope.launch {
                Toast.makeText(this@Lfm3VoiceChatActivity, "正在試聽合成...", Toast.LENGTH_SHORT).show()
                val samples = Qwen3TtsRunner.synthesizeSpeech(
                    context = this@Lfm3VoiceChatActivity,
                    text = testSentence,
                    stylePrompt = styleText,
                    profile = selectedProfile
                )
                if (samples != null && samples.isNotEmpty()) {
                    playAudio(samples, 24_000)
                } else {
                    speakText(testSentence)
                }
            }
        }

        btnSave.setOnClickListener {
            HapticUtil.click(this)
            val selectedProfile = when (rgProfiles.checkedRadioButtonId) {
                R.id.rb_voice_taiwan_calm -> ModelConfig.TTS_PROFILE_TAIWAN_CALM
                R.id.rb_voice_taiwan_male -> ModelConfig.TTS_PROFILE_TAIWAN_MALE
                R.id.rb_voice_taiwan_lively -> ModelConfig.TTS_PROFILE_TAIWAN_LIVELY
                R.id.rb_voice_custom -> ModelConfig.TTS_PROFILE_CUSTOM
                else -> ModelConfig.TTS_PROFILE_TAIWAN_SWEET
            }
            val promptText = etPrompt.text?.toString().orEmpty().ifBlank {
                "親切自然、帶有甜美微笑感的台灣女生"
            }

            ModelConfig.setTtsSpeakerProfile(this, selectedProfile)
            ModelConfig.setTtsStylePrompt(this, promptText)
            updateVoiceTuningUi()
            Toast.makeText(this, "已套用音色與風格設定！", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
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

    private fun handleDirectTtsSpeak(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        stopAll()
        setState(ChatState.THINKING)
        tvAcousticStatus.text = "TTS 正在合成語音中..."

        lifecycleScope.launch {
            try {
                val mode = ModelConfig.getLfm3SynthesisMode(this@Lfm3VoiceChatActivity)
                val profile = ModelConfig.getTtsSpeakerProfile(this@Lfm3VoiceChatActivity)
                val stylePrompt = ModelConfig.getTtsStylePrompt(this@Lfm3VoiceChatActivity)
                val synthSamples: FloatArray? = when (mode) {
                    ModelConfig.SYNTHESIS_MODE_QWEN3_TTS -> {
                        if (Qwen3TtsRunner.isAvailable(this@Lfm3VoiceChatActivity)) {
                            Qwen3TtsRunner.synthesizeSpeech(
                                context = this@Lfm3VoiceChatActivity,
                                text = trimmed,
                                stylePrompt = stylePrompt,
                                profile = profile
                            )
                        } else {
                            null
                        }
                    }
                    ModelConfig.SYNTHESIS_MODE_NATIVE_CODEC -> {
                        Lfm3AudioEngine.synthesizeNativeSpeech(this@Lfm3VoiceChatActivity, trimmed)
                    }
                    else -> {
                        null
                    }
                }

                val modeLabel = when (mode) {
                    ModelConfig.SYNTHESIS_MODE_QWEN3_TTS -> if (synthSamples != null) "Qwen3-TTS" else "系統 TTS (Qwen3 未下載)"
                    ModelConfig.SYNTHESIS_MODE_NATIVE_CODEC -> if (synthSamples != null) "LFM3 原生" else "系統 TTS"
                    else -> "系統 TTS"
                }

                // 加入聊天清單
                chatAdapter.addMessage(
                    Lfm3ChatMessage(
                        isUser = false,
                        text = "[$modeLabel 朗讀] $trimmed",
                        audioSamples = synthSamples
                    )
                )
                rvChatMessages.scrollToPosition(chatAdapter.itemCount - 1)

                // 播放語音
                if (synthSamples != null && synthSamples.isNotEmpty()) {
                    playAudio(synthSamples, 24_000) {
                        setState(ChatState.IDLE)
                    }
                } else {
                    speakText(trimmed) {
                        setState(ChatState.IDLE)
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Direct TTS synthesis failed: ${e.message}", e)
                Toast.makeText(this@Lfm3VoiceChatActivity, "語音合成失敗：${e.message}", Toast.LENGTH_SHORT).show()
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
                    tvAcousticStatus.text = "輸入文字直接唸出或點擊麥克風說話"
                    ivStatusIcon.setImageResource(R.drawable.ic_mic)
                    ivStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.md_theme_light_primary))
                    btnMicAction.text = "麥克風對話"
                    btnMicAction.isEnabled = true
                    btnSpeakText.isEnabled = true
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
                    btnSpeakText.isEnabled = false
                    btnStopSpeech.isEnabled = true
                    startPulseAnimation()
                }
                ChatState.THINKING -> {
                    ivStatusIcon.setImageResource(R.drawable.ic_ai_sparkle)
                    ivStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.tag_recommend))
                    btnMicAction.text = "處理中..."
                    btnMicAction.isEnabled = false
                    btnSpeakText.isEnabled = false
                    btnStopSpeech.isEnabled = true
                    stopPulseAnimation()
                }
                ChatState.SPEAKING -> {
                    tvAcousticStatus.text = "正在播放 TTS 語音朗讀..."
                    ivStatusIcon.setImageResource(R.drawable.ic_play_arrow)
                    ivStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.tag_recommend))
                    btnMicAction.text = "插話打斷"
                    btnMicAction.isEnabled = true
                    btnSpeakText.isEnabled = true
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
