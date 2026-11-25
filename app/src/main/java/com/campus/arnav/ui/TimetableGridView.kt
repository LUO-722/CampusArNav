package com.campus.arnav.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.campus.arnav.data.Course
import com.campus.arnav.data.ScheduleLogic

/**
 * 自绘课程表网格：7 天 × 12 节，课程按周次过滤后画成色块。
 * 宽度固定为 7*52dp + 40dp，由 HorizontalScrollView 承载。
 */
class TimetableGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var courses: List<Course> = emptyList()
        set(v) { field = v; invalidate() }
    var skips: Set<Long> = emptySet()
        set(v) { field = v; invalidate() }
    var week: Int = 1
        set(v) { field = v; invalidate() }

    var onCourseTap: ((Course) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val periodW = 40 * density
    private val dayW = 56 * density
    private val headerH = 30 * density
    private val rowH = 54 * density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF121821.toInt() }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF263040.toInt()
        strokeWidth = 1 * density
        style = Paint.Style.STROKE
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9AA7B4.toInt()
        textSize = 11 * density
        textAlign = Paint.Align.CENTER
    }
    private val periodPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9AA7B4.toInt()
        textSize = 11 * density
        textAlign = Paint.Align.CENTER
    }
    private val courseNamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 10.5f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val courseLocPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 9 * density
        textAlign = Paint.Align.CENTER
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 1 * density
    }
    private val blockRect = RectF()

    private var lastTap = 0L

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (periodW + dayW * 7).toInt()
        val h = (headerH + rowH * 12).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(bgPaint.color)

        // 表头：周一…周日
        val days = arrayOf("一", "二", "三", "四", "五", "六", "日")
        for (d in 0 until 7) {
            val cx = periodW + dayW * d + dayW / 2
            canvas.drawText(days[d], cx, headerH * 0.68f, headPaint)
        }
        canvas.drawLine(periodW, 0f, periodW, headerH + rowH * 12, linePaint)

        // 横线 + 节次
        for (p in 0..12) {
            val y = headerH + rowH * p
            canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
            if (p < 12) {
                canvas.drawText(
                    (p + 1).toString(),
                    periodW / 2,
                    y + rowH / 2 + periodPaint.textSize / 3,
                    periodPaint
                )
            }
        }
        for (d in 1..7) {
            val x = periodW + dayW * d
            canvas.drawLine(x, 0f, x, headerH + rowH * 12, linePaint)
        }

        // 课程块
        for (c in courses) {
            if (!ScheduleLogic.weekMatches(c.weeks, week)) continue
            if (c.id in skips) continue
            val x = periodW + dayW * (c.weekday - 1) + 2 * density
            val y = headerH + rowH * (c.startPeriod - 1) + 2 * density
            val w = dayW - 4 * density
            val h = rowH * (c.endPeriod - c.startPeriod + 1) - 4 * density
            blockRect.set(x, y, x + w, y + h)
            fillPaint.color = c.color
            canvas.drawRoundRect(blockRect, 6 * density, 6 * density, fillPaint)
            canvas.drawRoundRect(blockRect, 6 * density, 6 * density, outlinePaint)

            val lines = wrap(c.name, blockRect.width() - 8 * density, courseNamePaint)
            var ty = y + 15 * density
            for (line in lines.take(3)) {
                canvas.drawText(line, x + w / 2, ty, courseNamePaint)
                ty += 12 * density
            }
            if (c.location.isNotBlank() && h > 46 * density) {
                canvas.drawText(
                    c.location.take(8),
                    x + w / 2,
                    y + h - 7 * density,
                    courseLocPaint
                )
            }
        }
    }

    private fun wrap(text: String, maxWidth: Float, paint: Paint): List<String> {
        val out = ArrayList<String>()
        var cur = StringBuilder()
        for (ch in text) {
            val test = cur.toString() + ch
            if (paint.measureText(test) > maxWidth && cur.isNotEmpty()) {
                out.add(cur.toString())
                cur = StringBuilder().append(ch)
            } else {
                cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val now = System.currentTimeMillis()
            if (now - lastTap < 350) return true
            lastTap = now
            val x = event.x
            val y = event.y
            if (x < periodW || y < headerH) return true
            val day = ((x - periodW) / dayW).toInt() + 1
            val period = ((y - headerH) / rowH).toInt() + 1
            val hit = courses.firstOrNull {
                it.weekday == day && period in it.startPeriod..it.endPeriod &&
                    ScheduleLogic.weekMatches(it.weeks, week) && it.id !in skips
            }
            if (hit != null) {
                performClick()
                onCourseTap?.invoke(hit)
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
