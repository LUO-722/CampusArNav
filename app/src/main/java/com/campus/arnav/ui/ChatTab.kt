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
import com.campus.arnav.R
import com.campus.arnav.agent.ActionExecutor
import com.campus.arnav.agent.AgentEngine
import com.campus.arnav.agent.NoticeExtractor
import com.campus.arnav.data.ChatMsg
import com.campus.arnav.data.ChatStore
import com.campus.arnav.data.SettingsStore

/**
 * ④ 聊天：知识库问答 + Agent 决策（录活动/加课/闹钟/导航建议）；可发图片给通义 VL 识图。
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
    private var sending = false
    private val greeting = "你好，我是智嘉。可以问我校园制度、办事流程、楼栋位置，也能帮你录活动、加课、设闹钟、找自习的地方。"

    init {
        orientation = VERTICAL
        setBackgroundColor(ctx.color(R.color.bg_dark))
        setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(8))

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
            choose(ctx, "聊天用哪个模型", listOf(
                "DeepSeek 文本（快、便宜）",
                "通义 VL（可发图识图）",
                "打开设置填 Key"
            )) { which ->
                when (which) {
                    0 -> { SettingsStore.setChatUseVision(ctx, false); refreshProvider() }
                    1 -> { SettingsStore.setChatUseVision(ctx, true); refreshProvider() }
                    2 -> onOpenSettings()
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
        row.addView(button(ctx, "发送", ctx.color(R.color.accent), Color.parseColor("#0E1116")).apply {
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
    }

    fun refreshProvider() {
        val p = SettingsStore.chatProvider(ctx)
        modelLabel.text = if (p.ready()) {
            "模型：${p.model}" + if (SettingsStore.chatUseVision(ctx)) "（VL 模型，可发图）" else ""
        } else {
            "未配置 API Key，点左上角 ☰ 设置"
        }
    }

    private fun pickImage() {
        if (sending) {
            ctx.toast("正在回复，稍等再发图")
            return
        }
        if (!SettingsStore.vision(ctx).ready()) {
            ctx.toast("识图需要先在「设置」里填写通义 VL 的 Key")
            return
        }
        onPickImage()
    }

    /** MainShell 的图片选择器回调。 */
    fun onImagePicked(uri: Uri?) {
        if (uri == null || sending) return
        val text = input.text.toString().trim().ifBlank {
            "请识别这张图片里的通知，提取活动信息；如果有冲突请提醒我。"
        }
        input.setText("")
        addBubble("[图片] $text", false)
        history.add(ChatMsg("user", text))
        ChatStore.append(ctx, "user", text)
        sending = true
        Thread {
            try {
                val b64 = NoticeExtractor.loadImageBase64(ctx, uri)
                val r = AgentEngine.askImage(ctx, history, text, b64)
                post { complete(r) }
            } catch (e: Exception) {
                post { fail("识图失败：${e.message}") }
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
                val r = AgentEngine.ask(context, history, text)
                post { complete(r) }
            } catch (e: Exception) {
                post { fail("出错了：${e.message}") }
            }
        }.start()
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
            setTextColor(if (assistant) ctx.color(R.color.text_primary) else Color.parseColor("#0E1116"))
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
