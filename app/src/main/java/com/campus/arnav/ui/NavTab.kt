package com.campus.arnav.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import com.campus.arnav.ArNavActivity
import com.campus.arnav.CalibrateActivity
import com.campus.arnav.CompassTestActivity
import androidx.appcompat.app.AlertDialog
import com.campus.arnav.R
import com.campus.arnav.core.GeoUtils
import com.campus.arnav.core.RouteRepository
import java.net.URLEncoder

/**
 * ③ 校园导航：楼栋清单 + AR 步行导航 + 高德真实路线。
 */
class NavTab(private val ctx: Context) : LinearLayout(ctx) {

    private val listBox = LinearLayout(ctx)
    private val query = editText(ctx, "搜楼名：明志楼、风华楼、精工楼…")
    private var keyword = ""

    init {
        orientation = VERTICAL
        // 不铺纯色底，让 MainShell 的 #FFF1F7→#EDF7FF 渐变透出来
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(8))
        addView(textView(ctx, "校园导航", 24f, ctx.color(R.color.text_primary), bold = true))
        addView(textView(ctx, "潘安湖校区 · 楼栋坐标可现场校准", 12f, ctx.color(R.color.text_secondary)))

        val tools = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            setPadding(0, ctx.dp(8), 0, ctx.dp(6))
        }
        tools.addView(button(ctx, "罗盘自检", ctx.color(R.color.bg_card), ctx.color(R.color.accent_ink)).apply {
            setOnClickListener { ctx.startActivity(Intent(ctx, CompassTestActivity::class.java)) }
        }, LinearLayout.LayoutParams(0, ctx.dp(40), 1f))
        tools.addView(button(ctx, "楼栋取点校准", ctx.color(R.color.bg_card), ctx.color(R.color.text_primary)).apply {
            setOnClickListener { ctx.startActivity(Intent(ctx, CalibrateActivity::class.java)) }
        }, LinearLayout.LayoutParams(0, ctx.dp(40), 1f).apply { leftMargin = ctx.dp(6) })
        addView(tools)

        addView(query, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                keyword = s?.toString()?.trim().orEmpty()
                render()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val scroll = ScrollView(ctx)
        scroll.isFillViewport = true
        listBox.orientation = VERTICAL
        listBox.setPadding(0, ctx.dp(6), 0, ctx.dp(8))
        scroll.addView(listBox)
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        render()
    }

    fun refresh() = render()

    private fun render() {
        listBox.removeAllViews()
        val all = RouteRepository.buildings(ctx)
        val list = if (keyword.isBlank()) all else all.filter {
            it.name.contains(keyword, true) || it.subtitle.contains(keyword, true)
        }
        if (list.isEmpty()) {
            listBox.addView(textView(ctx, "没有匹配的楼栋。", 13f, ctx.color(R.color.text_secondary)))
            return
        }
        for (b in list) {
            val row = card(ctx, 12).apply {
                setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
                val head = LinearLayout(ctx).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                head.addView(textView(ctx, b.name, 16f, ctx.color(R.color.text_primary), bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                head.addView(textView(ctx, "AR", 11f, ctx.color(R.color.accent_ink)))
                addView(head)
                addView(textView(ctx, b.subtitle, 12f, ctx.color(R.color.text_secondary)))
                val meters = GeoUtils.distanceMeters(RouteRepository.gate.lat, RouteRepository.gate.lon, b.lat, b.lon)
                addView(textView(ctx, "距南门约 ${meters.toInt()} 米 · 步行约 ${(meters / 80).toInt().coerceAtLeast(1)} 分钟", 12f, ctx.color(R.color.accent_ink)))
            }
            row.setOnClickListener { showActions(b.id, b.name, b.subtitle, b.lat, b.lon) }
            listBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ctx.dp(8) })
        }
    }

    private fun showActions(id: String, name: String, subtitle: String, lat: Double, lon: Double) {
        AlertDialog.Builder(ctx, R.style.Theme_CampusAr_Dialog)
            .setTitle(name)
            .setMessage(subtitle + "\n坐标：" +
                String.format(java.util.Locale.US, "%.5f, %.5f", lat, lon))
            .setPositiveButton("AR 步行导航") { _, _ ->
                ctx.startActivity(Intent(ctx, ArNavActivity::class.java).putExtra("buildingId", id))
            }
            .setNeutralButton("高德路线") { _, _ -> openAmap(lon, lat, name, true) }
            .setNegativeButton("高德标记") { _, _ -> openAmap(lon, lat, name, false) }
            .show()
    }

    private fun openAmap(lon: Double, lat: Double, name: String, navigation: Boolean) {
        val n = URLEncoder.encode(name, "UTF-8")
        val uri = if (navigation) {
            "https://uri.amap.com/navigation?to=$lon,$lat,$n&mode=walk&policy=1&src=zhijia&coordinate=gaode&callnative=1"
        } else {
            "https://uri.amap.com/marker?position=$lon,$lat&name=$n&src=zhijia&coordinate=gaode&callnative=1"
        }
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        } catch (e: ActivityNotFoundException) {
            ctx.toast("没有找到高德地图，也没有浏览器可打开链接")
        }
    }
}
