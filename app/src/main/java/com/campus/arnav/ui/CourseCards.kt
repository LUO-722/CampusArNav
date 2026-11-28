package com.campus.arnav.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.campus.arnav.R

/**
 * 课程表「日视图」用的一组卡片：日期大图卡 + 日程卡片。
 *
 * 手写体（res/font/long_cang.ttf）只留给日期大图卡和页面大标题；
 * 日程卡片里的课名/时间/地点一律用系统黑体，浅粉底上更清楚。
 *
 * 视觉方向：Ins 浅色·精致化 —— 白卡 + 1dp #F2E4EC 描边 + 圆角 18dp，
 * 左侧 4dp 课程色竖条，右上角「第 N-M 节」小徽标。
 */

private var handTypefaceCache: Typeface? = null
private var handTypefaceLoaded = false

/** 手写体；拿不到字体时返回 null（调用方按系统字体处理）。 */
fun handTypeface(ctx: Context): Typeface? {
    if (!handTypefaceLoaded) {
        handTypefaceLoaded = true
        handTypefaceCache = try {
            ResourcesCompat.getFont(ctx, R.font.long_cang)
        } catch (t: Throwable) {
            null
        }
    }
    return handTypefaceCache
}

/** 手写体文本；字体缺失时退化为普通文本，不影响可读性。 */
fun handText(
    ctx: Context,
    text: String,
    sizeSp: Float,
    color: Int,
    bold: Boolean = false
): TextView = textView(ctx, text, sizeSp, color, bold).apply {
    handTypeface(ctx)?.let { typeface = it }
    setLineSpacing(0f, 1.05f)
}

/** 删除线：日视图里已经过去的课用这个弱化。 */
fun TextView.strikeThrough() {
    paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
}

// ---------------- Ins 色板 ----------------

/** Ins pastel 课程色：比旧调色板再淡一档，整页更统一。 */
private val insPastel = intArrayOf(
    0xFFFAD6E4.toInt(), 0xFFCFE8F8.toInt(), 0xFFE2DAF6.toInt(), 0xFFD3EDDF.toInt(),
    0xFFFFE0CC.toInt(), 0xFFF8DDEA.toInt(), 0xFFD6E6FA.toInt(), 0xFFF0D8E4.toInt()
)

/** 旧调色板（CourseStore.palette，库里已有课程存的是这组色），按位置换成新 pastel。 */
private val legacyPastel = intArrayOf(
    0xFFF8AECB.toInt(), 0xFFA8D8F2.toInt(), 0xFFC9BCEC.toInt(), 0xFFA9E3C9.toInt(),
    0xFFFFC9A8.toInt(), 0xFFF3BBDB.toInt(), 0xFF9DCBF0.toInt(), 0xFFE7B7CE.toInt()
)

/** 显示用课程色：旧调色板映射到新 pastel；其它颜色与白色混合压淡，保持整页统一。 */
internal fun coursePastel(color: Int): Int {
    val i = legacyPastel.indexOf(color)
    if (i >= 0) return insPastel[i]
    return blendWithWhite(color, 0.62f)
}

/** 颜色与白色混合（keep=原色占比，越小越淡）。 */
internal fun blendWithWhite(color: Int, keep: Float): Int {
    val r = (Color.red(color) * keep + 255 * (1 - keep)).toInt().coerceIn(0, 255)
    val g = (Color.green(color) * keep + 255 * (1 - keep)).toInt().coerceIn(0, 255)
    val b = (Color.blue(color) * keep + 255 * (1 - keep)).toInt().coerceIn(0, 255)
    return Color.rgb(r, g, b)
}

/** 颜色加深（factor<1）：课程块左侧竖条用，比 pastel 底深一档。 */
internal fun darkerColor(color: Int, factor: Float): Int {
    val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
    val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
    val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
    return Color.rgb(r, g, b)
}

/** 白底大圆角卡片（日视图 / 空态）：圆角 18dp + 1dp 描边 + elevation 2dp。 */
fun dayCard(ctx: Context, padH: Int = 16, padV: Int = 12): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(ctx.dp(padH), ctx.dp(padV), ctx.dp(padH), ctx.dp(padV + 2))
    background = GradientDrawable().apply {
        cornerRadius = ctx.dp(18).toFloat()
        setColor(Color.WHITE)
        setStroke(ctx.dp(1), 0xFFF2E4EC.toInt())
    }
    elevation = ctx.dp(2).toFloat()
}

/**
 * 一条日程卡片：左侧 4dp 课程色竖条 + 课名 + 时间（可带「1小时27分钟后开始」）+ 地点，
 * 右上角可带「第 N-M 节」小徽标。
 *
 * [passed] 为 true 时整卡淡化并对标题/时间加删除线（照搬参考图里上完的课的样子）。
 * [demo] 为 true 时在标题右侧加一个「示例」小标签（演示课表一眼可辨）。
 */
fun scheduleCard(
    ctx: Context,
    dotColor: Int,
    title: String,
    timeText: String,
    hint: String,
    place: String,
    passed: Boolean,
    demo: Boolean = false,
    periodText: String = "",
    onClick: (() -> Unit)? = null
): LinearLayout {
    val barColor = darkerColor(coursePastel(dotColor), 0.82f)
    val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        background = GradientDrawable().apply {
            cornerRadius = ctx.dp(18).toFloat()
            setColor(Color.WHITE)
            setStroke(ctx.dp(1), 0xFFF2E4EC.toInt())
        }
        elevation = ctx.dp(2).toFloat()
    }

    // 左侧 4dp 课程色竖条：上下留 10dp，跟着卡片圆角走
    val bar = View(ctx).apply { background = rounded(ctx, barColor, 2) }
    card.addView(bar, LinearLayout.LayoutParams(ctx.dp(4), ViewGroup.LayoutParams.MATCH_PARENT).apply {
        topMargin = ctx.dp(10)
        bottomMargin = ctx.dp(10)
        leftMargin = ctx.dp(10)
    })

    val body = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(10), ctx.dp(12), ctx.dp(14), ctx.dp(13))
    }
    card.addView(body, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

    val head = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val name = textView(ctx, title, 15f, 0xFF3A3040.toInt(), bold = true).apply {
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        setLineSpacing(0f, 1.08f)
    }
    head.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    if (periodText.isNotBlank()) {
        val badge = textView(ctx, periodText, 10.5f, 0xFFA61E46.toInt()).apply {
            background = rounded(ctx, 0xFFFFEAF2.toInt(), 8)
            setPadding(ctx.dp(7), ctx.dp(2), ctx.dp(7), ctx.dp(2))
        }
        head.addView(badge, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = ctx.dp(8) })
    }
    if (demo) {
        // 「示例」小标签：浅粉底 + accent_ink 深字（6.03:1，原 accent 只有 3.15:1）
        val badge = textView(ctx, "示例", 10f, ctx.color(R.color.accent_ink)).apply {
            background = rounded(ctx, ctx.color(R.color.accent_soft), 7)
            setPadding(ctx.dp(5), ctx.dp(1), ctx.dp(5), ctx.dp(1))
        }
        head.addView(badge, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = ctx.dp(6) })
    }
    body.addView(head)

    val timeLine = if (hint.isBlank()) timeText else "$timeText  |  $hint"
    val time = textView(ctx, timeLine, 12.5f, 0xFF7A6A82.toInt()).apply {
        setPadding(0, ctx.dp(6), 0, 0)
    }
    body.addView(time)

    if (place.isNotBlank()) {
        body.addView(textView(ctx, place, 12.5f, 0xFF7A6A82.toInt()).apply {
            setPadding(0, ctx.dp(3), 0, 0)
        })
    }

    if (passed) {
        card.alpha = 0.40f
        name.strikeThrough()
        time.strikeThrough()
    }
    if (onClick != null) {
        card.isClickable = true
        card.setOnClickListener { onClick() }
    }
    return card
}

/**
 * 日视图顶部的日期卡：品牌渐变底 + 手写日期。
 *
 * @param dateStr "yyyy-MM-dd"
 * @param weekdayText 例如「星期四」
 * @param weekNo 第几周（<=0 时不显示）
 * @param count 当天安排数量
 * @param caption 底部左侧的一行说明
 */
fun buildDayHero(
    ctx: Context,
    dateStr: String,
    weekdayText: String,
    weekNo: Int,
    count: Int,
    isToday: Boolean,
    caption: String
): FrameLayout {
    val hero = FrameLayout(ctx).apply {
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#EE8FAE"), Color.parseColor("#8FB6E8"))
        ).apply { cornerRadius = ctx.dp(22).toFloat() }
        clipToOutline = true
        elevation = ctx.dp(2).toFloat()
    }

    // 左上：年月 + 大号日
    val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    left.addView(handText(ctx, dateStr.substring(0, 4) + "/" + dateStr.substring(5, 7), 19f, Color.WHITE))
    left.addView(handText(ctx, dateStr.substring(8, 10), 68f, Color.WHITE))
    hero.addView(left, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { gravity = Gravity.TOP or Gravity.START; leftMargin = ctx.dp(18); topMargin = ctx.dp(14) })

    // 右上：星期 + 第几周 + 今天标记
    val right = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.END
    }
    right.addView(handText(ctx, weekdayText, 17f, Color.WHITE).apply { gravity = Gravity.END })
    if (weekNo > 0) {
        right.addView(handText(ctx, "第 $weekNo 周", 13f, Color.parseColor("#E6FFFFFF")).apply { gravity = Gravity.END })
    }
    if (isToday) {
        right.addView(handText(ctx, "· 今天 ·", 13f, Color.parseColor("#E6FFFFFF")).apply { gravity = Gravity.END })
    }
    hero.addView(right, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { gravity = Gravity.TOP or Gravity.END; rightMargin = ctx.dp(18); topMargin = ctx.dp(18) })

    // 底部：一句小字 + 当天安排数
    val bottom = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
    }
    bottom.addView(
        handText(ctx, caption, 13f, Color.parseColor("#F2FFFFFF")),
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    )
    bottom.addView(handText(ctx, if (count > 0) "$count 项安排" else "暂无安排", 13f, Color.parseColor("#F2FFFFFF")))
    hero.addView(bottom, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { gravity = Gravity.BOTTOM; leftMargin = ctx.dp(18); rightMargin = ctx.dp(18); bottomMargin = ctx.dp(14) })

    return hero
}