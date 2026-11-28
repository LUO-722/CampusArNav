package com.campus.arnav

import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import com.campus.arnav.core.Reminder
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.campus.arnav.data.AuthStore
import com.campus.arnav.data.CourseStore
import com.campus.arnav.data.SettingsStore
import com.campus.arnav.ui.insLightBars
import com.campus.arnav.ui.insBackground
import com.campus.arnav.ui.primaryButton
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
    private val navItems = ArrayList<NavItem>()

    /** 底栏一项：浅粉胶囊底 + 22dp 矢量图标 + 10.5sp 小字；选中态只换颜色与底色，不重建视图。 */
    private class NavItem(val pill: View, val icon: ImageView, val label: TextView)

    /** 底栏配色：选中 accent #E8447A，未选中 #A99CB0，选中胶囊底 #FFEAF2，顶边描边 #F2E4EC。 */
    private val navAccent = Color.parseColor("#E8447A")
    private val navIdle = Color.parseColor("#A99CB0")
    private val navPill = Color.parseColor("#FFEAF2")
    private val navStroke = Color.parseColor("#F2E4EC")

    private lateinit var timetable: TimetableTab
    private lateinit var zhijia: ZhijiaTab
    private lateinit var nav: NavTab
    private lateinit var chat: ChatTab
    private lateinit var profile: ProfileTab

    private var current = 0

    /** 页面构造失败时记下来，切页时显示成可读的错误页，而不是闪退。 */
    private var tabBuildError: Throwable? = null

    /** 登录态快照 "loggedIn|lastSyncAt"：从登录页回来时只在这两者变了才刷新，避免打断滚动。 */
    private var loginStamp: String? = null

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
        insLightBars()
        // 数据库/偏好设置读取也可能因为老库结构异常抛错，同样不能让它把 App 带崩
        runCatching { SettingsStore.applySemester(this); CourseStore.seedDemoIfEmpty(this) }
            .onFailure { Log.e("CampusArNav", "prepare data failed", it) }
        loginStamp = loginStampNow()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = insBackground(this@MainShellActivity)
        }
        setContentView(root)
        root.applySystemBarInsets { top, bottom, _, _ ->
            root.setPadding(0, top, 0, bottom)
        }

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // Ins 风底栏：白底、上沿 20dp 圆角、1dp 淡粉描边、轻投影；高度 62dp（底部导航栏 inset 由外层 root padding 承担）
            val r = dp(20).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setColor(color(R.color.bg_card))
                setStroke(dp(1), navStroke)
            }
            elevation = dp(8).toFloat()
            setPadding(dp(4), dp(4), dp(4), dp(2))
        }
        root.addView(navBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)))

        // 页面构造统一兜底：任一页面初始化失败（例如老数据库结构不对）不能让整个 App 闪退，
        // 改为显示一句提示和「重试」，异常只进日志。
        var buildError: Throwable? = null
        runCatching {
        timetable = TimetableTab(this)
        zhijia = ZhijiaTab(this, onSaved = { timetable.refresh() }, onPickImage = {
            val i = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
            imageLauncher.launch(Intent.createChooser(i, "选择通知截图"))
        }, onOpenTimetable = { switchTab(0) })
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
        }.onFailure { buildError = it; Log.e("CampusArNav", "buildTabs failed", it) }
        tabBuildError = buildError

        // 五个 tab：22dp 矢量图标 + 10.5sp 文字竖排；选中时图标/文字都用 accent，并在图标后面垫浅粉胶囊。
        val tabs = listOf(
            "课程表" to R.drawable.ic_tab_timetable,
            "智嘉" to R.drawable.ic_tab_zhijia,
            "导航" to R.drawable.ic_tab_nav,
            "对话" to R.drawable.ic_tab_chat,
            "我的" to R.drawable.ic_tab_profile
        )
        tabs.forEachIndexed { i, (label, iconRes) ->
            val pill = FrameLayout(this).apply {
                // 胶囊：高 26dp、内容左右各 10dp，圆角由 switchTab 里 rounded(..., 999) 给
                setPadding(dp(10), 0, dp(10), 0)
            }
            val icon = ImageView(this).apply {
                setImageResource(iconRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                // 图标 XML 用 #000000 绘制，这里用 ColorFilter 上色（app:tint 在纯 ImageView 上不生效）
                setColorFilter(navIdle, PorterDuff.Mode.SRC_IN)
            }
            pill.addView(icon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            val labelView = TextView(this).apply {
                text = label
                contentDescription = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTextColor(navIdle)
                includeFontPadding = false
            }
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setOnClickListener { switchTab(i) }
                addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26)))
                addView(labelView, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(3) })
            }
            navItems.add(NavItem(pill, icon, labelView))
            navBar.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
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

    override fun onResume() {
        super.onResume()
        if (!this::content.isInitialized) return
        askNotifyPermission()
        Reminder.scheduleAsync(this)
        // 从登录页（或设置页）回来：登录态 / 最近同步时间变了才刷新当前栏，
        // 登录成功后课表已被教务数据替换，需要重新读；没变化就不重建视图，保住滚动位置。
        val stamp = loginStampNow()
        val before = loginStamp
        loginStamp = stamp
        if (before == null || before == stamp) return
        when (current) {
            0 -> timetable.refresh()
            2 -> nav.refresh()
            3 -> chat.refreshProvider()
            4 -> profile.refresh()
        }
    }

    /** 定位权限对话框返回：转给导航栏，允许就继续定位，拒绝也给出路。 */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 81) return
        val ok = grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (this::nav.isInitialized) nav.onLocatePermission(ok)
    }

    /** Android 13+ 第一次进应用要主动要通知权限，否则提醒发不出来。 */
    private fun askNotifyPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            try {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 71)
            } catch (_: Exception) {
            }
        }
    }

    private fun loginStampNow(): String {
        val s = AuthStore.session(this)
        return s.loggedIn.toString() + "|" + s.lastSyncAt
    }

    private fun switchTab(i: Int) {
        current = i
        content.removeAllViews()
        val view: View = try {
            when (i) {
                0 -> { timetable.refresh(); timetable }
                1 -> zhijia
                2 -> { nav.refresh(); nav }
                3 -> { chat.refreshProvider(); chat }
                else -> { profile.refresh(); profile }
            }
        } catch (e: Throwable) {
            Log.e("CampusArNav", "switchTab " + i + " failed", e)
            errorView(tabBuildError ?: e)
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
        for ((idx, item) in navItems.withIndex()) {
            val on = idx == i
            val tint = if (on) navAccent else navIdle
            item.icon.setColorFilter(tint, PorterDuff.Mode.SRC_IN)
            item.label.setTextColor(tint)
            // 选中：图标后面垫浅粉胶囊（高 26dp、圆角 999）；未选中：去掉底色
            item.pill.background = if (on) rounded(this, navPill, 999) else null
        }
    }

    /** 出错时替代页面内容：一句人话 + 重试按钮；异常只进日志，不上屏。 */
    private fun errorView(e: Throwable): View {
        Log.e("CampusArNav", "tab error", e)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(24))
        }
        box.addView(textView(this@MainShellActivity, "这一页没能打开", 18f, color(R.color.text_primary), bold = true))
        box.addView(textView(this@MainShellActivity, "返回上一页，或者重试一次。", 13f, color(R.color.text_secondary)).apply {
            setPadding(0, dp(8), 0, 0)
        })
        box.addView(primaryButton(this@MainShellActivity, "重试").apply {
            setOnClickListener { switchTab(current) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { topMargin = dp(16) })
        return ScrollView(this).apply { addView(box) }
    }

    private fun openSettings() {
        settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
    }
}