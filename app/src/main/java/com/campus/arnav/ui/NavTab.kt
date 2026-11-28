package com.campus.arnav.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
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
import com.campus.arnav.core.CampusRoads
import com.campus.arnav.core.CampusVector
import com.campus.arnav.core.RoadMerge
import com.campus.arnav.core.TrackRecorder
import com.campus.arnav.core.CampusPath
import com.campus.arnav.core.Fix
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

    private val map = VectorMapView(ctx)
    private val routeHolder = LinearLayout(ctx)
    private val listBox = LinearLayout(ctx)
    private val query = editText(ctx, "搜楼名：明志楼、风华楼、精工楼…")

    /** 起点卡片的值（「我的位置」/「南门」）。 */
    private val startValue = textView(ctx, "南门", 14.5f, ctx.color(R.color.text_primary))

    /** 终点卡片的值（选好目的地后显示楼名）。 */
    private val endValue = textView(ctx, "点下方楼栋，或搜一个目的地", 14.5f, ctx.color(R.color.text_hint))

    /** 地图卡上的悬浮进度条：左侧剩余距离 + 右侧「跟随 / AR / 外部地图」。 */
    private var progressBar: View? = null
    private var guideText: TextView? = null
    /** 路线步骤是否展开（默认只显示 4 步）。 */
    private var stepsExpanded = false

    private var keyword = ""
    private var destName: String? = null
    private var path: CampusPath? = null

    /** 实时测距：用上一次定位的「原始剩余距离 + 速度」做 0.5 秒插值，数字连续下降而不是一跳一跳。 */
    private var guideRawRemain = -1.0
    private var guideShownRemain = -1.0
    private var guideSpeedMps = 0.0
    private var guideLastAt = 0L
    private var guideOffRoute = 0.0
    private var guideArrived = false
    private val guideHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val guideTicker = object : Runnable {
        override fun run() {
            if (destName == null || routePoints.size < 2 || guideRawRemain < 0) return
            val dt = ((System.currentTimeMillis() - guideLastAt).coerceAtLeast(0L)) / 1000.0
            // 只有「在动」时才推算（速度 <0.4 m/s 视为站着，避免数字乱跳）
            val predicted = if (guideSpeedMps >= 0.4) {
                (guideRawRemain - guideSpeedMps * dt).coerceAtLeast(0.0)
            } else guideRawRemain
            guideShownRemain = if (guideShownRemain < 0) predicted
            else guideShownRemain + (predicted - guideShownRemain) * 0.45
            renderGuide()
            guideHandler.postDelayed(this, 400L)
        }
    }

    /** 卫星实景底图只解一次，失败就保持纯矢量模式。 */
    private var baseTried = false
    private fun ensureBaseMap() {
        if (baseTried) return
        baseTried = true
        try {
            val opts = android.graphics.BitmapFactory.Options()
            opts.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            val bmp = android.graphics.BitmapFactory.decodeResource(resources, R.drawable.campus_base, opts)
            map.setBase(bmp)
        } catch (e: Throwable) {
            map.setBase(null)
        }
    }

    /** 起点模式：true = 我的位置（有定位才生效），false = 南门。 */
    private var fromMe = false
    private var routePoints: List<Pair<Double, Double>> = emptyList()

    private var following = false

    private var fixLat: Double = RouteRepository.gate.lat
    private var fixLon: Double = RouteRepository.gate.lon
    private var fixBearing: Float = -1f
    private var haveFix = false

    /** 测绘状态（「未在测绘」/「测绘中 · 已存 N 条」），必须在 init 之前初始化。 */
    private var surveyStatus: TextView = textView(ctx, "未在测绘", 11.5f, ctx.color(R.color.text_hint))

    /** 轨迹条数 chip。 */
    private var surveyChip: TextView? = null

    init {
        orientation = VERTICAL
        // 不铺纯色底，让 MainShell 的 #FFF8FB→#EFF7FE 渐变透出来
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(ctx.dp(16), ctx.dp(10), ctx.dp(16), ctx.dp(8))

        // ---- 顶部栏：装饰字体标题 + 两个 34dp 圆形按钮 ----
        val head = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(ctx).apply { orientation = VERTICAL }
        titleCol.addView(titleText(ctx, "校园导航"))
        titleCol.addView(textView(ctx, "卫星影像底图 · 沿校内道路步行导航", 11.5f, ctx.color(R.color.text_hint)))
        head.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(circleText("图例", "查看图例") { showLegend() }, LinearLayout.LayoutParams(ctx.dp(34), ctx.dp(34)).apply {
            rightMargin = ctx.dp(8)
        })
        head.addView(circleText("更多", "更多导航工具") { showMore() }, LinearLayout.LayoutParams(ctx.dp(34), ctx.dp(34)))
        addView(head)

        // ---- 实测测绘卡 ----
        addView(surveyCard(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dp(10)
        })

        // ---- 地图卡：白底 20dp 圆角 + 1dp 描边 + 2dp 阴影 + 4dp 内边距，地图内容裁圆角 ----
        val mapCard = card(ctx, 4)
        val mapFrame = FrameLayout(ctx).apply {
            background = rounded(ctx, ctx.color(R.color.bg_card), 16)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, ctx.dp(16).toFloat())
                }
            }
        }
        mapFrame.addView(map, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val compass = CompassView(ctx).apply {
            isClickable = true
            contentDescription = "回到整张校园图"
            setOnClickListener {
                map.fitAll()
                ctx.toast("已回到整张校园图；点「＋」可放大")
            }
        }
        mapFrame.addView(compass, FrameLayout.LayoutParams(ctx.dp(46), ctx.dp(46)).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = ctx.dp(8)
            bottomMargin = ctx.dp(58)
        })

        val zoomCol = LinearLayout(ctx).apply { orientation = VERTICAL }
        zoomCol.addView(circleText("＋", "放大") { map.zoomBy(1.4f) }, LinearLayout.LayoutParams(ctx.dp(34), ctx.dp(34)))
        zoomCol.addView(circleText("－", "缩小") { map.zoomBy(0.7f) }, LinearLayout.LayoutParams(ctx.dp(34), ctx.dp(34)).apply {
            topMargin = ctx.dp(6)
        })
        mapFrame.addView(zoomCol, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            rightMargin = ctx.dp(8)
        })

        // ---- 悬浮进度条：距底 10dp、左右 10dp、高 40dp、白底 90%、圆角 12dp ----
        val bar = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(12), 0, ctx.dp(6), 0)
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(12).toFloat()
                setColor(0xE6FFFFFF.toInt())
                setStroke(ctx.dp(1), ctx.color(R.color.border))
            }
            visibility = View.GONE
        }
        guideText = textView(ctx, "", 15f, ctx.color(R.color.accent_ink), bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        guideText?.let { bar.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        bar.addView(actionCircle("跟随", "跟随定位") { startFollow() }, LinearLayout.LayoutParams(ctx.dp(28), ctx.dp(28)).apply {
            leftMargin = ctx.dp(4)
        })
        bar.addView(actionCircle("AR", "AR 实景") { destName?.let { openAr(it) } }, LinearLayout.LayoutParams(ctx.dp(28), ctx.dp(28)).apply {
            leftMargin = ctx.dp(6)
        })
        bar.addView(actionCircle("外部", "外部地图") {
            destName?.let { name -> findBuilding(name)?.let { b -> openAmap(b.lon, b.lat, b.name, true) } }
        }, LinearLayout.LayoutParams(ctx.dp(28), ctx.dp(28)).apply {
            leftMargin = ctx.dp(6)
        })
        progressBar = bar
        mapFrame.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(40)).apply {
            gravity = Gravity.BOTTOM
            leftMargin = ctx.dp(10)
            rightMargin = ctx.dp(10)
            bottomMargin = ctx.dp(10)
        })

        mapCard.addView(mapFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(300)))
        // 底图是竖版（约 729m × 984m），按底图宽高比调高地图卡，最多占屏高 40%。
        mapFrame.post {
            val w = mapFrame.width
            if (w > 0) {
                val ratio = if (map.baseAspect > 0.2f) map.baseAspect else 1.35f
                val maxH = (resources.displayMetrics.heightPixels * 0.40f).toInt()
                val h = (w * ratio).toInt().coerceIn(ctx.dp(220), maxH)
                val lp = mapFrame.layoutParams as LinearLayout.LayoutParams
                if (lp.height != h) {
                    lp.height = h
                    mapFrame.layoutParams = lp
                }
            }
        }
        addView(mapCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dp(10)
        })
        map.setOnBuildingTap { shape -> planFuzzy(shape.name) }

        // 底图脚注（比例尺说明已并入悬浮条）
        addView(textView(ctx, "卫星影像底图 · 路线沿校内道路 · 以现场为准", 10.5f, ctx.color(R.color.text_hint)).apply {
            setPadding(ctx.dp(2), ctx.dp(6), 0, 0)
        })

        // ---- 起点 / 终点：两行卡片式输入 ----
        addView(inputRow("起点", startValue, ghostSmall("切换起点") { toggleStart() }), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dp(10)
        })
        addView(inputRow("终点", endValue, primaryPill("去这里") { goEnd() }), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dp(8)
        })

        // ---- 定位 / 清除（小号 ghost 按钮，保持原入口） ----
        val tools = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tools.addView(ghostSmall("定位我的位置") { locate() }, LinearLayout.LayoutParams(0, ctx.dp(32), 1f))
        tools.addView(ghostSmall("清除路线") { clearRoute() }, LinearLayout.LayoutParams(0, ctx.dp(32), 1f).apply {
            leftMargin = ctx.dp(8)
        })
        addView(tools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dp(8)
        })

        // ---- 路线卡（无路线时隐藏） ----
        routeHolder.orientation = VERTICAL
        routeHolder.visibility = View.GONE
        addView(routeHolder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dp(10)
        })

        // ---- 搜索框：白底 999 圆角 ----
        query.background = GradientDrawable().apply {
            cornerRadius = ctx.dp(999).toFloat()
            setColor(ctx.color(R.color.bg_card))
            setStroke(ctx.dp(1), ctx.color(R.color.border))
        }
        query.setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
        addView(query, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dp(10)
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
        listBox.setPadding(0, ctx.dp(8), 0, ctx.dp(8))
        scroll.addView(listBox)
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        CampusRoads.load(ctx)
        map.setVector(CampusVector.load(ctx))
        map.setRoads(CampusRoads.segments(ctx))
        ensureBaseMap()
        updateExtraPaths()
        renderSurveyState()
        updateA11y()

        render()
    }

    // ---------------- 底图 / 图例 / 缩放 / 无障碍 ----------------

    // ---------------- 实测测绘 ----------------

    /** 「实地测绘」卡：标题 + 状态 + 两个 34dp 胶囊按钮 + 轨迹 chip。 */
    private fun surveyCard(): LinearLayout {
        val box = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(18).toFloat()
                setColor(ctx.color(R.color.bg_card))
                setStroke(ctx.dp(1), ctx.color(R.color.border))
            }
            elevation = ctx.dp(2).toFloat()
            clipToPadding = false
        }
        val head = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(sectionTitle(ctx, "实地测绘"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(surveyStatus)
        box.addView(head)

        surveyChip = chip(ctx, "轨迹 0 条", true).apply { visibility = View.GONE }
        surveyChip?.let {
            box.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ctx.dp(6)
            })
        }

        val acts = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            setPadding(0, ctx.dp(8), 0, 0)
        }
        acts.addView(styledBtn("开始测绘", true) { surveyStart() }.apply { tag = "surveyStart" }, LinearLayout.LayoutParams(0, ctx.dp(34), 1f))
        acts.addView(styledBtn("结束并并入", false) { surveyStop() }.apply { tag = "surveyStop" }, LinearLayout.LayoutParams(0, ctx.dp(34), 1f).apply {
            leftMargin = ctx.dp(8)
        })
        box.addView(acts)
        return box
    }

    init {
        // 首次进入就按「未测绘」上态（避免两个按钮都像可点、又显示「轨迹 0 条」）
        post { renderSurveyState() }
    }

    private fun surveyStart() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locate()
            return
        }
        TrackRecorder.start(ctx)
        renderSurveyState()
        ctx.toast("测绘中：沿着路走一圈，走完点「结束并并入」")
    }

    private fun surveyStop() {
        if (!TrackRecorder.isRecording()) {
            ctx.toast("当前没有在测绘")
            return
        }
        val t = TrackRecorder.stop(ctx, "测绘 " + java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date())) ?: run { ctx.toast("没有记录到轨迹"); return }
        val r = RoadMerge.rebuild(ctx)
        updateExtraPaths()
        CampusRoads.reload(ctx)
        renderSurveyState()
        ctx.toast("已保存轨迹 ${t.pts.size} 个点 / ${t.meters} 米；并入路网：新增 ${r.addedEdges} 段")
    }

    private fun renderSurveyState() {
        val n = TrackRecorder.list(ctx).size
        val rec = TrackRecorder.isRecording()
        surveyStatus.text = if (rec) "测绘中 · 已存 $n 条" else "未在测绘"
        surveyStatus.setTextColor(ctx.color(if (rec) R.color.success else R.color.text_hint))
        surveyChip?.let { c ->
            if (n > 0) {
                c.text = "轨迹 $n 条"
                c.visibility = View.VISIBLE
            } else {
                c.visibility = View.GONE
            }
        }
        (findViewWithTag<View>("surveyStart") as? TextView)?.let { paintButton(it, true, !rec) }
        (findViewWithTag<View>("surveyStop") as? TextView)?.let { paintButton(it, false, rec) }
    }

    /** 把绘过的轨迹以浅色虚线画在地图上。 */
    private fun updateExtraPaths() {
        val paths = TrackRecorder.list(ctx).map { t -> t.pts.map { it.lat to it.lon } }
        map.setExtraPaths(if (paths.isEmpty()) null else paths)
    }

    /** 点楼牌时把名字对齐到 RouteRepository（矢量图里的名字带全称）。 */
    private fun planFuzzy(raw: String) {
        val key0 = raw.substringBefore("（").substringBefore(" ·").trim()
        // 官图上的叫法与路网里的名字不一致，先过一遍别名
        val aliases = mapOf(
            "图书馆" to "明志楼", "图文信息中心" to "明志楼", "图书信息中心" to "明志楼",
            "活动中心" to "风华楼", "大学生活动中心" to "风华楼", "风华" to "风华楼",
            "行政楼" to "崇德楼", "大门" to "南门", "正门" to "南门", "主校门" to "南门",
            "一食堂" to "第一食堂", "二食堂" to "第二食堂", "食堂" to "第一食堂",
            "操场" to "田径场", "运动场" to "田径场", "体育场" to "田径场",
            "实训中心" to "工程训练中心", "门诊部" to "校医院", "超市" to "商业街"
        )
        val key = aliases[key0] ?: key0
        val all = RouteRepository.buildings(ctx)
        val hit = all.firstOrNull { it.name == raw }
            ?: all.firstOrNull { it.name == key }
            ?: all.firstOrNull { it.name.contains(key) || key.contains(it.name) }
            ?: all.firstOrNull { key.isNotEmpty() && (it.name.contains(key.take(2))) }
        if (hit != null) plan(hit.name) else ctx.toast("「$raw」暂时算不了路线")
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

    /** 无障碍：地图当前底图 + 目的地 + 路线长度，随状态更新。 */
    private fun updateA11y() {
        val kindText = "自绘平面图"
        val dest = destName?.let { "，已选目的地：$it" } ?: ""
        val dist = path?.let { "，路线 ${it.distanceMeters} 米" } ?: ""
        map.contentDescription = "校园地图，当前显示：$kindText$dest$dist"
    }

    fun refresh() {
        CampusRoads.load(ctx)
        map.setVector(CampusVector.load(ctx))
                map.setRoads(CampusRoads.segments(ctx))
        ensureBaseMap()
        updateExtraPaths()
        // setVector 会重建底图缓存，这里把已算路线/目的地/蓝点补回去（否则切 Tab 回来线就没了）
        path?.let { p -> map.setRoute(p.points) }
        destName?.let { name ->
            RouteRepository.buildings(ctx).firstOrNull { it.name == name }?.let { b ->
                map.setDestination(b.lat, b.lon, b.name)
            }
        }
        if (haveFix) map.setMyLocation(fixLat, fixLon, fixBearing)
        renderSurveyState()
        render()
    }

    // ---------------- 交互 ----------------

    private fun toggleStart() {
        fromMe = !fromMe
        if (fromMe) locate() else {
            fixLat = RouteRepository.gate.lat
            fixLon = RouteRepository.gate.lon
            haveFix = false
            setStart("南门")
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
            ctx.toast("已请求定位权限，允许后会继续定位")
            return
        }
        UserLocator.start(ctx) { fix -> onFix(fix) }
    }

    /** 定位权限对话框返回后由 MainShellActivity 转发过来。 */
    fun onLocatePermission(granted: Boolean) {
        if (granted) locate() else ctx.toast("没有定位权限：可以先用「起点：南门」导航")
    }

    private fun onFix(fix: Fix) {
        val moved = haveFix &&
            GeoUtils.distanceMeters(fixLat, fixLon, fix.lat, fix.lon) > 8.0
        fixLat = fix.lat
        fixLon = fix.lon
        fixBearing = fix.bearing ?: -1f
        haveFix = !UserLocator.isNoFix(fix)
        map.setMyLocation(fix.lat, fix.lon, fixBearing)
        if (!haveFix) {
            ctx.toast("暂时没有定位，先用南门当起点")
            return
        }
        fromMe = true
        setStart("我的位置")
        val dest = destName ?: return
        updateGuideFrom(fix.lat, fix.lon, fix.speedMps)
        // 跟随模式：地图跟着蓝点走，不重算路线（以前每个定位点都重排页面）
        if (following) {
            map.zoomTo(fix.lat, fix.lon, map.currentScale())
            return
        }
        // 起点变了才更新路线，且走出 15 米以上才重算，重算时不再重置视角
        if (moved) plan(dest, refit = false)
    }

    /** 用当前定位更新「剩余距离 · 下一步」。speedMps 来自 GPS，用于 0.5 秒插值。 */
    private fun updateGuideFrom(lat: Double, lon: Double, speedMps: Float? = null) {
        val pts = routePoints
        if (pts.size < 2) return
        var bestIdx = 0
        var bestD = Double.MAX_VALUE
        for (i in pts.indices) {
            val dd = GeoUtils.distanceMeters(lat, lon, pts[i].first, pts[i].second)
            if (dd < bestD) {
                bestD = dd
                bestIdx = i
            }
        }
        var remain = 0.0
        for (i in bestIdx until pts.size - 1) {
            remain += GeoUtils.distanceMeters(pts[i].first, pts[i].second, pts[i + 1].first, pts[i + 1].second)
        }
        guideOffRoute = bestD
        // GPS 速度平滑：>8 m/s 视为骑行/车载噪音，按走路上限处理；走停之间平滑衰减
        val s = speedMps?.toDouble() ?: -1.0
        guideSpeedMps = when {
            s >= 0.2 -> if (guideSpeedMps <= 0.0) s.coerceAtMost(3.0) else guideSpeedMps * 0.6 + s * 0.4
            else -> guideSpeedMps * 0.5
        }.coerceIn(0.0, 3.0)
        if (guideSpeedMps < 0.4) guideSpeedMps = 0.0
        guideRawRemain = remain
        if (guideShownRemain < 0 || remain > guideShownRemain + 40) guideShownRemain = remain
        guideLastAt = System.currentTimeMillis()
        guideArrived = remain < 12
        renderGuide()
        guideHandler.removeCallbacks(guideTicker)
        if (!guideArrived) guideHandler.postDelayed(guideTicker, 400L)
    }

    /** 把当前（可能是插值出来的）剩余距离画到进度条上：细化到 5 米，避免数字蹦跳。 */
    private fun renderGuide() {
        val gt = guideText ?: return
        val dest = destName ?: return
        if (guideRawRemain < 0) return
        val remain = if (guideShownRemain < 0) guideRawRemain else guideShownRemain
        // 近处按 1 米跳（精细），远处按 5 米跳（不闪）
        val step = if (remain < 50.0) 1.0 else 5.0
        val shown = (Math.round(remain / step) * step).toInt().coerceAtLeast(0)
        val min = Math.ceil(remain / 80.0).toInt().coerceAtLeast(1)
        gt.text = when {
            remain < 12 -> "已到达 $dest"
            guideOffRoute > 60 -> "偏离路线约 ${guideOffRoute.toInt()} 米 · 剩余 $shown 米到 $dest"
            else -> "剩余 $shown 米 · 约 $min 分钟到 $dest"
        }
    }

    private fun stopGuideTicker() {
        guideHandler.removeCallbacks(guideTicker)
        guideRawRemain = -1.0
        guideShownRemain = -1.0
        guideSpeedMps = 0.0
        guideArrived = false
    }

    private fun clearRoute() {
        destName = null
        path = null
        following = false
        UserLocator.stop()
        stopGuideTicker()
        fromMe = false
        haveFix = false
        fixBearing = -1f
        fixLat = RouteRepository.gate.lat
        fixLon = RouteRepository.gate.lon
        setStart("南门")
        endValue.text = "点下方楼栋，或搜一个目的地"
        endValue.setTextColor(ctx.color(R.color.text_hint))
        stepsExpanded = false
        showProgress(false)
        guideText?.text = ""
        map.setMyLocation(null, null, null)
        map.setRoute(emptyList())
        map.setDestination(null, null, null)
        map.fitAll()
        routeHolder.removeAllViews()
        routeHolder.visibility = View.GONE
        updateA11y()
    }

    private fun plan(name: String, refit: Boolean = true) {
        if (!CampusRoads.load(ctx)) {
            ctx.toast("路网数据没读到，稍后再试")
            return
        }
        var fellBack = false
        val p = if (fromMe && haveFix) {
            val r = CampusRoads.routeFromPoint(ctx, fixLat, fixLon, name)
            if (r == null) {
                fellBack = true
                CampusRoads.route(ctx, "南门", name)
            } else r
        } else {
            CampusRoads.route(ctx, "南门", name)
        }
        if (p == null) {
            ctx.toast("没能算出到「$name」的路线")
            return
        }
        if (fellBack) {
            fromMe = false
            haveFix = false
            setStart("南门")
            ctx.toast("定位点离校园路网太远，已改从南门算起")
        }
        destName = name
        path = p
        val target = RouteRepository.buildings(ctx).firstOrNull { it.name == name }
        if (target != null) map.setDestination(target.lat, target.lon, target.name) else map.setDestination(null, null, null)
        routePoints = p.points
        map.setRoute(p.points)
        if (refit) map.fitRoute(p.points)
        if (fromMe && haveFix) map.setMyLocation(fixLat, fixLon, fixBearing)
        renderRoute(p, name)
        updateA11y()
    }

    private fun startFollow() {
        val dest = destName ?: return
        following = true
        map.zoomTo(fixLat, fixLon, 2.0f)
        locate()
        ctx.toast("跟随已开：蓝点会带着地图走，去 $dest")
    }

    // ---------------- 渲染 ----------------

    private fun renderRoute(p: CampusPath, name: String) {
        routeHolder.removeAllViews()
        val wrap = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(20).toFloat()
                setColor(ctx.color(R.color.bg_card))
                setStroke(ctx.dp(1), ctx.color(R.color.border))
            }
            elevation = ctx.dp(2).toFloat()
        }
        wrap.addView(sectionTitle(ctx, "路线"))
        wrap.addView(textView(ctx, "去 $name · ${p.distanceMeters} 米 · 约 ${(p.distanceMeters / 80).coerceAtLeast(1)} 分钟", 12.5f, ctx.color(R.color.text_secondary)).apply {
            setPadding(0, ctx.dp(2), 0, ctx.dp(4))
        })

        val shown = if (stepsExpanded || p.steps.size <= 4) p.steps else p.steps.take(4)
        for ((i, s) in shown.withIndex()) {
            if (i > 0) wrap.addView(divider(ctx))
            wrap.addView(stepRow(i + 1, s))
        }
        if (p.steps.size > 4) {
            wrap.addView(textView(ctx, if (stepsExpanded) "收起（共 ${p.steps.size} 步）" else "还有 ${p.steps.size - 4} 步 · 展开", 12f, ctx.color(R.color.accent_ink)).apply {
                setPadding(0, ctx.dp(10), 0, 0)
                setOnClickListener {
                    stepsExpanded = !stepsExpanded
                    renderRoute(p, name)
                }
            })
        }

        endValue.text = name
        endValue.setTextColor(ctx.color(R.color.text_primary))
        showProgress(true)
        if (haveFix) {
            updateGuideFrom(fixLat, fixLon)
        } else {
            guideText?.text = "剩余 ${p.distanceMeters} 米 · 约 ${(p.distanceMeters / 80).coerceAtLeast(1)} 分钟"
        }
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
            listBox.addView(textView(ctx, "没有匹配的楼栋。", 13f, ctx.color(R.color.text_secondary)).apply {
                setPadding(ctx.dp(6), ctx.dp(12), 0, 0)
            })
            return
        }
        val gate = RouteRepository.gate
        val cardBox = LinearLayout(ctx).apply {
            orientation = VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(20).toFloat()
                setColor(ctx.color(R.color.bg_card))
                setStroke(ctx.dp(1), ctx.color(R.color.border))
            }
            elevation = ctx.dp(2).toFloat()
        }
        for ((i, b) in list.withIndex()) {
            if (i > 0) cardBox.addView(divider(ctx))
            val d = GeoUtils.distanceMeters(gate.lat, gate.lon, b.lat, b.lon).toInt()
            val min = (d / 80.0).coerceAtLeast(1.0).toInt()
            val row = LinearLayout(ctx).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
            }
            row.addView(dot(), LinearLayout.LayoutParams(ctx.dp(8), ctx.dp(8)).apply { rightMargin = ctx.dp(10) })
            row.addView(textView(ctx, b.name, 14.5f, ctx.color(R.color.text_primary)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(textView(ctx, "距南门 $d 米 · 约 $min 分钟", 12f, ctx.color(R.color.text_secondary)))
            row.setOnClickListener { plan(b.name) }
            cardBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(56)))
        }
        listBox.addView(cardBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
                            "· 底图是高德卫星影像（GCJ-02，与楼栋/路网同一套坐标），楼与路的相对位置按实景对齐。\n" +
                                "· 路线由校内路网 A* 算出（745 个路网节点 / 4443 段连通，主干道 + 步道 + 可穿行广场），距离是沿路累加，不是直线。\n" +
                                "· 走到楼附近以门牌和路牌为准；AR 实景仅作最后 30 米参考。"
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
        stopGuideTicker()
        super.onDetachedFromWindow()
    }

    /** 保留给外部：两点直线距离（调试用）。 */
    fun straightMeters(name: String): Int {
        val b = findBuilding(name) ?: return 0
        return GeoUtils.distanceMeters(RouteRepository.gate.lat, RouteRepository.gate.lon, b.lat, b.lon).toInt()
    }

    // ---------------- 视图小件（只做样式，不碰地图与业务） ----------------

    /** 起点/终点卡片式输入行：左 label + 值，右侧动作。 */
    private fun inputRow(label: String, value: TextView, action: View): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(10), ctx.dp(8))
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(18).toFloat()
                setColor(ctx.color(R.color.bg_card))
                setStroke(ctx.dp(1), ctx.color(R.color.border))
            }
        }
        val col = LinearLayout(ctx).apply { orientation = VERTICAL }
        col.addView(textView(ctx, label, 11.5f, ctx.color(R.color.text_hint)))
        col.addView(value)
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(action, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.dp(34)).apply {
            leftMargin = ctx.dp(8)
        })
        return row
    }

    /** 34dp 圆形文字按钮（顶部栏 / 缩放）。 */
    private fun circleText(text: String, note: String, onTap: () -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 12f
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(ctx.color(R.color.text_primary))
        contentDescription = note
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ctx.color(R.color.bg_card))
            setStroke(ctx.dp(1), ctx.color(R.color.border))
        }
        setOnClickListener { onTap() }
    }

    /** 悬浮条上的 28dp 圆形动作按钮（跟随 / AR / 外部）。 */
    private fun actionCircle(text: String, note: String, onTap: () -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 10.5f
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(ctx.color(R.color.accent_ink))
        contentDescription = note
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ctx.color(R.color.accent_soft))
        }
        setOnClickListener { onTap() }
    }

    /** 小号 ghost 胶囊（切换起点 / 定位 / 清除）。 */
    private fun ghostSmall(text: String, onTap: () -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 12f
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(ctx.color(R.color.accent_ink))
        background = GradientDrawable().apply {
            cornerRadius = ctx.dp(999).toFloat()
            setColor(ctx.color(R.color.bg_card))
            setStroke(ctx.dp(1), ctx.color(R.color.border))
        }
        setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
        setOnClickListener { onTap() }
    }

    /** 主胶囊按钮（去这里 / 开始测绘）。 */
    private fun primaryPill(text: String, onTap: () -> Unit): TextView = styledBtn(text, true, onTap)

    /** 胶囊按钮：主按钮粉底白字，次按钮白底深粉字；禁用改灰底灰字。 */
    private fun styledBtn(text: String, primary: Boolean, onTap: () -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 13.5f
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTypeface(typeface, Typeface.BOLD)
        setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
        paintButton(this, primary, true)
        setOnClickListener { onTap() }
    }

    private fun paintButton(v: TextView, primary: Boolean, enabled: Boolean) {
        v.isEnabled = enabled
        v.isClickable = enabled
        v.setTextColor(
            when {
                !enabled -> ctx.color(R.color.text_hint)
                primary -> Color.WHITE
                else -> ctx.color(R.color.accent_ink)
            }
        )
        v.background = GradientDrawable().apply {
            cornerRadius = ctx.dp(999).toFloat()
            when {
                !enabled -> {
                    setColor(ctx.color(R.color.border))
                    setStroke(ctx.dp(1), ctx.color(R.color.border))
                }
                primary -> {
                    setColor(ctx.color(R.color.accent))
                    setStroke(ctx.dp(1), ctx.color(R.color.accent))
                }
                else -> {
                    setColor(ctx.color(R.color.bg_card))
                    setStroke(ctx.dp(1), ctx.color(R.color.border))
                }
            }
        }
    }

    /** 路线步骤行：22dp 圆形序号 + 13.5sp 文案。 */
    private fun stepRow(n: Int, text: String): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ctx.dp(10), 0, ctx.dp(10))
        }
        val idx = TextView(ctx).apply {
            this.text = "$n"
            textSize = 11f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(ctx.color(R.color.accent_ink))
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ctx.color(R.color.accent_soft))
            }
        }
        row.addView(idx, LinearLayout.LayoutParams(ctx.dp(22), ctx.dp(22)).apply { rightMargin = ctx.dp(10) })
        row.addView(textView(ctx, text, 13.5f, ctx.color(R.color.text_primary)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    /** 楼栋列表左侧 8dp 圆点。 */
    private fun dot(): View = View(ctx).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ctx.color(R.color.accent))
        }
    }

    private fun showProgress(on: Boolean) {
        progressBar?.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun setStart(label: String) {
        startValue.text = label
        startValue.setTextColor(ctx.color(R.color.text_primary))
    }

    /** 终点行的「去这里」：没选目的地先提示。 */
    private fun goEnd() {
        val d = destName
        if (d == null) ctx.toast("先点下面的楼栋，或搜索一个目的地") else plan(d)
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