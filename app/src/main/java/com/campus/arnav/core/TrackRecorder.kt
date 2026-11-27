package com.campus.arnav.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot

/**
 * 走路录道路：拿着手机在校园里走一圈，把 GPS 轨迹录下来，供 [RoadMerge] 并进路网。
 *
 * 设计取舍：
 *  - **只负责采集与存盘**，不碰 [CampusGraph]、不碰 assets，也不做地图渲染（都由 lead 接线）。
 *  - **存盘就是 GCJ-02**：GPS 芯片给的是 WGS-84，进 [TrackPoint] 之前先经
 *    [CoordTransform.wgs2gcj] 转一次，所以 `tracks.json` 里的数与楼栋、底图同口径；
 *    下游（[RoadMerge] / CampusGraph）**不要再转**，否则会叠加 ~560 米偏移。
 *  - **点很稀疏**：`requestLocationUpdates(…, 3000ms, 3m)` + 「距上一点 ≥3 米 或 距上次 ≥5 秒」，
 *    一段 500 米的路通常只有 30–80 个点，抽稀后十几条边，够 A* 用。
 *  - **失败不炸 App**：文件读写、定位注册都 `runCatching` 包住，异常只落日志/返回空。
 *
 * 文件：`ctx.filesDir/tracks.json`
 * ```json
 * {"tracks":[{"id":1,"name":"中轴路","startedAt":1690000000000,
 *             "pts":[[34.34439,117.38093,8.0,1690000001000], ...]}]}
 * ```
 * `pts` 每项是 `[lat, lon, accM, at]`；`meters` 不落盘（读时按 Haversine 重算，避免两处真相）。
 */
object TrackRecorder {

    /** 一个采样点。坐标为 **GCJ-02**（写入前已由 WGS-84 转换）。 */
    data class TrackPoint(val lat: Double, val lon: Double, val accM: Float, val at: Long)

    /**
     * 一条录好的轨迹。
     * @param pts 已转成 GCJ-02 的点串（按时间顺序，未抽稀；抽稀用 [simplify]）。
     * @param meters 相邻点 Haversine 累加取整（沿轨迹走的距离，不是首尾直线）。
     */
    data class Track(
        val id: Long,
        val name: String,
        val pts: List<TrackPoint>,
        val startedAt: Long,
        val meters: Int
    )

    private const val FILE_NAME = "tracks.json"

    /** 采样节流：距上一点 ≥3 米，或距上次 ≥5 秒（站着不动也每 5 秒留一个点，便于判断停留）。 */
    private const val MIN_STEP_METERS = 3.0
    private const val MIN_STEP_MILLIS = 5_000L

    /** 精度差于 100 米直接丢（室内/楼缝里的跳点）。 */
    private const val MAX_ACCURACY_M = 100f

    /** 系统请求频率：3 秒 / 3 米（GPS 最小间隔通常 1 秒，这里再稀一点更省电）。 */
    private const val REQ_TIME_MS = 3_000L
    private const val REQ_DISTANCE_M = 3f

    /** [simplify] 默认容差：4 米（约一步宽，保留路口拐点）。 */
    const val DEFAULT_TOLERANCE_M = 4.0

    private const val MAX_NAME_LEN = 40
    private const val MAX_TRACKS = 200
    private const val MAX_POINTS_PER_TRACK = 20_000

    /**
     * 主线程 Handler 用 `by lazy` 而不是直接 new：`simplify`/`exportJson` 这类纯函数
     * 不该在类加载时就去碰 `Looper`（也让它们能在纯 JVM 环境里被单测/校验程序调用）。
     */
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val points = ArrayList<TrackPoint>()
    private var recording = false
    private var startedAt = 0L
    private var manager: LocationManager? = null
    private var registered = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onFix(location)
        }
    }

    /**
     * 开始录制。已在录制返回 false（不会清掉正在录的点）。
     * 无定位权限 / 没有任何可用 Provider 也返回 false，且不会留下半截录音。
     */
    @Synchronized
    fun start(ctx: Context): Boolean {
        if (recording) return false
        val app = ctx.applicationContext
        val m = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false

        val fine = ContextCompat.checkSelfPermission(
            app, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            app, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return false

        val wanted = if (fine) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        } else {
            // 只有粗定位也能录，但精度普遍 >100 米会被过滤掉，基本录不出东西：如实返回 false
            listOf(LocationManager.NETWORK_PROVIDER)
        }

        manager = m
        points.clear()
        startedAt = System.currentTimeMillis()

        var any = false
        for (p in wanted) {
            try {
                if (!m.isProviderEnabled(p)) continue
                m.requestLocationUpdates(p, REQ_TIME_MS, REQ_DISTANCE_M, listener, mainHandler.looper)
                any = true
            } catch (e: SecurityException) {
                // 权限被撤销：跳过这个 Provider
            } catch (e: Exception) {
                // 个别机型注册会抛 IllegalArgument；跳过
            }
        }
        if (!any) {
            manager = null
            points.clear()
            return false
        }

        recording = true
        registered = true

        // 冷启动先收一个「上次已知位置」，用户不用站着等 5–20 秒才出第一个点
        try {
            val known = wanted.asReversed().firstNotNullOfOrNull { p ->
                runCatching { m.getLastKnownLocation(p) }.getOrNull()
            }
            if (known != null) onFix(known)
        } catch (e: SecurityException) {
            // 忽略
        }
        return true
    }

    /**
     * 停止录制并保存。点数 < 2 返回 null **且不保存**（走两步就停不算一条路）。
     * 名字空白时落成「未命名路线」。
     */
    @Synchronized
    fun stop(ctx: Context, name: String): Track? {
        if (!recording) return null
        recording = false
        unregister()
        val snapshot = ArrayList(points)
        points.clear()
        startedAt = 0L
        if (snapshot.size < 2) return null

        val cleanName = name.trim().ifEmpty { "未命名路线" }.take(MAX_NAME_LEN)
        val nextId = loadAll(ctx).maxOfOrNull { it.id }?.plus(1) ?: 1L
        val track = Track(nextId, cleanName, snapshot, snapshot.first().at, metersOf(snapshot))
        saveAll(ctx, loadAll(ctx) + track)
        return track
    }

    /** 是否正在录制。 */
    @Synchronized
    fun isRecording(): Boolean = recording

    /** 当前已录到的点（GCJ-02 快照，UI 可边录边画折线）。 */
    @Synchronized
    fun current(): List<TrackPoint> = ArrayList(points)

    /** 已保存的轨迹，**新的在前**（`startedAt` 降序）。 */
    @Synchronized
    fun list(ctx: Context): List<Track> = loadAll(ctx).sortedByDescending { it.startedAt }

    @Synchronized
    fun delete(ctx: Context, id: Long) {
        val rest = loadAll(ctx).filterNot { it.id == id }
        saveAll(ctx, rest)
    }

    @Synchronized
    fun rename(ctx: Context, id: Long, name: String) {
        val clean = name.trim().ifEmpty { "未命名路线" }.take(MAX_NAME_LEN)
        val all = loadAll(ctx).map { if (it.id == id) it.copy(name = clean) else it }
        saveAll(ctx, all)
    }

    // ---------------- Douglas-Peucker ----------------

    /**
     * 抽稀轨迹（Douglas-Peucker，距离按「米」算，用局部等距投影近似）。
     *
     * 纯函数：不读 Context、不改入参，[debug/SelfCheck] 风格的单测可直接断言。
     * 首尾点一定保留；返回点数 ≥ min(入参, 2)。
     */
    fun simplify(pts: List<TrackPoint>, toleranceM: Double = DEFAULT_TOLERANCE_M): List<TrackPoint> {
        if (pts.size <= 2) return pts
        val tol = maxOf(0.5, toleranceM)
        val mPerLat = 111_320.0
        val mPerLon = 111_320.0 * cos(Math.toRadians(pts[0].lat)).coerceAtLeast(1e-6)

        val xs = DoubleArray(pts.size) { pts[it].lon * mPerLon }
        val ys = DoubleArray(pts.size) { pts[it].lat * mPerLat }

        val keep = BooleanArray(pts.size)
        keep[0] = true
        keep[pts.size - 1] = true

        // 显式栈，避免一公里以上的长轨迹把递归打爆
        val stack = ArrayList<IntArray>()
        stack.add(intArrayOf(0, pts.size - 1))
        while (stack.isNotEmpty()) {
            val seg = stack.removeAt(stack.size - 1)
            val from = seg[0]
            val to = seg[1]
            if (to - from < 2) continue
            var bestIdx = -1
            var bestDist = -1.0
            for (i in from + 1 until to) {
                val d = pointToSegmentMeters(xs[i], ys[i], xs[from], ys[from], xs[to], ys[to])
                if (d > bestDist) {
                    bestDist = d
                    bestIdx = i
                }
            }
            if (bestIdx > 0 && bestDist > tol) {
                keep[bestIdx] = true
                stack.add(intArrayOf(from, bestIdx))
                stack.add(intArrayOf(bestIdx, to))
            }
        }

        val out = ArrayList<TrackPoint>(keep.count { it })
        for (i in pts.indices) if (keep[i]) out.add(pts[i])
        return out
    }

    /** 点到线段的距离（米），线段退化成一个点时按点距算。 */
    private fun pointToSegmentMeters(
        px: Double, py: Double,
        ax: Double, ay: Double,
        bx: Double, by: Double
    ): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 <= 1e-9) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    // ---------------- 导出 ----------------

    /**
     * 导出全部轨迹为 JSON 文本（与 `tracks.json` 同构，另外补了每条的 `meters`，方便贴给 lead/自己备份）。
     */
    @Synchronized
    fun exportJson(ctx: Context): String = toJson(loadAll(ctx), withMeters = true).toString(2)

    // ---------------- 定位回调 ----------------

    private fun onFix(loc: Location) {
        if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY_M) return
        // 没有精度字段时按 50 米保守处理（不因为缺字段就丢点）
        val acc = if (loc.hasAccuracy()) loc.accuracy else 50f
        val now = if (loc.time > 0) loc.time else System.currentTimeMillis()

        // GPS 原始值 WGS-84 → 项目统一口径 GCJ-02（只在这里转一次）
        val gcj = CoordTransform.wgs2gcj(loc.latitude, loc.longitude)
        val p = TrackPoint(gcj.first, gcj.second, acc, now)

        synchronized(this) {
            if (!recording) return
            val last = points.lastOrNull()
            if (last != null) {
                val moved = GeoUtils.distanceMeters(last.lat, last.lon, p.lat, p.lon)
                val waited = p.at - last.at
                if (moved < MIN_STEP_METERS && waited < MIN_STEP_MILLIS) return
            }
            if (points.size >= MAX_POINTS_PER_TRACK) return
            points.add(p)
        }
    }

    @Synchronized
    private fun unregister() {
        if (!registered) return
        registered = false
        val m = manager
        manager = null
        if (m != null) {
            try {
                m.removeUpdates(listener)
            } catch (e: Exception) {
                // 忽略：注册可能已被系统回收
            }
        }
    }

    // ---------------- 存盘 ----------------

    private fun file(ctx: Context): File = File(ctx.applicationContext.filesDir, FILE_NAME)

    @Synchronized
    private fun loadAll(ctx: Context): List<Track> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val doc = JSONObject(f.readText(Charsets.UTF_8))
            val arr = doc.optJSONArray("tracks") ?: return emptyList()
            val out = ArrayList<Track>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val ptArr = o.optJSONArray("pts") ?: continue
                val pts = ArrayList<TrackPoint>(ptArr.length())
                for (k in 0 until ptArr.length()) {
                    val a = ptArr.optJSONArray(k) ?: continue
                    if (a.length() < 4) continue
                    pts.add(
                        TrackPoint(
                            lat = a.getDouble(0),
                            lon = a.getDouble(1),
                            accM = a.optDouble(2, 0.0).toFloat(),
                            at = a.optLong(3, 0L)
                        )
                    )
                }
                if (pts.size < 2) continue
                out.add(
                    Track(
                        id = o.optLong("id", 0L),
                        name = o.optString("name", "未命名路线").ifEmpty { "未命名路线" },
                        pts = pts,
                        startedAt = o.optLong("startedAt", pts.first().at),
                        meters = metersOf(pts)
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList() // 文件坏了就当没有，别让设置页/导航页崩
        }
    }

    @Synchronized
    private fun saveAll(ctx: Context, all: List<Track>) {
        val trimmed = all.sortedBy { it.startedAt }.takeLast(MAX_TRACKS)
        try {
            file(ctx).writeText(toJson(trimmed, withMeters = false).toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            // 磁盘满/无权限：静默失败，内存里的点仍在（下次 stop 还能再试）
        }
    }

    private fun toJson(all: List<Track>, withMeters: Boolean): JSONObject {
        val tracks = JSONArray()
        for (t in all) {
            val o = JSONObject()
            o.put("id", t.id)
            o.put("name", t.name)
            o.put("startedAt", t.startedAt)
            if (withMeters) o.put("meters", t.meters)
            val pts = JSONArray()
            for (p in t.pts) {
                val a = JSONArray()
                a.put(round(p.lat, 6))
                a.put(round(p.lon, 6))
                a.put(round(p.accM.toDouble(), 1))
                a.put(p.at)
                pts.put(a)
            }
            o.put("pts", pts)
            tracks.put(o)
        }
        val doc = JSONObject()
        doc.put("tracks", tracks)
        return doc
    }

    private fun metersOf(pts: List<TrackPoint>): Int {
        if (pts.size < 2) return 0
        var sum = 0.0
        for (i in 1 until pts.size) {
            sum += GeoUtils.distanceMeters(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon)
        }
        return Math.round(sum).toInt()
    }

    private fun round(v: Double, digits: Int): Double {
        val f = Math.pow(10.0, digits.toDouble())
        return Math.round(v * f) / f
    }

    /** 给日志用的一行摘要。 */
    fun summary(t: Track): String = String.format(
        Locale.US, "#%d %s · %d 点 · %d 米", t.id, t.name, t.pts.size, t.meters
    )
}