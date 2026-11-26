package com.campus.arnav.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.min

/**
 * 2D 兜底视图：罗盘圆盘 + 指向目标的大箭头 + 剩余距离。
 */
class CompassRoseView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * dp
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
    }

    var headingDeg: Float = Float.NaN
    var bearingDeg: Float = 0f
    var distanceMeters: Double = 0.0
    var instruction: String = ""
    var topInsetPx: Int = 0
    var bottomInsetPx: Int = 0

    fun refresh() = invalidate()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        fill.color = Color.parseColor("#FFF7FB")
        canvas.drawRect(0f, 0f, w, h, fill)

        text.textSize = 15f * dp
        text.typeface = Typeface.DEFAULT_BOLD
        text.color = Color.parseColor("#E8447A")
        canvas.drawText("2D 模式", w / 2f, topInsetPx + 48f * dp, text)

        val cx = w / 2f
        val cy = h * 0.46f
        val radius = min(w, h) * 0.30f

        stroke.color = Color.argb(90, 232, 68, 122)
        canvas.drawCircle(cx, cy, radius, stroke)

        val heading = if (headingDeg.isNaN()) 0f else headingDeg
        val labels = listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f)
        text.textSize = 14f * dp
        text.typeface = Typeface.DEFAULT_BOLD
        for ((label, deg) in labels) {
            val a = Math.toRadians((deg - heading).toDouble())
            val x = cx + (Math.sin(a) * radius * 0.86).toFloat()
            val y = cy - (Math.cos(a) * radius * 0.86).toFloat() + 5f * dp
            text.color = if (label == "N") Color.parseColor("#E8447A") else Color.argb(220, 61, 51, 64)
            canvas.drawText(label, x, y, text)
        }

        // 指向目标的箭头
        val rel = (bearingDeg - heading + 540f) % 360f - 180f
        canvas.save()
        canvas.rotate(rel, cx, cy)
        fill.color = Color.parseColor("#E8447A")
        val p = android.graphics.Path()
        val len = radius * 0.72f
        p.moveTo(cx, cy - len)
        p.lineTo(cx + len * 0.30f, cy + len * 0.34f)
        p.lineTo(cx, cy + len * 0.14f)
        p.lineTo(cx - len * 0.30f, cy + len * 0.34f)
        p.close()
        canvas.drawPath(p, fill)
        canvas.restore()

        // 中心点
        fill.color = Color.parseColor("#E8447A")
        canvas.drawCircle(cx, cy, 5f * dp, fill)

        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = 34f * dp
        text.color = Color.parseColor("#3D3340")
        canvas.drawText(formatDistance(distanceMeters), cx, cy + radius + 66f * dp, text)

        text.typeface = Typeface.DEFAULT
        text.textSize = 14f * dp
        text.color = Color.argb(190, 61, 51, 64)
        val hint = if (rel > 8f) "偏右 " + rel.toInt() + "°"
        else if (rel < -8f) "偏左 " + (-rel).toInt() + "°"
        else "方向正确"
        canvas.drawText(hint, cx, cy + radius + 92f * dp, text)

        if (instruction.isNotEmpty()) {
            text.textSize = 15f * dp
            text.color = Color.parseColor("#E8447A")
            canvas.drawText(instruction, cx, h - bottomInsetPx - 60f * dp, text)
        }
    }

    private fun formatDistance(m: Double): String =
        if (m >= 1000) String.format(Locale.CHINA, "%.1f km", m / 1000.0)
        else String.format(Locale.CHINA, "%.0f m", m)
}
