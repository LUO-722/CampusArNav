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
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.campus.arnav.R
import com.campus.arnav.core.Building
import com.campus.arnav.core.GeoUtils
import com.campus.arnav.core.NavRoute
import com.campus.arnav.core.RouteRepository
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * 自绘 2D 校园地图：完全离线，不依赖任何地图 SDK。
 *
 * 底图 [R.drawable.campus_map]（1600×1320）与 [LAT_MIN]..[LAT_MAX] / [LON_MIN]..[LON_MAX]
 * 严格线性对应：边界取 [RouteRepository] 全部楼栋 + 南门的外接矩形再各放 5% 边距，
 * 所以楼栋点、路线、蓝点在同一坐标框架里自洽。
 *
 * 手势：双指缩放（0.6×–4×，相对「整图适配」的基准比例）、单指拖动、双击放大；
 * 单击楼栋点回调 [OnBuildingTap]。用户一拖动就退出 [setFollowUser] 跟随。
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
        const val IMG_W = 1600f
        const val IMG_H = 1320f

        // 与 res/drawable-nodpi/campus_map.png 的边界一一对应。
        const val LAT_MIN = 34.342066
        const val LAT_MAX = 34.349330
        const val LON_MIN = 117.368916
        const val LON_MAX = 117.379573

        const val MIN_ZOOM = 0.6f
        const val MAX_ZOOM = 4f
        const val ALL_LABELS_ZOOM = 1.7f
    }

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = v * scaledDensity

    private val cBgFill = context.getColor(R.color.bg_dark)
    private val cAccent = context.getColor(R.color.accent)
    private val cAccentInk = context.getColor(R.color.accent_ink)
    private val cBlue = context.getColor(R.color.accent_blue)
    private val cTextPrimary = context.getColor(R.color.text_primary)
    private val cTextSecondary = context.getColor(R.color.text_secondary)

    // ---- 数据 ----
    private var buildings: List<Building> = emptyList()
    private var destination: Building? = null
    private var myLat = Double.NaN
    private var myLon = Double.NaN
    private var myBearing = -1f
    private var followUser = false
    private var onTap: OnBuildingTap? = null

    // ---- 变换：screen = img * scale + off ----
    private val mapBmp: Bitmap? = try {
        BitmapFactory.decodeResource(resources, R.drawable.campus_map)
    } catch (e: OutOfMemoryError) {
        null
    }
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
    private val nearestIdx = IntArray(3)

    private val dstRect = RectF()
    private val sectorRect = RectF()
    private val sectorPath = Path()
    private val routePath = Path()

    // ---- 画笔 ----
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val routeCasingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
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
    private val labelFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11f)
        typeface = Typeface.DEFAULT
        color = cTextPrimary
    }
    private val labelStrokePaint = Paint(labelFillPaint).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val destLabelPaint = Paint(labelFillPaint).apply {
        textSize = sp(12f)
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

    /** 经度跨度对应的米数，用于比例尺。 */
    private val lonSpanMeters = (LON_MAX - LON_MIN) * 111320.0 *
        cos(Math.toRadians((LAT_MIN + LAT_MAX) / 2.0))

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val b = baseScale
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
    }

    // ---------------------------------------------------------------- API

    /** 楼栋清单（[RouteRepository.buildings]）。 */
    fun setBuildings(list: List<Building>) {
        buildings = list
        val n = list.size
        imgX = FloatArray(n)
        imgY = FloatArray(n)
        scrX = FloatArray(n)
        scrY = FloatArray(n)
        labelShown = BooleanArray(n)
        for (i in 0 until n) {
            imgX[i] = lonToImgX(list[i].lon)
            imgY[i] = latToImgY(list[i].lat)
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
        routeImgX = FloatArray(n)
        routeImgY = FloatArray(n)
        routeScrX = FloatArray(n)
        routeScrY = FloatArray(n)
        for (i in 0 until n) {
            routeImgX[i] = lonToImgX(points[i].second)
            routeImgY[i] = latToImgY(points[i].first)
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
            minX = lonToImgX(d.lon); maxX = minX
            minY = latToImgY(d.lat); maxY = minY
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
        baseScale = min(w / IMG_W, h / IMG_H)
        if (scale == 0f) {
            scale = baseScale
        } else {
            scale = scale.coerceIn(baseScale * MIN_ZOOM, baseScale * MAX_ZOOM)
        }
        // 尺寸变化后把当前地图中心留在视图中心，避免旋转屏幕后跑偏
        offX = w / 2f - IMG_W * scale / 2f
        offY = h / 2f - IMG_H * scale / 2f
        clampOffsets()
    }

    // ---------------------------------------------------------------- 绘制

    override fun onDraw(canvas: Canvas) {
        val bmp = mapBmp
        val w = width.toFloat()
        val h = height.toFloat()
        if (bmp == null || w <= 0f || h <= 0f) {
            canvas.drawColor(cBgFill)
            return
        }
        canvas.drawColor(cBgFill)
        dstRect.set(offX, offY, offX + IMG_W * scale, offY + IMG_H * scale)
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
            var best = Float.MAX_VALUE
            val mx = lonToImgX(myLon) * scale + offX
            val my = latToImgY(myLat) * scale + offY
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
        dotPaint.color = Color.WHITE
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
            val ox = if (!myLat.isNaN()) lonToImgX(myLon) * scale + offX else width / 2f
            val oy = if (!myLat.isNaN()) latToImgY(myLat) * scale + offY else height / 2f
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
                ringPaint.color = Color.WHITE
                ringPaint.strokeWidth = dp(2f)
                canvas.drawCircle(bx, by, dp(5.5f), ringPaint)
            } else {
                dotPaint.color = Color.WHITE
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
            if (bx < -dp(60f) || by < -dp(30f) || bx > width + dp(60f) || by > height + dp(30f)) continue
            val isDest = dest != null && buildings[i].id == dest.id
            val paint = if (isDest) destLabelPaint else labelFillPaint
            val name = buildings[i].name
            val tw = paint.measureText(name)
            var tx = bx + dp(7f)
            if (tx + tw > width - dp(4f)) tx = bx - dp(7f) - tw
            val ty = by - dp(6f)
            labelStrokePaint.textSize = paint.textSize
            canvas.drawText(name, tx, ty, labelStrokePaint)
            canvas.drawText(name, tx, ty, paint)
        }
    }

    private fun drawMyLocation(canvas: Canvas) {
        if (myLat.isNaN() || myLon.isNaN()) return
        val x = lonToImgX(myLon) * scale + offX
        val y = latToImgY(myLat) * scale + offY

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
        dotPaint.color = Color.WHITE
        canvas.drawCircle(x, y, dp(8.5f), dotPaint)
        dotPaint.color = cBlue
        canvas.drawCircle(x, y, dp(6.5f), dotPaint)
        ringPaint.color = Color.WHITE
        ringPaint.strokeWidth = dp(2f)
        canvas.drawCircle(x, y, dp(6.5f), ringPaint)
    }

    private fun drawScaleBar(canvas: Canvas) {
        val pxPerMeter = (scale * (IMG_W / lonSpanMeters)).toFloat()
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

    private fun centerOnUser() {
        if (myLat.isNaN() || myLon.isNaN() || width == 0) return
        if (scale == 0f) scale = baseScale
        offX = width / 2f - lonToImgX(myLon) * scale
        offY = height * 0.58f - latToImgY(myLat) * scale
        clampOffsets()
    }

    private fun clampOffsets() {
        val mw = IMG_W * scale
        val mh = IMG_H * scale
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

    private fun lonToImgX(lon: Double): Float =
        ((lon - LON_MIN) / (LON_MAX - LON_MIN) * IMG_W).toFloat()

    private fun latToImgY(lat: Double): Float =
        ((LAT_MAX - lat) / (LAT_MAX - LAT_MIN) * IMG_H).toFloat()
}