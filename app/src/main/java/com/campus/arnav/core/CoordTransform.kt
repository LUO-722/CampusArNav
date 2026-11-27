package com.campus.arnav.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS-84 ↔ GCJ-02（"火星坐标"）互转。
 *
 * 约定：**App 内部只用一个基准 —— GCJ-02**。
 *  - 进入：手机 GPS（LocationManager 给的是 WGS-84）→ [wgs2gcj] 转一次再进业务层；
 *  - 出口：只有明确要 WGS 的消费方（导出 GPX、喂 OSM/谷歌等）→ [gcj2wgs]；
 *  - 楼栋数据、`campus_graph.json`、底图投影、高德 URI（`coordinate=gaode`）**一律不再转换**。
 *
 * 为什么不统一用 WGS-84：校园底图与楼栋坐标是按国内底图口径（GCJ-02）标的，
 * 国内地图服务接收的也是 GCJ-02；而 GPS 芯片吐的是 WGS-84。两者在本地差 ~562 米，
 * 不转换会让蓝点、AR 距离、「到楼 30 米」判定全部失效。
 *
 * 算法为公开的 coordtransform 实现（克拉索夫斯基椭球加偏 / 迭代反解），误差 < 1 m。
 * 纯函数、无 Android 依赖，可直接被自检/单测调用。
 */
object CoordTransform {

    private const val PI = Math.PI

    /** 克拉索夫斯基椭球长半轴。 */
    private const val A = 6378245.0

    /** 第一偏心率平方。 */
    private const val EE = 0.00669342162296594323

    /** 中国大陆之外不做偏移（港澳台/境外本来就是 WGS-84）。 */
    fun outOfChina(lat: Double, lon: Double): Boolean =
        lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        r += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        r += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        r += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return r
    }

    private fun transformLon(x: Double, y: Double): Double {
        var r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        r += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        r += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        r += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return r
    }

    /**
     * WGS-84 → GCJ-02。GPS 定位、OSM/谷歌坐标进入 App 时调用一次。
     *
     * 校园锚点校验：`wgs2gcj(34.345479, 117.374960)` ≈ `34.344399, 117.380937`（位移 ~562 m）。
     */
    fun wgs2gcj(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) return lat to lon
        var dLat = transformLat(lon - 105.0, lat - 35.0)
        var dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLon = (dLon * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return (lat + dLat) to (lon + dLon)
    }

    /**
     * GCJ-02 → WGS-84，迭代 3 次反向逼近，往返误差 < 1 m。
     *
     * 校园锚点校验：`gcj2wgs(34.345479, 117.374960)` ≈ `34.346566, 117.368993`。
     */
    fun gcj2wgs(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) return lat to lon
        var wLat = lat
        var wLon = lon
        repeat(3) {
            val g = wgs2gcj(wLat, wLon)
            wLat -= g.first - lat
            wLon -= g.second - lon
        }
        return wLat to wLon
    }
}