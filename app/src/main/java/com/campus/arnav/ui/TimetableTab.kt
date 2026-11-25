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
import org.json.JSONArray
import org.json.JSONObject

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
        weekLabel.text = ScheduleLogic.weekLabel(shownWeek)
        subTitle.text = "${ScheduleLogic.semesterName()} · ${ScheduleLogic.totalWeeks()} 周 · 本周第 ${ScheduleLogic.currentWeek()} 周"

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
                    if (a.conflict) Color.parseColor("#FFB86B") else ctx.color(R.color.text_primary),
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
            append("节次：${ScheduleLogic.periodTime(c.startPeriod, ScheduleLogic.dateOf(wk, c.weekday))} - ${ScheduleLogic.periodTime(c.endPeriod, ScheduleLogic.dateOf(wk, c.weekday))}\n")
            append("周次：${c.weeks}\n")
            append("来源：${c.source}")
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
                ctx.toast("已添加课程")
            } else {
                CourseStore.updateCourse(
                    ctx, c.id, name,
                    teacher = v.getOrNull(1).orEmpty(),
                    location = v.getOrNull(2).orEmpty(),
                    weekday = wd, startPeriod = st, endPeriod = en, weeks = weeks
                )
                ctx.toast("已保存修改")
            }
            refresh()
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
            .setNeutralButton("删除") { _, _ ->
                CourseStore.deleteActivity(ctx, a.id)
                refresh()
            }
            .setNegativeButton("关闭", null)
            .show()
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
            CourseStore.updateActivity(ctx, a.id, draft, hasConflict(date, st, en))
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

    /** 导出当前课表为 JSON（可再粘贴回「导入课表 → JSON」），走系统分享面板。 */
    private fun exportShare() {
        val courses = CourseStore.courses(ctx)
        if (courses.isEmpty()) {
            ctx.toast("课表是空的，没有可导出的课程")
            return
        }
        val arr = JSONArray()
        for (c in courses) {
            arr.put(JSONObject().apply {
                put("name", c.name)
                put("teacher", c.teacher)
                put("location", c.location)
                put("weekday", c.weekday)
                put("start", c.startPeriod)
                put("end", c.endPeriod)
                put("weeks", c.weeks)
            })
        }
        val root = JSONObject().apply {
            put("app", "科文智嘉")
            put("version", 1)
            put("semesterName", ScheduleLogic.semesterName())
            put("semesterStart", ScheduleLogic.semesterStartText())
            put("totalWeeks", ScheduleLogic.totalWeeks())
            put("courses", arr)
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "科文智嘉课表备份")
            putExtra(Intent.EXTRA_TEXT, root.toString(2))
        }
        ctx.startActivity(Intent.createChooser(send, "导出 / 分享课表"))
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
                    listOf("字段：name/weekday/start/end/weeks/location" to ""),
                    multiline = true
                ) { v ->
                    val n = CourseStore.importJson(ctx, v.firstOrNull().orEmpty())
                    refresh()
                    ctx.toast(if (n >= 0) "已导入 $n 门课程" else "JSON 格式不对")
                }
            }
        }
    }
}
