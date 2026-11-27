package com.campus.arnav.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
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
import com.campus.arnav.core.Building
import com.campus.arnav.core.CampusProjection
import com.campus.arnav.core.GeoUtils
import com.campus.arnav.core.NavRoute
import com.campus.arnav.core.RouteRepository
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 自绘 2D 校园地图 v2：真实官方底图 + 经纬度投影，完全离线，不依赖任何地图 SDK。
 *
 * 底图有四种（[setBaseKind]）：
 * - `campus_ortho`  1279×1736 卫星正射（默认）
 * - `campus_guide`  1920×1080 官方平面导览（插画透视）
 * - `campus_index`  1280×640 卫星 + 编号图例
 * - `campus_traffic` 1280×934 交通导览
 *
 * 坐标链路：`lat/lon` → 图片像素（[CampusProjection]，assets/campus_calib.json 的单应矩阵）
 * 到位后替换）→ 屏幕（`img * scale + off`）。楼栋、路线、蓝点、目的地全部走这条链路，
 * 所以线能压在真实道路上。
 *
 * 手势：双指缩放（0.6×–4×，相对「整图适配」基准）、单指拖动、双击放大；
 * 单击楼栋点回调 [OnBuildingTap]；[zoomBy] 供外部缩放按钮使用。
 */
class CampusMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    fun interface OnBuildingTap {
        fun onBuildingTap(b: Building)
    }

    private companion object {
        const val MIN_ZOOM = 0.6f
        const val MAX_ZOOM = 4f
        const val ALL_LABELS_ZOOM = 1.8f
        const val MAX_TEXTURE_SIDE = 2048

        const val KIND_ORTHO = "campus_ortho"
        const val KIND_GUIDE = "campus_guide"
        const val KIND_INDEX = "campus_index"
        const val KIND_TRAFFIC = "campus_traffic"
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics
    )

    private val cBgFill = context.getColor(R.color.bg_dark)
    private val cAccent = context.getColor(R.color.accent)
    private val cAccentInk = context.getColor(R.color.accent_ink)
    private val cBlue = context.getColor(R.color.accent_blue)
    private val cBorder = context.getColor(R.color.border)
    private val cTextPrimary = context.getColor(R.color.text_primary)
    private val cTextSecondary = context.getColor(R.color.text_secondary)
    private val cWhite = Color.WHITE

    // ---- 数据 ----
    private var baseKind: String = KIND_ORTHO
    private var buildings: List<Building> = emptyList()
    private var routeLat = DoubleArray(0)
    private var routeLon = DoubleArray(0)
    private var destination: Building? = null
    private var myLat = Double.NaN
    private var myLon = Double.NaN
    private var myBearing = -1f
    private var followUser = false
    private var onTap: OnBuildingTap? = null

    // ---- 底图 ----
    private var mapBmp: Bitmap? = null
    private var imgW = 0f
    private var imgH = 0f
    private var laidOut = false

    // ---- 变换：screen = img * scale + off ----
    private var baseScale = 1f
    private var scale = 0f
    private var offX = 0f
    private var offY = 0f

    // ---- 缓存（onDraw 里不 new 对象） ----
    private var imgX = FloatArray(0)
    private var imgY = FloatArray(0)
    private var scrX = FloatArray(0)
    private var scrY = FloatArray(0)
    private var labelShown = BooleanArray(0)
    private var routeImgX = FloatArray(0)
    private var routeImgY = FloatArray(0)
    private var routeScrX = FloatArray(0)
    private var routeScrY = FloatArray(0)
    private var myImgX = 0f
    private var myImgY = 0f
    private val nearestIdx = IntArray(3)

    /** 当前底图横向 1 图片像素对应多少米（比例尺用）。 */
    private var imgPxPerMeter = 0f

    private val dstRect = RectF()
    private val sectorRect = RectF()
    private val labelRect = RectF()
    private val sectorPath = Path()
    private val routePath = Path()

    // ---- 画笔 ----
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val routeCasingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = cWhite
        strokeWidth = dp(14f)
    }
    private val routeDonePaint = Paint(routeCasingPaint).apply {
        color = cAccent
        strokeWidth = dp(8f)
        alpha = 255
    }
    private val routeTodoPaint = Paint(routeCasingPaint).apply {
        color = cAccent
        strokeWidth = dp(8f)
        alpha = 150
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** 官图式楼名小牌：白底圆角 + 深色字。 */
    private val labelPillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xF0FFFFFF.toInt()
    }
    private val labelPillStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = cBorder
    }
    private val labelFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(9f)
        typeface = Typeface.DEFAULT
        color = cTextPrimary
    }
    private val destPillStrokePaint = Paint(labelPillStrokePaint).apply {
        strokeWidth = dp(1.4f)
        color = cAccent
    }
    private val destTextPaint = Paint(labelFillPaint).apply {
        textSize = sp(10f)
        typeface = Typeface.DEFAULT_BOLD
        color = cAccentInk
    }
    private val scaleBarLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cTextSecondary
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val scaleBarTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cTextPrimary
        textSize = sp(10f)
    }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val b = baseScale
                if (b <= 0f || scale <= 0f) return false
                val newScale = (scale * detector.scaleFactor).coerceIn(b * MIN_ZOOM, b * MAX_ZOOM)
                val f = newScale / scale
                offX = detector.focusX - (detector.focusX - offX) * f
                offY = detector.focusY - (detector.focusY - offY) * f
                scale = newScale
                clampOffsets()
                invalidate()
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float
            ): Boolean {
                if (scaleDetector.isInProgress) return false
                offX -= dx
                offY -= dy
                followUser = false
                clampOffsets()
                invalidate()
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val hit = hitTest(e.x, e.y) ?: return false
                performClick()
                onTap?.onBuildingTap(hit)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val b = baseScale
                if (b <= 0f || scale <= 0f) return false
                val newScale = (scale * 1.6f).coerceIn(b * MIN_ZOOM, b * MAX_ZOOM)
                val f = newScale / scale
                offX = e.x - (e.x - offX) * f
                offY = e.y - (e.y - offY) * f
                scale = newScale
                clampOffsets()
                invalidate()
                return true
            }
        })

    init {
        isClickable = true
        contentDescription = "校园地图"
        CampusProjection.load(context)
        loadBase()
    }

    // ---------------------------------------------------------------- API

    /** 楼栋清单（通常来自 [RouteRepository.buildings]）。 */
    fun setBuildings(list: List<Building>) {
        buildings = list
        val n = list.size
        imgX = FloatArray(n)
        imgY = FloatArray(n)
        scrX = FloatArray(n)
        scrY = FloatArray(n)
        labelShown = BooleanArray(n)
        for (i in 0 until n) {
            projectInto(list[i].lat, list[i].lon, imgX, imgY, i)
        }
        invalidate()
    }

    /** 便利重载：只给楼名，坐标按名字从 [RouteRepository] 反查。 */
    fun setBuildingsByName(names: List<String>) {
        val byName = RouteRepository.buildings(context).associateBy { it.name }
        setBuildings(names.mapNotNull { byName[it] })
    }

    /** 路线折线（lat, lon 顺序）。 */
    fun setRoute(points: List<Pair<Double, Double>>) {
        val n = points.size
        routeLat = DoubleArray(n)
        routeLon = DoubleArray(n)
        routeImgX = FloatArray(n)
        routeImgY = FloatArray(n)
        routeScrX = FloatArray(n)
        routeScrY = FloatArray(n)
        for (i in 0 until n) {
            routeLat[i] = points[i].first
            routeLon[i] = points[i].second
            projectInto(points[i].first, points[i].second, routeImgX, routeImgY, i)
        }
        invalidate()
    }

    /** 便利重载：直接吃 [RouteRepository.routeTo] 的结果。 */
    fun setRoute(nav: NavRoute) {
        setRoute(nav.points.map { it.lat to it.lon })
        setDestination(nav.destination)
    }

    /** 蓝点位置；bearing &lt; 0 表示方向未知（不画朝向扇形）。 */
    fun setMyLocation(lat: Double, lon: Double, bearing: Float) {
        myLat = lat
        myLon = lon
        myBearing = bearing
        val p = CampusProjection.project(baseKind, lat, lon)
        if (p != null) {
            myImgX = p.first
            myImgY = p.second
        }
        if (followUser) centerOnUser()
        updateA11y()
        invalidate()
    }

    fun setDestination(b: Building?) {
        destination = b
        updateA11y()
        invalidate()
    }

    /** 便利重载：按楼名指定目的地（找不到时为 null）。 */
    fun setDestination(name: String?) {
        if (name == null) {
            destination = null
            updateA11y()
            invalidate()
            return
        }
        val found = buildings.firstOrNull { it.name == name }
            ?: RouteRepository.buildings(context).firstOrNull { it.name == name }
        setDestination(found)
    }

    /** 跟随模式：蓝点更新时视图居中跟随；用户拖动会自动退出。 */
    fun setFollowUser(on: Boolean) {
        followUser = on
        if (on) centerOnUser()
        invalidate()
    }

    fun setOnBuildingTap(cb: ((Building) -> Unit)?) {
        onTap = if (cb == null) null else OnBuildingTap { b -> cb(b) }
    }

    /**
     * 切换底图。支持 `campus_ortho`（默认）/`campus_guide`/`campus_index`/`campus_traffic`；
     * 未知 kind 直接忽略。切换后重新投影所有坐标并 [fitAll] 复位缩放、退出跟随。
     */
    fun setBaseKind(kind: String) {
        if (CampusProjection.size(kind) == null) return
        if (kind == baseKind && mapBmp != null) return
        val bmp = decodeBase(kind) ?: return
        baseKind = kind
        mapBmp = bmp
        imgW = bmp.width.toFloat()
        imgH = bmp.height.toFloat()
        reprojectAll()
        fitAll()
        updateA11y()
        invalidate()
    }

    /** 当前底图 kind（`campus_ortho` 等）。 */
    fun currentBaseKind(): String = baseKind

    /**
     * 以视图中心为锚缩放，供外部「＋ / －」按钮使用。
     * factor &gt; 1 放大、&lt; 1 缩小；结果 clamp 在 `baseScale * MIN_ZOOM .. baseScale * MAX_ZOOM`。
     */
    fun zoomBy(factor: Float) {
        if (width == 0 || height == 0 || scale <= 0f) return
        val b = baseScale
        if (b <= 0f) return
        val newScale = (scale * factor).coerceIn(b * MIN_ZOOM, b * MAX_ZOOM)
        val f = newScale / scale
        val cx = width / 2f
        val cy = height / 2f
        offX = cx - (cx - offX) * f
        offY = cy - (cy - offY) * f
        scale = newScale
        clampOffsets()
        invalidate()
    }

    /** 整图适配：缩放到正好装下整张底图、居中、退出跟随。 */
    fun fitAll() {
        if (width == 0 || height == 0 || imgW <= 0f || imgH <= 0f) return
        baseScale = min(width / imgW, height / imgH)
        scale = baseScale
        offX = (width - imgW * scale) / 2f
        offY = (height - imgH * scale) / 2f
        followUser = false
        clampOffsets()
        invalidate()
    }

    /** 把整条路线（没有路线就缩到目的地）装进视野。 */
    fun zoomToRoute() {
        if (width == 0 || height == 0) return
        val n = routeImgX.size
        var minX: Float
        var minY: Float
        var maxX: Float
        var maxY: Float
        if (n >= 2) {
            minX = Float.MAX_VALUE
            minY = Float.MAX_VALUE
            maxX = -Float.MAX_VALUE
            maxY = -Float.MAX_VALUE
            for (i in 0 until n) {
                minX = min(minX, routeImgX[i]); maxX = max(maxX, routeImgX[i])
                minY = min(minY, routeImgY[i]); maxY = max(maxY, routeImgY[i])
            }
        } else {
            val d = destination ?: return
            val p = CampusProjection.project(baseKind, d.lat, d.lon) ?: return
            minX = p.first; maxX = minX
            minY = p.second; maxY = minY
        }
        val pad = dp(56f)
        val availW = max(1f, width - pad * 2)
        val availH = max(1f, height - pad * 2)
        val fit = min(availW / max(1f, maxX - minX), availH / max(1f, maxY - minY))
        val b = baseScale
        scale = fit.coerceIn(b * MIN_ZOOM, b * MAX_ZOOM)
        offX = width / 2f - (minX + maxX) / 2f * scale
        offY = height / 2f - (minY + maxY) / 2f * scale
        clampOffsets()
        invalidate()
    }

    // ---------------------------------------------------------------- 手势

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0 || imgW <= 0f || imgH <= 0f) return
        baseScale = min(w / imgW, h / imgH)
        if (!laidOut) {
            laidOut = true
            scale = baseScale
            offX = (w - imgW * scale) / 2f
            offY = (h - imgH * scale) / 2f
        } else {
            scale = (if (scale > 0f) scale else baseScale)
                .coerceIn(baseScale * MIN_ZOOM, baseScale * MAX_ZOOM)
            // 尺寸变化后把整图中心留在视图中心，避免旋转屏幕后跑偏
            offX = w / 2f - imgW * scale / 2f
            offY = h / 2f - imgH * scale / 2f
        }
        clampOffsets()
    }

    // ---------------------------------------------------------------- 绘制

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(cBgFill)
        val bmp = mapBmp
        if (bmp == null || w <= 0f || h <= 0f) return
        dstRect.set(offX, offY, offX + imgW * scale, offY + imgH * scale)
        canvas.drawBitmap(bmp, null, dstRect, bitmapPaint)

        projectAll()
        drawRoute(canvas)
        drawBuildings(canvas)
        drawMyLocation(canvas)
        drawScaleBar(canvas)
    }

    private fun drawRoute(canvas: Canvas) {
        val n = routeScrX.size
        if (n == 0) return
        if (n == 1) {
            dotPaint.color = cAccent
            canvas.drawCircle(routeScrX[0], routeScrY[0], dp(5f), dotPaint)
            return
        }
        // 已走段 = 起点到离我最近的路线点；没有定位就整条算「未走」
        var split = 0
        if (!myLat.isNaN()) {
            val mx = myImgX * scale + offX
            val my = myImgY * scale + offY
            var best = Float.MAX_VALUE
            for (i in 0 until n) {
                val dx = routeScrX[i] - mx
                val dy = routeScrY[i] - my
                val d = dx * dx + dy * dy
                if (d < best) {
                    best = d
                    split = i
                }
            }
        }
        routePath.reset()
        routePath.moveTo(routeScrX[0], routeScrY[0])
        for (i in 1 until n) routePath.lineTo(routeScrX[i], routeScrY[i])
        canvas.drawPath(routePath, routeCasingPaint)
        for (i in 0 until n - 1) {
            canvas.drawLine(
                routeScrX[i], routeScrY[i], routeScrX[i + 1], routeScrY[i + 1],
                if (i < split) routeDonePaint else routeTodoPaint
            )
        }
        val ex = routeScrX[n - 1]
        val ey = routeScrY[n - 1]
        dotPaint.color = cWhite
        canvas.drawCircle(ex, ey, dp(8f), dotPaint)
        dotPaint.color = cAccent
        canvas.drawCircle(ex, ey, dp(5f), dotPaint)
    }

    private fun drawBuildings(canvas: Canvas) {
        val n = scrX.size
        if (n == 0) return
        val dest = destination
        val zoom = if (baseScale > 0f) scale / baseScale else 1f
        if (zoom >= ALL_LABELS_ZOOM) {
            for (i in 0 until n) labelShown[i] = true
        } else {
            for (i in 0 until n) labelShown[i] = false
            val ox = if (!myLat.isNaN()) myImgX * scale + offX else width / 2f
            val oy = if (!myLat.isNaN()) myImgY * scale + offY else height / 2f
            nearestIdx.fill(-1)
            for (i in 0 until n) {
                val dx = scrX[i] - ox
                val dy = scrY[i] - oy
                val d2 = dx * dx + dy * dy
                for (k in 0 until 3) {
                    if (nearestIdx[k] < 0) {
                        nearestIdx[k] = i
                        break
                    }
                    val bi = nearestIdx[k]
                    val bdx = scrX[bi] - ox
                    val bdy = scrY[bi] - oy
                    if (d2 < bdx * bdx + bdy * bdy) {
                        for (m in 2 downTo k + 1) nearestIdx[m] = nearestIdx[m - 1]
                        nearestIdx[k] = i
                        break
                    }
                }
            }
            for (k in 0 until 3) {
                val idx = nearestIdx[k]
                if (idx >= 0) labelShown[idx] = true
            }
            if (dest != null) {
                val di = buildings.indexOf(dest)
                if (di >= 0) labelShown[di] = true
            }
        }

        for (i in 0 until n) {
            val bx = scrX[i]
            val by = scrY[i]
            if (bx < -dp(40f) || by < -dp(40f) || bx > width + dp(40f) || by > height + dp(40f)) continue
            if (dest != null && buildings[i].id == dest.id) {
                haloPaint.color = (cAccent and 0x00FFFFFF) or (48 shl 24)
                canvas.drawCircle(bx, by, dp(11f), haloPaint)
                dotPaint.color = cAccent
                canvas.drawCircle(bx, by, dp(5.5f), dotPaint)
                ringPaint.color = cWhite
                ringPaint.strokeWidth = dp(2f)
                canvas.drawCircle(bx, by, dp(5.5f), ringPaint)
            } else {
                dotPaint.color = cWhite
                canvas.drawCircle(bx, by, dp(3.4f), dotPaint)
                ringPaint.color = cTextSecondary
                ringPaint.strokeWidth = dp(1.4f)
                canvas.drawCircle(bx, by, dp(3.4f), ringPaint)
            }
        }

        for (i in 0 until n) {
            if (!labelShown[i]) continue
            val bx = scrX[i]
            val by = scrY[i]
            if (bx < -dp(70f) || by < -dp(40f) || bx > width + dp(70f) || by > height + dp(40f)) continue
            val isDest = dest != null && buildings[i].id == dest.id
            val textPaint = if (isDest) destTextPaint else labelFillPaint
            val name = buildings[i].name
            val tw = textPaint.measureText(name)
            val padX = dp(5f)
            val padY = dp(2.5f)
            val pillW = tw + padX * 2
            val pillH = textPaint.textSize + padY * 2
            var px = bx + dp(6f)
            if (px + pillW > width - dp(3f)) px = bx - dp(6f) - pillW
            var py = by - dp(5f) - pillH
            if (py < dp(2f)) py = by + dp(5f)
            labelRect.set(px, py, px + pillW, py + pillH)
            val radius = dp(4f)
            canvas.drawRoundRect(labelRect, radius, radius, labelPillPaint)
            canvas.drawRoundRect(
                labelRect, radius, radius,
                if (isDest) destPillStrokePaint else labelPillStrokePaint
            )
            val baseline = py + padY - textPaint.ascent()
            canvas.drawText(name, px + padX, baseline, textPaint)
        }
    }

    private fun drawMyLocation(canvas: Canvas) {
        if (myLat.isNaN() || myLon.isNaN()) return
        val x = myImgX * scale + offX
        val y = myImgY * scale + offY

        if (myBearing >= 0f) {
            val r = dp(44f)
            sectorRect.set(x - r, y - r, x + r, y + r)
            sectorPath.reset()
            sectorPath.moveTo(x, y)
            sectorPath.arcTo(sectorRect, myBearing - 31f, 62f, false)
            sectorPath.close()
            sectorPaint.color = (cBlue and 0x00FFFFFF) or (66 shl 24)
            canvas.drawPath(sectorPath, sectorPaint)
        }

        haloPaint.color = (cBlue and 0x00FFFFFF) or (58 shl 24)
        canvas.drawCircle(x, y, dp(17f), haloPaint)
        dotPaint.color = cWhite
        canvas.drawCircle(x, y, dp(8.5f), dotPaint)
        dotPaint.color = cBlue
        canvas.drawCircle(x, y, dp(6.5f), dotPaint)
        ringPaint.color = cWhite
        ringPaint.strokeWidth = dp(2f)
        canvas.drawCircle(x, y, dp(6.5f), ringPaint)
    }

    private fun drawScaleBar(canvas: Canvas) {
        val pxPerMeter = scale * imgPxPerMeter
        if (pxPerMeter <= 0f) return
        var meters = 200
        for (m in intArrayOf(20, 50, 100, 200)) {
            if (m * pxPerMeter <= dp(96f)) meters = m
        }
        val len = meters * pxPerMeter
        val x = dp(12f)
        val y = height - dp(16f)
        canvas.drawLine(x, y, x + len, y, scaleBarLinePaint)
        canvas.drawLine(x, y - dp(4f), x, y + dp(4f), scaleBarLinePaint)
        canvas.drawLine(x + len, y - dp(4f), x + len, y + dp(4f), scaleBarLinePaint)
        val label = if (meters >= 100) (meters / 100).toString() + "00 m" else meters.toString() + " m"
        canvas.drawText(label, x, y - dp(6f), scaleBarTextPaint)
    }

    // ---------------------------------------------------------------- 内部

    /** 图片像素 → 屏幕像素。 */
    private fun projectAll() {
        for (i in scrX.indices) {
            scrX[i] = imgX[i] * scale + offX
            scrY[i] = imgY[i] * scale + offY
        }
        for (i in routeScrX.indices) {
            routeScrX[i] = routeImgX[i] * scale + offX
            routeScrY[i] = routeImgY[i] * scale + offY
        }
    }

    /** lat/lon → 图片像素（投影依赖 baseKind，切底图后必须重跑）。 */
    private fun reprojectAll() {
        for (i in imgX.indices) {
            projectInto(buildings[i].lat, buildings[i].lon, imgX, imgY, i)
        }
        for (i in routeImgX.indices) {
            projectInto(routeLat[i], routeLon[i], routeImgX, routeImgY, i)
        }
        if (!myLat.isNaN() && !myLon.isNaN()) {
            val p = CampusProjection.project(baseKind, myLat, myLon)
            if (p != null) {
                myImgX = p.first
                myImgY = p.second
            }
        }
        computeImgPxPerMeter()
    }

    /**
     * 比例尺用：把「图片横向 100 米」投影出来，量两端像素距离换算出 px/m。
     * 单应矩阵会让不同位置的缩放略有差异，这里取外接框中心附近的值。
     */
    private fun computeImgPxPerMeter() {
        val midLat = (CampusProjection.LAT_MIN + CampusProjection.LAT_MAX) / 2
        val midLon = (CampusProjection.LON_MIN + CampusProjection.LON_MAX) / 2
        val p0 = CampusProjection.project(baseKind, midLat, midLon) ?: return
        val dLon = 100.0 / (111320.0 * cos(Math.toRadians(midLat)))
        val p1 = CampusProjection.project(baseKind, midLat, midLon + dLon) ?: return
        val dx = (p1.first - p0.first).toDouble()
        val dy = (p1.second - p0.second).toDouble()
        val distPx = hypot(dx, dy)
        imgPxPerMeter = if (distPx > 1e-6) (distPx / 100.0).toFloat() else 0f
    }

    private fun projectInto(lat: Double, lon: Double, xs: FloatArray, ys: FloatArray, i: Int) {
        val p = CampusProjection.project(baseKind, lat, lon)
        xs[i] = p?.first ?: 0f
        ys[i] = p?.second ?: 0f
    }

    private fun loadBase() {
        val bmp = decodeBase(baseKind) ?: return
        mapBmp = bmp
        imgW = bmp.width.toFloat()
        imgH = bmp.height.toFloat()
        computeImgPxPerMeter()
    }

    private fun decodeBase(kind: String): Bitmap? {
        val resId = when (kind) {
            KIND_GUIDE -> R.drawable.campus_guide
            KIND_INDEX -> R.drawable.campus_index
            KIND_TRAFFIC -> R.drawable.campus_traffic
            else -> R.drawable.campus_ortho
        }
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(resources, resId, bounds)
            var sample = 1
            val longSide = max(bounds.outWidth, bounds.outHeight)
            while (longSide / sample > MAX_TEXTURE_SIDE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeResource(resources, resId, opts)
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun centerOnUser() {
        if (myLat.isNaN() || myLon.isNaN() || width == 0) return
        if (scale == 0f) scale = baseScale
        offX = width / 2f - myImgX * scale
        offY = height * 0.58f - myImgY * scale
        clampOffsets()
    }

    private fun clampOffsets() {
        val mw = imgW * scale
        val mh = imgH * scale
        offX = if (mw <= width) (width - mw) / 2f else offX.coerceIn(width - mw, 0f)
        offY = if (mh <= height) (height - mh) / 2f else offY.coerceIn(height - mh, 0f)
    }

    private fun hitTest(x: Float, y: Float): Building? {
        var best = -1
        var bestD = dp(26f) * dp(26f)
        for (i in scrX.indices) {
            val dx = scrX[i] - x
            val dy = scrY[i] - y
            val d = dx * dx + dy * dy
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return if (best >= 0) buildings[best] else null
    }

    private fun updateA11y() {
        val d = destination
        contentDescription = when {
            d == null -> "校园地图，未选择目的地"
            myLat.isNaN() -> "校园地图，当前目的地 " + d.name
            else -> {
                val m = GeoUtils.distanceMeters(myLat, myLon, d.lat, d.lon)
                "校园地图，当前目的地 " + d.name + "，剩余 " + m.toInt() + " 米"
            }
        }
    }
}