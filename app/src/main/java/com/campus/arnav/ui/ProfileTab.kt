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
 * 第五栏「我的」：身份卡 + 本机统计 + 本周课程 + 快捷入口。
 *
 * 不联网、不登录；资料只存本机，个人页只是把「这个人的课表/活动/知识库」汇总一眼可见。
 */
class ProfileTab(
    private val ctx: Context,
    private val onOpenSettings: () -> Unit,
    private val onOpenTimetable: () -> Unit
) : LinearLayout(ctx) {

    private lateinit var avatar: TextView
    private lateinit var nameView: TextView
    private lateinit var metaView: TextView
    private lateinit var statsRow: LinearLayout
    private lateinit var weekList: LinearLayout
    private lateinit var semView: TextView

    init {
        orientation = VERTICAL
        setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(20))
    }

    fun refresh() {
        removeAllViews()
        val p = ProfileStore.get(ctx)
        val st = ProfileStore.stats(ctx)

        addView(textView(ctx, "我的", 26f, ctx.color(R.color.text_primary), bold = true))
        addView(textView(ctx, "本机资料与学习概览", 12f, ctx.color(R.color.text_secondary)))

        // ① 身份卡
        val me = card(ctx, 14).apply {
            val head = LinearLayout(ctx).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            avatar = TextView(ctx).apply {
                text = p.initial
                textSize = 24f
                setTextColor(Color.parseColor("#1A120C"))
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
            nameView = textView(ctx, p.nick, 18f, ctx.color(R.color.text_primary), bold = true)
            metaView = textView(ctx, metaText(p), 12f, ctx.color(R.color.text_secondary))
            texts.addView(nameView)
            texts.addView(metaView)
            head.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val edit = button(ctx, "编辑", ctx.color(R.color.bg_dark), ctx.color(R.color.text_primary)).apply {
                textSize = 12f
                setOnClickListener { editProfile() }
            }
            head.addView(edit, LinearLayout.LayoutParams(ctx.dp(58), ctx.dp(36)))
            addView(head)
            semView = textView(ctx, "${st.semesterName} · 第 ${st.currentWeek} / ${st.totalWeeks} 周", 12f, ctx.color(R.color.accent))
            semView.setPadding(0, ctx.dp(10), 0, 0)
            addView(semView)
        }
        addView(me, cardLp())

        // ② 统计
        statsRow = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
        }
        statsRow.addView(statCell("课程", st.courseCount.toString()), weighted())
        statsRow.addView(statCell("本周", st.weekCourseCount.toString()), weighted(1))
        statsRow.addView(statCell("活动", st.activityCount.toString()), weighted(1))
        statsRow.addView(statCell("知识库", st.kbDocs.toString()), weighted(1))
        addView(statsRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = ctx.dp(10) })

        // ③ 本周课程
        val weekCard = card(ctx, 14)
        weekCard.addView(textView(ctx, "本周课程", 15f, ctx.color(R.color.text_primary), bold = true))
        weekList = LinearLayout(ctx).apply { orientation = VERTICAL }
        if (st.weekCourses.isEmpty()) {
            weekList.addView(textView(ctx, "本周没有匹配的课程；可在「课程表」导入或添加。", 12f, ctx.color(R.color.text_secondary)))
        } else {
            for (c in st.weekCourses) {
                val row = LinearLayout(ctx).apply {
                    orientation = VERTICAL
                    setPadding(0, ctx.dp(8), 0, ctx.dp(8))
                    addView(textView(ctx,
                        "${weekdayName(c.weekday)} 第 ${c.startPeriod}-${c.endPeriod} 节  ${c.name}",
                        13f, ctx.color(R.color.text_primary)))
                    val sub = listOf(c.location, c.teacher).filter { it.isNotBlank() }.joinToString(" · ")
                    if (sub.isNotBlank()) addView(textView(ctx, sub, 11f, ctx.color(R.color.text_secondary)))
                }
                weekList.addView(row)
            }
        }
        weekCard.addView(weekList)
        weekCard.addView(textView(ctx, "点「课程表」查看完整周表与节次时间。", 11f, ctx.color(R.color.text_secondary))
            .apply { setPadding(0, ctx.dp(8), 0, 0) })
        addView(weekCard, cardLp())

        // ④ 教务数据（登录教务系统后搬回来的考试 / 成绩）
        val jwCard = card(ctx, 14)
        jwCard.addView(textView(ctx, "教务数据", 15f, ctx.color(R.color.text_primary), bold = true))
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
        val session = AuthStore.session(ctx)
        val syncText = if (!session.loggedIn) {
            "还没登录教务系统。登录后会自动把课表、考试、成绩搬到本机。"
        } else {
            session.modeLabel() + " · " + AuthStore.lastSyncText(ctx)
        }
        jwCard.addView(textView(ctx, syncText, 11f, ctx.color(R.color.text_secondary))
            .apply { setPadding(0, ctx.dp(6), 0, 0) })
        jwCard.addView(textView(ctx,
            "考试 " + exams.size + " 条 · 成绩 " + grades.size + " 条 · 课表课程 " + st.courseCount + " 门",
            12f, ctx.color(R.color.accent)
        ).apply { setPadding(0, ctx.dp(8), 0, 0) })

        if (exams.isEmpty() && grades.isEmpty()) {
            jwCard.addView(textView(ctx,
                "同步一次会把教务系统的考试安排与成绩单一起存下来，原页面也留档。",
                11f, ctx.color(R.color.text_secondary)).apply { setPadding(0, ctx.dp(6), 0, 0) })
        } else {
            for (e in exams.take(3)) {
                jwCard.addView(textView(ctx,
                    "考试 · " + e.course + (if (e.time.isNotBlank()) "  " + e.time else ""),
                    12f, ctx.color(R.color.text_primary)).apply { setPadding(0, ctx.dp(8), 0, 0) })
                val sub = listOf(e.location, e.seat).filter { it.isNotBlank() }.joinToString(" · ")
                if (sub.isNotBlank()) {
                    jwCard.addView(textView(ctx, sub, 11f, ctx.color(R.color.text_secondary)))
                }
            }
            for (g in grades.take(4)) {
                val line = g.course + "  " + g.credit + " 学分  " + g.score
                jwCard.addView(textView(ctx, line, 12f, ctx.color(R.color.text_primary))
                    .apply { setPadding(0, ctx.dp(8), 0, 0) })
            }
            if (exams.size > 3 || grades.size > 4) {
                jwCard.addView(textView(ctx, "下面可以看全部考试与成绩。", 11f, ctx.color(R.color.text_secondary))
                    .apply { setPadding(0, ctx.dp(6), 0, 0) })
            }
        }
        jwCard.addView(actionRow(
            "同步教务" to { syncJwxt() },
            "全部考试" to { showExams(exams) }
        ))
        jwCard.addView(actionRow(
            "全部成绩" to { showGrades(grades) },
            "教务原文" to { showJwFiles() }
        ))
        addView(jwCard, cardLp())

        // ⑤ 快捷入口
        val actions = card(ctx, 14)
        actions.addView(textView(ctx, "快捷入口", 15f, ctx.color(R.color.text_primary), bold = true))
        actions.addView(actionRow(
            "课程表" to onOpenTimetable,
            "设置与 Key" to onOpenSettings
        ))
        actions.addView(actionRow(
            "导出课表" to { shareBackup() },
            "关于" to { about() }
        ))
        addView(actions, cardLp())

        addView(textView(ctx,
            "· 个人资料只保存在本机，不会上传。\n" +
                "· 课程/活动来自本机数据库；知识库为离线快照。\n" +
                "· 高德导航走 uri.amap.com，不需要 Key。",
            11f, ctx.color(R.color.text_secondary)
        ).apply { setPadding(0, ctx.dp(14), 0, 0) })
    }

    private fun metaText(p: ProfileStore.Profile): String {
        val parts = ArrayList<String>()
        if (p.sid.isNotBlank()) parts.add("学号 ${p.sid}")
        if (p.college.isNotBlank()) parts.add(p.college)
        if (p.dorm.isNotBlank()) parts.add("宿舍 ${p.dorm}")
        return if (parts.isEmpty()) "点「编辑」填写学号 / 院系 / 宿舍" else parts.joinToString(" · ")
    }

    private fun editProfile() {
        val p = ProfileStore.get(ctx)
        inputForm(ctx, "编辑个人资料", listOf(
            "昵称" to p.nick,
            "学号" to p.sid,
            "院系" to p.college,
            "宿舍楼" to p.dorm
        )) { v ->
            ProfileStore.save(ctx, ProfileStore.Profile(
                nick = v.getOrElse(0) { "" },
                sid = v.getOrElse(1) { "" },
                college = v.getOrElse(2) { "" },
                dorm = v.getOrElse(3) { "" }
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

    /** 重新从教务系统搬一次数据（课表 / 考试 / 成绩 / 学生信息）。 */
    private fun syncJwxt() {
        val a = AuthStore.session(ctx)
        if (!a.ready() || !a.loggedIn) {
            ctx.toast("还没登录教务系统，先在「设置与 Key」里登录。")
            return
        }
        if (a.mode == AuthStore.MODE_WAN && AuthStore.wanBlocked()) {
            ctx.toast(AuthStore.wanWindowText() + "，现在不能同步（已缓存内容照常看）。")
            return
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
                runCatching {
                    val old = ProfileStore.get(ctx)
                    ProfileStore.save(
                        ctx,
                        old.copy(
                            nick = r.profile.name.ifBlank { old.nick },
                            sid = r.profile.sid.ifBlank { old.sid },
                            college = r.profile.college.ifBlank { old.college }
                        )
                    )
                }
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
            ctx.toast("还没有考试数据，先同步一次。")
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
            ctx.toast("还没有成绩数据，先同步一次。")
            return
        }
        val text = list.joinToString("\n") { g ->
            val term = if (g.term.isNotBlank()) "[" + g.term + "] " else ""
            term + g.course + "  " + g.credit + "学分  " + g.score +
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
            .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
        return t.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun about() {
        ctx.toast("科文智嘉 · 课程表 / 智嘉 / 导航 / 对话 / 我的 · 离线知识库")
    }

    private fun statCell(label: String, value: String): LinearLayout = LinearLayout(ctx).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        background = rounded(ctx, ctx.color(R.color.bg_card), 14)
        setPadding(ctx.dp(6), ctx.dp(10), ctx.dp(6), ctx.dp(10))
        addView(textView(ctx, value, 20f, ctx.color(R.color.accent), bold = true))
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
            val b = button(ctx, a.first, ctx.color(R.color.bg_dark), ctx.color(R.color.text_primary)).apply {
                textSize = 13f
                setOnClickListener { a.second() }
            }
            addView(b, LinearLayout.LayoutParams(0, ctx.dp(42), 1f).apply {
                if (i > 0) leftMargin = ctx.dp(8)
            })
        }
    }

    private fun weekdayName(wd: Int): String = when (wd) {
        1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"
        5 -> "周五"; 6 -> "周六"; 7 -> "周日"; else -> "周?"
    }
}