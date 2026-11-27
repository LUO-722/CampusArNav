package com.campus.arnav.core

import android.content.Context
import org.json.JSONObject
import kotlin.math.abs

/**
 * 官方底图坐标投影：把 WGS84 经纬度映射到官方导览图/卫星图的像素坐标，或反向由用户点按的像素反推经纬度。
 *
 * 标定数据在 `assets/campus_calib.json`（每张底图一组控制点 + 单应矩阵），控制点来自
 * `res/drawable-nodpi/` 里官方图上的可见地标，与 [RouteRepository] 的经纬度配对拟合。
 *
 * 兜底：`campus_calib.json` 缺失、某张图没标定、或矩阵坏了 —— 不抛异常、不返回 null，
 * 退化成长宽拉伸（按校园外接框 [LAT_MIN]..[LAT_MAX] / [LON_MIN]..[LON_MAX] 铺满该图像素），
 * 保证地图/标记永远有坐标可画；KDoc 里说明这是粗略兜底。
 */
object CampusProjection {

    /** 校园外接框（WGS84，来自 RouteRepository 27 个目的地的最小外接矩形，四舍五入到 1e-6）。 */
    const val LAT_MIN = 34.342066
    const val LAT_MAX = 34.349330
    const val LON_MIN = 117.368916
    const val LON_MAX = 117.379573

    private const val ASSET = "campus_calib.json"

    /** 一张已标定（或仅登记了尺寸）的底图。 */
    class CalibImage(
        val name: String,
        val file: String?,
        val w: Int,
        val h: Int,
        /** 行主序 3×3 单应矩阵：像素 = H · (lon, lat, 1)，9 个数；null 表示未标定。 */
        val matrix: DoubleArray?,
        val residualPx: Double,
        val controlCount: Int
    ) {
        val calibrated: Boolean get() = matrix != null && matrix.size == 9
    }

    /** 四张官方底图的名称 → 像素尺寸。JSON 里没登记时也用这份默认值。 */
    private val DEFAULT_SIZES: Map<String, Pair<Int, Int>> = linkedMapOf(
        "campus_guide" to (1920 to 1080),
        "campus_ortho" to (1279 to 1736),
        "campus_index" to (1280 to 640),
        "campus_traffic" to (1280 to 934)
    )

    @Volatile
    private var images: Map<String, CalibImage> = emptyMap()

    @Volatile
    private var ready = false

    /** 读取并解析 assets/campus_calib.json；失败返回 false（不抛异常，退化到线性兜底）。 */
    fun load(ctx: Context): Boolean {
        if (ready) return true
        return synchronized(this) {
            if (ready) return true
            images = try {
                parse(ctx)
            } catch (t: Throwable) {
                emptyMap()
            }
            ready = images.isNotEmpty()
            ready
        }
    }

    /** 已登记的底图名称；调用方拿它做底图切换。 */
    fun kinds(): List<String> {
        val out = ArrayList<String>(DEFAULT_SIZES.keys)
        for (k in images.keys) if (k !in out) out += k
        return out
    }

    /** 该底图的像素尺寸 (width, height)；未知 kind 返回 null。 */
    fun size(kind: String): Pair<Int, Int>? {
        images[kind]?.let { return it.w to it.h }
        return DEFAULT_SIZES[kind]
    }

    /**
     * 经纬度 → 该底图像素 (x, y)。已标定走单应投影，未标定走线性拉伸兜底。
     * kind 未知时返回 null；其余情况一定返回值（地图标记永远画得出来）。
     */
    fun project(kind: String, lat: Double, lon: Double): Pair<Float, Float>? {
        val sz = size(kind) ?: return null
        val m = images[kind]?.matrix
        if (m != null && m.size == 9) {
            val den = m[6] * lon + m[7] * lat + m[8]
            if (abs(den) < 1e-12) return null
            val x = (m[0] * lon + m[1] * lat + m[2]) / den
            val y = (m[3] * lon + m[4] * lat + m[5]) / den
            return x.toFloat() to y.toFloat()
        }
        val x = (lon - LON_MIN) / (LON_MAX - LON_MIN) * sz.first
        val y = (LAT_MAX - lat) / (LAT_MAX - LAT_MIN) * sz.second
        return x.toFloat() to y.toFloat()
    }

    /**
     * 该底图像素 (x, y) → (lat, lon)。已标定用矩阵求逆，未标定按线性拉伸反解。
     * 注意返回顺序是 `lat to lon`，与 [RouteRepository.Building] 的字段顺序一致。
     */
    fun unproject(kind: String, x: Float, y: Float): Pair<Double, Double>? {
        val sz = size(kind) ?: return null
        val m = images[kind]?.matrix
        if (m != null && m.size == 9) {
            val inv = invert3(m) ?: return null
            val den = inv[6] * x + inv[7] * y + inv[8]
            if (abs(den) < 1e-12) return null
            val lon = (inv[0] * x + inv[1] * y + inv[2]) / den
            val lat = (inv[3] * x + inv[4] * y + inv[5]) / den
            return lat to lon
        }
        val lon = LON_MIN + x.toDouble() / sz.first * (LON_MAX - LON_MIN)
        val lat = LAT_MAX - y.toDouble() / sz.second * (LAT_MAX - LAT_MIN)
        return lat to lon
    }

    /** 调试：该底图的最大像素残差（未标定返回 null）。 */
    fun residualPx(kind: String): Double? = images[kind]?.takeIf { it.calibrated }?.residualPx

    /** 调试：该底图的控制点数量。 */
    fun controlPoints(kind: String): Int = images[kind]?.controlCount ?: 0

    // ---- 内部 ----

    private fun parse(ctx: Context): Map<String, CalibImage> {
        val text = ctx.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val doc = JSONObject(text)
        val arr = doc.optJSONArray("images") ?: return emptyMap()
        val out = LinkedHashMap<String, CalibImage>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name")
            if (name.isEmpty()) continue
            val fallback = DEFAULT_SIZES[name]
            // 尺寸：w 是 int；高度同时接受 "height"（本文件用）与 "h" 为整数（兼容旧样例）
            val w = o.optInt("w", fallback?.first ?: 0)
            var h = o.optInt("height", 0)
            if (h <= 0) {
                val hAsArray = o.optJSONArray("h")
                if (hAsArray == null) h = o.optInt("h", fallback?.second ?: 0)
                else h = o.optInt("height", fallback?.second ?: 0)
            }
            if (w <= 0 || h <= 0) continue
            // 矩阵：matrix 优先，其次 h（数组形式）
            var matrix: DoubleArray? = null
            val mArr = o.optJSONArray("matrix") ?: o.optJSONArray("h")
            if (mArr != null && mArr.length() == 9) {
                val v = DoubleArray(9) { mArr.optDouble(it, Double.NaN) }
                if (v.none { it.isNaN() }) matrix = v
            }
            val control = o.optJSONArray("control")
            val residual = if (o.has("residualPx") && !o.isNull("residualPx")) o.optDouble("residualPx", 0.0) else 0.0
            out[name] = CalibImage(
                name = name,
                file = o.optString("file").takeIf { it.isNotEmpty() },
                w = w,
                h = h,
                matrix = matrix,
                residualPx = residual,
                controlCount = control?.length() ?: 0
            )
        }
        return out
    }

    /** 3×3 行主序矩阵求逆（伴随矩阵法）；奇异时返回 null。 */
    private fun invert3(m: DoubleArray): DoubleArray? {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val A = e * i - f * h
        val B = -(d * i - f * g)
        val C = d * h - e * g
        val det = a * A + b * B + c * C
        if (abs(det) < 1e-15) return null
        val inv = doubleArrayOf(
            A / det, -(b * i - c * h) / det, (b * f - c * e) / det,
            B / det, (a * i - c * g) / det, -(a * f - c * d) / det,
            C / det, -(a * h - b * g) / det, (a * e - b * d) / det
        )
        return inv
    }
}