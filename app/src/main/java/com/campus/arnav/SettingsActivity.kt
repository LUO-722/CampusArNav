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
import com.campus.arnav.data.AuthStore
import com.campus.arnav.data.ChatMsg
import com.campus.arnav.data.ChatStore
import com.campus.arnav.data.CourseStore
import com.campus.arnav.data.ProfileStore
import com.campus.arnav.data.ScheduleLogic
import com.campus.arnav.data.SettingsStore
import com.campus.arnav.kb.KbIndex
import com.campus.arnav.net.JwxtClient
import com.campus.arnav.net.LlmClient
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
 *   ② 图片识别 —— 本机 ML Kit OCR，离线、不上传图片、不需要 Key
 *   ③ 学期设置 —— 开始日/周数/名称 + 恢复默认
 *   ④ 数据与诊断 —— 自检 / 知识库 / 清空对话
 * 底部固定的「保存设置」一次提交全部改动。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var dsBadge: TextView
    private lateinit var dsKey: EditText
    private var authBadge: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this).apply {
            setBackgroundColor(color(R.color.bg_dark))
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
        box.addView(textView(this, "Key 只存本机，不上传；模型地址可折叠在「高级」里。", 12f, color(R.color.text_secondary)))

        val ds = SettingsStore.deepseek(this)

        // ① DeepSeek
        dsBadge = badge(false)
        val dsCard = card(this, 14)
        dsCard.addView(headerRow("DeepSeek", "文本问答 · 录活动 · 加课", dsBadge))
        dsKey = field(dsCard, "API Key", ds.key).apply { maskKey() }
        val dsEye = eyeToggle(dsCard, dsKey)
        // eyeToggle 自己会挂到 dsCard 上，这里不能再 addView，否则 child already has a parent
        val dsBase = field(dsCard, "接口地址", ds.base)
        val dsModel = field(dsCard, "模型名", ds.model)
        val dsAdv = advanced(dsCard, "高级：接口地址 / 模型名") { listOf(dsBase, dsModel) }
        // 高级折叠区默认收起，字段先建好再收起
        setAdvancedVisible(dsCard, dsAdv, false)
        dsCard.addView(actionRow(
            "测试连接" to { testProvider(dsBase, dsKey, dsModel, false) },
            "高级" to {
                setAdvancedVisible(dsCard, dsAdv, dsAdv.visibility != View.VISIBLE)
            }
        ))
        box.addView(dsCard, cardLp())

        // ② 教务系统（登录态 / 同步）
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

        val save = button(this, "保存设置", color(R.color.accent), Color.parseColor("#1A120C")).apply {
            textSize = 16f
            setOnClickListener {
                saveAll(dsBase, dsKey, dsModel, semStart, semWeeks, semName)
            }
        }
        box.addView(save, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(6) })

        box.addView(textView(this,
            "· DeepSeek 默认 https://api.deepseek.com，模型 deepseek-chat。\n" +
                "· 图片文字识别走手机本机 ML Kit 中文 OCR，离线、免费、不传图片、不用 Key。\n" +
                "· 高德路线走 uri.amap.com，不需要 Key。",
            11f, color(R.color.text_secondary)
        ).apply { setPadding(0, dp(14), 0, 0) })

        refreshBadges()
    }

    // ---------------- 教务系统 ----------------

    private fun authSummary(a: AuthStore.Session): String {
        val wanNote = if (a.mode == AuthStore.MODE_WAN && AuthStore.wanBlocked()) " · 现在外网不可登录" else ""
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
            status.text = AuthStore.wanWindowText() + "，现在不能同步（已缓存内容照常看）。"
            return
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
            runCatching {
                val old = ProfileStore.get(this)
                val jw = r.profile
                ProfileStore.save(
                    this,
                    old.copy(
                        nick = jw.name.ifBlank { old.nick },
                        sid = jw.sid.ifBlank { old.sid },
                        college = jw.college.ifBlank { old.college }
                    )
                )
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
        dsBase: EditText, dsKey: EditText, dsModel: EditText,
        semStart: EditText, semWeeks: EditText, semName: EditText
    ) {
        SettingsStore.saveDeepSeek(this, dsBase.text.toString(), dsKey.text.toString(), dsModel.text.toString())

        val startText = semStart.text.toString().trim()
        val weeksInt = semWeeks.text.toString().trim().toIntOrNull()
        if (ScheduleLogic.parseDate(startText) == null) {
            status.text = "已保存 Key；学期设置未保存：开始日应为 yyyy-MM-dd。"
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
        val dsReady = SettingsStore.deepseek(this).ready()
        paintBadge(dsBadge, dsReady)
    }

    // ---------------- 诊断动作 ----------------

    private fun runSelfCheck() {
        status.text = "自检中…"
        Thread {
            try {
                val report = com.campus.arnav.debug.SelfCheck.format(this)
                runOnUiThread {
                    status.text = report.lineSequence().firstOrNull() ?: "自检完成"
                    AlertDialog.Builder(this)
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

    private fun testProvider(
        base: EditText, key: EditText, model: EditText, vision: Boolean
    ) {
        status.text = "测试中…"
        if (vision) {
            SettingsStore.saveVision(this, base.text.toString(), key.text.toString(), model.text.toString())
        } else {
            SettingsStore.saveDeepSeek(this, base.text.toString(), key.text.toString(), model.text.toString())
        }
        val p = if (vision) SettingsStore.vision(this) else SettingsStore.deepseek(this)
        if (!p.ready()) {
            status.text = "还没填 Key，先填好再测试。"
            return
        }
        Thread {
            try {
                val r = LlmClient.chat(p, listOf(ChatMsg("user", "只回复两个字：正常")))
                runOnUiThread {
                    status.text = (if (vision) "VL 连接成功：" else "连接成功：") + r.take(60)
                    refreshBadges()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "连接失败：${e.message}" }
            }
        }.start()
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

    private fun paintBadge(v: TextView, ready: Boolean) {
        v.text = if (ready) "已配置" else "未配置"
        v.setTextColor(if (ready) Color.parseColor("#FFB067") else Color.parseColor("#FFB86B"))
        v.background = GradientDrawable().apply {
            cornerRadius = dp(9).toFloat()
            setColor(if (ready) Color.parseColor("#3A2A1E") else Color.parseColor("#4A3524"))
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
}