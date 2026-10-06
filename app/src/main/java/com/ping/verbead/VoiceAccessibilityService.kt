package com.ping.verbead

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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

        /**
         * Callback fired when soft keyboard status or bounds change.
         */
        var onKeyboardStateChanged: ((KeyboardInfo) -> Unit)? = null

        /**
         * Callback fired when entering or exiting text input state.
         * true = editable field is focused; false = no editable field focused.
         */
        var onInputFocusStateChanged: ((Boolean) -> Unit)? = null

        /**
         * Callbacks for X-button (Undo) auto-invalidation
         */
        var onManualTypingDetected: (() -> Unit)? = null
        var onCursorMoved: (() -> Unit)? = null
        var onInputFocusLost: (() -> Unit)? = null
    }

    private var lastFocusedNode: AccessibilityNodeInfo? = null
    private var currentInputState: Boolean? = null
    private var currentKeyboardInfo: KeyboardInfo = KeyboardInfo(false, 0, 0)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isAutomatedActionInProgress = false
    var lastSnapshot: EditorSnapshot? = null
        private set

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
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        mainHandler.removeCallbacks(keyboardCheckRunnable)
        onKeyboardStateChanged = null
        onInputFocusStateChanged = null
        onManualTypingDetected = null
        onCursorMoved = null
        onInputFocusLost = null
        lastSnapshot = null
        Log.i(TAG, "VoiceAccessibilityService disconnected")
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        Log.w(TAG, "VoiceAccessibilityService interrupted")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

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
                if (!isAutomatedActionInProgress) {
                    onManualTypingDetected?.invoke()
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                if (!isAutomatedActionInProgress) {
                    onCursorMoved?.invoke()
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
        onKeyboardStateChanged?.invoke(info)
    }

    private fun notifyInputState(hasInputFocus: Boolean) {
        if (currentInputState == hasInputFocus) return
        currentInputState = hasInputFocus
        Log.i(TAG, "notifyInputState: hasInputFocus=$hasInputFocus")
        _inputFocusStateFlow.tryEmit(hasInputFocus)
        onInputFocusStateChanged?.invoke(hasInputFocus)
        if (!hasInputFocus) {
            onInputFocusLost?.invoke()
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

    /**
     * Injects [text] into the currently focused editable node in the active foreground window.
     * Uses ACTION_SET_TEXT combined with cursor calculation to insert text at current cursor without
     * writing to the system clipboard, preserving user privacy.
     * Skips password fields and only falls back to clipboard (with EXTRA_IS_SENSITIVE) if ACTION_SET_TEXT fails.
     */
    fun inputText(text: String): Boolean {
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
                Toast.makeText(this, "密碼欄位不支援自動填入", Toast.LENGTH_SHORT).show()
            }
            return false
        }

        Log.i(TAG, "inputText: target=${target.className}, isEditable=${target.isEditable}, isFocused=${target.isFocused}")

        // Ensure focused
        if (!target.isFocused) {
            runCatching { target.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        }

        isAutomatedActionInProgress = true
        try {
            // 判斷是否顯示 HintText，避免把提示文字誤當成欄位原文
            val isHint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                target.isShowingHintText
            } else {
                false
            }
            val originalText = if (isHint) "" else (target.text?.toString() ?: "")
            val textLen = originalText.length

            var selStart = target.textSelectionStart
            var selEnd = target.textSelectionEnd
            if (isHint || selStart < 0 || selEnd < 0) {
                selStart = textLen
                selEnd = textLen
            } else {
                selStart = selStart.coerceIn(0, textLen)
                selEnd = selEnd.coerceIn(0, textLen)
                if (selStart > selEnd) {
                    val tmp = selStart
                    selStart = selEnd
                    selEnd = tmp
                }
            }

            // §6.1 快照捕捉時機點：在執行輸入動作前捕捉
            lastSnapshot = EditorSnapshot(
                node = target,
                originalText = originalText,
                selectionStart = selStart,
                selectionEnd = selEnd
            )

            // 依游標位置組合文字: 原文[0..selStart] + 新文字 + 原文[selEnd..]
            val combined = originalText.substring(0, selStart) + text + originalText.substring(selEnd)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined)
            }
            val setSuccess = runCatching {
                target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }.getOrDefault(false)

            Log.i(TAG, "ACTION_SET_TEXT result: $setSuccess")

            if (setSuccess) {
                // 送出 ACTION_SET_TEXT 後，以 ACTION_SET_SELECTION 將游標移到 selStart + 新文字長度
                val newCursor = selStart + text.length
                val selArgs = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursor)
                }
                runCatching { target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs) }
                return true
            }

            // SET_TEXT 失敗（部分 WebView 或自繪編輯器）時才退回剪貼簿，並加上 EXTRA_IS_SENSITIVE 標記與使用者提示
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
                    Toast.makeText(this, "無法直接填入，已安全複製至剪貼簿", Toast.LENGTH_SHORT).show()
                }
            }
            return pasted
        } finally {
            mainHandler.postDelayed({ isAutomatedActionInProgress = false }, 250)
        }
    }

    /**
     * §6.1 Undo the last paste using the captured snapshot.
     */
    fun restoreLastSnapshot(): Boolean {
        val snapshot = lastSnapshot ?: return false
        val target = findActiveEditableNode() ?: snapshot.node ?: return false
        isAutomatedActionInProgress = true
        try {
            if (!target.isFocused) {
                runCatching { target.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
            }
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, snapshot.originalText)
            }
            val restored = runCatching { target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
            if (snapshot.selectionStart >= 0 && snapshot.selectionEnd >= 0) {
                val selArgs = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, snapshot.selectionStart)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, snapshot.selectionEnd)
                }
                runCatching { target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs) }
            }
            lastSnapshot = null
            return restored
        } finally {
            mainHandler.postDelayed({ isAutomatedActionInProgress = false }, 250)
        }
    }

    /**
     * Dispatches Enter / IME Action to the active editable field.
     */
    fun sendEnterKey(): Boolean {
        val target = findActiveEditableNode() ?: return false
        val imeAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }.getOrDefault(false)
        } else {
            false
        }
        if (imeAction) return true
        return inputText("\n")
    }
}