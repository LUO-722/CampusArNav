package com.campus.arnav

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.campus.arnav.core.PoiStore
import com.campus.arnav.core.Reminder
import com.campus.arnav.data.AuthStore
import com.campus.arnav.data.ChatStore
import com.campus.arnav.data.CourseStore
import com.campus.arnav.data.ProfileStore
import com.campus.arnav.data.ScheduleLogic
import com.campus.arnav.data.SettingsStore
import com.campus.arnav.kb.KbIndex
import com.campus.arnav.net.JwxtClient
import com.campus.arnav.ui.applySystemBarInsets
import com.campus.arnav.ui.badge
import com.campus.arnav.ui.captionText
import com.campus.arnav.ui.card
import com.campus.arnav.ui.chip
import com.campus.arnav.ui.color
import com.campus.arnav.ui.confirm
import com.campus.arnav.ui.divider
import com.campus.arnav.ui.dp
import com.campus.arnav.ui.editText
import com.campus.arnav.ui.ghostButton
import com.campus.arnav.ui.insBackground
import com.campus.arnav.ui.insLightBars
import com.campus.arnav.ui.primaryButton
import com.campus.arnav.ui.sectionTitle
import com.campus.arnav.ui.textView
import com.campus.arnav.ui.titleText

/**
 * 设置页：按「卡片 + 状态徽标」组织，一屏能看完，且没有任何需要用户填的云端配置。
 *
 * 结构：
 *   ① AI 服务 —— 只读状态：内置云端模型 + 断网自动离线
 *   ② 教务系统（登录 / 同步 / 退出，登录是可选的）
 *   ③ 提醒 / 学期设置 / 数据与诊断
 * 底部固定的「保存设置」只提交学期。
 * 云端通道在构建期注入，用户侧不需要 Key、地址或代理。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var aiBadge: TextView
    private var authBadge: TextView? = null
    private val remindChips = ArrayList<TextView>()
    private var remindChipMinutes = intArrayOf(5, 10, 30)

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

        // 顶部：36dp 圆形返回 + 装饰字体标题
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = "‹"
            textSize = 22f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(color(R.color.text_primary))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color(R.color.bg_card))
                setStroke(dp(1), color(R.color.border))
            }
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        titleRow.addView(titleText(this, "设置"), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(12) })
        box.addView(titleRow)
        box.addView(captionText(this, "无需任何配置：有网络自动用云端模型，没有网络自动用离线知识库 + 本机规则。").apply {
            setPadding(0, dp(6), 0, dp(4))
        })

        // ① AI 服务：内置，界面上不出现任何接口地址 / API Key / 模型配置
        aiBadge = badge(this, "已接入")
        val aiCard = card(this, 18)
        aiCard.addView(cardHeader("AI 服务", aiBadge))
        aiCard.addView(aiInfoRow())
        box.addView(aiCard, cardLp())

        // ② 教务系统（登录态 / 同步）
        val authCard = card(this, 18)
        val auth = AuthStore.session(this)
        authBadge = badge(this, if (auth.loggedIn) "已登录" else "未登录")
        authCard.addView(cardHeader("教务系统", authBadge))
        authCard.addView(captionText(this, authSummary(auth)).apply {
            setPadding(0, dp(8), 0, dp(6))
        })
        authCard.addView(infoRow("学号", auth.account.ifBlank { "未填写" }))
        authCard.addView(divider(this))
        authCard.addView(infoRow("登录网络", auth.modeLabel() + " · " + auth.base().ifBlank { "地址未填" }))
        authCard.addView(divider(this))
        authCard.addView(infoRow("最近同步", AuthStore.lastSyncText(this)))
        authCard.addView(actionRow(
            Triple("立即同步", false) { syncNow() },
            Triple("重新登录", false) { relaunchLogin() }
        ))
        authCard.addView(actionRow(
            Triple("退出登录", true) {
                confirm(this, "退出教务登录", "会清掉本机保存的密码与登录状态，课表等已缓存内容保留。", "退出") {
                    AuthStore.logout(this)
                    authBadge?.let { paintBadge(it, false, "已登录", "未登录") }
                    status.text = "已退出教务登录。"
                    setResult(RESULT_OK)
                }
            }
        ))
        box.addView(authCard, cardLp())

        // ③ 图片识别（本机 OCR）
        val ocrCard = card(this, 18)
        ocrCard.addView(cardHeader("图片识别", null))
        ocrCard.addView(textView(this,
            "智嘉选图、对话发图时，有网络会交内置看图模型理解整张图，断网时用手机本机的 ML Kit 中文 OCR 兜底；" +
                "只把识别结果用于本次处理，图片不留存。\n" +
                "适合截图、票据、通知照片、界面文字。识别结果会先填入文字框，可修改后再提取。",
            12.5f, color(R.color.text_secondary)
        ).apply { setPadding(0, dp(8), 0, 2) })
        box.addView(ocrCard, cardLp())

        // ④ 提醒（课程 / 活动开始前 N 分钟发通知）
        val remindCard = card(this, 18)
        remindCard.addView(cardHeader("提醒", null))
        val remindBox = CheckBox(this).apply {
            text = "开启提醒（当前提前 ${SettingsStore.remindBefore(this@SettingsActivity)} 分钟）"
            isChecked = SettingsStore.remindEnabled(this@SettingsActivity)
            setTextColor(color(R.color.text_primary))
            textSize = 13f
            buttonTintList = ColorStateList.valueOf(color(R.color.accent))
            setPadding(0, dp(4), 0, 0)
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
            12.5f, color(R.color.text_secondary)
        ).apply { setPadding(0, dp(6), 0, dp(6)) })

        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        val curMinutes = SettingsStore.remindBefore(this)
        remindChips.clear()
        remindChipMinutes.forEachIndexed { i, min ->
            val c = chip(this, "$min 分钟", min == curMinutes)
            c.setOnClickListener {
                setRemind(this@SettingsActivity, min)
                remindChips.forEachIndexed { idx, cv ->
                    paintChip(cv, remindChipMinutes[idx] == SettingsStore.remindBefore(this@SettingsActivity))
                }
            }
            remindChips.add(c)
            chipRow.addView(c, LinearLayout.LayoutParams(0, dp(32), 1f).apply {
                if (i > 0) leftMargin = dp(8)
            })
        }
        remindCard.addView(chipRow)
        box.addView(remindCard, cardLp())

        // ⑤ 学期设置
        val semCard = card(this, 18)
        semCard.addView(cardHeader("学期设置", null))
        val semStart = field(semCard, "学期开始日（第 1 周周一）", SettingsStore.semesterStart(this))
        val semWeeks = field(semCard, "总教学周数", SettingsStore.semesterWeeks(this).toString()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val semName = field(semCard, "学期名称", SettingsStore.semesterName(this))
        semCard.addView(actionRow(
            Triple("恢复默认", false) {
                semStart.setText(ScheduleLogic.DEFAULT_SEMESTER_START)
                semWeeks.setText(ScheduleLogic.DEFAULT_TOTAL_WEEKS.toString())
                semName.setText(ScheduleLogic.DEFAULT_SEMESTER_NAME)
                status.text = "已填入默认值，记得点底部「保存设置」。"
            },
            Triple("本学期概况", false) {
                status.text = "${SettingsStore.semesterName(this)} · 开始 ${SettingsStore.semesterStart(this)} · 共 ${SettingsStore.semesterWeeks(this)} 周 · 当前第 ${ScheduleLogic.currentWeek()} 周"
            }
        ))
        box.addView(semCard, cardLp())

        // ⑥ 数据与诊断
        val toolCard = card(this, 18)
        toolCard.addView(cardHeader("数据与诊断", null))
        toolCard.addView(actionRow(
            Triple("运行离线自检", false) { runSelfCheck() },
            Triple("检查知识库", false) { runKbCheck() }
        ))
        toolCard.addView(actionRow(
            Triple("导出课表", false) { shareTimetable() },
            Triple("清空对话", true) {
                confirm(this, "清空对话记录", "只删除本机聊天记录，不影响课表与设置。", "清空") {
                    ChatStore.clear(this)
                    status.text = "对话记录已清空。"
                    setResult(RESULT_OK)
                }
            }
        ))
        box.addView(toolCard, cardLp())

        // ⑦ 地图与定位（坐标口径说明 + 两个一次性清理入口）
        val mapCard = card(this, 18)
        mapCard.addView(cardHeader("地图与定位", null))
        mapCard.addView(textView(this,
            "坐标基准：GCJ-02（国内底图口径）。手机 GPS 已自动换算，界面里的蓝点与楼栋底图不会再偏。\n" +
                "旧版「取点校准」存的是 GPS 原始坐标（WGS-84），按现在的口径解释会偏约 560 米 —— 可以一键清理后重新取点。",
            12.5f, color(R.color.text_secondary)
        ).apply { setPadding(0, dp(8), 0, dp(6)) })
        mapCard.addView(actionRow(
            Triple("清理旧的取点数据", false) { confirmPurgeLegacy() }
        ))
        mapCard.addView(actionRow(
            Triple("重置底图偏好", false) { resetBaseKind() }
        ))
        box.addView(mapCard, cardLp())

        // 状态行 + 保存
        status = textView(this, "", 12.5f, color(R.color.text_secondary)).apply {
            setPadding(0, dp(12), 0, dp(8))
        }
        box.addView(status)

        val save = primaryButton(this, "保存设置").apply {
            setOnClickListener {
                saveAll(semStart, semWeeks, semName)
            }
        }
        box.addView(save, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            bottomMargin = dp(20)
        })

        box.addView(textView(this,
            "· 打开即用：云端模型已内置，无需任何配置。\n" +
                "· 有网络时自动接入云端（问答、录活动、写课表都在云端判断）；断网自动切离线知识库 + 本机规则。\n" +
                "· 图片文字识别走手机本机 ML Kit 中文 OCR，离线、免费、不传图片。\n" +
                "· 路线跳转走系统地图 App（URI），无需配置。",
            12f, color(R.color.text_hint)
        ).apply {
            setPadding(0, dp(2), 0, 0)
            setLineSpacing(0f, 1.5f)
            includeFontPadding = false
        })

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
        paintBadge(aiBadge, SettingsStore.aiApiReady(this), "已接入", "离线")
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
    ).apply { topMargin = dp(14) }

    /** 卡片左上标题（带粉色竖条）+ 可选徽标。 */
    private fun cardHeader(title: String, badgeView: TextView?): LinearLayout {
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(sectionTitle(this@SettingsActivity, title),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        badgeView?.let { head.addView(it) }
        return head
    }

    /** 卡内一行：左 label 12.5sp 次要色、右 value 14sp 主文字色，行高 44dp。 */
    private fun infoRow(label: String, value: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
        }
        row.addView(captionText(this, label),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(textView(this, value, 14f, color(R.color.text_primary)).apply {
            gravity = Gravity.END
            maxLines = 2
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    /** t-3：状态徽标；paintBadge 负责「已配置/未配置」双态配色。 */
    private fun paintBadge(
        v: TextView, ready: Boolean,
        readyText: String = "已配置", offText: String = "未配置"
    ) {
        v.text = if (ready) readyText else offText
        v.isEnabled = ready
        v.alpha = if (ready) 1f else 0.45f
        v.setTextColor(if (ready) color(R.color.accent_ink) else color(R.color.blue_ink))
        v.background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(if (ready) color(R.color.accent_soft) else color(R.color.blue_soft))
        }
    }

    /** 与 Ui.chip 的视觉保持一致。 */
    private fun paintChip(v: TextView, selected: Boolean) {
        v.textSize = 13f
        v.gravity = Gravity.CENTER
        v.setPadding(dp(12), 0, dp(12), 0)
        v.setTextColor(if (selected) color(R.color.accent_ink) else color(R.color.text_secondary))
        v.background = GradientDrawable().apply {
            cornerRadius = dp(999).toFloat()
            setColor(if (selected) color(R.color.accent_soft) else color(R.color.bg_card))
            setStroke(dp(1), if (selected) color(R.color.accent) else color(R.color.border))
        }
    }

    private fun field(parent: LinearLayout, label: String, value: String): EditText {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        wrap.addView(captionText(this, label))
        val e = editText(this, label, InputType.TYPE_CLASS_TEXT, value)
        wrap.addView(e, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })
        parent.addView(wrap, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return e
    }

    private fun actionRow(vararg actions: Triple<String, Boolean, () -> Unit>): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
            for ((i, a) in actions.withIndex()) {
                val b = ghostButton(this@SettingsActivity, a.first).apply {
                    if (a.second) setTextColor(color(R.color.danger))
                    setOnClickListener { a.third.invoke() }
                }
                addView(b, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                    if (i > 0) leftMargin = dp(8)
                })
            }
        }

    /** AI 服务卡内的两行说明（左侧 success 小圆点）。 */
    private fun aiInfoRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        row.addView(android.view.View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color(R.color.success))
            }
        }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(10) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(textView(this, "阿里云百炼 · 主模型 DeepSeek-V4.1-Flash", 14f, color(R.color.text_primary)))
        texts.addView(textView(this, "已内置，无需任何配置；无网络时自动切离线知识库", 12f, color(R.color.text_hint)).apply {
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    /** ② 地图与定位：清理旧口径的取点数据（二次确认）。 */
    private fun confirmPurgeLegacy() {
        AlertDialog.Builder(this, R.style.Theme_CampusAr_Dialog)
            .setTitle("清理旧的取点数据")
            .setMessage(
                "会删掉旧版「取点校准」保存的自定义取点（当时存的是 GPS 原始坐标，和现在的底图差约 560 米），" +
                    "楼栋默认坐标和其它设置不受影响。\n" +
                    "清理后如果还想要精确到门口，重新去「③ 校园取点校准」站到楼门口点一次即可。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("清理") { _, _ ->
                val n = PoiStore.purgeLegacy(this)
                status.text = if (n > 0) "已清理 $n 条旧取点，可以重新取点校准。" else "没有需要清理的旧取点：现有取点都是新口径。"
                android.widget.Toast.makeText(
                    this,
                    if (n > 0) "已清理 $n 条旧取点" else "没有旧取点需要清理",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                setResult(RESULT_OK)
            }
            .show()
    }

    /** ⑦ 地图与定位：把底图偏好恢复成默认卫星图（下次进导航页生效）。 */
    private fun resetBaseKind() {
        val prefs = getSharedPreferences("zhijia_map", MODE_PRIVATE)
        val had = prefs.contains("base_kind")
        prefs.edit().remove("base_kind").apply()
        status.text = if (had) "已恢复默认卫星图，下次打开 App 生效。" else "当前就是默认卫星图，没有需要重置的偏好。"
        android.widget.Toast.makeText(
            this,
            if (had) "已恢复默认卫星图（下次进入 App 生效）" else "已经是默认卫星图",
            android.widget.Toast.LENGTH_SHORT
        ).show()
        setResult(RESULT_OK)
    }

    /** 改提前分钟数：立刻重排提醒，并给出反馈。 */
    private fun setRemind(ctx: android.content.Context, minutes: Int) {
        SettingsStore.setRemindBefore(ctx, minutes)
        SettingsStore.setRemindEnabled(ctx, true)
        Reminder.scheduleAsync(ctx)
        android.widget.Toast.makeText(ctx, "已设为提前 $minutes 分钟提醒", android.widget.Toast.LENGTH_SHORT).show()
    }
}