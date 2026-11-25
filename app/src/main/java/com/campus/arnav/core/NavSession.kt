package com.campus.arnav.core

data class NavState(
    val distanceToTarget: Double,
    val bearingToTarget: Double,
    val remaining: Double,
    val instruction: String,
    val lastLeg: Boolean,
    val arrived: Boolean
)

/**
 * 导航状态机：维护当前目标路点，算距离/方位/剩余里程/是否到达。
 */
class NavSession(private val route: NavRoute, private val arriveRadius: Double = 30.0) {

    var index: Int = 0
        private set
    var arrived: Boolean = false
        private set

    val destination: Building get() = route.destination

    fun currentTarget(): Waypoint = route.points[index]

    fun update(lat: Double, lon: Double): NavState {
        val cur0 = currentTarget()
        val d0 = GeoUtils.distanceMeters(lat, lon, cur0.lat, cur0.lon)
        if (!arrived && index < route.points.size - 1 && d0 < 15.0) {
            index++
        }
        val cur = currentTarget()
        val d = GeoUtils.distanceMeters(lat, lon, cur.lat, cur.lon)
        val lastLeg = index == route.points.size - 1
        if (lastLeg && d <= arriveRadius) arrived = true

        var remaining = d
        for (i in index until route.points.size - 1) {
            val a = route.points[i]
            val b = route.points[i + 1]
            remaining += GeoUtils.distanceMeters(a.lat, a.lon, b.lat, b.lon)
        }

        val bearing = GeoUtils.bearingDeg(lat, lon, cur.lat, cur.lon)
        return NavState(d, bearing, remaining, cur.instruction, lastLeg, arrived)
    }
}
