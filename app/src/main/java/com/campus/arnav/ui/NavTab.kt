package com.campus.arnav.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.campus.arnav.ArNavActivity
import com.campus.arnav.CalibrateActivity
import com.campus.arnav.CompassTestActivity
import com.campus.arnav.R
import com.campus.arnav.core.CampusGraph
import com.campus.arnav.core.CampusPath
import com.campus.arnav.core.GeoUtils
import com.campus.arnav.core.RouteRepository
import com.campus.arnav.core.UserLocator
import java.net.URLEncoder

/**
 * ③ 校园导航（2D 版）：
 *  - 主视图是自绘离线地图 [CampusMapView]，路线来自离线路网 [CampusGraph] 的 A* 算路；
 *  - 可用 GPS 定位作为起点并跟随（[UserLocator]），定位不可用时退回「南门」；
 *  - AR 实景与高德路线降级为可选入口，不再当主力。
 */
class NavTab(private val ctx: Context) : LinearLayout(ctx) {

    private val map = CampusMapView(ctx)
    private val routeHolder = LinearLayout(ctx)
    private val listBox = LinearLayout(ctx)
    private val query = editText(ctx, "搜楼名：明志楼、风华楼、精工楼…")

    private var keyword = ""
    private var destName: String? = null
    private var path: CampusPath? = null

    /** 起点模式：true = 我的位置（有定位才生效），false = 南门。 */
    private var fromMe = false
    private var following = false

    private var fixLat: Double = RouteRepository.gate.lat
    private var fixLon: Double = RouteRepository.gate.lon
    private var fixBearing: Float = -1f
    private var haveFix = false

    private val startLabel = textView(ctx, "起点：南门", 12f, ctx.color(R.color.accent_ink), bold = true)

    init {
        orientation = VERTICAL
        // 不铺纯色底，让 MainShell 的 #FFF1F7→#EDF7FF 渐变透出来
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(14), ctx.dp(6))

        val head = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(ctx).apply { orientation = VERTICAL }
        titleCol.addView(textView(ctx, "校园导航", 22f, ctx.color(R.color.text_primary), bold = true))
        titleCol.addView(textView(ctx, "离线路网 · 2D 地图 · 步行路线", 11f, ctx.color(R.color.text_secondary)))
        head.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(button(ctx, "更多", ctx.color(R.color.bg_card), ctx.color(R.color.text_primary)).apply {
            setOnClickListener { showMore() }
        }, LinearLayout.LayoutParams(ctx.dp(72), ctx.dp(34)))
        addView(head)

        // 地图卡片
        val mapCard = card(ctx, 12)
        mapCard.setPadding(ctx.dp(6), ctx.dp(6), ctx.dp(6), ctx.dp(6))
        mapCard.addView(map, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(300)))
        addView(mapCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dp(8)
        })
        map.setOnBuildingTap { b -> plan(b.name) }

        // 起点 / 定位 / 清除
        val tools = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ctx.dp(8), 0, ctx.dp(4))
        }
        startLabel.setPadding(ctx.dp(10), ctx.dp(6), ctx.dp(10), ctx.dp(6))
        startLabel.background = rounded(ctx, ctx.color(R.color.accent_soft), 12)
        startLabel.setOnClickListener { toggleStart() }
        tools.addView(startLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.dp(32)))
        tools.addView(button(ctx, "定位", ctx.color(R.color.bg_card), ctx.color(R.color.accent_ink)).apply {
            setOnClickListener { locate() }
        }, LinearLayout.LayoutParams(0, ctx.dp(32), 1f).apply { leftMargin = ctx.dp(6) })
        tools.addView(button(ctx, "清除", ctx.color(R.color.bg_card), ctx.color(R.color.text_primary)).apply {
            setOnClickListener { clearRoute() }
        }, LinearLayout.LayoutParams(0, ctx.dp(32), 1f).apply { leftMargin = ctx.dp(6) })
        addView(tools)

        // 路线卡（无路线时隐藏）
        routeHolder.orientation = VERTICAL
        routeHolder.visibility = View.GONE
        addView(routeHolder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 搜索
        addView(query, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dp(6)
        })
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
        listBox.setPadding(0, ctx.dp(4), 0, ctx.dp(8))
        scroll.addView(listBox)
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        if (CampusGraph.load(ctx)) {
            map.setBuildings(RouteRepository.buildings(ctx))
        }
        render()
    }

    fun refresh() {
        if (CampusGraph.load(ctx)) map.setBuildings(RouteRepository.buildings(ctx))
        if (destName != null && path != null) map.zoomToRoute()
        render()
    }

    // ---------------- 交互 ----------------

    private fun toggleStart() {
        fromMe = !fromMe
        if (fromMe) locate() else {
            fixLat = RouteRepository.gate.lat
            fixLon = RouteRepository.gate.lon
            haveFix = false
            startLabel.text = "起点：南门"
            if (destName != null) destName?.let { plan(it) }
        }
    }

    private fun locate() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            (ctx as? Activity)?.let {
                ActivityCompat.requestPermissions(
                    it,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    81
                )
            }
            ctx.toast("已请求定位权限，允许后点「定位」再试")
        }
        UserLocator.start(ctx) { fix ->
            fixLat = fix.lat
            fixLon = fix.lon
            fixBearing = fix.bearing ?: -1f
            haveFix = !UserLocator.isNoFix(fix)
            map.setMyLocation(fix.lat, fix.lon, fixBearing)
            if (haveFix) {
                fromMe = true
                startLabel.text = "起点：我的位置"
                if (destName != null) destName?.let { plan(it) }
            } else {
                ctx.toast("暂时没有定位，先用南门当起点")
            }
        }
    }

    private fun clearRoute() {
        destName = null
        path = null
        following = false
        UserLocator.stop()
        map.setRoute(emptyList())
        map.setDestination(null as String?)
        map.setFollowUser(false)
        routeHolder.removeAllViews()
        routeHolder.visibility = View.GONE
    }

    private fun plan(name: String) {
        if (!CampusGraph.load(ctx)) {
            ctx.toast("路网数据没读到，稍后再试")
            return
        }
        val p = if (fromMe && haveFix) {
            CampusGraph.routeFromPoint(fixLat, fixLon, name) ?: CampusGraph.route("南门", name)
        } else {
            CampusGraph.route("南门", name)
        }
        if (p == null) {
            ctx.toast("没能算出到「$name」的路线")
            return
        }
        destName = name
        path = p
        map.setDestination(name)
        map.setRoute(p.points)
        map.zoomToRoute()
        if (fromMe && haveFix) map.setMyLocation(fixLat, fixLon, fixBearing)
        renderRoute(p, name)
    }

    private fun startFollow() {
        val dest = destName ?: return
        following = true
        map.setFollowUser(true)
        locate()
        ctx.toast("跟随已开：蓝点会带着地图走，去 $dest")
    }

    // ---------------- 渲染 ----------------

    private fun renderRoute(p: CampusPath, name: String) {
        routeHolder.removeAllViews()

        val wrap = card(ctx, 12)
        wrap.setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))

        val head = LinearLayout(ctx).apply {
            orientation = VERTICAL
        }
        head.addView(textView(ctx, "去 $name", 15f, ctx.color(R.color.text_primary), bold = true))
        head.addView(
            textView(
                ctx,
                "${p.distanceMeters} 米 · 步行约 ${(p.distanceMeters / 80).coerceAtLeast(1)} 分钟 · 示意路线",
                11f,
                ctx.color(R.color.text_secondary)
            )
        )
        wrap.addView(head)

        val steps = p.steps.take(3)
        for ((i, s) in steps.withIndex()) {
            wrap.addView(textView(ctx, "${i + 1}. $s", 11f, ctx.color(R.color.text_primary)))
        }
        if (p.steps.size > steps.size) {
            wrap.addView(textView(ctx, "…共 ${p.steps.size} 步", 10f, ctx.color(R.color.text_secondary)))
        }

        val acts = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            setPadding(0, ctx.dp(6), 0, 0)
        }
        acts.addView(button(ctx, "跟随", ctx.color(R.color.accent), Color.WHITE).apply {
            setOnClickListener { startFollow() }
        }, LinearLayout.LayoutParams(0, ctx.dp(34), 1f))
        acts.addView(button(ctx, "AR", ctx.color(R.color.bg_card), ctx.color(R.color.accent_ink)).apply {
            setOnClickListener { openAr(name) }
        }, LinearLayout.LayoutParams(0, ctx.dp(34), 0.7f).apply { leftMargin = ctx.dp(6) })
        acts.addView(button(ctx, "高德", ctx.color(R.color.bg_card), ctx.color(R.color.text_primary)).apply {
            setOnClickListener { findBuilding(name)?.let { openAmap(it.lon, it.lat, it.name, true) } }
        }, LinearLayout.LayoutParams(0, ctx.dp(34), 0.7f).apply { leftMargin = ctx.dp(6) })
        wrap.addView(acts)

        routeHolder.addView(wrap)
        routeHolder.visibility = View.VISIBLE
    }

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
                setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(14), ctx.dp(10))
                val head2 = LinearLayout(ctx).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                head2.addView(
                    textView(ctx, b.name, 15f, ctx.color(R.color.text_primary), bold = true),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                head2.addView(textView(ctx, "去这里", 11f, ctx.color(R.color.accent_ink)))
                addView(head2)
                addView(textView(ctx, b.subtitle, 11f, ctx.color(R.color.text_secondary)))
            }
            row.setOnClickListener { plan(b.name) }
            listBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = ctx.dp(6)
            })
        }
    }

    // ---------------- 其它入口 ----------------

    private fun findBuilding(name: String) = RouteRepository.buildings(ctx).firstOrNull { it.name == name || it.id == name }

    private fun openAr(name: String) {
        val b = findBuilding(name) ?: return
        ctx.startActivity(Intent(ctx, ArNavActivity::class.java).putExtra("buildingId", b.id))
    }

    private fun showMore() {
        AlertDialog.Builder(ctx, R.style.Theme_CampusAr_Dialog)
            .setTitle("导航工具")
            .setItems(arrayOf("罗盘自检（AR 用）", "楼栋取点校准", "路线说明")) { _, which ->
                when (which) {
                    0 -> ctx.startActivity(Intent(ctx, CompassTestActivity::class.java))
                    1 -> ctx.startActivity(Intent(ctx, CalibrateActivity::class.java))
                    else -> AlertDialog.Builder(ctx, R.style.Theme_CampusAr_Dialog)
                        .setTitle("路线说明")
                        .setMessage(
                            "· 地图是离线示意图，楼与路的相对位置按官方平面导览图排的，不是卫星影像。\n" +
                                "· 路线由校内路网 A* 算出（中轴 + 两翼 + 宿舍环 + 运动区支线），距离是沿路累加，不是直线。\n" +
                                "· 坐标精度约 ±100 米，走到楼附近以门牌和路牌为准；AR 实景仅作最后 30 米参考。"
                        )
                        .setPositiveButton("知道了", null)
                        .show()
                }
            }
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

    override fun onDetachedFromWindow() {
        UserLocator.stop()
        super.onDetachedFromWindow()
    }

    /** 保留给外部：两点直线距离（调试用）。 */
    fun straightMeters(name: String): Int {
        val b = findBuilding(name) ?: return 0
        return GeoUtils.distanceMeters(RouteRepository.gate.lat, RouteRepository.gate.lon, b.lat, b.lon).toInt()
    }
}