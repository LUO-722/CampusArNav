package com.campus.arnav.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.DashPathEffect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.campus.arnav.data.Activity
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
    /** 本周（只传本周日期范围内的）活动，按时间画进格子。 */
    var activities: List<Activity> = emptyList()
        set(v) { field = v; invalidate() }

    var onCourseTap: ((Course) -> Unit)? = null
    var onActivityTap: ((Activity) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val periodW = 52 * density
    /** 星期列宽按实际可用宽度算：原来写死 54dp，7 列会把「日」挤出屏幕右边。 */
    private var dayW = 44 * density
    private val headerH = 30 * density
    private val rowH = 54 * density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4DEE8.toInt()
        strokeWidth = 1 * density
        style = Paint.Style.STROKE
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF74637C.toInt()
        textSize = 11 * density
        textAlign = Paint.Align.CENTER
    }
    private val periodPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF74637C.toInt()
        textSize = 11 * density
        textAlign = Paint.Align.CENTER
    }
    /** 节次时间：08:15 这类小字，画在节次号下面。 */
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7E6C86.toInt()
        textSize = 8f * density
        textAlign = Paint.Align.CENTER
    }
    private val courseNamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // pastel 浅底块：用深色字，白字在浅粉浅蓝上几乎看不见
        color = 0xFF3D3340.toInt()
        textSize = 10.5f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val courseLocPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB33D3340.toInt()
        textSize = 8.5f * density
        textAlign = Paint.Align.CENTER
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    /** 活动块用虚线框，和实心的课程块区分开。 */
    private val activityStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val activityNamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3D3340.toInt()
        textSize = 9.5f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val activityTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC3D3340.toInt()
        textSize = 8.5f * density
        textAlign = Paint.Align.CENTER
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x14E8447A
        style = Paint.Style.STROKE
        strokeWidth = 1 * density
    }
    private val blockRect = RectF()

    /** 「示例」角标：只有 demo 课程块右上角画一个很小的圆角标签，纯绘制，不参与点击命中。 */
    /** 课程与活动时间重叠时，活动的点击区域（画成课程块右上角的小角标）。 */
    private val badgeHits = ArrayList<Pair<RectF, Activity>>()

    /** 不与课程重叠的活动：仍按真实时间画虚线块，点击命中它们。 */
    private var freeActivities: List<Activity> = emptyList()

    private val actBadgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8447A.toInt() }
    private val actBadgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 7.5f * density
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val actHintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD6455F.toInt()
        textSize = 7f * density
        textAlign = Paint.Align.CENTER
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFE3EE.toInt() }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFA61E46.toInt()
        textSize = 7.5f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val badgeRect = RectF()

    private var lastTap = 0L

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 宽度铺满父容器：periodW 固定给节次+时间，剩下 7 天平分
        val avail = MeasureSpec.getSize(widthMeasureSpec)
        if (avail > 0) {
            dayW = ((avail - periodW) / 7f).coerceAtLeast(34 * density)
        }
        val w = (periodW + dayW * 7).toInt().coerceAtMost(if (avail > 0) avail else Int.MAX_VALUE)
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

        // 横线 + 节次 + 节次时间
        // 时间来自知识库《作息时间与门禁》里的科文 12 节作息（全年一套）。左侧时间列按节次上下沿各写一个
        // 时间：行顶＝本节开始，行底＝本节结束，中间是节次号，这样一眼能看出「几点到几点」。
        for (p in 0..12) {
            val y = headerH + rowH * p
            canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
            if (p < 12) {
                val mid = y + rowH / 2
                val range = ScheduleLogic.periodTime(p + 1, "")
                val start = range.substringBefore('-').trim()
                val end = range.substringAfter('-', "").trim()
                canvas.drawText((p + 1).toString(), periodW / 2, mid + 3 * density, periodPaint)
                if (start.isNotEmpty()) {
                    canvas.drawText(start, periodW / 2, y + 10 * density, timePaint)
                }
                if (end.isNotEmpty()) {
                    canvas.drawText(end, periodW / 2, y + rowH - 4 * density, timePaint)
                }
            }
        }
        for (d in 1..7) {
            val x = periodW + dayW * d
            canvas.drawLine(x, 0f, x, headerH + rowH * 12, linePaint)
        }

        // 先把课程格算出来：后面判断「哪些活动压在这门课上」要用
        val courseRects = ArrayList<Pair<Course, RectF>>()
        for (c in courses) {
            if (!ScheduleLogic.weekMatches(c.weeks, week)) continue
            if (c.id in skips) continue
            courseRects.add(c to courseRect(c))
        }

        // 与课程同格的活动：不再盖在课程上，改成课程块里的「＋N」角标（点一下看活动）
        badgeHits.clear()
        val overlapped = LinkedHashMap<Course, MutableList<Activity>>()
        val free = ArrayList<Activity>()
        for (a in activities) {
            val r = activityRect(a) ?: continue
            val host = courseRects.firstOrNull { RectF.intersects(it.second, r) }
            if (host != null) {
                overlapped.getOrPut(host.first) { ArrayList() }.add(a)
            } else {
                free.add(a)
            }
        }
        freeActivities = free

        // 课程块
        for ((c, rect) in courseRects) {
            blockRect.set(rect)
            fillPaint.color = c.color
            canvas.drawRoundRect(blockRect, 6 * density, 6 * density, fillPaint)
            canvas.drawRoundRect(blockRect, 6 * density, 6 * density, outlinePaint)

            val acts = overlapped[c]
            val hasAct = !acts.isNullOrEmpty()
            // 有活动角标时课程名整体下移，保证课名不被角标压住
            val nameTop = if (hasAct) 27 * density else 15 * density
            val lines = wrap(c.name, rect.width() - 8 * density, courseNamePaint)
            var ty = rect.top + nameTop
            for (line in lines.take(if (hasAct) 2 else 3)) {
                canvas.drawText(line, rect.centerX(), ty, courseNamePaint)
                ty += 12 * density
            }
            if (c.location.isNotBlank() && rect.height() > 46 * density) {
                canvas.drawText(
                    c.location.take(8),
                    rect.centerX(),
                    rect.bottom - 7 * density,
                    courseLocPaint
                )
            }

            // 示例课角标：默认右上角；若该格还有活动角标，挪到左上角免得叠在一起
            if (c.source == "demo") {
                val label = "示例"
                val bw = badgeTextPaint.measureText(label) + 6 * density
                val bh = 10 * density
                val top = rect.top + 2 * density
                val right = if (hasAct) rect.left + 2 * density + bw else rect.right - 2 * density
                badgeRect.set(right - bw, top, right, top + bh)
                canvas.drawRoundRect(badgeRect, bh / 2, bh / 2, badgeBgPaint)
                canvas.drawText(
                    label,
                    badgeRect.centerX(),
                    top + bh * 0.75f,
                    badgeTextPaint
                )
            }

            if (acts != null && acts.isNotEmpty()) drawActivityBadge(canvas, rect, acts)
        }

        // 不重叠的活动：仍按真实时间画虚线块，一眼区分「临时日程」
        for (a in free) {
            val wd = ScheduleLogic.weekdayOf(a.date)
            if (wd !in 1..7) continue
            val sp = ScheduleLogic.periodForTime(a.startTime, a.date).coerceIn(1, 12)
            val epRaw = ScheduleLogic.periodForTime(a.endTime, a.date).coerceIn(1, 12)
            val ep = if (epRaw < sp) sp else epRaw
            val x = periodW + dayW * (wd - 1) + 2 * density
            val y = headerH + rowH * (sp - 1) + 2 * density
            val w = dayW - 4 * density
            val h = rowH * (ep - sp + 1) - 4 * density
            blockRect.set(x, y, x + w, y + h)
            fillPaint.color = if (a.conflict) 0x66FFC2C8.toInt() else 0x66FFD9E6
            canvas.drawRoundRect(blockRect, 6 * density, 6 * density, fillPaint)
            activityStroke.pathEffect = DashPathEffect(floatArrayOf(3 * density, 2 * density), 0f)
            activityStroke.color = if (a.conflict) 0xCCD6455F.toInt() else 0xCCE8447A.toInt()
            canvas.drawRoundRect(blockRect, 6 * density, 6 * density, activityStroke)
            activityStroke.pathEffect = null

            val lines = wrap(a.title, blockRect.width() - 8 * density, activityNamePaint)
            var ty = y + 14 * density
            for (line in lines.take(2)) {
                canvas.drawText(line, x + w / 2, ty, activityNamePaint)
                ty += 11 * density
            }
            if (h > 42 * density) {
                canvas.drawText(
                    "${a.startTime}-${a.endTime}",
                    x + w / 2,
                    y + h - 6 * density,
                    activityTimePaint
                )
            }
        }
    }

    /** 课程在周视图里的矩形。 */
    private fun courseRect(c: Course): RectF {
        val x = periodW + dayW * (c.weekday - 1) + 2 * density
        val y = headerH + rowH * (c.startPeriod - 1) + 2 * density
        val w = dayW - 4 * density
        val h = rowH * (c.endPeriod - c.startPeriod + 1) - 4 * density
        return RectF(x, y, x + w, y + h)
    }

    /** 活动在周视图里的矩形；日期非法返回 null。 */
    /** "HH:mm" → 当天分钟数；解析不出来返回 -1。 */
    private fun minutesOf(hhmm: String): Int {
        val t = hhmm.trim()
        val i = t.indexOf(':')
        if (i <= 0) return -1
        val h = t.substring(0, i).toIntOrNull() ?: return -1
        val m = t.substring(i + 1).take(2).toIntOrNull() ?: return -1
        return h * 60 + m
    }

    /**
     * 时间 → 行内 y 坐标：先定位落在哪一小节，再在该小节里按分钟线性插值。
     * 这样 10:00—11:30 的活动就只占 10:00 到 11:30 那段，不再被拉成整节。
     */
    private fun yForTime(hhmm: String, fallbackPeriod: Int): Float {
        val t = minutesOf(hhmm)
        if (t < 0) return headerH + rowH * (fallbackPeriod - 1)
        for (p in 1..12) {
            val range = ScheduleLogic.periodTime(p, "")
            val s = minutesOf(range.substringBefore("-"))
            val e = minutesOf(range.substringAfter("-"))
            if (s < 0 || e <= s) continue
            if (t < s) return headerH + rowH * (p - 1)
            if (t <= e) {
                val frac = (t - s).toFloat() / (e - s).toFloat()
                return headerH + rowH * (p - 1) + rowH * frac
            }
        }
        return headerH + rowH * 12
    }

    private fun activityRect(a: Activity): RectF? {
        val wd = ScheduleLogic.weekdayOf(a.date)
        if (wd !in 1..7) return null
        val sp = ScheduleLogic.periodForTime(a.startTime, a.date).coerceIn(1, 12)
        val gridBottom = headerH + rowH * 12
        val top = yForTime(a.startTime, sp) + density
        val endRaw = if (a.endTime.isBlank()) top + rowH * 0.75f
                     else yForTime(a.endTime, sp + 1) - density
        val bottom = endRaw.coerceAtLeast(top + 16 * density).coerceAtMost(gridBottom)
        val x = periodW + dayW * (wd - 1) + 2 * density
        return RectF(x, top, x + dayW - 4 * density, bottom)
    }

    /** 在课程块右上角画「＋N」角标：只占顶部一条，课名已下移让位，点角标看活动详情。 */
    private fun drawActivityBadge(canvas: Canvas, block: RectF, list: List<Activity>) {
        val n = list.size
        val narrow = block.width() < 62 * density
        val label = if (narrow) "＋$n" else "＋$n 活动"
        val bh = 11 * density
        val bw = (actBadgeText.measureText(label) + 8 * density).coerceAtMost(block.width() - 2 * density)
        val right = block.right - 2 * density
        val top = block.top + 2 * density
        val rect = RectF(right - bw, top, right, top + bh)
        canvas.drawRoundRect(rect, bh / 2, bh / 2, actBadgeBg)
        canvas.drawText(label, rect.centerX(), top + bh * 0.74f, actBadgeText)
        badgeHits.add(rect to list.first())
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
            // 先判「＋N 活动」角标，再判独立的虚线活动块，最后才是课程
            val hitBadge = badgeHits.firstOrNull { it.first.contains(x, y) }?.second
            if (hitBadge != null) {
                performClick()
                onActivityTap?.invoke(hitBadge)
                return true
            }
            val hitAct = freeActivities.firstOrNull { a ->
                if (a.date.isBlank()) return@firstOrNull false
                val wd = ScheduleLogic.weekdayOf(a.date)
                val sp = ScheduleLogic.periodForTime(a.startTime, a.date).coerceIn(1, 12)
                val epRaw = ScheduleLogic.periodForTime(a.endTime, a.date).coerceIn(1, 12)
                val ep = if (epRaw < sp) sp else epRaw
                wd == day && period in sp..ep
            }
            if (hitAct != null) {
                performClick()
                onActivityTap?.invoke(hitAct)
                return true
            }
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
