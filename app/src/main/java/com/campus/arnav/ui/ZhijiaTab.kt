package com.campus.arnav.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.campus.arnav.R
import com.campus.arnav.agent.NoticeExtractor
import com.campus.arnav.agent.OfflineExtractor
import com.campus.arnav.data.ActivityDraft
import com.campus.arnav.data.CourseStore
import com.campus.arnav.data.ScheduleLogic
import com.campus.arnav.data.SettingsStore
import com.campus.arnav.net.WebPage
import com.campus.arnav.ocr.LocalOcr

/**
 * ② 智嘉：粘贴文字 / 选图片 / 贴链接 → 抽活动 → 检查冲突 → 录进课表。
 *
 * 联网抽取（内置云端通道）优先；通道不可用时自动降级为 [OfflineExtractor] 本机规则解析，
 * 功能不会「死」。视觉走 Ins 浅色（白卡 18dp 圆角 + 1dp 描边 + 轻阴影），
 * 提取 / 冲突处理 / 录入的业务逻辑与回调保持不变。
 */
class ZhijiaTab(
    private val ctx: Context,
    private val onSaved: () -> Unit,
    private val onPickImage: () -> Unit,
    /** 空态里的「去课程表手动加」入口；默认什么都不做，方便预览/单测。 */
    private val onOpenTimetable: () -> Unit = {}
) : LinearLayout(ctx) {

    private var mode = 0 // 0 文字 1 图片 2 链接
    private val modeCards = ArrayList<LinearLayout>()
    private val modeTitles = ArrayList<TextView>()
    private val modeDescs = ArrayList<TextView>()
    private val input = editText(
        ctx,
        "把学校通知原文粘贴到这里…",
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    )
    private val imageLabel = textView(ctx, "还没有选择图片", 12.5f, ctx.color(R.color.text_hint))
    private var imageUri: Uri? = null
    private val pickRow = LinearLayout(ctx)
    private val status = textView(ctx, "", 12.5f, ctx.color(R.color.text_secondary))
    private val statusRow = LinearLayout(ctx)
    private val retryBtn = miniGhost("重试") { if (!extracting) extract() }
    private val resultTitle = sectionTitle(ctx, "识别结果（勾选要录入的）")
    /** 原文改过、但没重新识别时挂在结果区顶部的提示；提取过一次之后才可能出现。 */
    private val staleHint = textView(ctx, "内容已修改，点「让智嘉提取活动」重新识别", 12.5f, ctx.color(R.color.accent_ink))
    private var lastExtractedText = ""
    private var lastFailed = false
    private var emptyNotice = ""
    private val draftBox = LinearLayout(ctx)
    private val drafts = ArrayList<ActivityDraft>()
    private val checked = ArrayList<Boolean>()
    private val saveBtn = pillButton("录入选中活动", primary = false)
    private val extractBtn = pillButton("让智嘉提取活动", primary = true)
    private var extracting = false
    private var ocrBusy = false

    init {
        orientation = VERTICAL
        // 不铺纯色底，让 MainShell 的 #FFF8FB→#EFF7FE 渐变透出来
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(8))

        // ---- 顶部：装饰字体标题 + 一行说明 ----
        val head = LinearLayout(ctx).apply { orientation = VERTICAL }
        head.addView(titleText(ctx, "智嘉"))
        head.addView(
            textView(ctx, "复制通知 → 提取活动 → 冲突处理 → 进课表", 12.5f, ctx.color(R.color.text_secondary))
                .apply { setPadding(0, ctx.dp(2), 0, 0) }
        )
        addView(head)

        // ---- 三个入口小卡：等分、76dp 高、选中粉底粉描边 ----
        val modeRow = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        listOf(
            Triple("✎", "粘贴文字", "粘贴原文"),
            Triple("▣", "选图片", "选一张截图"),
            Triple("⛓", "贴链接", "粘贴网址")
        ).forEachIndexed { i, (glyph, title, desc) ->
            val c = modeCard(glyph, title, desc) { setMode(i) }
            modeCards.add(c)
            modeRow.addView(c, LinearLayout.LayoutParams(0, ctx.dp(76), 1f).apply {
                if (i > 0) leftMargin = ctx.dp(8)
            })
        }
        addView(modeRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = ctx.dp(12) })

        // ---- 输入框：白底 18dp 圆角、14dp 内边距、至少 5 行 ----
        input.textSize = 15f
        input.minLines = 5
        input.setPadding(ctx.dp(14), ctx.dp(14), ctx.dp(14), ctx.dp(14))
        input.background = GradientDrawable().apply {
            cornerRadius = ctx.dp(18).toFloat()
            setColor(ctx.color(R.color.bg_card))
            setStroke(ctx.dp(1), ctx.color(R.color.border))
        }
        input.setHintTextColor(ctx.color(R.color.text_hint))
        addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = ctx.dp(12) })

        imageLabel.setPadding(0, ctx.dp(10), 0, ctx.dp(10))
        addView(imageLabel)

        pickRow.orientation = HORIZONTAL
        pickRow.addView(
            miniGhost("选择图片") { pickImage() },
            LinearLayout.LayoutParams(0, ctx.dp(38), 1f)
        )
        pickRow.addView(
            miniGhost("清除") {
                imageUri = null
                imageLabel.text = "还没有选择图片"
                imageLabel.setTextColor(ctx.color(R.color.text_hint))
            },
            LinearLayout.LayoutParams(ctx.dp(72), ctx.dp(38)).apply { leftMargin = ctx.dp(8) }
        )
        addView(pickRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = ctx.dp(8) })

        // ---- 主按钮：48dp、999 圆角、粉底白字 ----
        extractBtn.setOnClickListener { extract() }
        addView(extractBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(48)
        ).apply { topMargin = ctx.dp(14) })

        // ---- 状态行：说明 + 失败时才出现的「重试」 ----
        status.setPadding(0, ctx.dp(10), 0, ctx.dp(4))
        statusRow.orientation = HORIZONTAL
        statusRow.gravity = Gravity.CENTER_VERTICAL
        statusRow.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        retryBtn.visibility = GONE
        statusRow.addView(
            retryBtn,
            LinearLayout.LayoutParams(ctx.dp(64), ctx.dp(32)).apply { leftMargin = ctx.dp(8) }
        )
        addView(statusRow)

        // ---- staleHint：浅粉底圆角提示条 ----
        resultTitle.visibility = GONE
        staleHint.apply {
            visibility = GONE
            background = rounded(ctx, Color.parseColor("#FFF3F8"), 12)
            setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8))
        }
        addView(staleHint, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = ctx.dp(10) })
        addView(resultTitle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = ctx.dp(10) })
        draftBox.orientation = VERTICAL
        addView(draftBox)

        // ---- 次按钮：白底 + 粉描边 + 粉字 ----
        saveBtn.setOnClickListener { saveSelected() }
        saveBtn.visibility = GONE
        addView(saveBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(46)
        ).apply {
            topMargin = ctx.dp(12)
            bottomMargin = ctx.dp(12)
        })

        setMode(0)
        renderDrafts() // 首屏就摆上常驻空态提示，避免按钮到导航栏之间整片空白
        // 改过原文但没重新提取：结果区顶上挂一条提示，别让人拿旧结果去录入
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updateStaleHint()
        })
    }

    /** 原文和上次识别时不一样了，就在结果区顶部提示「重新识别」。 */
    private fun updateStaleHint() {
        if (mode == 1) {
            staleHint.visibility = GONE
            return
        }
        val now = input.text?.toString().orEmpty().trim()
        staleHint.visibility =
            if (lastExtractedText.isNotBlank() && now != lastExtractedText.trim()) VISIBLE else GONE
    }

    private fun setMode(m: Int) {
        mode = m
        for ((i, card) in modeCards.withIndex()) {
            val on = i == m
            card.background = GradientDrawable().apply {
                cornerRadius = ctx.dp(18).toFloat()
                setColor(ctx.color(if (on) R.color.accent_soft else R.color.bg_card))
                setStroke(ctx.dp(1), ctx.color(if (on) R.color.accent else R.color.border))
            }
            modeTitles.getOrNull(i)?.setTextColor(
                ctx.color(if (on) R.color.accent_ink else R.color.text_primary)
            )
            modeDescs.getOrNull(i)?.setTextColor(
                ctx.color(if (on) R.color.accent_ink else R.color.text_hint)
            )
        }
        input.visibility = if (m == 1) GONE else VISIBLE
        imageLabel.visibility = if (m == 1) VISIBLE else GONE
        pickRow.visibility = if (m == 1) VISIBLE else GONE
        if (m == 0) {
            input.hint = "把学校通知原文粘贴到这里…"
        } else if (m == 1) {
            // 图片模式
        } else {
            input.hint = "粘贴 https://… 网址（公众号文章可能被反爬，建议截图）"
        }
        updateStaleHint()
    }

    private fun pickImage() {
        onPickImage()
    }

    /** MainShellActivity 回调。 */
    fun onImagePicked(uri: Uri?) {
        if (uri == null) return
        imageUri = uri
        val name = runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "已选图片"
        imageLabel.text = "已选：$name"
        imageLabel.setTextColor(ctx.color(R.color.accent_ink))
        setMode(1)
        ocrBusy = false
        status.text = if (SettingsStore.extractOnline(ctx)) {
            "已选图片。点「让智嘉提取活动」，图片会上传到云端识别，只用于识别，不会保存。"
        } else {
            "已选图片。现在没联网，图片只在手机本地识别，不会上传。"
        }
        status.setTextColor(ctx.color(R.color.text_secondary))
    }

    private fun extract() {
        if (extracting) {
            ctx.toast("正在提取，请稍等…")
            return
        }
        retryBtn.visibility = GONE
        staleHint.visibility = GONE
        if (mode == 1) {
            val uri = imageUri ?: run { ctx.toast("先选一张通知截图"); return }
            val online = SettingsStore.extractOnline(ctx)
            extracting = true
            extractBtn.isEnabled = false
            extractBtn.text = "识别中…"
            status.setTextColor(ctx.color(R.color.text_secondary))
            Thread {
                // 云端看图：抽到活动就直接用；失败或为空都记下原因，后面如实告诉用户（不再静默吞掉）
                var cloudError: String? = null
                var cloudEmpty = false
                if (online) {
                    post { status.text = "正在识别图片（图片会上传到云端，只用于识别，不会保存）…" }
                    try {
                        val b64 = NoticeExtractor.loadImageBase64(ctx, uri)
                        val out = NoticeExtractor.extractImage(ctx, b64, "image/jpeg")
                        if (out.isNotEmpty()) {
                            post { finishExtract(out, usedOffline = false, cloudError = null, cloudEmpty = false) }
                            return@Thread
                        }
                        cloudEmpty = true
                        post { status.text = "云端没找到活动，再用本地规则找一遍…" }
                    } catch (e: Exception) {
                        cloudError = whyText(e)
                        post { status.text = "云端没读出来：$cloudError；改用本地识字…" }
                    }
                }
                // 兜底：本机识字（离线、不上传）+ 本机规则
                try {
                    post {
                        status.text = if (online) "本地识字中（图片不再上传）…" else "本地识字和规则一起跑（图片不出手机）…"
                    }
                    val text = LocalOcr.recognizeLines(ctx, uri)
                    if (text.isBlank()) {
                        post { finishExtract(emptyList(), usedOffline = true, cloudError = cloudError, cloudEmpty = cloudEmpty) }
                        return@Thread
                    }
                    var usedOffline = true
                    val out: List<ActivityDraft>
                    if (online && cloudError != null) {
                        // 图没读懂但网络还在：用云端文本模型再读一次本机识出的文字
                        out = try {
                            NoticeExtractor.extractText(ctx, text).also { usedOffline = false }
                        } catch (_: Exception) {
                            // 文字模型也没读出来：保留第一次的云端原因，继续用本机规则
                            OfflineExtractor.extract(text)
                        }
                    } else {
                        out = OfflineExtractor.extract(text)
                    }
                    post { finishExtract(out, usedOffline = usedOffline, cloudError = cloudError, cloudEmpty = cloudEmpty) }
                } catch (e: Exception) {
                    post {
                        finishExtract(
                            emptyList(),
                            usedOffline = true,
                            cloudError = cloudError ?: whyText(e),
                            cloudEmpty = cloudEmpty
                        )
                    }
                }
            }.start()
        } else {
            val text = input.text.toString().trim()
            if (text.isEmpty()) {
                ctx.toast(if (mode == 2) "先粘贴网址" else "先粘贴通知原文")
                return
            }
            val online = SettingsStore.extractOnline(ctx)
            extracting = true
            extractBtn.isEnabled = false
            extractBtn.text = "识别中…"
            status.text = when {
                mode == 2 && text.contains("mp.weixin.qq.com") ->
                    "公众号文章常被反爬拦截，正在尝试抓取；失败请改用截图或粘贴正文"
                mode == 2 -> "正在抓网页正文…"
                online -> "正在提取活动…"
                else -> "正在用本地规则提取活动（不需要联网）…"
            }
            status.setTextColor(ctx.color(R.color.text_secondary))
            Thread {
                // 记住失败原因但别静默：结果为空时要把「云端为什么没读出来」写在状态行上
                var cloudError: String? = null
                var cloudEmpty = false
                var usedOffline = !online
                try {
                    val body = if (mode == 2) WebPage.fetch(text) else text
                    val out: List<ActivityDraft> = if (online) {
                        try {
                            NoticeExtractor.extractText(ctx, body).also { cloudEmpty = it.isEmpty() }
                        } catch (e: Exception) {
                            cloudError = whyText(e)
                            usedOffline = true
                            OfflineExtractor.extract(body)
                        }
                    } else {
                        OfflineExtractor.extract(body)
                    }
                    post { finishExtract(out, usedOffline, cloudError, cloudEmpty) }
                } catch (e: Exception) {
                    val msg = if (mode == 2) {
                        "网页没打开：${whyText(e)}；换成截图，或把正文粘贴进来再提取。"
                    } else {
                        "这段内容没读进去：${whyText(e)}；换个格式再试，或手动在课程表里＋活动。"
                    }
                    post { failExtract(msg) }
                }
            }.start()
        }
    }

    private fun doneExtract() {
        extracting = false
        extractBtn.isEnabled = true
        extractBtn.text = "让智嘉提取活动"
    }

    /**
     * 提取结束：把「云端有没有读出来」如实写在状态行上，并只在失败时露出「重试」。
     * 文案口径：只出现「云端 / 本机」，不出现 Key、接口地址、模型名、堆栈。
     */
    private fun finishExtract(
        list: List<ActivityDraft>,
        usedOffline: Boolean,
        cloudError: String? = null,
        cloudEmpty: Boolean = false
    ) {
        doneExtract()
        lastFailed = list.isEmpty()
        retryBtn.visibility = if (lastFailed) VISIBLE else GONE
        drafts.clear()
        drafts.addAll(list)
        checked.clear()
        repeat(list.size) { checked.add(true) }
        val reason = cloudError?.take(80)
        status.text = when {
            list.isNotEmpty() && reason != null && usedOffline ->
                "云端没读出来：$reason；本地识别出 ${list.size} 项，核对后录入。"
            list.isNotEmpty() && reason != null ->
                "云端没读懂图片：$reason；改用文字识别读到 ${list.size} 项，核对后录入。"
            list.isNotEmpty() && usedOffline ->
                "本地识别出 ${list.size} 项（未联网），核对后录入。"
            list.isNotEmpty() ->
                "识别到 ${list.size} 项活动，核对后录入。"
            reason != null ->
                "云端没读出来：$reason；已改用本地识别重试。"
            cloudEmpty ->
                "这段内容里没有可录入的活动。"
            else ->
                "没认出时间和地点，可以到课程表里手动加活动。"
        }
        // 云端其实读完了、只是没有活动 —— 这是「正常但为空」，别用报警红
        status.setTextColor(
            if (list.isEmpty() && (reason != null || !cloudEmpty)) {
                ctx.color(R.color.danger)
            } else {
                ctx.color(R.color.text_secondary)
            }
        )
        emptyNotice = if (list.isEmpty()) {
            "可以点「重试」再试一次，或把时间、地点和活动名补全后重新提取。"
        } else {
            ""
        }
        // 记下这次识别用的原文：之后原文一改，顶部就提示重新识别
        lastExtractedText = if (mode == 1) "" else input.text?.toString().orEmpty()
        renderDrafts()
        updateStaleHint()
    }

    /** 彻底没跑起来（网页抓不到等）：给一句人话 + 引导，并露出「重试」。 */
    private fun failExtract(message: String) {
        doneExtract()
        lastFailed = true
        retryBtn.visibility = VISIBLE
        drafts.clear()
        checked.clear()
        emptyNotice = "可以点「重试」再试一次，或把时间、地点和活动名补全后重新提取。"
        status.text = sanitize(message)
        status.setTextColor(ctx.color(R.color.danger))
        lastExtractedText = if (mode == 1) "" else input.text?.toString().orEmpty()
        renderDrafts()
        updateStaleHint()
    }

    /** 把异常翻成人话，并去掉 Key/接口地址/模型名等不该给学生看的东西。 */
    private fun whyText(e: Throwable?): String {
        val raw = (e?.message ?: e?.javaClass?.simpleName ?: "").lowercase()
        return when {
            raw.contains("timeout") || raw.contains("timed out") || raw.contains("超时") -> "网络超时"
            raw.contains("unknownhost") || raw.contains("unable to resolve") ||
                raw.contains("connect") || raw.contains("socket") || raw.contains("refused") -> "连不上服务器（网络可能不通）"
            raw.contains("401") || raw.contains("403") || raw.contains("key") ||
                raw.contains("token") || raw.contains("bearer") || raw.contains("unauthor") ||
                raw.contains("验证") || raw.contains("权限") || raw.contains("forbidden") -> "服务端没通过验证"
            raw.contains("429") || raw.contains("rate") || raw.contains("频繁") -> "请求太频繁"
            raw.contains("500") || raw.contains("502") || raw.contains("503") || raw.contains("504") ||
                raw.contains("unavailable") -> "服务端暂时不可用"
            raw.contains("json") || raw.contains("parse") || raw.contains("格式") -> "返回内容没读懂"
            else -> sanitize(e?.message.orEmpty()).ifBlank { "网络或服务暂时不可用" }
        }
    }

    private fun sanitize(s: String): String {
        val cleaned = s
            .replace(Regex("https?://\\S+"), " ")
            .replace(
                Regex(
                    "(?i)\\b(deepseek|qwen|dashscope|bailian|aliyun|openai|gpt|llm|api[-_ ]?key|bearer|token)\\S*"
                ),
                " "
            )
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (cleaned.length > 80) cleaned.take(80) else cleaned
    }

    private fun renderDrafts() {
        draftBox.removeAllViews()
        resultTitle.visibility = if (drafts.isEmpty()) GONE else VISIBLE
        saveBtn.visibility = if (drafts.isEmpty()) GONE else VISIBLE
        if (drafts.isEmpty()) {
            draftBox.addView(emptyCard(), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = ctx.dp(10) })
            return
        }
        for ((i, d) in drafts.withIndex()) {
            val c = card(ctx, 14).apply {
                setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12))
                val head = LinearLayout(ctx).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val cb = CheckBox(ctx).apply {
                    isChecked = checked[i]
                    setOnCheckedChangeListener { _, v -> checked[i] = v }
                    buttonTintList = android.content.res.ColorStateList.valueOf(ctx.color(R.color.accent))
                }
                head.addView(cb, LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)))
                head.addView(
                    textView(ctx, d.title, 15f, ctx.color(R.color.text_primary), bold = true),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                addView(head)
                val whenText = if (d.startTime.isBlank()) {
                    if (d.date.isBlank()) "时间待定" else "${d.date}  时间待定"
                } else {
                    "${d.date}  ${d.startTime}-${d.endTime}"
                }
                addView(
                    textView(ctx, whenText, 12.5f, ctx.color(R.color.text_secondary))
                        .apply { setPadding(0, ctx.dp(6), 0, 0) }
                )
                val place = listOf(d.location, d.organizer).filter { it.isNotBlank() }.joinToString(" · ")
                if (place.isNotBlank()) {
                    addView(
                        textView(ctx, place, 12.5f, ctx.color(R.color.text_secondary))
                            .apply { setPadding(0, ctx.dp(4), 0, 0) }
                    )
                }
                if (d.note.isNotBlank()) {
                    addView(
                        textView(ctx, d.note, 12f, ctx.color(R.color.text_hint))
                            .apply { setPadding(0, ctx.dp(4), 0, 0) }
                    )
                }
                if (d.evidence.isNotBlank()) {
                    addView(
                        textView(ctx, "图上原文：${d.evidence}", 11.5f, ctx.color(R.color.text_hint))
                            .apply {
                                setPadding(0, ctx.dp(6), 0, 0)
                                maxLines = 1
                                ellipsize = TextUtils.TruncateAt.END
                            }
                    )
                }
                addView(
                    textView(ctx, "点卡片可修改", 11.5f, ctx.color(R.color.text_hint))
                        .apply { setPadding(0, ctx.dp(6), 0, 0) }
                )
            }
            c.setOnClickListener { editDraft(i) }
            draftBox.addView(c, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = ctx.dp(10) })
        }
    }

    /**
     * 空态常驻卡：首屏/无结果时不靠隐藏标题来消除留白，而是给一条提示
     * 加一个真正可点的入口（去课程表手动加）。
     */
    private fun emptyCard(): LinearLayout = card(ctx, 16).apply {
        addView(emptyState(
            ctx,
            "把通知交给智嘉",
            "粘贴通知原文，或选一张截图，自动读出时间、地点、活动名。",
            "去课程表手动加"
        ) { onOpenTimetable() })
        if (emptyNotice.isNotBlank()) {
            addView(
                textView(ctx, emptyNotice, 12f, ctx.color(R.color.text_hint)).apply {
                    gravity = Gravity.CENTER
                    setPadding(ctx.dp(20), ctx.dp(4), ctx.dp(20), 0)
                }
            )
        }
    }

    private fun editDraft(i: Int) {
        val d = drafts[i]
        inputForm(
            ctx, "修改活动",
            listOf(
                "活动名" to d.title,
                "日期 yyyy-MM-dd" to d.date,
                "开始 HH:mm" to d.startTime,
                "结束 HH:mm" to d.endTime,
                "地点" to d.location,
                "主办方" to d.organizer,
                "备注" to d.note
            )
        ) { v ->
            val nd = d.copy(
                title = v.getOrNull(0).orEmpty().trim().ifBlank { d.title },
                date = v.getOrNull(1).orEmpty().trim(),
                startTime = v.getOrNull(2).orEmpty().trim(),
                endTime = v.getOrNull(3).orEmpty().trim(),
                location = v.getOrNull(4).orEmpty(),
                organizer = v.getOrNull(5).orEmpty(),
                note = v.getOrNull(6).orEmpty()
            )
            val parsed = ScheduleLogic.parseDate(nd.date.ifBlank { CourseStore.today() })
            if (parsed == null) {
                ctx.toast("日期格式应为 yyyy-MM-dd")
                return@inputForm
            }
            if ((nd.startTime.isNotBlank() && !ScheduleLogic.validClock(nd.startTime)) ||
                (nd.endTime.isNotBlank() && !ScheduleLogic.validClock(nd.endTime))
            ) {
                ctx.toast("时间格式应为 HH:mm")
                return@inputForm
            }
            val st = normClock(nd.startTime)
            val en = normClock(nd.endTime)
            if (st.isNotBlank() && en.isNotBlank() &&
                ScheduleLogic.toMinutes(en) <= ScheduleLogic.toMinutes(st)
            ) {
                ctx.toast("结束时间应晚于开始时间")
                return@inputForm
            }
            drafts[i] = nd.copy(
                date = ScheduleLogic.formatDate(parsed),
                startTime = st,
                endTime = en
            )
            renderDrafts()
        }
    }

    /** "9:00" → "09:00"；空/非法原样返回。 */
    private fun normClock(s: String): String =
        if (s.isNotBlank() && ScheduleLogic.validClock(s)) {
            ScheduleLogic.fromMinutes(ScheduleLogic.toMinutes(s))
        } else s

    private fun saveSelected() {
        val picked = drafts.filterIndexed { i, _ -> checked[i] }
        if (picked.isEmpty()) {
            ctx.toast("先勾选要录入的活动")
            return
        }
        saveQueue(picked, 0, intArrayOf(0, 0, 0, 0))
    }

    private fun saveQueue(list: List<ActivityDraft>, idx: Int, stat: IntArray) {
        if (idx >= list.size) {
            val msg = buildString {
                append("已处理：录入 ${stat[0]} 项")
                if (stat[1] > 0) append("，保留原安排 ${stat[1]} 项")
                if (stat[2] > 0) append("，替换原安排 ${stat[2]} 项")
                if (stat[3] > 0) append("，跳过无效/重复 ${stat[3]} 项")
            }
            ctx.toast(msg)
            onSaved()
            drafts.clear()
            checked.clear()
            emptyNotice = ""
            lastFailed = false
            retryBtn.visibility = GONE
            renderDrafts()
            status.text = msg
            return
        }
        val d = list[idx]
        val parsed = ScheduleLogic.parseDate(d.date.ifBlank { CourseStore.today() })
        if (parsed == null) {
            stat[3]++
            saveQueue(list, idx + 1, stat)
            return
        }
        val date = ScheduleLogic.formatDate(parsed)
        val st = normClock(d.startTime)
        val en = normClock(d.endTime)
        val nd = d.copy(date = date, startTime = st, endTime = en)
        val dup = CourseStore.activities(ctx).any {
            it.title == nd.title && it.date == date && it.startTime == st
        }
        if (dup) {
            stat[3]++
            saveQueue(list, idx + 1, stat)
            return
        }
        if ((st.isNotBlank() && !ScheduleLogic.validClock(st)) ||
            (en.isNotBlank() && !ScheduleLogic.validClock(en))
        ) {
            stat[3]++
            saveQueue(list, idx + 1, stat)
            return
        }
        // 录进课表：无冲突直接落库；有冲突弹「两个同时录入 / 保留原课程表日程 / 只保存最新日程」
        ActivityRecord.record(ctx, nd, source = "zhijia") { r ->
            when (r) {
                ActivityRecord.KEEP_OLD -> stat[1]++
                ActivityRecord.NEW_ONLY -> stat[2]++
                else -> stat[0]++
            }
            saveQueue(list, idx + 1, stat)
        }
    }

    // ---- 小工具：三个入口卡 / 胶囊按钮 / 小 ghost 按钮 ----

    /** 入口小卡：白底 18dp 圆角 + 1dp 描边 + 1dp 阴影，76dp 高。 */
    private fun modeCard(glyph: String, title: String, desc: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(18).toFloat()
                setColor(ctx.color(R.color.bg_card))
                setStroke(ctx.dp(1), ctx.color(R.color.border))
            }
            elevation = ctx.dp(1).toFloat()
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, ctx.dp(18).toFloat())
                }
            }
            clipToOutline = true
            val glyphView = textView(ctx, glyph, 16f, ctx.color(R.color.accent_ink), bold = true)
                .apply { gravity = Gravity.CENTER }
            val titleView = textView(ctx, title, 13.5f, ctx.color(R.color.text_primary), bold = true)
                .apply {
                    gravity = Gravity.CENTER
                    setPadding(0, ctx.dp(4), 0, 0)
                }
            val descView = textView(ctx, desc, 11f, ctx.color(R.color.text_hint))
                .apply {
                    gravity = Gravity.CENTER
                    setPadding(0, ctx.dp(2), 0, 0)
                }
            addView(glyphView)
            addView(titleView)
            addView(descView)
            modeTitles.add(titleView)
            modeDescs.add(descView)
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** 胶囊按钮：primary = 粉底白字；否则白底 + 粉描边 + 粉字。 */
    private fun pillButton(text: String, primary: Boolean): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 15.5f
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(ctx.color(if (primary) R.color.surface else R.color.accent))
        background = GradientDrawable().apply {
            cornerRadius = ctx.dp(999).toFloat()
            if (primary) {
                setColor(ctx.color(R.color.accent))
            } else {
                setColor(ctx.color(R.color.bg_card))
                setStroke(ctx.dp(1), ctx.color(R.color.accent))
            }
        }
        isClickable = true
    }

    /** 小 ghost 按钮：白底 + 1dp 描边 + 正文深色字，38dp 高。 */
    private fun miniGhost(text: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 12.5f
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(ctx.color(R.color.text_primary))
        background = GradientDrawable().apply {
            cornerRadius = ctx.dp(14).toFloat()
            setColor(ctx.color(R.color.bg_card))
            setStroke(ctx.dp(1), ctx.color(R.color.border))
        }
        setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
        isClickable = true
        setOnClickListener { onClick() }
    }
}