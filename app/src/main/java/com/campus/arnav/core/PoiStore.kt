package com.campus.arnav.core

import android.content.Context
import java.util.Locale

/**
 * 现场取点的覆盖存储。
 *
 * 走校园时在「校园取点校准」页点一下楼栋，就把当前 GPS 记成该楼栋的入口坐标；
 * 这里存的坐标优先于 RouteRepository 里的平面图估算值。
 *
 * **坐标系（2026-11 迁移）**：App 内部统一用 GCJ-02，存点时已由 [CoordTransform.wgs2gcj]
 * 从 GPS 的 WGS-84 转过来。老版本没有坐标系字段，那时存的是**原始 WGS-84 值**，
 * 直接当 GCJ 用会偏 ~562 米（方向约 282°，蓝点会跑到校外）。所以：
 *  - 新写入：值末尾带 `|gcj`；
 *  - 旧记录：读取时补上 `|wgs?(legacy)` 并落盘（只做一次），UI 侧可据此提示「建议重新取点」；
 *  - 想清掉旧点：[purgeLegacy]。
 */
object PoiStore {

    private const val PREF = "poi_overrides"

    /** 新写入点的坐标系：与底图 / RouteRepository / 高德 URI 同一口径。 */
    const val SYS_GCJ = "gcj"

    /** 旧版记录：没有坐标系字段，无法确定是不是 WGS-84，只能标成「疑似」。 */
    const val SYS_LEGACY = "wgs?(legacy)"

    /** 一条覆盖点：坐标 + 坐标系标记。 */
    data class SavedPoint(val lat: Double, val lon: Double, val coordSys: String) {
        /** 旧版取点（可能需要重新取点）。 */
        fun isLegacy(): Boolean = coordSys.contains("legacy", ignoreCase = true)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ---- 值编解码 ----
    // 普通点： "lat,lon"（旧） / "lat,lon|gcj"（新）
    // 自定义点："name|lat,lon"（旧） / "name|lat,lon|gcj"（新）

    private fun encode(lat: Double, lon: Double): String =
        String.format(Locale.US, "%.6f,%.6f|%s", lat, lon, SYS_GCJ)

    /** 解析 "lat,lon" 或 "lat,lon|sys"，返回 (坐标, 坐标系标记)。 */
    private fun parsePair(body: String): Pair<Pair<Double, Double>, String>? {
        val bar = body.lastIndexOf('|')
        val coords = if (bar >= 0) body.substring(0, bar) else body
        val sys = if (bar >= 0) body.substring(bar + 1) else ""
        val parts = coords.split(",")
        if (parts.size != 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        return (lat to lon) to sys
    }

    /** 取一个已存条目的坐标系标记；旧数据（没有标记）返回 ""。 */
    private fun tagOf(key: String, value: String): String {
        if (key.startsWith(CUSTOM_PREFIX)) {
            // "name|lat,lon[|sys]"：名字里可能有空格，但第一个 | 一定是分隔符
            val bar = value.indexOf('|')
            if (bar < 0) return ""
            val rest = value.substring(bar + 1)
            val bar2 = rest.lastIndexOf('|')
            return if (bar2 >= 0) rest.substring(bar2 + 1) else ""
        }
        val bar = value.lastIndexOf('|')
        return if (bar >= 0) value.substring(bar + 1) else ""
    }

    /**
     * 把缺坐标系字段的旧记录补成 [SYS_LEGACY]（一次性落盘，幂等）。返回补了几条。
     *
     * 只在读取入口调用；旧版存的是 WGS-84，但也不能排除个别点是旧版就已经是 GCJ，
     * 所以只标「疑似」，不擅自做坐标转换（转换会糊掉真正的偏差，用户重新取点更可靠）。
     */
    fun migrateLegacy(ctx: Context): Int {
        val sp = prefs(ctx)
        var n = 0
        val ed = sp.edit()
        for ((k, v) in sp.all) {
            val s = v as? String ?: continue
            if (tagOf(k, s).isNotEmpty()) continue
            val body = if (k.startsWith(CUSTOM_PREFIX)) {
                val bar = s.indexOf('|')
                if (bar <= 0) continue
                s.substring(bar + 1)
            } else {
                s
            }
            if (parsePair(body) == null) continue
            ed.putString(k, s + "|" + SYS_LEGACY)
            n++
        }
        if (n > 0) ed.apply()
        return n
    }

    /** 还剩多少条旧版取点（UI 可据此决定是否提示「建议重新取点」）。 */
    fun legacyCount(ctx: Context): Int {
        migrateLegacy(ctx)
        val sp = prefs(ctx)
        var n = 0
        for ((k, v) in sp.all) {
            val s = v as? String ?: continue
            if (tagOf(k, s).contains("legacy", ignoreCase = true)) n++
        }
        return n
    }

    /** 每条的坐标系标记；没有该条目返回 null。 */
    fun sysOf(ctx: Context, id: String): String? {
        migrateLegacy(ctx)
        val raw = prefs(ctx).getString(id, null) ?: return null
        return tagOf(id, raw).ifBlank { null }
    }

    /** 被覆盖的楼栋：id -> (lat, lon)。坐标原样返回（新点是 GCJ，旧点可能是 WGS）。 */
    fun saved(ctx: Context): Map<String, Pair<Double, Double>> {
        val tagged = savedTagged(ctx)
        val out = LinkedHashMap<String, Pair<Double, Double>>()
        for ((k, p) in tagged) out[k] = p.lat to p.lon
        return out
    }

    /** 被覆盖的楼栋 + 坐标系标记（[SavedPoint.isLegacy] 判断旧点）。 */
    fun savedTagged(ctx: Context): Map<String, SavedPoint> {
        migrateLegacy(ctx)
        val sp = prefs(ctx)
        val out = LinkedHashMap<String, SavedPoint>()
        for ((k, v) in sp.all) {
            if (k.startsWith(CUSTOM_PREFIX)) continue
            val s = v as? String ?: continue
            val parsed = parsePair(s) ?: continue
            val sys = parsed.second.ifBlank { SYS_LEGACY }
            out[k] = SavedPoint(parsed.first.first, parsed.first.second, sys)
        }
        return out
    }

    /** 存楼栋覆盖点。[lat]/[lon] 必须是 GCJ-02（调用方转换后再传）。 */
    fun save(ctx: Context, id: String, lat: Double, lon: Double) {
        prefs(ctx).edit().putString(id, encode(lat, lon)).apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    /**
     * 清掉旧版取点（坐标系标记含 legacy 的记录）。返回被清理的条数。
     *
     * 供「设置」页调用，清理后提醒用户重新取点即可恢复正常精度。
     */
    fun purgeLegacy(ctx: Context): Int {
        migrateLegacy(ctx)
        val sp = prefs(ctx)
        var n = 0
        val ed = sp.edit()
        for ((k, v) in sp.all) {
            val s = v as? String ?: continue
            if (!tagOf(k, s).contains("legacy", ignoreCase = true)) continue
            ed.remove(k)
            n++
        }
        if (n > 0) ed.apply()
        return n
    }

    // ---- 现场新增的自定义地点（比如"3 号宿舍楼下"），名字和坐标一起存 ----

    private const val CUSTOM_PREFIX = "custom:"

    /** 自定义地点：id -> (名字, lat, lon) */
    fun customPois(ctx: Context): List<Triple<String, String, Pair<Double, Double>>> {
        migrateLegacy(ctx)
        val sp = prefs(ctx)
        val out = ArrayList<Triple<String, String, Pair<Double, Double>>>()
        for ((k, v) in sp.all) {
            if (!k.startsWith(CUSTOM_PREFIX)) continue
            val s = v as? String ?: continue
            val bar = s.indexOf('|')
            if (bar <= 0) continue
            val name = s.substring(0, bar)
            val parsed = parsePair(s.substring(bar + 1)) ?: continue
            out.add(Triple(k.removePrefix(CUSTOM_PREFIX), name, parsed.first))
        }
        return out
    }

    /** 存自定义地点。[lat]/[lon] 必须是 GCJ-02。 */
    fun saveCustom(ctx: Context, id: String, name: String, lat: Double, lon: Double) {
        prefs(ctx).edit().putString(CUSTOM_PREFIX + id, name + "|" + encode(lat, lon)).apply()
    }

    fun removeCustom(ctx: Context, id: String) {
        prefs(ctx).edit().remove(CUSTOM_PREFIX + id).apply()
    }
}