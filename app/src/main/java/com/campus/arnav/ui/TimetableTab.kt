package com.campus.arnav.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.campus.arnav.R
import com.campus.arnav.data.Course
import com.campus.arnav.data.CourseStore
import com.campus.arnav.data.ScheduleLogic

/**
 * ① 课程表：本周网格 + 今日活动 + 导入入口。
 */
class TimetableTab(private val ctx: Context) : LinearLayout(ctx) {

    private val grid = TimetableGridView(ctx).apply {
        contentDescription = "课程表：7 天 × 12 节；点课程块查看详情"
    }
    private val weekLabel = textView(ctx, "", 13f, ctx.color(R.color.text_secondary))
    private val subTitle = textView(ctx, "", 12f, ctx.color(R.color.text_secondary))
    private val todayBox = LinearLayout(ctx)
    private val upcomingBox = LinearLayout(ctx)
    private var shownWeek = ScheduleLogic.currentWeek()

    init {
        orientation = VERTICAL
        setBackgroundColor(ctx.color(R.color.bg_dark))
        setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(8))

        // 标题
        addView(textView(ctx, "课程表", 24f, ctx.color(R.color.text_primary), bold = true))
        addView(subTitle)

        // 周次切换
        val weekRow = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ctx.dp(8), 0, ctx.dp(4))
        }
        val prev = button(ctx, "◀", ctx.color(R.color.bg_card), ctx.color(R.color.accent)).apply {
            setOnClickListener { switchWeek(-1) }
        }
        val next = button(ctx, "▶", ctx.color(R.color.bg_card), ctx.color(R.color.accent)).apply {
            setOnClickListener { switchWeek(1) }
        }
        val now = button(ctx, "本周", ctx.color(R.color.bg_card), ctx.color(R.color.text_primary)).apply {
            setOnClickListener {
                shownWeek = ScheduleLogic.currentWeek()
                refresh()
            }
        }
        weekRow.addView(prev, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(36)))
        val lpLabel = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        weekRow.addView(weekLabel, lpLabel)
        weekRow.addView(next, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(36)))
        weekRow.addView(now, LinearLayout.LayoutParams(ctx.dp(64), ctx.dp(36)).apply { leftMargin = ctx.dp(6) })
        addView(weekRow)

        // 网格
        val hScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(grid)
        }
        addView(hScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 操作（两行，避免窄屏挤成多行）
        val ops = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            setPadding(0, ctx.dp(8), 0, ctx.dp(4))
        }
        ops.addView(button(ctx, "导入课表", ctx.color(R.color.bg_card), ctx.color(R.color.accent)).apply {
            setOnClickListener { importDialog() }
        }, LinearLayout.LayoutParams(0, ctx.dp(40), 1f))
        ops.addView(button(ctx, "＋课程", ctx.color(R.color.bg_card), ctx.color(R.color.text_primary)).apply {
            setOnClickListener { editCourse(null) }
        }, LinearLayout.LayoutParams(0, ctx.dp(40), 1f).apply { leftMargin = ctx.dp(6) })
        ops.addView(button(ctx, "示例课表", ctx.color(R.color.bg_card), ctx.color(R.color.text_primary)).apply {
            setOnClickListener {
                if (CourseStore.courses(ctx).isNotEmpty()) {
                    ctx.toast("课表里已有课程，先点「清空」再载入示例")
                    return@setOnClickListener
                }
                CourseStore.seedDemo(ctx, force = true)
                refresh()
                ctx.toast("已载入演示课表")
            }
        }, LinearLayout.LayoutParams(0, ctx.dp(40), 1f).apply { leftMargin = ctx.dp(6) })
        addView(ops)

        val ops2 = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            setPadding(0, ctx.dp(4), 0, ctx.dp(4))
        }
        ops2.addView(button(ctx, "导出 / 分享", ctx.color(R.color.bg_card), ctx.color(R.color.text_primary)).apply {
            setOnClickListener { exportShare() }
        }, LinearLayout.LayoutParams(0, ctx.dp(40), 1f))
        ops2.addView(button(ctx, "＋活动", ctx.color(R.color.bg_card), ctx.color(R.color.accent)).apply {
            setOnClickListener { addActivity() }
        }, LinearLayout.LayoutParams(0, ctx.dp(40), 1f).apply { leftMargin = ctx.dp(6) })
        ops2.addView(button(ctx, "清空", ctx.color(R.color.bg_card), ctx.color(R.color.text_secondary)).apply {
            setOnClickListener {
                confirm(ctx, "清空课程表", "会删除全部课程与跳课记录，活动保留。") {
                    CourseStore.clearCourses(ctx)
                    refresh()
                }
            }
        }, LinearLayout.LayoutParams(0, ctx.dp(40), 1f).apply { leftMargin = ctx.dp(6) })
        addView(ops2)

        // 今日活动
        addView(textView(ctx, "今日活动", 15f, ctx.color(R.color.text_primary), bold = true).apply {
            setPadding(0, ctx.dp(12), 0, ctx.dp(4))
        })
        todayBox.orientation = VERTICAL
        addView(todayBox)

        addView(textView(ctx, "接下来 7 天", 15f, ctx.color(R.color.text_primary), bold = true).apply {
            setPadding(0, ctx.dp(14), 0, ctx.dp(4))
        })
        upcomingBox.orientation = VERTICAL
        addView(upcomingBox)

        grid.onCourseTap = { showCourse(it) }
        grid.onActivityTap = { showActivity(it) }
        shownWeek = ScheduleLogic.currentWeek()
        refresh()
    }

    private fun switchWeek(d: Int) {
        shownWeek = (shownWeek + d).coerceIn(1, ScheduleLogic.totalWeeks())
        refresh()
    }

    fun refresh() {
        val courses = CourseStore.courses(ctx)
        grid.courses = courses
        grid.week = shownWeek
        val skips = HashSet<Long>()
        for (wd in 1..7) skips.addAll(CourseStore.skipsFor(ctx, ScheduleLogic.dateOf(shownWeek, wd)))
        grid.skips = skips
        // 本周活动也画进课表格子（虚线块）
        val weekStart = ScheduleLogic.dateOf(shownWeek, 1)
        val weekEnd = ScheduleLogic.dateOf(shownWeek, 7)
        grid.activities = CourseStore.activities(ctx)
            .filter { it.date >= weekStart && it.date <= weekEnd }
            .sortedBy { it.date + it.startTime }
        // 节次时间用知识库《作息时间与门禁》的科文作息：8:15 开始、全年一套，不分冬夏
        weekLabel.text = ScheduleLogic.weekLabel(shownWeek)
        subTitle.text = "${ScheduleLogic.semesterName()} · ${ScheduleLogic.totalWeeks()} 周 · 本周第 ${ScheduleLogic.currentWeek()} 周 · 科文作息 8:15—20:40"

        todayBox.removeAllViews()
        val today = CourseStore.today()
        val acts = CourseStore.activitiesOn(ctx, today).sortedBy { it.startTime }
        if (acts.isEmpty()) {
            todayBox.addView(textView(ctx, "今天没有录入的活动。", 13f, ctx.color(R.color.text_secondary)))
        }
        for (a in acts) {
            val row = card(ctx, 12).apply {
                setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
                val t = textView(
                    ctx,
                    (if (a.conflict) "⚠ " else "") + "${a.startTime}-${a.endTime}  ${a.title}",
                    14f,
                    if (a.conflict) Color.parseColor("#E8447A") else ctx.color(R.color.text_primary),
                    bold = true
                )
                addView(t)
                addView(textView(ctx, listOf(a.location, a.note).filter { it.isNotBlank() }.joinToString(" · "), 12f, ctx.color(R.color.text_secondary)))
            }
            row.setOnClickListener {
                showActivity(a)
            }
            todayBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ctx.dp(6) })
        }

        // 接下来 7 天
        upcomingBox.removeAllViews()
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, 7)
        val end = fmt.format(cal.time)
        val upcoming = CourseStore.activities(ctx)
            .filter { it.date > today && it.date <= end }
            .sortedBy { it.date }
        if (upcoming.isEmpty()) {
            upcomingBox.addView(textView(ctx, "未来一周没有活动。", 13f, ctx.color(R.color.text_secondary)))
        }
        for (a in upcoming) {
            val row = card(ctx, 12).apply {
                setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
                addView(textView(ctx, "${a.date}  ${a.startTime}-${a.endTime}", 12f, ctx.color(R.color.accent)))
                addView(textView(ctx, a.title, 14f, ctx.color(R.color.text_primary), bold = true))
                val extra = listOf(a.location, a.note).filter { it.isNotBlank() }.joinToString(" · ")
                if (extra.isNotBlank()) addView(textView(ctx, extra, 12f, ctx.color(R.color.text_secondary)))
            }
            row.setOnClickListener {
                showActivity(a)
            }
            upcomingBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ctx.dp(6) })
        }
    }

    private fun showCourse(c: Course) {
        val wk = shownWeek
        val info = buildString {
            append("${c.name}\n")
            if (c.teacher.isNotBlank()) append("教师：${c.teacher}\n")
            if (c.location.isNotBlank()) append("地点：${c.location}\n")
            append("时间：周${ScheduleLogic.weekdayName(c.weekday)} 第 ${c.startPeriod}-${c.endPeriod} 节\n")
            val date = ScheduleLogic.dateOf(wk, c.weekday)
            val start = ScheduleLogic.periodTime(c.startPeriod, date).substringBefore('-')
            val end = ScheduleLogic.periodTime(c.endPeriod, date).substringAfter('-')
            append("节次：$start - $end\n")
            append("周次：${c.weeks}\n")
            append("来源：${c.source}")
            if (c.raw.isNotBlank()) {
                append("\n\n教务原文：\n")
                append(c.raw)
            }
        }
        AlertDialog.Builder(ctx)
            .setTitle("课程详情")
            .setMessage(info)
            .setPositiveButton("编辑") { _, _ -> editCourse(c) }
            .setNeutralButton("本周停课") { _, _ ->
                val date = ScheduleLogic.dateOf(wk, c.weekday)
                CourseStore.skipCourseOn(ctx, c.id, date)
                refresh()
                ctx.toast("已标记 $date 停课")
            }
            .setNegativeButton("删除") { _, _ ->
                CourseStore.deleteCourse(ctx, c.id)
                refresh()
            }
            .show()
    }

    private fun editCourse(c: Course?) {
        inputForm(
            ctx, if (c == null) "添加课程" else "编辑课程",
            listOf(
                "课程名" to (c?.name ?: ""),
                "教师" to (c?.teacher ?: ""),
                "地点" to (c?.location ?: ""),
                "周几（1-7）" to (c?.weekday?.toString() ?: "1"),
                "开始节（1-12）" to (c?.startPeriod?.toString() ?: "1"),
                "结束节（1-12）" to (c?.endPeriod?.toString() ?: "2"),
                "周次（如 1-16、单周）" to (c?.weeks ?: "1-16")
            ),
            multiline = false
        ) { v ->
            val name = v.getOrNull(0).orEmpty().trim()
            if (name.isBlank()) {
                ctx.toast("课程名不能为空")
                return@inputForm
            }
            val wdRaw = v.getOrNull(3).orEmpty().trim()
            val stRaw = v.getOrNull(4).orEmpty().trim()
            val enRaw = v.getOrNull(5).orEmpty().trim()
            val wd = wdRaw.toIntOrNull()?.coerceIn(1, 7)
            val st = stRaw.toIntOrNull()?.coerceIn(1, 12)
            val en = enRaw.toIntOrNull()?.coerceIn(st ?: 1, 12)
            if (wd == null || st == null || en == null) {
                ctx.toast("周几/节次应填数字：周几 1-7，节次 1-12")
                return@inputForm
            }
            val weeks = v.getOrNull(6).orEmpty().trim().ifBlank { "1-16" }
            if (weeks.any { it !in "0123456789,-—~，单双周第" }) {
                ctx.toast("周次格式示例：1-16、1-8,10-16、1-16单")
                return@inputForm
            }
            if (c == null) {
                CourseStore.addCourse(
                    ctx, name,
                    teacher = v.getOrNull(1).orEmpty(),
                    location = v.getOrNull(2).orEmpty(),
                    weekday = wd, startPeriod = st, endPeriod = en, weeks = weeks
                )
                warnConflict(wd, st, en, null, "已添加课程")
            } else {
                CourseStore.updateCourse(
                    ctx, c.id, name,
                    teacher = v.getOrNull(1).orEmpty(),
                    location = v.getOrNull(2).orEmpty(),
                    weekday = wd, startPeriod = st, endPeriod = en, weeks = weeks
                )
                warnConflict(wd, st, en, c.id, "已保存修改")
            }
            refresh()
        }
    }

    /** 保存后提示与其它课程同周几同节次的时间重叠。 */
    private fun warnConflict(wd: Int, st: Int, en: Int, excludeId: Long?, okText: String) {
        val clash = CourseStore.courses(ctx).firstOrNull {
            it.id != excludeId && it.weekday == wd && it.startPeriod <= en && it.endPeriod >= st
        }
        if (clash != null) {
            ctx.toast("$okText；注意「${clash.name}」也在周${arrayOf("", "一", "二", "三", "四", "五", "六", "日")[wd]} 第${clash.startPeriod}-${clash.endPeriod}节")
        } else {
            ctx.toast(okText)
        }
    }

    /** 课表里直接录一个活动（写进本周格子）；与课程/活动撞时间就弹三选一。 */
    private fun addActivity() {
        val today = CourseStore.today()
        inputForm(
            ctx, "录入活动（直接进课表）",
            listOf(
                "标题" to "",
                "日期（yyyy-MM-dd）" to today,
                "开始（HH:mm）" to "",
                "结束（HH:mm）" to "",
                "地点" to "",
                "备注" to ""
            ),
            multiline = false
        ) { v ->
            val title = v.getOrNull(0).orEmpty().trim().ifBlank { "未命名活动" }
            val rawDate = v.getOrNull(1).orEmpty().trim()
            val st = v.getOrNull(2).orEmpty().trim()
            val en = v.getOrNull(3).orEmpty().trim()
            val parsed = ScheduleLogic.parseDate(rawDate)
            if (parsed == null) {
                ctx.toast("日期格式应为 yyyy-MM-dd")
                return@inputForm
            }
            if (!validTime(st) || !validTime(en)) {
                ctx.toast("时间格式应为 HH:mm")
                return@inputForm
            }
            if (st.isNotBlank() && en.isNotBlank() && ScheduleLogic.toMinutes(en) <= ScheduleLogic.toMinutes(st)) {
                ctx.toast("结束时间要晚于开始时间")
                return@inputForm
            }
            val draft = com.campus.arnav.data.ActivityDraft(
                title = title,
                date = ScheduleLogic.formatDate(parsed),
                startTime = st,
                endTime = en,
                location = v.getOrNull(4).orEmpty().trim(),
                organizer = "",
                note = v.getOrNull(5).orEmpty().trim()
            )
            ActivityRecord.record(ctx, draft, source = "manual") { r ->
                refresh()
                ctx.toast(
                    when (r) {
                        ActivityRecord.KEEP_OLD -> "已保留原课程表日程，新活动没有录入"
                        ActivityRecord.NEW_ONLY -> "已只保存最新日程（冲突课程当天停课）"
                        else -> "已录入课表"
                    }
                )
            }
        }
    }

    private fun showActivity(a: com.campus.arnav.data.Activity) {
        AlertDialog.Builder(ctx)
            .setTitle(a.title)
            .setMessage(
                buildString {
                    append("${a.date}  ${a.startTime}-${a.endTime}\n")
                    if (a.location.isNotBlank()) append("地点：${a.location}\n")
                    if (a.note.isNotBlank()) append(a.note)
                }
            )
            .setPositiveButton("编辑") { _, _ -> editActivity(a) }
            .setNeutralButton("加入日历") { _, _ -> addToCalendar(a) }
            .setNegativeButton("删除") { _, _ ->
                CourseStore.deleteActivity(ctx, a.id)
                refresh()
            }
            .show()
    }

    /** 把活动写入系统日历：不申请权限，唤起日历 App 的“新建事件”界面由用户确认。 */
    private fun addToCalendar(a: com.campus.arnav.data.Activity) {
        val begin = eventMillis(a.date, a.startTime)
        val end = eventMillis(a.date, a.endTime)
        if (begin == null || end == null) {
            ctx.toast("活动日期或时间格式不对，无法加入日历")
            return
        }
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = android.provider.CalendarContract.Events.CONTENT_URI
            putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, end)
            putExtra(android.provider.CalendarContract.EXTRA_EVENT_ALL_DAY, false)
            putExtra(android.provider.CalendarContract.Events.TITLE, a.title)
            putExtra(android.provider.CalendarContract.Events.EVENT_LOCATION, a.location)
            putExtra(android.provider.CalendarContract.Events.DESCRIPTION, a.note)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            ctx.toast("没有找到可用的日历应用")
        }
    }

    private fun eventMillis(date: String, time: String): Long? {
        val d = ScheduleLogic.parseDate(date) ?: return null
        val hm = time.split(":")
        val h = hm.getOrNull(0)?.toIntOrNull() ?: return null
        val m = hm.getOrNull(1)?.toIntOrNull() ?: 0
        if (h !in 0..23 || m !in 0..59) return null
        return java.util.Calendar.getInstance().apply {
            this.time = d
            set(java.util.Calendar.HOUR_OF_DAY, h)
            set(java.util.Calendar.MINUTE, m)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun editActivity(a: com.campus.arnav.data.Activity) {
        inputForm(
            ctx, "编辑活动",
            listOf(
                "标题" to a.title,
                "日期（yyyy-MM-dd）" to a.date,
                "开始（HH:mm）" to a.startTime,
                "结束（HH:mm）" to a.endTime,
                "地点" to a.location,
                "备注" to a.note
            ),
            multiline = false
        ) { v ->
            val title = v.getOrNull(0).orEmpty().trim().ifBlank { "未命名活动" }
            val rawDate = v.getOrNull(1).orEmpty().trim()
            val st = v.getOrNull(2).orEmpty().trim()
            val en = v.getOrNull(3).orEmpty().trim()
            val parsed = ScheduleLogic.parseDate(rawDate)
            if (parsed == null) {
                ctx.toast("日期格式应为 yyyy-MM-dd")
                return@inputForm
            }
            if (!validTime(st) || !validTime(en) || (st.isNotBlank() && en.isNotBlank() && ScheduleLogic.toMinutes(en) <= ScheduleLogic.toMinutes(st))) {
                ctx.toast("时间格式应为 HH:mm，且结束晚于开始")
                return@inputForm
            }
            val date = ScheduleLogic.formatDate(parsed)
            val draft = com.campus.arnav.data.ActivityDraft(
                title = title, date = date, startTime = st, endTime = en,
                location = v.getOrNull(4).orEmpty().trim(),
                organizer = "",
                note = v.getOrNull(5).orEmpty().trim()
            )
            CourseStore.updateActivity(ctx, a.id, draft, ActivityRecord.hasClash(ctx, date, st, en, excludeId = a.id))
            refresh()
            ctx.toast("已保存修改")
        }
    }

    private fun validTime(s: String): Boolean {
        if (s.isBlank()) return true
        return ScheduleLogic.validClock(s)
    }

    /** 活动与当天课程是否时间冲突（分钟区间比较，课程按节次时间换算）。 */
    private fun hasConflict(date: String, start: String, end: String): Boolean {
        if (date.isBlank() || start.isBlank() || end.isBlank()) return false
        if (ScheduleLogic.parseDate(date) == null) return false
        val s = ScheduleLogic.toMinutes(start)
        val e = ScheduleLogic.toMinutes(end)
        if (e <= s) return false
        val wk = ScheduleLogic.weekOf(date)
        val wd = ScheduleLogic.weekdayOf(date)
        val skips = CourseStore.skipsFor(ctx, date)
        return CourseStore.courses(ctx).any { c ->
            c.weekday == wd && c.id !in skips && ScheduleLogic.weekMatches(c.weeks, wk) &&
                run {
                    val (cs, ce) = ScheduleLogic.courseRange(c, date)
                    s < ce && e > cs
                }
        }
    }

    /** 导出当前课表 + 活动为 JSON（可再粘贴回「导入课表 → JSON」），走系统分享面板。 */
    private fun exportShare() {
        val courses = CourseStore.courses(ctx)
        val acts = CourseStore.activities(ctx)
        if (courses.isEmpty() && acts.isEmpty()) {
            ctx.toast("课表和活动都是空的，没有可导出的数据")
            return
        }
        val root = CourseStore.backupJson(courses, acts)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "科文智嘉课表备份")
            putExtra(Intent.EXTRA_TEXT, root.toString(2))
        }
        ctx.startActivity(Intent.createChooser(send, "导出 / 分享课表与活动"))
    }

    private fun importDialog() {
        val modes = listOf("教务/表格文本（每行：课程|周几|起节|止节|周次|地点）", "JSON 课表", "取消")
        choose(ctx, "导入课表", modes) { which ->
            when (which) {
                0 -> inputForm(
                    ctx, "粘贴课表文本",
                    listOf("每行一条，例：高等数学|一|1|2|1-16|格物楼101" to ""),
                    multiline = true
                ) { v ->
                    val n = CourseStore.importLines(ctx, v.firstOrNull().orEmpty())
                    refresh()
                    ctx.toast(if (n > 0) "已导入 $n 门课程" else "没有解析出课程，检查格式")
                }
                1 -> inputForm(
                    ctx, "粘贴 JSON",
                    listOf("字段：name/weekday/start/end/weeks/location；可含 activities" to ""),
                    multiline = true
                ) { v ->
                    val imp = CourseStore.importJson(ctx, v.firstOrNull().orEmpty())
                    refresh()
                    when {
                        imp.courses < 0 -> ctx.toast("JSON 格式不对，检查是否完整复制")
                        imp.courses == 0 && imp.activities == 0 ->
                            ctx.toast("没有可导入的课程或活动")
                        else -> ctx.toast("已导入 ${imp.courses} 门课程、${imp.activities} 个活动")
                    }
                }
            }
        }
    }
}
