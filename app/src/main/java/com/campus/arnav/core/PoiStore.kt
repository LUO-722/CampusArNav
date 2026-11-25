package com.campus.arnav.core

import android.content.Context
import java.util.Locale

/**
 * 现场取点的覆盖存储。
 *
 * 走校园时在「校园取点校准」页点一下楼栋，就把当前 GPS 记成该楼栋的入口坐标；
 * 这里存的坐标优先于 RouteRepository 里的平面图估算值。
 */
object PoiStore {

    private const val PREF = "poi_overrides"

    fun saved(ctx: Context): Map<String, Pair<Double, Double>> {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val out = LinkedHashMap<String, Pair<Double, Double>>()
        for ((k, v) in sp.all) {
            val s = v as? String ?: continue
            val parts = s.split(",")
            if (parts.size == 2) {
                val lat = parts[0].toDoubleOrNull() ?: continue
                val lon = parts[1].toDoubleOrNull() ?: continue
                out[k] = lat to lon
            }
        }
        return out
    }

    fun save(ctx: Context, id: String, lat: Double, lon: Double) {
        val v = String.format(Locale.US, "%.6f,%.6f", lat, lon)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(id, v).apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }

    // ---- 现场新增的自定义地点（比如"3 号宿舍楼下"），名字和坐标一起存 ----

    private const val CUSTOM_PREFIX = "custom:"

    /** 自定义地点：id -> (名字, lat, lon) */
    fun customPois(ctx: Context): List<Triple<String, String, Pair<Double, Double>>> {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val out = ArrayList<Triple<String, String, Pair<Double, Double>>>()
        for ((k, v) in sp.all) {
            if (!k.startsWith(CUSTOM_PREFIX)) continue
            val s = v as? String ?: continue
            val bar = s.indexOf('|')
            if (bar <= 0) continue
            val name = s.substring(0, bar)
            val parts = s.substring(bar + 1).split(",")
            if (parts.size != 2) continue
            val lat = parts[0].toDoubleOrNull() ?: continue
            val lon = parts[1].toDoubleOrNull() ?: continue
            out.add(Triple(k.removePrefix(CUSTOM_PREFIX), name, lat to lon))
        }
        return out
    }

    fun saveCustom(ctx: Context, id: String, name: String, lat: Double, lon: Double) {
        val v = name + "|" + String.format(Locale.US, "%.6f,%.6f", lat, lon)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(CUSTOM_PREFIX + id, v).apply()
    }

    fun removeCustom(ctx: Context, id: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .remove(CUSTOM_PREFIX + id).apply()
    }
}