package com.campus.arnav.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.campus.arnav.R

/** 一些手写 View 的小工具，避免引入 Material 依赖。 */

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.color(id: Int): Int = ContextCompat.getColor(this, id)

fun Context.toast(msg: String) {
    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

// ---------------------------------------------------------------------------
// 基础文本
// ---------------------------------------------------------------------------

fun textView(
    ctx: Context,
    text: String,
    sizeSp: Float = 14f,
    color: Int = ctx.color(R.color.text_primary),
    bold: Boolean = false
): TextView = TextView(ctx).apply {
    this.text = text
    textSize = sizeSp
    setTextColor(color)
    if (bold) setTypeface(typeface, Typeface.BOLD)
    includeFontPadding = false
}

/** 22sp 页面大标题，用装饰字体（long_cang）；字体加载失败退回系统粗体。 */
fun titleText(ctx: Context, text: String): TextView {
    val deco: Typeface? = try {
        ResourcesCompat.getFont(ctx, R.font.long_cang)
    } catch (t: Throwable) {
        null
    }
    return TextView(ctx).apply {
        this.text = text
        textSize = 22f
        setTextColor(ctx.color(R.color.text_primary))
        typeface = deco ?: Typeface.DEFAULT_BOLD
        includeFontPadding = false
    }
}

/** 15sp 正文，系统字体，行距 1.35。 */
fun bodyText(ctx: Context, text: String): TextView = TextView(ctx).apply {
    this.text = text
    textSize = 15f
    setTextColor(ctx.color(R.color.text_primary))
    setLineSpacing(0f, 1.35f)
    includeFontPadding = false
}

/** 12.5sp 说明文字，次要色。 */
fun captionText(ctx: Context, text: String): TextView = TextView(ctx).apply {
    this.text = text
    textSize = 12.5f
    setTextColor(ctx.color(R.color.text_secondary))
    includeFontPadding = false
}

/** 小节标题：15sp 粗体 + 左侧 3dp 圆角粉色竖条（compound drawable 实现）。 */
fun sectionTitle(ctx: Context, text: String): TextView = TextView(ctx).apply {
    this.text = text
    textSize = 15f
    setTextColor(ctx.color(R.color.text_primary))
    setTypeface(typeface, Typeface.BOLD)
    includeFontPadding = false
    gravity = Gravity.CENTER_VERTICAL
    val bar = GradientDrawable().apply {
        setColor(ctx.color(R.color.accent))
        cornerRadius = ctx.dp(2).toFloat()
        setSize(ctx.dp(3), ctx.dp(14))
    }
    setCompoundDrawables(bar, null, null, null)
    compoundDrawablePadding = ctx.dp(8)
}

// ---------------------------------------------------------------------------
// 形状 / 背景
// ---------------------------------------------------------------------------

/** Ins 风格页面底：上粉下浅蓝的柔和渐变（#FFF8FB → #EFF7FE）。 */
fun insBackground(ctx: Context): GradientDrawable = GradientDrawable(
    GradientDrawable.Orientation.TOP_BOTTOM,
    intArrayOf(ctx.color(R.color.bg_top), ctx.color(R.color.bg_bottom))
)

fun rounded(ctx: Context, colorInt: Int, radiusDp: Int = 12): GradientDrawable =
    GradientDrawable().apply {
        cornerRadius = ctx.dp(radiusDp).toFloat()
        setColor(colorInt)
    }

// ---------------------------------------------------------------------------
// 容器 / 卡片
// ---------------------------------------------------------------------------

/** 白卡片：圆角 20dp、1dp 描边、2dp 阴影、clipToPadding=false。 */
fun card(ctx: Context, padDp: Int = 16): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(ctx.dp(padDp), ctx.dp(padDp), ctx.dp(padDp), ctx.dp(padDp))
    background = GradientDrawable().apply {
        cornerRadius = ctx.dp(20).toFloat()
        setColor(ctx.color(R.color.bg_card))
        setStroke(ctx.dp(1), ctx.color(R.color.border))
    }
    clipToPadding = false
    elevation = ctx.dp(2).toFloat()
}

// ---------------------------------------------------------------------------
// 按钮
// ---------------------------------------------------------------------------

/** 主按钮：44dp 高、14dp 圆角、粉色实底白字。 */
fun primaryButton(ctx: Context, text: String): TextView = TextView(ctx).apply {
    this.text = text
    textSize = 15f
    setTextColor(Color.WHITE)
    setTypeface(typeface, Typeface.BOLD)
    gravity = Gravity.CENTER
    includeFontPadding = false
    background = GradientDrawable().apply {
        cornerRadius = ctx.dp(14).toFloat()
        setColor(ctx.color(R.color.accent))
    }
    minHeight = ctx.dp(44)
    minimumHeight = ctx.dp(44)
    setPadding(ctx.dp(16), 0, ctx.dp(16), 0)
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ctx.dp(44)
    )
}

/** 次按钮（幽灵按钮）：40dp 高、白底 + 1dp 描边 + 主文字色。 */
fun ghostButton(ctx: Context, text: String): TextView = TextView(ctx).apply {
    this.text = text
    textSize = 14f
    setTextColor(ctx.color(R.color.text_primary))
    gravity = Gravity.CENTER
    includeFontPadding = false
    background = GradientDrawable().apply {
        cornerRadius = ctx.dp(14).toFloat()
        setColor(ctx.color(R.color.bg_card))
        setStroke(ctx.dp(1), ctx.color(R.color.border))
    }
    minHeight = ctx.dp(40)
    minimumHeight = ctx.dp(40)
    setPadding(ctx.dp(16), 0, ctx.dp(16), 0)
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ctx.dp(40)
    )
}

/** 圆形图标按钮：白底 + 1dp 描边 + 主文字色 tint。 */
fun iconButton(
    ctx: Context,
    drawableRes: Int,
    desc: String,
    sizeDp: Int = 36
): ImageButton = ImageButton(ctx).apply {
    setImageResource(drawableRes)
    contentDescription = desc
    imageTintList = ColorStateList.valueOf(ctx.color(R.color.text_primary))
    scaleType = ImageView.ScaleType.CENTER_INSIDE
    background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(ctx.color(R.color.bg_card))
        setStroke(ctx.dp(1), ctx.color(R.color.border))
    }
    val size = ctx.dp(sizeDp)
    layoutParams = ViewGroup.LayoutParams(size, size)
    setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
}

/** 旧版通用按钮（保持签名，供已改造页面继续使用）。 */
fun button(
    ctx: Context,
    text: String,
    bg: Int = ctx.color(R.color.bg_card),
    fg: Int = ctx.color(R.color.accent_ink)
): Button = Button(ctx).apply {
    this.text = text
    isAllCaps = false
    textSize = 14f
    setTextColor(fg)
    background = GradientDrawable().apply {
        cornerRadius = ctx.dp(14).toFloat()
        setColor(bg)
        setStroke(ctx.dp(1), ctx.color(R.color.border))
    }
    minHeight = ctx.dp(44)
    minimumHeight = ctx.dp(44)
    setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
}

// ---------------------------------------------------------------------------
// 标签 / 气泡
// ---------------------------------------------------------------------------

/** 胶囊筛选 chip：选中粉底深字 + 主色描边，未选白底次要字 + 普通描边。 */
fun chip(ctx: Context, text: String, selected: Boolean): TextView = TextView(ctx).apply {
    this.text = text
    textSize = 13f
    includeFontPadding = false
    gravity = Gravity.CENTER
    setTextColor(
        if (selected) ctx.color(R.color.accent_ink) else ctx.color(R.color.text_secondary)
    )
    background = GradientDrawable().apply {
        cornerRadius = ctx.dp(999).toFloat()
        setColor(
            if (selected) ctx.color(R.color.accent_soft) else ctx.color(R.color.bg_card)
        )
        setStroke(
            ctx.dp(1),
            if (selected) ctx.color(R.color.accent) else ctx.color(R.color.border)
        )
    }
    setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(12), ctx.dp(6))
}

/** 小号角标：12sp、粉底深字、圆角 8dp。 */
fun badge(ctx: Context, text: String): TextView = TextView(ctx).apply {
    this.text = text
    textSize = 12f
    includeFontPadding = false
    setTextColor(ctx.color(R.color.accent_ink))
    background = GradientDrawable().apply {
        cornerRadius = ctx.dp(8).toFloat()
        setColor(ctx.color(R.color.accent_soft))
    }
    setPadding(ctx.dp(6), ctx.dp(2), ctx.dp(6), ctx.dp(2))
}

// ---------------------------------------------------------------------------
// 空态
// ---------------------------------------------------------------------------

/** 居中空态：淡粉圆形 ✦ + 标题 + 说明 + 可选操作按钮。 */
fun emptyState(
    ctx: Context,
    title: String,
    desc: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER_HORIZONTAL
    setPadding(0, ctx.dp(24), 0, ctx.dp(24))

    addView(TextView(ctx).apply {
        text = "✦"
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(ctx.color(R.color.primary_deep))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ctx.color(R.color.accent_soft))
        }
        layoutParams = LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44))
    })

    addView(textView(ctx, title, 15f, ctx.color(R.color.text_primary), bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(0, ctx.dp(12), 0, 0)
    })

    addView(textView(ctx, desc, 12.5f, ctx.color(R.color.text_secondary)).apply {
        gravity = Gravity.CENTER
        setPadding(ctx.dp(20), ctx.dp(6), ctx.dp(20), 0)
    })

    if (!actionText.isNullOrBlank()) {
        addView(ghostButton(ctx, actionText).apply {
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = ctx.dp(14)
            layoutParams = lp
            setOnClickListener { onAction?.invoke() }
        })
    }
}

// ---------------------------------------------------------------------------
// 输入
// ---------------------------------------------------------------------------

/** 输入框：白底、12dp 圆角、1dp 描边、15sp 系统字体、hint 用 text_hint。 */
fun editText(
    ctx: Context,
    hint: String,
    inputType: Int = InputType.TYPE_CLASS_TEXT,
    text: String = ""
): EditText = EditText(ctx).apply {
    this.hint = hint
    this.inputType = inputType
    setText(text)
    textSize = 15f
    setTextColor(ctx.color(R.color.text_primary))
    setHintTextColor(ctx.color(R.color.text_hint))
    includeFontPadding = false
    background = GradientDrawable().apply {
        cornerRadius = ctx.dp(12).toFloat()
        setColor(ctx.color(R.color.bg_card))
        setStroke(ctx.dp(1), ctx.color(R.color.border))
    }
    setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
    minHeight = ctx.dp(46)
}

// ---------------------------------------------------------------------------
// 弹窗
// ---------------------------------------------------------------------------

/** 简单表单弹窗：fields 为 标题 to 初始值；点确定后按顺序回调。 */
fun inputForm(
    ctx: Context,
    title: String,
    fields: List<Pair<String, String>>,
    multiline: Boolean = false,
    onResult: (List<String>) -> Unit
) {
    val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(18), ctx.dp(8), ctx.dp(18), 0)
    }
    val edits = ArrayList<EditText>()
    for ((label, init) in fields) {
        box.addView(textView(ctx, label, 12f, ctx.color(R.color.text_secondary)))
        val e = editText(
            ctx, label,
            if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else InputType.TYPE_CLASS_TEXT,
            init
        )
        if (multiline) e.minLines = 5
        box.addView(e)
        edits.add(e)
    }
    AlertDialog.Builder(ctx, R.style.Theme_CampusAr_Dialog)
        .setTitle(title)
        .setView(box)
        .setPositiveButton("确定") { _, _ -> onResult(edits.map { it.text.toString().trim() }) }
        .setNegativeButton("取消", null)
        .show()
}

fun confirm(
    ctx: Context,
    title: String,
    msg: String,
    okText: String = "确定",
    onCancel: (() -> Unit)? = null,
    onOk: () -> Unit
) {
    AlertDialog.Builder(ctx, R.style.Theme_CampusAr_Dialog)
        .setTitle(title)
        .setMessage(msg)
        .setPositiveButton(okText) { _, _ -> onOk() }
        .setNegativeButton("取消") { _, _ -> onCancel?.invoke() }
        .show()
}

fun choose(
    ctx: Context,
    title: String,
    options: List<String>,
    onPick: (Int) -> Unit
) {
    AlertDialog.Builder(ctx, R.style.Theme_CampusAr_Dialog)
        .setTitle(title)
        .setItems(options.toTypedArray()) { _, which -> onPick(which) }
        .setNegativeButton("取消", null)
        .show()
}

// ---------------------------------------------------------------------------
// 行 / 分割线
// ---------------------------------------------------------------------------

/** 一行「左标题 + 右值」。 */
fun kvRow(ctx: Context, k: String, v: String): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(0, ctx.dp(6), 0, ctx.dp(6))
    addView(textView(ctx, k, 13f, ctx.color(R.color.text_secondary)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    addView(textView(ctx, v, 13f, ctx.color(R.color.text_primary)), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
}

/** 分割线：1dp、描边色、左右各留 4dp。 */
fun divider(ctx: Context): View {
    val v = View(ctx)
    v.setBackgroundColor(ctx.color(R.color.border))
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(1))
    lp.setMargins(ctx.dp(4), 0, ctx.dp(4), 0)
    v.layoutParams = lp
    return v
}

// ---------------------------------------------------------------------------
// 系统栏
// ---------------------------------------------------------------------------

/** 浅色皮肤：状态栏与导航栏图标改成深色，否则白色图标在浅底上看不见。 */
fun Activity.insLightBars() {
    // 先取 decorView 保证 DecorView 已经建好：onCreate 里直接摸 window.insetsController
    // 会因为 PhoneWindow 还没有 DecorView 抛 NPE（真机 0.9.0 首启崩溃就是这个原因）。
    val decor = window.decorView
    @Suppress("DEPRECATION")
    val lightStatus = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    @Suppress("DEPRECATION")
    val lightNav = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    } else {
        0
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(mask, mask)
        } catch (t: Throwable) {
            // 老 ROM / 早期时机仍可能失败，退回旧接口
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = decor.systemUiVisibility or lightStatus or lightNav
        }
    } else {
        @Suppress("DEPRECATION")
        decor.systemUiVisibility = decor.systemUiVisibility or lightStatus or lightNav
    }
}