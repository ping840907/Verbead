package com.ping.verbead.bubble

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.ping.verbead.R
import com.ping.verbead.VoiceAccessibilityService
import com.ping.verbead.engine.AudioRecorder
import com.ping.verbead.engine.ModelConfig
import com.ping.verbead.ModelDownloadService
import com.ping.verbead.engine.ModelDownloadState
import com.ping.verbead.engine.Qwen3AsrEngine
import com.ping.verbead.engine.XAsrEngine
import com.ping.verbead.slm.SlmChatAdapter
import com.ping.verbead.slm.SlmChatMessage
import com.ping.verbead.slm.SlmInferenceEngine
import com.ping.verbead.slm.SlmModelConfig
import com.ping.verbead.util.HapticUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Controller managing the floating SLM Chat overlay dialog,
 * model switching, thinking toggle, output formatting, speech input,
 * and conversational inference stream.
 */
class SlmChatOverlayController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val themedCtx: ContextThemeWrapper,
    private val scope: CoroutineScope,
    private val slmInferenceEngine: SlmInferenceEngine,
    private val recorder: AudioRecorder,
    private val xAsr: XAsrEngine,
    private val qwen3Asr: Qwen3AsrEngine,
    private val postProcess: (String) -> String,
    private val buildHotwords: () -> String,
    private val qwen3NeedsReload: () -> Boolean,
    private val showXButton: () -> Unit,
    private val hideXButton: () -> Unit,
    private val showPreviewText: (String, Boolean) -> Unit,
    private val copyToClipboardFallback: (String) -> Unit,
    private val onClosed: (animate: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "SlmChatOverlayCtrl"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var slmChatView: View? = null
    private var slmChatLayoutParams: WindowManager.LayoutParams? = null

    var isActive: Boolean = false
        private set

    private val slmMessages = mutableListOf<SlmChatMessage>()
    private var slmChatAdapter: SlmChatAdapter? = null

    private var slmVoiceJob: Job? = null
    private var isSlmVoiceRecording = false

    private var updateModelUiRef: (() -> Unit)? = null

    init {
        scope.launch {
            ModelDownloadState.active.collect {
                if (isActive && slmChatView != null) {
                    updateModelUiRef?.invoke()
                }
            }
        }
        scope.launch {
            ModelDownloadState.results.collect { (engine, _) ->
                if (isActive && slmChatView != null && SlmModelConfig.AVAILABLE_MODELS.any { it.id == engine }) {
                    updateModelUiRef?.invoke()
                }
            }
        }
    }

    fun open(
        anchorY: Int,
        isDockedOnRight: Boolean,
        screenWidth: Int,
        safeBottom: Int
    ) {
        if (isActive) return

        if (slmChatView == null) {
            slmChatView = LayoutInflater.from(themedCtx).inflate(R.layout.layout_floating_slm_chat, null)
        }
        val chatView = slmChatView ?: return

        val density = context.resources.displayMetrics.density
        val bubbleWidthPx = (60 * density).toInt()
        val gap = (10 * density).toInt()
        val maxChatWidth = screenWidth - bubbleWidthPx - gap - (12 * density).toInt()
        val chatWidth = (320 * density).toInt().coerceAtMost(maxChatWidth)

        val layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        val btnClose = chatView.findViewById<ImageButton>(R.id.btn_close_slm_chat)
        val tvAccelBadge = chatView.findViewById<TextView>(R.id.tv_slm_accel_badge)
        val btnModelToggle = chatView.findViewById<TextView>(R.id.btn_slm_model_toggle)
        val btnThinkingToggle = chatView.findViewById<LinearLayout>(R.id.btn_slm_thinking_toggle)
        val ivThinkingIcon = chatView.findViewById<ImageView>(R.id.iv_slm_thinking_icon)
        val tvThinkingLabel = chatView.findViewById<TextView>(R.id.tv_slm_thinking_label)
        val rvChat = chatView.findViewById<RecyclerView>(R.id.rv_slm_chat)
        val layoutEmpty = chatView.findViewById<View>(R.id.layout_slm_empty)
        val btnMic = chatView.findViewById<ImageButton>(R.id.btn_slm_mic)
        val etPrompt = chatView.findViewById<EditText>(R.id.et_slm_prompt)
        val btnSend = chatView.findViewById<ImageButton>(R.id.btn_slm_send)
        val btnModeFast = chatView.findViewById<TextView>(R.id.btn_mode_fast)
        val btnModeBullet = chatView.findViewById<TextView>(R.id.btn_mode_bullet)
        val btnModeRefine = chatView.findViewById<TextView>(R.id.btn_mode_refine)

        tvAccelBadge?.setOnClickListener {
            HapticUtil.click(context)
            val desc = SlmModelConfig.getHardwareAccelDescription(context)
            Toast.makeText(context, desc, Toast.LENGTH_SHORT).show()
        }

        btnClose.setOnClickListener {
            HapticUtil.click(context)
            close()
        }

        val tvEmptyTitle = chatView.findViewById<TextView>(R.id.tv_slm_empty_title)
        val tvEmptyDesc = chatView.findViewById<TextView>(R.id.tv_slm_empty_desc)
        val btnEmptyDownload = chatView.findViewById<MaterialButton>(R.id.btn_slm_empty_download)
        val btnEmptyLoad = chatView.findViewById<MaterialButton>(R.id.btn_slm_empty_load)
        val progressEmpty = chatView.findViewById<LinearProgressIndicator>(R.id.progress_slm_empty)
        val tvEmptyProgress = chatView.findViewById<TextView>(R.id.tv_slm_empty_progress)

        fun updateModelUi() {
            val selectedModel = SlmModelConfig.getSelectedModel(context)
            val meta = SlmModelConfig.getModelMetadata(selectedModel)
            val isDownloaded = SlmModelConfig.isModelDownloaded(context, selectedModel)
            val isLoaded = slmInferenceEngine.isLoaded(selectedModel)

            val statusTag = when {
                isLoaded -> "· 已載入"
                isDownloaded -> "· 已就緒"
                else -> "· 未下載"
            }
            btnModelToggle.text = "${meta.chipLabel} $statusTag"

            val active = ModelDownloadState.active.value
            if (active != null && active.engine == selectedModel) {
                btnEmptyDownload.visibility = View.VISIBLE
                btnEmptyDownload.text = "取消下載"
                btnEmptyLoad.visibility = View.GONE
                progressEmpty.visibility = View.VISIBLE
                progressEmpty.isIndeterminate = active.progress.percent < 0
                if (active.progress.percent >= 0) progressEmpty.progress = active.progress.percent
                tvEmptyProgress.visibility = View.VISIBLE
                tvEmptyProgress.text = "${active.progress.label} (${active.progress.percent}%)"
                tvEmptyTitle.text = "正在下載 ${meta.displayName}…"
                tvEmptyDesc.text = "檔案大小約 ${meta.quantizedSize}，下載將於背景進行"
            } else {
                progressEmpty.visibility = View.GONE
                tvEmptyProgress.visibility = View.GONE

                if (isDownloaded) {
                    btnEmptyDownload.visibility = View.GONE
                    if (isLoaded) {
                        btnEmptyLoad.visibility = View.GONE
                        tvEmptyTitle.text = "端側 ${meta.displayName} 已載入 · GPU/NPU 加速中"
                        tvEmptyDesc.text = "• 運算由 GPU (Vulkan) / NPU (NNAPI) 全層卸載加速\n• 點擊麥克風可用離線語音下達指令\n• 預設簡短扼要回覆，支援思考推導\n• 回覆後可一鍵複製或直接貼入輸入框"
                    } else {
                        btnEmptyLoad.visibility = View.VISIBLE
                        btnEmptyLoad.text = "載入至記憶體"
                        tvEmptyTitle.text = "端側 ${meta.displayName} 已就緒 (未載入)"
                        tvEmptyDesc.text = "模型檔案已下載完成。點擊下方按鈕預熱載入至記憶體，或直接發送問題自動載入。"
                    }
                } else {
                    btnEmptyLoad.visibility = View.GONE
                    btnEmptyDownload.visibility = View.VISIBLE
                    btnEmptyDownload.text = "下載此模型 (${meta.quantizedSize})"
                    tvEmptyTitle.text = "端側 ${meta.displayName} 尚未下載 (${meta.parameterCount})"
                    tvEmptyDesc.text = "${meta.description}\n\n檔案大小：${meta.quantizedSize}，點擊下方按鈕即可下載至手機本機運行。"
                }
            }
        }
        updateModelUiRef = ::updateModelUi
        updateModelUi()

        btnEmptyDownload.setOnClickListener {
            HapticUtil.click(context)
            val current = SlmModelConfig.getSelectedModel(context)
            val active = ModelDownloadState.active.value
            if (active != null && active.engine == current) {
                ModelDownloadService.cancel(context)
                Toast.makeText(context, R.string.toast_slm_download_cancelled, Toast.LENGTH_SHORT).show()
            } else {
                ModelDownloadService.start(context, current)
                val m = SlmModelConfig.getModelMetadata(current)
                Toast.makeText(context, context.getString(R.string.toast_slm_download_started, m.displayName), Toast.LENGTH_SHORT).show()
            }
            updateModelUi()
        }

        btnEmptyLoad.setOnClickListener {
            HapticUtil.click(context)
            val current = SlmModelConfig.getSelectedModel(context)
            scope.launch {
                btnEmptyLoad.isEnabled = false
                btnEmptyLoad.text = "載入中…"
                val res = slmInferenceEngine.loadModel(current)
                btnEmptyLoad.isEnabled = true
                if (res.isSuccess) {
                    val m = SlmModelConfig.getModelMetadata(current)
                    Toast.makeText(context, context.getString(R.string.toast_slm_loaded_memory, m.displayName), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, context.getString(R.string.toast_slm_load_failed, res.exceptionOrNull()?.message ?: "未知錯誤"), Toast.LENGTH_SHORT).show()
                }
                updateModelUi()
            }
        }

        btnModelToggle.setOnClickListener {
            HapticUtil.click(context)
            val current = SlmModelConfig.getSelectedModel(context)
            val nextMeta = SlmModelConfig.getNextModel(current)
            SlmModelConfig.setSelectedModel(context, nextMeta.id)
            if (SlmModelConfig.isModelDownloaded(context, nextMeta.id) && !slmInferenceEngine.isLoaded(nextMeta.id)) {
                scope.launch {
                    slmInferenceEngine.loadModel(nextMeta.id)
                    updateModelUi()
                }
            }
            updateModelUi()
            Toast.makeText(context, context.getString(R.string.toast_slm_switched_model, "${nextMeta.displayName} (${nextMeta.shortTag})"), Toast.LENGTH_SHORT).show()
        }

        btnModelToggle.setOnLongClickListener {
            HapticUtil.heavyClick(context)
            val popup = PopupMenu(themedCtx, btnModelToggle)
            SlmModelConfig.AVAILABLE_MODELS.forEachIndexed { index, meta ->
                val current = SlmModelConfig.getSelectedModel(context)
                val checkMark = if (meta.id == current) "● " else "   "
                val isDownloaded = SlmModelConfig.isModelDownloaded(context, meta.id)
                val isLoaded = slmInferenceEngine.isLoaded(meta.id)
                val statusTag = when {
                    isLoaded -> "[記憶體已載入]"
                    isDownloaded -> "[已下載就緒]"
                    else -> "[未下載 · ${meta.quantizedSize}]"
                }
                popup.menu.add(0, index, index, "$checkMark${meta.displayName} (${meta.chipLabel}) · $statusTag")
            }
            popup.setOnMenuItemClickListener { menuItem ->
                HapticUtil.click(context)
                val selected = SlmModelConfig.AVAILABLE_MODELS.getOrNull(menuItem.itemId)
                if (selected != null) {
                    SlmModelConfig.setSelectedModel(context, selected.id)
                    updateModelUi()
                    if (SlmModelConfig.isModelDownloaded(context, selected.id)) {
                        scope.launch {
                            slmInferenceEngine.loadModel(selected.id)
                            updateModelUi()
                        }
                    }
                    Toast.makeText(context, context.getString(R.string.toast_slm_switched_model, "${selected.displayName} (${selected.shortTag})"), Toast.LENGTH_SHORT).show()
                }
                true
            }
            popup.show()
            true
        }

        fun updateThinkingUi() {
            val thinkingEnabled = SlmModelConfig.isThinkingEnabled(context)
            if (thinkingEnabled) {
                btnThinkingToggle.setBackgroundResource(R.drawable.bg_slm_chip_active)
                ivThinkingIcon.setColorFilter(android.graphics.Color.parseColor("#FBBF24"))
                tvThinkingLabel.setTextColor(android.graphics.Color.parseColor("#FEF3C7"))
            } else {
                btnThinkingToggle.setBackgroundResource(R.drawable.bg_slm_chip_inactive)
                ivThinkingIcon.setColorFilter(android.graphics.Color.parseColor("#94A3B8"))
                tvThinkingLabel.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
            }
        }
        updateThinkingUi()

        btnThinkingToggle.setOnClickListener {
            HapticUtil.click(context)
            val next = !SlmModelConfig.isThinkingEnabled(context)
            SlmModelConfig.setThinkingEnabled(context, next)
            updateThinkingUi()
            val msgRes = if (next) R.string.toast_slm_thinking_on else R.string.toast_slm_thinking_off
            Toast.makeText(context, msgRes, Toast.LENGTH_SHORT).show()
        }

        fun updateModeUi() {
            val currentMode = SlmModelConfig.getOutputMode(context)
            fun styleModeBtn(btn: TextView, isModeActive: Boolean) {
                if (isModeActive) {
                    btn.setBackgroundResource(R.drawable.bg_slm_chip_active)
                    btn.setTextColor(android.graphics.Color.parseColor("#E0F2FE"))
                } else {
                    btn.setBackgroundResource(R.drawable.bg_slm_chip_inactive)
                    btn.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
                }
            }
            styleModeBtn(btnModeFast, currentMode == SlmModelConfig.OUTPUT_MODE_FAST)
            styleModeBtn(btnModeBullet, currentMode == SlmModelConfig.OUTPUT_MODE_BULLET)
            styleModeBtn(btnModeRefine, currentMode == SlmModelConfig.OUTPUT_MODE_REFINE)
        }
        updateModeUi()

        btnModeFast.setOnClickListener {
            HapticUtil.click(context)
            SlmModelConfig.setOutputMode(context, SlmModelConfig.OUTPUT_MODE_FAST)
            updateModeUi()
            Toast.makeText(context, context.getString(R.string.toast_slm_mode_switched, context.getString(R.string.slm_mode_fast)), Toast.LENGTH_SHORT).show()
        }

        btnModeBullet.setOnClickListener {
            HapticUtil.click(context)
            SlmModelConfig.setOutputMode(context, SlmModelConfig.OUTPUT_MODE_BULLET)
            updateModeUi()
            Toast.makeText(context, context.getString(R.string.toast_slm_mode_switched, context.getString(R.string.slm_mode_bullet)), Toast.LENGTH_SHORT).show()
        }

        btnModeRefine.setOnClickListener {
            HapticUtil.click(context)
            SlmModelConfig.setOutputMode(context, SlmModelConfig.OUTPUT_MODE_REFINE)
            updateModeUi()
            Toast.makeText(context, context.getString(R.string.toast_slm_mode_switched, context.getString(R.string.slm_mode_refine)), Toast.LENGTH_SHORT).show()
        }

        slmChatAdapter = SlmChatAdapter(
            messages = slmMessages,
            onCopyClick = { msg -> copySlmAnswer(msg) },
            onPasteClick = { msg -> pasteSlmAnswer(msg) }
        )
        rvChat.layoutManager = LinearLayoutManager(themedCtx).apply {
            stackFromEnd = true
        }
        rvChat.adapter = slmChatAdapter
        layoutEmpty.visibility = if (slmMessages.isEmpty()) View.VISIBLE else View.GONE

        btnMic.setOnClickListener {
            HapticUtil.click(context)
            startOrStopVoiceForSlm(btnMic, etPrompt)
        }

        btnSend.setOnClickListener {
            val text = etPrompt.text.toString().trim()
            if (text.isNotEmpty()) {
                HapticUtil.click(context)
                submitSlmPrompt(text, etPrompt, rvChat, layoutEmpty)
            }
        }

        etPrompt.setOnEditorActionListener { _, _, _ ->
            val text = etPrompt.text.toString().trim()
            if (text.isNotEmpty()) {
                HapticUtil.click(context)
                submitSlmPrompt(text, etPrompt, rvChat, layoutEmpty)
                true
            } else {
                false
            }
        }

        val maxAllowedHeight = maxOf((240 * density).toInt(), safeBottom - (16 * density).toInt())
        val defaultChatHeight = (440 * density).toInt().coerceAtMost(maxAllowedHeight)
        val maxAllowedY = safeBottom - defaultChatHeight - (8 * density).toInt()
        val targetY = anchorY.coerceIn(0, maxOf(0, maxAllowedY))

        slmChatLayoutParams = WindowManager.LayoutParams(
            chatWidth,
            defaultChatHeight,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = if (isDockedOnRight) Gravity.TOP or Gravity.RIGHT else Gravity.TOP or Gravity.LEFT
            x = bubbleWidthPx + gap
            y = targetY
        }

        val bubbleCenterY = anchorY + (30 * density)
        val relativePivotY = (bubbleCenterY - targetY).coerceIn(0f, defaultChatHeight.toFloat())

        chatView.pivotX = if (isDockedOnRight) chatWidth.toFloat() else 0f
        chatView.pivotY = relativePivotY
        chatView.alpha = 0f
        chatView.scaleX = 0.5f
        chatView.scaleY = 0.5f
        chatView.translationX = if (isDockedOnRight) (20 * density) else -(20 * density)

        try {
            if (chatView.isAttachedToWindow) {
                windowManager.removeView(chatView)
            }
            windowManager.addView(chatView, slmChatLayoutParams)
            isActive = true
            showXButton()

            chatView.animate()
                .alpha(1f)
                .translationX(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(240)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .start()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add slmChatView", e)
        }
    }

    fun close(hideX: Boolean = true) {
        if (!isActive) return
        isActive = false
        if (isSlmVoiceRecording) {
            val btnMic = slmChatView?.findViewById<ImageButton>(R.id.btn_slm_mic)
            if (btnMic != null) stopRecordingForSlm(btnMic)
        }
        slmInferenceEngine.cancelCurrent()

        if (hideX) {
            hideXButton()
        }
        val chat = slmChatView ?: return
        val density = context.resources.displayMetrics.density
        val targetTranslationX = (24 * density)

        chat.animate()
            .alpha(0f)
            .translationX(targetTranslationX)
            .scaleX(0.6f)
            .scaleY(0.6f)
            .setDuration(180)
            .withEndAction {
                if (chat.isAttachedToWindow) {
                    try {
                        windowManager.removeView(chat)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to remove slmChatView", e)
                    }
                }
                slmChatView = null
                slmChatAdapter = null
                updateModelUiRef = null
                onClosed(true)
            }
            .start()
    }

    fun updatePosition(
        anchorY: Int,
        isDockedOnRight: Boolean,
        screenWidth: Int,
        safeBottom: Int
    ) {
        val chat = slmChatView ?: return
        val lp = slmChatLayoutParams ?: return
        val density = context.resources.displayMetrics.density
        val bubbleWidthPx = (60 * density).toInt()
        val gap = (10 * density).toInt()
        val maxChatWidth = screenWidth - bubbleWidthPx - gap - (12 * density).toInt()
        val chatWidth = (320 * density).toInt().coerceAtMost(maxChatWidth)
        val defaultChatHeight = (440 * density).toInt().coerceAtMost(safeBottom - (16 * density).toInt())

        lp.gravity = if (isDockedOnRight) Gravity.TOP or Gravity.RIGHT else Gravity.TOP or Gravity.LEFT
        lp.x = bubbleWidthPx + gap
        lp.width = chatWidth
        lp.height = defaultChatHeight
        val maxAllowedY = safeBottom - defaultChatHeight - (8 * density).toInt()
        lp.y = anchorY.coerceIn(0, maxOf(0, maxAllowedY))

        if (chat.isAttachedToWindow) {
            try {
                windowManager.updateViewLayout(chat, lp)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update slmChatView layout", e)
            }
        }
    }

    private fun startOrStopVoiceForSlm(btnMic: ImageButton, etPrompt: EditText) {
        if (isSlmVoiceRecording) {
            stopRecordingForSlm(btnMic)
            return
        }

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(context, R.string.toast_mic_permission_voice, Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            return
        }

        val isDual = ModelConfig.isDualEngineActive(context)
        val currentEngine = ModelConfig.selectedEngine(context)

        // Validate model readiness per user's active engine preference
        if (isDual) {
            val xReady = ModelConfig.isXAsrReady(context)
            val qReady = ModelConfig.isQwen3Ready(context)
            if (!xReady || !qReady) {
                val missing = if (!xReady && !qReady) "X-ASR 與 Qwen3-ASR" else if (!xReady) "X-ASR" else "Qwen3-ASR"
                Toast.makeText(context, context.getString(R.string.toast_asr_model_not_ready, missing), Toast.LENGTH_SHORT).show()
                return
            }
        } else if (currentEngine == ModelConfig.ENGINE_X_ASR) {
            if (!ModelConfig.isXAsrReady(context)) {
                Toast.makeText(context, context.getString(R.string.toast_asr_model_not_ready, "X-ASR"), Toast.LENGTH_SHORT).show()
                return
            }
        } else {
            if (!ModelConfig.isQwen3Ready(context)) {
                Toast.makeText(context, context.getString(R.string.toast_asr_model_not_ready, "Qwen3-ASR"), Toast.LENGTH_SHORT).show()
                return
            }
        }

        slmVoiceJob = scope.launch {
            // 1. Ensure required engine models are loaded into memory
            val needXAsr = isDual || currentEngine == ModelConfig.ENGINE_X_ASR
            val needQwen3 = isDual || currentEngine == ModelConfig.ENGINE_QWEN3

            if ((needXAsr && !xAsr.isLoaded()) || (needQwen3 && (!qwen3Asr.isLoaded() || qwen3NeedsReload()))) {
                etPrompt.hint = "載入語音引擎中…"
                withContext(Dispatchers.IO) {
                    if (needXAsr && !xAsr.isLoaded()) xAsr.load()
                    if (needQwen3 && (!qwen3Asr.isLoaded() || qwen3NeedsReload())) qwen3Asr.load(buildHotwords())
                }
            }

            // 2. Set recording UI state
            isSlmVoiceRecording = true
            btnMic.setBackgroundResource(R.drawable.bg_slm_mic_recording)
            btnMic.setColorFilter(android.graphics.Color.WHITE)
            HapticUtil.click(context)

            val vadSilence = ModelConfig.vadSilenceSeconds(context)

            try {
                if (isDual) {
                    // Pipeline: Dual Engine (X-ASR streaming preview + Qwen3-ASR high-accuracy batch finalize)
                    etPrompt.hint = "聆聽中 (雙引擎)…"
                    val stream = xAsr.createStream() ?: return@launch
                    val audioBuffer = mutableListOf<FloatArray>()
                    var accumulated = ""

                    withContext(Dispatchers.IO) {
                        recorder.recordStreaming(
                            silenceSeconds = vadSilence,
                            onChunk = { chunk ->
                                if (!isSlmVoiceRecording) return@recordStreaming
                                audioBuffer.add(chunk.copyOf())
                                xAsr.acceptWaveform(stream, chunk)
                                while (xAsr.isReady(stream)) {
                                    xAsr.decode(stream)
                                }
                                val partial = xAsr.getResult(stream)
                                if (partial.isNotBlank()) {
                                    val fullPreview = postProcess(accumulated + partial)
                                    mainHandler.post {
                                        etPrompt.setText(fullPreview)
                                        etPrompt.setSelection(fullPreview.length)
                                    }
                                }
                                if (xAsr.isEndpoint(stream)) {
                                    accumulated += partial
                                    xAsr.reset(stream)
                                }
                            }
                        )
                    }

                    runCatching { xAsr.inputFinished(stream) }
                    runCatching {
                        while (xAsr.isReady(stream)) {
                            xAsr.decode(stream)
                        }
                    }
                    val finalStreamPartial = xAsr.getResult(stream).trim()
                    runCatching { stream.release() }

                    val totalSamples = audioBuffer.sumOf { it.size }
                    if (totalSamples > 0 && qwen3Asr.isLoaded()) {
                        withContext(Dispatchers.Main) { etPrompt.hint = "高精轉譯中…" }
                        val combinedAudio = FloatArray(totalSamples)
                        var offset = 0
                        for (c in audioBuffer) {
                            System.arraycopy(c, 0, combinedAudio, offset, c.size)
                            offset += c.size
                        }
                        val qwen3Result = runCatching { qwen3Asr.transcribe(combinedAudio) }.getOrNull()
                        val finalText = if (!qwen3Result.isNullOrBlank()) {
                            postProcess(qwen3Result)
                        } else {
                            postProcess(accumulated + finalStreamPartial)
                        }
                        if (finalText.isNotBlank()) {
                            withContext(Dispatchers.Main) {
                                etPrompt.setText(finalText)
                                etPrompt.setSelection(finalText.length)
                            }
                        }
                    }
                } else if (currentEngine == ModelConfig.ENGINE_X_ASR) {
                    // Pipeline: X-ASR Streaming Transducer
                    etPrompt.hint = "聆聽中 (X-ASR 串流)…"
                    val stream = xAsr.createStream() ?: return@launch
                    var accumulated = ""

                    withContext(Dispatchers.IO) {
                        recorder.recordStreaming(
                            silenceSeconds = vadSilence,
                            onChunk = { chunk ->
                                if (!isSlmVoiceRecording) return@recordStreaming
                                xAsr.acceptWaveform(stream, chunk)
                                while (xAsr.isReady(stream)) {
                                    xAsr.decode(stream)
                                }
                                val partial = xAsr.getResult(stream)
                                if (partial.isNotBlank()) {
                                    val fullPreview = postProcess(accumulated + partial)
                                    mainHandler.post {
                                        etPrompt.setText(fullPreview)
                                        etPrompt.setSelection(fullPreview.length)
                                    }
                                }
                                if (xAsr.isEndpoint(stream)) {
                                    accumulated += partial
                                    xAsr.reset(stream)
                                }
                            }
                        )
                    }

                    runCatching { xAsr.inputFinished(stream) }
                    runCatching {
                        while (xAsr.isReady(stream)) {
                            xAsr.decode(stream)
                        }
                    }
                    val finalStreamPartial = xAsr.getResult(stream).trim()
                    runCatching { stream.release() }

                    val finalText = postProcess(accumulated + finalStreamPartial)
                    if (finalText.isNotBlank()) {
                        withContext(Dispatchers.Main) {
                            etPrompt.setText(finalText)
                            etPrompt.setSelection(finalText.length)
                        }
                    }
                } else {
                    // Pipeline: Qwen3-ASR Offline Speech Recognition
                    etPrompt.hint = "聆聽中 (Qwen3-ASR 離線)…"
                    val audioBuffer = mutableListOf<FloatArray>()

                    withContext(Dispatchers.IO) {
                        recorder.recordStreaming(
                            silenceSeconds = vadSilence,
                            onChunk = { chunk ->
                                if (!isSlmVoiceRecording) return@recordStreaming
                                audioBuffer.add(chunk.copyOf())
                            }
                        )
                    }

                    val totalSamples = audioBuffer.sumOf { it.size }
                    if (totalSamples > 0 && qwen3Asr.isLoaded()) {
                        withContext(Dispatchers.Main) { etPrompt.hint = "離線高精辨識中…" }
                        val combinedAudio = FloatArray(totalSamples)
                        var offset = 0
                        for (c in audioBuffer) {
                            System.arraycopy(c, 0, combinedAudio, offset, c.size)
                            offset += c.size
                        }
                        val qwen3Result = runCatching { qwen3Asr.transcribe(combinedAudio) }.getOrNull()
                        if (!qwen3Result.isNullOrBlank()) {
                            val finalText = postProcess(qwen3Result)
                            withContext(Dispatchers.Main) {
                                etPrompt.setText(finalText)
                                etPrompt.setSelection(finalText.length)
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "SLM voice pipeline error", e)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    stopRecordingForSlm(btnMic)
                    etPrompt.hint = "語音或輸入指令..."
                }
            }
        }
    }

    private fun stopRecordingForSlm(btnMic: ImageButton) {
        if (!isSlmVoiceRecording) return
        isSlmVoiceRecording = false
        recorder.stopEarly()
        slmVoiceJob?.cancel()
        slmVoiceJob = null
        btnMic.setBackgroundResource(R.drawable.bg_scanner_btn_circle)
        btnMic.setColorFilter(android.graphics.Color.parseColor("#38BDF8"))
        HapticUtil.click(context)
    }

    private fun submitSlmPrompt(
        prompt: String,
        etPrompt: EditText,
        rvChat: RecyclerView,
        emptyView: View
    ) {
        emptyView.visibility = View.GONE
        etPrompt.text.clear()

        // 1. Add user message
        val userMsg = SlmChatMessage(
            role = SlmChatMessage.Role.USER,
            content = prompt
        )
        slmMessages.add(userMsg)
        slmChatAdapter?.notifyItemInserted(slmMessages.size - 1)
        rvChat.scrollToPosition(slmMessages.size - 1)

        // 2. Add assistant placeholder
        val selectedModel = SlmModelConfig.getSelectedModel(context)
        val meta = SlmModelConfig.getModelMetadata(selectedModel)
        val accelTag = SlmModelConfig.getHardwareAccelTag(context)
        val modeLabel = SlmModelConfig.getOutputModeLabel(SlmModelConfig.getOutputMode(context))
        val assistantMsg = SlmChatMessage(
            role = SlmChatMessage.Role.ASSISTANT,
            content = "",
            thinkingContent = null,
            isStreaming = true,
            modelTag = "${meta.displayName} · $modeLabel · $accelTag"
        )
        slmMessages.add(assistantMsg)
        val assistantPos = slmMessages.size - 1
        slmChatAdapter?.notifyItemInserted(assistantPos)
        rvChat.scrollToPosition(assistantPos)

        // 3. Trigger generation
        slmInferenceEngine.generate(
            userPrompt = prompt,
            history = slmMessages.dropLast(2),
            callback = object : SlmInferenceEngine.StreamCallback {
                override fun onThinkingUpdate(thinking: String) {
                    assistantMsg.thinkingContent = thinking
                    slmChatAdapter?.notifyItemChanged(assistantPos)
                    rvChat.scrollToPosition(assistantPos)
                }

                override fun onAnswerUpdate(answer: String) {
                    assistantMsg.content = answer
                    slmChatAdapter?.notifyItemChanged(assistantPos)
                    rvChat.scrollToPosition(assistantPos)
                }

                override fun onComplete(fullMessage: SlmChatMessage) {
                    assistantMsg.isStreaming = false
                    assistantMsg.content = fullMessage.content
                    assistantMsg.thinkingContent = fullMessage.thinkingContent
                    slmChatAdapter?.notifyItemChanged(assistantPos)
                    rvChat.scrollToPosition(assistantPos)
                }

                override fun onError(error: Throwable) {
                    assistantMsg.isStreaming = false
                    assistantMsg.content = "推論失敗：${error.localizedMessage ?: "未知錯誤"}"
                    slmChatAdapter?.notifyItemChanged(assistantPos)
                }
            }
        )
    }

    private fun copySlmAnswer(message: SlmChatMessage) {
        val clean = message.getCleanAnswer()
        if (clean.isBlank()) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("SLM Answer", clean))
        Toast.makeText(context, R.string.toast_slm_copied, Toast.LENGTH_SHORT).show()
    }

    private fun pasteSlmAnswer(message: SlmChatMessage) {
        val clean = message.getCleanAnswer()
        if (clean.isBlank()) return
        val injected = VoiceAccessibilityService.instance?.inputText(clean) ?: false
        if (injected) {
            showPreviewText(context.getString(R.string.toast_slm_pasted), true)
            Toast.makeText(context, R.string.toast_slm_pasted, Toast.LENGTH_SHORT).show()
        } else {
            copyToClipboardFallback(clean)
            showPreviewText(context.getString(R.string.toast_slm_pasted_fallback), true)
            Toast.makeText(context, R.string.toast_slm_pasted_fallback, Toast.LENGTH_SHORT).show()
        }
    }

    fun destroy() {
        close(hideX = false)
        slmInferenceEngine.cancelCurrent()
    }
}
