package com.campus.arnav.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.campus.arnav.R
import com.campus.arnav.agent.ActionExecutor
import com.campus.arnav.agent.AgentEngine
import com.campus.arnav.data.ChatMsg
import com.campus.arnav.data.ChatSession
import com.campus.arnav.data.ChatStore
import com.campus.arnav.data.SettingsStore
import com.campus.arnav.kb.KbIndex
import com.campus.arnav.ocr.LocalOcr
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ④ 聊天：知识库问答 + Agent 决策（录活动/加课/闹钟/导航建议）；图片先本机 OCR 识文字再提问。
 *
 * 云端通道内置（无需任何配置），有网走 [AgentEngine]；没网或云端失败也不报错，
 * 直接回落本机 BM25 知识库回答，功能不会「死」。
 *
 * 会话模型：构造时 `ChatStore.newSession(ctx)` 开一个新会话，当前会话为空就只显示欢迎语（不落库）；
 * 顶部「记录」按钮回看历史会话（只读，可删除非当前会话），「返回」回到当前对话。
 */
class ChatTab(
    private val ctx: Context,
    private val onOpenSettings: () -> Unit,
    private val onPickImage: () -> Unit = {}
) : LinearLayout(ctx) {

    private val scroll = ScrollView(ctx)
    private val msgBox = LinearLayout(ctx)
    private val input = editText(ctx, "问校园制度、办事、课程…", android.text.InputType.TYPE_CLASS_TEXT)
    private val modelLabel = textView(ctx, "", 11.5f, ctx.color(R.color.text_hint))
    private val history = ArrayList<ChatMsg>()
    private var imeExtra = 0
    private var sending = false
    private var streamBubble: TextView? = null

    /** 上一条消息是谁发的：用来决定气泡间距（同侧 4dp、换边 10dp）。 */
    private var lastAssistant: Boolean? = null

    // Ins 浅色色板（全部取自 colors.xml，避免散落硬编码）
    private val cBorder = ctx.color(R.color.border)
    private val cCard = ctx.color(R.color.bg_card)
    private val cAccent = ctx.color(R.color.accent)
    private val cAccentInk = ctx.color(R.color.accent_ink)
    private val cAccentSoft = ctx.color(R.color.accent_soft)
    private val cTextPrimary = ctx.color(R.color.text_primary)
    private val cTextSecondary = ctx.color(R.color.text_secondary)
    private val cTextHint = ctx.color(R.color.text_hint)

    @Volatile
    private var cancelFlag = false
    private var lastTouchAt = 0L
    private val greeting = "你好，我是智嘉。可以问我校园制度、办事流程、楼栋位置，也能帮你录活动、加课、设闹钟、找自习的地方。"

    /** 当前会话 sid（构造时新开）。 */
    private lateinit var sessionId: String
    private lateinit var recordBtn: TextView
    private lateinit var clearBtn: TextView
    private lateinit var sendBtn: TextView
    private lateinit var inputRow: LinearLayout

    /** true = 正在看历史会话（只读），底部输入行隐藏。 */
    private var viewingHistory = false
    private var viewingSid: String? = null

    /** 当前打开的「聊天记录」对话框，删除后刷新时先关掉它。 */
    private var listDialog: AlertDialog? = null

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    init {
        orientation = VERTICAL
        // 不铺纯色底，让 MainShell 的 #FFF1F7→#EDF7FF 渐变透出来
        setBackgroundColor(Color.TRANSPARENT)
        // 横向不留白：底部输入条要能铺满整宽；左右留白交给标题行/消息区自己加
        setPadding(0, ctx.dp(10), 0, 0)

        // 每次进入新开一个会话；旧会话留在「记录」里
        sessionId = ChatStore.newSession(ctx)

        val head = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
        }
        head.addView(circleBtn("☰", 34) { onOpenSettings() }, LinearLayout.LayoutParams(ctx.dp(34), ctx.dp(34)))
        head.addView(decoTitle("智嘉 · 对话"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ctx.dp(10) })

        // 「记录 / 返回」：34dp 白底圆形按钮，1dp 描边，和旁边「清」同一规格
        recordBtn = circleBtn("记录", 34) {
            if (viewingHistory) backToCurrent() else openHistory()
        }
        head.addView(recordBtn, LinearLayout.LayoutParams(ctx.dp(34), ctx.dp(34)).apply { leftMargin = ctx.dp(8) })

        clearBtn = circleBtn("清", 34) {
            confirm(ctx, "清空对话记录？", "只清空当前这段对话，其它历史记录和课表、知识库都不受影响。") {
                ChatStore.delete(ctx, sessionId)
                sessionId = ChatStore.newSession(ctx)
                history.clear()
                msgBox.removeAllViews()
                lastAssistant = null
                addBubble(greeting, true, welcome = true)
            }
        }
        head.addView(clearBtn, LinearLayout.LayoutParams(ctx.dp(34), ctx.dp(34)).apply { leftMargin = ctx.dp(8) })
        addView(head)

        // 通道状态单独一行小字（11.5sp / #A99CB0），文案仍由 refreshProvider() 填
        modelLabel.setPadding(ctx.dp(14), ctx.dp(6), ctx.dp(14), ctx.dp(2))
        // 模型通道是构建期内置的，没有可选项：点一下只解释「无需配置」，不再弹配置菜单
        modelLabel.setOnClickListener {
            ctx.toast("不用配置。联网时用云端模型，断网时自动用本地知识库。")
        }
        addView(modelLabel)

        msgBox.orientation = VERTICAL
        msgBox.setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8))
        scroll.addView(msgBox)
        scroll.isFillViewport = true
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(10), ctx.dp(10), ctx.dp(10), ctx.dp(10))
        }
        inputRow = row
        // 输入框：白底、22dp 圆角、1dp 描边、最小高 44dp、内边距 14dp
        input.maxLines = 4
        input.textSize = 15f
        input.setTextColor(cTextPrimary)
        input.setHintTextColor(cTextHint)
        input.setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
        input.minHeight = ctx.dp(44)
        input.background = GradientDrawable().apply {
            cornerRadius = ctx.dp(22).toFloat()
            setColor(cCard)
            setStroke(ctx.dp(1), cBorder)
        }
        input.isFocusableInTouchMode = true
        input.isCursorVisible = true
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send()
                true
            } else false
        }
        // 一次点击就要能打字：ACTION_DOWN 就把焦点和键盘拿到手。
        // 键盘弹起后本视图会补底部 padding 重新布局，输入行整体上移，
        // 原来那次 ACTION_UP 会落到移动后的位置上，以前就表现为「点一下只是升起来，要再点一次才能输入」。
        input.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) grabInputFocus(openIme = true)
            false
        }
        input.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) v.post { showIme(v) }
        }
        row.addView(circleBtn("图", 34) { pickImage() }, LinearLayout.LayoutParams(ctx.dp(34), ctx.dp(34)).apply { rightMargin = ctx.dp(8) })
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // 发送：40dp 圆形玫粉按钮 + 白色上箭头；发送中变浅粉底深字（仍可点 = 取消）
        sendBtn = circleBtn("↑", 40, filled = true) { send() }.apply {
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        }
        row.addView(sendBtn, LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)).apply { leftMargin = ctx.dp(8) })
        updateSendState()
        // 整条输入行都可点：点空白处也直接进输入态，不用精确点到输入框内部。
        // 注意 clickable 的 ViewGroup 默认也是 focusable 的，这里显式关掉，
        // 否则它会在键盘重排时跟输入框抢焦点。
        row.isClickable = true
        row.isFocusable = false
        row.isFocusableInTouchMode = false
        row.setOnClickListener { grabInputFocus(openIme = true) }

        // 底部输入条：白底铺满整宽，顶部 1dp 分隔线，内部 10dp 内边距
        val inputBar = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setBackgroundColor(cCard)
        }
        inputBar.addView(View(ctx).apply { setBackgroundColor(cBorder) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(1)))
        inputBar.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(inputBar)

        val saved = ChatStore.messages(ctx, sessionId).takeLast(30)
        history.addAll(saved)
        if (saved.isEmpty()) {
            addBubble(greeting, true, welcome = true)
        } else {
            saved.forEach { addBubble(it.content, it.role != "user") }
        }
        refreshProvider()
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        bindImeInsets()
    }

    // ---------------------------------------------------------------- 会话 / 记录

    /** 打开「聊天记录」：自绘列表（ScrollView），当前会话标注「当前」，非当前可删。 */
    private fun openHistory() {
        val sessions = ChatStore.sessions(ctx)
        if (sessions.isEmpty()) {
            AlertDialog.Builder(ctx, R.style.Theme_CampusAr_Dialog)
                .setTitle("聊天记录")
                .setMessage("还没有历史记录，聊几句就会出现在这里。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        val box = LinearLayout(ctx).apply { orientation = VERTICAL }
        val sv = ScrollView(ctx).apply {
            isFillViewport = false
            addView(box)
        }
        // 每行固定 56dp + 1dp 分隔线
        val listH = (sessions.size.coerceAtMost(6) * 57 + 8)
        val container = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setPadding(ctx.dp(14), ctx.dp(2), ctx.dp(14), 0)
            addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(listH)))
        }
        val dialog = AlertDialog.Builder(ctx, R.style.Theme_CampusAr_Dialog)
            .setTitle("聊天记录")
            .setView(container)
            .setNegativeButton("关闭", null)
            .create()
        listDialog = dialog

        // 行点击需要 dialog 引用（先建 dialog，再往已经挂在容器里的 box 填行）
        sessions.forEachIndexed { index, s ->
            box.addView(historyRow(s) {
                dialog.dismiss()
                showHistorySession(s)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ctx.dp(56)
            ))
            if (index != sessions.lastIndex) box.addView(rowDivider())
        }
        dialog.show()
    }

    /** 列表行分隔线：1dp、#F2E4EC、铺满行宽（比通用 divider 少了左右 4dp 缩进）。 */
    private fun rowDivider(): View = View(ctx).apply {
        setBackgroundColor(cBorder)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(1))
    }

    private fun historyRow(s: ChatSession, onOpen: () -> Unit): View {
        val count = try {
            ChatStore.messages(ctx, s.id).size
        } catch (e: Exception) {
            0
        }
        val row = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ctx.dp(8), 0, ctx.dp(8))
            isClickable = true
            setOnClickListener { onOpen() }
        }
        // 当前会话：行首 3dp 玫粉竖条；其它行留同等缩进，保证文字左对齐
        if (s.id == sessionId) {
            row.addView(View(ctx).apply { setBackgroundColor(cAccent) },
                LinearLayout.LayoutParams(ctx.dp(3), ctx.dp(24)).apply { rightMargin = ctx.dp(9) })
        } else {
            row.addView(View(ctx), LinearLayout.LayoutParams(ctx.dp(12), 1))
        }
        val col = LinearLayout(ctx).apply { orientation = VERTICAL }
        col.addView(textView(ctx, if (s.title.isBlank()) "新对话" else s.title, 15f, cTextPrimary).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        col.addView(textView(ctx, "${fmt(s.updatedAt)} · ${count} 条", 12f, cTextSecondary).apply {
            setPadding(0, ctx.dp(2), 0, 0)
        })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (s.id == sessionId) {
            row.addView(textView(ctx, "当前", 10f, cAccentInk).apply {
                background = rounded(ctx, cAccentSoft, 8)
                setPadding(ctx.dp(6), ctx.dp(2), ctx.dp(6), ctx.dp(2))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = ctx.dp(6)
            })
        }
        val del = textView(ctx, "删除", 11f, cAccent, bold = true).apply {
            setPadding(ctx.dp(6), ctx.dp(4), 0, ctx.dp(4))
            if (s.id == sessionId) {
                // 当前会话不可删：置灰且不响应
                setTextColor(cTextSecondary)
                alpha = 0.45f
            } else {
                setOnClickListener {
                    confirm(ctx, "删除这条记录？", "删除后不可恢复。") {
                        ChatStore.delete(ctx, s.id)
                        if (viewingSid == s.id) backToCurrent()
                        // 先关掉旧列表再重新打开，避免两个对话框叠在一起
                        listDialog?.dismiss()
                        openHistory()
                    }
                }
            }
        }
        row.addView(del, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = ctx.dp(4)
        })
        return row
    }

    /** 只读打开某条历史会话：顶部提示条 + 消息，隐藏底部输入行，按钮变「返回」。 */
    private fun showHistorySession(s: ChatSession) {
        viewingHistory = true
        viewingSid = s.id
        recordBtn.text = "返回"
        clearBtn.visibility = View.GONE
        inputRow.visibility = View.GONE
        msgBox.removeAllViews()

        val tip = textView(ctx, "历史记录 · 只读 · 点上方「返回」回到当前对话", 12f, ctx.color(R.color.accent_ink)).apply {
            background = rounded(ctx, ctx.color(R.color.accent_soft), 12)
            gravity = Gravity.CENTER
            setPadding(ctx.dp(10), ctx.dp(8), ctx.dp(10), ctx.dp(8))
        }
        val tipWrap = LinearLayout(ctx).apply {
            setPadding(0, 0, 0, ctx.dp(6))
            addView(tip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        msgBox.addView(tipWrap)

        val msgs = try {
            ChatStore.messages(ctx, s.id)
        } catch (e: Exception) {
            emptyList()
        }
        lastAssistant = null
        if (msgs.isEmpty()) {
            addBubble("（这条记录里没有消息）", true)
        } else {
            msgs.forEach { addBubble(it.content, it.role != "user") }
        }
        scroll.post { scroll.scrollTo(0, 0) }
    }

    /** 回到当前会话（退出只读）。 */
    private fun backToCurrent() {
        viewingHistory = false
        viewingSid = null
        recordBtn.text = "记录"
        clearBtn.visibility = View.VISIBLE
        inputRow.visibility = View.VISIBLE
        msgBox.removeAllViews()
        lastAssistant = null
        if (history.isEmpty()) {
            addBubble(greeting, true, welcome = true)
        } else {
            history.forEach { addBubble(it.content, it.role != "user") }
        }
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun fmt(ts: Long): String = timeFmt.format(Date(ts))

    // ---------------------------------------------------------------- 输入 / 键盘

    /** 明确地弹一次键盘：不依赖系统「点一下就自动弹」的时序，避免第一次点击被重排吃掉。 */
    private fun showIme(target: android.view.View) {
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * 把焦点 / 光标 / 键盘一次性交回输入框。
     *
     * 为什么不只靠 EditText 自己的点击：键盘弹起的同一帧里，本页会补底部 padding 重新布局，
     * 焦点在重排过程中会丢，而 msgBox 里的气泡只要还是 focusableInTouchMode
     * （`setTextIsSelectable(true)` 就会变成这样），系统就会把焦点塞给其中一个气泡：
     * IME 的 servedView 变成 TextView，键盘看起来弹起来了却一个字符都进不了输入框，
     * 必须再点一次。所以这里在每次点击时主动把焦点按回输入框。
     */
    private fun grabInputFocus(openIme: Boolean) {
        if (viewingHistory || inputRow.visibility != View.VISIBLE) return
        val had = input.hasFocus()
        if (!had) {
            input.requestFocus()
            if (!input.hasFocus()) input.requestFocusFromTouch()
        }
        if (!had) input.setSelection(input.text?.length ?: 0)
        input.isCursorVisible = true
        if (openIme) showIme(input)
    }

    /** 长按气泡复制整条消息（以前靠 setTextIsSelectable 拖选，副作用是把气泡变成可聚焦视图）。 */
    private fun copyBubble(v: TextView) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("消息", v.text?.toString().orEmpty()))
        ctx.toast("已复制这条消息")
    }

    /**
     * 点输入框弹键盘时，让整个对话区（连同输入行）跟着键盘升上来。
     *
     * targetSdk 35+ 系统强制 edge-to-edge：manifest 里的 adjustResize 不再压缩窗口，
     * 键盘只把 IME insets 报给视图。不自己让位，输入行就被压在键盘底下。
     * 这里量出「本视图底边到窗口底边」还剩多少（主壳 62dp 底栏 + 系统导航栏），
     * 只补足键盘实际超出的那部分，让输入行贴着键盘上沿。
     */
    /** 记一下最后一次「手指按下」的时刻，布局改动据此延时，避免吃掉这次点击。 */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) lastTouchAt = android.os.SystemClock.uptimeMillis()
        return super.dispatchTouchEvent(ev)
    }

    private fun bindImeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val sysBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            if (v.height <= 0 || v.rootView.height <= 0) {
                // 还没量好尺寸，等布局完再问一次；键盘已经收起就先复位，别把上次的垫高留着
                if (ime > 0) {
                    v.post { v.requestApplyInsets() }
                } else if (imeExtra != 0) {
                    imeExtra = 0
                    v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, 0)
                }
                return@setOnApplyWindowInsetsListener insets
            }
            // 窗口被系统压缩过（老系统 adjustResize 真的生效）时 rootView 会明显矮于屏幕，
            // 此时内容已被整体抬高，再补 padding 会重复位移，所以只在铺满屏幕时自己补。
            val fullScreen = v.rootView.height >= v.resources.displayMetrics.heightPixels - ctx.dp(80)
            val loc = IntArray(2)
            v.getLocationInWindow(loc)
            val below = (v.rootView.height - loc[1] - v.height).coerceAtLeast(sysBottom)
            val extra = if (fullScreen) (ime - below).coerceAtLeast(0) else 0
            if (extra != imeExtra) {
                imeExtra = extra
                val hadFocus = input.hasFocus()
                val caret = if (hadFocus) input.selectionStart.coerceAtLeast(0) else -1
                val apply = Runnable {
                    v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, imeExtra)
                    // 关键顺序：先让 padding 落地（这一帧的布局会被改），再在下一帧把焦点抢回来。
                    // 重排期间系统可能已经把焦点交给了别的可聚焦视图（气泡），
                    // 那样键盘虽然弹出来，字符却进不了输入框；这里必须最后一步再补焦点 + 补键盘。
                    input.post {
                        if (viewingHistory || inputRow.visibility != View.VISIBLE) return@post
                        if (imeExtra > 0) {
                            if (!input.hasFocus()) {
                                input.requestFocus()
                                if (!input.hasFocus()) input.requestFocusFromTouch()
                            }
                            val len = input.text?.length ?: 0
                            val at = if (caret >= 0) caret.coerceIn(0, len) else len
                            input.setSelection(at)
                            input.isCursorVisible = true
                            // requestFocus 只保证「焦点在我这」，键盘可能还连着上一个视图：再显式要一次
                            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                            if (imm != null && !imm.isActive(input)) {
                                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                            }
                        }
                        scroll.fullScroll(ScrollView.FOCUS_DOWN)
                    }
                }
                // 手指还按在屏幕上时先别挪：挪早了会把 ACTION_UP 甩到别处，输入框收不到光标
                if (SystemClock.uptimeMillis() - lastTouchAt < 400L) {
                    v.postDelayed(apply, 220L)
                } else {
                    apply.run()
                }
            }
            insets
        }
    }

    fun refreshProvider() {
        val p = SettingsStore.chatProvider(ctx)
        modelLabel.text = if (p.ready()) {
            "当前：云端模型，无需配置；断网时自动用本地知识库。"
        } else {
            "离线模式：问答只用手机里的知识库。"
        }
    }

    private fun pickImage() {
        if (sending) {
            ctx.toast("正在回复，稍等再发图")
            return
        }
        if (viewingHistory) return
        // 图片走手机本地文字识别：离线、不上传、不依赖云端配置
        onPickImage()
    }

    /** MainShell 的图片选择器回调：先本机识文字，填进输入框，用户确认后再发送。 */
    fun onImagePicked(uri: Uri?) {
        if (uri == null || sending || viewingHistory) return
        ctx.toast("正在本地识别图片文字（不会上传）…")
        Thread {
            try {
                val text = LocalOcr.recognizeLines(ctx, uri)
                post {
                    if (text.isBlank()) {
                        ctx.toast("图片里没认出文字，试试换一张清晰点的截图")
                    } else {
                        input.setText(text)
                        input.setSelection(text.length)
                        ctx.toast("本地已识别 ${text.length} 字（图片没有上传），确认后点「发送」")
                    }
                }
            } catch (e: Exception) {
                post { ctx.toast("本地识别失败，换一张图再试。") }
            }
        }.start()
    }

    private fun send() {
        if (sending) {
            // 「卡住」时给一条活路：再点一次发送 = 取消这次回复，输入框里的字保留
            cancelFlag = true
            setSending(false)
            val b = streamBubble
            if (b != null && b.text.isNullOrBlank()) b.text = "（已取消）"
            streamBubble = null
            ctx.toast("已取消这次回复")
            return
        }
        val text = input.text.toString().trim()
        if (text.isEmpty() || viewingHistory) return
        input.setText("")
        val first = history.isEmpty()
        addBubble(text, false)
        history.add(ChatMsg("user", text))
        ChatStore.add(ctx, sessionId, ChatMsg("user", text))
        if (first) ChatStore.setTitle(ctx, sessionId, text)
        setSending(true)
        cancelFlag = false
        val context = ctx
        streamBubble = addBubble("…", true)
        var lastRefresh = 0L
        // 卡死兜底：100 秒还没回来就自动放开按钮，用户可以重发或取消
        postDelayed({
            if (sending) {
                cancelFlag = true
                setSending(false)
                ctx.toast("这次回复超时了，可以再点一次「发送」重试")
            }
        }, 100_000L)
        Thread {
            try {
                if (SettingsStore.chatOnline(context)) {
                    val r = AgentEngine.ask(context, history, text) { visible ->
                        val now = System.currentTimeMillis()
                        if (now - lastRefresh >= 40L) {
                            lastRefresh = now
                            post { if (!cancelFlag && (visible.isEmpty() || com.campus.arnav.agent.AnswerRules.looksLikeAnswer(visible))) showStreaming(visible) }
                        }
                    }
                    if (cancelFlag) return@Thread
                    post { complete(r) }
                } else {
                    // 没网 / 云端不可用：不报错，直接用本机知识库答（BM25，不联网）
                    val reply = answerOffline(context, text)
                    post { completeOffline(reply) }
                }
            } catch (e: Exception) {
                if (cancelFlag) return@Thread
                // 断网 / 模型全部限流 / 云端报错：不让对话断在这里，直接用离线知识库答
                val reply = answerOffline(context, text)
                post { completeOffline(reply + "\n\n（云端暂时不可用，这里用本地知识库回答）") }
            }
        }.start()
    }

    /**
     * 离线兜底：本机 BM25 知识库检索后直接给条文（不联网、不依赖云端配置）。
     * 注意离线模式只答知识性问题，写入类操作（加课/录活动）需要云端模型。
     */
    private fun answerOffline(context: Context, query: String): String {
        val hits = try {
            KbIndex.search(context, query, 3)
        } catch (e: Exception) {
            emptyList()
        }
        if (hits.isEmpty()) {
            return "本地知识库里没找到和「$query」相关的内容。换个说法再试，联网后我再查一次。\n\n（离线回答）"
        }
        val sb = StringBuilder("根据学校知识库：")
        hits.forEachIndexed { i, h ->
            sb.append("\n${i + 1}. ").append(h.title)
            sb.append("\n").append(h.text.trim().take(260).replace("\n", " "))
        }
        sb.append("\n\n（离线回答）")
        return sb.toString()
    }

    private fun completeOffline(reply: String) {
        markStreaming(reply)
        history.add(ChatMsg("assistant", reply))
        ChatStore.add(ctx, sessionId, ChatMsg("assistant", reply))
        while (history.size > 30) history.removeAt(0)
        setSending(false)
    }

    private fun complete(r: AgentEngine.Result) {
        markStreaming(r.reply)
        if (r.actions.isNotEmpty()) {
            addLog(r.actions.joinToString("\n") { "· $it" })
        }
        if (r.sources.isNotEmpty()) {
            addLog("依据：" + r.sources.joinToString("、"))
        }
        history.add(ChatMsg("assistant", r.reply))
        ChatStore.add(ctx, sessionId, ChatMsg("assistant", r.reply))
        while (history.size > 30) history.removeAt(0)
        setSending(false)
        if (r.pending.isEmpty()) return
        // 写操作（加课/录活动/闹钟/删除）先让用户确认，未确认不落库
        val lines = r.pending.joinToString("\n") { "· " + AgentEngine.describe(it) }
        confirm(
            ctx, "允许智嘉执行这些操作？", lines, "允许",
            onOk = {
                val outcomes = r.pending.map { action ->
                    val type = action.optString("type")
                    try {
                        ActionExecutor.executeOutcome(ctx, action)
                    } catch (e: Exception) {
                        ActionExecutor.logFailure(type, e)
                        ActionExecutor.Outcome(false, type, ActionExecutor.humanize(type, e))
                    }
                }
                addLog(outcomes.joinToString("\n") { "· " + it.message })
                // 失败不能只躺在灰色日志里：再补一条明确的气泡，避免「看着像成功」
                val failed = outcomes.filter { !it.ok }
                if (failed.isNotEmpty()) {
                    val sorry = "动作没有全部成功：" + failed.joinToString("；") { it.message }
                    addBubble(sorry, true)
                    ChatStore.add(ctx, sessionId, ChatMsg("assistant", sorry))
                }
            },
            onCancel = { addLog("已取消执行") }
        )
    }

    private fun fail(message: String) {
        addBubble(message, true)
        setSending(false)
    }

    /** 模型回复常带 Markdown 记号，TextView 不渲染，这里转成干净文本。 */
    private fun plain(s: String): String {
        var t = s.replace("**", "").replace("__", "")
        t = t.replace(Regex("`([^`]*)`"), "$1")
        t = t.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        t = t.replace(Regex("(?m)^#{1,6}\\s*"), "")
        t = t.replace(Regex("(?m)^\\s*[-*]\\s+"), "· ")
        return t
    }

    private fun addBubble(text: String, assistant: Boolean, welcome: Boolean = false): TextView {
        val bubble = TextView(ctx).apply {
            this.text = if (assistant) plain(text) else text
            textSize = 15f
            typeface = Typeface.DEFAULT
            setLineSpacing(0f, 1.4f)
            includeFontPadding = false
            maxWidth = resources.displayMetrics.widthPixels - ctx.dp(if (welcome) 116 else 64)
            setTextColor(if (assistant) cTextPrimary else Color.WHITE)
            setPadding(ctx.dp(14), ctx.dp(14), ctx.dp(14), ctx.dp(14))
            background = if (welcome) {
                // 欢迎语：浅粉卡片，无描边
                GradientDrawable().apply {
                    cornerRadius = ctx.dp(18).toFloat()
                    setColor(Color.parseColor("#FFF3F8"))
                }
            } else {
                bubbleBg(assistant)
            }
            // 复制改成「长按」：setTextIsSelectable(true) 会把气泡变成 focusableInTouchMode 的 TextView，
            // 键盘弹起重排时焦点被它抢走，第一次点输入框就打不了字。这里绝不让气泡可聚焦。
            setOnLongClickListener {
                copyBubble(it as TextView)
                true
            }
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = false
        }
        val wrap = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = if (assistant) Gravity.START else Gravity.END
            // 气泡间距：换边 10dp，同侧连续 4dp
            val gap = when (lastAssistant) {
                null -> ctx.dp(2)
                assistant -> ctx.dp(4)
                else -> ctx.dp(10)
            }
            setPadding(0, gap, 0, 0)
        }
        if (welcome) {
            wrap.addView(avatarView(), LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)).apply {
                rightMargin = ctx.dp(8)
            })
        }
        wrap.addView(bubble, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        msgBox.addView(wrap)
        lastAssistant = assistant
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        return bubble
    }

    /** 助手气泡：白底 + 1dp 描边 + 左下角 6dp；用户气泡：玫粉实底 + 右下角 6dp。 */
    private fun bubbleBg(assistant: Boolean): GradientDrawable {
        val big = ctx.dp(18).toFloat()
        val small = ctx.dp(6).toFloat()
        return GradientDrawable().apply {
            cornerRadii = if (assistant) {
                // 顺序：左上、右上、右下、左下（每角两个值）
                floatArrayOf(big, big, big, big, big, big, small, small)
            } else {
                floatArrayOf(big, big, big, big, small, small, big, big)
            }
            setColor(if (assistant) cCard else cAccent)
            if (assistant) setStroke(ctx.dp(1), cBorder)
        }
    }

    /** 34dp / 40dp 圆形按钮：白底描边（ghost）或玫粉实底白字（filled）。 */
    private fun circleBtn(
        text: String,
        sizeDp: Int,
        filled: Boolean = false,
        onClick: () -> Unit
    ): TextView = TextView(ctx).apply {
        this.text = text
        textSize = if (sizeDp >= 40) 20f else 12f
        gravity = Gravity.CENTER
        includeFontPadding = false
        maxLines = 1
        setTextColor(if (filled) Color.WHITE else cTextPrimary)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (filled) cAccent else cCard)
            if (!filled) setStroke(ctx.dp(1), cBorder)
        }
        setPadding(0, 0, 0, 0)
        isClickable = true
        isFocusable = false
        setOnClickListener { onClick() }
    }

    /** 发送按钮状态：发送中浅粉底 + 深粉箭头（仍可点，用来取消）。 */
    private fun updateSendState() {
        if (!::sendBtn.isInitialized) return
        sendBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (sending) cAccentSoft else cAccent)
        }
        sendBtn.setTextColor(if (sending) cAccentInk else Color.WHITE)
        sendBtn.text = if (sending) "×" else "↑"
        sendBtn.contentDescription = if (sending) "取消这次回复" else "发送"
    }

    private fun setSending(v: Boolean) {
        sending = v
        updateSendState()
    }

    /** 欢迎语左侧的头像占位：淡粉圆底 + ✦。 */
    private fun avatarView(): TextView = TextView(ctx).apply {
        text = "✦"
        textSize = 16f
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(cAccentInk)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(cAccentSoft)
        }
    }

    /** 页面大标题：20sp 装饰字体；字体加载失败退回系统粗体。正文一律系统字体。 */
    private fun decoTitle(text: String): TextView {
        val deco: Typeface? = try {
            ResourcesCompat.getFont(ctx, R.font.long_cang)
        } catch (t: Throwable) {
            null
        }
        return TextView(ctx).apply {
            this.text = text
            textSize = 20f
            setTextColor(cTextPrimary)
            typeface = deco ?: Typeface.DEFAULT_BOLD
            includeFontPadding = false
            maxLines = 1
        }
    }

    /** 流式过程中刷新「还没写完」的气泡：文字末尾跟一条玫粉竖条光标（不用三点动画）。 */
    private fun showStreaming(visible: String) {
        val b = streamBubble ?: return
        if (b.parent == null) return
        val body = if (visible.isBlank()) "…" else plain(visible)
        // 光标用「▍」字符 + 主色，比画 View 简单，而且会随文字自动换行
        val sp = SpannableString(body + "▍")
        sp.setSpan(
            ForegroundColorSpan(cAccent),
            body.length, body.length + 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        b.text = sp
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /** 收尾：把流式气泡定稿；没有流式气泡时按普通气泡插入。 */
    private fun markStreaming(text: String) {
        val b = streamBubble
        if (b != null && b.parent != null) {
            b.text = plain(text)
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        } else {
            addBubble(text, true)
        }
        streamBubble = null
    }

    private fun addLog(text: String) {
        val tv = textView(ctx, text, 11f, ctx.color(R.color.text_secondary))
        tv.setPadding(ctx.dp(4), ctx.dp(2), ctx.dp(4), ctx.dp(6))
        msgBox.addView(tv)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}