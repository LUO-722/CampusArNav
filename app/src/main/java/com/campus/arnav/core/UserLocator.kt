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
import kotlin.math.abs

/**
 * 一次可用的定位结果。UI / 导航层只消费这个，不直接碰 [Location]。
 *
 * **坐标基准：GCJ-02**。手机 GPS 原始值是 WGS-84，[UserLocator] 在内部已经用
 * [CoordTransform.wgs2gcj] 转好了，所以这里的 [lat]/[lon] 可以直接和
 * `RouteRepository` 的楼栋坐标、`campus_graph.json`、底图投影混用，**不要再转一次**
 * （二次转换会偏出去 ~562 米）。
 *
 * 注意 [bearing] 的语义：它是「移动方向」（行进方位），不是「手机朝向」。
 * 站着不动时它是 null —— 那时候该用磁力计（见 [UserLocator.headingFusion]），
 * 而不是拿上一次的 GPS 航向硬顶。
 */
data class Fix(
    val lat: Double,
    val lon: Double,
    /** 水平圆概率半径，米。5–15m 是城市/校园的常态；>100m 的已被过滤，不会传出来。 */
    val accuracyMeters: Float,
    /** 行进方位（北 0°，顺时针），静止或无法判定时为 null。 */
    val bearing: Float?,
    val speedMps: Float,
    val atMillis: Long
) {
    fun ageMillis(now: Long = System.currentTimeMillis()): Long = (now - atMillis).coerceAtLeast(0L)

    fun freshEnough(maxAgeMs: Long, now: Long = System.currentTimeMillis()): Boolean =
        ageMillis(now) <= maxAgeMs
}

/**
 * 2D 导航的「我在哪」数据源。
 *
 * 设计取向：**位置用 GPS，朝向能不用磁力计就不用**。
 *  - 位置：GPS_PROVIDER 优先（1s / 3m），没授权 FINE 时退到 NETWORK_PROVIDER；
 *    两个都不可用就回调一个 [NO_FIX_ACCURACY] 的占位 Fix，让 UI 明确走「手动起点」，
 *    而不是假装有定位。
 *  - 朝向：行进中优先用 GPS 航向（钢筋、手机壳、书包拉链都骗不了它）；
 *    静止时才允许短暂信任磁力计（见 [headingFusion]），并且只信 10 秒内的值。
 *  - 所有回调都在主线程（[Handler] 绑主 Looper），UI 直接 setText 也安全。
 *  - **坐标：[Fix] 对外输出 GCJ-02**（与 `RouteRepository` / 底图 / 高德同一口径）。
 *    GPS 芯片给的是 WGS-84，在 [accept] 里用 [CoordTransform.wgs2gcj] 转一次再出门；
 *    消费者（NavTab、ArNavActivity、CalibrateActivity）拿到就是最终坐标，不要再转。
 */
object UserLocator {

    /** 没有可用定位（未授权 / 定位关闭 / 无 Provider）时的精度标记，UI 见到它就切手动起点。 */
    const val NO_FIX_ACCURACY = 500f

    /** 精度超过这个值的点直接丢：校园里 >100m 基本是网络基站粗定位，画在地图上比不画更误导。 */
    private const val MAX_ACCURACY_M = 100f

    private const val MIN_TIME_MS = 300L
    private const val MIN_DISTANCE_M = 0f

    /** 低于这个速度，GPS 的 bearing 字段不可信（散步 1.4m/s，跑步 3m/s）。 */
    private const val BEARING_MIN_SPEED_MPS = 0.8f

    /** 两次 fix 位移超过这个距离，才用大圆方位补一个行进方向。 */
    private const val BEARING_MIN_MOVE_M = 3.0

    /** 跳点判定窗口：窗口内位移超过 [DRIFT_MAX_M] 视为漂移。 */
    private const val DRIFT_WINDOW_MS = 2000L
    private const val DRIFT_MAX_M = 200.0

    /** GPS 航向的保鲜期：超过 15 秒的 bearing 不再代表「我现在朝哪」。 */
    private const val FIX_FRESH_MS = 15_000L

    /** 磁力计只在「刚校准过 / 刚更新过」的 10 秒内被信任。 */
    private const val SENSOR_HEADING_FRESH_MS = 10_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    private var lm: LocationManager? = null
    private var callback: ((Fix) -> Unit)? = null
    private val providers = ArrayList<String>()
    private var started = false
    private var fallbackSent = false

    @Volatile
    private var last: Fix? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            accept(location)
        }
    }

    /**
     * 开始监听。重复调用只会替换回调（不会重复注册）。
     * 回调一定在主线程；首个回调可能是「上次已知位置」，也可能是占位 Fix。
     */
    @Synchronized
    fun start(ctx: Context, onFix: (Fix) -> Unit) {
        callback = onFix
        if (started) {
            last?.let { postFix(it) }
            return
        }
        started = true
        fallbackSent = false
        val app = ctx.applicationContext
        val manager = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        lm = manager
        if (manager == null) {
            emitNoFix("设备没有 LocationManager")
            return
        }

        val fine = ContextCompat.checkSelfPermission(
            app, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            app, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            emitNoFix("未授权定位")
            return
        }

        // 没 FINE 就只有 NETWORK：粗定位（几十~几百米）也比没有强，UI 会按 accuracy 提示
        val wanted = if (fine) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER)
        }
        for (p in wanted) {
            try {
                if (!manager.isProviderEnabled(p)) continue
                manager.requestLocationUpdates(p, MIN_TIME_MS, MIN_DISTANCE_M, listener, mainHandler.looper)
                providers.add(p)
            } catch (_: SecurityException) {
                // 没有定位权限：跳过这个 provider，用剩下的
            } catch (_: Exception) {
                // 注册失败（定位服务异常）：同样跳过，不影响其它 provider
            }
        }

        // 冷启动先给上次已知位置，用户不用站着等 5–20 秒才拿到第一个点
        try {
            val known = providers.asReversed().firstNotNullOfOrNull { p ->
                runCatching { manager.getLastKnownLocation(p) }.getOrNull()
            }
            if (known != null) accept(known)
        } catch (e: SecurityException) {
            // 忽略：拿不到已知位置不影响后续实时回调
        }

        if (providers.isEmpty()) emitNoFix("定位服务不可用")
    }

    /** 停止监听。保留 [lastFix] 供 UI 显示「最后一次位置」，但不再产生新点。 */
    @Synchronized
    fun stop() {
        started = false
        callback = null
        fallbackSent = false
        val manager = lm
        if (manager != null) {
            try {
                manager.removeUpdates(listener)
            } catch (_: SecurityException) {
                // 没权限时本来就没有监听可摘，忽略
            } catch (_: Exception) {
                // 忽略：停止时抛异常不影响下面的状态复位
            }
        }
        providers.clear()
        lm = null
    }

    /** 最近一次可用定位；没有就返回 null。 */
    fun lastFix(): Fix? = last

    /** 这个 Fix 是不是「没有真实定位」的占位（UI 见到就切手动起点）。 */
    fun isNoFix(fix: Fix?): Boolean =
        fix == null || fix.accuracyMeters >= NO_FIX_ACCURACY || (fix.lat == 0.0 && fix.lon == 0.0)

    /** 当前是否有定位权限（FINE 或 COARSE 任一）。UI 可以用它决定要不要弹授权。 */
    fun canLocate(ctx: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /**
     * 朝向融合：只在「有把握」时给值，没把握返回 null，让地图层自己决定
     * （显示「朝向校准中」而不是画一个偏 30° 的箭头）。
     *
     * 1. 有新鲜 GPS 行进航向 → 用它（最可信，不受建筑和手机壳干扰）
     * 2. 静止/慢走，且传感器朝向是 10 秒内的 → 用磁力计值兜底
     * 3. 其他情况 → null
     *
     * @param sensorHeading 磁力计/ROTATION_VECTOR 算出的朝向（北 0°，顺时针），可为 null
     * @param sensorAtMillis 该朝向的采样时间（System.currentTimeMillis 同一时钟）
     */
    fun headingFusion(fix: Fix?, sensorHeading: Float?, sensorAtMillis: Long): Float? {
        if (fix != null) {
            val b = fix.bearing
            if (b != null && fix.freshEnough(FIX_FRESH_MS)) return norm360(b)
        }
        val moving = (fix?.speedMps ?: 0f) > BEARING_MIN_SPEED_MPS
        if (!moving && sensorHeading != null && sensorAtMillis > 0L) {
            val age = System.currentTimeMillis() - sensorAtMillis
            if (age in 0L..SENSOR_HEADING_FRESH_MS) return norm360(sensorHeading)
        }
        return null
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    /** 这里做所有「点可不可信」的判断，UI 不用重复写。 */
    private fun accept(loc: Location) {
        if (!loc.hasAccuracy()) return
        val accuracy = loc.accuracy
        if (accuracy.isNaN() || accuracy > MAX_ACCURACY_M) return

        // GPS 给的是 WGS-84；业务层统一 GCJ-02，这里转一次，下游（蓝点/算路/AR 距离）不用再管。
        val gcj = CoordTransform.wgs2gcj(loc.latitude, loc.longitude)
        val lat = gcj.first
        val lon = gcj.second

        val t = if (loc.time > 0L) loc.time else System.currentTimeMillis()
        val prev = last
        if (prev != null) {
            val dt = abs(t - prev.atMillis)
            val moved = GeoUtils.distanceMeters(prev.lat, prev.lon, lat, lon)
            if (dt < DRIFT_WINDOW_MS && moved > DRIFT_MAX_M) {
                return
            }
        }

        val speed = if (loc.hasSpeed()) loc.speed.coerceAtLeast(0f) else 0f
        val moved = if (prev != null) {
            GeoUtils.distanceMeters(prev.lat, prev.lon, lat, lon)
        } else {
            0.0
        }
        val bearing: Float? = when {
            loc.hasBearing() && speed > BEARING_MIN_SPEED_MPS -> norm360(loc.bearing)
            prev != null && moved > BEARING_MIN_MOVE_M ->
                norm360(GeoUtils.bearingDeg(prev.lat, prev.lon, lat, lon).toFloat())
            else -> null
        }

        val fix = Fix(
            lat = lat,
            lon = lon,
            accuracyMeters = accuracy,
            bearing = bearing,
            speedMps = speed,
            atMillis = t
        )
        last = fix
        postFix(fix)
    }

    private fun emitNoFix(reason: String) {
        if (fallbackSent) return
        fallbackSent = true
        // 没有真正注册监听：把 started 复位，用户去系统里打开定位/授权后再调 start() 还能生效
        started = false
        val fix = Fix(
            lat = 0.0,
            lon = 0.0,
            accuracyMeters = NO_FIX_ACCURACY,
            bearing = null,
            speedMps = 0f,
            atMillis = System.currentTimeMillis()
        )
        last = fix
        postFix(fix)
    }

    /** 回调统一在主线程：LocationManager 可能从 Binder 线程回调。 */
    private fun postFix(fix: Fix) {
        val cb = callback
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cb?.invoke(fix)
        } else {
            mainHandler.post { callback?.invoke(fix) }
        }
    }

    private fun norm360(deg: Float): Float = GeoUtils.normalize360(deg.toDouble()).toFloat()
}