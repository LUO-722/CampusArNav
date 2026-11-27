package com.campus.arnav.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.net.Uri
import android.text.TextUtils
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
    private val input = editText(ctx, "问校园的事，或说“下周三下午3点有个讲座帮我录入”…", android.text.InputType.TYPE_CLASS_TEXT)
    private val modelLabel = textView(ctx, "", 11f, ctx.color(R.color.text_secondary))
    private val history = ArrayList<ChatMsg>()
    private val padBottom = ctx.dp(8)
    private var imeExtra = 0
    private var sending = false
    private var streamBubble: TextView? = null

    @Volatile
    private var cancelFlag = false
    private var lastTouchAt = 0L
    private val greeting = "你好，我是智嘉。可以问我校园制度、办事流程、楼栋位置，也能帮你录活动、加课、设闹钟、找自习的地方。"

    /** 当前会话 sid（构造时新开）。 */
    private lateinit var sessionId: String
    private lateinit var recordBtn: Button
    private lateinit var clearBtn: Button
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
        setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), padBottom)

        // 每次进入新开一个会话；旧会话留在「记录」里
        sessionId = ChatStore.newSession(ctx)

        val head = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(button(ctx, "☰", ctx.color(R.color.bg_card), ctx.color(R.color.text_primary)).apply {
            setOnClickListener { onOpenSettings() }
        }, LinearLayout.LayoutParams(ctx.dp(42), ctx.dp(38)))
        val titleBox = LinearLayout(ctx).apply { orientation = VERTICAL }
        titleBox.addView(textView(ctx, "智嘉 · 对话", 18f, ctx.color(R.color.text_primary), bold = true))
        titleBox.addView(modelLabel)
        // 模型通道是构建期内置的，没有可选项：点一下只解释「无需配置」，不再弹配置菜单
        modelLabel.setOnClickListener {
            ctx.toast("云端模型随网络自动切换，无需配置；断网时自动用离线知识库。")
        }
        head.addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ctx.dp(10) })

        // 「记录 / 返回」：白底圆角小按钮，和旁边「清」同色系
        recordBtn = button(ctx, "记录", ctx.color(R.color.bg_card), ctx.color(R.color.text_primary)).apply {
            textSize = 11f
            minHeight = ctx.dp(38)
            minimumHeight = ctx.dp(38)
            setPadding(ctx.dp(4), 0, ctx.dp(4), 0)
            setOnClickListener { if (viewingHistory) backToCurrent() else openHistory() }
        }
        head.addView(recordBtn, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(38)).apply { leftMargin = ctx.dp(6) })

        clearBtn = button(ctx, "清", ctx.color(R.color.bg_card), ctx.color(R.color.text_secondary)).apply {
            setOnClickListener {
                confirm(ctx, "清空对话记录？", "只清空当前这段对话，其它历史记录和课表、知识库都不受影响。") {
                    ChatStore.delete(ctx, sessionId)
                    sessionId = ChatStore.newSession(ctx)
                    history.clear()
                    msgBox.removeAllViews()
                    addBubble(greeting, true)
                }
            }
        }
        head.addView(clearBtn, LinearLayout.LayoutParams(ctx.dp(42), ctx.dp(38)).apply { leftMargin = ctx.dp(6) })
        addView(head)

        msgBox.orientation = VERTICAL
        msgBox.setPadding(0, ctx.dp(8), 0, ctx.dp(8))
        scroll.addView(msgBox)
        scroll.isFillViewport = true
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        inputRow = row
        input.maxLines = 4
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
        row.addView(button(ctx, "图", ctx.color(R.color.bg_card), ctx.color(R.color.text_primary)).apply {
            setOnClickListener { pickImage() }
        }, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(42)).apply { rightMargin = ctx.dp(6) })
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button(ctx, "发送", ctx.color(R.color.accent), Color.parseColor("#FFFFFF")).apply {
            setOnClickListener { send() }
        }, LinearLayout.LayoutParams(ctx.dp(64), ctx.dp(42)).apply { leftMargin = ctx.dp(6) })
        // 整条输入行都可点：点空白处也直接进输入态，不用精确点到输入框内部。
        // 注意 clickable 的 ViewGroup 默认也是 focusable 的，这里显式关掉，
        // 否则它会在键盘重排时跟输入框抢焦点。
        row.isClickable = true
        row.isFocusable = false
        row.isFocusableInTouchMode = false
        row.setOnClickListener { grabInputFocus(openIme = true) }
        addView(row)

        val saved = ChatStore.messages(ctx, sessionId).takeLast(30)
        history.addAll(saved)
        if (saved.isEmpty()) {
            addBubble(greeting, true)
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
        val listH = (sessions.size.coerceAtMost(6) * 62 + 8)
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
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            if (index != sessions.lastIndex) box.addView(divider(ctx))
        }
        dialog.show()
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
            setPadding(0, ctx.dp(10), 0, ctx.dp(10))
            isClickable = true
            setOnClickListener { onOpen() }
        }
        val col = LinearLayout(ctx).apply { orientation = VERTICAL }
        col.addView(textView(ctx, if (s.title.isBlank()) "新对话" else s.title, 14f, ctx.color(R.color.text_primary), bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        col.addView(textView(ctx, "${fmt(s.updatedAt)} · ${count} 条", 11f, ctx.color(R.color.text_secondary)))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (s.id == sessionId) {
            row.addView(textView(ctx, "当前", 10f, ctx.color(R.color.accent_ink)).apply {
                background = rounded(ctx, ctx.color(R.color.accent_soft), 8)
                setPadding(ctx.dp(6), ctx.dp(2), ctx.dp(6), ctx.dp(2))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = ctx.dp(6)
            })
        }
        val del = textView(ctx, "删除", 11f, ctx.color(R.color.accent), bold = true).apply {
            setPadding(ctx.dp(6), ctx.dp(4), 0, ctx.dp(4))
            if (s.id == sessionId) {
                // 当前会话不可删：置灰且不响应
                setTextColor(ctx.color(R.color.text_secondary))
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
        if (history.isEmpty()) {
            addBubble(greeting, true)
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
                    v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, padBottom)
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
                    v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, padBottom + imeExtra)
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
            "当前：云端模型（内置，无需配置） · 断网自动切离线知识库"
        } else {
            "离线知识库模式（云端通道未内置，问答走本机知识库）"
        }
    }

    private fun pickImage() {
        if (sending) {
            ctx.toast("正在回复，稍等再发图")
            return
        }
        if (viewingHistory) return
        // 图片走本机 ML Kit OCR：离线、不上传、不依赖云端配置
        onPickImage()
    }

    /** MainShell 的图片选择器回调：先本机识文字，填进输入框，用户确认后再发送。 */
    fun onImagePicked(uri: Uri?) {
        if (uri == null || sending || viewingHistory) return
        ctx.toast("正在本机识别图片文字（不上传）…")
        Thread {
            try {
                val text = LocalOcr.recognizeLines(ctx, uri)
                post {
                    if (text.isBlank()) {
                        ctx.toast("图片里没认出文字，试试换一张清晰点的截图")
                    } else {
                        input.setText(text)
                        input.setSelection(text.length)
                        ctx.toast("本机已识别 ${text.length} 字（图片未上传），确认后点「发送」")
                    }
                }
            } catch (e: Exception) {
                post { ctx.toast("本机识字失败：${e.message}") }
            }
        }.start()
    }

    private fun send() {
        if (sending) {
            // 「卡住」时给一条活路：再点一次发送 = 取消这次回复，输入框里的字保留
            cancelFlag = true
            sending = false
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
        sending = true
        cancelFlag = false
        val context = ctx
        streamBubble = addBubble("…", true)
        var lastRefresh = 0L
        // 卡死兜底：100 秒还没回来就自动放开按钮，用户可以重发或取消
        postDelayed({
            if (sending) {
                cancelFlag = true
                sending = false
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
                post { completeOffline(reply + "\n\n（云端暂时不可用，已用离线知识库回答）") }
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
            return "本机知识库里暂时没找到和「$query」相关的内容。换个说法（例如「图书馆 开放时间」「宿舍门禁」）再试，" +
                "联网后再问一次会用云端模型补充回答。\n\n（离线知识库回答）"
        }
        val sb = StringBuilder("根据学校知识库：")
        hits.forEachIndexed { i, h ->
            sb.append("\n${i + 1}. ").append(h.title)
            sb.append("\n").append(h.text.trim().take(260).replace("\n", " "))
        }
        sb.append("\n\n（离线知识库回答）")
        return sb.toString()
    }

    private fun completeOffline(reply: String) {
        markStreaming(reply)
        history.add(ChatMsg("assistant", reply))
        ChatStore.add(ctx, sessionId, ChatMsg("assistant", reply))
        while (history.size > 30) history.removeAt(0)
        sending = false
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
        sending = false
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
        sending = false
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

    private fun addBubble(text: String, assistant: Boolean): TextView {
        val bubble = TextView(ctx).apply {
            this.text = if (assistant) plain(text) else text
            textSize = 14f
            maxWidth = resources.displayMetrics.widthPixels - ctx.dp(64)
            setTextColor(if (assistant) ctx.color(R.color.text_primary) else Color.parseColor("#FFFFFF"))
            setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
            background = rounded(ctx, if (assistant) ctx.color(R.color.bg_card) else ctx.color(R.color.accent), 14)
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
            setPadding(0, ctx.dp(3), 0, ctx.dp(3))
        }
        wrap.addView(bubble, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        msgBox.addView(wrap)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        return bubble
    }

    /** 流式过程中刷新「还没写完」的气泡。 */
    private fun showStreaming(visible: String) {
        val b = streamBubble ?: return
        if (b.parent == null) return
        b.text = if (visible.isBlank()) "…" else plain(visible)
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