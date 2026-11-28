package com.campus.arnav.core

import android.content.Context
import java.io.File
import org.json.JSONObject

/**
 * 离线校园路网基线：`assets/campus_graph.json` 里 38 个路口 + 23 个楼门口 + 3 个校门。
 * 楼栋坐标与 [RouteRepository] 一致，路口按官方卫星底图 + 实测方位估算，距离是**步行估算**。
 *
 * 现状：导航算路已改用 [CampusRoads]（沿真实道路走），本类不再参与寻路；保留它是为了
 * 「测绘并入」链路 —— [load] 时把 `filesDir/road_extra.json`（[RoadMerge] 写出的现场
 * 轨迹增量）并进基线，之后再把基线回灌到 assets。`load()` 只解析一次，失败返回 false。
 */
object CampusGraph {

    /** 资产文件名。 */
    const val ASSET = "campus_graph.json"

    /** 吸附上限：离最近路口/楼门口超过这个距离就认为「不在校内」，不算路线。 */
    const val GRAPH_SNAP_LIMIT_M = 300.0

    /** 路网节点。[kind] ∈ junction / building / gate / door / landmark。 */
    class Node(
        val id: String,
        val name: String,
        val lat: Double,
        val lon: Double,
        val kind: String,
        val building: String?,
        val aliases: List<String>
    )

    /** 邻接边（无向边在两边各存一条）。 */
    class Arc(val to: String, val meters: Double, val road: String)

    /** 解析好的图，[CampusGraph] 内部缓存一份。 */
    class Graph(
        val nodes: Map<String, Node>,
        val destinations: List<String>,
        val arcs: Map<String, List<Arc>>
    ) {
        fun arc(from: String, to: String): Arc? = arcs[from]?.firstOrNull { it.to == to }

        /** 可吸附/可作为起终点的外部节点：路口、校门、楼门口（楼体中心与湖面不算）。 */
        fun exterior(id: String): Boolean {
            val n = nodes[id] ?: return false
            return n.kind == "junction" || n.kind == "gate" || n.kind == "door"
        }

        fun destination(id: String): Node? = nodes[id]?.takeIf { destinations.contains(it.id) }
    }

    @Volatile
    private var graph: Graph? = null

    /** 解析 assets。返回 true 表示路网可用。解析失败不会抛异常，只是返回 false。 */
    fun load(ctx: Context): Boolean {
        if (graph != null) return true
        return synchronized(this) {
            if (graph != null) return true
            graph = try {
                parse(ctx)
            } catch (t: Throwable) {
                null
            }
            graph != null
        }
    }

    /** 清空缓存重新解析（测绘并入新路段后调用）。 */
    @Synchronized
    fun reload(ctx: Context): Boolean {
        graph = null
        return load(ctx)
    }

    /** 目的地清单（顺序与 assets 里的 destinations 一致，即 RouteRepository 的顺序）。 */
    fun buildings(): List<String> {
        val g = graph ?: return emptyList()
        return g.destinations.mapNotNull { g.nodes[it]?.name }
    }

    /**
     * 两栋楼（或校门）之间的步行路线；名字支持 RouteRepository 名称与常见别名
     * （「一食堂」「大学生活动中心」「志广楼」…）。找不到名字或图未加载返回 null。
     */
    fun route(fromBuilding: String, toBuilding: String): CampusPath? {
        val g = graph ?: return null
        val a = findDest(g, fromBuilding) ?: return null
        val b = findDest(g, toBuilding) ?: return null
        if (a.id == b.id) {
            return CampusPath(listOf(a.lat to a.lon), 0, listOf("已在${a.name}"), a.name)
        }
        val start = endpoint(g, a) ?: return null
        val goal = endpoint(g, b) ?: return null
        val ids = Navigator.path(g, start.first, goal.first) ?: return null

        var dist = pathMeters(g, ids)
        val points = ArrayList<Pair<Double, Double>>()
        ids.forEach { id -> g.nodes[id]?.let { points += it.lat to it.lon } }
        val steps = Navigator.steps(g, ids, b.name, doorSuffix(g, goal.first)).toMutableList()

        start.second?.let { lake ->
            val leg = GeoUtils.distanceMeters(lake.lat, lake.lon, g.nodes[start.first]?.lat ?: lake.lat,
                g.nodes[start.first]?.lon ?: lake.lon)
            dist += leg
            points.add(0, lake.lat to lake.lon)
            steps.add(0, "从${lake.name}步行 ${legMeters(leg)} 米到${g.nodes[start.first]?.name ?: "步道"}")
        }
        goal.second?.let { lake ->
            val leg = GeoUtils.distanceMeters(g.nodes[goal.first]?.lat ?: lake.lat,
                g.nodes[goal.first]?.lon ?: lake.lon, lake.lat, lake.lon)
            dist += leg
            points += lake.lat to lake.lon
            steps[steps.size - 1] = "步行 ${legMeters(leg)} 米到达${lake.name}"
        }
        return CampusPath(points, Math.round(dist).toInt(), steps, a.name)
    }

    /**
     * 从当前位置（GPS / 用户拖的点）算到某栋楼：先吸附 300 米内最近的
     * 路口 / 校门 / 楼门口，再走 A*；吸附超过 300 米返回 null（说明不在校内）。
     */
    fun routeFromPoint(lat: Double, lon: Double, toBuilding: String): CampusPath? {
        val g = graph ?: return null
        val b = findDest(g, toBuilding) ?: return null
        val snap = nearestExterior(g, lat, lon) ?: return null
        val snapDist = GeoUtils.distanceMeters(lat, lon, snap.lat, snap.lon)
        if (snapDist > GRAPH_SNAP_LIMIT_M) return null
        val goal = endpoint(g, b) ?: return null
        val ids = Navigator.path(g, snap.id, goal.first) ?: return null

        var dist = snapDist + pathMeters(g, ids)
        val points = ArrayList<Pair<Double, Double>>()
        points += lat to lon
        ids.forEach { id -> g.nodes[id]?.let { points += it.lat to it.lon } }
        val steps = Navigator.steps(g, ids, b.name, doorSuffix(g, goal.first)).toMutableList()

        if (snapDist >= 12.0) {
            val brg = GeoUtils.bearingDeg(lat, lon, snap.lat, snap.lon)
            steps.add(0, "从当前位置向${Navigator.compass(brg)}步行 ${legMeters(snapDist)} 米到${snap.name}")
        }
        goal.second?.let { lake ->
            val leg = GeoUtils.distanceMeters(g.nodes[goal.first]?.lat ?: lake.lat,
                g.nodes[goal.first]?.lon ?: lake.lon, lake.lat, lake.lon)
            dist += leg
            points += lake.lat to lake.lon
            steps[steps.size - 1] = "步行 ${legMeters(leg)} 米到达${lake.name}"
        }
        return CampusPath(points, Math.round(dist).toInt(), steps, nearestDest(g, lat, lon)?.name)
    }

    // ---- 调试用（导航页/自检可以拿来核对图是否正常） ----

    fun loaded(): Boolean = graph != null

    fun nodeCount(): Int = graph?.nodes?.size ?: 0

    fun destinationNodes(): List<Node> {
        val g = graph ?: return emptyList()
        return g.destinations.mapNotNull { g.nodes[it] }
    }

    fun nearestNode(lat: Double, lon: Double): Pair<Node, Double>? {
        val g = graph ?: return null
        val n = nearestExterior(g, lat, lon) ?: return null
        return n to GeoUtils.distanceMeters(lat, lon, n.lat, n.lon)
    }

    // ---- 内部 ----

    private fun parse(ctx: Context): Graph {
        val text = ctx.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val doc = JSONObject(text)

        val nodes = LinkedHashMap<String, Node>()
        val rawNodes = doc.getJSONArray("nodes")
        for (i in 0 until rawNodes.length()) {
            val o = rawNodes.getJSONObject(i)
            val aliases = ArrayList<String>()
            o.optJSONArray("aliases")?.let { arr ->
                for (k in 0 until arr.length()) aliases += arr.getString(k)
            }
            val n = Node(
                id = o.getString("id"),
                name = o.getString("name"),
                lat = o.getDouble("lat"),
                lon = o.getDouble("lon"),
                kind = o.optString("kind", "junction"),
                building = if (o.has("building") && !o.isNull("building")) o.getString("building") else null,
                aliases = aliases
            )
            nodes[n.id] = n
        }

        val dests = ArrayList<String>()
        val rawDests = doc.getJSONArray("destinations")
        for (i in 0 until rawDests.length()) dests += rawDests.getString(i)

        val arcs = HashMap<String, MutableList<Arc>>()
        val rawEdges = doc.getJSONArray("edges")
        for (i in 0 until rawEdges.length()) {
            val o = rawEdges.getJSONObject(i)
            val a = nodes[o.getString("from")] ?: continue
            val b = nodes[o.getString("to")] ?: continue
            val meters = GeoUtils.distanceMeters(a.lat, a.lon, b.lat, b.lon)
            val road = o.optString("road", "校园道路")
            arcs.getOrPut(a.id) { ArrayList() } += Arc(b.id, meters, road)
            arcs.getOrPut(b.id) { ArrayList() } += Arc(a.id, meters, road)
        }
        mergeSurvey(ctx, nodes, arcs)
        return Graph(nodes, dests, arcs)
    }

    /**
     * 把「实测测绘」存下的路段并进路网（`filesDir/road_extra.json`，由 [RoadMerge] 写入）。
     * 只加节点与边，不动目的地列表；文件不存在或解析失败都不影响内置路网。
     */
    private fun mergeSurvey(ctx: Context, nodes: LinkedHashMap<String, Node>, arcs: HashMap<String, MutableList<Arc>>) {
        val f = File(ctx.applicationContext.filesDir, "road_extra.json")
        if (!f.exists()) return
        try {
            val doc = JSONObject(f.readText(Charsets.UTF_8))
            val ns = doc.optJSONArray("nodes") ?: return
            for (i in 0 until ns.length()) {
                val o = ns.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                if (id.isEmpty() || nodes.containsKey(id)) continue
                nodes[id] = Node(
                    id = id,
                    name = o.optString("name", "测绘点"),
                    lat = o.getDouble("lat"),
                    lon = o.getDouble("lon"),
                    kind = o.optString("kind", "survey"),
                    building = null,
                    aliases = emptyList()
                )
            }
            val es = doc.optJSONArray("edges") ?: return
            for (i in 0 until es.length()) {
                val o = es.optJSONObject(i) ?: continue
                val a = nodes[o.optString("from", "")] ?: continue
                val b = nodes[o.optString("to", "")] ?: continue
                val meters = GeoUtils.distanceMeters(a.lat, a.lon, b.lat, b.lon)
                arcs.getOrPut(a.id) { ArrayList() } += Arc(b.id, meters, "实测路线")
                arcs.getOrPut(b.id) { ArrayList() } += Arc(a.id, meters, "实测路线")
            }
        } catch (t: Throwable) {
            // 坏文件忽略
        }
    }

    private fun findDest(g: Graph, name: String): Node? {
        val key = name.trim()
        if (key.isEmpty()) return null
        g.destinations.firstNotNullOfOrNull { id -> g.nodes[id]?.takeIf { it.name == key } }?.let { return it }
        g.destinations.firstNotNullOfOrNull { id -> g.nodes[id]?.takeIf { key in it.aliases } }?.let { return it }
        return g.destinations.firstNotNullOfOrNull { id ->
            g.nodes[id]?.takeIf { it.name.contains(key) || key.contains(it.name) }
        }
    }

    /**
     * 起终点取哪个节点：楼 → 自己的楼门口（路径两端就是门口，所以距离不含 20 米楼内走廊）；
     * 校门 → 校门本身；湖面等 landmark → 最近的可走节点 + 返回 landmark 本身用于补最后一段。
     */
    private fun endpoint(g: Graph, dest: Node): Pair<String, Node?>? {
        if (g.exterior(dest.id)) return dest.id to null
        val door = g.nodes[dest.id + "_door"]
        if (door != null && g.exterior(door.id)) return door.id to null
        val near = nearestExterior(g, dest.lat, dest.lon) ?: return null
        return near.id to dest
    }

    private fun nearestExterior(g: Graph, lat: Double, lon: Double): Node? {
        var best: Node? = null
        var bestD = Double.MAX_VALUE
        for (n in g.nodes.values) {
            if (!g.exterior(n.id)) continue
            val d = GeoUtils.distanceMeters(lat, lon, n.lat, n.lon)
            if (d < bestD) {
                best = n
                bestD = d
            }
        }
        return best
    }

    private fun nearestDest(g: Graph, lat: Double, lon: Double): Node? {
        var best: Node? = null
        var bestD = Double.MAX_VALUE
        for (id in g.destinations) {
            val n = g.nodes[id] ?: continue
            val d = GeoUtils.distanceMeters(lat, lon, n.lat, n.lon)
            if (d < bestD) {
                best = n
                bestD = d
            }
        }
        return best
    }

    private fun pathMeters(g: Graph, ids: List<String>): Double {
        var sum = 0.0
        for (i in 0 until ids.size - 1) {
            val a = g.nodes[ids[i]] ?: continue
            val b = g.nodes[ids[i + 1]] ?: continue
            sum += GeoUtils.distanceMeters(a.lat, a.lon, b.lat, b.lon)
        }
        return sum
    }

    private fun doorSuffix(g: Graph, nodeId: String): String? {
        if (!nodeId.endsWith("_door")) return null
        return g.nodes[nodeId]?.name?.substringAfterLast("·")?.trim()
    }

    private fun legMeters(m: Double): Int = maxOf(1, Math.round(m).toInt())
}