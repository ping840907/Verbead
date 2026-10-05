package com.ping.voiceime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ping.voiceime.engine.ModelConfig
import com.ping.voiceime.engine.ModelDownloadSpec
import com.ping.voiceime.engine.ModelDownloader
import com.ping.voiceime.engine.XAsrEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class SherpaOnnxTest {

    companion object {
        private const val TAG = "SherpaOnnxTest"
    }

    private fun readWavPcm16(file: File): FloatArray {
        val bytes = file.readBytes()
        val pcmOffset = 44
        val pcmLength = bytes.size - pcmOffset
        val sampleCount = pcmLength / 2
        val floats = FloatArray(sampleCount)
        val bb = ByteBuffer.wrap(bytes, pcmOffset, pcmLength).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            floats[i] = bb.short.toFloat() / 32768.0f
        }
        return floats
    }

    @Test
    fun testSherpaOnnxJniLoad() {
        Log.i(TAG, "Testing JNI loading of sherpa-onnx 1.13.8...")
        com.k2fsa.sherpa.onnx.OfflineRecognizer.prependAdspLibraryPath("")
        com.k2fsa.sherpa.onnx.OnlineRecognizer.prependAdspLibraryPath("")
        Log.i(TAG, "sherpa-onnx 1.13.8 JNI libraries loaded successfully!")
    }

    @Test
    fun testOnnxRuntimeAndSherpaCoexistence() {
        Log.i(TAG, "Testing JNI loading of sherpa-onnx...")
        com.k2fsa.sherpa.onnx.OnlineRecognizer.prependAdspLibraryPath("")
        Log.i(TAG, "Testing OrtEnvironment initialization...")
        val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
        assertNotNull(env)
        Log.i(TAG, "OrtEnvironment and Sherpa-ONNX both loaded successfully!")
    }

    private fun ensureOcrModels(context: android.content.Context) {
        val dest = File(context.filesDir, "models/ocr")
        dest.mkdirs()
        val tmpOcr = File("/data/local/tmp/ocr")
        if (tmpOcr.exists()) {
            tmpOcr.listFiles()?.forEach { f ->
                val target = File(dest, f.name)
                if (!target.exists() || target.length() != f.length()) {
                    f.copyTo(target, overwrite = true)
                }
            }
        }
    }

    @Test
    fun testPpOcrEngineLoad() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            ensureOcrModels(context)
            if (File(ModelConfig.ocrDetPath(context, ModelConfig.OCR_MODEL_SMALL)).exists()) {
                ModelConfig.setSelectedOcrModel(context, ModelConfig.OCR_MODEL_SMALL)
            }
            val ocr = com.ping.voiceime.ocr.PpOcrEngine(context)
            val loaded = ocr.load()
            Log.i(TAG, "PpOcrEngine loaded: $loaded, isReady: ${ocr.isReady}")
            assertTrue("PpOcrEngine should load successfully", loaded)
            ocr.release()
        }
    }

    @Test
    fun testPpOcrVerticalTextRecognition() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            ensureOcrModels(context)
            if (File(ModelConfig.ocrDetPath(context, ModelConfig.OCR_MODEL_SMALL)).exists()) {
                ModelConfig.setSelectedOcrModel(context, ModelConfig.OCR_MODEL_SMALL)
            }
            val ocr = com.ping.voiceime.ocr.PpOcrEngine(context)
            val loaded = ocr.load()
            assertTrue("PpOcrEngine should load successfully", loaded)

            suspend fun testOneVertical(text: String, gap: Float = 0f, bgColor: Int = Color.WHITE, textColor: Int = Color.BLACK) {
                val fontSize = 44f
                val charCount = text.length
                val w = 72
                val h = (20f + charCount * fontSize + (charCount - 1) * gap + 20f).toInt()
                val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val cv = Canvas(bm)
                cv.drawColor(bgColor)

                val pt = Paint().apply {
                    color = textColor
                    textSize = fontSize
                    isAntiAlias = true
                    typeface = Typeface.DEFAULT_BOLD
                    textAlign = Paint.Align.CENTER
                }

                for (i in text.indices) {
                    val y = 20f + i * (fontSize + gap) + fontSize * 0.75f
                    cv.drawText(text[i].toString(), w / 2f, y, pt)
                }

                val boxes = ocr.detectText(bm)
                Log.i(TAG, "--- Testing '$text' (gap=$gap, bg=${String.format("#%06X", 0xFFFFFF and bgColor)}) ---")
                Log.i(TAG, "Detected ${boxes.size} boxes for '$text'")
                for ((idx, box) in boxes.withIndex()) {
                    val rec = ocr.recognizeBox(bm, box)
                    Log.i(TAG, "  Box $idx rect=$box -> '$rec'")
                    assertEquals("Box $idx recognition should match", text, rec)
                }
                val direct = ocr.recognizeText(bm)
                Log.i(TAG, "  Direct rec -> '$direct'")
                assertEquals("Direct recognition should match", text, direct)
            }

            testOneVertical("牛肉麵")
            testOneVertical("台北信義區")
            testOneVertical("天仁茗茶", gap = 20f)
            testOneVertical("風調雨順國泰民安")
            testOneVertical("大吉大利", bgColor = Color.rgb(245, 235, 215)) // aged paper / cream
            testOneVertical("珍珠奶茶半糖去冰")

            suspend fun testOneHorizontal(text: String) {
                val fontSize = 44f
                val paint = Paint().apply {
                    color = Color.BLACK
                    textSize = fontSize
                    isAntiAlias = true
                    typeface = Typeface.DEFAULT_BOLD
                }
                val textW = paint.measureText(text)
                val w = (textW + 40).toInt()
                val h = (fontSize + 30).toInt()
                val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val cv = Canvas(bm)
                cv.drawColor(Color.WHITE)
                cv.drawText(text, 20f, 15f + fontSize * 0.75f, paint)

                val boxes = ocr.detectText(bm)
                Log.i(TAG, "--- Horizontal test for '$text' ---")
                for ((idx, box) in boxes.withIndex()) {
                    val rec = ocr.recognizeBox(bm, box)
                    Log.i(TAG, "  H Box $idx rect=$box -> '$rec'")
                    assertEquals("H Box $idx recognition should match", text, rec)
                }
                val direct = ocr.recognizeText(bm)
                Log.i(TAG, "  H Direct rec -> '$direct'")
                assertEquals("H Direct recognition should match", text, direct)
            }

            testOneHorizontal("繁體中文語音輸入法")
            testOneHorizontal("Hello World 123")

            ocr.release()
        }
    }

    @Test
    fun testXAsrEngineWithRealAudio() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val xAsr = XAsrEngine(context)

            val encoder = File(ModelConfig.xAsrEncoderPath(context))
            if (!encoder.exists()) {
                Log.i(TAG, "Downloading X-ASR model...")
                val downloader = ModelDownloader(context)
                val res = downloader.download(ModelDownloadSpec.xAsr()) { p ->
                    Log.i(TAG, "Download progress: ${p.label} ${p.percent}%")
                }
                assertTrue("Model download should succeed: ${res.exceptionOrNull()?.message}", res.isSuccess)
            }

            Log.i(TAG, "Testing XAsrEngine with sherpa-onnx 1.13.8...")
            val loadResult = xAsr.load()
            Log.i(TAG, "XAsr load result: success=${loadResult.success}, provider=${loadResult.provider}, error=${loadResult.error}")
            assertTrue("XAsrEngine should load successfully: ${loadResult.error}", loadResult.success)

            val stream = xAsr.createStream()
            assertNotNull("Stream should not be null", stream)

            var wavFile = File(context.filesDir, "test.wav")
            if (!wavFile.exists()) {
                val extWav = File(context.getExternalFilesDir(null), "test.wav")
                if (extWav.exists()) {
                    extWav.copyTo(wavFile, overwrite = true)
                } else {
                    val candidateWav = File(ModelConfig.modelsDir(context), "qwen3_asr/test_wavs/qiqiu1.wav")
                    if (candidateWav.exists()) {
                        candidateWav.copyTo(wavFile, overwrite = true)
                    }
                }
            }
            assertTrue("test.wav should exist", wavFile.exists())

            val samples = readWavPcm16(wavFile)
            Log.i(TAG, "Feeding ${samples.size} samples (${samples.size / 16000f}s) to XAsr...")

            // Feed audio in 0.1s chunks (1600 samples)
            val chunkSize = 1600
            var offset = 0
            while (offset < samples.size) {
                val end = minOf(offset + chunkSize, samples.size)
                val chunk = samples.copyOfRange(offset, end)
                xAsr.acceptWaveform(stream!!, chunk)
                while (xAsr.isReady(stream)) {
                    xAsr.decode(stream)
                }
                offset = end
            }
            xAsr.inputFinished(stream!!)
            while (xAsr.isReady(stream)) {
                xAsr.decode(stream)
            }

            val text = xAsr.getResult(stream)
            Log.i(TAG, ">>> XAsr recognized text: '$text' <<<")
            assertTrue("XAsr should recognize text from test.wav", text.isNotBlank())

            xAsr.release()
            Log.i(TAG, "XAsrEngine real audio test completed successfully!")
        }
    }
}
