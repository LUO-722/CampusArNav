package com.campus.arnav

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.campus.arnav.core.Reminder
import com.campus.arnav.data.AuthStore
import com.campus.arnav.data.ChatMsg
import com.campus.arnav.data.ChatStore
import com.campus.arnav.data.CourseStore
import com.campus.arnav.data.ProfileStore
import com.campus.arnav.data.Provider
import com.campus.arnav.data.ScheduleLogic
import com.campus.arnav.data.SettingsStore
import com.campus.arnav.kb.KbIndex
import com.campus.arnav.net.JwxtClient
import com.campus.arnav.net.LlmClient
import com.campus.arnav.ui.insLightBars
import com.campus.arnav.ui.insBackground
import com.campus.arnav.ui.applySystemBarInsets
import com.campus.arnav.ui.button
import com.campus.arnav.ui.card
import com.campus.arnav.ui.color
import com.campus.arnav.ui.confirm
import com.campus.arnav.ui.dp
import com.campus.arnav.ui.editText
import com.campus.arnav.ui.kvRow
import com.campus.arnav.ui.textView

/**
 * 设置页：按「卡片 + 状态徽标 + 折叠高级项」组织，避免一屏堆十几个输入框。
 *
 * 结构：
 *   ① DeepSeek（文本）—— 状态徽标 + Key + 测试 + 折叠的高级项（地址/模型）
 *   ② AI 服务（可选）—— 自建服务端代理地址 + 访问令牌，填了 App 内就无需 API Key
 *   ③ 教务系统 / 图片识别 / 学期设置 / 数据与诊断
 * 底部固定的「保存设置」一次提交全部改动。
 * 没有 Key 也没有代理时，App 自动降级为本机规则识别活动 + 离线知识库问答。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var aiBadge: TextView
    private var authBadge: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        insLightBars()

        val root = ScrollView(this).apply {
            background = insBackground(this@SettingsActivity)
            isFillViewport = true
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(20))
        }
        root.addView(box)
        setContentView(root)
        root.applySystemBarInsets { top, bottom, _, _ ->
            box.setPadding(dp(16), dp(16) + top, dp(16), dp(20) + bottom)
        }

        box.addView(textView(this, "设置", 26f, color(R.color.text_primary), bold = true))
        box.addView(textView(this,
            "无需任何配置：有网络自动用云端模型，没有网络自动用离线知识库 + 本机规则。",
            12f, color(R.color.text_secondary)
        ))

        // ① AI 服务：内置，界面上不出现任何接口地址 / API Key / 模型配置
        aiBadge = badge(true)
        val aiCard = card(this, 14)
        aiCard.addView(headerRow("AI 服务", SettingsStore.aiApiName(this), aiBadge))
        aiCard.addView(textView(this,
            "已内置，无需任何配置。有网络时自动接入云端模型（问答、录活动、写课表都在云端判断）；" +
                "没有网络时自动切到离线知识库 + 本机规则，功能照常可用。",
            12f, color(R.color.text_secondary)
        ).apply { setPadding(0, dp(6), 0, dp(2)) })

        // ③ 教务系统（登录态 / 同步）
        val authCard = card(this, 14)
        val auth = AuthStore.session(this)
        authBadge = badge(auth.loggedIn)
        authCard.addView(headerRow("教务系统", "学号登录 · 课表/考试/成绩同步", authBadge))
        authCard.addView(textView(this, authSummary(auth), 12f, color(R.color.text_secondary))
            .apply { setPadding(0, dp(6), 0, dp(2)) })
        authCard.addView(kvRow(this, "学号", auth.account.ifBlank { "未填写" }))
        authCard.addView(kvRow(this, "登录网络", auth.modeLabel() + " · " + auth.base().ifBlank { "地址未填" }))
        authCard.addView(kvRow(this, "最近同步", AuthStore.lastSyncText(this)))
        authCard.addView(actionRow(
            "立即同步" to { syncNow() },
            "重新登录" to { relaunchLogin() }
        ))
        authCard.addView(actionRow(
            "退出登录" to {
                confirm(this, "退出教务登录", "会清掉本机保存的密码与登录状态，课表等已缓存内容保留。", "退出") {
                    AuthStore.logout(this)
                    authBadge?.let { paintBadge(it, false) }
                    status.text = "已退出教务登录。"
                    setResult(RESULT_OK)
                }
            }
        ))
        box.addView(authCard, cardLp())

        // ③ 图片识别（本机 OCR）
        val ocrCard = card(this, 14)
        ocrCard.addView(headerRow("图片识别", "本机 OCR · 离线 · 不传图片", null))
        ocrCard.addView(textView(this,
            "智嘉选图、对话发图时会先用手机本机的 ML Kit 中文 OCR 把图片里的文字识别出来，" +
                "只把识别出的文字用于后续处理；图片本身不上传，也不需要任何 Key。\n" +
                "适合截图、票据、通知照片、界面文字。识别结果会先填入文字框，可修改后再提取。",
            12f, color(R.color.text_secondary)
        ).apply { setPadding(0, dp(6), 0, dp(6)) })
        box.addView(ocrCard, cardLp())

        // ③ 提醒（课程 / 活动开始前 N 分钟发通知）
        val remindCard = card(this, 14)
        remindCard.addView(headerRow("提醒", "课程与活动开始前提醒你", null))
        val remindBox = CheckBox(this).apply {
            text = "开启提醒（当前提前 ${SettingsStore.remindBefore(this@SettingsActivity)} 分钟）"
            isChecked = SettingsStore.remindEnabled(this@SettingsActivity)
            setTextColor(color(R.color.text_primary))
            textSize = 13f
            buttonTintList = ColorStateList.valueOf(color(R.color.accent))
            setOnCheckedChangeListener { _, v ->
                SettingsStore.setRemindEnabled(this@SettingsActivity, v)
                Reminder.scheduleAsync(this@SettingsActivity)
                status.text = if (v) "已开启提醒：课程或活动开始前会发通知。" else "已关闭提醒。"
            }
        }
        remindCard.addView(remindBox)
        remindCard.addView(textView(this,
            "到点会发一条系统通知，例如「10 分钟后上课：高等数学ⅠA · 致广楼-303 · 第 3-5 节」。\n" +
                "当天已停课的课不会提醒；改了课表会自动重排。若系统里把通知权限关掉，就收不到。",
            12f, color(R.color.text_secondary)
        ).apply { setPadding(0, dp(4), 0, dp(6)) })
        remindCard.addView(actionRow(
            "提前 5 分钟" to { setRemind(this@SettingsActivity, 5) },
            "10 分钟" to { setRemind(this@SettingsActivity, 10) },
            "30 分钟" to { setRemind(this@SettingsActivity, 30) }
        ))
        box.addView(remindCard, cardLp())

        // ③ 学期设置
        val semCard = card(this, 14)
        semCard.addView(headerRow("学期设置", "决定第几周、作息与导出内容", null))
        val semStart = field(semCard, "学期开始日（第 1 周周一）", SettingsStore.semesterStart(this))
        val semWeeks = field(semCard, "总教学周数", SettingsStore.semesterWeeks(this).toString()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val semName = field(semCard, "学期名称", SettingsStore.semesterName(this))
        semCard.addView(actionRow(
            "恢复默认" to {
                semStart.setText(ScheduleLogic.DEFAULT_SEMESTER_START)
                semWeeks.setText(ScheduleLogic.DEFAULT_TOTAL_WEEKS.toString())
                semName.setText(ScheduleLogic.DEFAULT_SEMESTER_NAME)
                status.text = "已填入默认值，记得点底部「保存设置」。"
            },
            "本学期概况" to {
                status.text = "${SettingsStore.semesterName(this)} · 开始 ${SettingsStore.semesterStart(this)} · 共 ${SettingsStore.semesterWeeks(this)} 周 · 当前第 ${ScheduleLogic.currentWeek()} 周"
            }
        ))
        box.addView(semCard, cardLp())

        // ④ 数据与诊断
        val toolCard = card(this, 14)
        toolCard.addView(headerRow("数据与诊断", "离线可用，不联网", null))
        toolCard.addView(actionRow(
            "运行离线自检" to { runSelfCheck() },
            "检查知识库" to { runKbCheck() }
        ))
        toolCard.addView(actionRow(
            "导出课表" to { shareTimetable() },
            "清空对话" to {
                confirm(this, "清空对话记录", "只删除本机聊天记录，不影响课表与设置。", "清空") {
                    ChatStore.clear(this)
                    status.text = "对话记录已清空。"
                    setResult(RESULT_OK)
                }
            }
        ))
        box.addView(toolCard, cardLp())

        // 状态行 + 保存
        status = textView(this, "", 12f, color(R.color.text_secondary)).apply {
            setPadding(0, dp(10), 0, dp(6))
        }
        box.addView(status)

        val save = button(this, "保存设置", color(R.color.accent), Color.parseColor("#FFFFFF")).apply {
            textSize = 16f
            setOnClickListener {
                saveAll(semStart, semWeeks, semName)
            }
        }
        box.addView(save, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(6) })

        box.addView(textView(this,
            "· 什么都不填也能用：智嘉走本机规则识别活动，对话走离线知识库，绝不因为没 Key 而不可用。\n" +
                "· 有自建代理时请求发到「代理地址 /chat/completions」（OpenAI 兼容），App 内不需要 API Key。\n" +
                "· 图片文字识别走手机本机 ML Kit 中文 OCR，离线、免费、不传图片、不用 Key。\n" +
                "· 高德路线走 uri.amap.com，不需要 Key。",
            11f, color(R.color.text_secondary)
        ).apply { setPadding(0, dp(14), 0, 0) })

        refreshBadges()
    }

    // ---------------- 教务系统 ----------------

    private fun authSummary(a: AuthStore.Session): String {
        val wanNote = if (a.mode == AuthStore.MODE_WAN && AuthStore.wanBlocked()) " · 不在外网开放时段" else ""
        return if (a.loggedIn) {
            "已登录（${a.modeLabel()}）$wanNote；可立即同步课表 / 考试 / 成绩。"
        } else {
            "未登录。未登录也能用课程表、智嘉、导航、对话，只是不同步教务数据。"
        }
    }

    private fun syncNow() {
        val a = AuthStore.session(this)
        if (!a.ready()) {
            status.text = "还没登录教务系统，先点「重新登录」。"
            return
        }
        if (a.mode == AuthStore.MODE_WAN && AuthStore.wanBlocked()) {
            // 只提示，不拦：时段外也允许试一次，连不上由网络层报错
            status.text = AuthStore.wanWindowText() + "，现在不在开放时段，正在尝试同步…"
        }
        status.text = "正在同步…"
        Thread {
            val r = JwxtClient.sync(this, a.base()) { msg -> runOnUiThread { status.text = msg } }
            val n = try {
                if (r.courses.isNotEmpty()) CourseStore.replaceJwxtCourses(this, r.courses) else 0
            } catch (e: Exception) {
                0
            }
            try {
                if (r.exams.isNotEmpty()) CourseStore.replaceExams(this, r.exams)
                if (r.grades.isNotEmpty()) CourseStore.replaceGrades(this, r.grades)
            } catch (e: Exception) {
                // 忽略落库异常，原文已存档
            }
            // 学籍信息一并更新；学号没解析出来就用已保存的登录账号兜底
            runCatching {
                var jw = r.profile
                if (jw.sid.isBlank()) jw = jw.copy(sid = a.account)
                ProfileStore.mergeJwxt(this, jw)
            }
            runOnUiThread {
                status.text = if (r.ok) {
                    r.message + "，导入课程 " + n + " 门、考试 " + r.exams.size + " 条、成绩 " + r.grades.size + " 条"
                } else {
                    r.message
                }
                if (r.ok) AuthStore.setLastSync(this, System.currentTimeMillis())
                setResult(RESULT_OK)
            }
        }.start()
    }

    private fun relaunchLogin() {
        AuthStore.setLoggedIn(this, false)
        val i = android.content.Intent(this, LoginActivity::class.java).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(i)
        finish()
    }

    // ---------------- 保存 ----------------

    private fun saveAll(
        semStart: EditText, semWeeks: EditText, semName: EditText
    ) {

        val startText = semStart.text.toString().trim()
        val weeksInt = semWeeks.text.toString().trim().toIntOrNull()
        if (ScheduleLogic.parseDate(startText) == null) {
            status.text = "学期设置未保存：开始日应为 yyyy-MM-dd。"
        } else {
            SettingsStore.saveSemester(
                this, startText,
                (weeksInt ?: ScheduleLogic.DEFAULT_TOTAL_WEEKS).coerceIn(1, 30),
                semName.text.toString().trim().ifBlank { ScheduleLogic.DEFAULT_SEMESTER_NAME }
            )
            SettingsStore.applySemester(this)
            status.text = "已保存。"
        }
        refreshBadges()
        setResult(RESULT_OK)
    }

    private fun refreshBadges() {
        paintBadge(aiBadge, SettingsStore.aiApiReady(this), "已内置", "未内置")
    }

    // ---------------- 诊断动作 ----------------

    private fun runSelfCheck() {
        status.text = "自检中…"
        Thread {
            try {
                val report = com.campus.arnav.debug.SelfCheck.format(this)
                runOnUiThread {
                    status.text = report.lineSequence().firstOrNull() ?: "自检完成"
                    AlertDialog.Builder(this, R.style.Theme_CampusAr_Dialog)
                        .setTitle("离线自检结果")
                        .setMessage(report)
                        .setPositiveButton("知道了", null)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "自检失败：${e.message}" }
            }
        }.start()
    }

    private fun runKbCheck() {
        status.text = "检查中…"
        Thread {
            try {
                val s = KbIndex.selfCheck(this)
                runOnUiThread { status.text = s }
            } catch (e: Exception) {
                runOnUiThread { status.text = "知识库读取失败：${e.message}" }
            }
        }.start()
    }

    private fun shareTimetable() {
        val courses = CourseStore.courses(this)
        val acts = CourseStore.activities(this)
        if (courses.isEmpty() && acts.isEmpty()) {
            status.text = "课表和活动都是空的，暂无可导出内容。"
            return
        }
        val json = CourseStore.backupJson(courses, acts).toString(2)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, json)
        }
        try {
            startActivity(android.content.Intent.createChooser(send, "导出 / 分享课表"))
        } catch (e: Exception) {
            status.text = "没有找到可分享的应用：${e.message}"
        }
    }


    // ---------------- 小部件 ----------------

    private fun cardLp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(10) }

    private fun headerRow(title: String, sub: String, badgeView: TextView?): LinearLayout {
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(textView(this, title, 16f, color(R.color.text_primary), bold = true))
        texts.addView(textView(this, sub, 11f, color(R.color.text_secondary)))
        head.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        badgeView?.let { head.addView(it) }
        return head
    }

    private fun badge(ready: Boolean): TextView = TextView(this).apply {
        textSize = 11f
        setPadding(dp(10), dp(4), dp(10), dp(4))
        paintBadge(this, ready)
    }

    private fun paintBadge(
        v: TextView, ready: Boolean,
        readyText: String = "已配置", offText: String = "未配置"
    ) {
        v.text = if (ready) readyText else offText
        v.setTextColor(if (ready) Color.parseColor("#E8447A") else Color.parseColor("#2E7FA8"))
        v.background = GradientDrawable().apply {
            cornerRadius = dp(9).toFloat()
            setColor(if (ready) Color.parseColor("#FFE3EE") else Color.parseColor("#EAF6FD"))
        }
    }

    private fun field(parent: LinearLayout, label: String, value: String): EditText {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        wrap.addView(textView(this, label, 11f, color(R.color.text_secondary)))
        val e = editText(this, label, InputType.TYPE_CLASS_TEXT, value)
        wrap.addView(e)
        parent.addView(wrap, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return e
    }

    /** 高级折叠区：把给定的字段挪进一个默认隐藏的纵向容器。 */
    private fun advanced(parent: LinearLayout, label: String, fields: () -> List<EditText>): LinearLayout {
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        parent.addView(holder, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        for (e in fields()) {
            val wrap = e.parent as? ViewGroup ?: continue
            (wrap.parent as? ViewGroup)?.removeView(wrap)
            holder.addView(wrap, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        return holder
    }

    private fun setAdvancedVisible(parent: LinearLayout, holder: LinearLayout, visible: Boolean) {
        holder.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun actionRow(vararg actions: Pair<String, () -> Unit>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(10), 0, 0)
        for ((i, a) in actions.withIndex()) {
            val b = button(this@SettingsActivity, a.first, color(R.color.bg_dark), color(R.color.text_primary)).apply {
                textSize = 13f
                setOnClickListener { a.second() }
            }
            addView(b, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                if (i > 0) leftMargin = dp(8)
            })
        }
    }

    private fun eyeToggle(parent: LinearLayout, target: EditText): TextView {
        var hidden = true
        return TextView(this).apply {
            text = "显示 Key"
            textSize = 11f
            setTextColor(color(R.color.accent))
            setPadding(0, dp(6), 0, 0)
            setOnClickListener {
                if (hidden) {
                    target.inputType = InputType.TYPE_CLASS_TEXT
                    target.transformationMethod = null
                    text = "隐藏 Key"
                } else {
                    target.maskKey()
                    text = "显示 Key"
                }
                hidden = !hidden
            }
        }.also { parent.addView(it) }
    }

    private fun EditText.maskKey() {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        transformationMethod = PasswordTransformationMethod.getInstance()
        typeface = Typeface.DEFAULT
    }

    /** 改提前分钟数：立刻重排提醒，并给出反馈。 */
    private fun setRemind(ctx: android.content.Context, minutes: Int) {
        SettingsStore.setRemindBefore(ctx, minutes)
        SettingsStore.setRemindEnabled(ctx, true)
        Reminder.scheduleAsync(ctx)
        android.widget.Toast.makeText(ctx, "已设为提前 $minutes 分钟提醒", android.widget.Toast.LENGTH_SHORT).show()
    }

}