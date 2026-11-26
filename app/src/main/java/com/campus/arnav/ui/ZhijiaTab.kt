package com.campus.arnav.ui

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import com.campus.arnav.R
import com.campus.arnav.agent.NoticeExtractor
import com.campus.arnav.data.ActivityDraft
import com.campus.arnav.data.CourseStore
import com.campus.arnav.data.ScheduleLogic
import com.campus.arnav.net.WebPage
import com.campus.arnav.ocr.LocalOcr

/**
 * ② 智嘉：粘贴文字 / 选图片 / 贴链接 → 抽活动 → 检查冲突 → 录进课表。
 */
class ZhijiaTab(
    private val ctx: Context,
    private val onSaved: () -> Unit,
    private val onPickImage: () -> Unit
) : LinearLayout(ctx) {

    private var mode = 0 // 0 文字 1 图片 2 链接
    private val modeButtons = ArrayList<android.widget.Button>()
    private val input = editText(ctx, "把学校通知原文粘贴到这里…", android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE)
    private val imageLabel = textView(ctx, "还没有选择图片", 13f, ctx.color(R.color.text_secondary))
    private var imageUri: Uri? = null
    private val pickRow = LinearLayout(ctx)
    private val status = textView(ctx, "", 12f, ctx.color(R.color.text_secondary))
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
        addView(status)

        addView(textView(ctx, "识别结果（勾选要录入的）", 15f, ctx.color(R.color.text_primary), bold = true).apply {
            setPadding(0, ctx.dp(8), 0, ctx.dp(4))
        })
        draftBox.orientation = VERTICAL
        addView(draftBox)

        saveBtn.setOnClickListener { saveSelected() }
        saveBtn.visibility = GONE
        addView(saveBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(46)).apply {
            topMargin = ctx.dp(10)
            bottomMargin = ctx.dp(12)
        })

        setMode(0)
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
        // 选完图立刻用本机 ML Kit OCR 识字；图片不上传、不需要 VL Key
        ocrBusy = true
        status.text = "已选图片，正在用本机 OCR 识别文字（离线，不上传）…"
        status.setTextColor(ctx.color(R.color.text_secondary))
        Thread {
            try {
                val text = LocalOcr.recognizeLines(ctx, uri)
                post {
                    ocrBusy = false
                    if (text.isBlank()) {
                        status.text = "本机识字结果为空：图片可能太模糊，试试重新截图或裁剪后再试。"
                    } else {
                        setMode(0)
                        input.setText(text)
                        status.text = "本机已识别 ${text.length} 字（图片未上传），可先修改文字，再点「让智嘉提取活动」"
                        status.setTextColor(ctx.color(R.color.accent))
                        ctx.toast("已本机识别文字，图片未上传")
                    }
                }
            } catch (e: Exception) {
                post {
                    ocrBusy = false
                    status.text = "本机识字失败：${e.message}"
                }
            }
        }.start()
    }

    private fun extract() {
        if (extracting) {
            ctx.toast("正在提取，请稍等…")
            return
        }
        if (mode == 1) {
            val uri = imageUri ?: run { ctx.toast("先选一张通知截图"); return }
            if (ocrBusy) {
                ctx.toast("正在本机识字，请稍等…")
                return
            }
            extracting = true
            extractBtn.isEnabled = false
            extractBtn.text = "提取中…"
            status.text = "正在本机识字，再用 DeepSeek 提取活动（图片不上传）…"
            status.setTextColor(ctx.color(R.color.text_secondary))
            Thread {
                try {
                    val text = LocalOcr.recognizeLines(ctx, uri)
                    if (text.isBlank()) {
                        post { doneExtract(); status.text = "本机识字结果为空，无法提取活动。" }
                        return@Thread
                    }
                    val out = NoticeExtractor.extractText(ctx, text)
                    post { doneExtract(); showDrafts(out) }
                } catch (e: Exception) {
                    post { doneExtract(); status.text = "提取失败：${e.message}" }
                }
            }.start()
        } else {
            val text = input.text.toString().trim()
            if (text.isEmpty()) {
                ctx.toast(if (mode == 2) "先粘贴网址" else "先粘贴通知原文")
                return
            }
            extracting = true
            extractBtn.isEnabled = false
            extractBtn.text = "提取中…"
            status.text = when {
                mode == 2 && text.contains("mp.weixin.qq.com") ->
                    "公众号文章常被反爬拦截，正在尝试抓取；失败请改用截图或粘贴正文"
                mode == 2 -> "正在抓网页正文…"
                else -> "正在让 DeepSeek 提取活动…"
            }
            status.setTextColor(ctx.color(R.color.text_secondary))
            Thread {
                try {
                    val body = if (mode == 2) WebPage.fetch(text) else text
                    val out = NoticeExtractor.extractText(ctx, body)
                    post { doneExtract(); showDrafts(out) }
                } catch (e: Exception) {
                    post {
                        doneExtract()
                        status.text = if (mode == 2) {
                            "提取失败：${e.message}（公众号文章可能被反爬拦截，试试截图或粘贴正文）"
                        } else {
                            "提取失败：${e.message}"
                        }
                    }
                }
            }.start()
        }
    }

    private fun doneExtract() {
        extracting = false
        extractBtn.isEnabled = true
        extractBtn.text = "让智嘉提取活动"
    }

    private fun showDrafts(list: List<ActivityDraft>) {
        drafts.clear()
        drafts.addAll(list)
        checked.clear()
        repeat(list.size) { checked.add(true) }
        status.text = if (list.isEmpty()) "没有识别到需要参加的活动，换一段原文试试。" else "识别到 ${list.size} 项活动，核对后录入。"
        status.setTextColor(if (list.isEmpty()) Color.parseColor("#E8447A") else ctx.color(R.color.text_secondary))
        renderDrafts()
    }

    private fun renderDrafts() {
        draftBox.removeAllViews()
        saveBtn.visibility = if (drafts.isEmpty()) GONE else VISIBLE
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
                addView(textView(ctx, "${d.date}  ${d.startTime}-${d.endTime}", 13f, ctx.color(R.color.accent)))
                val place = listOf(d.location, d.organizer).filter { it.isNotBlank() }.joinToString(" · ")
                if (place.isNotBlank()) addView(textView(ctx, place, 12f, ctx.color(R.color.text_secondary)))
                if (d.note.isNotBlank()) addView(textView(ctx, d.note, 12f, ctx.color(R.color.text_secondary)))
                addView(textView(ctx, "点卡片可修改", 11f, ctx.color(R.color.text_secondary)))
            }
            c.setOnClickListener { editDraft(i) }
            draftBox.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ctx.dp(8) })
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
