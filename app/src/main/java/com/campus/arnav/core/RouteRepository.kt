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
 * 说明：下面的默认坐标是按《校园平面导览图》按约 1 像素 = 1.1 米换算出来的，
 * 校园中心锚在 (34.345479, 117.374960)，精度大约 ±100 米 —— 足够让箭头指对方向、
 * 距离量级正确，但不足以"走 100 米就到门口"。
 *
 * 想要精确到门口：手机进「③ 校园取点校准」，站在楼门口点一下该楼栋即可，
 * 存下的坐标会覆盖这里的估算值（见 [PoiStore]）。
 */
object RouteRepository {

    const val CAMPUS_NAME = "江苏师范大学科文学院 · 潘安湖校区"

    /** 南门（主校门）：所有演示路线从这里出发。 */
    val gate = Building("gate", "南门 · 主校门", "学院路 1 号", 34.342396, 117.374960)

    private val defaultBuildings = listOf(
        Building("mingzhi", "明志楼", "中轴主楼 · 正门", 34.345430, 117.375139),
        Building("fanghu", "方湖", "校园中心水面", 34.346220, 117.375199),
        Building("fenghua", "风华楼", "方湖北侧", 34.347159, 117.374840),
        Building("canteen1", "第一食堂", "思齐广场西侧", 34.347870, 117.374182),
        Building("canteen2", "第二食堂", "思齐广场东侧", 34.347919, 117.375858),
        Building("shop", "商业街", "第一食堂北侧", 34.348097, 117.374385),
        Building("hospital", "校医院", "校园北侧", 34.348344, 117.373284),
        Building("zhishan", "至善书院", "西北宿舍区", 34.348097, 117.371489),
        Building("chongde_sy", "崇德书院", "西北宿舍区", 34.347801, 117.371369),
        Building("qiuzhen", "求真书院", "东北宿舍区", 34.348097, 117.377653),
        Building("shangmei", "尚美书院", "东北宿舍区", 34.347702, 117.378012),
        Building("hongyi", "弘毅楼", "东侧教学区", 34.346912, 117.377234),
        Building("jinggong", "精工楼", "东侧教学区", 34.346912, 117.379089),
        Building("boxue", "博学楼", "东侧教学区", 34.346220, 117.377592),
        Building("jiangxin", "匠心楼", "东南教学区", 34.346220, 117.379029),
        Building("zhiguang", "致广楼", "西侧教学区", 34.346961, 117.373105),
        Building("gewu", "格物楼", "西侧教学区", 34.346123, 117.373284),
        Building("duxing", "笃行楼", "格物与致广之间", 34.346542, 117.373195),
        Building("mingzhi_lecture", "明志报告厅", "明志楼西南侧", 34.344850, 117.374400),
        Building("chongde_lou", "崇德楼", "南侧行政楼", 34.343602, 117.372985),
        Building("gym", "体育馆", "西南角", 34.343507, 117.369633),
        Building("track", "田径场", "西侧运动区", 34.346162, 117.371372),
        Building("training", "体育训练中心", "西主干道南段", 34.343900, 117.371100),
        Building("engineering", "工程训练中心", "西北 · 体育场东侧", 34.347500, 117.371800),
        Building("west_gate", "西北门", "校园西北角", 34.349000, 117.371500),
        Building("west_side_gate", "西辅门", "校园西侧出口", 34.345200, 117.369400)
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