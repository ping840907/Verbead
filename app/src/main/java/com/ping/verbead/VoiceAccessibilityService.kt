package com.ping.verbead

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.ping.verbead.engine.ModelConfig
import com.ping.verbead.util.TextInsertion

class VoiceAccessibilityService : AccessibilityService() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.ping.verbead.util.AppLocale.wrap(newBase))
    }


    data class KeyboardInfo(
        val isVisible: Boolean,
        val keyboardTop: Int,
        val keyboardHeight: Int
    )

    data class EditorSnapshot(
        val node: AccessibilityNodeInfo?,
        val originalText: String,
        val selectionStart: Int,
        val selectionEnd: Int,
        /** 是否以 IME 通道插入（還原時只需刪除剛插入的字元） */
        val viaIme: Boolean = false,
        /** 本次插入的文字（IME 還原用） */
        val injectedText: String = "",
        val capturedAtMs: Long = System.currentTimeMillis()
    )

    companion object {
        private const val TAG = "VoiceAccessibility"

        @Volatile
        var instance: VoiceAccessibilityService? = null
            private set

        fun isServiceRunning(): Boolean = instance != null

        private val _keyboardStateFlow = MutableSharedFlow<KeyboardInfo>(replay = 1, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val keyboardStateFlow: SharedFlow<KeyboardInfo> = _keyboardStateFlow.asSharedFlow()

        private val _manualTypingFlow = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val manualTypingFlow: SharedFlow<Unit> = _manualTypingFlow.asSharedFlow()

        private val _cursorMovedFlow = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val cursorMovedFlow: SharedFlow<Unit> = _cursorMovedFlow.asSharedFlow()

        private val _inputFocusLostFlow = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val inputFocusLostFlow: SharedFlow<Unit> = _inputFocusLostFlow.asSharedFlow()
    }

    private var lastFocusedNode: AccessibilityNodeInfo? = null
    private var currentInputState: Boolean? = null
    private var currentKeyboardInfo: KeyboardInfo = KeyboardInfo(false, 0, 0)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isAutomatedActionInProgress = false
    var lastSnapshot: EditorSnapshot? = null
        private set
    private var lastInjectedText: String? = null
    private var lastInjectedCursorPos: Int = -1
    private val releaseGuardRunnable = Runnable { isAutomatedActionInProgress = false }

    /** 自動操作結束後延遲解除「忽略自身事件」旗標；重複呼叫會重新計時，避免較早的計時器提前解除。 */
    private fun releaseAutomationGuardLater() {
        mainHandler.removeCallbacks(releaseGuardRunnable)
        mainHandler.postDelayed(releaseGuardRunnable, 800)
    }

    fun hasValidSnapshot(): Boolean {
        val snap = lastSnapshot ?: return false
        return (System.currentTimeMillis() - snap.capturedAtMs) < 15_000
    }

    /**
     * 擷取目前整個螢幕畫面（需 Android 11+）。回呼在主執行緒；失敗回傳 null。
     * 回傳的是已複製為軟體 ARGB_8888 的 Bitmap，呼叫端負責 recycle。
     */
    @android.annotation.SuppressLint("NewApi") // guarded by the SDK_INT check below
    fun captureScreen(callback: (android.graphics.Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback(null)
            return
        }
        try {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                java.util.concurrent.Executors.newSingleThreadExecutor(),
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        var bmp: android.graphics.Bitmap? = null
                        try {
                            val hw = android.graphics.Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            bmp = hw?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                            hw?.recycle()
                        } catch (e: Throwable) {
                            Log.e("VoiceA11y", "screenshot convert failed", e)
                        } finally {
                            result.hardwareBuffer.close()
                        }
                        mainHandler.post { callback(bmp) }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w("VoiceA11y", "takeScreenshot failed: $errorCode")
                        mainHandler.post { callback(null) }
                    }
                }
            )
        } catch (e: Throwable) {
            Log.e("VoiceA11y", "takeScreenshot error", e)
            callback(null)
        }
    }

    private var isKeyboardCheckScheduled = false
    private val keyboardCheckRunnable = Runnable {
        isKeyboardCheckScheduled = false
        checkKeyboardState()
    }

    fun scheduleKeyboardCheck(delayMs: Long = 0) {
        if (delayMs <= 0) {
            mainHandler.removeCallbacks(keyboardCheckRunnable)
            isKeyboardCheckScheduled = false
            checkKeyboardState()
        } else if (!isKeyboardCheckScheduled) {
            isKeyboardCheckScheduled = true
            mainHandler.postDelayed(keyboardCheckRunnable, delayMs)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "VoiceAccessibilityService connected")
        checkKeyboardState()
        if (android.provider.Settings.canDrawOverlays(this)) {
            FloatingBubbleService.start(this)
        }
        if (BuildConfig.DEBUG) {
            val filter = IntentFilter("com.ping.verbead.TEST_INPUT")
            androidx.core.content.ContextCompat.registerReceiver(
                this, testReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED
            )
        }
    }

    private val testReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val setup = intent.getStringExtra("setupText")
            if (setup != null) {
                val sel = intent.getIntExtra("setupSel", 0)
                val target = findActiveEditableNode()
                if (target != null) {
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, setup)
                    }
                    target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    val selArgs = Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, sel)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, sel)
                    }
                    target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
                    Log.d(TAG, "TestReceiver setup completed: text='$setup', cursor=$sel")
                }
                return
            }
            if (intent.getBooleanExtra("clickX", false)) {
                Log.d(TAG, "TestReceiver received clickX request")
                FloatingBubbleService.instance?.onXButtonClick()
                return
            }
            if (intent.getBooleanExtra("undo", false)) {
                Log.d(TAG, "TestReceiver received undo request")
                restoreLastSnapshot()
                return
            }
            if (intent.getBooleanExtra("enter", false)) {
                Log.d(TAG, "TestReceiver received enter request")
                sendEnterKey()
                return
            }
            val text = intent.getStringExtra("text") ?: return
            Log.d(TAG, "TestReceiver received text: $text")
            val injected = inputText(text)
            if (injected) {
                FloatingBubbleService.instance?.showPastedStateForTest()
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        mainHandler.removeCallbacks(keyboardCheckRunnable)
        if (BuildConfig.DEBUG) {
            runCatching { unregisterReceiver(testReceiver) }
        }
        lastSnapshot = null
        lastInjectedText = null
        lastInjectedCursorPos = -1
        Log.i(TAG, "VoiceAccessibilityService disconnected")
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        Log.w(TAG, "VoiceAccessibilityService interrupted")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName == packageName) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Coalesce / debounce rapid window change bursts (30ms) to avoid IPC congestion
                scheduleKeyboardCheck(30)
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                scheduleKeyboardCheck(30)
                val source = event.source
                if (source != null && isEditableNode(source)) {
                    lastFocusedNode = source
                    notifyInputState(true)
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                if (lastSnapshot != null && !isAutomatedActionInProgress) {
                    val currentText = event.text?.joinToString("") ?: ""
                    if (currentText.isNotEmpty() && currentText == lastInjectedText) {
                        Log.d(TAG, "Ignoring TYPE_VIEW_TEXT_CHANGED matching lastInjectedText")
                    } else {
                        Log.d(TAG, "Manual typing detected: emitting manualTypingFlow")
                        _manualTypingFlow.tryEmit(Unit)
                    }
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                if (lastSnapshot != null && !isAutomatedActionInProgress) {
                    val selStart = event.fromIndex
                    val selEnd = event.toIndex
                    if (lastInjectedCursorPos >= 0 && selStart == lastInjectedCursorPos && selEnd == lastInjectedCursorPos) {
                        Log.d(TAG, "Ignoring TYPE_VIEW_TEXT_SELECTION_CHANGED matching lastInjectedCursorPos")
                    } else {
                        Log.d(TAG, "Cursor moved detected: emitting cursorMovedFlow (sel=$selStart..$selEnd, injected=$lastInjectedCursorPos)")
                        _cursorMovedFlow.tryEmit(Unit)
                    }
                }
            }
        }
    }

    fun checkKeyboardState(): KeyboardInfo {
        val windowList = runCatching { windows }.getOrNull() ?: emptyList()
        val screenHeight = resources.displayMetrics.heightPixels
        val imeWindow = windowList.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

        if (imeWindow != null) {
            val bounds = Rect()
            imeWindow.getBoundsInScreen(bounds)
            // Keyboard is active if height > 100, top is within screen bounds
            if (bounds.height() > 100 && bounds.top < screenHeight && bounds.bottom >= bounds.top) {
                val keyboardHeight = bounds.height()
                val keyboardTop = bounds.top
                val info = KeyboardInfo(isVisible = true, keyboardTop = keyboardTop, keyboardHeight = keyboardHeight)
                updateKeyboardState(info)
                return info
            }
        }
        val info = KeyboardInfo(isVisible = false, keyboardTop = screenHeight, keyboardHeight = 0)
        updateKeyboardState(info)
        return info
    }

    private fun updateKeyboardState(info: KeyboardInfo) {
        if (currentKeyboardInfo == info) return
        currentKeyboardInfo = info
        Log.i(TAG, "updateKeyboardState: isVisible=${info.isVisible}, top=${info.keyboardTop}, height=${info.keyboardHeight}")
        _keyboardStateFlow.tryEmit(info)
    }

    private fun notifyInputState(hasInputFocus: Boolean) {
        if (currentInputState == hasInputFocus) return
        currentInputState = hasInputFocus
        Log.i(TAG, "notifyInputState: hasInputFocus=$hasInputFocus")
        if (!hasInputFocus) {
            _inputFocusLostFlow.tryEmit(Unit)
        }
    }

    private fun isEditableNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isEditable) return true
        val cls = node.className?.toString() ?: ""
        return cls.contains("EditText", ignoreCase = true) ||
                cls.contains("AutoCompleteTextView", ignoreCase = true) ||
                cls.contains("SearchAutoComplete", ignoreCase = true)
    }

    /**
     * 找出目前取得輸入焦點的可編輯節點（僅供 Android 13 以下的備援路徑與還原使用）。
     * 只認「有焦點」的欄位，不再猜測畫面上任意一個可編輯欄位，避免填錯地方。
     */
    fun findActiveEditableNode(): AccessibilityNodeInfo? {
        runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            ?.takeIf { isEditableNode(it) }?.let { return it }

        val appWindows = runCatching { windows }.getOrNull().orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        for (window in appWindows) {
            val root = window.root ?: continue
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { isEditableNode(it) }?.let { return it }
            searchFocusedEditable(root)?.let { return it }
        }

        lastFocusedNode?.let { last ->
            runCatching { last.refresh() }
            if (isEditableNode(last)) return last
        }
        return null
    }

    private fun searchFocusedEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isFocused && isEditableNode(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchFocusedEditable(child)
            if (found != null) return found
        }
        return null
    }

    // ── Accessibility IME channel (Android 13+) ─────────────────────────────────────────
    // ACTION_SET_TEXT 會「整段取代」欄位內容，Google Keep 這類自管編輯狀態的欄位事後會與內部模型脫節
    // （按換行鍵游標逐字亂跳、最後閃退）；Google Docs 這類自繪編輯器則根本不支援 SET_TEXT。
    // 透過 InputMethod 通道（等同鍵盤的 commitText）插入，欄位會把它當成一般打字，行為最接近原生輸入。

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreateInputMethod(): android.accessibilityservice.InputMethod =
        android.accessibilityservice.InputMethod(this)

    private fun imeConnection(): android.accessibilityservice.InputMethod.AccessibilityInputConnection? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return runCatching {
            val im = inputMethod
            if (im == null || !im.currentInputStarted) null else im.currentInputConnection
        }.getOrNull()
    }

    private fun isPasswordInputType(inputType: Int): Boolean {
        val cls = inputType and android.text.InputType.TYPE_MASK_CLASS
        val variation = inputType and android.text.InputType.TYPE_MASK_VARIATION
        return when (cls) {
            android.text.InputType.TYPE_CLASS_TEXT ->
                variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            android.text.InputType.TYPE_CLASS_NUMBER ->
                variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /** 以 IME 通道插入文字；通道不可用或為密碼欄位時回傳 false，由呼叫端退回其他方式。 */
    private fun tryInputViaIme(text: String, createSnapshot: Boolean): Boolean {
        val ic = imeConnection() ?: return false
        val info = runCatching { inputMethod?.currentInputEditorInfo }.getOrNull()
        if (info != null && isPasswordInputType(info.inputType)) return false

        val target = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        if (target?.isPassword == true) return false

        isAutomatedActionInProgress = true
        try {
            if (createSnapshot) {
                // 不做選取探測（避免干擾游標），僅採用系統明確標示的提示文字旗標
                val showingHint = target != null &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && target.isShowingHintText
                lastSnapshot = EditorSnapshot(
                    node = target,
                    originalText = if (showingHint) "" else (target?.text?.toString() ?: ""),
                    selectionStart = target?.textSelectionStart ?: -1,
                    selectionEnd = target?.textSelectionEnd ?: -1,
                    viaIme = true,
                    injectedText = text
                )
                lastInjectedText = text
                lastInjectedCursorPos = -1
            }
            ic.commitText(text, 1, null)
            Log.i(TAG, "inputText: committed ${text.length} chars via accessibility IME channel")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "IME commitText failed: ${e.message}")
            return false
        } finally {
            releaseAutomationGuardLater()
        }
    }

    /** IME 插入的精確反操作：刪掉剛插入的字元，若當時取代了選取文字則補回。 */
    private fun undoViaIme(snapshot: EditorSnapshot): Boolean {
        if (snapshot.injectedText.isEmpty()) return false
        val ic = imeConnection() ?: return false
        return runCatching {
            ic.deleteSurroundingText(snapshot.injectedText.length, 0)
            val s = snapshot.selectionStart
            val e = snapshot.selectionEnd
            if (s in 0 until e && e <= snapshot.originalText.length) {
                ic.commitText(snapshot.originalText.substring(s, e), 1, null)
            }
            true
        }.getOrDefault(false)
    }

    /**
     * Injects [text] into the currently focused editable node in the active foreground window.
     * Uses ACTION_SET_TEXT combined with cursor calculation to insert text at current cursor without
     * writing to the system clipboard, preserving user privacy.
     * Skips password fields. The clipboard is only used in clipboard-paste mode; if ACTION_SET_TEXT fails this returns false.
     */
    fun inputText(text: String, createSnapshot: Boolean = true): Boolean {
        if (text.isEmpty()) return false

        // 直接填入模式：優先走 IME 通道（Keep、Docs 等無法可靠使用 SET_TEXT 的應用）
        if (!ModelConfig.isPasteModeEnabled(this) && tryInputViaIme(text, createSnapshot)) {
            return true
        }

        val target = findActiveEditableNode()
        if (target == null) {
            Log.w(TAG, "No focused editable node found across all windows and cache")
            return false
        }

        // 1.2 排除密碼欄位：拒絕貼入，也不建立快照
        if (target.isPassword) {
            Log.w(TAG, "Target node is a password field; rejecting automated input for privacy/security")
            mainHandler.post {
                Toast.makeText(this, R.string.toast_password_not_supported, Toast.LENGTH_SHORT).show()
            }
            return false
        }

        Log.i(TAG, "inputText: target=${target.className}, isEditable=${target.isEditable}, isFocused=${target.isFocused}")

        // Ensure focused
        if (!target.isFocused) {
            val focused = runCatching { target.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }.getOrDefault(false)
            if (focused) {
                runCatching { target.refresh() }
            }
        }

        isAutomatedActionInProgress = true
        try {
            val isPasteMode = ModelConfig.isPasteModeEnabled(this)

            if (isPasteMode) {
                // ── 模式 2: 剪貼簿貼上 (Text Paste) ──
                // 直接透過剪貼簿貼上（僅 Android 13 以下使用；13 以上一律走 IME 通道）
                if (createSnapshot) {
                    val showingHint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && target.isShowingHintText
                    val initialText = if (showingHint) "" else (target.text?.toString() ?: "")
                    val rawSelStart = target.textSelectionStart
                    val rawSelEnd = target.textSelectionEnd
                    lastSnapshot = EditorSnapshot(
                        node = target,
                        originalText = initialText,
                        selectionStart = rawSelStart,
                        selectionEnd = rawSelEnd
                    )
                    lastInjectedText = text
                    lastInjectedCursorPos = -1
                }

                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("text", text).apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        description.extras = PersistableBundle().apply {
                            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                        }
                    }
                }
                clipboard.setPrimaryClip(clip)

                val pasted = runCatching {
                    target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                }.getOrDefault(false)
                Log.i(TAG, "Paste mode ACTION_PASTE result: $pasted")
                if (!pasted) {
                    mainHandler.post {
                        Toast.makeText(this, R.string.toast_fallback_clipboard, Toast.LENGTH_SHORT).show()
                    }
                }
                return pasted
            }

            // ── 模式 1: 無障礙直接填入 (SetText) ──
            val initialText = target.text?.toString() ?: ""
            val rawSelStart = target.textSelectionStart
            val rawSelEnd = target.textSelectionEnd

            // 備援路徑（Android 13 以下）：只相信系統明確標示的提示文字旗標
            val isHint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && target.isShowingHintText
            val insertion = TextInsertion.insert(
                originalText = initialText,
                rawSelStart = rawSelStart,
                rawSelEnd = rawSelEnd,
                insertedText = text,
                isHint = isHint
            )

            if (createSnapshot) {
                // §6.1 快照捕捉時機點：在執行輸入動作前捕捉
                lastSnapshot = EditorSnapshot(
                    node = target,
                    originalText = if (isHint) "" else initialText,
                    selectionStart = insertion.normalizedSelStart,
                    selectionEnd = insertion.normalizedSelEnd
                )
                lastInjectedText = insertion.text
                lastInjectedCursorPos = insertion.cursorPosition
            }

            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, insertion.text)
            }
            val setSuccess = runCatching {
                target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }.getOrDefault(false)

            Log.i(TAG, "ACTION_SET_TEXT result: $setSuccess")

            if (setSuccess) {
                // 送出 ACTION_SET_TEXT 後，以 ACTION_SET_SELECTION 將游標移到計算出的新位置
                val selArgs = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, insertion.cursorPosition)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, insertion.cursorPosition)
                }
                runCatching { target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs) }
                return true
            }

            // SET_TEXT 失敗：直接填入模式絕不寫入剪貼簿（只有「剪貼簿貼上」模式才會動用剪貼簿）。
            // 回傳 false，由呼叫端決定是否放進待填文字清單。
            Log.w(TAG, "ACTION_SET_TEXT failed; not touching the clipboard outside clipboard-paste mode")
            return false
        } finally {
            releaseAutomationGuardLater()
        }
    }

    /**
     * §6.1 還原最近一次填入。原則：做了什麼就反做什麼。
     * - IME 通道插入：刪掉剛插入的字元（必要時補回被取代的選取文字）。
     * - 其餘（SetText／貼上）：以 ACTION_SET_TEXT 寫回原始文字並還原游標。
     * 不再搜尋目標應用程式自己的「復原」按鈕：那會依語系關鍵字猜測，且可能連使用者先前的輸入一起復原。
     */
    fun restoreLastSnapshot(): Boolean {
        // 使用者可能在填入後不到 1 秒就點 ✕：不可因防干擾旗標而忽略，快照被取走後本身就不會重入
        val snapshot = lastSnapshot ?: run {
            Log.w(TAG, "restoreLastSnapshot: lastSnapshot is null")
            return false
        }
        // 立即取走快照，避免重入
        lastSnapshot = null
        lastInjectedText = null
        lastInjectedCursorPos = -1

        isAutomatedActionInProgress = true
        try {
            if (snapshot.viaIme && undoViaIme(snapshot)) {
                Log.i(TAG, "restoreLastSnapshot: undone via accessibility IME channel")
                return true
            }
            return restoreViaSetText(snapshot)
        } finally {
            releaseAutomationGuardLater()
        }
    }

    private fun restoreViaSetText(snapshot: EditorSnapshot): Boolean {
        val target = findActiveEditableNode()
            ?: snapshot.node?.takeIf { runCatching { it.refresh() }.getOrDefault(false) || isEditableNode(it) }
        if (target == null) {
            Log.w(TAG, "restoreLastSnapshot: no editable node to restore into")
            return false
        }
        runCatching { target.refresh() }
        if (!target.isFocused) {
            runCatching { target.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, snapshot.originalText)
        }
        val restored = runCatching { target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
        Log.i(TAG, "restoreLastSnapshot: ACTION_SET_TEXT result: $restored")

        if (restored && snapshot.selectionStart >= 0 && snapshot.selectionEnd >= 0) {
            val selArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, snapshot.selectionStart)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, snapshot.selectionEnd)
            }
            runCatching { target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs) }
        }
        return restored
    }

    /**
     * Dispatches Enter / IME Action to the active editable field.
     */
    fun sendEnterKey(): Boolean {
        val target = findActiveEditableNode()
        if (target == null || target.isMultiLine) {
            // 多行欄位／自繪編輯器：送出真正的 Enter 按鍵事件，與實體鍵盤行為一致
            val ic = imeConnection()
            if (ic != null) {
                val ok = runCatching {
                    val now = android.os.SystemClock.uptimeMillis()
                    ic.sendKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER, 0))
                    ic.sendKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER, 0))
                    true
                }.getOrDefault(false)
                if (ok) return true
            }
        }
        if (target == null) return false
        // 多行文字框（例如 Google Keep 筆記區、各類多行備忘錄與自繪文件）：
        // Enter 鍵在多行編輯器中代表換行（\n），若送出 ACTION_IME_ENTER 會觸發 IME_ACTION_NEXT（跳至下一個欄位/字元）而無法換行。
        // 故在 isMultiLine 為 true 時直接注入換行字元 \n；僅單行文字框（搜尋列、網址列等）才觸發 ACTION_IME_ENTER。
        if (!target.isMultiLine) {
            val imeAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                }.getOrDefault(false)
            } else {
                false
            }
            if (imeAction) return true
        }
        return inputText("\n", createSnapshot = false)
    }
}
