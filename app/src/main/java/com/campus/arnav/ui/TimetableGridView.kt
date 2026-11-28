package com.campus.arnav.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.DashPathEffect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.campus.arnav.data.Activity
import com.campus.arnav.data.Course
import com.campus.arnav.data.ScheduleLogic

/**
 * 自绘课程表网格：7 天 × 12 节，课程按周次过滤后画成色块。
 * 宽度固定为 7*52dp + 40dp，由 HorizontalScrollView 承载。
 *
 * 视觉：Ins 浅色·精致化 —— 网格线只有 #F2E4EC 一档，课程块是 pastel 圆角块 + 左侧深色竖条，
 * 活动块继续保持粉色虚线（和实心课程块一眼区分）。正文全部系统字体。
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
    /** 每个星期几的节假日标记（周一..周日，null=无）。 */
    var dayMarks: Array<String?>? = null
    /** 节假日所在的星期几（1..7），这些列会加一层淡色底。 */
    var holidayDays: IntArray = IntArray(0)
        set(v) { field = v; invalidate() }
    /** 今天所在的星期几（1..7）；0 表示当前显示的不是本周，不画「今天」高亮。 */
    var todayWeekday: Int = 0
        set(v) { field = v; invalidate() }

    var onCourseTap: ((Course) -> Unit)? = null
    var onActivityTap: ((Activity) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val periodW = 52 * density
    /** 星期列宽按实际可用宽度算：原来写死 54dp，7 列会把「日」挤出屏幕右边。 */
    private var dayW = 44 * density
    private val headerH = 30 * density
    private val rowH = 54 * density

    // ---- Ins 色板 ----
    private val cLine = 0xFFF2E4EC.toInt()        // 网格线
    private val cColLine = 0xFFFAF0F5.toInt()     // 列间淡线
    private val cBody = 0xFF3A3040.toInt()        // 正文 / 表头
    private val cInk = 0xFFA61E46.toInt()         // 深主色文字
    private val cTodayBg = 0xFFFFEAF2.toInt()     // 浅粉底
    private val cHolidayBg = 0xFFFFF3F8.toInt()   // 节假日淡粉底
    private val cHolidayMark = 0xFFD98324.toInt() // 节假日标记
    private val cTimeStart = 0xFFA99CB0.toInt()   // 开始时间
    private val cTimeEnd = 0xFFC9BFCF.toInt()     // 结束时间
    private val cName = 0xFF3D3340.toInt()        // 课程名
    private val cLoc = 0xFF5D5364.toInt()         // 地点
    private val cActTitle = 0xFFA61E46.toInt()    // 活动标题
    private val cActTime = 0xFFB0647C.toInt()     // 活动时间
    private val cPrimary = 0xFFE8447A.toInt()     // 主色

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cLine
        strokeWidth = 1 * density
        style = Paint.Style.STROKE
    }
    /** 列间淡线：只做「表格感」，不抢课程的视觉重量。 */
    private val colLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cColLine
        strokeWidth = 1 * density
        style = Paint.Style.STROKE
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cBody
        textSize = 13 * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val periodPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cBody
        textSize = 11 * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    /** 左侧时间列：本节开始时间（9.5sp 淡）与结束时间（9sp 更淡）。 */
    private val startTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cTimeStart
        textSize = 9.5f * density
        textAlign = Paint.Align.CENTER
    }
    private val endTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cTimeEnd
        textSize = 9 * density
        textAlign = Paint.Align.CENTER
    }
    private val holidayPaint = Paint().apply { color = cHolidayBg }
    private val todayHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cTodayBg }
    private val dayMarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cHolidayMark
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val courseNamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // pastel 浅底块：深色系统黑体，窄列也认得清
        color = cName
        textSize = 10.5f * density
        textAlign = Paint.Align.LEFT
        typeface = Typeface.DEFAULT_BOLD
    }
    private val courseLocPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cLoc
        textSize = 9 * density
        textAlign = Paint.Align.LEFT
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    /** 活动块用虚线框，和实心的课程块区分开。 */
    private val activityStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = cPrimary
    }
    private val activityNamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cActTitle
        textSize = 9.5f * density
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }
    private val activityTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cActTime
        textSize = 8.5f * density
        textAlign = Paint.Align.CENTER
    }
    private val blockRect = RectF()
    private val barRect = RectF()

    /** 课程与活动时间重叠时，活动的点击区域（画成课程块右上角的小角标）。 */
    private val badgeHits = ArrayList<Pair<RectF, Activity>>()

    /** 不与课程重叠的活动：仍按真实时间画虚线块，点击命中它们。 */
    private var freeActivities: List<Activity> = emptyList()

    private val actBadgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cPrimary }
    private val actBadgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 8.5f * density
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cTodayBg }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cInk
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

        val gridBottom = headerH + rowH * 12

        // 节假日列：整列铺一层很淡的粉，比整行高亮更安静
        for (d in 0 until 7) {
            if (holidayDays.isNotEmpty() && d + 1 in holidayDays) {
                canvas.drawRect(periodW + dayW * d, 0f, periodW + dayW * (d + 1), gridBottom, holidayPaint)
            }
        }

        // 今天所在列：只在表头画一颗浅粉胶囊，避免整列抢眼
        if (todayWeekday in 1..7) {
            val d = todayWeekday - 1
            val pad = 3 * density
            blockRect.set(
                periodW + dayW * d + pad, 2 * density,
                periodW + dayW * (d + 1) - pad, headerH - 2 * density
            )
            val r = blockRect.height() / 2f
            canvas.drawRoundRect(blockRect, r, r, todayHeadPaint)
        }

        // 表头：周一…周日
        val days = arrayOf("一", "二", "三", "四", "五", "六", "日")
        headPaint.textSize = 13 * density
        for (d in 0 until 7) {
            val cx = periodW + dayW * d + dayW / 2
            headPaint.color = if (todayWeekday == d + 1) cInk else cBody
            canvas.drawText(days[d], cx, headerH * 0.64f, headPaint)
            val mk = dayMarks?.getOrNull(d)
            if (!mk.isNullOrEmpty()) {
                dayMarkPaint.textSize = 12 * density
                canvas.drawText(mk.take(2), cx, headerH - 5 * density, dayMarkPaint)
            }
        }

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
                canvas.drawText((p + 1).toString(), periodW / 2, mid + 4 * density, periodPaint)
                if (start.isNotEmpty()) {
                    canvas.drawText(start, periodW / 2, y + 12 * density, startTimePaint)
                }
                if (end.isNotEmpty()) {
                    canvas.drawText(end, periodW / 2, y + rowH - 5 * density, endTimePaint)
                }
            }
        }
        // 竖线：只画列间淡线 + 时间列右侧一条，横线才是主结构
        for (d in 1..7) {
            val x = periodW + dayW * d
            canvas.drawLine(x, headerH, x, gridBottom, colLinePaint)
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
            val paper = coursePastel(c.color)

            // 假阴影：右下角偏移 1dp 的深一档色块（Canvas 画不了 elevation，这样有一点浮起感）
            fillPaint.color = darkerColor(paper, 0.92f)
            canvas.drawRoundRect(
                blockRect.left + density, blockRect.top + density,
                blockRect.right + density, blockRect.bottom + density,
                10 * density, 10 * density, fillPaint
            )

            fillPaint.color = paper
            canvas.drawRoundRect(blockRect, 10 * density, 10 * density, fillPaint)

            // 左侧 3dp 竖条：用课程色加深 20%，一眼分得清是「哪一类」课
            barRect.set(
                blockRect.left + 3 * density, blockRect.top + 5 * density,
                blockRect.left + 6 * density, blockRect.bottom - 5 * density
            )
            barPaint.color = darkerColor(paper, 0.80f)
            canvas.drawRoundRect(barRect, 1.5f * density, 1.5f * density, barPaint)

            val acts = overlapped[c]
            val hasAct = !acts.isNullOrEmpty()
            val hasDemo = c.source == "demo"
            // 顶部角标条带：有「示例」/「＋N」角标时课名整体下移，任何块宽都不压字
            val badgeBand = when {
                hasAct -> 16 * density
                hasDemo -> 13 * density
                else -> 0f
            }
            val narrow = rect.width() < 62 * density
            val nameSize = (if (narrow) 8.5f else 9.5f) * density
            courseNamePaint.textSize = nameSize
            val lineH = nameSize * 1.1f
            val textLeft = blockRect.left + 6 * density
            val avail = (blockRect.right - 2 * density - textLeft).coerceAtLeast(8 * density)
            val bodyH = (blockRect.height() - badgeBand - 2 * density).coerceAtLeast(lineH)
            val maxLines = (bodyH / lineH).toInt().coerceIn(1, 4)
            val lines = wrap(c.name, avail, courseNamePaint)
            val shown = lines.take(maxLines).toMutableList()
            if (lines.size > maxLines && shown.isNotEmpty()) {
                shown[shown.size - 1] = ellipsize(shown[shown.size - 1], avail, courseNamePaint)
            }
            var ty = blockRect.top + badgeBand + 2 * density + nameSize
            for (line in shown) {
                canvas.drawText(line, textLeft, ty, courseNamePaint)
                ty += lineH
            }
            if (c.location.isNotBlank() && blockRect.height() > 50 * density &&
                ty + 2 * density <= blockRect.bottom - 10 * density) {
                courseLocPaint.textSize = (if (narrow) 8f else 8.5f) * density
                canvas.drawText(
                    c.location.take(if (narrow) 5 else 7),
                    textLeft,
                    blockRect.bottom - 5 * density,
                    courseLocPaint
                )
            }

            // 示例课角标：默认右上角；若该格还有活动角标，挪到左上角免得叠在一起
            if (hasDemo) {
                val label = "示例"
                val bw = (badgeTextPaint.measureText(label) + 6 * density)
                    .coerceAtMost(rect.width() - 4 * density)
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
            fillPaint.color = if (a.conflict) 0xFFFFE7EE.toInt() else cHolidayBg
            canvas.drawRoundRect(blockRect, 10 * density, 10 * density, fillPaint)
            activityStroke.pathEffect = DashPathEffect(floatArrayOf(3 * density, 2 * density), 0f)
            activityStroke.color = if (a.conflict) 0xFFD6455F.toInt() else cPrimary
            canvas.drawRoundRect(blockRect, 10 * density, 10 * density, activityStroke)
            activityStroke.pathEffect = null

            val textLeft = blockRect.left + 7 * density
            val avail = (blockRect.width() - 12 * density).coerceAtLeast(10 * density)
            val lines = wrap(a.title, avail, activityNamePaint)
            val hasDemo = a.source == "demo"
            val badgeBand = if (hasDemo) 13 * density else 0f
            var ty = y + badgeBand + 3 * density + 9.5f * density
            for (line in lines.take(2)) {
                canvas.drawText(line, textLeft, ty, activityNamePaint)
                ty += 11 * density
            }

            // 活动块「示例」角标：同样占顶部一条，不压标题
            if (hasDemo) {
                val label = "示例"
                val bw = (badgeTextPaint.measureText(label) + 6 * density)
                    .coerceAtMost(blockRect.width() - 4 * density)
                val bh = 10 * density
                val top = y + 2 * density
                badgeRect.set(x + w - 2 * density - bw, top, x + w - 2 * density, top + bh)
                canvas.drawRoundRect(badgeRect, bh / 2, bh / 2, badgeBgPaint)
                canvas.drawText(label, badgeRect.centerX(), top + bh * 0.75f, badgeTextPaint)
            }

            if (h > 40 * density) {
                drawActivityTime(canvas, blockRect, a.startTime, a.endTime)
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
        val avail = (block.width() - 4 * density).coerceAtLeast(8 * density)
        var label = if (block.width() < 70 * density) "＋$n" else "＋$n 活动"
        // 先按块宽挑字号（8.5sp 起，最小 7sp），保证文字不越界
        var sizeSp = 8.5f
        actBadgeText.textSize = sizeSp * density
        while (sizeSp > 7f && actBadgeText.measureText(label) > avail - 4 * density) {
            sizeSp -= 0.5f
            actBadgeText.textSize = sizeSp * density
        }
        if (actBadgeText.measureText(label) > avail - 2 * density) {
            label = "＋$n"
            actBadgeText.textSize = 8.5f * density
        }
        val bh = 14 * density
        val bw = (actBadgeText.measureText(label) + 10 * density).coerceAtMost(avail)
        val right = block.right - 2 * density
        val top = block.top + 2 * density
        val rect = RectF(right - bw, top, right, top + bh)
        canvas.drawRoundRect(rect, 6 * density, 6 * density, actBadgeBg)
        canvas.drawText(label, rect.centerX(), top + bh * 0.72f, actBadgeText)
        badgeHits.add(rect to list.first())
    }

    /**
     * 活动块底部时间「HH:mm-HH:mm」：先量再画，宽了先降到 8sp；还宽就拆两行
     * （起始时间 / 结束时间），保证不会出现「16:3」这种被裁断的中间态。
     */
    private fun drawActivityTime(canvas: Canvas, block: RectF, start: String, end: String) {
        val s = start.ifBlank { "--:--" }
        val e = end.ifBlank { "--:--" }
        val one = "$s-$e"
        val avail = (block.width() - 6 * density).coerceAtLeast(10 * density)
        activityTimePaint.textSize = 8.5f * density
        if (activityTimePaint.measureText(one) > avail) {
            activityTimePaint.textSize = 8f * density
        }
        val cx = block.centerX()
        if (activityTimePaint.measureText(one) <= avail) {
            canvas.drawText(one, cx, block.bottom - 6 * density, activityTimePaint)
            return
        }
        // 两行兜底：块高足够才画（≥48dp），否则只显示起始时间，绝不画半截
        if (block.height() >= 42 * density) {
            canvas.drawText(s, cx, block.bottom - 17 * density, activityTimePaint)
            canvas.drawText(e, cx, block.bottom - 6 * density, activityTimePaint)
        } else if (activityTimePaint.measureText(s) <= avail) {
            canvas.drawText(s, cx, block.bottom - 6 * density, activityTimePaint)
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

    /** 行尾省略号：只影响最后一行的显示，不改课程名本身。 */
    private fun ellipsize(text: String, maxWidth: Float, paint: Paint): String {
        var s = text
        while (s.isNotEmpty() && paint.measureText("$s…") > maxWidth) {
            s = s.dropLast(1)
        }
        return s + "…"
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