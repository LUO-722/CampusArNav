package com.campus.arnav.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.campus.arnav.R
import com.campus.arnav.core.CampusVector
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 纯 Canvas 自绘的校园 2D 平面图：不加载任何位图，建筑/水面/绿地/道路全部由 [CampusVector]
 * 的经纬度数据画出来，配合 Ins 粉蓝配色。
 *
 * 坐标链路：经纬度 → 以校园中心为原点的「米」坐标（等距圆柱近似）→ 屏幕像素
 * （`screen = world * scale + offset`）。绘制世界坐标时用 canvas 的 translate/scale，
 * 道路线宽要按米换算，文字则在屏幕坐标里单独画，保证字号不随缩放变化。
 *
 * onDraw 零分配：所有 Path、Paint、RectF、FloatArray 都是字段并在数据/缩放变化时重建。
 */
class VectorMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private companion object {
        const val MIN_ZOOM = 0.6f
        const val MAX_ZOOM = 5f
        /** 每度纬度对应的米（中国纬度下足够用）。 */
        const val M_PER_DEG_LAT = 111132.0
        /** 赤道处每度经度对应的米。 */
        const val M_PER_DEG_LON = 111320.0
        /** 低于该缩放只画目的地附近的楼名，避免糊成一片。 */
        const val LABEL_ZOOM = 1.2f
        /** 目的地楼名可见半径（米）。 */
        const val DEST_LABEL_RADIUS_M = 150f
        /** 天气般透明的矢量图空态提示。 */
        const val EMPTY_HINT = "暂无矢量数据"
    }

    // ------------------------------------------------------------------ 数据

    private var vec: CampusVector? = null

    /** 世界坐标系原点（校园中心）与尺寸，单位米。 */
    private var lat0 = 0.0
    private var lon0 = 0.0
    private var worldW = 1f
    private var worldH = 1f
    private var mPerLon = 1.0

    // 建筑：Path 用世界米坐标，绘制时靠 canvas 变换
    private var bPaths = arrayOfNulls<Path>(0)
    private var bFill = IntArray(0)
    private var bCx = FloatArray(0)
    private var bCy = FloatArray(0)
    private var bR = FloatArray(0)

    // 水面 / 绿地
    private var waterPaths = arrayOfNulls<Path>(0)
    private var greenPaths = arrayOfNulls<Path>(0)
    private var greenColors = IntArray(0)

    // 道路
    private var roadPaths = arrayOfNulls<Path>(0)
    private var roadWidth = IntArray(0)

    // 文字标注（楼名/片区名）
    private var labelTexts = arrayOfNulls<String>(0)
    private var labelX = FloatArray(0)
    private var labelY = FloatArray(0)

    // 路线 / 测绘轨迹（世界米坐标）
    private var routePath: Path? = null
    private var trackPath: Path? = null

    // 目的地 / 我的位置
    private var destLat = Double.NaN
    private var destLon = Double.NaN
    private var destName: String? = null
    private var myLat = Double.NaN
    private var myLon = Double.NaN
    private var myBearing = Float.NaN

    // ------------------------------------------------------------------ 视图变换

    /** 整幅适配时的「像素 / 米」。 */
    private var fitScale = 1f

    /** 当前「像素 / 米」。 */
    private var scale = 1f

    /** 相对 fitAll 的缩放倍数（0.6–5）。 */
    private var zoom = 1f

    private var offX = 0f
    private var offY = 0f
    private var laidOut = false

    private var tapListener: ((CampusVector.Shape) -> Unit)? = null

    // ------------------------------------------------------------------ 画笔（全部常量字段）

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    /** 底色缓存，避免 onDraw 里反复查资源。 */
    private val bgColor = getColorSafe(R.color.bg_dark)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = getColorSafe(R.color.border)
    }
    private val waterFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = getColorSafe(R.color.accent_blue)
    }
    private val waterStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF4E9CC6.toInt()
    }
    private val greenFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val roadCasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val roadInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = getColorSafe(R.color.border)
    }
    private val routeCasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val routeCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = getColorSafe(R.color.accent)
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = getColorSafe(R.color.accent_blue)
    }
    private val destRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val destFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = getColorSafe(R.color.accent)
    }
    private val meFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = getColorSafe(R.color.blue_ink)
    }
    private val meRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val sectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = getColorSafe(R.color.accent_blue)
        alpha = 90
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = getColorSafe(R.color.text_primary)
        textAlign = Paint.Align.CENTER
        textSize = spPx(10f)
    }
    private val labelHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = spPx(10f)
        strokeWidth = dp(3f)
    }
    private val destLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = getColorSafe(R.color.accent_ink)
        textAlign = Paint.Align.CENTER
        textSize = spPx(11f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val destLabelHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = spPx(11f)
        strokeWidth = dp(3.5f)
    }
    private val compassBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xF2FFFFFF.toInt()
    }
    private val compassStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = getColorSafe(R.color.border)
    }
    private val compassArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = getColorSafe(R.color.accent)
    }
    private val compassTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = getColorSafe(R.color.accent_ink)
        textAlign = Paint.Align.CENTER
        textSize = spPx(9f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val scaleLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = getColorSafe(R.color.text_secondary)
        strokeWidth = dp(1.5f)
    }
    private val scaleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = getColorSafe(R.color.text_secondary)
        textAlign = Paint.Align.LEFT
        textSize = spPx(9f)
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = getColorSafe(R.color.text_secondary)
        textAlign = Paint.Align.CENTER
        textSize = spPx(12f)
    }

    // 复用对象（onDraw 不新建）
    private val sectorRect = RectF()
    private val compassArrow = Path()

    private val buildingKindColor = HashMap<String, Int>(8)
    private val plainGreenColor = 0xFFE8F5E9.toInt()

    // ------------------------------------------------------------------ 手势

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                applyZoom(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                offX -= distanceX
                offY -= distanceY
                clampOffsets()
                invalidate()
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val hit = hitTest(e.x, e.y)
                if (hit != null) {
                    tapListener?.invoke(hit)
                    return true
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                applyZoom(zoom * 1.6f, e.x, e.y)
                return true
            }
        }
    )

    init {
        isClickable = true
        setBackgroundColor(bgColor)
        contentDescription = "校园矢量地图"
        buildCompassArrow()
        buildingKindColor["teach"] = Color.parseColor("#FFE3EE")
        buildingKindColor["dorm"] = Color.parseColor("#F3BBDB")
        buildingKindColor["canteen"] = Color.parseColor("#FFC9A8")
        buildingKindColor["library"] = Color.parseColor("#C9BCEC")
        buildingKindColor["sport"] = Color.parseColor("#A9E3C9")
        buildingKindColor["admin"] = Color.parseColor("#9DCBF0")
        buildingKindColor["gate"] = Color.parseColor("#E8447A")
        buildingKindColor["other"] = Color.parseColor("#E7B7CE")
    }

    // ------------------------------------------------------------------ 公开 API

    /** 喂入矢量数据；null 表示数据未就绪（画占位提示）。 */
    fun setVector(v: CampusVector?) {
        vec = v
        if (v == null) {
            clearCaches()
            contentDescription = "校园矢量地图，数据未就绪"
            invalidate()
            return
        }
        lat0 = (v.latMin + v.latMax) / 2
        lon0 = (v.lonMin + v.lonMax) / 2
        mPerLon = M_PER_DEG_LON * cos(Math.toRadians(lat0))
        val latSpan = v.latMax - v.latMin
        val lonSpan = v.lonMax - v.lonMin
        worldW = (lonSpan * mPerLon).toFloat().coerceAtLeast(1f)
        worldH = (latSpan * M_PER_DEG_LAT).toFloat().coerceAtLeast(1f)

        buildBuildings(v)
        buildFaces(v)
        buildRoads(v)
        buildLabels(v)
        routePath = null
        trackPath = null
        contentDescription = "校园矢量地图，共 " + v.buildings.size + " 栋建筑"
        laidOut = false
        if (width > 0 && height > 0) onSizeChanged(width, height, 0, 0)
        invalidate()
    }

    /** 路线折线（lat, lon 顺序）。空列表表示清除。 */
    fun setRoute(pts: List<Pair<Double, Double>>) {
        routePath = if (pts.size < 2) null else polygonPath(pts, close = false)
        invalidate()
    }

    /** 蓝色定位点；lat/lon 传 null 隐藏，bearing 为 null 时不画朝向扇形。 */
    fun setMyLocation(lat: Double?, lon: Double?, bearingDeg: Float? = null) {
        myLat = lat ?: Double.NaN
        myLon = lon ?: Double.NaN
        myBearing = bearingDeg ?: Float.NaN
        if (!myLat.isNaN() && !myLon.isNaN()) {
            contentDescription = "校园矢量地图，已定位"
        }
        invalidate()
    }

    /** 目的地标记；lat/lon 传 null 隐藏。 */
    fun setDestination(lat: Double?, lon: Double?, name: String? = null) {
        destLat = lat ?: Double.NaN
        destLon = lon ?: Double.NaN
        destName = name
        invalidate()
    }

    /** 叠加测绘轨迹（可空，每条是独立折线）。 */
    fun setExtraPaths(paths: List<List<Pair<Double, Double>>>?) {
        if (paths == null || paths.isEmpty()) {
            trackPath = null
            invalidate()
            return
        }
        val p = Path()
        var any = false
        for (i in paths.indices) {
            val seg = paths[i]
            if (seg.size < 2) continue
            val x0 = worldX(seg[0].second)
            val y0 = worldY(seg[0].first)
            p.moveTo(x0, y0)
            for (j in 1 until seg.size) {
                p.lineTo(worldX(seg[j].second), worldY(seg[j].first))
            }
            any = true
        }
        trackPath = if (any) p else null
        invalidate()
    }

    fun setOnBuildingTap(cb: ((CampusVector.Shape) -> Unit)?) {
        tapListener = cb
    }

    /** 以给定经纬度为中心、指定缩放倍数（相对整幅）。 */
    fun zoomTo(lat: Double, lon: Double, scale: Float = 2.5f) {
        setZoomInternal(scale)
        val wx = worldX(lon)
        val wy = worldY(lat)
        offX = width / 2f - wx * this.scale
        offY = height / 2f - wy * this.scale
        clampOffsets()
        invalidate()
    }

    /** 整幅适配并居中。 */
    fun fitAll() {
        setZoomInternal(1f)
        offX = (width - worldW * scale) / 2f
        offY = (height - worldH * scale) / 2f
        clampOffsets()
        invalidate()
    }

    /** 当前缩放倍数（相对整幅，1.0 = fitAll）。 */
    fun currentScale(): Float = zoom

    /** 以视图中心为锚缩放；factor>1 放大。 */
    fun zoomBy(factor: Float) {
        if (width == 0 || height == 0) return
        applyZoom(zoom * factor, width / 2f, height / 2f)
    }

    // ------------------------------------------------------------------ 数据 → Path

    private fun clearCaches() {
        bPaths = arrayOfNulls(0)
        bFill = IntArray(0)
        bCx = FloatArray(0)
        bCy = FloatArray(0)
        bR = FloatArray(0)
        waterPaths = arrayOfNulls(0)
        greenPaths = arrayOfNulls(0)
        greenColors = IntArray(0)
        roadPaths = arrayOfNulls(0)
        roadWidth = IntArray(0)
        labelTexts = arrayOfNulls(0)
        labelX = FloatArray(0)
        labelY = FloatArray(0)
        routePath = null
        trackPath = null
    }

    private fun buildBuildings(v: CampusVector) {
        val n = v.buildings.size
        bPaths = arrayOfNulls(n)
        bFill = IntArray(n)
        bCx = FloatArray(n)
        bCy = FloatArray(n)
        bR = FloatArray(n)
        for (i in 0 until n) {
            val s = v.buildings[i]
            bPaths[i] = polygonPath(s.poly, close = true)
            bFill[i] = buildingKindColor[s.kind] ?: buildingKindColor["other"]!!
            var cx = 0.0
            var cy = 0.0
            var r2 = Float.MIN_VALUE
            for (p in s.poly) {
                val x = worldX(p.second)
                val y = worldY(p.first)
                cx += x
                cy += y
            }
            if (s.poly.isNotEmpty()) {
                cx /= s.poly.size
                cy /= s.poly.size
            }
            bCx[i] = cx.toFloat()
            bCy[i] = cy.toFloat()
            for (p in s.poly) {
                val dx = worldX(p.second) - bCx[i]
                val dy = worldY(p.first) - bCy[i]
                val d2 = dx * dx + dy * dy
                if (d2 > r2) r2 = d2
            }
            bR[i] = if (r2 > 0f) (sqrt(r2) + 6f) else 14f
        }
    }

    private fun buildFaces(v: CampusVector) {
        waterPaths = arrayOfNulls(v.water.size)
        for (i in v.water.indices) waterPaths[i] = polygonPath(v.water[i].poly, close = true)
        greenPaths = arrayOfNulls(v.greens.size)
        greenColors = IntArray(v.greens.size)
        for (i in v.greens.indices) {
            greenPaths[i] = polygonPath(v.greens[i].poly, close = true)
            greenColors[i] = plainGreenColor
        }
    }

    private fun buildRoads(v: CampusVector) {
        roadPaths = arrayOfNulls(v.roads.size)
        roadWidth = IntArray(v.roads.size)
        for (i in v.roads.indices) {
            val r = v.roads[i]
            roadPaths[i] = polygonPath(r.pts, close = false)
            roadWidth[i] = max(3, r.widthM)
        }
    }

    private fun buildLabels(v: CampusVector) {
        val hasData = v.labels.isNotEmpty()
        val n = if (hasData) v.labels.size else v.buildings.size
        labelTexts = arrayOfNulls(n)
        labelX = FloatArray(n)
        labelY = FloatArray(n)
        if (hasData) {
            for (i in 0 until n) {
                val l = v.labels[i]
                labelTexts[i] = l.text
                labelX[i] = worldX(l.lon)
                labelY[i] = worldY(l.lat)
            }
        } else {
            for (i in 0 until n) {
                labelTexts[i] = v.buildings[i].name
                labelX[i] = bCx[i]
                labelY[i] = bCy[i]
            }
        }
    }

    /** 经纬度列表 → 世界米坐标的 Path。 */
    private fun polygonPath(pts: List<Pair<Double, Double>>, close: Boolean): Path {
        val p = Path()
        if (pts.isEmpty()) return p
        p.moveTo(worldX(pts[0].second), worldY(pts[0].first))
        for (i in 1 until pts.size) {
            p.lineTo(worldX(pts[i].second), worldY(pts[i].first))
        }
        if (close && pts.size >= 3) p.close()
        return p
    }

    private fun worldX(lon: Double): Float = ((lon - lon0) * mPerLon).toFloat()
    private fun worldY(lat: Double): Float = ((lat0 - lat) * M_PER_DEG_LAT).toFloat()

    // ------------------------------------------------------------------ 变换 / 缩放

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        fitScale = min(w / worldW, h / worldH)
        if (!laidOut) {
            laidOut = true
            zoom = 1f
            scale = fitScale
            offX = (w - worldW * scale) / 2f
            offY = (h - worldH * scale) / 2f
        } else {
            scale = fitScale * zoom
            offX = (w - worldW * scale) / 2f
            offY = (h - worldH * scale) / 2f
        }
        clampOffsets()
        updateScaleDependent()
    }

    private fun setZoomInternal(z: Float) {
        zoom = z.coerceIn(MIN_ZOOM, MAX_ZOOM)
        scale = fitScale * zoom
        updateScaleDependent()
    }

    /** 以屏幕点 (fx, fy) 为锚按目标倍数缩放。 */
    private fun applyZoom(target: Float, fx: Float, fy: Float) {
        val z = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (z == zoom) return
        val k = z / zoom
        offX = fx + (offX - fx) * k
        offY = fy + (offY - fy) * k
        zoom = z
        scale = fitScale * zoom
        clampOffsets()
        updateScaleDependent()
        invalidate()
    }

    private fun clampOffsets() {
        val w = worldW * scale
        val h = worldH * scale
        offX = if (w <= width) (width - w) / 2f else offX.coerceIn(width - w, 0f)
        offY = if (h <= height) (height - h) / 2f else offY.coerceIn(height - h, 0f)
    }

    /** 线宽都是「米」，随缩放变化时统一刷新；同时刷新虚线参数（避免 onDraw 分配）。 */
    private fun updateScaleDependent() {
        if (scale <= 0f) return
        strokePaint.strokeWidth = dp(1f) / scale
        waterStrokePaint.strokeWidth = dp(1f) / scale
        roadCasePaint.strokeWidth = 0f // 每条路单独设，这里只是复位
        roadInnerPaint.strokeWidth = 0f
        routeCasePaint.strokeWidth = dp(14f) / scale
        routeCorePaint.strokeWidth = dp(8f) / scale
        trackPaint.strokeWidth = dp(2f) / scale
        val dash = dp(7f) / scale
        val gap = dp(5f) / scale
        trackPaint.pathEffect = DashPathEffect(floatArrayOf(dash, gap), 0f)
        // 文字字号固定（屏幕像素），不随缩放变化，无需刷新
    }

    // ------------------------------------------------------------------ 绘制

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(bgColor)

        val v = vec
        if (v == null) {
            canvas.drawText(EMPTY_HINT, width / 2f, height / 2f, emptyPaint)
            drawCompass(canvas)
            return
        }

        // 世界坐标层：translate + scale 后所有 Path 直接用米坐标
        val save = canvas.save()
        canvas.translate(offX, offY)
        canvas.scale(scale, scale)
        drawGreens(canvas)
        drawWater(canvas)
        drawRoads(canvas)
        drawBuildings(canvas)
        drawRoute(canvas)
        drawTrack(canvas)
        canvas.restoreToCount(save)

        // 屏幕坐标层：命中点、文字、指北针、比例尺
        drawDestination(canvas, v)
        drawMyLocation(canvas)
        drawLabels(canvas, v)
        drawCompass(canvas)
        drawScaleBar(canvas)
    }

    private fun drawGreens(canvas: Canvas) {
        for (i in greenPaths.indices) {
            val p = greenPaths[i] ?: continue
            fillPaint.color = greenColors[i]
            canvas.drawPath(p, fillPaint)
        }
    }

    private fun drawWater(canvas: Canvas) {
        for (i in waterPaths.indices) {
            val p = waterPaths[i] ?: continue
            canvas.drawPath(p, waterFillPaint)
            canvas.drawPath(p, waterStrokePaint)
        }
    }

    private fun drawRoads(canvas: Canvas) {
        // 先白色底、再浅粉灰内芯；main 更宽由数据 widthM 决定
        for (i in roadPaths.indices) {
            val p = roadPaths[i] ?: continue
            val w = max(roadWidth[i].toFloat(), dp(1.5f) / scale)
            roadCasePaint.strokeWidth = w + dp(1.6f) / scale
            canvas.drawPath(p, roadCasePaint)
        }
        for (i in roadPaths.indices) {
            val p = roadPaths[i] ?: continue
            val w = max(roadWidth[i].toFloat() * 0.8f, dp(1f) / scale)
            roadInnerPaint.strokeWidth = w
            canvas.drawPath(p, roadInnerPaint)
        }
    }

    private fun drawBuildings(canvas: Canvas) {
        for (i in bPaths.indices) {
            val p = bPaths[i] ?: continue
            fillPaint.color = bFill[i]
            canvas.drawPath(p, fillPaint)
            canvas.drawPath(p, strokePaint)
        }
    }

    private fun drawRoute(canvas: Canvas) {
        val p = routePath ?: return
        canvas.drawPath(p, routeCasePaint)
        canvas.drawPath(p, routeCorePaint)
    }

    private fun drawTrack(canvas: Canvas) {
        val p = trackPath ?: return
        canvas.drawPath(p, trackPaint)
    }

    private fun drawDestination(canvas: Canvas, v: CampusVector) {
        if (destLat.isNaN() || destLon.isNaN()) return
        val x = worldX(destLon) * scale + offX
        val y = worldY(destLat) * scale + offY
        if (x < -dp(40f) || x > width + dp(40f) || y < -dp(40f) || y > height + dp(40f)) return
        destRingPaint.strokeWidth = dp(2.4f)
        canvas.drawCircle(x, y, dp(9f), destRingPaint)
        destFillPaint.alpha = 255
        canvas.drawCircle(x, y, dp(6f), destFillPaint)
        canvas.drawCircle(x, y, dp(6f), destRingPaint)
        drawHaloText(canvas, destName ?: "", x, y - dp(14f), destLabelPaint, destLabelHaloPaint)
    }

    private fun drawMyLocation(canvas: Canvas) {
        if (myLat.isNaN() || myLon.isNaN()) return
        val x = worldX(myLon) * scale + offX
        val y = worldY(myLat) * scale + offY
        if (x < -dp(40f) || x > width + dp(40f) || y < -dp(40f) || y > height + dp(40f)) return
        if (!myBearing.isNaN()) {
            sectorRect.set(x - dp(26f), y - dp(26f), x + dp(26f), y + dp(26f))
            canvas.drawArc(sectorRect, myBearing - 31f, 62f, true, sectorPaint)
        }
        meRingPaint.strokeWidth = dp(1.8f)
        canvas.drawCircle(x, y, dp(7.6f), meRingPaint)
        canvas.drawCircle(x, y, dp(5.4f), meFillPaint)
        canvas.drawCircle(x, y, dp(7.6f), meRingPaint)
    }

    private fun drawLabels(canvas: Canvas, v: CampusVector) {
        val n = labelTexts.size
        if (n == 0) return
        val showAll = zoom >= LABEL_ZOOM
        // 低缩放时只保留目的地附近的楼名，避免文字互相覆盖
        val dLat = destLat
        val dLon = destLon
        var destX = 0f
        var destY = 0f
        val hasDest = !dLat.isNaN() && !dLon.isNaN()
        if (hasDest) {
            destX = worldX(dLon) * scale + offX
            destY = worldY(dLat) * scale + offY
        }
        for (i in 0 until n) {
            val t = labelTexts[i] ?: continue
            if (t.isEmpty()) continue
            val x = labelX[i] * scale + offX
            val y = labelY[i] * scale + offY
            if (x < -dp(60f) || x > width + dp(60f) || y < -dp(30f) || y > height + dp(30f)) continue
            if (!showAll && hasDest) {
                val dx = x - destX
                val dy = y - destY
                if (dx * dx + dy * dy > (DEST_LABEL_RADIUS_M * scale) * (DEST_LABEL_RADIUS_M * scale)) continue
            } else if (!showAll && !hasDest) {
                // 没有目的地时，低缩放只显示前 8 个，避免糊屏
                if (i >= 8) continue
            }
            drawHaloText(canvas, t, x, y, labelTextPaint, labelHaloPaint)
        }
    }

    /** 白描边 + 实心字，保证底图上可读（两次 drawText，无分配）。 */
    private fun drawHaloText(
        canvas: Canvas, text: String, x: Float, y: Float,
        fill: Paint, halo: Paint
    ) {
        if (text.isEmpty()) return
        canvas.drawText(text, x, y, halo)
        canvas.drawText(text, x, y, fill)
    }

    private fun drawCompass(canvas: Canvas) {
        val cx = width - dp(30f)
        val cy = dp(32f)
        val r = dp(15f)
        canvas.drawCircle(cx, cy, r, compassBgPaint)
        compassStrokePaint.strokeWidth = dp(1f)
        canvas.drawCircle(cx, cy, r, compassStrokePaint)
        val save = canvas.save()
        canvas.translate(cx, cy)
        canvas.drawPath(compassArrow, compassArrowPaint)
        canvas.restoreToCount(save)
        canvas.drawText("N", cx, cy - r - dp(3f), compassTextPaint)
    }

    private fun drawScaleBar(canvas: Canvas) {
        if (scale <= 0f) return
        val maxLen = dp(96f)
        var meters = 50
        if (200 * scale <= maxLen) meters = 200
        else if (100 * scale <= maxLen) meters = 100
        val len = meters * scale
        val right = width - dp(14f)
        val y = height - dp(18f)
        val left = right - len
        scaleLinePaint.strokeWidth = dp(1.5f)
        canvas.drawLine(left, y, right, y, scaleLinePaint)
        canvas.drawLine(left, y - dp(4f), left, y + dp(4f), scaleLinePaint)
        canvas.drawLine(right, y - dp(4f), right, y + dp(4f), scaleLinePaint)
        val label = when (meters) {
            200 -> "200 m"
            100 -> "100 m"
            else -> "50 m"
        }
        canvas.drawText(label, left, y - dp(6f), scaleTextPaint)
    }

    // ------------------------------------------------------------------ 命中

    /** 屏幕点 → 命中建筑：点在多边形内，或距建筑中心小于其外接半径。 */
    private fun hitTest(sx: Float, sy: Float): CampusVector.Shape? {
        val v = vec ?: return null
        val wx = (sx - offX) / scale
        val wy = (sy - offY) / scale
        val lat = lat0 - wy.toDouble() / M_PER_DEG_LAT
        val lon = lon0 + wx.toDouble() / mPerLon
        for (i in bPaths.indices) {
            val s = v.buildings[i]
            if (pointInPoly(lat, lon, s.poly)) return s
            val dx = wx - bCx[i]
            val dy = wy - bCy[i]
            if (dx * dx + dy * dy <= bR[i] * bR[i]) return s
        }
        return null
    }

    /** 射线法判断点是否在多边形内（经纬度平面近似）。 */
    private fun pointInPoly(lat: Double, lon: Double, poly: List<Pair<Double, Double>>): Boolean {
        if (poly.size < 3) return false
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val yi = poly[i].first
            val xi = poly[i].second
            val yj = poly[j].first
            val xj = poly[j].second
            if ((yi > lat) != (yj > lat)) {
                val xCross = (xj - xi) * (lat - yi) / (yj - yi) + xi
                if (lon < xCross) inside = !inside
            }
            j = i
        }
        return inside
    }

    // ------------------------------------------------------------------ 触摸 & 其他

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private fun buildCompassArrow() {
        val r = dp(10f)
        compassArrow.reset()
        compassArrow.moveTo(0f, -r)
        compassArrow.lineTo(r * 0.52f, r * 0.55f)
        compassArrow.lineTo(0f, r * 0.22f)
        compassArrow.lineTo(-r * 0.52f, r * 0.55f)
        compassArrow.close()
    }

    private fun spPx(v: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics
    )

    private fun getColorSafe(resId: Int): Int = context.getColor(resId)
}