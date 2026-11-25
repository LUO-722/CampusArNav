package com.campus.arnav

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.campus.arnav.data.CourseStore
import com.campus.arnav.data.SettingsStore
import com.campus.arnav.ui.ChatTab
import com.campus.arnav.ui.NavTab
import com.campus.arnav.ui.TimetableTab
import com.campus.arnav.ui.ZhijiaTab
import com.campus.arnav.ui.applySystemBarInsets
import com.campus.arnav.ui.color
import com.campus.arnav.ui.dp
import com.campus.arnav.ui.rounded
import com.campus.arnav.ui.textView

/**
 * 主壳：底部四栏 —— 课程表 / 智嘉 / 校园导航 / 对话。
 */
class MainShellActivity : AppCompatActivity() {

    private lateinit var content: FrameLayout
    private lateinit var navBar: LinearLayout
    private val navButtons = ArrayList<TextView>()

    private lateinit var timetable: TimetableTab
    private lateinit var zhijia: ZhijiaTab
    private lateinit var nav: NavTab
    private lateinit var chat: ChatTab

    private var current = 0

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            SettingsStore.applySemester(this)
            timetable.refresh()
            chat.refreshProvider()
        }

    private val imageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            zhijia.onImagePicked(result.data?.data)
        }

    private val chatImageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            chat.onImagePicked(result.data?.data)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsStore.applySemester(this)
        CourseStore.seedDemoIfEmpty(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.bg_dark))
        }
        setContentView(root)
        root.applySystemBarInsets { top, bottom, _, _ ->
            root.setPadding(0, top, 0, bottom)
        }

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(color(R.color.bg_card))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        root.addView(navBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)))

        timetable = TimetableTab(this)
        zhijia = ZhijiaTab(this, onSaved = { timetable.refresh() }, onPickImage = {
            val i = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
            imageLauncher.launch(Intent.createChooser(i, "选择通知截图"))
        })
        nav = NavTab(this)
        chat = ChatTab(
            this,
            onOpenSettings = { openSettings() },
            onPickImage = {
                val i = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
                chatImageLauncher.launch(Intent.createChooser(i, "选择要识别的图片"))
            }
        )

        val labels = listOf("课程表" to "▦", "智嘉" to "✦", "导航" to "➤", "对话" to "✉", "我的" to "◉")
        labels.forEachIndexed { i, (label, icon) ->
            val tv = TextView(this).apply {
                text = "$icon\n$label"
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(color(R.color.text_secondary))
                setPadding(0, dp(4), 0, dp(4))
                setOnClickListener { switchTab(i) }
            }
            navButtons.add(tv)
            navBar.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }

        switchTab(0)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (current != 0) switchTab(0) else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun switchTab(i: Int) {
        current = i
        content.removeAllViews()
        val view: View = when (i) {
            0 -> { timetable.refresh(); timetable }
            1 -> zhijia
            2 -> { nav.refresh(); nav }
            3 -> { chat.refreshProvider(); chat }
            else -> buildAccountPage()
        }
        val shown: View = if (i == 0 || i == 1) {
            // 页面在切换时会被临时包一层 ScrollView；再次进入前先从旧父容器摘除，避免 child already has a parent
            (view.parent as? ViewGroup)?.removeView(view)
            ScrollView(this).apply {
                isFillViewport = false
                addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        } else view
        content.addView(shown, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        for ((idx, tv) in navButtons.withIndex()) {
            val on = idx == i
            tv.setTextColor(if (on) color(R.color.accent) else color(R.color.text_secondary))
            tv.background = if (on) rounded(this, Color.parseColor("#1F2933"), 12) else null
        }
    }

    private fun openSettings() {
        settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
    }

    /** 「我的」页：教务账号状态、重新同步、切换账号。 */
    private fun buildAccountPage(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(16))
        }
        col.addView(textView(this, "我的", 22f, color(R.color.text_primary), bold = true))
        col.addView(textView(this, "教务账号与同步数据", 13f, color(R.color.text_secondary)).apply {
            setPadding(0, dp(4), 0, dp(16))
        })

        val accCard = com.campus.arnav.ui.card(this)
        val account = com.campus.arnav.data.JwStore.account(this)
        val signed = account.isNotBlank()
        accCard.addView(textView(this, if (signed) "已登录" else "未登录", 13f, color(R.color.text_secondary)))
        accCard.addView(textView(this, if (signed) account else "—", 20f, color(R.color.text_primary), bold = true).apply {
            setPadding(0, dp(6), 0, 0)
        })
        val chan = com.campus.arnav.net.JwChannel.of(com.campus.arnav.data.JwStore.channel(this))
        accCard.addView(textView(this, "通道：${chan.label}", 12f, color(R.color.text_secondary)).apply {
            setPadding(0, dp(8), 0, 0)
        })
        accCard.addView(textView(this, "同步数据：${com.campus.arnav.data.JwStore.cacheSummary(this)}", 12f, color(R.color.text_secondary)).apply {
            setPadding(0, dp(4), 0, 0)
        })
        col.addView(accCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14) })

        val resync = com.campus.arnav.ui.button(this, "重新同步教务数据", color(R.color.accent), color(R.color.bg_dark))
        resync.setOnClickListener {
            settingsLauncher.launch(Intent(this, LoginActivity::class.java))
        }
        col.addView(resync, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) })

        val switchAcc = com.campus.arnav.ui.button(this, "切换账号 / 退出登录")
        switchAcc.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("切换账号")
                .setMessage("将清除本机保存的学号密码与同步数据，下次打开需重新登录。课程表中教务导入的课程也会一并清除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确定") { _, _ ->
                    com.campus.arnav.data.JwStore.clear(this)
                    runCatching {
                        CourseStore.courses(this).filter { it.source == "jwxt" }
                            .forEach { CourseStore.deleteCourse(this, it.id) }
                    }
                    startActivity(Intent(this, LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                    finish()
                }
                .show()
        }
        col.addView(switchAcc, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val setBtn = com.campus.arnav.ui.button(this, "模型与学期设置")
        setBtn.setOnClickListener { openSettings() }
        col.addView(setBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        return ScrollView(this).apply {
            isFillViewport = false
            addView(col)
        }
    }
}
