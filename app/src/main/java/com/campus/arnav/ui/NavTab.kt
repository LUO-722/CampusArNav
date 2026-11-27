package com.campus.arnav.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
 *  - AR 实景与外部地图路线降级为可选入口，不再当主力。
 *  - 上方可切「卫星图 / 导览图」，左上角图例、左下角指北针、右下角缩放，均不依赖外部地图应用。
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

    // ---- 底图切换（选择记忆在 zhijia_map，下次进来沿用） ----
    private val mapPrefs = ctx.getSharedPreferences("zhijia_map", Context.MODE_PRIVATE)

    /** `campus_ortho` = 卫星图（默认），`campus_guide` = 官方导览图。 */
    private var baseKind: String = "campus_ortho"
    private lateinit var chipOrtho: TextView
    private lateinit var chipGuide: TextView

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

        // 底图切换：卫星图 / 导览图
        addView(baseChipRow(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dp(6)
        })

        // 地图卡片（底图之上叠加：图例、指北针、缩放）
        val mapCard = card(ctx, 12)
        mapCard.setPadding(ctx.dp(6), ctx.dp(6), ctx.dp(6), ctx.dp(6))

        val mapFrame = FrameLayout(ctx)
        mapFrame.addView(map, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val legendBtn = textView(ctx, "图例", 11f, ctx.color(R.color.accent_ink), bold = true).apply {
            setPadding(ctx.dp(10), ctx.dp(6), ctx.dp(10), ctx.dp(6))
            background = rounded(ctx, ctx.color(R.color.bg_card), 12)
            setOnClickListener { showLegend() }
        }
        mapFrame.addView(legendBtn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = ctx.dp(8)
            topMargin = ctx.dp(8)
        })

        mapFrame.addView(CompassView(ctx), FrameLayout.LayoutParams(ctx.dp(46), ctx.dp(46)).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = ctx.dp(8)
            bottomMargin = ctx.dp(8)
        })

        val zoomCol = LinearLayout(ctx).apply { orientation = VERTICAL }
        zoomCol.addView(zoomButton("＋") { map.zoomBy(1.4f) }, LinearLayout.LayoutParams(ctx.dp(36), ctx.dp(36)))
        zoomCol.addView(zoomButton("－") { map.zoomBy(0.7f) }, LinearLayout.LayoutParams(ctx.dp(36), ctx.dp(36)).apply {
            topMargin = ctx.dp(6)
        })
        mapFrame.addView(zoomCol, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = ctx.dp(8)
            bottomMargin = ctx.dp(8)
        })

        mapCard.addView(mapFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(300)))
        addView(mapCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dp(8)
        })
        map.setOnBuildingTap { b -> plan(b.name) }

        // 比例尺说明（不出现任何外部地图服务名）
        addView(textView(ctx, "约 100 米/格 · 数据为示意图，实际以现场为准", 10f, ctx.color(R.color.text_secondary)).apply {
            setPadding(ctx.dp(2), ctx.dp(4), 0, 0)
        })

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

        // 恢复上次选择的底图（未知值一律回到卫星图）
        val saved = mapPrefs.getString("base_kind", "campus_ortho")
        baseKind = if (saved == "campus_guide") "campus_guide" else "campus_ortho"
        if (baseKind != "campus_ortho") map.setBaseKind(baseKind)
        renderBaseChips()
        updateA11y()

        render()
    }

    // ---------------- 底图 / 图例 / 缩放 / 无障碍 ----------------

    /** 「底图」+ 卫星图 / 导览图 两枚胶囊。 */
    private fun baseChipRow(): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(textView(ctx, "底图", 11f, ctx.color(R.color.text_secondary)).apply {
            setPadding(0, 0, ctx.dp(8), 0)
        })
        chipOrtho = baseChip("卫星图") { switchBase("campus_ortho") }
        chipGuide = baseChip("导览图") { switchBase("campus_guide") }
        row.addView(chipOrtho, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.dp(32)))
        row.addView(chipGuide, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.dp(32)).apply {
            leftMargin = ctx.dp(6)
        })
        renderBaseChips()
        return row
    }

    private fun baseChip(label: String, onTap: () -> Unit): TextView =
        textView(ctx, label, 12f, ctx.color(R.color.accent_ink), bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
            background = rounded(ctx, ctx.color(R.color.bg_card), 14)
            setOnClickListener { onTap() }
        }

    private fun renderBaseChips() {
        if (!::chipOrtho.isInitialized || !::chipGuide.isInitialized) return
        paintChip(chipOrtho, baseKind == "campus_ortho")
        paintChip(chipGuide, baseKind == "campus_guide")
    }

    private fun paintChip(chip: TextView, selected: Boolean) {
        chip.background = rounded(ctx, ctx.color(if (selected) R.color.accent else R.color.bg_card), 14)
        chip.setTextColor(ctx.color(if (selected) R.color.bg_card else R.color.accent_ink))
    }

    private fun switchBase(kind: String) {
        val next = if (kind == "campus_guide") "campus_guide" else "campus_ortho"
        baseKind = next
        map.setBaseKind(next)
        mapPrefs.edit().putString("base_kind", next).apply()
        renderBaseChips()
        updateA11y()
        ctx.toast(if (next == "campus_guide") "已切换到导览图" else "已切换到卫星图")
    }

    /** 图例：官图上的 1—15 编号。 */
    private fun showLegend() {
        val body = listOf(
            "1 崇德楼", "2 明志楼", "3 风华楼", "4 格物楼", "5 笃行楼",
            "6 致广楼", "7 博学楼", "8 弘毅楼", "9 匠心楼", "10 精工楼",
            "11 明志报告厅", "12 星垂湖", "13 韶光园", "14 青荇园", "15 思齐广场"
        ).joinToString("\n")
        AlertDialog.Builder(ctx, R.style.Theme_CampusAr_Dialog)
            .setTitle("图例")
            .setMessage("$body\n\n底图与编号来自学院官方导览图")
            .setPositiveButton("知道了", null)
            .show()
    }

    /** 缩放按钮：以视图中心为锚，CampusMapView 内已 clamp 到合理范围。 */
    private fun zoomButton(label: String, onTap: () -> Unit): TextView =
        textView(ctx, label, 18f, ctx.color(R.color.accent_ink), bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(ctx, ctx.color(R.color.bg_card), 18)
            setOnClickListener { onTap() }
        }

    /** 无障碍：地图当前底图 + 目的地 + 路线长度，随状态更新。 */
    private fun updateA11y() {
        val kindText = if (baseKind == "campus_guide") "导览图" else "卫星图"
        val dest = destName?.let { "，已选目的地：$it" } ?: ""
        val dist = path?.let { "，路线 ${it.distanceMeters} 米" } ?: ""
        map.contentDescription = "校园地图，当前显示：$kindText$dest$dist"
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
        updateA11y()
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
        updateA11y()
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
        acts.addView(button(ctx, "外部地图", ctx.color(R.color.bg_card), ctx.color(R.color.text_primary)).apply {
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
            .setItems(arrayOf("罗盘自检（AR 用）", "楼栋取点校准", "关于坐标", "路线说明")) { _, which ->
                when (which) {
                    0 -> ctx.startActivity(Intent(ctx, CompassTestActivity::class.java))
                    1 -> ctx.startActivity(Intent(ctx, CalibrateActivity::class.java))
                    2 -> AlertDialog.Builder(ctx, R.style.Theme_CampusAr_Dialog)
                        .setTitle("关于坐标")
                        .setMessage(
                            "· GPS 已自动换算：手机给的是 WGS-84，App 内部统一成 GCJ-02 再画蓝点和算距离。\n" +
                                "· 蓝点不会偏：它和楼栋、底图用的是同一套坐标，误差只剩 GPS 本身的几米。\n" +
                                "· 旧的取点数据是换算前的口径，会偏约 560 米；可在「设置 → 地图与定位」一键清理后重新取点。"
                        )
                        .setPositiveButton("知道了", null)
                        .show()
                    3 -> AlertDialog.Builder(ctx, R.style.Theme_CampusAr_Dialog)
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
            ctx.toast("没有找到可用的地图应用")
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

/** 指北针：图北朝上，不随地图旋转（官方底图都是北朝上）。 */
private class CompassView(ctx: Context) : View(ctx) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1).toFloat()
        color = context.color(R.color.text_secondary)
    }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.color(R.color.accent)
    }
    private val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.color(R.color.accent_ink)
        textSize = context.dp(9).toFloat()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) - context.dp(2)
        canvas.drawCircle(cx, cy, r, ring)
        canvas.drawText("N", cx, cy - r * 0.34f, letter)
        val tipY = cy - r * 0.12f
        val tailY = cy + r * 0.52f
        canvas.drawPath(
            Path().apply {
                moveTo(cx, tipY)
                lineTo(cx - r * 0.2f, tailY)
                lineTo(cx, tailY - r * 0.18f)
                lineTo(cx + r * 0.2f, tailY)
                close()
            },
            arrow
        )
    }
}