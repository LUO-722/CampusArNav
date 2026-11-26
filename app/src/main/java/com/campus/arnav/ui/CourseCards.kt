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
 * 课程表「日视图」用的一组卡片：手写体、日期大图卡、日程卡片。
 *
 * 手写体用 res/font/long_cang.ttf（Long Cang，OFL 授权，可随 APK 分发）。
 * 字体加载失败时自动退回系统字体，界面不会崩。
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

/** 白底大圆角卡片（比 Ui.card 更松、更圆，贴近日视图的卡片观感）。 */
fun dayCard(ctx: Context, padH: Int = 16, padV: Int = 12): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(ctx.dp(padH), ctx.dp(padV), ctx.dp(padH), ctx.dp(padV + 2))
    background = GradientDrawable().apply {
        cornerRadius = ctx.dp(20).toFloat()
        setColor(Color.WHITE)
    }
    elevation = ctx.dp(2).toFloat()
}

/**
 * 一条日程卡片：彩色圆点 + 手写标题 + 时间（可带「1小时27分钟后开始」）+ 地点。
 * [passed] 为 true 时整卡淡化并对标题/时间加删除线（照搬参考图里上完的课的样子）。
 */
fun scheduleCard(
    ctx: Context,
    dotColor: Int,
    title: String,
    timeText: String,
    hint: String,
    place: String,
    passed: Boolean,
    onClick: (() -> Unit)? = null
): LinearLayout {
    val card = dayCard(ctx)

    val head = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val dot = View(ctx).apply { background = rounded(ctx, dotColor, 4) }
    head.addView(dot, LinearLayout.LayoutParams(ctx.dp(8), ctx.dp(8)).apply { rightMargin = ctx.dp(10) })
    val name = handText(ctx, title, 17f, ctx.color(R.color.text_primary)).apply {
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    head.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    card.addView(head)

    val timeLine = if (hint.isBlank()) timeText else "$timeText  |  $hint"
    val time = handText(ctx, timeLine, 13f, ctx.color(R.color.text_secondary)).apply {
        setPadding(ctx.dp(18), ctx.dp(6), 0, 0)
    }
    card.addView(time)

    if (place.isNotBlank()) {
        card.addView(handText(ctx, place, 13f, ctx.color(R.color.text_secondary)).apply {
            setPadding(ctx.dp(18), ctx.dp(2), 0, 0)
        })
    }

    if (passed) {
        card.alpha = 0.38f
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
 * 日视图顶部的日期大图卡：整块圆角照片 + 渐变蒙层 + 手写日期。
 *
 * @param dateStr "yyyy-MM-dd"
 * @param weekdayText 例如「星期四」
 * @param weekNo 第几周（<=0 时不显示）
 * @param count 当天安排数量
 * @param caption 图下角的一行小字（装饰用）
 * @param photoRes 背景照片资源
 */
fun buildDayHero(
    ctx: Context,
    dateStr: String,
    weekdayText: String,
    weekNo: Int,
    count: Int,
    isToday: Boolean,
    caption: String,
    photoRes: Int
): FrameLayout {
    val hero = FrameLayout(ctx).apply {
        background = GradientDrawable().apply {
            cornerRadius = ctx.dp(24).toFloat()
            setColor(Color.parseColor("#4A7FB5"))
        }
        clipToOutline = true
        elevation = ctx.dp(3).toFloat()
    }

    val photo = ImageView(ctx).apply {
        setImageResource(photoRes)
        scaleType = ImageView.ScaleType.CENTER_CROP
    }
    hero.addView(photo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    // 上深下也深一点：日期和底部小字都压得住
    val scrim = View(ctx).apply {
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#59000000"), Color.parseColor("#12000000"), Color.parseColor("#80000000"))
        )
    }
    hero.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

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
