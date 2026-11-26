package com.campus.arnav.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 提供手机朝向（磁北为 0°，顺时针）。
 *
 * 融合策略：
 *  1. 优先用 ROTATION_VECTOR（已经内部融合了加速度计 + 磁力计 + 陀螺仪）
 *  2. 退而求其次用 ACCELEROMETER + MAGNETIC_FIELD 手工算
 *  3. 用「向量低通」而不是「角度低通」平滑，避免 359°/0° 跳变
 *  4. 朝向取「相机轴（设备 -Z）在水平面的投影」，而不是 getOrientation 的 azimuth ——
 *     竖握做 AR 时 azimuth 会退化（R[1]、R[4] 同时趋近 0），箭头方向会乱飘。
 */
class HeadingProvider(context: Context) : SensorEventListener {

    interface Listener {
        fun onHeadingChanged(headingDeg: Float, accuracyDeg: Float)
    }

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magSensor = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val rotationMatrix = FloatArray(9)
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)

    private var haveGravity = false
    private var haveMag = false
    private var initialized = false
    private var vecX = 0f
    private var vecY = 0f

    private var accuracyDeg = 20f
    private var lastEmitNs = 0L

    /** 设备→世界(东-北-天) 旋转矩阵，行主序。把"地上的 3D 箭头"投影到相机画面要用它。 */
    private val pose = FloatArray(9)
    private var poseReady = false

    fun poseMatrix(): FloatArray? = if (poseReady) pose else null

    var headingDeg: Float = Float.NaN
        private set
    var listener: Listener? = null

    /** 平滑系数：越小越稳，越大越跟手 */
    var smoothing = 0.18f

    fun start() {
        rotationSensor?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        if (rotationSensor == null) {
            accelSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            magSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        }
        magSensor?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sm.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                System.arraycopy(rotationMatrix, 0, pose, 0, 9)
                poseReady = true
                applyRaw(GeoUtils.cameraHeadingDeg(pose))
                accuracyDeg = if (event.accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_HIGH) 8f else 18f
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, gravity, 0, 3)
                haveGravity = true
                if (rotationSensor == null) tryFromAccelMag()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, geomagnetic, 0, 3)
                haveMag = true
                accuracyDeg = when (event.accuracy) {
                    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> 8f
                    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> 18f
                    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> 30f
                    else -> 45f
                }
                if (rotationSensor == null) tryFromAccelMag()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            accuracyDeg = when (accuracy) {
                SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> 8f
                SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> 18f
                SensorManager.SENSOR_STATUS_ACCURACY_LOW -> 30f
                else -> 45f
            }
        }
    }

    private fun tryFromAccelMag() {
        if (!haveGravity || !haveMag) return
        if (!SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)) return
        System.arraycopy(rotationMatrix, 0, pose, 0, 9)
        poseReady = true
        applyRaw(GeoUtils.cameraHeadingDeg(pose))
    }

    private fun applyRaw(deg: Double) {
        val vx = cos(Math.toRadians(deg)).toFloat()
        val vy = sin(Math.toRadians(deg)).toFloat()
        if (!initialized) {
            vecX = vx
            vecY = vy
            initialized = true
        } else {
            vecX += smoothing * (vx - vecX)
            vecY += smoothing * (vy - vecY)
        }
        headingDeg = GeoUtils.normalize360(Math.toDegrees(atan2(vecY, vecX).toDouble())).toFloat()
        val now = System.nanoTime()
        if (now - lastEmitNs > 40_000_000L) {
            lastEmitNs = now
            listener?.onHeadingChanged(headingDeg, accuracyDeg)
        }
    }
}
