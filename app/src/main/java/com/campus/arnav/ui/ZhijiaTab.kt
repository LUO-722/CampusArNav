package com.campus.arnav.ui

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
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
 * 联网抽取（DeepSeek 直连或自建代理）优先；没配任何 AI 通道时自动降级为
 * [OfflineExtractor] 本机规则解析，功能不会「死」。
 */
class ZhijiaTab(
    private val ctx: Context,
    private val onSaved: () -> Unit,
    private val onPickImage: () -> Unit,
    /** 空态里的「去课程表手动加」入口；默认什么都不做，方便预览/单测。 */
    private val onOpenTimetable: () -> Unit = {}
) : LinearLayout(ctx) {

    private var mode = 0 // 0 文字 1 图片 2 链接
    private val modeButtons = ArrayList<android.widget.Button>()
    private val input = editText(ctx, "把学校通知原文粘贴到这里…", android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE)
    private val imageLabel = textView(ctx, "还没有选择图片", 13f, ctx.color(R.color.text_secondary))
    private var imageUri: Uri? = null
    private val pickRow = LinearLayout(ctx)
    private val status = textView(ctx, "", 12f, ctx.color(R.color.text_secondary))
    private val statusRow = LinearLayout(ctx)
    private val retryBtn = button(ctx, "重试", ctx.color(R.color.bg_card), ctx.color(R.color.accent))
    private val resultTitle = textView(ctx, "识别结果（勾选要录入的）", 15f, ctx.color(R.color.text_primary), bold = true)
    /** 原文改过、但没重新识别时挂在结果区顶部的提示；提取过一次之后才可能出现。 */
    private val staleHint = textView(ctx, "内容已修改，点「让智嘉提取活动」重新识别", 12f, ctx.color(R.color.accent_ink))
    private var lastExtractedText = ""
    private var lastFailed = false
    private var emptyNotice = ""
    private val draftBox = LinearLayout(ctx)
    private val drafts = ArrayList<ActivityDraft>()
    private val checked = ArrayList<Boolean>()
    private val saveBtn = button(ctx, "录入选中活动", ctx.color(R.color.accent), Color.parseColor("#FFFFFF"))
    private val extractBtn = button(ctx, "让智嘉提取活动", ctx.color(R.color.accent), Color.parseColor("#FFFFFF"))
    private var extracting = false
    private var ocrBusy = false

    init {
        orientation = VERTICAL
        // 不铺纯色底，让 MainShell 的 #FFF1F7→#EDF7FF 渐变透出来
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(8))
        addView(textView(ctx, "智嘉", 24f, ctx.color(R.color.text_primary), bold = true))
        addView(textView(ctx, "复制通知 → 提取活动 → 冲突处理 → 进课表", 12f, ctx.color(R.color.text_secondary)))

        // 模式
        val modeRow = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            setPadding(0, ctx.dp(10), 0, ctx.dp(6))
        }
        listOf("粘贴文字", "选图片", "贴链接").forEachIndexed { i, name ->
            val b = button(ctx, name, ctx.color(R.color.bg_card), ctx.color(R.color.text_secondary))
            b.setOnClickListener { setMode(i) }
            modeButtons.add(b)
            modeRow.addView(b, LinearLayout.LayoutParams(0, ctx.dp(38), 1f).apply {
                if (i > 0) leftMargin = ctx.dp(6)
            })
        }
        addView(modeRow)

        input.minLines = 6
        addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        imageLabel.setPadding(0, ctx.dp(8), 0, ctx.dp(8))
        addView(imageLabel)

        pickRow.orientation = HORIZONTAL
        pickRow.addView(button(ctx, "选择图片", ctx.color(R.color.bg_card), ctx.color(R.color.accent)).apply {
            setOnClickListener { pickImage() }
        }, LinearLayout.LayoutParams(0, ctx.dp(40), 1f))
        pickRow.addView(button(ctx, "清除", ctx.color(R.color.bg_card), ctx.color(R.color.text_secondary)).apply {
            setOnClickListener {
                imageUri = null
                imageLabel.text = "还没有选择图片"
                imageLabel.setTextColor(ctx.color(R.color.text_secondary))
            }
        }, LinearLayout.LayoutParams(ctx.dp(64), ctx.dp(40)).apply { leftMargin = ctx.dp(6) })
        addView(pickRow)

        extractBtn.setOnClickListener { extract() }
        addView(extractBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(46)).apply { topMargin = ctx.dp(8) })

        status.setPadding(0, ctx.dp(8), 0, ctx.dp(4))
        statusRow.orientation = HORIZONTAL
        statusRow.gravity = Gravity.CENTER_VERTICAL
        statusRow.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        retryBtn.visibility = GONE
        retryBtn.setOnClickListener {
            if (!extracting) extract()
        }
        statusRow.addView(retryBtn, LinearLayout.LayoutParams(ctx.dp(64), ctx.dp(34)).apply { leftMargin = ctx.dp(8) })
        addView(statusRow)

        resultTitle.setPadding(0, ctx.dp(8), 0, ctx.dp(4))
        resultTitle.visibility = GONE
        staleHint.apply {
            visibility = GONE
            background = rounded(ctx, ctx.color(R.color.accent_soft), 10)
            setPadding(ctx.dp(10), ctx.dp(6), ctx.dp(10), ctx.dp(6))
        }
        addView(staleHint, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = ctx.dp(8) })
        addView(resultTitle)
        draftBox.orientation = VERTICAL
        addView(draftBox)

        saveBtn.setOnClickListener { saveSelected() }
        saveBtn.visibility = GONE
        addView(saveBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(46)).apply {
            topMargin = ctx.dp(10)
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
        for ((i, b) in modeButtons.withIndex()) {
            val on = i == m
            b.setTextColor(if (on) Color.parseColor("#FFFFFF") else ctx.color(R.color.text_secondary))
            b.background = rounded(ctx, if (on) ctx.color(R.color.accent) else ctx.color(R.color.bg_card), 12)
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
        imageLabel.setTextColor(ctx.color(R.color.accent))
        setMode(1)
        ocrBusy = false
        status.text = if (SettingsStore.extractOnline(ctx)) {
            "已选图片。点「让智嘉提取活动」，会由看图模型理解整张图（图片上传云端，仅用于识别、不保存）。"
        } else {
            "已选图片。当前没配 AI 或没网，会用本机识字 + 本机规则处理（图片不出手机）。"
        }
        status.setTextColor(ctx.color(R.color.accent))
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
            extractBtn.text = "提取中…"
            status.setTextColor(ctx.color(R.color.text_secondary))
            Thread {
                // 云端看图：抽到活动就直接用；失败或为空都记下原因，后面如实告诉用户（不再静默吞掉）
                var cloudError: String? = null
                var cloudEmpty = false
                if (online) {
                    post { status.text = "AI 正在读图（图片上传云端，仅用于识别、不保存）…" }
                    try {
                        val b64 = NoticeExtractor.loadImageBase64(ctx, uri)
                        val out = NoticeExtractor.extractImage(ctx, b64, "image/jpeg")
                        if (out.isNotEmpty()) {
                            post { finishExtract(out, usedOffline = false, cloudError = null, cloudEmpty = false) }
                            return@Thread
                        }
                        cloudEmpty = true
                        post { status.text = "云端看完了，图里没找到活动；再用本机规则找一遍…" }
                    } catch (e: Exception) {
                        cloudError = whyText(e)
                        post { status.text = "云端没读出来：$cloudError；改在本机识字…" }
                    }
                }
                // 兜底：本机识字（离线、不上传）+ 本机规则
                try {
                    post {
                        status.text = if (online) "本机识字中（图片不再上传）…" else "本机识字 + 本机规则（图片不出手机）…"
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
            extractBtn.text = "提取中…"
            status.text = when {
                mode == 2 && text.contains("mp.weixin.qq.com") ->
                    "公众号文章常被反爬拦截，正在尝试抓取；失败请改用截图或粘贴正文"
                mode == 2 -> "正在抓网页正文…"
                online -> "正在让 AI 提取活动…"
                else -> "正在用本机规则提取活动（不需要联网）…"
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
                "云端没读出来：$reason；已用本机规则识别 ${list.size} 项，核对后录入。"
            list.isNotEmpty() && reason != null ->
                "云端看图没读懂：$reason；改用文字模型读到了 ${list.size} 项，核对后录入。"
            list.isNotEmpty() && usedOffline ->
                "本机规则识别成功 ${list.size} 项（离线处理，文字/图片都没出手机），核对后录入。"
            list.isNotEmpty() ->
                "识别到 ${list.size} 项活动，核对后录入。"
            reason != null ->
                "云端没读出来：$reason；已用本机规则重试。"
            cloudEmpty ->
                "看完了，这段内容里没有需要到场/报名的活动。"
            else ->
                "本机没认出时间地点，可手动在课程表里＋活动。"
        }
        // 云端其实读完了、只是没有活动 —— 这是「正常但为空」，别用报警红
        status.setTextColor(
            if (list.isEmpty() && (reason != null || !cloudEmpty)) {
                Color.parseColor("#E8447A")
            } else {
                ctx.color(R.color.text_secondary)
            }
        )
        emptyNotice = if (list.isEmpty()) {
            "可点右侧「重试」再跑一次，或把通知原文补全（时间、地点、活动名）后重新提取。"
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
        emptyNotice = "可点右侧「重试」再跑一次，或把通知原文补全（时间、地点、活动名）后重新提取。"
        status.text = sanitize(message)
        status.setTextColor(Color.parseColor("#E8447A"))
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
            val c = card(ctx, 12).apply {
                setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
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
                head.addView(textView(ctx, d.title, 15f, ctx.color(R.color.text_primary), bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(head)
                val whenText = if (d.startTime.isBlank()) {
                    if (d.date.isBlank()) "时间待定" else "${d.date}  时间待定"
                } else {
                    "${d.date}  ${d.startTime}-${d.endTime}"
                }
                addView(textView(ctx, whenText, 13f, ctx.color(R.color.accent)))
                val place = listOf(d.location, d.organizer).filter { it.isNotBlank() }.joinToString(" · ")
                if (place.isNotBlank()) addView(textView(ctx, place, 12f, ctx.color(R.color.text_secondary)))
                if (d.note.isNotBlank()) addView(textView(ctx, d.note, 12f, ctx.color(R.color.text_secondary)))
                if (d.evidence.isNotBlank()) {
                    addView(textView(ctx, "图上原文：${d.evidence}", 11f, ctx.color(R.color.text_secondary)))
                }
                addView(textView(ctx, "点卡片可修改", 11f, ctx.color(R.color.text_secondary)))
            }
            c.setOnClickListener { editDraft(i) }
            draftBox.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ctx.dp(8) })
        }
    }

    /**
     * 空态常驻卡：首屏/无结果时不靠隐藏标题来消除留白，而是给一条 13sp 提示
     * 加一个真正可点的入口（去课程表手动加）。
     */
    private fun emptyCard(): LinearLayout = card(ctx, 14).apply {
        setPadding(ctx.dp(14), ctx.dp(14), ctx.dp(14), ctx.dp(14))
        addView(textView(ctx, "粘贴通知或选图片，智嘉会读出时间地点。", 13f, ctx.color(R.color.text_secondary)))
        if (emptyNotice.isNotBlank()) {
            addView(textView(ctx, emptyNotice, 12f, ctx.color(R.color.text_secondary)).apply {
                setPadding(0, ctx.dp(6), 0, 0)
            })
        }
        addView(
            button(ctx, "去课程表手动加", ctx.color(R.color.accent_soft), ctx.color(R.color.accent)).apply {
                setOnClickListener { onOpenTimetable() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.dp(38)).apply {
                topMargin = ctx.dp(10)
            }
        )
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
}
