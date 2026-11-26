package com.campus.arnav

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.campus.arnav.data.AuthStore
import com.campus.arnav.data.Course
import com.campus.arnav.data.CourseStore
import com.campus.arnav.data.SettingsStore
import com.campus.arnav.net.JwxtClient
import com.campus.arnav.ui.insLightBars
import com.campus.arnav.ui.insBackground
import com.campus.arnav.ui.insLightBars
import com.campus.arnav.ui.applySystemBarInsets
import com.campus.arnav.ui.insLightBars
import com.campus.arnav.ui.insBackground
import com.campus.arnav.ui.insLightBars
import com.campus.arnav.ui.insBackground
import com.campus.arnav.ui.insLightBars
import com.campus.arnav.ui.insBackground
import com.campus.arnav.ui.insLightBars
import com.campus.arnav.ui.button
import com.campus.arnav.ui.insLightBars
import com.campus.arnav.ui.insBackground
import com.campus.arnav.ui.insLightBars
import com.campus.arnav.ui.card
import com.campus.arnav.ui.insLightBars
import com.campus.arnav.ui.insBackground
import com.campus.arnav.ui.insLightBars
import com.campus.arnav.ui.color
import com.campus.arnav.ui.insLightBars
import com.campus.arnav.ui.insBackground
import com.campus.arnav.ui.insLightBars
import com.campus.arnav.ui.dp
import com.campus.arnav.ui.insLightBars
import com.campus.arnav.ui.insBackground
import com.campus.arnav.ui.insLightBars
import com.campus.arnav.ui.editText
import com.campus.arnav.ui.insLightBars
import com.campus.arnav.ui.insBackground
import com.campus.arnav.ui.insLightBars
import com.campus.arnav.ui.rounded
import com.campus.arnav.ui.insLightBars
import com.campus.arnav.ui.insBackground
import com.campus.arnav.ui.insLightBars
import com.campus.arnav.ui.textView

/**
 * 登录页：学号 + 密码 + 内网/外网选择，登录教务系统并首次同步课表/考试/成绩。
 *
 * - 内网全天可用；外网入口开放时段 09:00—18:00，时段外只做提示、仍可尝试（已缓存内容照常看）。
 * - 账号密码用 AndroidKeyStore 加密后存本机；勾掉「记住」就不留密码。
 * - 登录成功后会把课表/考试/成绩页面原文存到 filesDir/jwxt/，并尽力解析课表入库。
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var accountInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var lanInput: EditText
    private lateinit var wanInput: EditText
    private lateinit var rememberBox: CheckBox
    private lateinit var lanChip: TextView
    private lateinit var wanChip: TextView
    private lateinit var lanWrap: LinearLayout
    private lateinit var wanWrap: LinearLayout
    private lateinit var hint: TextView
    private lateinit var status: TextView
    private lateinit var loginBtn: Button

    private var captchaRow: LinearLayout? = null
    private var captchaImage: ImageView? = null
    private var captchaInput: EditText? = null

    private var mode = AuthStore.MODE_LAN
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        insLightBars()
        SettingsStore.applySemester(this)

        val root = ScrollView(this).apply {
            background = insBackground(this@LoginActivity)
            isFillViewport = true
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(24))
        }
        root.addView(box)
        setContentView(root)
        root.applySystemBarInsets { top, bottom, _, _ ->
            box.setPadding(dp(18), dp(24) + top, dp(18), dp(24) + bottom)
        }

        val session = AuthStore.session(this)
        mode = session.mode

        // 标题
        box.addView(textView(this, "科文智嘉", 30f, color(R.color.text_primary), bold = true))
        box.addView(textView(this, "登录教务系统 · 学号 + 密码", 13f, color(R.color.text_secondary))
            .apply { setPadding(0, dp(4), 0, dp(18)) })

        // ① 账号卡
        val accCard = card(this, 14)
        accCard.addView(textView(this, "学号", 11f, color(R.color.text_secondary)))
        accountInput = editText(this, "例如 2026010101", InputType.TYPE_CLASS_TEXT,
            if (session.remember) session.account else "")
        accCard.addView(accountInput)

        accCard.addView(textView(this, "密码", 11f, color(R.color.text_secondary))
            .apply { setPadding(0, dp(8), 0, 0) })
        passwordInput = editText(this, "学校内网登录密码", InputType.TYPE_CLASS_TEXT, session.password)
            .apply { mask() }
        accCard.addView(passwordInput)
        accCard.addView(textView(this, "显示密码", 11f, color(R.color.accent)).apply {
            setPadding(0, dp(6), 0, 0)
            setOnClickListener {
                if (passwordInput.transformationMethod == null) {
                    passwordInput.mask()
                    text = "显示密码"
                } else {
                    passwordInput.inputType = InputType.TYPE_CLASS_TEXT
                    passwordInput.transformationMethod = null
                    text = "隐藏密码"
                }
            }
        })
        rememberBox = CheckBox(this).apply {
            text = "记住学号和密码（只存本机，加密保存）"
            textSize = 12f
            setTextColor(color(R.color.text_secondary))
            isChecked = session.remember
        }
        accCard.addView(rememberBox)
        box.addView(accCard, cardLp())

        // ② 网络选择卡
        val netCard = card(this, 14)
        netCard.addView(textView(this, "登录网络", 16f, color(R.color.text_primary), bold = true))
        netCard.addView(textView(this,
            "内网：全天可登录（需连校园网）。外网：开放时段 09:00—18:00，时段外可试但可能不通。",
            11f, color(R.color.text_secondary)).apply { setPadding(0, dp(4), 0, dp(10)) })

        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        }
        lanChip = chip("内网 · 全天")
        wanChip = chip("外网 · 9:00—18:00")
        chipRow.addView(lanChip, LinearLayout.LayoutParams(0, dp(40), 1f))
        chipRow.addView(wanChip, LinearLayout.LayoutParams(0, dp(40), 1f).apply { leftMargin = dp(8) })
        netCard.addView(chipRow)

        // 内网地址
        lanWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lanWrap.addView(textView(this, "内网地址", 11f, color(R.color.text_secondary)))
        lanInput = editText(this, "http://10.110.225.76/jsxsd/",
            InputType.TYPE_TEXT_VARIATION_URI, session.lanBase)
        lanWrap.addView(lanInput)
        netCard.addView(lanWrap)

        // 外网地址
        wanWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wanWrap.addView(textView(this, "外网地址（学校外网访问入口）", 11f, color(R.color.text_secondary))
            .apply { setPadding(0, dp(8), 0, 0) })
        wanInput = editText(this, AuthStore.DEFAULT_WAN_BASE,
            InputType.TYPE_TEXT_VARIATION_URI, session.wanBase)
        wanWrap.addView(wanInput)
        netCard.addView(wanWrap)

        hint = textView(this, "", 11f, Color.parseColor("#E8447A")).apply {
            setPadding(0, dp(8), 0, 0)
        }
        netCard.addView(hint)
        box.addView(netCard, cardLp())

        lanChip.setOnClickListener { setMode(AuthStore.MODE_LAN) }
        wanChip.setOnClickListener { setMode(AuthStore.MODE_WAN) }
        setMode(mode)

        // ③ 验证码行（默认隐藏）
        val capRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        val img = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            minimumWidth = dp(96)
            minimumHeight = dp(38)
        }
        val code = editText(this, "验证码", InputType.TYPE_CLASS_TEXT).apply {
            textSize = 14f
        }
        capRow.addView(img, LinearLayout.LayoutParams(dp(110), dp(42)))
        capRow.addView(code, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { leftMargin = dp(8) })
        val refresh = textView(this, "换一张", 12f, color(R.color.accent)).apply {
            setPadding(dp(10), dp(6), 0, dp(6))
            setOnClickListener {
                captchaInput?.setText("")
                status.text = "正在获取验证码…"
                probeCaptcha()
            }
        }
        capRow.addView(refresh)
        captchaRow = capRow
        captchaImage = img
        captchaInput = code
        box.addView(capRow, cardLp())

        // ④ 状态 + 按钮
        status = textView(this, "", 12f, color(R.color.text_secondary)).apply {
            setPadding(0, dp(14), 0, dp(6))
        }
        box.addView(status)

        loginBtn = button(this, "登录并同步", color(R.color.accent), Color.parseColor("#FFFFFF")).apply {
            textSize = 16f
            setOnClickListener { doLogin() }
        }
        box.addView(loginBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        if (session.loggedIn) {
            val enter = button(this, "直接进入（上次已登录，用已缓存内容）",
                color(R.color.bg_card), color(R.color.text_primary)).apply {
                textSize = 14f
                setOnClickListener { enterApp() }
            }
            box.addView(enter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
                .apply { topMargin = dp(10) })
        }

        val offline = button(this, "先离线使用（不同步课表）", color(R.color.bg_card), color(R.color.text_secondary)).apply {
            textSize = 13f
            setOnClickListener { enterApp() }
        }
        box.addView(offline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
            .apply { topMargin = dp(10) })

        box.addView(textView(this,
            "· 学号密码只存在本机：密码用 AndroidKeyStore 加密，不上传任何服务器。\n" +
                "· 登录成功后会把课表、考试、成绩页面原文存到 App 私有目录，并尝试解析课表。\n" +
                "· 内网地址 10.x 只在校内网络可访问；外网入口开放 09:00—18:00，已缓存内容随时可看。",
            11f, color(R.color.text_secondary)).apply { setPadding(0, dp(16), 0, 0) })

        status.text = if (session.loggedIn) "上次已登录，可直接进入；也可以重新登录以刷新数据。" else "填好学号密码后点「登录并同步」。"
    }

    // ---------------- 交互 ----------------

    private fun setMode(m: String) {
        mode = m
        val lan = m == AuthStore.MODE_LAN
        paintChip(lanChip, lan, "内网 · 全天")
        paintChip(wanChip, !lan, "外网 · 9:00—18:00")
        lanWrap.visibility = if (lan) View.VISIBLE else View.GONE
        wanWrap.visibility = if (lan) View.GONE else View.VISIBLE
        if (lan) {
            hint.text = "内网全天可登录，需要手机连着校园网。"
            hint.setTextColor(Color.parseColor("#E8447A"))
        } else {
            val blocked = AuthStore.wanBlocked()
            hint.text = if (blocked) {
                "现在 ${AuthStore.nowText()}，${AuthStore.wanWindowText()}；现在不在开放时段，仍可直接尝试登录。"
            } else {
                "现在 ${AuthStore.nowText()}，外网开放时段内（${AuthStore.wanWindowText()}），可以直接登录。"
            }
            hint.setTextColor(if (blocked) Color.parseColor("#E8447A") else Color.parseColor("#E8447A"))
        }
    }

    private fun chip(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(8), 0, dp(8), 0)
    }

    private fun paintChip(v: TextView, selected: Boolean, label: String) {
        v.text = label
        v.setTextColor(if (selected) color(R.color.accent) else color(R.color.text_secondary))
        v.background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (selected) Color.parseColor("#FFE3EE") else color(R.color.bg_dark))
            if (selected) setStroke(dp(1), color(R.color.accent))
        }
    }

    private fun EditText.mask() {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        transformationMethod = PasswordTransformationMethod.getInstance()
    }

    private fun cardLp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(10) }

    private fun base(): String =
        if (mode == AuthStore.MODE_WAN) wanInput.text.toString().trim()
        else lanInput.text.toString().trim()

    private fun ui(block: () -> Unit) = runOnUiThread(block)

    // ---------------- 登录 ----------------

    private fun probeCaptcha() {
        val base = base()
        val acc = accountInput.text.toString().trim()
        val pwd = passwordInput.text.toString()
        if (base.isBlank() || acc.isBlank() || pwd.isBlank()) {
            status.text = "先填好学号、密码和地址。"
            return
        }
        Thread {
            val r = JwxtClient.login(base, acc, pwd, "")
            ui {
                if (r.needCaptcha) {
                    showCaptcha(r.captcha, "请输入验证码。")
                } else {
                    status.text = r.message
                }
            }
        }.start()
    }

    private fun showCaptcha(bmp: Bitmap?, msg: String) {
        captchaRow?.visibility = View.VISIBLE
        captchaImage?.setImageBitmap(bmp)
        status.text = if (bmp == null) "$msg（验证码图片没取到，可点「换一张」重试）" else msg
    }

    private fun doLogin() {
        if (busy) return
        val acc = accountInput.text.toString().trim()
        val pwd = passwordInput.text.toString()
        if (acc.isBlank() || pwd.isBlank()) {
            status.text = "学号和密码都要填。"
            return
        }
        // 外网时段只做提示，不拦登录：时段内一般更通，时段外也允许试一次，真连不上由网络层报错。
        val outsideWindow = mode == AuthStore.MODE_WAN && AuthStore.wanBlocked()
        val base = base()
        if (base.isBlank()) {
            status.text = if (mode == AuthStore.MODE_WAN) "外网地址还没填。" else "内网地址不能为空。"
            return
        }
        val captcha = captchaInput?.text?.toString()?.trim() ?: ""
        busy = true
        loginBtn.isEnabled = false
        loginBtn.text = "连接中…"
        status.text = if (outsideWindow) {
            "正在连接教务系统…（现在不在外网开放时段，若失败请 9:00—18:00 再试）"
        } else {
            "正在连接教务系统…"
        }

        Thread {
            val r = JwxtClient.login(base, acc, pwd, captcha)
            if (!r.ok) {
                ui {
                    if (r.needCaptcha) showCaptcha(r.captcha, r.message) else status.text = r.message
                    busy = false
                    loginBtn.isEnabled = true
                    loginBtn.text = "登录并同步"
                }
                return@Thread
            }
            AuthStore.saveCredentials(this, acc, pwd, mode,
                lanInput.text.toString().trim(), wanInput.text.toString().trim(),
                rememberBox.isChecked)
            AuthStore.setLoggedIn(this, true)
            ui {
                status.text = "登录成功，开始同步课表 / 考试 / 成绩…"
                loginBtn.text = "同步中…"
                captchaRow?.visibility = View.GONE
            }
            val sync = JwxtClient.sync(this, base) { msg -> ui { status.text = msg } }
            val imported = try {
                importCourses(sync.courses)
            } catch (e: Exception) {
                0
            }
            // 考试、成绩也一起搬进来；学生信息补进「我的」
            try {
                if (sync.exams.isNotEmpty()) CourseStore.replaceExams(this, sync.exams)
                if (sync.grades.isNotEmpty()) CourseStore.replaceGrades(this, sync.grades)
            } catch (e: Exception) {
                // 落库失败不影响进入应用
            }
            // 学籍信息（姓名/学号/院系/专业/班级）并进「我的」；学号没解析出来就用登录账号兜底
            runCatching {
                var jw = sync.profile
                if (jw.sid.isBlank()) jw = jw.copy(sid = acc)
                com.campus.arnav.data.ProfileStore.mergeJwxt(this, jw)
            }
            AuthStore.setLastSync(this, System.currentTimeMillis())
            ui {
                busy = false
                loginBtn.isEnabled = true
                loginBtn.text = "登录并同步"
                showSyncResult(sync)
            }
        }.start()
    }

    /** 把解析出的课程写库：先清掉上次从教务系统导入的，再按“同名同节次”去重插入。 */
    private fun importCourses(list: List<Course>): Int = CourseStore.replaceJwxtCourses(this, list)

    private fun showSyncResult(sync: JwxtClient.SyncResult) {
        val imported = sync.courses.size
        val exams = sync.exams.size
        val grades = sync.grades.size
        val demoLeft = CourseStore.courses(this).count { it.source == "demo" }
        val text = buildString {
            append(sync.message)
            append("\n课表课程 ").append(imported).append(" 门")
            append("，考试 ").append(exams).append(" 条")
            append("，成绩 ").append(grades).append(" 条")
            if (sync.saved.isNotEmpty()) append("\n本机存档：" + sync.saved.joinToString("、"))
            if (demoLeft > 0) append("\n\n课表里还有 ").append(demoLeft).append(" 门示例课程，可以一起清掉。")
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("同步完成")
            .setMessage(text)
            .setPositiveButton("进入应用") { _, _ -> enterApp() }
            .setNegativeButton("留在此页", null)
        if (demoLeft > 0) {
            builder.setNeutralButton("清掉示例课并进入") { _, _ ->
                CourseStore.courses(this).filter { it.source == "demo" }.forEach {
                    CourseStore.deleteCourse(this, it.id)
                }
                enterApp()
            }
        }
        builder.show()
    }

    private fun enterApp() {
        startActivity(Intent(this, MainShellActivity::class.java))
        finish()
    }
}