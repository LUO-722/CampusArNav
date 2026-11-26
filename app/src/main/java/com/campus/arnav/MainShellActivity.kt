package com.campus.arnav

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
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
import com.campus.arnav.ui.ProfileTab
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
    private lateinit var profile: ProfileTab

    private var current = 0

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            SettingsStore.applySemester(this)
            timetable.refresh()
            chat.refreshProvider()
            profile.refresh()
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
        Log.i("MainShell", "onCreate saved=" + (savedInstanceState != null) + " tabArg=" + (savedInstanceState?.getInt("tab", -1) ?: -1))
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
        profile = ProfileTab(
            this,
            onOpenSettings = { openSettings() },
            onOpenTimetable = { switchTab(0) }
        )

        val labels = listOf(
            "课程表" to "▦",
            "智嘉" to "✦",
            "导航" to "➤",
            "对话" to "✉",
            "我的" to "☺"
        )
        labels.forEachIndexed { i, (label, icon) ->
            val tv = TextView(this).apply {
                text = "$icon\n$label"
                contentDescription = label
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(color(R.color.text_secondary))
                setPadding(0, dp(4), 0, dp(4))
                setOnClickListener { switchTab(i) }
            }
            navButtons.add(tv)
            navBar.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }

        switchTab(savedInstanceState?.getInt("tab", 0)?.coerceIn(0, 4) ?: 0)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (current != 0) switchTab(0) else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", current)
    }

    private fun switchTab(i: Int) {
        Log.i("MainShell", "switchTab " + i + " (was " + current + ")")
        current = i
        content.removeAllViews()
        val view: View = when (i) {
            0 -> { timetable.refresh(); timetable }
            1 -> zhijia
            2 -> { nav.refresh(); nav }
            3 -> { chat.refreshProvider(); chat }
            else -> { profile.refresh(); profile }
        }
        val shown: View = if (i == 0 || i == 1 || i == 4) {
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
            tv.background = if (on) rounded(this, Color.parseColor("#2A1E15"), 12) else null
        }
    }

    private fun openSettings() {
        settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
    }
}
