package com.campus.arnav.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import android.widget.TextView
import com.campus.arnav.LoginActivity
import com.campus.arnav.R
import com.campus.arnav.data.AuthStore
import com.campus.arnav.data.CourseStore
import com.campus.arnav.data.ProfileStore
import com.campus.arnav.data.ScheduleLogic
import com.campus.arnav.net.JwxtClient

/**
 * 第五栏「我的」：学籍卡 + 本机统计 + 本周课程 + 教务数据（考试/成绩）+ 快捷入口。
 *
 * 视觉走 Ins 浅色：粉白渐变透底、白卡 20dp 圆角 + 1dp 描边 + 2dp 阴影，
 * 正文系统字体、页面大标题用装饰字体。业务逻辑与 public 回调保持不变。
 */
class ProfileTab(
    private val ctx: Context,
    private val onOpenSettings: () -> Unit,
    private val onOpenTimetable: () -> Unit
) : LinearLayout(ctx) {

    init {
        orientation = VERTICAL
        // 不铺纯色底，让 MainShell 的 #FFF8FB→#EFF7FE 渐变透出来
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(24))
    }

    fun refresh() {
        removeAllViews()
        val p = ProfileStore.get(ctx)
        val st = ProfileStore.stats(ctx)

        header(st)
        idCard(p)
        statRow(st)
        weekCard(st)
        jwCard()
        quickCard()
        footer()
    }

    // ① 标题：装饰字体 22sp + 右侧 34dp 圆形设置按钮
    private fun header(st: ProfileStore.Stats) {
        val head = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(ctx).apply { orientation = VERTICAL }
        titleCol.addView(titleText(ctx, "我的"))
        titleCol.addView(
            textView(
                ctx,
                "${st.semesterName} · 第 ${st.currentWeek} 周（共 ${st.totalWeeks} 周）",
                12.5f,
                ctx.color(R.color.text_secondary)
            ).apply { setPadding(0, ctx.dp(2), 0, 0) }
        )
        head.addView(
            titleCol,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        head.addView(
            circleButton("⚙", "设置") { onOpenSettings() },
            LinearLayout.LayoutParams(ctx.dp(34), ctx.dp(34))
        )
        addView(head)
    }

    // ② 身份卡
    private fun idCard(p: ProfileStore.Profile) {
        val card = card(ctx, 16)

        val head = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val avatar = TextView(ctx).apply {
            text = p.initial
            textSize = 22f
            setTextColor(ctx.color(R.color.accent_ink))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ctx.color(R.color.accent_soft))
            }
        }
        head.addView(avatar, LinearLayout.LayoutParams(ctx.dp(56), ctx.dp(56)))

        val texts = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setPadding(ctx.dp(12), 0, ctx.dp(8), 0)
        }
        texts.addView(
            textView(ctx, p.nick.ifBlank { "同学" }, 18f, ctx.color(R.color.text_primary), bold = true)
        )
        texts.addView(
            textView(ctx, subLine(p), 12.5f, ctx.color(R.color.text_secondary))
                .apply { setPadding(0, ctx.dp(3), 0, 0) }
        )
        head.addView(
            texts,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        head.addView(
            miniGhost("编辑") { editProfile() },
            LinearLayout.LayoutParams(ctx.dp(58), ctx.dp(32))
        )
        card.addView(head)

        // 学籍四项一行、共四行，label 淡、value 深
        val rows = listOf(
            listOf("学号" to p.sid.ifBlank { "未填" }, "性别" to p.gender.ifBlank { "—" }),
            listOf("院系" to p.college.ifBlank { "未同步" }, "专业" to p.major.ifBlank { "—" }),
            listOf("班级" to p.clazz.ifBlank { "—" }, "学制" to p.studyLen.ifBlank { "—" }),
            listOf("入学" to p.enterDate.ifBlank { "—" }, "宿舍" to p.dorm.ifBlank { "—" })
        )
        for (pair in rows) {
            val line = LinearLayout(ctx).apply { orientation = HORIZONTAL }
            for ((i, kv) in pair.withIndex()) {
                line.addView(
                    infoCell(kv.first, kv.second),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (i > 0) leftMargin = ctx.dp(12)
                    }
                )
            }
            card.addView(
                line,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = ctx.dp(10) }
            )
        }
        if (p.sid.isBlank() && p.college.isBlank()) {
            card.addView(
                textView(
                    ctx,
                    "登录教务系统同步一次，姓名、学号、院系、专业、班级会自动补齐；也可以点「编辑」自己填。",
                    12f,
                    ctx.color(R.color.text_hint)
                ).apply { setPadding(0, ctx.dp(12), 0, 0) }
            )
        }
        addView(card, cardLp())
    }

    // ③ 统计 2×2：粉/蓝浅底交替
    private fun statRow(st: ProfileStore.Stats) {
        val cells = listOf(
            "课程" to st.courseCount.toString(),
            "本周" to st.weekCourseCount.toString(),
            "考试" to st.examCount.toString(),
            "成绩" to st.gradeCount.toString()
        )
        val grid = LinearLayout(ctx).apply { orientation = VERTICAL }
        for (r in 0 until 2) {
            val row = LinearLayout(ctx).apply { orientation = HORIZONTAL }
            for (c in 0 until 2) {
                val i = r * 2 + c
                row.addView(
                    statCell(cells[i].first, cells[i].second, pink = i % 2 == 0),
                    LinearLayout.LayoutParams(0, ctx.dp(72), 1f).apply {
                        if (c > 0) leftMargin = ctx.dp(10)
                    }
                )
            }
            grid.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { if (r > 0) topMargin = ctx.dp(10) }
            )
        }
        addView(grid, cardLp())
    }

    // ④ 本周课程：按天分组、条目之间 1dp 分隔
    private fun weekCard(st: ProfileStore.Stats) {
        val card = card(ctx, 16)
        card.addView(titleRow("本周课程", if (st.weekCourses.isEmpty()) "" else "${st.weekCourses.size} 节"))
        if (st.weekCourses.isEmpty()) {
            card.addView(
                textView(
                    ctx,
                    "本周没有课（或课表还没同步）。到「课程表」同步教务，或导入课表。",
                    12.5f,
                    ctx.color(R.color.text_hint)
                ).apply { setPadding(0, ctx.dp(8), 0, 0) }
            )
        } else {
            var lastDay = -1
            var firstOfDay = true
            for (c in st.weekCourses) {
                if (c.weekday != lastDay) {
                    lastDay = c.weekday
                    val pill = TextView(ctx).apply {
                        text = "周" + ScheduleLogic.weekdayName(c.weekday)
                        textSize = 11.5f
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        setTextColor(ctx.color(R.color.accent_ink))
                        background = rounded(ctx, ctx.color(R.color.accent_soft), 999)
                        setPadding(ctx.dp(9), ctx.dp(3), ctx.dp(9), ctx.dp(3))
                    }
                    card.addView(
                        pill,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = ctx.dp(12) }
                    )
                    firstOfDay = true
                }
                if (!firstOfDay) {
                    card.addView(thinLine(), LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(1)
                    ).apply { topMargin = ctx.dp(8) })
                }
                val time = ScheduleLogic.periodTime(c.startPeriod, "").substringBefore("-")
                val period = if (c.startPeriod == c.endPeriod) {
                    "第 ${c.startPeriod} 节"
                } else {
                    "第 ${c.startPeriod}-${c.endPeriod} 节"
                }
                val row = LinearLayout(ctx).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                row.addView(
                    textView(ctx, "$time  $period", 12f, ctx.color(R.color.text_secondary)),
                    LinearLayout.LayoutParams(ctx.dp(100), ViewGroup.LayoutParams.WRAP_CONTENT)
                )
                row.addView(
                    textView(ctx, c.name, 14.5f, ctx.color(R.color.text_primary)).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                if (c.location.isNotBlank()) {
                    row.addView(
                        textView(ctx, c.location, 12f, ctx.color(R.color.text_hint)).apply {
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { leftMargin = ctx.dp(8) }
                    )
                }
                card.addView(
                    row,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = ctx.dp(8) }
                )
                firstOfDay = false
            }
        }
        addView(card, cardLp())
    }

    // ⑤ 教务数据：标题 + 同步按钮、考试/成绩摘要行、三个 chip
    private fun jwCard() {
        val session = AuthStore.session(ctx)

        if (!session.loggedIn) {
            addView(
                textView(
                    ctx,
                    "不登录也能用课程表：先看示例，随时可换成自己的课表",
                    12f,
                    ctx.color(R.color.text_hint)
                ).apply { setPadding(ctx.dp(2), ctx.dp(12), ctx.dp(2), 0) }
            )
        }

        val card = card(ctx, 16)
        val head = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            textView(ctx, "教务数据", 15f, ctx.color(R.color.text_primary), bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        head.addView(
            miniGhost(if (session.loggedIn) "同步教务" else "登录教务") {
                if (session.loggedIn) syncJwxt() else openLogin()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ctx.dp(32)
            )
        )
        card.addView(head)

        val line = if (!session.loggedIn) {
            "还没登录教务系统 · 登录是可选的"
        } else {
            session.modeLabel() + " · " + AuthStore.lastSyncText(ctx)
        }
        card.addView(
            textView(ctx, line, 12f, ctx.color(R.color.text_hint))
                .apply { setPadding(0, ctx.dp(4), 0, 0) }
        )

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

        val examLine = if (exams.isEmpty()) {
            "暂无考试安排（一般学期中后段才发布）"
        } else {
            "${exams.size} 场 · " + exams.take(2).joinToString("，") { it.course }
        }
        val gradeLine = if (grades.isEmpty()) {
            "本学期还没有出分，出分后同步一次就会列在这里"
        } else {
            "${grades.size} 门已出分" + grades.take(1).joinToString("") { " · ${it.course} ${it.score}" }
        }
        card.addView(summaryRow("考试", examLine, R.color.text_primary))
        card.addView(summaryRow("成绩", gradeLine, R.color.text_primary))

        val chips = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            setPadding(0, ctx.dp(12), 0, 0)
        }
        val actions = listOf<Pair<String, () -> Unit>>(
            "全部考试" to { showExams(CourseStore.exams(ctx)) },
            "全部成绩" to { showGrades(CourseStore.grades(ctx)) },
            "教务原文" to { showJwFiles() }
        )
        for ((i, a) in actions.withIndex()) {
            chips.addView(
                smallChip(a.first) { a.second() },
                LinearLayout.LayoutParams(0, ctx.dp(32), 1f).apply {
                    if (i > 0) leftMargin = ctx.dp(8)
                }
            )
        }
        card.addView(chips)
        addView(card, cardLp())
    }

    // ⑥ 快捷入口 2×2 宫格
    private fun quickCard() {
        val entries = listOf<Pair<String, Pair<String, () -> Unit>>>(
            "▤" to ("课程表" to onOpenTimetable),
            "⚙" to ("设置" to onOpenSettings),
            "↗" to ("导出课表" to { shareBackup() }),
            "ⓘ" to ("关于" to { about() })
        )
        val grid = LinearLayout(ctx).apply { orientation = VERTICAL }
        for (r in 0 until 2) {
            val row = LinearLayout(ctx).apply { orientation = HORIZONTAL }
            for (c in 0 until 2) {
                val (glyph, entry) = entries[r * 2 + c]
                row.addView(
                    quickCell(glyph, entry.first, entry.second),
                    LinearLayout.LayoutParams(0, ctx.dp(80), 1f).apply {
                        if (c > 0) leftMargin = ctx.dp(10)
                    }
                )
            }
            grid.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { if (r > 0) topMargin = ctx.dp(10) }
            )
        }
        addView(grid, cardLp())
    }

    private fun footer() {
        addView(
            textView(
                ctx,
                "· 学籍与课表来自教务系统，原文留档在「教务原文」里可逐字核对。\n" +
                    "· 身份证号、家庭住址等敏感信息不保存、不显示。\n" +
                    "· 资料只保存在本机，不会上传；知识库是离线版。",
                11.5f,
                ctx.color(R.color.text_hint)
            ).apply {
                setLineSpacing(0f, 1.5f)
                setPadding(0, ctx.dp(16), 0, 0)
            }
        )
    }

    // ---- 小工具 ----

    private fun subLine(p: ProfileStore.Profile): String {
        val parts = ArrayList<String>()
        if (p.college.isNotBlank()) parts.add(p.college)
        if (p.major.isNotBlank()) parts.add(p.major)
        if (p.clazz.isNotBlank()) parts.add(p.clazz)
        return if (parts.isEmpty()) "点「编辑」填写学籍，或登录教务自动同步" else parts.joinToString(" · ")
    }

    /** 浅底数字格：粉格深粉字、蓝格深蓝字。 */
    private fun statCell(label: String, value: String, pink: Boolean): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            background = rounded(
                ctx,
                if (pink) ctx.color(R.color.accent_soft) else ctx.color(R.color.blue_soft),
                16
            )
            setPadding(ctx.dp(6), ctx.dp(12), ctx.dp(6), ctx.dp(12))
            addView(
                textView(
                    ctx,
                    value,
                    20f,
                    if (pink) ctx.color(R.color.accent_ink) else ctx.color(R.color.blue_ink),
                    bold = true
                )
            )
            addView(
                textView(ctx, label, 11.5f, ctx.color(R.color.text_secondary))
                    .apply { setPadding(0, ctx.dp(4), 0, 0) }
            )
        }

    private fun infoCell(label: String, value: String): LinearLayout = LinearLayout(ctx).apply {
        orientation = VERTICAL
        addView(textView(ctx, label, 11.5f, ctx.color(R.color.text_hint)))
        addView(
            textView(ctx, value, 13.5f, ctx.color(R.color.text_primary)).apply {
                setPadding(0, ctx.dp(3), 0, 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        )
    }

    private fun titleRow(title: String, right: String): LinearLayout = LinearLayout(ctx).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            textView(ctx, title, 15f, ctx.color(R.color.text_primary), bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        if (right.isNotBlank()) {
            addView(textView(ctx, right, 11.5f, ctx.color(R.color.text_hint)))
        }
    }

    private fun summaryRow(label: String, text: String, colorRes: Int): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ctx.dp(8), 0, 0)
            addView(
                textView(ctx, label, 12f, ctx.color(R.color.text_secondary)),
                LinearLayout.LayoutParams(ctx.dp(44), ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            addView(
                textView(ctx, text, 13f, ctx.color(colorRes)).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }

    /** 34dp 圆形按钮：白底 + 1dp 描边 + 深色符号。 */
    private fun circleButton(glyph: String, desc: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = glyph
            textSize = 15f
            gravity = Gravity.CENTER
            includeFontPadding = false
            contentDescription = desc
            setTextColor(ctx.color(R.color.text_secondary))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ctx.color(R.color.bg_card))
                setStroke(ctx.dp(1), ctx.color(R.color.border))
            }
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** 小号 ghost 按钮：白底 + 1dp 描边 + 正文深色字，32dp 高。 */
    private fun miniGhost(text: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 12f
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(ctx.color(R.color.text_primary))
        background = GradientDrawable().apply {
            cornerRadius = ctx.dp(14).toFloat()
            setColor(ctx.color(R.color.bg_card))
            setStroke(ctx.dp(1), ctx.color(R.color.border))
        }
        setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** 胶囊 chip：浅粉底 + 深粉字。 */
    private fun smallChip(text: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 12f
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(ctx.color(R.color.accent_ink))
        background = rounded(ctx, ctx.color(R.color.accent_soft), 999)
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** 快捷入口格：白底 16dp 圆角 + 1dp 描边 + 1dp 阴影，80dp 高。 */
    private fun quickCell(glyph: String, label: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(16).toFloat()
                setColor(ctx.color(R.color.bg_card))
                setStroke(ctx.dp(1), ctx.color(R.color.border))
            }
            elevation = ctx.dp(1).toFloat()
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(
                        0, 0, view.width, view.height, ctx.dp(16).toFloat()
                    )
                }
            }
            clipToOutline = true
            addView(
                textView(ctx, glyph, 18f, ctx.color(R.color.accent), bold = true)
                    .apply { gravity = Gravity.CENTER }
            )
            addView(
                textView(ctx, label, 13f, ctx.color(R.color.text_primary))
                    .apply { setPadding(0, ctx.dp(6), 0, 0) }
            )
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun thinLine(): View = View(ctx).apply {
        setBackgroundColor(ctx.color(R.color.border))
    }

    private fun cardLp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = ctx.dp(10) }

    private fun editProfile() {
        val p = ProfileStore.get(ctx)
        inputForm(ctx, "编辑学籍信息", listOf(
            "姓名或昵称" to p.nick,
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
            ctx.startActivity(Intent.createChooser(send, "导出或分享课表"))
        } catch (e: Exception) {
            ctx.toast("没有找到可分享的应用：${e.message}")
        }
    }

    /** 登录教务是可选项：从「我的」页进入登录页，登录成功回主壳后会自动刷新课程表。 */
    private fun openLogin() {
        val c = ctx
        try {
            val i = Intent(c, LoginActivity::class.java)
            if (c !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(i)
        } catch (e: Exception) {
            ctx.toast("打不开登录页：${e.message}")
        }
    }

    /** 重新从教务系统搬一次数据（课表 / 学籍 / 考试 / 成绩）。 */
    private fun syncJwxt() {
        val a = AuthStore.session(ctx)
        if (!a.ready() || !a.loggedIn) {
            ctx.toast("还没登录教务系统，先点上面的「登录教务（可选）」。")
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
        androidx.appcompat.app.AlertDialog.Builder(ctx, R.style.Theme_CampusAr_Dialog)
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
        androidx.appcompat.app.AlertDialog.Builder(ctx, R.style.Theme_CampusAr_Dialog)
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
        androidx.appcompat.app.AlertDialog.Builder(ctx, R.style.Theme_CampusAr_Dialog)
            .setTitle("教务原文")
            .setItems(names.toTypedArray()) { _, which ->
                val f = files[which]
                val content = try {
                    if (f.name.endsWith(".txt")) f.readText() else textFromHtml(f.readText())
                } catch (e: Exception) {
                    "读取失败：" + (e.message ?: "")
                }
                androidx.appcompat.app.AlertDialog.Builder(ctx, R.style.Theme_CampusAr_Dialog)
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
        ctx.toast("科文智嘉 · 离线知识库")
    }
}