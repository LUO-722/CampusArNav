package com.campus.arnav.core

import android.content.Context
import org.json.JSONObject
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min

/**
 * 真实路网（`assets/campus_roads.json`）：节点是沿着卫星图上**看得见的道路**每 8 米采一个点，
 * 交叉口自动连通，共 1200+ 节点；楼栋通过 `entrance` 表吸附到最近的路口节点。
 *
 * 与旧 [CampusGraph] 的区别：这里的路线**沿着真正的道路走**，不会斜穿草坪与楼体。
 * 坐标系 GCJ-02，与 [RouteRepository]、底图完全一致。
 *
 * 用法：
 * ```
 * if (CampusRoads.load(ctx)) {
 *     val p = CampusRoads.route("南门", "明志楼")
 *     val q = CampusRoads.routeFromPoint(gpsLat, gpsLon, "博学楼")
 * }
 * ```
 */
object CampusRoads {

    const val ASSET = "campus_roads.json"

    /** 吸附上限：离最近道路节点超过这个距离就认为不在校内。 */
    const val SNAP_LIMIT_METERS = 400.0

    private var latA = DoubleArray(0)
    private var lonA = DoubleArray(0)
    private var adj: Array<IntArray> = emptyArray()
    private var adjM: Array<DoubleArray> = emptyArray()
    private var entrance: Map<String, Int> = emptyMap()

    @Volatile
    private var loaded = false

    /** 解析 assets；失败返回 false，不抛异常。 */
    fun load(ctx: Context): Boolean {
        if (loaded) return true
        synchronized(this) {
            if (loaded) return true
            loaded = try {
                parse(ctx)
                true
            } catch (e: Throwable) {
                false
            }
        }
        return loaded
    }

    fun reload(ctx: Context): Boolean {
        synchronized(this) {
            loaded = false
            return load(ctx)
        }
    }

    private fun parse(ctx: Context) {
        val txt = ctx.assets.open(ASSET).bufferedReader().use { it.readText() }
        val o = JSONObject(txt)
        val arr = o.getJSONArray("nodes")
        val n = arr.length()
        latA = DoubleArray(n)
        lonA = DoubleArray(n)
        for (i in 0 until n) {
            val p = arr.getJSONArray(i)
            latA[i] = p.getDouble(0)
            lonA[i] = p.getDouble(1)
        }
        val es = o.getJSONArray("edges")
        val fromL = IntArray(es.length())
        val toL = IntArray(es.length())
        val mL = DoubleArray(es.length())
        val deg = IntArray(n)
        for (i in 0 until es.length()) {
            val e = es.getJSONArray(i)
            fromL[i] = e.getInt(0)
            toL[i] = e.getInt(1)
            mL[i] = e.getDouble(2)
            deg[fromL[i]]++
            deg[toL[i]]++
        }
        adj = Array(n) { IntArray(deg[it]) }
        adjM = Array(n) { DoubleArray(deg[it]) }
        val fill = IntArray(n)
        for (i in 0 until es.length()) {
            val a = fromL[i]
            val b = toL[i]
            adj[a][fill[a]] = b
            adjM[a][fill[a]] = mL[i]
            fill[a]++
            adj[b][fill[b]] = a
            adjM[b][fill[b]] = mL[i]
            fill[b]++
        }
        val ent = o.optJSONObject("entrance") ?: JSONObject()
        val m = HashMap<String, Int>()
        val keys = ent.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            m[k] = ent.optInt(k, -1)
        }
        entrance = m
    }

    /** 可导航的目的地名（与 [RouteRepository] 一致）。 */
    fun buildings(ctx: Context): List<String> = RouteRepository.buildings(ctx).map { it.name }

    private fun meters(a: Int, b: Int): Double {
        val dLat = (latA[a] - latA[b]) * 111132.0
        val dLon = (lonA[a] - lonA[b]) * 111320.0 * cos(Math.toRadians(latA[a]))
        return hypot(dLat, dLon)
    }

    /** 名称 → 楼栋 id（含常用别名）。 */
    private fun resolve(ctx: Context, name: String): Building? {
        val list = RouteRepository.buildings(ctx)
        list.firstOrNull { it.name == name || it.id == name }?.let { return it }
        val alias = when (name.trim()) {
            "南门", "主校门", "正门", "大门" -> "gate"
            "图书馆", "图文信息中心" -> "mingzhi"
            "活动中心", "大学生活动中心", "风华楼" -> "fenghua"
            "行政楼" -> "chongde_lou"
            "一食堂", "第一食堂" -> "canteen1"
            "二食堂", "第二食堂" -> "canteen2"
            "操场", "运动场", "体育场" -> "track"
            "实训中心" -> "engineering"
            "门诊部" -> "hospital"
            else -> null
        }
        if (alias != null) {
            list.firstOrNull { it.id == alias }?.let { return it }
            if (alias == "gate") return RouteRepository.gate
        }
        return null
    }

    private fun nodeOf(b: Building): Int {
        entrance[b.id]?.let { if (it in latA.indices) return it }
        var best = -1
        var bestD = Double.MAX_VALUE
        for (i in latA.indices) {
            val d = meters2(b.lat, b.lon, i)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    private fun meters2(lat: Double, lon: Double, i: Int): Double {
        val dLat = (lat - latA[i]) * 111132.0
        val dLon = (lon - lonA[i]) * 111320.0 * cos(Math.toRadians(lat))
        return hypot(dLat, dLon)
    }

    private fun nearestNode(lat: Double, lon: Double): Int {
        var best = -1
        var bestD = Double.MAX_VALUE
        for (i in latA.indices) {
            val d = meters2(lat, lon, i)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    /** A* 最短路：返回节点序列，失败返回 null。 */
    private fun astar(start: Int, goal: Int): IntArray? {
        if (start < 0 || goal < 0 || latA.isEmpty()) return null
        val n = latA.size
        val g = DoubleArray(n) { Double.MAX_VALUE }
        val prev = IntArray(n) { -1 }
        val done = BooleanArray(n)
        g[start] = 0.0
        val pq = PriorityQueue<Long>()
        pq.add((0L shl 20) or start.toLong())
        while (pq.isNotEmpty()) {
            val cur = (pq.poll() and 0xFFFFF).toInt()
            if (done[cur]) continue
            done[cur] = true
            if (cur == goal) break
            val nb = adj[cur]
            for (k in nb.indices) {
                val nx = nb[k]
                if (done[nx]) continue
                val ng = g[cur] + adjM[cur][k]
                if (ng < g[nx] - 1e-9) {
                    g[nx] = ng
                    prev[nx] = cur
                    val f = ng + meters(nx, goal)
                    pq.add((f * 16.0).toLong().coerceAtLeast(0L) shl 20 or nx.toLong())
                }
            }
        }
        if (g[goal] == Double.MAX_VALUE) return null
        val out = ArrayList<Int>(64)
        var c = goal
        while (c != -1) {
            out.add(c)
            c = prev[c]
        }
        out.reverse()
        return out.toIntArray()
    }

    private fun pathOf(ids: IntArray, destName: String): CampusPath {
        val pts = ArrayList<Pair<Double, Double>>(ids.size + 2)
        var dist = 0.0
        for (i in ids.indices) {
            pts.add(latA[ids[i]] to lonA[ids[i]])
            if (i > 0) dist += meters(ids[i - 1], ids[i])
        }
        val steps = buildSteps(ids, dist, destName)
        return CampusPath(pts, dist.toInt(), steps, null)
    }

    /** 由节点序列生成「直行/左转/右转 + 距离」的分段提示。 */
    private fun buildSteps(ids: IntArray, total: Double, destName: String): List<String> {
        if (ids.size < 2) return listOf("已经在 $destName 附近")
        val out = ArrayList<String>()
        var segStart = 0
        var segDist = 0.0
        var turn = "出发"
        var lastBearing = bearing(ids[0], min(1, ids.size - 1))
        for (i in 1 until ids.size) {
            segDist += meters(ids[i - 1], ids[i])
            val isLast = i == ids.size - 1
            var nextTurn: String? = null
            if (!isLast) {
                val b2 = bearing(ids[i], i + 1)
                var delta = b2 - lastBearing
                while (delta > 180) delta -= 360.0
                while (delta < -180) delta += 360.0
                if (abs(delta) > 40 && segDist > 25) {
                    nextTurn = when {
                        delta > 0 -> "右转"
                        else -> "左转"
                    }
                    lastBearing = b2
                }
            }
            if (nextTurn != null || isLast) {
                val prefix = if (out.isEmpty() && turn == "出发") "从起点出发" else turn
                out.add("$prefix，步行 ${segDist.toInt().coerceAtLeast(1)} 米")
                if (nextTurn != null) {
                    turn = nextTurn
                    segStart = i
                }
                segDist = 0.0
                if (out.size >= 5 && !isLast) {
                    out.add("……")
                    break
                }
            }
        }
        out.add("到达 $destName")
        return out
    }

    private fun bearing(a: Int, b: Int): Double {
        val dLat = (latA[b] - latA[a]) * 111132.0
        val dLon = (lonA[b] - lonA[a]) * 111320.0 * cos(Math.toRadians(latA[a]))
        return Math.toDegrees(atan2(dLon, dLat))
    }

    /** 楼到楼算路（名称可为 id 或别名）。 */
    fun route(ctx: Context, from: String, to: String): CampusPath? {
        if (!load(ctx)) return null
        val a = resolve(ctx, from) ?: return null
        val b = resolve(ctx, to) ?: return null
        val ids = astar(nodeOf(a), nodeOf(b)) ?: return null
        return pathOf(ids, b.name)
    }

    /** 从任意定位点算到某栋楼：先走到最近的路，再沿路网算。 */
    fun routeFromPoint(ctx: Context, lat: Double, lon: Double, to: String): CampusPath? {
        if (!load(ctx)) return null
        val b = resolve(ctx, to) ?: return null
        val near = nearestNode(lat, lon)
        if (near < 0) return null
        if (meters2(lat, lon, near) > SNAP_LIMIT_METERS) return null
        val ids = astar(near, nodeOf(b)) ?: return null
        val p = pathOf(ids, b.name)
        val pts = ArrayList<Pair<Double, Double>>(p.points.size + 1)
        pts.add(lat to lon)
        pts.addAll(p.points)
        val extra = GeoUtils.distanceMeters(lat, lon, latA[near], lonA[near])
        return CampusPath(pts, p.distanceMeters + extra.toInt(), p.steps, null)
    }
}