package com.campus.arnav.core

import java.util.PriorityQueue

/**
 * 一条算好的校内步行路线。
 *
 * @param points 路线的经纬度点串（WGS84，首点可能是当前位置，末点可能是湖面中心）。
 * @param distanceMeters 步行距离，整数米（由经纬度实时算出，含吸附段与湖畔最后一段）。
 * @param steps 中文分步指引，例如「沿中轴路向北直行 251 米」「在明志楼南路口右转，沿星湖路向东直行 141 米」
 *              「到达明志楼（南门）」。
 * @param nearestBuilding 起点所在（或离当前位置最近的）建筑名，用于导航页标题。
 */
class CampusPath(
    val points: List<Pair<Double, Double>>,
    val distanceMeters: Int,
    val steps: List<String>,
    val nearestBuilding: String?
)

/**
 * A* 算路与步态文案生成。图形结构由 [CampusGraph] 提供；这里不碰 Android API，
 * 方便单独推理与复算（`_verify_graph.py` 用同样规则复刻了一遍）。
 */
internal object Navigator {

    private val DIRS8 = arrayOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")

    /** 8 方位。北 = 0°，顺时针。 */
    fun compass(deg: Double): String {
        val idx = ((deg % 360.0 + 360.0) % 360.0 / 45.0 + 0.5).toInt() % 8
        return DIRS8[idx]
    }

    /** 启发式 = 直线距离，实际代价 = 沿边走行距离，两者同一单位（米），所以 A* 最优解与 Dijkstra 相同。 */
    fun path(g: CampusGraph.Graph, start: String, goal: String): List<String>? {
        if (start == goal) return listOf(start)
        val goalNode = g.nodes[goal] ?: return null
        val best = HashMap<String, Double>()
        val came = HashMap<String, String>()
        val closed = HashSet<String>()
        val open = PriorityQueue<Scored>(compareBy<Scored> { it.f }.thenBy { it.g })
        best[start] = 0.0
        open += Scored(0.0, 0.0, start)
        while (open.isNotEmpty()) {
            val cur = open.poll()?.id ?: break
            if (cur == goal) return unwind(came, cur)
            if (!closed.add(cur)) continue
            val base = best[cur] ?: continue
            for (arc in g.arcs[cur] ?: emptyList()) {
                if (arc.to in closed) continue
                val next = base + arc.meters
                if (next >= (best[arc.to] ?: Double.MAX_VALUE) - 1e-9) continue
                best[arc.to] = next
                came[arc.to] = cur
                val n = g.nodes[arc.to] ?: continue
                val h = GeoUtils.distanceMeters(n.lat, n.lon, goalNode.lat, goalNode.lon)
                open += Scored(next + h, next, arc.to)
            }
        }
        return null
    }

    /**
     * 把节点串转成中文指引：先把「同一条路 + 转角 ≤ 30°」的相邻段并成一组，
     * 组与组之间按方位角差判断直行 / 左转 / 右转（±45° 内算直行）。
     */
    fun steps(g: CampusGraph.Graph, ids: List<String>, destName: String, doorSuffix: String?): List<String> {
        val out = ArrayList<String>()
        if (ids.size < 2) {
            out += "到达" + destName + (doorSuffix?.let { "（$it）" } ?: "")
            return out
        }
        class Seg(val a: String, val meters: Double, val bearing: Double, val road: String)

        class Group(val start: String, var meters: Double, val bearing: Double, var lastBearing: Double, val road: String)

        val segs = ArrayList<Seg>()
        for (i in 0 until ids.size - 1) {
            val a = g.nodes[ids[i]] ?: continue
            val b = g.nodes[ids[i + 1]] ?: continue
            segs += Seg(
                ids[i],
                GeoUtils.distanceMeters(a.lat, a.lon, b.lat, b.lon),
                GeoUtils.bearingDeg(a.lat, a.lon, b.lat, b.lon),
                g.arc(ids[i], ids[i + 1])?.road ?: "校园道路"
            )
        }
        val groups = ArrayList<Group>()
        for (s in segs) {
            val last = groups.lastOrNull()
            if (last != null) {
                val turn = GeoUtils.normalize180(s.bearing - last.lastBearing)
                if (last.road == s.road && kotlin.math.abs(turn) <= 30.0) {
                    last.meters += s.meters
                    last.lastBearing = s.bearing
                    continue
                }
            }
            groups += Group(s.a, s.meters, s.bearing, s.bearing, s.road)
        }
        groups.forEachIndexed { i, grp ->
            val word = if (grp.road == "楼前步道" || grp.road == "校园步道") "步行" else "直行"
            val meters = maxOf(1, Math.round(grp.meters).toInt())
            if (i == 0) {
                out += "沿${grp.road}向${compass(grp.bearing)}$word $meters 米"
            } else {
                val prev = groups[i - 1]
                val turn = GeoUtils.normalize180(grp.bearing - prev.lastBearing)
                val here = g.nodes[grp.start]?.name ?: grp.start
                val body = "沿${grp.road}向${compass(grp.bearing)}$word $meters 米"
                out += when {
                    kotlin.math.abs(turn) <= 45.0 -> "在${here}继续${body}"
                    turn > 0 -> "在${here}右转，${body}"
                    else -> "在${here}左转，${body}"
                }
            }
        }
        out += "到达" + destName + (doorSuffix?.let { "（$it）" } ?: "")
        return out
    }

    private class Scored(val f: Double, val g: Double, val id: String)

    private fun unwind(came: Map<String, String>, end: String): List<String> {
        val out = ArrayList<String>()
        var cur = end
        out += cur
        while (true) {
            cur = came[cur] ?: break
            out += cur
        }
        out.reverse()
        return out
    }
}