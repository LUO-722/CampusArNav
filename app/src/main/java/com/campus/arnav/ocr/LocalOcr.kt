package com.campus.arnav.ocr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.campus.arnav.agent.NoticeExtractor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.TimeUnit

/**
 * 本机离线 OCR：ML Kit 中文文字识别（模型随 APK 打包，断网可用）。
 *
 * 只识别图片里的文字，不联网、不上传图片、不需要 API Key。
 * 识别结果交给调用方填进文字框，再由用户决定是否用 DeepSeek 抽取活动/回答。
 */
object LocalOcr {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /** 识别图片文字；失败时抛出带中文说明的 IllegalStateException。 */
    fun recognize(ctx: Context, uri: Uri): String {
        val bitmap: Bitmap = NoticeExtractor.loadBitmap(ctx, uri)
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = Tasks.await(recognizer.process(image), 25, TimeUnit.SECONDS)
            return result.text.trim()
        } catch (e: Exception) {
            throw IllegalStateException("本机识字失败：${e.message ?: "未知错误"}", e)
        } finally {
            bitmap.recycle()
        }
    }

    /** 识别并整理成按行拼接的纯文本，去掉空行。 */
    fun recognizeLines(ctx: Context, uri: Uri): String {
        return recognize(ctx, uri)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }
}
