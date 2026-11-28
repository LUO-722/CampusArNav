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
import com.campus.arnav.ui.applySystemBarInsets
import com.campus.arnav.ui.captionText
import com.campus.arnav.ui.card
import com.campus.arnav.ui.chip
import com.campus.arnav.ui.color
import com.campus.arnav.ui.dp
import com.campus.arnav.ui.editText
import com.campus.arnav.ui.ghostButton
import com.campus.arnav.ui.insBackground
import com.campus.arnav.ui.insLightBars
import com.campus.arnav.ui.primaryButton
import com.campus.arnav.ui.textView
import com.campus.arnav.ui.titleText

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
    private lateinit var loginBtn: TextView
    private lateinit var eye: TextView

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

        // 标题区：64dp 圆形 logo + 装饰字体标题 + 副标题
        box.addView(logoView(), LinearLayout.LayoutParams(dp(64), dp(64)))
        box.addView(titleText(this, "科文智嘉").apply {
            setPadding(0, dp(14), 0, 0)
        })
        box.addView(captionText(this, "登录教务系统（可选）· 学号 + 密码").apply {
            setPadding(0, dp(4), 0, dp(18))
        })

        // ① 账号卡
        val accCard = card(this, 18)
        accCard.addView(captionText(this, "学号"))
        accountInput = editText(this, "例如 9926264225", InputType.TYPE_CLASS_TEXT,
            if (session.remember) session.account else "")
        accCard.addView(accountInput, inputLp())

        val pwdHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        pwdHead.addView(captionText(this, "密码"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        eye = TextView(this).apply {
            text = "显示"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.primary))
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener {
                if (passwordInput.transformationMethod == null) {
                    passwordInput.mask()
                    text = "显示"
                } else {
                    passwordInput.inputType = InputType.TYPE_CLASS_TEXT
                    passwordInput.transformationMethod = null
                    text = "隐藏"
                }
            }
        }
        pwdHead.addView(eye, LinearLayout.LayoutParams(dp(56), dp(28)))
        accCard.addView(pwdHead)

        passwordInput = editText(this, "学校内网登录密码", InputType.TYPE_CLASS_TEXT, session.password)
            .apply { mask() }
        accCard.addView(passwordInput, inputLp())

        rememberBox = CheckBox(this).apply {
            text = "记住学号和密码（只存本机，加密保存）"
            textSize = 12f
            setTextColor(color(R.color.text_secondary))
            isChecked = session.remember
            setPadding(0, dp(6), 0, 0)
        }
        accCard.addView(rememberBox)
        box.addView(accCard, cardLp())

        // ② 网络选择卡
        val netCard = card(this, 18)
        netCard.addView(textView(this, "登录网络", 15f, color(R.color.text_primary), bold = true))
        netCard.addView(textView(this,
            "内网：全天可登录（需连校园网）。外网：开放时段 09:00—18:00，时段外可试但可能不通。",
            12f, color(R.color.text_secondary)).apply { setPadding(0, dp(4), 0, dp(10)) })

        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        }
        lanChip = chip(this, "内网 · 全天", mode == AuthStore.MODE_LAN)
        wanChip = chip(this, "外网 · 9:00—18:00", mode != AuthStore.MODE_LAN)
        chipRow.addView(lanChip, LinearLayout.LayoutParams(0, dp(36), 1f))
        chipRow.addView(wanChip, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(8) })
        netCard.addView(chipRow)

        // 内网地址
        lanWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lanWrap.addView(captionText(this, "内网地址"))
        lanInput = editText(this, "http://10.110.225.76/jsxsd/",
            InputType.TYPE_TEXT_VARIATION_URI, session.lanBase)
        lanWrap.addView(lanInput, inputLp())
        netCard.addView(lanWrap)

        // 外网地址
        wanWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wanWrap.addView(captionText(this, "外网地址（学校外网访问入口）").apply {
            setPadding(0, dp(8), 0, 0)
        })
        wanInput = editText(this, AuthStore.DEFAULT_WAN_BASE,
            InputType.TYPE_TEXT_VARIATION_URI, session.wanBase)
        wanWrap.addView(wanInput, inputLp())
        netCard.addView(wanWrap)

        hint = TextView(this).apply {
            textSize = 12.5f
            setTextColor(color(R.color.warn))
            setLineSpacing(0f, 1.35f)
            includeFontPadding = false
            setPadding(0, dp(10), 0, 0)
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
        val refresh = textView(this, "换一张", 12f, color(R.color.primary)).apply {
            setPadding(dp(10), dp(6), 0, dp(6))
            setOnClickListener {
                captchaInput?.setText("")
                setStatus("正在获取验证码…")
                probeCaptcha()
            }
        }
        capRow.addView(refresh)
        captchaRow = capRow
        captchaImage = img
        captchaInput = code
        box.addView(capRow, cardLp())

        // ④ 状态 + 按钮
        status = TextView(this).apply {
            textSize = 12.5f
            setTextColor(color(R.color.text_secondary))
            setPadding(0, dp(14), 0, dp(8))
        }
        box.addView(status)

        loginBtn = primaryButton(this, "登录并同步").apply {
            setOnClickListener { doLogin() }
        }
        box.addView(loginBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        if (session.loggedIn) {
            val enter = ghostButton(this, "直接进入（上次已登录）").apply {
                setOnClickListener { enterApp() }
            }
            box.addView(enter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
                .apply { topMargin = dp(10) })
        }

        val offline = ghostButton(this, "先离线使用（不同步课表）").apply {
            setTextColor(color(R.color.text_secondary))
            setOnClickListener { enterApp() }
        }
        box.addView(offline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
            .apply { topMargin = dp(10) })

        box.addView(textView(this,
            "· 学号密码只保存在本机，加密后存储，不上传任何服务器。\n" +
                "· 登录成功后会把课表、考试、成绩的页面原文存到 App 私有目录，并尝试解析课表。\n" +
                "· 内网只在学校网络里能访问；外网入口 9:00—18:00 开放，已缓存的内容随时可看。",
            12f, color(R.color.text_hint)).apply {
            setPadding(0, dp(18), 0, 0)
            setLineSpacing(0f, 1.5f)
            includeFontPadding = false
        })

        setStatus(if (session.loggedIn) "上次已登录，可直接进入；也可以重新登录以刷新数据。" else "填好学号密码后点「登录并同步」。")
    }

    // ---------------- 视觉小部件 ----------------

    private fun logoView(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color(R.color.primary_soft))
        }
        addView(TextView(this@LoginActivity).apply {
            text = "智"
            textSize = 24f
            setTextColor(color(R.color.primary_deep))
            gravity = Gravity.CENTER
            includeFontPadding = false
        })
    }

    private fun inputLp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(6) }

    private fun setStatus(msg: String, error: Boolean = false) {
        status.text = msg
        status.setTextColor(color(if (error) R.color.danger else R.color.text_secondary))
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
            hint.setTextColor(color(R.color.warn))
        } else {
            val blocked = AuthStore.wanBlocked()
            hint.text = if (blocked) {
                "现在 ${AuthStore.nowText()}，${AuthStore.wanWindowText()}；现在不在开放时段，仍可直接尝试登录。"
            } else {
                "现在 ${AuthStore.nowText()}，外网开放时段内（${AuthStore.wanWindowText()}），可以直接登录。"
            }
            hint.setTextColor(if (blocked) color(R.color.warn) else color(R.color.blue_ink))
        }
    }

    /** 与 Ui.chip 的视觉保持一致：胶囊、选中粉底深字 + 主色描边。 */
    private fun paintChip(v: TextView, selected: Boolean, label: String) {
        v.text = label
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
            setStatus("先填好学号、密码和地址。", error = true)
            return
        }
        Thread {
            val r = JwxtClient.login(base, acc, pwd, "")
            ui {
                if (r.needCaptcha) {
                    showCaptcha(r.captcha, "请输入验证码。")
                } else {
                    setStatus(r.message)
                }
            }
        }.start()
    }

    private fun showCaptcha(bmp: Bitmap?, msg: String) {
        captchaRow?.visibility = View.VISIBLE
        captchaImage?.setImageBitmap(bmp)
        setStatus(if (bmp == null) "$msg（验证码图片没取到，可点「换一张」重试）" else msg)
    }

    private fun doLogin() {
        if (busy) return
        val acc = accountInput.text.toString().trim()
        val pwd = passwordInput.text.toString()
        if (acc.isBlank() || pwd.isBlank()) {
            setStatus("学号和密码都要填。", error = true)
            return
        }
        // 外网时段只做提示，不拦登录：时段内一般更通，时段外也允许试一次，真连不上由网络层报错。
        val outsideWindow = mode == AuthStore.MODE_WAN && AuthStore.wanBlocked()
        val base = base()
        if (base.isBlank()) {
            setStatus(if (mode == AuthStore.MODE_WAN) "外网地址还没填。" else "内网地址不能为空。", error = true)
            return
        }
        val captcha = captchaInput?.text?.toString()?.trim() ?: ""
        busy = true
        loginBtn.isEnabled = false
        loginBtn.text = "连接中…"
        setStatus(if (outsideWindow) {
            "正在连接教务系统…（现在不在外网开放时段，若失败请 9:00—18:00 再试）"
        } else {
            "正在连接教务系统…"
        })

        Thread {
            val r = JwxtClient.login(base, acc, pwd, captcha)
            if (!r.ok) {
                ui {
                    if (r.needCaptcha) showCaptcha(r.captcha, r.message) else setStatus(r.message, error = true)
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
                setStatus("登录成功，开始同步课表 / 考试 / 成绩…")
                loginBtn.text = "同步中…"
                captchaRow?.visibility = View.GONE
            }
            val sync = JwxtClient.sync(this, base) { msg -> ui { status.text = msg } }
            // 课表必须「同步成功 + 非空」才替换本机数据：校园网断、教务维护、外网时段外都可能
            // 返回 ok=false 或解析出 0 门课，这时落库会删掉学生原有课表（与「我的」页行为保持一致）。
            val syncOk = sync.ok
            val coursesOk = syncOk && sync.courses.isNotEmpty()
            if (coursesOk) {
                try {
                    importCourses(sync.courses)
                } catch (e: Exception) {
                    // 落库失败不影响进入应用
                }
            }
            // 考试、成绩也搬进来，但同样**只在解析到数据时才替换**：
            // 教务端解析偶尔会返回 0 条，若无脑覆盖会把上一次拉到的好数据清空（与课表同一条教训）。
            try {
                if (syncOk && sync.exams.isNotEmpty()) CourseStore.replaceExams(this, sync.exams)
                if (syncOk && sync.grades.isNotEmpty()) CourseStore.replaceGrades(this, sync.grades)
            } catch (e: Exception) {
                // 落库失败不影响进入应用
            }
            // 学籍信息（姓名/学号/院系/专业/班级）并进「我的」；学号没解析出来就用登录账号兜底
            if (syncOk) runCatching {
                var jw = sync.profile
                if (jw.sid.isBlank()) jw = jw.copy(sid = acc)
                com.campus.arnav.data.ProfileStore.mergeJwxt(this, jw)
            }
            // 只有真正同步成功才盖「最近同步」时间戳，避免失败后「我的」页显示刚同步过
            if (syncOk) AuthStore.setLastSync(this, System.currentTimeMillis())
            ui {
                busy = false
                loginBtn.isEnabled = true
                loginBtn.text = "登录并同步"
                if (coursesOk) showSyncResult(sync) else showSyncIncomplete(sync)
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
            if (sync.saved.isNotEmpty()) append("\n已保存到本机：" + sync.saved.joinToString("、"))
            if (demoLeft > 0) append("\n\n课表里还有 ").append(demoLeft).append(" 门示例课程，可以一起清掉。")
        }
        val builder = AlertDialog.Builder(this, R.style.Theme_CampusAr_Dialog)
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

    /**
     * 同步失败或没拿到课程：本机课表原样保留，说明原因后直接进应用，不把人卡在登录页。
     * N 取本机现有课程条数，让用户确认「课表还在」。
     */
    private fun showSyncIncomplete(sync: JwxtClient.SyncResult) {
        val local = CourseStore.courses(this).size
        val reason = sync.message.ifBlank { "网络或教务端未返回数据" }
        AlertDialog.Builder(this, R.style.Theme_CampusAr_Dialog)
            .setTitle("同步未完成")
            .setMessage(
                "$reason\n\n已保留原来的课表（$local 条）。\n" +
                    "可稍后在「我的」页重新同步；连校园网或在外网开放时段（09:00—18:00）成功率更高。"
            )
            .setPositiveButton("知道了") { _, _ -> enterApp() }
            .show()
    }

    private fun enterApp() {
        startActivity(Intent(this, MainShellActivity::class.java))
        finish()
    }
}