package com.campus.arnav.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.campus.arnav.core.GeoUtils
import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * AR 叠加层：世界锁定箭头 + 罗盘带 + 距离 + 转向卡。
 * 不依赖任何 3D 引擎，就是一块透明 Canvas。
 */
class ArrowOverlayView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private val dp = resources.displayMetrics.density

    var destinationName: String = ""
    var bearingDeg: Double = 0.0
    var headingDeg: Float = Float.NaN
    var distanceMeters: Double = 0.0
    var instruction: String = ""
    var arrival: Boolean = false
    var weakSignal: Boolean = false
    var horizontalFovDeg: Double = 62.0

    /** 设备→世界(东-北-天) 旋转矩阵，行主序。有它就能把箭头"贴到地上"做 3D 投影。 */
    var poseMatrix: FloatArray? = null

    /** 手机离地高度（米），3D 箭头铺在地面上。 */
    var eyeHeightM: Float = 1.35f

    /** 状态栏高度（px）。targetSdk 36 强制 edge-to-edge，顶部罗盘带要自己让位。 */
    var topInsetPx: Int = 0

    /** 最近一帧的投影结果，供日志核对（rel = bearing − heading）。 */
    var lastRelDeg: Double = Double.NaN
    var lastArrowX: Float = Float.NaN

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * dp
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val arrowPath = Path()

    fun refresh() = invalidate()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        drawCompassTape(canvas, w)
        if (arrival) {
            drawArrival(canvas, w, h)
            return
        }
        // 优先画"贴地"的 3D 透视箭头；投影不出来（比如目标在身后）再退回平面箭头
        val drew3d = drawGroundArrows(canvas, w, h)
        if (!drew3d) drawArrow(canvas, w, h)
        drawDistance(canvas, w, h)
        drawTurnCard(canvas, w, h)
        if (weakSignal) drawWeakBanner(canvas, w)
    }

    private fun drawCompassTape(canvas: Canvas, w: Float) {
        if (headingDeg.isNaN()) return
        val top = topInsetPx.toFloat()
        val baseY = top + 34f * dp
        val pxPerDeg = 3.4f * dp
        val span = (w / 2f) / pxPerDeg
        fill.color = Color.argb(90, 0, 0, 0)
        canvas.drawRect(0f, top, w, top + 70f * dp, fill)

        // 起点必须对齐到 5° 的整数倍，否则 d 永远落不到 15/90 的倍数上 —— 一格刻度都画不出来
        var d = (Math.ceil((headingDeg - span) / 5.0) * 5).toInt()
        val endD = (headingDeg + span).toInt()
        while (d <= endD) {
            val major = ((d % 90) + 90) % 90 == 0
            val minor = ((d % 15) + 15) % 15 == 0
            if (major || minor) {
                val x = w / 2f + (d - headingDeg) * pxPerDeg
                stroke.color = if (major) Color.argb(220, 255, 255, 255) else Color.argb(110, 255, 255, 255)
                canvas.drawLine(x, baseY, x, baseY + (if (major) 14f else 8f) * dp, stroke)
                if (major) {
                    text.textSize = 12f * dp
                    text.color = if (isCardinal(d)) Color.parseColor("#FFB067") else Color.argb(200, 255, 255, 255)
                    canvas.drawText(labelFor(d), x, baseY + 32f * dp, text)
                }
            }
            d += 5
        }
        // 中央朝向指针
        fill.color = Color.parseColor("#FFB067")
        arrowPath.reset()
        arrowPath.moveTo(w / 2f, top + 70f * dp)
        arrowPath.lineTo(w / 2f - 7f * dp, top + 80f * dp)
        arrowPath.lineTo(w / 2f + 7f * dp, top + 80f * dp)
        arrowPath.close()
        canvas.drawPath(arrowPath, fill)

        text.textSize = 12f * dp
        text.color = Color.argb(170, 255, 255, 255)
        text.textAlign = Paint.Align.CENTER
        canvas.drawText("朝向 " + headingDeg.toInt() + "°", w / 2f, top + 20f * dp, text)
    }

    private fun isCardinal(d: Int) = ((d % 90) + 90) % 90 == 0

    private fun labelFor(d: Int): String {
        val n = ((d % 360) + 360) % 360
        return when (n) {
            0 -> "N"
            45 -> "NE"
            90 -> "E"
            135 -> "SE"
            180 -> "S"
            225 -> "SW"
            270 -> "W"
            315 -> "NW"
            else -> ""
        }
    }

    private fun drawArrow(canvas: Canvas, w: Float, h: Float) {
        if (headingDeg.isNaN()) {
            text.textSize = 15f * dp
            text.color = Color.WHITE
            text.textAlign = Paint.Align.CENTER
            canvas.drawText("正在获取罗盘…请举平手机", w / 2f, h * 0.5f, text)
            return
        }
        val rel = GeoUtils.normalize180(bearingDeg - headingDeg)
        val halfFov = horizontalFovDeg / 2.0
        val tanHalfFov = tan(Math.toRadians(halfFov))
        val halfW = w / 2f
        val margin = 30f * dp

        // 只有落在视锥内才用透视投影；超出视场（含目标在身后 rel≈±180）一律压到屏幕边缘，
        // 否则 tan 在 ±180 附近会回落到圆心，箭头会指错方向。
        val outOfFov = Math.abs(rel) > halfFov
        val x = if (outOfFov) {
            if (rel < 0) margin else w - margin
        } else {
            (halfW + tan(Math.toRadians(rel)) / tanHalfFov * halfW).toFloat()
        }
        lastRelDeg = rel
        lastArrowX = x

        val cy = h * 0.50f
        val farness = (distanceMeters / 200.0).coerceIn(0.0, 1.0)
        val scale = (0.62f + (1.0 - farness).toFloat() * 0.38f)
        val unit = 42f * dp * scale

        // 外发光：两层半透明三角
        fill.color = Color.argb(55, 55, 224, 200)
        drawArrowShape(canvas, x, cy, unit * 1.45f)
        fill.color = Color.argb(110, 55, 224, 200)
        drawArrowShape(canvas, x, cy, unit * 1.18f)
        // 主体
        fill.color = Color.parseColor("#FFB067")
        drawArrowShape(canvas, x, cy, unit)

        if (outOfFov) {
            // 箭头被压到屏幕边缘时给一个提示
            text.textSize = 13f * dp
            text.color = Color.parseColor("#FFB067")
            text.textAlign = Paint.Align.CENTER
            val side = if (x < w / 2f) "◀" else "▶"
            canvas.drawText(side + " " + Math.abs(rel).toInt() + "°", x, cy - unit * 1.6f, text)
        }
    }

    /**
     * 把"躺在地上的箭头"投影到相机画面（不用 ARCore，只用旋转矩阵 + 相机内参）。
     *
     * 世界坐标用东-北-天(ENU)：手机在原点，地面在 z = -eyeHeight。
     * 箭头沿"手机→目标"的方位铺在正前方，再按设备姿态投影成屏幕像素，
     * 于是它看起来就贴在路面上，而且会随手机俯仰自然变形。
     */
    private fun drawGroundArrows(canvas: Canvas, w: Float, h: Float): Boolean {
        val r = poseMatrix ?: return false
        if (bearingDeg.isNaN() || headingDeg.isNaN()) return false
        val f = ((w / 2f) / tan(Math.toRadians(horizontalFovDeg / 2.0))).toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val brg = Math.toRadians(bearingDeg.toDouble())
        val sinB = sin(brg).toFloat()
        val cosB = cos(brg).toFloat()

        // 流动动画：位置随时间缓慢前移，形成"箭头在往你脚下走"的感觉
        val phase = (System.currentTimeMillis() % 2600L) / 2600f
        val count = 3
        var drawn = 0
        var centerX = Float.NaN

        for (i in 0 until count) {
            val base = 2.4f + i * 4.6f - phase * 4.6f
            if (base < 0.8f) continue
            val alpha = (240f * (1f - i * 0.3f)).toInt().coerceIn(60, 240)

            var ok = true
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            val pts = FloatArray(ARROW_LOCAL.size)
            var k = 0
            while (k < ARROW_LOCAL.size) {
                val u = base + ARROW_LOCAL[k]
                val v = ARROW_LOCAL[k + 1]
                // (u 沿方位, v 垂直方位) → ENU
                val e = u * sinB + v * cosB
                val n = u * cosB - v * sinB
                val up = -eyeHeightM
                // 世界 → 设备（旋转矩阵转置）
                val xd = r[0] * e + r[3] * n + r[6] * up
                val yd = r[1] * e + r[4] * n + r[7] * up
                val zd = r[2] * e + r[5] * n + r[8] * up
                // 设备 → 相机（相机看向设备 -Z，且图像 y 向下）
                val zc = -zd
                if (zc < 0.35f) {
                    ok = false
                    break
                }
                val sx = cx + xd / zc * f
                val sy = cy + (-yd) / zc * f
                pts[k] = sx
                pts[k + 1] = sy
                if (sx < minX) minX = sx
                if (sx > maxX) maxX = sx
                if (sy < minY) minY = sy
                if (sy > maxY) maxY = sy
                k += 2
            }
            if (!ok) continue
            if (maxX < -w * 0.6f || minX > w * 1.6f || maxY < -h * 0.6f || minY > h * 1.6f) continue

            arrowPath.reset()
            arrowPath.moveTo(pts[0], pts[1])
            for (j in 2 until pts.size step 2) arrowPath.lineTo(pts[j], pts[j + 1])
            arrowPath.close()

            // 地面上的柔光
            stroke.color = Color.argb(alpha / 5, 55, 224, 200)
            stroke.strokeWidth = 9f * dp
            stroke.style = Paint.Style.STROKE
            canvas.drawPath(arrowPath, stroke)
            stroke.style = Paint.Style.STROKE

            // 主体（半透明填充，能看见路面）
            fill.color = Color.argb((alpha * 0.72f).toInt(), 55, 224, 200)
            canvas.drawPath(arrowPath, fill)

            // 描边，保证在亮路面/草地上也看得清
            stroke.color = Color.argb(alpha, 230, 255, 250)
            stroke.strokeWidth = 2.2f * dp
            canvas.drawPath(arrowPath, stroke)

            if (centerX.isNaN()) centerX = (minX + maxX) / 2f
            drawn++
        }

        if (drawn > 0) {
            lastRelDeg = GeoUtils.normalize180(bearingDeg - headingDeg)
            if (!centerX.isNaN()) lastArrowX = centerX
            postInvalidateOnAnimation()  // 让流动动画继续
        }
        return drawn > 0
    }

    private fun drawArrowShape(canvas: Canvas, cx: Float, cy: Float, unit: Float) {
        arrowPath.reset()
        arrowPath.moveTo(cx, cy - unit)
        arrowPath.lineTo(cx + unit * 0.62f, cy + unit * 0.10f)
        arrowPath.lineTo(cx + unit * 0.22f, cy + unit * 0.10f)
        arrowPath.lineTo(cx + unit * 0.22f, cy + unit * 0.78f)
        arrowPath.lineTo(cx - unit * 0.22f, cy + unit * 0.78f)
        arrowPath.lineTo(cx - unit * 0.22f, cy + unit * 0.10f)
        arrowPath.lineTo(cx - unit * 0.62f, cy + unit * 0.10f)
        arrowPath.close()
        canvas.drawPath(arrowPath, fill)
    }

    private fun drawDistance(canvas: Canvas, w: Float, h: Float) {
        if (distanceMeters <= 0.0) return
        text.textAlign = Paint.Align.CENTER
        text.color = Color.WHITE
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = 40f * dp
        canvas.drawText(formatDistance(distanceMeters), w / 2f, h * 0.72f, text)

        text.typeface = Typeface.DEFAULT
        text.textSize = 13f * dp
        text.color = Color.argb(190, 255, 255, 255)
        canvas.drawText("剩余距离", w / 2f, h * 0.72f + 20f * dp, text)
    }

    private fun drawTurnCard(canvas: Canvas, w: Float, h: Float) {
        if (instruction.isEmpty()) return
        val rect = RectF(24f * dp, h - 132f * dp, w - 24f * dp, h - 44f * dp)
        fill.color = Color.argb(185, 16, 20, 26)
        canvas.drawRoundRect(rect, 22f * dp, 22f * dp, fill)

        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = 17f * dp
        text.color = Color.WHITE
        canvas.drawText(instruction, w / 2f, rect.top + 42f * dp, text)

        text.typeface = Typeface.DEFAULT
        text.textSize = 13f * dp
        text.color = Color.parseColor("#FFB067")
        canvas.drawText(formatDistance(distanceMeters) + " 后到达", w / 2f, rect.top + 66f * dp, text)
    }

    private fun drawWeakBanner(canvas: Canvas, w: Float) {
        val top = topInsetPx.toFloat()
        val rect = RectF(0f, top + 84f * dp, w, top + 122f * dp)
        fill.color = Color.argb(200, 255, 200, 40)
        canvas.drawRect(rect, fill)
        text.textSize = 13f * dp
        text.color = Color.parseColor("#3A2A00")
        text.textAlign = Paint.Align.CENTER
        canvas.drawText("定位信号弱，已降级为 2D 模式", w / 2f, top + 108f * dp, text)
    }

    private fun drawArrival(canvas: Canvas, w: Float, h: Float) {
        fill.color = Color.argb(210, 10, 14, 18)
        canvas.drawRect(0f, 0f, w, h, fill)
        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = 30f * dp
        text.color = Color.parseColor("#FFB067")
        canvas.drawText("已到达", w / 2f, h * 0.44f, text)
        text.textSize = 18f * dp
        text.color = Color.WHITE
        canvas.drawText(destinationName, w / 2f, h * 0.44f + 38f * dp, text)
        text.typeface = Typeface.DEFAULT
        text.textSize = 14f * dp
        text.color = Color.argb(190, 255, 255, 255)
        canvas.drawText("请留意正门位置", w / 2f, h * 0.44f + 70f * dp, text)
    }

    private fun formatDistance(m: Double): String =
        if (m >= 1000) String.format(Locale.CHINA, "%.1f 公里", m / 1000.0)
        else String.format(Locale.CHINA, "%.0f 米", m)

    private companion object {
        /**
         * 箭头铺在路面平面上的形状，单位米：(u = 前进方向, v = 横向)。
         * 箭杆 3 米、箭头 1.8 米长 2.3 米宽 —— 差不多是真实路面导向箭头的尺寸。
         */
        val ARROW_LOCAL = floatArrayOf(
            0f, -0.38f,
            3.0f, -0.38f,
            3.0f, -1.15f,
            4.8f, 0f,
            3.0f, 1.15f,
            3.0f, 0.38f,
            0f, 0.38f
        )
    }
}
