package com.campus.arnav.ui

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
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
import com.campus.arnav.data.ChatStore
import com.campus.arnav.data.SettingsStore
import com.campus.arnav.kb.KbIndex
import com.campus.arnav.ocr.LocalOcr

/**
 * ④ 聊天：知识库问答 + Agent 决策（录活动/加课/闹钟/导航建议）；图片先本机 OCR 识文字再提问。
 *
 * 配了 AI 通道（自己的 Key 或自建代理）走 [AgentEngine]；没配也不报错，
 * 直接用本机 BM25 知识库回答，功能不会「死」。
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
    private val greeting = "你好，我是智嘉。可以问我校园制度、办事流程、楼栋位置，也能帮你录活动、加课、设闹钟、找自习的地方。"

    init {
        orientation = VERTICAL
        // 不铺纯色底，让 MainShell 的 #FFF1F7→#EDF7FF 渐变透出来
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), padBottom)

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
        modelLabel.setOnClickListener {
            choose(ctx, "聊天模型", listOf(
                "DeepSeek 文本（快、便宜）",
                "打开设置（填 Key 或自建代理）"
            )) { which ->
                when (which) {
                    0 -> { SettingsStore.setChatUseVision(ctx, false); refreshProvider() }
                    1 -> onOpenSettings()
                }
            }
        }
        head.addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ctx.dp(10) })
        head.addView(button(ctx, "清", ctx.color(R.color.bg_card), ctx.color(R.color.text_secondary)).apply {
            setOnClickListener {
                confirm(ctx, "清空对话记录？", "只清空本机聊天，不影响课表和知识库。") {
                    ChatStore.clear(ctx)
                    history.clear()
                    msgBox.removeAllViews()
                    addBubble(greeting, true)
                }
            }
        }, LinearLayout.LayoutParams(ctx.dp(42), ctx.dp(38)).apply { leftMargin = ctx.dp(6) })
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
        input.maxLines = 4
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send()
                true
            } else false
        }
        row.addView(button(ctx, "图", ctx.color(R.color.bg_card), ctx.color(R.color.text_primary)).apply {
            setOnClickListener { pickImage() }
        }, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(42)).apply { rightMargin = ctx.dp(6) })
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button(ctx, "发送", ctx.color(R.color.accent), Color.parseColor("#FFFFFF")).apply {
            setOnClickListener { send() }
        }, LinearLayout.LayoutParams(ctx.dp(64), ctx.dp(42)).apply { leftMargin = ctx.dp(6) })
        addView(row)

        val saved = ChatStore.recent(ctx, 30)
        if (saved.isEmpty()) {
            addBubble(greeting, true)
        } else {
            saved.forEach {
                history.add(it)
                addBubble(it.content, it.role != "user")
            }
        }
        refreshProvider()
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        bindImeInsets()
    }

    /**
     * 点输入框弹键盘时，让整个对话区（连同输入行）跟着键盘升上来。
     *
     * targetSdk 35+ 系统强制 edge-to-edge：manifest 里的 adjustResize 不再压缩窗口，
     * 键盘只把 IME insets 报给视图。不自己让位，输入行就被压在键盘底下。
     * 这里量出「本视图底边到窗口底边」还剩多少（主壳 62dp 底栏 + 系统导航栏），
     * 只补足键盘实际超出的那部分，让输入行贴着键盘上沿。
     */
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
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, padBottom + extra)
                if (extra > 0) scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
            insets
        }
    }

    fun refreshProvider() {
        val p = SettingsStore.chatProvider(ctx)
        modelLabel.text = when {
            p.ready() && p.viaProxy() -> "模型：${p.model} · 走自建代理（App 内无需 Key）"
            p.ready() -> "模型：${p.model}（文本）"
            else -> "离线知识库模式（当前无网络或云端不可用，问答走本机知识库）"
        }
    }

    private fun pickImage() {
        if (sending) {
            ctx.toast("正在回复，稍等再发图")
            return
        }
        // 图片走本机 ML Kit OCR：离线、不上传、不需要任何 Key
        onPickImage()
    }

    /** MainShell 的图片选择器回调：先本机识文字，填进输入框，用户确认后再发送。 */
    fun onImagePicked(uri: Uri?) {
        if (uri == null || sending) return
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
        val text = input.text.toString().trim()
        if (text.isEmpty() || sending) return
        input.setText("")
        addBubble(text, false)
        history.add(ChatMsg("user", text))
        ChatStore.append(ctx, "user", text)
        sending = true
        val context = ctx
        Thread {
            try {
                if (SettingsStore.chatOnline(context)) {
                    val r = AgentEngine.ask(context, history, text)
                    post { complete(r) }
                } else {
                    // 没 Key / 没代理：不报错，直接用本机知识库答（BM25，不联网）
                    val reply = answerOffline(context, text)
                    post { completeOffline(reply) }
                }
            } catch (e: Exception) {
                // 断网 / 模型全部限流 / Key 失效：不让对话断在这里，直接用离线知识库答
                val reply = answerOffline(context, text)
                post { completeOffline(reply + "\n\n（云端暂时不可用，已用离线知识库回答）") }
            }
        }.start()
    }

    /**
     * 离线兜底：本机 BM25 知识库检索后直接给条文（不联网、不需要 Key）。
     * 注意离线模式只答知识性问题，写入类操作（加课/录活动）需要配 AI。
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
        addBubble(reply, true)
        history.add(ChatMsg("assistant", reply))
        ChatStore.append(ctx, "assistant", reply)
        while (history.size > 30) history.removeAt(0)
        sending = false
    }

    private fun complete(r: AgentEngine.Result) {
        addBubble(r.reply, true)
        if (r.actions.isNotEmpty()) {
            addLog(r.actions.joinToString("\n") { "· $it" })
        }
        if (r.sources.isNotEmpty()) {
            addLog("依据：" + r.sources.joinToString("、"))
        }
        history.add(ChatMsg("assistant", r.reply))
        ChatStore.append(ctx, "assistant", r.reply)
        while (history.size > 30) history.removeAt(0)
        sending = false
        if (r.pending.isEmpty()) return
        // 写操作（加课/录活动/闹钟/删除）先让用户确认，未确认不落库
        val lines = r.pending.joinToString("\n") { "· " + AgentEngine.describe(it) }
        confirm(
            ctx, "允许智嘉执行这些操作？", lines, "允许",
            onOk = {
                val logs = r.pending.map { action ->
                    try {
                        ActionExecutor.execute(ctx, action)
                    } catch (e: Exception) {
                        "执行失败：${e.message}"
                    }
                }
                addLog(logs.joinToString("\n") { "· $it" })
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

    private fun addBubble(text: String, assistant: Boolean) {
        val bubble = TextView(ctx).apply {
            this.text = if (assistant) plain(text) else text
            textSize = 14f
            maxWidth = resources.displayMetrics.widthPixels - ctx.dp(64)
            setTextColor(if (assistant) ctx.color(R.color.text_primary) else Color.parseColor("#FFFFFF"))
            setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
            background = rounded(ctx, if (assistant) ctx.color(R.color.bg_card) else ctx.color(R.color.accent), 14)
            setTextIsSelectable(true)
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
    }

    private fun addLog(text: String) {
        val tv = textView(ctx, text, 11f, ctx.color(R.color.text_secondary))
        tv.setPadding(ctx.dp(4), ctx.dp(2), ctx.dp(4), ctx.dp(6))
        msgBox.addView(tv)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
