package com.campus.arnav

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.campus.arnav.data.CourseStore
import com.campus.arnav.data.JwStore
import com.campus.arnav.data.SettingsStore
import com.campus.arnav.net.JwChannel
import com.campus.arnav.net.JwResult
import com.campus.arnav.net.JwxtClient
import com.campus.arnav.ui.button
import com.campus.arnav.ui.card
import com.campus.arnav.ui.color
import com.campus.arnav.ui.dp
import com.campus.arnav.ui.rounded
import com.campus.arnav.ui.textView
import com.campus.arnav.ui.toast
import java.util.concurrent.Executors

/**
 * 启动登录页。
 *
 * 流程：
 *  1. 选择通道（内网 / 外网；外网仅 18:00–次日 09:00 可用）
 *  2. 输入学号 / 密码 / 验证码
 *  3. 登录成功后把账号密码保存在本机，并一次性抓取学校网址里的
 *     课表、成绩、考试安排、个人信息，落到 jw_cache.json
 *  4. 课表自动导入课程表；进入主界面
 */
class LoginActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()

    private lateinit var accountEt: EditText
    private lateinit var passwordEt: EditText
    private lateinit var captchaEt: EditText
    private lateinit var captchaImg: ImageView
    private lateinit var loginBtn: Button
    private lateinit var channelHint: android.widget.TextView
    private lateinit var statusTv: android.widget.TextView
    private lateinit var innerBtn: Button
    private lateinit var outerBtn: Button
    private lateinit var cacheTv: android.widget.TextView
    private lateinit var autoCb: android.widget.CheckBox
    private lateinit var captchaRow: LinearLayout

    private var channel: JwChannel = JwChannel.INTRANET
    private var session: JwxtClient.Session? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        channel = JwChannel.of(JwStore.channel(this))
        renderChannel()
        prefill()
        refreshCaptcha()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }

    // ---------------- UI ----------------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.bg_dark))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(28))
        }
        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 标题
        col.addView(textView(this, "科文智嘉", 30f, color(R.color.text_primary), bold = true))
        col.addView(textView(this, "登录教务系统", 14f, color(R.color.text_secondary)).apply {
            setPadding(0, dp(6), 0, dp(22))
        })

        // 通道选择
        val chanCard = card(this)
        chanCard.addView(textView(this, "登录通道", 13f, color(R.color.text_secondary)))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        innerBtn = button(this, "内网").apply { setOnClickListener { pick(JwChannel.INTRANET) } }
        outerBtn = button(this, "外网").apply { setOnClickListener { pick(JwChannel.EXTRANET) } }
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.rightMargin = dp(8)
        row.addView(innerBtn, lp)
        row.addView(outerBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        chanCard.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        channelHint = textView(this, "", 12f, color(R.color.text_secondary)).apply {
            setPadding(0, dp(10), 0, 0)
        }
        chanCard.addView(channelHint)
        col.addView(chanCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14)
        })

        // 账号
        val accCard = card(this)
        accCard.addView(textView(this, "学号", 13f, color(R.color.text_secondary)))
        accountEt = edit("请输入学号", InputType.TYPE_CLASS_NUMBER)
        accCard.addView(accountEt)
        accCard.addView(textView(this, "密码（与教务系统一致）", 13f, color(R.color.text_secondary)).apply {
            setPadding(0, dp(12), 0, 0)
        })
        passwordEt = edit("请输入密码", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        accCard.addView(passwordEt)

        accCard.addView(textView(this, "验证码", 13f, color(R.color.text_secondary)).apply {
            setPadding(0, dp(12), 0, 0)
        })
        captchaRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        captchaEt = edit("请输入验证码", InputType.TYPE_CLASS_TEXT)
        val capLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        capLp.rightMargin = dp(10)
        captchaRow.addView(captchaEt, capLp)
        captchaImg = ImageView(this).apply {
            background = rounded(this@LoginActivity, color(R.color.bg_dark), 8)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { refreshCaptcha() }
        }
        captchaRow.addView(captchaImg, LinearLayout.LayoutParams(dp(120), dp(48)))
        accCard.addView(captchaRow)
        accCard.addView(textView(this, "看不清？点图片换一张", 11f, color(R.color.text_secondary)).apply {
            setPadding(0, dp(6), 0, 0)
        })
        col.addView(accCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14)
        })

        // 免登录
        autoCb = android.widget.CheckBox(this).apply {
            text = "记住账号密码，下次自动登录"
            setTextColor(color(R.color.text_secondary))
            textSize = 13f
            isChecked = JwStore.autoLogin(this@LoginActivity)
        }
        col.addView(autoCb)

        // 登录按钮
        loginBtn = button(this, "登录并同步", color(R.color.accent), color(R.color.bg_dark)).apply {
            setOnClickListener { doLogin() }
        }
        col.addView(loginBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        statusTv = textView(this, "", 12f, color(R.color.text_secondary)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        col.addView(statusTv)

        // 已有缓存
        cacheTv = textView(this, "", 12f, color(R.color.text_secondary)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(18), 0, 0)
        }
        col.addView(cacheTv)

        setContentView(root)
    }

    private fun edit(hint: String, inputType: Int): EditText = EditText(this).apply {
        this.hint = hint
        textSize = 15f
        setTextColor(color(R.color.text_primary))
        setHintTextColor(color(R.color.text_secondary))
        this.inputType = inputType
        background = rounded(this@LoginActivity, color(R.color.bg_dark), 10)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(6)
        layoutParams = lp
    }

    // ---------------- 状态 ----------------

    private fun pick(c: JwChannel) {
        if (busy) return
        channel = c
        renderChannel()
        refreshCaptcha()
    }

    private fun renderChannel() {
        val active = rounded(this, color(R.color.accent), 12)
        val idle = rounded(this, color(R.color.bg_dark), 12)
        innerBtn.background = if (channel == JwChannel.INTRANET) active else idle
        innerBtn.setTextColor(if (channel == JwChannel.INTRANET) color(R.color.bg_dark) else color(R.color.text_secondary))
        outerBtn.background = if (channel == JwChannel.EXTRANET) active else idle
        outerBtn.setTextColor(if (channel == JwChannel.EXTRANET) color(R.color.bg_dark) else color(R.color.text_secondary))

        val blocked = channel == JwChannel.EXTRANET && !JwxtClient.extranetAllowedNow()
        channelHint.text = when {
            channel == JwChannel.INTRANET -> "内网：全天可用（需连接校园网）· ${JwxtClient.beijingNow()}"
            blocked -> "外网当前不可用：仅 18:00–次日 09:00 开放 · 现在 ${JwxtClient.beijingNow()}"
            else -> "外网：可用（18:00–次日 09:00 开放）· 现在 ${JwxtClient.beijingNow()}"
        }
        channelHint.setTextColor(if (blocked) 0xFFFF6B6B.toInt() else color(R.color.text_secondary))
        loginBtn.isEnabled = !blocked && !busy
        loginBtn.alpha = if (blocked) 0.5f else 1f
    }

    private fun prefill() {
        if (JwStore.hasCredentials(this)) {
            accountEt.setText(JwStore.account(this))
            passwordEt.setText(JwStore.password(this))
            cacheTv.text = "本机已保存：${JwStore.cacheSummary(this)}"
        } else {
            cacheTv.text = "首次使用：登录后会自动同步课表、成绩、考试与个人信息"
        }
    }

    private fun setBusy(on: Boolean, msg: String = "") {
        busy = on
        loginBtn.isEnabled = !on
        loginBtn.text = if (on) "处理中…" else "登录并同步"
        loginBtn.alpha = if (on) 0.6f else 1f
        captchaEt.isEnabled = !on
        accountEt.isEnabled = !on
        passwordEt.isEnabled = !on
        if (msg.isNotEmpty()) statusTv.text = msg
    }

    // ---------------- 验证码 ----------------

    private fun refreshCaptcha() {
        if (busy) return
        captchaImg.setImageBitmap(null)
        io.execute {
            val s = JwxtClient.newSession(channel)
            val r = JwxtClient.fetchCaptcha(s)
            runOnUiThread {
                when (r) {
                    is JwResult.Ok -> {
                        session = s
                        val bmp = BitmapFactory.decodeByteArray(r.value, 0, r.value.size)
                        captchaImg.setImageBitmap(bmp)
                        captchaEt.setText("")
                    }
                    is JwResult.Err -> {
                        statusTv.text = r.message
                    }
                }
            }
        }
    }

    // ---------------- 登录 ----------------

    private fun doLogin() {
        if (busy) return
        val acc = accountEt.text.toString().trim()
        val pwd = passwordEt.text.toString()
        val code = captchaEt.text.toString().trim()

        if (acc.isBlank()) { toast("请输入学号"); accountEt.requestFocus(); return }
        if (pwd.isBlank()) { toast("请输入密码"); passwordEt.requestFocus(); return }
        if (code.isBlank()) { toast("请输入验证码"); captchaEt.requestFocus(); return }
        if (channel == JwChannel.EXTRANET && !JwxtClient.extranetAllowedNow()) {
            toast(JwxtClient.extranetBlockedHint())
            return
        }

        val s = session ?: JwxtClient.newSession(channel).also { session = it }
        setBusy(true, "正在登录教务系统…")

        io.execute {
            val loginRes = JwxtClient.login(s, acc, pwd, code)
            when (loginRes) {
                is JwResult.Err -> runOnUiThread {
                    setBusy(false)
                    statusTv.text = loginRes.message
                    toast(loginRes.message)
                    refreshCaptcha()
                }

                is JwResult.Ok -> {
                    runOnUiThread { statusTv.text = "登录成功，正在同步学校数据…" }
                    // 保存凭据（用户勾选才记住）
                    if (autoCb.isChecked) {
                        JwStore.saveCredentials(this, acc, pwd, channel.name)
                    }
                    JwStore.setAutoLogin(this, autoCb.isChecked)

                    val data = JwxtClient.fetchAll(s)
                    JwStore.saveCache(this, JwxtClient.toJson(data))

                    // 课表导入（先清掉教务来源的旧课程，避免重复）
                    var imported = 0
                    if (data.courses.isNotEmpty()) {
                        runCatching {
                            CourseStore.courses(this)
                                .filter { it.source == "jwxt" }
                                .forEach { CourseStore.deleteCourse(this, it.id) }
                            data.courses.forEach { c ->
                                CourseStore.addCourse(
                                    this,
                                    name = c.name,
                                    teacher = c.teacher,
                                    location = c.location,
                                    weekday = c.weekday.coerceIn(1, 7),
                                    startPeriod = c.startPeriod.coerceIn(1, 12),
                                    endPeriod = c.endPeriod.coerceIn(1, 12),
                                    weeks = c.weeks.ifBlank { "1-16" },
                                    source = "jwxt"
                                )
                                imported++
                            }
                        }
                    }

                    runOnUiThread {
                        setBusy(false)
                        val sum = "同步完成：课程 ${data.courses.size} · 成绩 ${data.grades.size}" +
                            " · 考试 ${data.exams.size}" +
                            if (data.profile.isNotEmpty()) " · 个人信息已保存" else ""
                        statusTv.text = if (imported > 0) "$sum（已导入课表 $imported 门）" else sum
                        toast("同步完成")
                        startActivity(
                            Intent(this, MainShellActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            }
                        )
                        finish()
                    }
                }
            }
        }
    }
}
