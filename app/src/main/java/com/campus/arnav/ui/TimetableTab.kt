package com.campus.arnav.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.campus.arnav.LoginActivity
import com.campus.arnav.R
import com.campus.arnav.data.Activity
import com.campus.arnav.data.Course
import com.campus.arnav.core.Reminder
import com.campus.arnav.data.CourseStore
import com.campus.arnav.data.ScheduleLogic
import java.util.Calendar
import java.util.Date

/**
 * ① 课程表：顶部「周 / 日」两种视图。
 *
 * - 周：原来的 7×12 网格 + 操作按钮；底部的「今日活动」「接下来 7 天」改成日视图同款卡片。
 * - 日：日期大图卡 + 当天课程/活动卡片列表（手写体），上完的淡化加删除线。
 */
class TimetableTab(private val ctx: Context) : LinearLayout(ctx) {

    private val grid = TimetableGridView(ctx).apply {
        contentDescription = "课程表：7 天 × 12 节；点课程块查看详情"
    }
    private val weekLabel = textView(ctx, "", 13f, ctx.color(R.color.text_secondary))
    private val subTitle = textView(ctx, "", 12f, ctx.color(R.color.text_secondary))
    private val todayBox = LinearLayout(ctx)
    private val upcomingBox = LinearLayout(ctx)
    private val weekBox = LinearLayout(ctx)
    /** 顶部「当前是示例课表」提示条：全部是示例数据时显示。 */
    private val demoTip = LinearLayout(ctx)

    private val dayNavLabel = handText(ctx, "", 15f, ctx.color(R.color.text_primary)).apply {
        gravity = Gravity.CENTER
    }
    private val heroHolder = FrameLayout(ctx)
    private val dayList = LinearLayout(ctx)
    private val dayBox = LinearLayout(ctx)

    private val segWeek = TextView(ctx)
    private val segDay = TextView(ctx)

    private var shownWeek = ScheduleLogic.currentWeek()
    private var shownDate = CourseStore.today()
    private var mode = 0

    init {
        orientation = VERTICAL
        // 透明底：露出 MainShell 的粉蓝渐变（四栏保持一致）
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(8))

        addView(buildHeader())

        // 示例课表提示条：放在「周 / 日」两视图共用的顶部，切换视图不影响
        buildDemoTip()
        addView(demoTip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = ctx.dp(8) })

        buildWeekBox()
        addView(weekBox)

        buildDayBox()
        addView(dayBox)

        grid.onCourseTap = { showCourse(it) }
        grid.onActivityTap = { showActivity(it) }
        shownWeek = ScheduleLogic.currentWeek()
        setMode(0)
        refresh()
    }

    // ---------------- 顶部：标题 + 周/日 切换 ----------------

    private fun buildHeader(): LinearLayout {
        val header = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            handText(ctx, "课程表", 26f, ctx.color(R.color.text_primary)),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val seg = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(18).toFloat()
                setColor(Color.WHITE)
                setStroke(ctx.dp(1), ctx.color(R.color.border))
            }
            elevation = ctx.dp(1).toFloat()
            setPadding(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
        }
        for ((label, idx) in listOf("周" to 0, "日" to 1)) {
            val tv = if (idx == 0) segWeek else segDay
            tv.text = label
            tv.textSize = 14f
            tv.gravity = Gravity.CENTER
            tv.setOnClickListener { setMode(idx) }
            seg.addView(tv, LinearLayout.LayoutParams(ctx.dp(46), ctx.dp(30)))
        }
        header.addView(seg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return header
    }

    /** 全部是示例课表时的一条浅粉提示：可以一键去登录，也可以点右上「清空」自己录。 */
    private fun buildDemoTip() {
        demoTip.orientation = HORIZONTAL
        demoTip.gravity = Gravity.CENTER_VERTICAL
        demoTip.background = rounded(ctx, ctx.color(R.color.accent_soft), 12)
        demoTip.setPadding(ctx.dp(10), ctx.dp(7), ctx.dp(10), ctx.dp(7))
        demoTip.visibility = View.GONE
        demoTip.addView(
            textView(ctx, "当前是示例课表 · 登录教务自动替换为你的真实课表", 11f, ctx.color(R.color.accent_ink)),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val go = textView(ctx, "去登录", 11f, ctx.color(R.color.accent_ink), bold = true).apply {
            background = rounded(ctx, Color.WHITE, 8)
            setPadding(ctx.dp(9), ctx.dp(3), ctx.dp(9), ctx.dp(3))
            setOnClickListener { openLogin() }
        }
        demoTip.addView(go, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = ctx.dp(8) })
    }

    /** 打开登录页（可选动作）：登录成功后由 MainShell 在 onResume 里刷新课表。 */
    private fun openLogin() {
        val c = ctx
        try {
            val i = Intent(c, LoginActivity::class.java)
            if (c !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(i)
        } catch (e: Exception) {
            ctx.toast("打不开登录页：${e.message}")
        }
    }

    private fun setMode(m: Int) {
        mode = m
        val soft = Color.parseColor("#FFE3EE")
        for ((tv, idx) in listOf(segWeek to 0, segDay to 1)) {
            tv.background = rounded(ctx, if (m == idx) soft else Color.TRANSPARENT, 16)
            tv.setTextColor(if (m == idx) ctx.color(R.color.accent_ink) else ctx.color(R.color.text_secondary))
        }
        weekBox.visibility = if (m == 0) View.VISIBLE else View.GONE
        dayBox.visibility = if (m == 1) View.VISIBLE else View.GONE
    }

    // ---------------- 周视图 ----------------

    private fun buildWeekBox() {
        weekBox.orientation = VERTICAL
        weekBox.addView(subTitle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = ctx.dp(2) })

        // 周次切换
        val weekRow = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ctx.dp(8), 0, ctx.dp(4))
        }
        val prev = button(ctx, "◀", ctx.color(R.color.bg_card), ctx.color(R.color.accent_ink)).apply {
            setOnClickListener { switchWeek(-1) }
        }
        val next = button(ctx, "▶", ctx.color(R.color.bg_card), ctx.color(R.color.accent_ink)).apply {
            setOnClickListener { switchWeek(1) }
        }
        val now = button(ctx, "本周", ctx.color(R.color.bg_card), ctx.color(R.color.text_primary)).apply {
            setOnClickListener {
                shownWeek = ScheduleLogic.currentWeek()
                refresh()
            }
        }
        weekRow.addView(prev, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(36)))
        weekRow.addView(weekLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        weekRow.addView(next, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(36)))
        weekRow.addView(now, LinearLayout.LayoutParams(ctx.dp(64), ctx.dp(36)).apply { leftMargin = ctx.dp(6) })
        weekBox.addView(weekRow)

        // 网格
        val hScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(grid)
        }
        weekBox.addView(hScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 操作（两行，避免窄屏挤成多行）
        val ops = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            setPadding(0, ctx.dp(8), 0, ctx.dp(4))
        }
        ops.addView(button(ctx, "导入课表", ctx.color(R.color.bg_card), ctx.color(R.color.accent_ink)).apply {
            setOnClickListener { importDialog() }
        }, LinearLayout.LayoutParams(0, ctx.dp(40), 1f))
        ops.addView(button(ctx, "＋课程", ctx.color(R.color.bg_card), ctx.color(R.color.text_primary)).apply {
            setOnClickListener { editCourse(null) }
        }, LinearLayout.LayoutParams(0, ctx.dp(40), 1f).apply { leftMargin = ctx.dp(6) })
        ops.addView(button(ctx, "示例课表", ctx.color(R.color.bg_card), ctx.color(R.color.text_primary)).apply {
            setOnClickListener {
                // 语义 = 「恢复示例课表」：课表空的时候才铺示例，已有课程时先请人清空
                if (CourseStore.courses(ctx).isNotEmpty()) {
                    ctx.toast("课表里已有课程，先点「清空」再恢复示例课表")
                    return@setOnClickListener
                }
                CourseStore.seedDemo(ctx, force = true)
                refresh()
                ctx.toast("已恢复示例课表，可随时清空")
            }
        }, LinearLayout.LayoutParams(0, ctx.dp(40), 1f).apply { leftMargin = ctx.dp(6) })
        weekBox.addView(ops)

        val ops2 = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            setPadding(0, ctx.dp(4), 0, ctx.dp(4))
        }
        ops2.addView(button(ctx, "导出 / 分享", ctx.color(R.color.bg_card), ctx.color(R.color.text_primary)).apply {
            setOnClickListener { exportShare() }
        }, LinearLayout.LayoutParams(0, ctx.dp(40), 1f))
        ops2.addView(button(ctx, "＋活动", ctx.color(R.color.bg_card), ctx.color(R.color.accent_ink)).apply {
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
        weekBox.addView(ops2)

        weekBox.addView(sectionTitle("今日活动"))
        todayBox.orientation = VERTICAL
        weekBox.addView(todayBox)

        weekBox.addView(sectionTitle("接下来 7 天"))
        upcomingBox.orientation = VERTICAL
        weekBox.addView(upcomingBox)
    }

    // ---------------- 日视图 ----------------

    private fun buildDayBox() {
        dayBox.orientation = VERTICAL

        val navRow = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ctx.dp(8), 0, ctx.dp(4))
        }
        navRow.addView(button(ctx, "◀", ctx.color(R.color.bg_card), ctx.color(R.color.accent_ink)).apply {
            setOnClickListener { shiftDay(-1) }
        }, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(36)))
        navRow.addView(dayNavLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        navRow.addView(button(ctx, "▶", ctx.color(R.color.bg_card), ctx.color(R.color.accent_ink)).apply {
            setOnClickListener { shiftDay(1) }
        }, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(36)))
        navRow.addView(button(ctx, "今天", ctx.color(R.color.bg_card), ctx.color(R.color.text_primary)).apply {
            setOnClickListener {
                shownDate = CourseStore.today()
                refresh()
            }
        }, LinearLayout.LayoutParams(ctx.dp(64), ctx.dp(36)).apply { leftMargin = ctx.dp(6) })
        dayBox.addView(navRow)

        heroHolder.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(196)
        ).apply {
            topMargin = ctx.dp(4)
            bottomMargin = ctx.dp(12)
        }
        dayBox.addView(heroHolder)

        dayList.orientation = VERTICAL
        dayBox.addView(dayList, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun sectionTitle(text: String): TextView = handText(ctx, text, 17f, ctx.color(R.color.text_primary)).apply {
        setPadding(0, ctx.dp(12), 0, ctx.dp(6))
    }

    // ---------------- 刷新 ----------------

    private fun switchWeek(d: Int) {
        shownWeek = (shownWeek + d).coerceIn(1, ScheduleLogic.totalWeeks())
        refresh()
    }

    private fun shiftDay(d: Int) {
        val base = ScheduleLogic.parseDate(shownDate) ?: Date()
        val cal = Calendar.getInstance().apply {
            time = base
            add(Calendar.DAY_OF_YEAR, d)
        }
        shownDate = ScheduleLogic.formatDate(cal.time)
        refresh()
    }

    fun refresh() {
        // 课表变化后顺手把提醒重排一遍（内部 20 秒节流，不会频繁排期）
        val courses = CourseStore.courses(ctx)
        val activities = CourseStore.activities(ctx)

        grid.courses = courses
        grid.week = shownWeek
        val skips = HashSet<Long>()
        for (wd in 1..7) skips.addAll(CourseStore.skipsFor(ctx, ScheduleLogic.dateOf(shownWeek, wd)))
        grid.skips = skips
        // 本周活动也画进课表格子（虚线块）
        val weekStart = ScheduleLogic.dateOf(shownWeek, 1)
        val weekEnd = ScheduleLogic.dateOf(shownWeek, 7)
        grid.activities = activities
            .filter { it.date >= weekStart && it.date <= weekEnd }
            .sortedBy { it.date + it.startTime }
        // 节次时间用知识库《作息时间与门禁》的科文作息：8:15 开始、全年一套、不分冬夏
        weekLabel.text = ScheduleLogic.weekLabel(shownWeek)
        subTitle.text = "${ScheduleLogic.semesterName()} · ${ScheduleLogic.totalWeeks()} 周 · 本周第 ${ScheduleLogic.currentWeek()} 周 · 科文作息 8:15—20:40"

        // 整张课表都是示例数据（user 还没登录、也没自己录课）时，顶部提示可以一键去登录
        val allDemo = courses.isNotEmpty() && courses.all { it.source == "demo" }
        demoTip.visibility = if (allDemo) View.VISIBLE else View.GONE

        fillWeekSections(activities)
        fillDay(courses, activities)
        Reminder.scheduleAsync(ctx)
    }

    /** 周视图底部两栏：样式与日视图卡片一致。 */
    private fun fillWeekSections(activities: List<Activity>) {
        val today = CourseStore.today()

        todayBox.removeAllViews()
        val acts = activities.filter { it.date == today }.sortedBy { it.startTime }
        if (acts.isEmpty()) {
            todayBox.addView(handText(ctx, "今天没有录入的活动。", 14f, ctx.color(R.color.text_secondary)))
        }
        for (a in acts) {
            todayBox.addView(
                scheduleCard(
                    ctx,
                    dotColor(a),
                    titleText(a),
                    "${a.startTime}-${a.endTime}",
                    startHint(a.date, a.endTime, a.startTime),
                    a.location,
                    isPassed(a.date, a.endTime)
                ) { showActivity(a) },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = ctx.dp(8) }
            )
        }

        upcomingBox.removeAllViews()
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, 7)
        val end = fmt.format(cal.time)
        val upcoming = activities
            .filter { it.date > today && it.date <= end }
            .sortedBy { it.date + it.startTime }
        if (upcoming.isEmpty()) {
            upcomingBox.addView(handText(ctx, "未来一周没有活动。", 14f, ctx.color(R.color.text_secondary)))
        }
        for (a in upcoming) {
            upcomingBox.addView(
                scheduleCard(
                    ctx,
                    dotColor(a),
                    titleText(a),
                    "${dayWord(a.date)}  ${a.startTime}-${a.endTime}",
                    "",
                    a.location,
                    false
                ) { showActivity(a) },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = ctx.dp(8) }
            )
        }
    }

    /** 日视图：日期大图卡 + 当天的课程 / 活动卡片。 */
    private fun fillDay(courses: List<Course>, activities: List<Activity>) {
        val today = CourseStore.today()
        val wd = ScheduleLogic.weekdayOf(shownDate)
        val wk = ScheduleLogic.weekOf(shownDate)
        dayNavLabel.text = buildString {
            append(monthDay(shownDate))
            append("  ")
            append(weekdayText(shownDate))
            if (shownDate == today) append("  · 今天")
        }

        val skips = CourseStore.skipsFor(ctx, shownDate)
        class Item(
            val title: String,
            val start: String,
            val end: String,
            val place: String,
            val color: Int,
            val course: Course?,
            val activity: Activity?
        )

        val items = ArrayList<Item>()
        for (c in courses) {
            if (c.weekday != wd) continue
            if (c.id in skips) continue
            if (!ScheduleLogic.weekMatches(c.weeks, wk)) continue
            val begin = ScheduleLogic.periodTime(c.startPeriod, shownDate)
            val finish = ScheduleLogic.periodTime(c.endPeriod, shownDate)
            if (begin.isBlank() || finish.isBlank()) continue
            items.add(
                Item(c.name, begin.substringBefore('-'), finish.substringAfter('-'), c.location, c.color, c, null)
            )
        }
        for (a in activities) {
            if (a.date != shownDate) continue
            items.add(Item(a.title, a.startTime, a.endTime, a.location, dotColor(a), null, a))
        }
        items.sortBy { ScheduleLogic.toMinutes(it.start) }

        heroHolder.removeAllViews()
        heroHolder.addView(
            buildDayHero(
                ctx,
                shownDate,
                weekdayText(shownDate),
                wk,
                items.size,
                shownDate == today,
                captionFor(shownDate),
                photoFor(shownDate)
            ),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        dayList.removeAllViews()
        if (items.isEmpty()) {
            dayList.addView(
                dayCard(ctx).apply {
                    addView(
                        handText(
                            ctx,
                            if (shownDate == today) "今天没有课，也没有活动。" else "这一天没有安排。",
                            15f,
                            ctx.color(R.color.text_secondary)
                        )
                    )
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = ctx.dp(8) }
            )
        }
        for (it in items) {
            val card = scheduleCard(
                ctx,
                it.color,
                it.title,
                "${it.start}-${it.end}",
                startHint(shownDate, it.end, it.start),
                it.place,
                isPassed(shownDate, it.end),
                demo = it.course?.source == "demo"
            ) {
                val c = it.course
                if (c != null) showCourse(c) else it.activity?.let { a -> showActivity(a) }
            }
            dayList.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = ctx.dp(8) })
        }
    }

    // ---------------- 小工具 ----------------

    private fun dotColor(a: Activity): Int =
        if (a.conflict) Color.parseColor("#E8447A") else ctx.color(R.color.accent)

    private fun titleText(a: Activity): String = (if (a.conflict) "⚠ " else "") + a.title

    private fun nowMinutes(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /** 上完了没有：过去的日子算上完，今天按结束时间比。 */
    private fun isPassed(date: String, end: String): Boolean {
        val today = CourseStore.today()
        if (date < today) return true
        if (date > today) return false
        val e = ScheduleLogic.toMinutes(end)
        return e > 0 && nowMinutes() > e
    }

    /** 时间行后半段：进行中 / 几分钟后开始 / 空。 */
    private fun startHint(date: String, end: String, start: String): String {
        if (date != CourseStore.today()) return ""
        val now = nowMinutes()
        val s = ScheduleLogic.toMinutes(start)
        val e = ScheduleLogic.toMinutes(end)
        if (s <= 0) return ""
        return when {
            now in s..e -> "进行中"
            now < s -> {
                val m = s - now
                when {
                    m < 1 -> "马上开始"
                    m < 60 -> "${m}分钟后开始"
                    else -> {
                        val h = m / 60
                        val mm = m % 60
                        if (mm == 0) "${h}小时后开始" else "${h}小时${mm}分钟后开始"
                    }
                }
            }
            else -> ""
        }
    }

    private fun weekdayText(date: String): String =
        "星期" + ScheduleLogic.weekdayName(ScheduleLogic.weekdayOf(date))

    private fun weekdayShort(date: String): String =
        "周" + ScheduleLogic.weekdayName(ScheduleLogic.weekdayOf(date))

    private fun monthDay(date: String): String =
        date.substring(5, 7).trimStart('0') + "月" + date.substring(8, 10).trimStart('0') + "日"

    private fun dayWord(date: String): String {
        val today = CourseStore.today()
        return when (date) {
            today -> "今天"
            addDays(today, 1) -> "明天"
            addDays(today, 2) -> "后天"
            else -> monthDay(date) + " " + weekdayShort(date)
        }
    }

    private fun addDays(date: String, d: Int): String {
        val base = ScheduleLogic.parseDate(date) ?: return date
        val cal = Calendar.getInstance().apply {
            time = base
            add(Calendar.DAY_OF_YEAR, d)
        }
        return ScheduleLogic.formatDate(cal.time)
    }

    private fun photoIndex(date: String): Int {
        val d = ScheduleLogic.parseDate(date) ?: Date()
        val cal = Calendar.getInstance().apply { time = d }
        return cal.get(Calendar.DAY_OF_YEAR) % 3
    }

    private fun photoFor(date: String): Int = intArrayOf(
        R.drawable.hero_lake, R.drawable.hero_fjord, R.drawable.hero_fall
    )[photoIndex(date)]

    private fun captionFor(date: String): String = arrayOf(
        "潘安湖畔伴清风", "云影天光共一色", "秋林染尽匠心楼"
    )[photoIndex(date)]

    // ---------------- 下面这些是原有的弹窗 / 导入导出逻辑，未改动 ----------------

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
        AlertDialog.Builder(ctx, R.style.Theme_CampusAr_Dialog)
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

    private fun showActivity(a: Activity) {
        AlertDialog.Builder(ctx, R.style.Theme_CampusAr_Dialog)
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
    private fun addToCalendar(a: Activity) {
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
        return Calendar.getInstance().apply {
            this.time = d
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun editActivity(a: Activity) {
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
