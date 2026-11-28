package com.campus.arnav.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自绘 2D 平面图的矢量数据：建筑/水面/绿地都是多边形，道路是折线，全部是经纬度（GCJ-02）。
 *
 * 数据在 `assets/campus_vector.json`，由 `_build_vector.py` 从官方四张图的测绘结果生成
 * 楼体、水面、绿地和道路的起点来自底图读点，坐标统一成 GCJ-02，与卫星底图同一套坐标系。
 * 地图**不使用任何位图**，全部由 [com.campus.arnav.ui.VectorMapView] 画出来。
 */
class CampusVector(
    val latMin: Double,
    val latMax: Double,
    val lonMin: Double,
    val lonMax: Double,
    /** 建筑多边形（教学楼、宿舍、食堂、场馆……）。 */
    val buildings: List<Shape>,
    /** 水面（方湖、星垂湖）。 */
    val water: List<Shape>,
    /** 绿地/广场/运动场等开放面。 */
    val greens: List<Shape>,
    /** 道路折线（主路与步道）。 */
    val roads: List<Path>,
    /** 图上文字（楼名、片区名）。 */
    val labels: List<Label>
) {

    /** 一个多边形面。`kind` 用来决定配色：teach/canteen/dorm/library/sport/admin/gate/other。 */
    class Shape(
        val id: String,
        val name: String,
        val kind: String,
        /** 每个点 (lat, lon)。 */
        val poly: List<Pair<Double, Double>>
    )

    /** 一条折线（道路）。`kind`: main / path / ring。 */
    class Path(
        val id: String,
        val name: String,
        val kind: String,
        /** 建议线宽（米），渲染时按当前缩放折算成像素。 */
        val widthM: Int,
        val pts: List<Pair<Double, Double>>
    )

    /** 文字标注。`kind`: building / area / water / road。 */
    class Label(val text: String, val lat: Double, val lon: Double, val kind: String)

    /** 找最近的建筑（用于点击命中）；`maxMeters` 内才算命中。 */
    /** bounds 缺失或退化（跨度 ≤ 0）时，用实际几何反推，避免整张图上不了屏。 */
    fun withComputedBounds(): CampusVector {
        if (latMax - latMin > 1e-6 && lonMax - lonMin > 1e-6) return this
        var a = Double.MAX_VALUE
        var b2 = -Double.MAX_VALUE
        var c = Double.MAX_VALUE
        var d = -Double.MAX_VALUE
        fun acc(lat: Double, lon: Double) {
            if (lat < a) a = lat
            if (lat > b2) b2 = lat
            if (lon < c) c = lon
            if (lon > d) d = lon
        }
        (buildings + water + greens).forEach { sh -> sh.poly.forEach { acc(it.first, it.second) } }
        roads.forEach { r -> r.pts.forEach { acc(it.first, it.second) } }
        labels.forEach { acc(it.lat, it.lon) }
        if (b2 - a <= 1e-6 || d - c <= 1e-6) return this
        return CampusVector(a, b2, c, d, buildings, water, greens, roads, labels)
    }

    /**
     * 用给定经纬范围替换 bounds。
     *
     * 用途：世界坐标必须等于**卫星底图**（`assets/campus_base.json`）的地理范围，
     * 底图才会 1:1 铺满、不会被拉伸，路线/蓝点/楼名才不会整体错位。
     */
    fun withBounds(latMin: Double, latMax: Double, lonMin: Double, lonMax: Double): CampusVector =
        CampusVector(latMin, latMax, lonMin, lonMax, buildings, water, greens, roads, labels)

    fun buildingNear(lat: Double, lon: Double, maxMeters: Double = 60.0): Shape? {
        var best: Shape? = null
        var bestD = Double.MAX_VALUE
        for (b in buildings) {
            val c = center(b) ?: continue
            val d = GeoUtils.distanceMeters(lat, lon, c.first, c.second)
            if (d < bestD) {
                bestD = d
                best = b
            }
        }
        return if (bestD <= maxMeters) best else null
    }

    /** 按 id 找建筑。 */
    fun building(id: String): Shape? = buildings.firstOrNull { it.id == id }

    companion object {
        private const val ASSET = "campus_vector.json"
        private const val BASE_ASSET = "campus_base.json"

        /** 多边形的几何中心（顶点平均，够用）。 */
        fun center(s: Shape): Pair<Double, Double>? {
            if (s.poly.isEmpty()) return null
            var la = 0.0
            var lo = 0.0
            for (p in s.poly) {
                la += p.first
                lo += p.second
            }
            return la / s.poly.size to lo / s.poly.size
        }

        @Volatile
        private var cached: CampusVector? = null

        /**
         * 卫星底图（`assets/campus_base.json`）自带的地理范围，顺序 [latMin, latMax, lonMin, lonMax]。
         * 读不到或退化时返回 null。
         */
        fun baseBounds(ctx: Context): DoubleArray? = try {
            val o = JSONObject(ctx.assets.open(BASE_ASSET).bufferedReader().use { it.readText() })
            doubleArrayOf(
                o.optDouble("latMin", 0.0),
                o.optDouble("latMax", 0.0),
                o.optDouble("lonMin", 0.0),
                o.optDouble("lonMax", 0.0)
            ).takeIf { it[1] - it[0] > 1e-6 && it[3] - it[2] > 1e-6 }
        } catch (t: Throwable) {
            null
        }

        /**
         * 读取并解析 `assets/campus_vector.json`；失败返回 null（调用方退化到卫星图底图）。
         *
         * 注意：解析后会用 [baseBounds] 把 bounds 对齐到卫星底图的真实范围 ——
         * 否则 [com.campus.arnav.ui.VectorMapView] 会按矢量包围盒去拉伸底图，出现「图被压扁 + 路线错位」。
         */
        fun load(ctx: Context): CampusVector? {
            cached?.let { return it }
            return synchronized(this) {
                cached ?: try {
                    val v = parse(ctx.assets.open(ASSET).bufferedReader().use { it.readText() })
                    val g = baseBounds(ctx)
                    val fixed = if (g != null) v.withBounds(g[0], g[1], g[2], g[3]) else v
                    fixed.also { cached = it }
                } catch (t: Throwable) {
                    null
                }
            }
        }

        /** 解析 JSON 文本（便于自检直接喂样例）。 */
        fun parse(text: String): CampusVector {
            val o = JSONObject(text)
            val b = o.optJSONObject("bounds")
            // bounds 既支持嵌套对象，也支持顶层平铺（生成脚本两种都出现过，缺一个整张地图就画不出来）
            fun num(key: String): Double = b?.optDouble(key, 0.0)?.takeIf { it != 0.0 }
                ?: o.optDouble(key, 0.0)
            val out = CampusVector(
                latMin = num("latMin"),
                latMax = num("latMax"),
                lonMin = num("lonMin"),
                lonMax = num("lonMax"),
                buildings = shapes(o.optJSONArray("buildings"), "building"),
                water = shapes(o.optJSONArray("water"), "water"),
                greens = shapes(o.optJSONArray("greens"), "green"),
                roads = paths(o.optJSONArray("roads")),
                labels = labels(o.optJSONArray("labels"))
            )
            return out.withComputedBounds()
        }

        private fun shapes(arr: JSONArray?, fallbackKind: String): List<Shape> {
            if (arr == null) return emptyList()
            val out = ArrayList<Shape>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val poly = points(o.optJSONArray("poly"))
                if (poly.size < 3) continue
                out.add(
                    Shape(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        kind = o.optString("kind", fallbackKind),
                        poly = poly
                    )
                )
            }
            return out
        }

        private fun paths(arr: JSONArray?): List<Path> {
            if (arr == null) return emptyList()
            val out = ArrayList<Path>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pts = points(o.optJSONArray("pts"))
                if (pts.size < 2) continue
                out.add(
                    Path(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        kind = o.optString("kind", "path"),
                        widthM = o.optInt("width", 6),
                        pts = pts
                    )
                )
            }
            return out
        }

        private fun labels(arr: JSONArray?): List<Label> {
            if (arr == null) return emptyList()
            val out = ArrayList<Label>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val text = o.optString("text")
                if (text.isEmpty()) continue
                out.add(
                    Label(
                        text = text,
                        lat = o.optDouble("lat", 0.0),
                        lon = o.optDouble("lon", 0.0),
                        kind = o.optString("kind", "building")
                    )
                )
            }
            return out
        }

        private fun points(arr: JSONArray?): List<Pair<Double, Double>> {
            if (arr == null) return emptyList()
            val out = ArrayList<Pair<Double, Double>>(arr.length())
            for (i in 0 until arr.length()) {
                val p = arr.optJSONArray(i) ?: continue
                if (p.length() < 2) continue
                out.add(p.optDouble(0) to p.optDouble(1))
            }
            return out
        }
    }
}