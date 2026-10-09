package com.ping.verbead

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
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

        private val _inputFocusStateFlow = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val inputFocusStateFlow: SharedFlow<Boolean> = _inputFocusStateFlow.asSharedFlow()

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

    fun hasValidSnapshot(): Boolean {
        val snap = lastSnapshot ?: return false
        return (System.currentTimeMillis() - snap.capturedAtMs) < 15_000
    }

    fun isInputFocused(): Boolean = currentInputState == true
    fun getKeyboardInfo(): KeyboardInfo = currentKeyboardInfo

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(testReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(testReceiver, filter)
            }
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
        _inputFocusStateFlow.tryEmit(hasInputFocus)
        if (!hasInputFocus) {
            _inputFocusLostFlow.tryEmit(Unit)
        }
    }

    fun checkActiveWindowInputState() {
        val target = findActiveEditableNode()
        if (target != null) {
            lastFocusedNode = target
            notifyInputState(true)
        } else {
            notifyInputState(false)
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
     * Finds the currently active editable text node across foreground applications,
     * skipping soft keyboard (TYPE_INPUT_METHOD) and overlay windows.
     */
    fun findActiveEditableNode(): AccessibilityNodeInfo? {
        // 1. Global input focus check across all windows
        runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()?.let { globalFocus ->
            if (isEditableNode(globalFocus)) {
                Log.d(TAG, "findActiveEditableNode: matched via service.findFocus(FOCUS_INPUT)")
                return globalFocus
            }
        }

        val windowList = runCatching { windows }.getOrNull() ?: emptyList()

        // 2. Prioritize TYPE_APPLICATION windows (the active app underneath the keyboard/bubble)
        val appWindows = windowList.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        for (window in appWindows) {
            val root = window.root ?: continue
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { focused ->
                if (isEditableNode(focused)) {
                    Log.d(TAG, "findActiveEditableNode: matched via appWindow.findFocus(FOCUS_INPUT)")
                    return focused
                }
            }
            searchFocusedEditable(root)?.let { focusedEditable ->
                Log.d(TAG, "findActiveEditableNode: matched via appWindow searchFocusedEditable")
                return focusedEditable
            }
            searchAnyEditable(root)?.let { anyEditable ->
                Log.d(TAG, "findActiveEditableNode: matched via appWindow searchAnyEditable")
                return anyEditable
            }
        }

        // 3. Search rootInActiveWindow
        rootInActiveWindow?.let { root ->
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { focused ->
                if (isEditableNode(focused)) {
                    Log.d(TAG, "findActiveEditableNode: matched via rootInActiveWindow.findFocus(FOCUS_INPUT)")
                    return focused
                }
            }
            searchFocusedEditable(root)?.let { focusedEditable ->
                Log.d(TAG, "findActiveEditableNode: matched via rootInActiveWindow searchFocusedEditable")
                return focusedEditable
            }
            searchAnyEditable(root)?.let { anyEditable ->
                Log.d(TAG, "findActiveEditableNode: matched via rootInActiveWindow searchAnyEditable")
                return anyEditable
            }
        }

        // 4. Search any remaining windows (explicitly skipping TYPE_INPUT_METHOD)
        for (window in windowList) {
            if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val root = window.root ?: continue
            searchFocusedEditable(root)?.let { return it }
            searchAnyEditable(root)?.let { return it }
        }

        // 5. Fallback to cached lastFocusedNode
        lastFocusedNode?.let { last ->
            val refreshed = runCatching { last.refresh() }.getOrDefault(false)
            if (refreshed && isEditableNode(last)) {
                Log.d(TAG, "findActiveEditableNode: matched via refreshed lastFocusedNode")
                return last
            }
            if (isEditableNode(last)) {
                Log.d(TAG, "findActiveEditableNode: matched via lastFocusedNode fallback")
                return last
            }
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

    private fun searchAnyEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (isEditableNode(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchAnyEditable(child)
            if (found != null) return found
        }
        return null
    }

    private fun isUndoButton(node: AccessibilityNodeInfo): Boolean {
        if (!node.isEnabled) return false

        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val text = node.text?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        if (desc.isEmpty() && text.isEmpty() && viewId.isEmpty()) return false

        // Redo keywords to explicitly exclude
        val redoKeywords = listOf("redo", "重做", "取消復原", "取消撤销", "取消撤銷")
        if (redoKeywords.any { desc.contains(it, ignoreCase = true) || text.contains(it, ignoreCase = true) || viewId.contains(it) }) {
            return false
        }

        // Undo keywords in various languages
        val undoKeywords = listOf("復原", "撤銷", "撤销", "元に戻す", "실행취소", "실행 취소", "deshacer")
        if (undoKeywords.any { desc.contains(it, ignoreCase = true) || text.contains(it, ignoreCase = true) }) {
            return true
        }

        if (desc.equals("undo", ignoreCase = true) ||
            desc.startsWith("undo ", ignoreCase = true) ||
            desc.endsWith(" undo", ignoreCase = true) ||
            text.equals("undo", ignoreCase = true) ||
            viewId.contains("undo")
        ) {
            return true
        }

        return false
    }

    private fun searchUndoButton(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (isUndoButton(node)) {
            if (node.isClickable) return node
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) return parent
                parent = parent.parent
            }
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchUndoButton(child)
            if (found != null) return found
        }
        return null
    }

    private fun findNativeUndoButton(target: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        // 1. Check parent tree of target node first
        var targetRoot: AccessibilityNodeInfo? = target
        while (targetRoot?.parent != null) {
            targetRoot = targetRoot.parent
        }
        targetRoot?.let { root ->
            val found = searchUndoButton(root)
            if (found != null) return found
        }

        // 2. Check rootInActiveWindow
        rootInActiveWindow?.let { root ->
            val found = searchUndoButton(root)
            if (found != null) return found
        }

        // 3. Search other windows
        val windowList = runCatching { windows }.getOrNull() ?: emptyList()
        for (window in windowList) {
            if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val root = window.root ?: continue
            val found = searchUndoButton(root)
            if (found != null) return found
        }

        return null
    }

    /**
     * 互動式判斷 target 節點當前文字是否為佔位文字（Placeholder / Hint）。
     *
     * 判定邏輯：
     * 1. 游標判定：若游標位置大於 0（rawSelStart > 0 || rawSelEnd > 0），空欄位在 Android 中游標必為 0 或 -1，
     *    因此有正數游標代表使用者已實際輸入內容，直接判定為非佔位符。
     * 2. 官方屬性判定：檢查 Android 8.0+ isShowingHintText 與 hintText 特徵。
     * 3. 欄位選取能力探測（Selection Capability Probe - 解決手打文字與佔位符同字的極端邊界條件）：
     *    - 僅在節點支援 ACTION_SET_SELECTION 時執行，避免自繪引擎（如 Google Docs）因不支援選取而被誤判為佔位符。
     * 4. 備援機制：若選取探測未執行或發生例外，退回 TextInsertion.isHintText 作為備援保護。
     */
    private fun isNodeTextPlaceholder(
        target: AccessibilityNodeInfo,
        initialText: String,
        rawSelStart: Int,
        rawSelEnd: Int
    ): Boolean {
        if (initialText.isEmpty()) return false

        // 1. 若游標位置大於 0，空欄位不可有正數游標，必為真實輸入內容
        if (rawSelStart > 0 || rawSelEnd > 0) {
            return false
        }

        // 2. 官方屬性快速判定：系統明確標記正在顯示提示文字
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (target.isShowingHintText) return true
        }

        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) target.hintText else null
        val hintNorm = TextInsertion.normalizeHint(hint)
        val origNorm = TextInsertion.normalizeHint(initialText)

        // 3. 防禦性過濾：若節點有定義提示文字（如 Google Keep 裡的「記事」），且目前文字（如「ABC」）與提示文字完全不相符，
        // 代表該欄位已包含使用者輸入的內容，絕非佔位符。立即返回 false，避免執行選取探測干擾游標與 Spannable 狀態。
        if (hintNorm.isNotEmpty() && !origNorm.equals(hintNorm, ignoreCase = true)) {
            return false
        }

        // 4. 欄位選取能力探測（Selection Capability Probe - 解決手打文字與佔位符同字的極端邊界條件）
        val len = initialText.length
        Log.d(TAG, "isNodeTextPlaceholder: pkg=${target.packageName}, cls=${target.className}, text='$initialText', rawSelStart=$rawSelStart, rawSelEnd=$rawSelEnd, hint='$hint', isShowingHint=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) target.isShowingHintText else false}, actions=${target.actionList.map { it.id }}")
        val supportsSelection = target.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_SELECTION }
        if (supportsSelection) {
            try {
                val selArgs = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
                }
                val canSelect = target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
                val isPlaceholder = TextInsertion.evaluateSelectionProbe(len, canSelect)
                Log.d(TAG, "Selection capability probe: initial='$initialText', len=$len, canSelect=$canSelect, isPlaceholder=$isPlaceholder")

                if (!isPlaceholder && rawSelStart >= 0) {
                    // 若判定為真實手打文字，將游標還原為原先位置
                    val restoreArgs = Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, rawSelStart)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, rawSelEnd)
                    }
                    target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, restoreArgs)
                }
                return isPlaceholder
            } catch (e: Exception) {
                Log.w(TAG, "Selection probe exception: ${e.message}")
            }
        } else {
            // 節點不包含 ACTION_SET_SELECTION。
            // 在 Telegram 等通訊軟體中，當輸入框為空時，底層文字緩衝區長度為 0，TextView 不會將 ACTION_SET_SELECTION 加入 actions。
            // 但若使用者手動輸入了與佔位文字相同的文字（如手打「輸入訊息」），文字緩衝區長度 > 0，ACTION_SET_SELECTION 就會存在。
            // 因此當 supportsSelection 為 false 且游標 <= 0 時：
            // 若為 Telegram，或支援 ACTION_SET_TEXT 且游標未初始化（rawSelStart < 0），判定為動態佔位文字！
            val isTelegram = target.packageName?.contains("telegram", ignoreCase = true) == true
            val supportsSetText = target.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
            if (isTelegram && supportsSetText && rawSelStart <= 0 && rawSelEnd <= 0) {
                Log.d(TAG, "isNodeTextPlaceholder: Telegram empty input detected without ACTION_SET_SELECTION ('$initialText')")
                return true
            }
            if (rawSelStart < 0 && rawSelEnd < 0 && supportsSetText) {
                Log.d(TAG, "isNodeTextPlaceholder: uninitialized cursor node without ACTION_SET_SELECTION detected as placeholder ('$initialText')")
                return true
            }
            Log.d(TAG, "Node does not support ACTION_SET_SELECTION, skipping selection probe")
        }

        // 5. 備援機制
        return TextInsertion.isHintText(
            originalText = initialText,
            hintText = hint,
            isShowingHintText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) target.isShowingHintText else false,
            contentDescription = target.contentDescription
        )
    }

    /**
     * Injects [text] into the currently focused editable node in the active foreground window.
     * Uses ACTION_SET_TEXT combined with cursor calculation to insert text at current cursor without
     * writing to the system clipboard, preserving user privacy.
     * Skips password fields and only falls back to clipboard (with EXTRA_IS_SENSITIVE) if ACTION_SET_TEXT fails.
     */
    fun inputText(text: String, createSnapshot: Boolean = true): Boolean {
        if (text.isEmpty()) return false

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
                // 完全 bypass 所有為了解決 SET_TEXT 而設置的迂迴解方：
                // 不執行 isNodeTextPlaceholder 佔位符探測與選取干擾，
                // 不執行強制全選，不執行字串拼接與游標計算，
                // 直接透過剪貼簿以原生效能與原生游標狀態貼上！
                if (createSnapshot) {
                    val initialText = target.text?.toString() ?: ""
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

            // 採用互動判斷邏輯（Interactive Probing）比對輸入反應，動態決定是否移除佔位文字
            val isHint = isNodeTextPlaceholder(target, initialText, rawSelStart, rawSelEnd)
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

            // SET_TEXT 失敗（部分 WebView 或自繪編輯器如 Google Docs）時才退回剪貼簿，並加上 EXTRA_IS_SENSITIVE 標記與使用者提示
            Log.w(TAG, "ACTION_SET_TEXT failed, falling back to clipboard paste with EXTRA_IS_SENSITIVE")
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
            Log.i(TAG, "Fallback ACTION_PASTE result: $pasted")

            if (!pasted) {
                mainHandler.post {
                    Toast.makeText(this, R.string.toast_fallback_clipboard, Toast.LENGTH_SHORT).show()
                }
            }
            return pasted
        } finally {
            mainHandler.postDelayed({ isAutomatedActionInProgress = false }, 800)
        }
    }

    /**
     * §6.1 Undo the last paste using the captured snapshot.
     */
    fun restoreLastSnapshot(): Boolean {
        val snapshot = lastSnapshot ?: run {
            Log.w(TAG, "restoreLastSnapshot: lastSnapshot is null")
            return false
        }
        Log.i(TAG, "restoreLastSnapshot called: originalText='${snapshot.originalText}', sel=${snapshot.selectionStart}..${snapshot.selectionEnd}")

        var target = findActiveEditableNode()
        if (target == null) {
            val cachedNode = snapshot.node
            if (cachedNode != null) {
                val refreshed = runCatching { cachedNode.refresh() }.getOrDefault(false)
                if (refreshed && isEditableNode(cachedNode)) {
                    target = cachedNode
                } else if (isEditableNode(cachedNode)) {
                    target = cachedNode
                }
            }
        }
        if (target == null) {
            Log.w(TAG, "restoreLastSnapshot: failed to find active or cached editable node, attempting native undo fallback")
            val nativeUndoBtn = findNativeUndoButton(null)
            if (nativeUndoBtn != null) {
                val restored = runCatching {
                    nativeUndoBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }.getOrDefault(false)
                Log.i(TAG, "restoreLastSnapshot: native undo button clicked without active target: result=$restored")
                if (restored) {
                    lastSnapshot = null
                    lastInjectedText = null
                    lastInjectedCursorPos = -1
                    return true
                }
            }
            return false
        }

        isAutomatedActionInProgress = true
        try {
            runCatching { target.refresh() }
            if (!target.isFocused) {
                runCatching { target.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
                runCatching { target.refresh() }
            }

            var restored = false
            var wasNativeUndo = false

            // 1. 若目標應用程式有原生 Undo 按鈕（如 Google Docs、Word 等自繪/辦公室編輯器）：
            // 原生 Undo 按鈕是最乾淨的復原方式，由應用程式自身的 Edit Stack 完整管理文字與游標位置。
            val nativeUndoBtn = findNativeUndoButton(target)
            if (nativeUndoBtn != null) {
                restored = runCatching {
                    nativeUndoBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }.getOrDefault(false)
                if (restored) {
                    wasNativeUndo = true
                    Log.i(TAG, "restoreLastSnapshot: native undo button clicked successfully: desc='${nativeUndoBtn.contentDescription}'")
                }
            }

            val isPasteMode = ModelConfig.isPasteModeEnabled(this)

            if (!restored) {
                if (isPasteMode) {
                    // ── 剪貼簿模式下的還原 ──
                    // 若沒有原生 Undo 按鈕，且節點支援 ACTION_SET_TEXT，還原原始文字
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, snapshot.originalText)
                    }
                    restored = runCatching { target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
                    if (!restored) {
                        // 剪貼簿備援：若支援選取，全選後貼上原文字或剪下清空
                        val supportsSelection = target.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_SELECTION }
                        if (supportsSelection) {
                            val currentLen = target.text?.length ?: 10000
                            val selAll = Bundle().apply {
                                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, currentLen)
                            }
                            val canSelect = runCatching { target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selAll) }.getOrDefault(false)
                            if (canSelect) {
                                if (snapshot.originalText.isEmpty()) {
                                    restored = runCatching { target.performAction(AccessibilityNodeInfo.ACTION_CUT) }.getOrDefault(false)
                                } else {
                                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("restore", snapshot.originalText).apply {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            description.extras = PersistableBundle().apply {
                                                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                                            }
                                        }
                                    }
                                    clipboard.setPrimaryClip(clip)
                                    restored = runCatching { target.performAction(AccessibilityNodeInfo.ACTION_PASTE) }.getOrDefault(false)
                                }
                            }
                        }
                    }
                } else {
                    // ── SET_TEXT 模式下的還原 ──
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, snapshot.originalText)
                    }
                    restored = runCatching { target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
                    Log.i(TAG, "restoreLastSnapshot: ACTION_SET_TEXT result: $restored")

                    if (!restored) {
                        val supportsSelection = target.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_SELECTION }
                        if (supportsSelection) {
                            val currentLen = target.text?.length ?: 10000
                            val selAll = Bundle().apply {
                                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, currentLen)
                            }
                            val canSelect = runCatching { target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selAll) }.getOrDefault(false)
                            if (canSelect) {
                                if (snapshot.originalText.isEmpty()) {
                                    restored = runCatching { target.performAction(AccessibilityNodeInfo.ACTION_CUT) }.getOrDefault(false)
                                } else {
                                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("restore", snapshot.originalText).apply {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            description.extras = PersistableBundle().apply {
                                                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                                            }
                                        }
                                    }
                                    clipboard.setPrimaryClip(clip)
                                    restored = runCatching { target.performAction(AccessibilityNodeInfo.ACTION_PASTE) }.getOrDefault(false)
                                }
                            }
                        }
                    }
                }
            }

            // 關鍵修復：只有在「非原生 Undo」且原始游標有效時，才還原游標！
            // 若為原生 Undo（如 Google Docs），應用程式內部已經維護好正確的游標位置；
            // 若在此時呼叫 ACTION_SET_SELECTION，會因為 Docs 回報 selectionStart=0 而將游標強行重設至文件開頭 (0)！
            if (restored && !wasNativeUndo && snapshot.selectionStart >= 0 && snapshot.selectionEnd >= 0) {
                val selArgs = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, snapshot.selectionStart)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, snapshot.selectionEnd)
                }
                runCatching { target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs) }
            }
            lastSnapshot = null
            lastInjectedText = null
            lastInjectedCursorPos = -1
            return restored
        } finally {
            mainHandler.postDelayed({ isAutomatedActionInProgress = false }, 800)
        }
    }

    /**
     * Dispatches Enter / IME Action to the active editable field.
     */
    fun sendEnterKey(): Boolean {
        val target = findActiveEditableNode() ?: return false
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