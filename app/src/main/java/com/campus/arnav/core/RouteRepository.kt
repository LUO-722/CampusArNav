package com.campus.arnav.core

import android.content.Context
import kotlin.math.cos
import kotlin.math.sin

data class Building(
    val id: String,
    val name: String,
    val subtitle: String,
    val lat: Double,
    val lon: Double
)

data class Waypoint(
    val lat: Double,
    val lon: Double,
    val instruction: String
)

data class NavRoute(
    val destination: Building,
    val points: List<Waypoint>
)

/**
 * 江苏师范大学科文学院 · 潘安湖校区 目的地数据。
 *
 * 说明：下面的默认坐标是按官方卫星图 `campus_ortho.jpg` 逐楼取点反算的（比例尺按 400 米跑道外圈
 * 两轴定标：东西 2.24 px/m、南北 2.02 px/m，横纵各向异性约 10%；锚点像素 (656, 857.5) 对齐校园中心
 * (34.345479, 117.374960)）；取点误差约 3 像素（≈1.2 米），可见校园约 531×741 米（≈590 亩）。
 *
 * **坐标系：GCJ-02**（高德 / 国内底图口径），与 `assets/campus_graph.json`、底图投影一致。
 * 手机 GPS 原始值是 WGS-84，进 App 必须先经 [CoordTransform.wgs2gcj] 转成 GCJ-02 再用；
 * 这里的坐标本身**不要再转换**（否则会二次偏移 ~560 米）。出口若要给 OSM/谷歌用，
 * 反向用 [CoordTransform.gcj2wgs]。
 *
 * 想要精确到门口：手机进「③ 校园取点校准」，站在楼门口点一下该楼栋即可，
 * 存下的坐标会覆盖这里的估算值（见 [PoiStore]）。
 */
object RouteRepository {

    const val CAMPUS_NAME = "江苏师范大学科文学院 · 潘安湖校区"

    /** 南门（主校门）：所有演示路线从这里出发。 */
    val gate = Building("gate", "南门 · 主校门", "学院路 1 号", 34.342022, 117.373729)

    private val defaultBuildings = listOf(
        Building("mingzhi", "明志楼", "中轴主楼 · 正门", 34.342704, 117.373767),
        Building("fanghu", "方湖", "校园中心水面", 34.344441, 117.373729),
        Building("fenghua", "风华楼", "方湖北侧", 34.345912, 117.373423),
        Building("canteen1", "第一食堂", "思齐广场西侧", 34.346913, 117.372882),
        Building("canteen2", "第二食堂", "思齐广场东侧", 34.346913, 117.373955),
        Building("shop", "商业街", "第一食堂北侧", 34.347339, 117.373395),
        Building("hospital", "校医院", "校园北侧", 34.347304, 117.372789),
        Building("zhishan", "至善书院", "西北宿舍区", 34.347769, 117.370233),
        Building("chongde_sy", "崇德书院", "西北宿舍区", 34.346588, 117.370233),
        Building("qiuzhen", "求真书院", "东北宿舍区", 34.348055, 117.373395),
        Building("shangmei", "尚美书院", "东北宿舍区", 34.346481, 117.373395),
        Building("hongyi", "弘毅楼", "东侧教学区", 34.346138, 117.374357),
        Building("jinggong", "精工楼", "东侧教学区", 34.346138, 117.375376),
        Building("boxue", "博学楼", "东侧教学区", 34.344809, 117.375242),
        Building("jiangxin", "匠心楼", "东南教学区", 34.344145, 117.375484),
        Building("zhiguang", "致广楼", "西侧教学区", 34.345916, 117.371675),
        Building("gewu", "格物楼", "西侧教学区", 34.345252, 117.371675),
        Building("duxing", "笃行楼", "格物与致广之间", 34.345916, 117.372479),
        Building("mingzhi_lecture", "明志报告厅", "明志楼西南侧", 34.342868, 117.372796),
        Building("chongde_lou", "崇德楼", "南侧行政楼", 34.343369, 117.372269),
        Building("gym", "体育馆", "西南角", 34.342930, 117.369979),
        Building("track", "田径场", "西侧运动区", 34.343670, 117.370843),
        Building("training", "体育训练中心", "西主干道南段", 34.342595, 117.372614),
        Building("engineering", "工程训练中心", "西北 · 体育场东侧", 34.347518, 117.370580),
        Building("west_gate", "西北门", "校园西北角", 34.349017, 117.369690),
        Building("west_side_gate", "西辅门", "校园西侧出口", 34.343259, 117.369851)
    )

    /** 估算坐标 + 现场取点覆盖后的最终数据（末尾追加现场新增的自定义地点）。 */
    fun buildings(ctx: Context): List<Building> {
        val ov = PoiStore.saved(ctx)
        val base = defaultBuildings.map { b ->
            val p = ov[b.id]
            if (p == null) b else b.copy(lat = p.first, lon = p.second)
        }
        val custom = PoiStore.customPois(ctx).map { (id, name, ll) ->
            Building(id, name, "现场取点", ll.first, ll.second)
        }
        return base + custom
    }

    fun find(ctx: Context, id: String): Building? = buildings(ctx).firstOrNull { it.id == id }

    fun isDefaultId(id: String): Boolean = defaultBuildings.any { it.id == id }

    /** 取点页用：哪些楼已经取过点。 */
    fun savedIds(ctx: Context): Set<String> = PoiStore.saved(ctx).keys

    /**
     * 生成演示路线：主校门 → 主路中点 → 楼前 45 米 → 楼栋。
     * 真实项目里换成地图 SDK 的步行算路。
     */
    fun routeTo(ctx: Context, id: String): NavRoute {
        val list = buildings(ctx)
        val dest = find(ctx, id) ?: list.first()
        val total = GeoUtils.distanceMeters(gate.lat, gate.lon, dest.lat, dest.lon)
        val brg = GeoUtils.bearingDeg(gate.lat, gate.lon, dest.lat, dest.lon)
        val dirName = compassName(brg)

        val mid = offset(gate.lat, gate.lon, brg, total * 0.5)
        val near = offset(dest.lat, dest.lon, GeoUtils.normalize360(brg + 180.0), 45.0)

        val midText = if (total > 150.0) "沿主路直行" else "直行即到"
        val points = listOf(
            Waypoint(mid.first, mid.second, midText),
            Waypoint(near.first, near.second, "继续前进，留意 " + dest.name),
            Waypoint(dest.lat, dest.lon, "到达 " + dest.name + "，门口在正前方")
        )
        return NavRoute(dest, points)
    }

    private fun compassName(bearing: Double): String {
        val names = arrayOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
        val idx = ((GeoUtils.normalize360(bearing) + 22.5) / 45.0).toInt() % 8
        return names[idx]
    }

    /** 从 (lat, lon) 沿 bearing 方向量 distM 米。 */
    private fun offset(lat: Double, lon: Double, bearing: Double, distM: Double): Pair<Double, Double> {
        val rad = Math.toRadians(bearing)
        val dLat = distM * cos(rad) / 111320.0
        val dLon = distM * sin(rad) / (111320.0 * cos(Math.toRadians(lat)))
        return (lat + dLat) to (lon + dLon)
    }
}