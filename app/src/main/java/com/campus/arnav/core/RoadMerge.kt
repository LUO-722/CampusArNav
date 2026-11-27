package com.campus.arnav.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * 把「走出来的路」（[TrackRecorder] 录的轨迹）并进现有路网，产出**增量**路网文件。
 *
 * 为什么是增量而不是改 assets：`campus_graph.json` 是官方导览图 + 标定算出来的基线，
 * 一旦被用户现场数据改写就没法回滚、也没法再做批量校验。所以测绘结果单独落
 * `filesDir/road_extra.json`，由 CampusGraph 在 `load()` 时合并（lead 接线），
 * 用户点「清除测绘数据」只需删文件，基线永远完整。
 *
 * 坐标：轨迹已经是 **GCJ-02**（[TrackRecorder] 存盘前转过），assets 也是 GCJ-02，
 * 所以这里**不做任何坐标转换**，直接算距离。
 *
 * 合并规则（与 lead 约定）：
 *  1. 每条轨迹先 [TrackRecorder.simplify] 抽稀到 8 米容差 —— 走路抖动被抹平，路口拐点保留。
 *  2. 抽稀后 <2 点，或总长 <10 米 → 记一条 `rejected`（站着录、来回踱步不算路）。
 *  3. 端点（首/末点）吸附：40 米内最近节点就用它，没有就新建节点（`x01`、`x02`…）。
 *  4. 中间点：15 米内已有节点就复用（避免同一条路录两次多出一串平行点），否则新建。
 *  5. 相邻解析结果连成边，`kind = "survey"`；同一对端点（坐标四舍五入到 1e-5 + 米数）只留一条。
 *
 * 幂等：重复 [rebuild] 同一批轨迹不会重复加边（去重键含端点坐标与米数），
 * 但**也不会删掉**之前加过的边 —— 要全清用 [clearExtra]。
 */
object RoadMerge {

    /**
     * @param addedNodes 本次新加的节点数（不含之前就在 road_extra.json 里的）
     * @param addedEdges 本次新加的边数
     * @param snapped 端点/中间点「吸附到已有节点」的次数（含复用）
     * @param rejected 被放弃的轨迹条数（点太少 / 太短 / 读不出来）
     * @param message 给 UI 直接显示的一句话
     */
    data class MergeReport(
        val addedNodes: Int,
        val addedEdges: Int,
        val snapped: Int,
        val rejected: Int,
        val message: String
    )

    private const val ASSET = "campus_graph.json"
    private const val EXTRA_FILE = "road_extra.json"

    /** 首尾点吸附半径：40 米内算是「同一条路的接头」。 */
    private const val SNAP_M = 40.0

    /** 中间点复用半径：15 米内复用同一节点（再小会把两条路并成一条，再大会多出平行边）。 */
    private const val REUSE_M = 15.0

    /** 抽稀容差：8 米（比录制的 4 米宽一倍，专治 GPS 抖动）。 */
    private const val SIMPLIFY_TOL_M = 8.0

    /** 太短的轨迹不要：总长 <10 米基本是原地转圈。 */
    private const val MIN_TRACK_M = 10.0

    private const val COORD_DIGITS = 5 // 1e-5 ≈ 1.1 米，足够做去重键

    /**
     * 重建增量路网：读 assets 基线 + 已有增量 + 所有轨迹，重新算一遍并写回 `road_extra.json`。
     *
     * 内部全程 `runCatching` 兜底：任何 IO/JSON 异常都变成一条「失败了」的报告，不抛给 UI。
     */
    @Synchronized
    fun rebuild(ctx: Context): MergeReport {
        val app = ctx.applicationContext
        return try {
            val base = readBaseNodes(app)
            val extra = readExtra(app)

            // 参与吸附的全部节点：基线 + 已保存的测绘节点；新增节点随时追加，同一条轨迹里后面就能复用
            val nodes = ArrayList<NodeLite>(base.size + extra.nodes.size + 64)
            nodes.addAll(base)
            nodes.addAll(extra.nodes)
            val knownIds = HashSet<String>(nodes.size * 2)
            for (n in nodes) knownIds.add(n.id)

            val existingEdges = HashSet<String>()
            // 基线的边也进去重集合：用户走的路如果正好是已有路，就不该再叠一条 survey 边
            for (e in readBaseEdges(app)) existingEdges.add(edgeKey(e.fromLat, e.fromLon, e.toLat, e.toLon, e.meters))
            for (e in extra.edges) existingEdges.add(edgeKey(e.aLat, e.aLon, e.bLat, e.bLon, e.meters))

            val newNodes = ArrayList<NodeLite>()
            val newEdges = ArrayList<EdgeLite>()
            var snapped = 0
            var rejected = 0

            val tracks = TrackRecorder.list(app)
            if (tracks.isEmpty()) {
                writeExtra(app, extra.nodes, extra.edges)
                return MergeReport(0, 0, 0, 0, "还没有录制任何轨迹：先去走一段路再合并。")
            }

            for (t in tracks) {
                val pts = TrackRecorder.simplify(t.pts, SIMPLIFY_TOL_M)
                val lengthM = polylineMeters(pts)
                if (pts.size < 2 || lengthM < MIN_TRACK_M) {
                    rejected++
                    continue
                }

                var prev: NodeLite? = null
                for ((i, p) in pts.withIndex()) {
                    val isEnd = i == 0 || i == pts.size - 1
                    val radius = if (isEnd) SNAP_M else REUSE_M
                    val near = nearest(nodes, p.lat, p.lon, radius)
                    val node: NodeLite
                    if (near != null) {
                        snapped++
                        node = near
                    } else {
                        val id = nextId(knownIds)
                        node = NodeLite(id, p.lat, p.lon, survey = true)
                        knownIds.add(id)
                        nodes.add(node)
                        newNodes.add(node)
                    }

                    val a = prev
                    if (a != null && a.id != node.id) {
                        val meters = GeoUtils.distanceMeters(a.lat, a.lon, node.lat, node.lon)
                        val key = edgeKey(a.lat, a.lon, node.lat, node.lon, meters)
                        if (existingEdges.add(key)) {
                            newEdges.add(EdgeLite(a.id, node.id, a.lat, a.lon, node.lat, node.lon, meters))
                        }
                    }
                    prev = node
                }
            }

            val allNodes = ArrayList<NodeLite>(extra.nodes.size + newNodes.size)
            allNodes.addAll(extra.nodes)
            allNodes.addAll(newNodes)
            val allEdges = ArrayList<EdgeLite>(extra.edges.size + newEdges.size)
            allEdges.addAll(extra.edges)
            allEdges.addAll(newEdges)
            writeExtra(app, allNodes, allEdges)

            val msg = buildString {
                append("合并 ${tracks.size} 条轨迹：")
                append("新增 ${newNodes.size} 个节点、${newEdges.size} 条测绘边")
                if (snapped > 0) append("；吸附已有路网 $snapped 处")
                if (rejected > 0) append("；跳过 $rejected 条（点太少或太短）")
                append("。")
            }
            MergeReport(newNodes.size, newEdges.size, snapped, rejected, msg)
        } catch (e: Exception) {
            MergeReport(0, 0, 0, 0, "合并失败：读数或写盘出错了，已保留原路网（${e.message ?: e.javaClass.simpleName}）。")
        }
    }

    /** 删掉 `road_extra.json`：路网回到「只有官方基线」的状态。 */
    @Synchronized
    fun clearExtra(ctx: Context) {
        try {
            extraFile(ctx).delete()
        } catch (e: Exception) {
            // 删不掉也不影响使用（load 时会重复合并，但边有去重）
        }
    }

    /** 当前增量路网里有几条边（>0 时 UI 可以显示「已并入 N 条测绘道路」）。 */
    @Synchronized
    fun extraEdgeCount(ctx: Context): Int = readExtra(ctx.applicationContext).edges.size

    // ---------------- 数据结构 ----------------

    private data class NodeLite(val id: String, val lat: Double, val lon: Double, val survey: Boolean)

    private data class EdgeLite(
        val a: String, val b: String,
        val aLat: Double, val aLon: Double,
        val bLat: Double, val bLon: Double,
        val meters: Double
    )

    private class Extra(
        val nodes: List<NodeLite>,
        val edges: List<EdgeLite>
    )

    private data class BaseEdge(
        val from: String, val to: String,
        val fromLat: Double, val fromLon: Double,
        val toLat: Double, val toLon: Double,
        val meters: Double
    )

    // ---------------- 读基线 ----------------

    private fun readBaseNodes(ctx: Context): List<NodeLite> {
        val text = ctx.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONObject(text).optJSONArray("nodes") ?: return emptyList()
        val out = ArrayList<NodeLite>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isEmpty()) continue
            out.add(NodeLite(id, o.optDouble("lat", Double.NaN), o.optDouble("lon", Double.NaN), survey = false))
        }
        return out.filter { it.lat.isFinite() && it.lon.isFinite() }
    }

    private fun readBaseEdges(ctx: Context): List<BaseEdge> {
        val text = ctx.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val doc = JSONObject(text)
        val nodeArr = doc.optJSONArray("nodes") ?: return emptyList()
        val latLon = HashMap<String, Pair<Double, Double>>()
        for (i in 0 until nodeArr.length()) {
            val o = nodeArr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isEmpty()) continue
            latLon[id] = o.optDouble("lat", Double.NaN) to o.optDouble("lon", Double.NaN)
        }
        val arr = doc.optJSONArray("edges") ?: return emptyList()
        val out = ArrayList<BaseEdge>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val a = o.optString("from", "")
            val b = o.optString("to", "")
            val pa = latLon[a] ?: continue
            val pb = latLon[b] ?: continue
            if (!pa.first.isFinite() || !pb.first.isFinite()) continue
            out.add(
                BaseEdge(
                    a, b, pa.first, pa.second, pb.first, pb.second,
                    GeoUtils.distanceMeters(pa.first, pa.second, pb.first, pb.second)
                )
            )
        }
        return out
    }

    // ---------------- 读写增量 ----------------

    private fun extraFile(ctx: Context): File =
        File(ctx.applicationContext.filesDir, EXTRA_FILE)

    /**
     * 读 `road_extra.json`。兼容两种边字段：
     *  - 本类写出的 `from`/`to`（与 assets/campus_graph.json 同构，CampusGraph.parse 直接能读）；
     *  - 任务书里写的 `a`/`b`（别名，这里也认，方便手工造的文件）。
     */
    private fun readExtra(ctx: Context): Extra {
        val f = extraFile(ctx)
        if (!f.exists()) return Extra(emptyList(), emptyList())
        return try {
            val doc = JSONObject(f.readText(Charsets.UTF_8))
            val nodes = ArrayList<NodeLite>()
            doc.optJSONArray("nodes")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id", "")
                    if (id.isEmpty()) continue
                    nodes.add(
                        NodeLite(
                            id,
                            o.optDouble("lat", Double.NaN),
                            o.optDouble("lon", Double.NaN),
                            survey = true
                        )
                    )
                }
            }
            // 端点坐标按 id 回查，便于去重键使用
            val byId = HashMap<String, NodeLite>(nodes.size * 2)
            for (n in nodes) byId[n.id] = n
            val edges = ArrayList<EdgeLite>()
            doc.optJSONArray("edges")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val a = o.optString("from", o.optString("a", ""))
                    val b = o.optString("to", o.optString("b", ""))
                    val na = byId[a] ?: continue
                    val nb = byId[b] ?: continue
                    if (!na.lat.isFinite() || !na.lon.isFinite()) continue
                    if (!nb.lat.isFinite() || !nb.lon.isFinite()) continue
                    edges.add(
                        EdgeLite(
                            a, b, na.lat, na.lon, nb.lat, nb.lon,
                            o.optDouble("meters", GeoUtils.distanceMeters(na.lat, na.lon, nb.lat, nb.lon))
                        )
                    )
                }
            }
            Extra(nodes.filter { it.lat.isFinite() && it.lon.isFinite() }, edges)
        } catch (e: Exception) {
            Extra(emptyList(), emptyList())
        }
    }

    private fun writeExtra(ctx: Context, nodes: List<NodeLite>, edges: List<EdgeLite>) {
        try {
            if (nodes.isEmpty() && edges.isEmpty()) {
                extraFile(ctx).delete()
                return
            }
            val nodeArr = JSONArray()
            for (n in nodes) {
                val o = JSONObject()
                o.put("id", n.id)
                // CampusGraph.parse 用 getString("name")，缺了会抛；测绘点统一叫「测绘点」
                o.put("name", "测绘点")
                o.put("kind", "survey")
                o.put("lat", round(n.lat, 6))
                o.put("lon", round(n.lon, 6))
                nodeArr.put(o)
            }
            val edgeArr = JSONArray()
            for (e in edges) {
                val o = JSONObject()
                o.put("from", e.a)
                o.put("to", e.b)
                // 别名：任务书里的 a/b 写法也带上，接线代码读哪个都能跑
                o.put("a", e.a)
                o.put("b", e.b)
                o.put("kind", "survey")
                o.put("meters", round(e.meters, 1))
                edgeArr.put(o)
            }
            val doc = JSONObject()
            doc.put("schema", "road-extra/1")
            doc.put("crs", "GCJ-02")
            doc.put("nodes", nodeArr)
            doc.put("edges", edgeArr)
            extraFile(ctx).writeText(doc.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            // 磁盘满等：静默，下次 rebuild 再试
        }
    }

    // ---------------- 几何 ----------------

    /** 半径内最近节点；`null` 表示范围内没有。 */
    private fun nearest(nodes: List<NodeLite>, lat: Double, lon: Double, radiusM: Double): NodeLite? {
        var best: NodeLite? = null
        var bestD = radiusM
        for (n in nodes) {
            val d = GeoUtils.distanceMeters(lat, lon, n.lat, n.lon)
            if (d <= bestD) {
                bestD = d
                best = n
            }
        }
        return best
    }

    /** 折线总长（米）。 */
    private fun polylineMeters(pts: List<TrackRecorder.TrackPoint>): Double {
        if (pts.size < 2) return 0.0
        var sum = 0.0
        for (i in 1 until pts.size) {
            sum += GeoUtils.distanceMeters(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon)
        }
        return sum
    }

    /**
     * 边去重键：端点坐标（1e-5 度）+ 米数（1 位小数），**方向无关**（A→B 与 B→A 同一条）。
     * 用坐标而不是 id，是因为「同一段路」可能先被建成 x03-x04、后来又吸附到 g12-x03。
     */
    private fun edgeKey(
        aLat: Double, aLon: Double,
        bLat: Double, bLon: Double,
        meters: Double
    ): String {
        val f = "%.${COORD_DIGITS}f,%.${COORD_DIGITS}f"
        val p = String.format(Locale.US, f, aLat, aLon)
        val q = String.format(Locale.US, f, bLat, bLon)
        val (x, y) = if (p <= q) p to q else q to p
        return x + "|" + y + "|" + String.format(Locale.US, "%.1f", meters)
    }

    /** 分配新节点 id：`x01`、`x02`…，跳过已占用的。 */
    private fun nextId(used: Set<String>): String {
        var i = 1
        while (true) {
            val id = String.format(Locale.US, "x%02d", i)
            if (!used.contains(id)) return id
            i++
        }
    }

    private fun round(v: Double, digits: Int): Double {
        val f = Math.pow(10.0, digits.toDouble())
        return Math.round(v * f) / f
    }
}