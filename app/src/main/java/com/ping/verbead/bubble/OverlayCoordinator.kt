package com.ping.verbead.bubble

/**
 * 條碼掃描、文字辨識、常用語抽屜、朗讀播放器這四種浮動視窗的協調者：
 * 一次只開一個（開啟其中一個前會先關掉其他的），並負責依錨點泡泡的位置擺放／重新定位。
 * 從 FloatingBubbleService 拆出，原本每個模式各自重複的「算錨點、關其他視窗」邏輯集中在這裡。
 */
class OverlayCoordinator(
    private val scanner: ScannerOverlayController,
    private val ocr: OcrOverlayController,
    private val phrases: PhrasesDrawerController,
    private val tts: TtsPlayerController,
    private val host: Host
) {
    /** 泡泡端提供的錨點位置與畫面資訊（每次呼叫時即時取值） */
    interface Host {
        val anchorY: Int
        val isDockedOnRight: Boolean
        val screenWidth: Int
        val safeBottom: Int
        fun hidePreview()
        fun untuckAnchor()
        /** 條碼掃描視窗關閉後：泡泡回到閒置狀態（除非正顯示可還原的 ✕） */
        fun onScannerStopped()
    }

    private enum class Kind { SCANNER, OCR, PHRASES, TTS }

    val isScannerActive: Boolean get() = scanner.isActive
    val isOcrActive: Boolean get() = ocr.isModeActive
    val isPhrasesActive: Boolean get() = phrases.isActive
    val isTtsActive: Boolean get() = tts.isActive

    // ── 開啟（一次只開一個）──────────────────────────────────────────────

    fun startScanner() {
        if (scanner.isActive) return
        prepare(Kind.SCANNER)
        scanner.start(host.anchorY, host.isDockedOnRight, host.screenWidth, host.safeBottom)
    }

    fun startOcr() {
        if (ocr.isModeActive) return
        prepare(Kind.OCR)
        ocr.start(host.anchorY, host.isDockedOnRight, host.screenWidth, host.safeBottom)
    }

    fun openPhrases() {
        if (phrases.isActive) return
        prepare(Kind.PHRASES)
        phrases.open(host.anchorY, host.isDockedOnRight, host.screenWidth, host.safeBottom)
    }

    fun openTts() {
        if (tts.isActive) return
        prepare(Kind.TTS)
        tts.open(host.anchorY, host.isDockedOnRight, host.screenWidth, host.safeBottom)
    }

    fun toggleScanner() = if (scanner.isActive) stopScanner() else startScanner()
    fun toggleOcr() = if (ocr.isModeActive) stopOcr() else startOcr()
    fun togglePhrases() = if (phrases.isActive) closePhrases() else openPhrases()
    fun toggleTts() = if (tts.isActive) closeTts() else openTts()

    // ── 關閉 ────────────────────────────────────────────────────────────

    fun stopScanner(hideX: Boolean = true) {
        if (!scanner.isActive) return
        scanner.stop(hideX = hideX, isDockedOnRight = host.isDockedOnRight)
        host.onScannerStopped()
    }

    fun stopOcr(hideX: Boolean = true) {
        if (!ocr.isModeActive) return
        ocr.stop(hideX = hideX, isDockedOnRight = host.isDockedOnRight)
    }

    fun closePhrases(hideX: Boolean = true) {
        if (!phrases.isActive) return
        phrases.close(hideX = hideX, anchorY = host.anchorY, isDockedOnRight = host.isDockedOnRight)
    }

    fun closeTts(hideX: Boolean = true) {
        if (!tts.isActive) return
        tts.close(hideX = hideX, anchorY = host.anchorY, isDockedOnRight = host.isDockedOnRight)
    }

    fun closeAll() {
        stopScanner()
        stopOcr()
        closePhrases()
        closeTts()
    }

    // ── 縮放與重新定位 ───────────────────────────────────────────────────

    fun setScannerEnlarged(enlarged: Boolean) {
        scanner.setEnlarged(enlarged, host.anchorY, host.isDockedOnRight, host.screenWidth, host.safeBottom)
    }

    fun setOcrEnlarged(enlarged: Boolean) {
        ocr.setEnlarged(enlarged, host.anchorY, host.isDockedOnRight, host.screenWidth, host.safeBottom)
    }

    /** 錨點泡泡移動後，讓開啟中的視窗跟著重新定位 */
    fun reposition() {
        if (ocr.isModeActive) ocr.updatePosition(host.anchorY, host.isDockedOnRight, host.screenWidth, host.safeBottom)
        if (scanner.isActive) scanner.updatePosition(host.anchorY, host.isDockedOnRight, host.screenWidth, host.safeBottom)
        if (phrases.isActive) phrases.updatePosition(host.anchorY, host.isDockedOnRight, host.screenWidth, host.safeBottom)
        if (tts.isActive) tts.updatePosition(host.anchorY, host.isDockedOnRight, host.screenWidth, host.safeBottom)
    }

    private fun prepare(opening: Kind) {
        host.hidePreview()
        host.untuckAnchor()
        if (opening != Kind.SCANNER) stopScanner()
        if (opening != Kind.OCR) stopOcr()
        if (opening != Kind.PHRASES) closePhrases()
        if (opening != Kind.TTS) closeTts()
    }
}
