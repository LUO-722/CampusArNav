package com.campus.arnav.ui

import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.campus.arnav.R

/** 一些手写 View 的小工具，避免引入 Material 依赖。 */

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.color(id: Int): Int = ContextCompat.getColor(this, id)

fun Context.toast(msg: String) {
    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

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
    if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    includeFontPadding = false
}

fun card(ctx: Context, padDp: Int = 14): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(ctx.dp(padDp), ctx.dp(padDp), ctx.dp(padDp), ctx.dp(padDp))
    background = GradientDrawable().apply {
        cornerRadius = ctx.dp(16).toFloat()
        setColor(ctx.color(R.color.bg_card))
    }
}

fun rounded(ctx: Context, colorInt: Int, radiusDp: Int = 12): GradientDrawable =
    GradientDrawable().apply {
        cornerRadius = ctx.dp(radiusDp).toFloat()
        setColor(colorInt)
    }

fun button(
    ctx: Context,
    text: String,
    bg: Int = ctx.color(R.color.bg_card),
    fg: Int = ctx.color(R.color.accent)
): Button = Button(ctx).apply {
    this.text = text
    isAllCaps = false
    textSize = 14f
    setTextColor(fg)
    background = rounded(ctx, bg, 12)
    minHeight = ctx.dp(44)
    minimumHeight = ctx.dp(44)
    setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
}

fun editText(
    ctx: Context,
    hint: String,
    inputType: Int = InputType.TYPE_CLASS_TEXT,
    text: String = ""
): EditText = EditText(ctx).apply {
    this.hint = hint
    this.inputType = inputType
    setText(text)
    textSize = 14f
    setTextColor(ctx.color(R.color.text_primary))
    setHintTextColor(ctx.color(R.color.text_secondary))
    background = rounded(ctx, ctx.color(R.color.bg_dark), 10)
    setPadding(ctx.dp(10), ctx.dp(8), ctx.dp(10), ctx.dp(8))
}

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
    AlertDialog.Builder(ctx)
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
    AlertDialog.Builder(ctx)
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
    AlertDialog.Builder(ctx)
        .setTitle(title)
        .setItems(options.toTypedArray()) { _, which -> onPick(which) }
        .setNegativeButton("取消", null)
        .show()
}

/** 一行「左标题 + 右值」。 */
fun kvRow(ctx: Context, k: String, v: String): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(0, ctx.dp(6), 0, ctx.dp(6))
    addView(textView(ctx, k, 13f, ctx.color(R.color.text_secondary)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    addView(textView(ctx, v, 13f, ctx.color(R.color.text_primary)), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
}

fun divider(ctx: Context): View = View(ctx).apply {
    setBackgroundColor(Color.parseColor("#263040"))
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(1))
}
