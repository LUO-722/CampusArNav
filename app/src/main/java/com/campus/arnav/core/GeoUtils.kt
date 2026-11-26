package com.campus.arnav.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object GeoUtils {

    private const val EARTH_R = 6371000.0

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * EARTH_R * asin(minOf(1.0, sqrt(a)))
    }

    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return normalize360(Math.toDegrees(atan2(y, x)))
    }

    /**
     * 由「设备→世界(ENU)」旋转矩阵求相机（设备 -Z 轴）在水平面上的方位角（北 0°，顺时针）。
     *
     * 为什么不用 SensorManager.getOrientation 的 azimuth：
     * 竖握手机做 AR 时设备 Y 轴接近世界天顶，R[1] 与 R[4] 同时趋近 0，azimuth 算法退化，
     * 读数会在 0 附近乱跳，箭头方向随之漂移。直接投影相机轴才是稳定解。
     *
     * 行主序 r[9]：r[2] = R02（设备 Z 轴在东向的分量），r[5] = R12（设备 Z 轴在北向的分量）。
     * 相机看向 -Z，所以相机水平方向 = (-R02, -R12)。
     */
    fun cameraHeadingDeg(r: FloatArray): Double {
        if (r.size < 9) return 0.0
        val east = -r[2].toDouble()
        val north = -r[5].toDouble()
        val horizontal = hypot(east, north)
        if (horizontal > 0.25) return normalize360(Math.toDegrees(atan2(east, north)))
        // 手机接近平放时相机朝天，退化到「屏幕顶部朝向」，此时 azimuth 公式仍有定义
        return normalize360(Math.toDegrees(atan2(r[1].toDouble(), r[4].toDouble())))
    }

    fun normalize180(deg: Double): Double = (deg + 540.0) % 360.0 - 180.0

    fun normalize360(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }
}
