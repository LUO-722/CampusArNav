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
import com.campus.arnav.data.ChatMsg
import com.campus.arnav.data.ChatStore
import com.campus.arnav.data.SettingsStore
import com.campus.arnav.ocr.LocalOcr

/**
 * ④ 聊天：知识库问答 + Agent 决策（录活动/加课/闹钟/导航建议）；图片先本机 OCR 识文字再提问。
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
        // 不铺纯色底，让 MainShell 的 #FFF1F7→#EDF7FF 渐变透出来
        setBackgroundColor(Color.TRANSPARENT)
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
            choose(ctx, "聊天模型", listOf(
                "DeepSeek 文本（快、便宜）",
                "打开设置填 Key"
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
    }

    fun refreshProvider() {
        val p = SettingsStore.chatProvider(ctx)
        modelLabel.text = if (p.ready()) {
            "模型：${p.model}（文本）"
        } else {
            "未配置 API Key，点左上角 ☰ 设置"
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
