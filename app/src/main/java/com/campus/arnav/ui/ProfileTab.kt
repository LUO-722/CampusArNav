package com.campus.arnav.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.campus.arnav.R
import com.campus.arnav.data.AuthStore
import com.campus.arnav.data.CourseStore
import com.campus.arnav.data.ProfileStore
import com.campus.arnav.data.ScheduleLogic
import com.campus.arnav.net.JwxtClient

/**
 * 第五栏「我的」：学籍卡 + 本机统计 + 本周课程 + 教务数据（考试/成绩）+ 快捷入口。
 *
 * 排版按「人 → 这周 → 教务给的结果 → 设置」从近到远铺开，一眼看清自己是谁、这周什么课、
 * 教务那边还搬来了什么。学籍信息来自本机资料，同步教务时自动补齐。
 */
class ProfileTab(
    private val ctx: Context,
    private val onOpenSettings: () -> Unit,
    private val onOpenTimetable: () -> Unit
) : LinearLayout(ctx) {

    init {
        orientation = VERTICAL
        setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(24))
    }

    fun refresh() {
        removeAllViews()
        val p = ProfileStore.get(ctx)
        val st = ProfileStore.stats(ctx)

        header(st)
        idCard(p, st)
        statRow(st)
        weekCard(st)
        jwCard(st)
        quickCard()
        footer()
    }

    // ① 标题
    private fun header(st: ProfileStore.Stats) {
        addView(textView(ctx, "我的", 26f, ctx.color(R.color.text_primary), bold = true))
        addView(textView(ctx,
            "${st.semesterName} · 第 ${st.currentWeek} / ${st.totalWeeks} 周",
            12f, ctx.color(R.color.accent)).apply { setPadding(0, ctx.dp(2), 0, 0) })
    }

    // ② 学籍卡
    private fun idCard(p: ProfileStore.Profile, st: ProfileStore.Stats) {
        val card = card(ctx, 14)
        val head = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val avatar = TextView(ctx).apply {
            text = p.initial
            textSize = 24f
            setTextColor(Color.parseColor("#FFFFFF"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ctx.color(R.color.accent))
            }
        }
        head.addView(avatar, LinearLayout.LayoutParams(ctx.dp(58), ctx.dp(58)))
        val texts = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setPadding(ctx.dp(12), 0, 0, 0)
        }
        texts.addView(textView(ctx, p.nick, 18f, ctx.color(R.color.text_primary), bold = true))
        texts.addView(textView(ctx, subLine(p), 12f, ctx.color(R.color.text_secondary)))
        head.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(button(ctx, "编辑", ctx.color(R.color.bg_dark), ctx.color(R.color.text_primary)).apply {
            textSize = 12f
            setOnClickListener { editProfile() }
        }, LinearLayout.LayoutParams(ctx.dp(58), ctx.dp(36)))
        card.addView(head)

        // 学籍六项两张一行，像学生证的资料面
        val rows = listOf(
            listOf("学号" to p.sid.ifBlank { "未填" }, "性别" to p.gender.ifBlank { "—" }),
            listOf("院系" to p.college.ifBlank { "未同步" }, "专业" to p.major.ifBlank { "—" }),
            listOf("班级" to p.clazz.ifBlank { "—" }, "学制" to p.studyLen.ifBlank { "—" }),
            listOf("入学" to p.enterDate.ifBlank { "—" }, "宿舍" to p.dorm.ifBlank { "—" })
        )
        for (pair in rows) {
            val line = LinearLayout(ctx).apply {
                orientation = HORIZONTAL
                setPadding(0, ctx.dp(8), 0, 0)
            }
            for ((i, kv) in pair.withIndex()) {
                line.addView(kvBlock(kv.first, kv.second), LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { if (i > 0) leftMargin = ctx.dp(10) })
            }
            card.addView(line)
        }
        if (p.sid.isBlank() && p.college.isBlank()) {
            card.addView(textView(ctx,
                "登录教务系统同步一次，姓名、学号、院系、专业、班级会自动补齐；也可以点「编辑」自己填。",
                11f, ctx.color(R.color.text_secondary)).apply { setPadding(0, ctx.dp(10), 0, 0) })
        }
        addView(card, cardLp())
    }

    // ③ 统计
    private fun statRow(st: ProfileStore.Stats) {
        val row = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        row.addView(statCell("课程", st.courseCount.toString()), weighted())
        row.addView(statCell("本周", st.weekCourseCount.toString()), weighted())
        row.addView(statCell("考试", st.examCount.toString()), weighted())
        row.addView(statCell("成绩", st.gradeCount.toString()), weighted())
        addView(row, cardLp())
    }

    // ④ 本周课程（按天分组）
    private fun weekCard(st: ProfileStore.Stats) {
        val card = card(ctx, 14)
        card.addView(titleRow("本周课程", if (st.weekCourses.isEmpty()) "" else "${st.weekCourses.size} 节"))
        if (st.weekCourses.isEmpty()) {
            card.addView(textView(ctx,
                "本周没有课（或课表还没同步）。到「课程表」同步教务，或导入课表。",
                12f, ctx.color(R.color.text_secondary)).apply { setPadding(0, ctx.dp(8), 0, 0) })
        } else {
            var lastDay = -1
            for (c in st.weekCourses) {
                if (c.weekday != lastDay) {
                    lastDay = c.weekday
                    card.addView(textView(ctx, "周" + ScheduleLogic.weekdayName(c.weekday),
                        12f, ctx.color(R.color.accent), bold = true).apply {
                        setPadding(0, ctx.dp(10), 0, 0)
                    })
                }
                val time = ScheduleLogic.periodTime(c.startPeriod, "").substringBefore("-")
                val periodLabel = if (c.startPeriod == c.endPeriod) {
                    "第 ${c.startPeriod} 节"
                } else {
                    "第 ${c.startPeriod}-${c.endPeriod} 节"
                }
                card.addView(textView(ctx, "$time  $periodLabel  ${c.name}",
                    13f, ctx.color(R.color.text_primary)).apply { setPadding(0, ctx.dp(6), 0, 0) })
                val sub = listOf(c.location, c.teacher).filter { it.isNotBlank() }.joinToString(" · ")
                if (sub.isNotBlank()) {
                    card.addView(textView(ctx, sub, 11f, ctx.color(R.color.text_secondary)))
                }
            }
        }
        addView(card, cardLp())
    }

    // ⑤ 教务数据
    private fun jwCard(st: ProfileStore.Stats) {
        val card = card(ctx, 14)
        card.addView(titleRow("教务数据", ""))
        val session = AuthStore.session(ctx)
        val line = if (!session.loggedIn) {
            "还没登录教务系统"
        } else {
            session.modeLabel() + " · " + AuthStore.lastSyncText(ctx)
        }
        card.addView(textView(ctx, line, 11f, ctx.color(R.color.text_secondary))
            .apply { setPadding(0, ctx.dp(4), 0, 0) })

        val exams = try {
            CourseStore.exams(ctx)
        } catch (e: Exception) {
            emptyList()
        }
        val grades = try {
            CourseStore.grades(ctx)
        } catch (e: Exception) {
            emptyList()
        }

        // 考试
        card.addView(textView(ctx, "考试安排（${exams.size}）", 13f, ctx.color(R.color.text_primary), bold = true)
            .apply { setPadding(0, ctx.dp(10), 0, 0) })
        if (exams.isEmpty()) {
            card.addView(textView(ctx, "教务系统暂无考试安排（期末安排一般到学期中后段才发布）。",
                11f, ctx.color(R.color.text_secondary)).apply { setPadding(0, ctx.dp(4), 0, 0) })
        } else {
            for (e in exams.take(4)) {
                card.addView(textView(ctx, e.course, 12f, ctx.color(R.color.text_primary))
                    .apply { setPadding(0, ctx.dp(6), 0, 0) })
                val sub = listOf(e.time, e.location, e.seat).filter { it.isNotBlank() }.joinToString(" · ")
                if (sub.isNotBlank()) card.addView(textView(ctx, sub, 11f, ctx.color(R.color.text_secondary)))
            }
        }

        // 成绩
        card.addView(textView(ctx, "成绩单（${grades.size}）", 13f, ctx.color(R.color.text_primary), bold = true)
            .apply { setPadding(0, ctx.dp(12), 0, 0) })
        if (grades.isEmpty()) {
            card.addView(textView(ctx, "本学期还没有出成绩。出分后同步一次就会列在这里。",
                11f, ctx.color(R.color.text_secondary)).apply { setPadding(0, ctx.dp(4), 0, 0) })
        } else {
            for (g in grades.take(5)) {
                val head = g.course + "   " + g.score
                card.addView(textView(ctx, head, 12f, ctx.color(R.color.text_primary))
                    .apply { setPadding(0, ctx.dp(6), 0, 0) })
                val sub = listOf(
                    if (g.credit.isNotBlank()) g.credit + " 学分" else "",
                    if (g.gpa.isNotBlank()) g.gpa + " 绩点" else "",
                    g.term
                ).filter { it.isNotBlank() }.joinToString(" · ")
                if (sub.isNotBlank()) card.addView(textView(ctx, sub, 11f, ctx.color(R.color.text_secondary)))
            }
        }

        card.addView(actionRow(
            "同步教务" to { syncJwxt() },
            "全部考试" to { showExams(CourseStore.exams(ctx)) }
        ))
        card.addView(actionRow(
            "全部成绩" to { showGrades(CourseStore.grades(ctx)) },
            "教务原文" to { showJwFiles() }
        ))
        addView(card, cardLp())
    }

    // ⑥ 快捷入口
    private fun quickCard() {
        val card = card(ctx, 14)
        card.addView(titleRow("快捷入口", ""))
        card.addView(actionRow("课程表" to onOpenTimetable, "设置与 Key" to onOpenSettings))
        card.addView(actionRow("导出课表" to { shareBackup() }, "关于" to { about() }))
        addView(card, cardLp())
    }

    private fun footer() {
        addView(textView(ctx,
            "· 学籍与课表来自教务系统，原文留档在「教务原文」里可逐字核对。\n" +
                "· 身份证号、家庭住址等敏感信息不落库、不显示。\n" +
                "· 资料只存本机，不上传；知识库为离线快照。",
            11f, ctx.color(R.color.text_secondary)
        ).apply { setPadding(0, ctx.dp(14), 0, 0) })
    }

    // ---- 小工具 ----

    private fun subLine(p: ProfileStore.Profile): String {
        val parts = ArrayList<String>()
        if (p.college.isNotBlank()) parts.add(p.college)
        if (p.major.isNotBlank()) parts.add(p.major)
        if (p.clazz.isNotBlank()) parts.add(p.clazz)
        return if (parts.isEmpty()) "点「编辑」填写学籍，或登录教务自动同步" else parts.joinToString(" · ")
    }

    private fun kvBlock(label: String, value: String): LinearLayout = LinearLayout(ctx).apply {
        orientation = VERTICAL
        addView(textView(ctx, label, 10f, ctx.color(R.color.text_secondary)))
        addView(textView(ctx, value, 12f, ctx.color(R.color.text_primary)))
    }

    private fun titleRow(title: String, right: String): LinearLayout = LinearLayout(ctx).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(textView(ctx, title, 15f, ctx.color(R.color.text_primary), bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (right.isNotBlank()) {
            addView(textView(ctx, right, 11f, ctx.color(R.color.text_secondary)))
        }
    }

    private var statIndex = 0

    /** Ins 风统计格：粉、蓝交替的浅色底，数字用同色系深色。 */
    private fun statCell(label: String, value: String): LinearLayout = LinearLayout(ctx).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        val pink = statIndex++ % 2 == 0
        val bgTint = if (pink) ctx.color(R.color.accent_soft) else ctx.color(R.color.blue_soft)
        val ink = if (pink) ctx.color(R.color.accent) else ctx.color(R.color.blue_ink)
        background = rounded(ctx, bgTint, 16)
        setPadding(ctx.dp(6), ctx.dp(12), ctx.dp(6), ctx.dp(12))
        addView(textView(ctx, value, 20f, ink, bold = true))
        addView(textView(ctx, label, 11f, ctx.color(R.color.text_secondary)))
    }

    private fun weighted(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun weighted(afterIndex: Int) = LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
    ).apply { leftMargin = ctx.dp(8) }

    private fun cardLp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = ctx.dp(10) }

    private fun actionRow(vararg actions: Pair<String, () -> Unit>): LinearLayout = LinearLayout(ctx).apply {
        orientation = HORIZONTAL
        setPadding(0, ctx.dp(10), 0, 0)
        for ((i, a) in actions.withIndex()) {
            addView(button(ctx, a.first, ctx.color(R.color.bg_dark), ctx.color(R.color.text_primary)).apply {
                textSize = 13f
                setOnClickListener { a.second() }
            }, LinearLayout.LayoutParams(0, ctx.dp(42), 1f).apply {
                if (i > 0) leftMargin = ctx.dp(8)
            })
        }
    }

    private fun editProfile() {
        val p = ProfileStore.get(ctx)
        inputForm(ctx, "编辑学籍信息", listOf(
            "姓名 / 昵称" to p.nick,
            "学号" to p.sid,
            "院系" to p.college,
            "专业" to p.major,
            "班级" to p.clazz,
            "宿舍楼" to p.dorm
        )) { v ->
            ProfileStore.save(ctx, p.copy(
                nick = v.getOrElse(0) { "" },
                sid = v.getOrElse(1) { "" },
                college = v.getOrElse(2) { "" },
                major = v.getOrElse(3) { "" },
                clazz = v.getOrElse(4) { "" },
                dorm = v.getOrElse(5) { "" }
            ))
            refresh()
        }
    }

    private fun shareBackup() {
        val courses = CourseStore.courses(ctx)
        val acts = CourseStore.activities(ctx)
        if (courses.isEmpty() && acts.isEmpty()) {
            ctx.toast("课表和活动都是空的，暂无可导出内容。")
            return
        }
        val json = CourseStore.backupJson(courses, acts).toString(2)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, json)
        }
        try {
            ctx.startActivity(Intent.createChooser(send, "导出 / 分享课表"))
        } catch (e: Exception) {
            ctx.toast("没有找到可分享的应用：${e.message}")
        }
    }

    /** 重新从教务系统搬一次数据（课表 / 学籍 / 考试 / 成绩）。 */
    private fun syncJwxt() {
        val a = AuthStore.session(ctx)
        if (!a.ready() || !a.loggedIn) {
            ctx.toast("还没登录教务系统，先在「设置与 Key」里登录。")
            return
        }
        if (a.mode == AuthStore.MODE_WAN && AuthStore.wanBlocked()) {
            ctx.toast(AuthStore.wanWindowText() + "，现在不在开放时段，仍尝试同步…")
        }
        ctx.toast("正在同步教务数据…")
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            val r = try {
                JwxtClient.sync(ctx, a.base()) { }
            } catch (e: Exception) {
                null
            }
            if (r != null && r.ok) {
                runCatching { if (r.courses.isNotEmpty()) CourseStore.replaceJwxtCourses(ctx, r.courses) }
                runCatching { if (r.exams.isNotEmpty()) CourseStore.replaceExams(ctx, r.exams) }
                runCatching { if (r.grades.isNotEmpty()) CourseStore.replaceGrades(ctx, r.grades) }
                runCatching { ProfileStore.mergeJwxt(ctx, r.profile) }
                AuthStore.setLastSync(ctx, System.currentTimeMillis())
            }
            main.post {
                ctx.toast(if (r == null) "同步失败：网络或会话异常。" else r.message)
                refresh()
            }
        }.start()
    }

    private fun showExams(list: List<com.campus.arnav.data.Exam>) {
        if (list.isEmpty()) {
            ctx.toast("教务系统里没有考试安排。")
            return
        }
        val text = list.joinToString("\n\n") { e ->
            buildString {
                append(e.course)
                if (e.time.isNotBlank()) append("\n时间：").append(e.time)
                if (e.location.isNotBlank()) append("\n地点：").append(e.location)
                if (e.seat.isNotBlank()) append("\n座位：").append(e.seat)
            }
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("全部考试（" + list.size + "）")
            .setMessage(text)
            .setPositiveButton("好", null)
            .show()
    }

    private fun showGrades(list: List<com.campus.arnav.data.Grade>) {
        if (list.isEmpty()) {
            ctx.toast("教务系统里还没有成绩。")
            return
        }
        val text = list.joinToString("\n") { g ->
            val term = if (g.term.isNotBlank()) "[" + g.term + "] " else ""
            term + g.course + "  " + g.score + (if (g.credit.isNotBlank()) "  " + g.credit + "学分" else "") +
                (if (g.gpa.isNotBlank()) "  " + g.gpa + "绩点" else "")
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("全部成绩（" + list.size + "）")
            .setMessage(text)
            .setPositiveButton("好", null)
            .show()
    }

    /** 看教务系统页面原文（同步时存在私有目录，用于核对课表一字不差）。 */
    private fun showJwFiles() {
        val dir = java.io.File(ctx.filesDir, "jwxt")
        val files = dir.listFiles()
        if (files == null || files.isEmpty()) {
            ctx.toast("还没有教务原文，先同步一次。")
            return
        }
        files.sortBy { it.name }
        val names = files.map { it.name }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("教务原文")
            .setItems(names.toTypedArray()) { _, which ->
                val f = files[which]
                val content = try {
                    if (f.name.endsWith(".txt")) f.readText() else textFromHtml(f.readText())
                } catch (e: Exception) {
                    "读取失败：" + (e.message ?: "")
                }
                androidx.appcompat.app.AlertDialog.Builder(ctx)
                    .setTitle(f.name)
                    .setMessage(content.take(6000))
                    .setPositiveButton("好", null)
                    .show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun textFromHtml(html: String): String {
        val t = html
            .replace(Regex("<br[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</(p|div|tr|table)[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</t[dh][^>]*>", RegexOption.IGNORE_CASE), "\t")
            .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
        return t.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun about() {
        ctx.toast("科文智嘉 · 课程表 / 智嘉 / 导航 / 对话 / 我的 · 离线知识库")
    }
}