package com.campus.arnav

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.campus.arnav.core.CoordTransform
import com.campus.arnav.core.HeadingProvider
import com.campus.arnav.core.NavSession
import com.campus.arnav.core.RouteRepository
import com.campus.arnav.ui.insLightBars
import com.campus.arnav.ui.ArrowOverlayView
import com.campus.arnav.ui.CompassRoseView
import com.campus.arnav.ui.applySystemBarInsets
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.atan
import kotlin.math.tan

class ArNavActivity : AppCompatActivity(), HeadingProvider.Listener {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: ArrowOverlayView
    private lateinit var rose: CompassRoseView
    private lateinit var btn2d: Button
    private lateinit var heading: HeadingProvider
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var locationManager: LocationManager

    private var session: NavSession? = null
    private var camera: Camera? = null
    private var twoDMode = false

    private var lastFixTime = 0L
    private var lastAccuracy = 99f
    private var weakSince = 0L
    private var lastLocLog = 0L
    private var lastHeadingLog = 0L

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { if (hasPermissions()) startAll() }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            lastFixTime = System.currentTimeMillis()
            lastAccuracy = if (loc.hasAccuracy()) loc.accuracy else 50f

            val s = session ?: return
            // GPS 原始值是 WGS-84；楼栋/路线数据是 GCJ-02，必须先转（否则整体偏 ~562 米，AR 永远判不到附近）
            val gcj = CoordTransform.wgs2gcj(loc.latitude, loc.longitude)
            val st = s.update(gcj.first, gcj.second)
            overlay.bearingDeg = st.bearingToTarget
            overlay.distanceMeters = st.distanceToTarget
            overlay.instruction = st.instruction
            overlay.arrival = st.arrived
            overlay.refresh()

            rose.bearingDeg = st.bearingToTarget.toFloat()
            rose.distanceMeters = st.distanceToTarget
            rose.instruction = st.instruction
            rose.refresh()

            updateWeakSignal()

            if (System.currentTimeMillis() - lastLocLog > 1000) {
                lastLocLog = System.currentTimeMillis()
                Log.d(
                    TAG,
                    String.format(
                        Locale.US,
                        "loc provider=%s acc=%.1fm lat=%.6f lon=%.6f (GCJ-02) bearing=%.1f dist=%.1fm arrived=%s",
                        loc.provider, lastAccuracy, gcj.first, gcj.second,
                        st.bearingToTarget, st.distanceToTarget, st.arrived
                    )
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        insLightBars()
        setContentView(R.layout.activity_ar_nav)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.viewFinder)
        overlay = findViewById(R.id.overlay)
        rose = findViewById(R.id.rose2d)
        btn2d = findViewById(R.id.btnToggle2d)

        val id = intent.getStringExtra("buildingId") ?: RouteRepository.buildings(this).first().id
        val route = RouteRepository.routeTo(this, id)
        session = NavSession(route)
        overlay.destinationName = route.destination.name

        val root = findViewById<View>(R.id.rootAr)
        val baseBtnMargin = (84 * resources.displayMetrics.density).toInt()
        root.applySystemBarInsets { top, bottom, _, _ ->
            if (overlay.topInsetPx != top) {
                overlay.topInsetPx = top
                overlay.refresh()
            }
            val lp = btn2d.layoutParams as FrameLayout.LayoutParams
            if (lp.topMargin != baseBtnMargin + top) {
                lp.topMargin = baseBtnMargin + top
                btn2d.layoutParams = lp
            }
            rose.topInsetPx = top
            rose.bottomInsetPx = bottom
            if (twoDMode) rose.refresh()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        heading = HeadingProvider(this)
        heading.listener = this
        heading.start()

        btn2d.setOnClickListener { setTwoD(!twoDMode) }

        if (hasPermissions()) startAll()
        else permLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun hasPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startAll() {
        startCamera()
        startLocation()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                provider.unbindAll()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                updateHorizontalFov()
            } catch (e: Exception) {
                setTwoD(true)
                rose.instruction = "相机不可用，已切 2D"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * 用 CameraCharacteristics 算水平视场角。FOV 猜错的话箭头会系统性偏移，
     * 所以这里实测：sensor 物理宽度 + 焦距 → 视场角，再按 PreviewView 的裁剪修正。
     */
    private fun updateHorizontalFov() {
        val info = camera?.cameraInfo ?: return
        try {
            val c2 = Camera2CameraInfo.from(info)
            val size: android.util.SizeF? =
                c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focals: FloatArray? =
                c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            if (size != null && focals != null && focals.isNotEmpty() && focals[0] > 0f) {
                val sensorW = size.width.toDouble()   // 传感器长边（横向）
                val sensorH = size.height.toDouble()  // 传感器短边
                val focalD = focals[0].toDouble()

                // 竖屏时画面被旋转 90°：传感器的「短边」变成屏幕水平方向。
                // 这是上一版算错的地方——按横屏 widthPixels/heightPixels 去算会得到 29°，实际约 58°。
                val imgAspect = sensorH / sensorW                       // 竖屏图像宽高比 ≈ 0.75
                val hFovPortrait = 2.0 * atan((sensorH / 2.0) / focalD)  // 竖屏图像的水平视场角
                val dm = resources.displayMetrics
                val viewAspect = dm.widthPixels.toDouble() / dm.heightPixels.toDouble()

                // PreviewView FILL_CENTER：谁更宽就裁谁
                val effRad = if (viewAspect <= imgAspect) {
                    hFovPortrait
                } else {
                    2.0 * atan(tan(hFovPortrait / 2.0) * (imgAspect / viewAspect))
                }

                overlay.horizontalFovDeg = Math.toDegrees(effRad)
                overlay.refresh()
                Log.d(
                    TAG,
                    String.format(
                        Locale.US,
                        "camera sensor=%.2fx%.2fmm focal=%.2fmm imgAspect=%.3f viewAspect=%.3f hFovPortrait=%.2f effFov=%.2f",
                        sensorW, sensorH, focalD, imgAspect, viewAspect,
                        Math.toDegrees(hFovPortrait), Math.toDegrees(effRad)
                    )
                )
            }
        } catch (e: Exception) {
            // 保底 62°
        }
    }

    private fun startLocation() {
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000L, 1.0f, locationListener, mainLooper
                )
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 2000L, 3.0f, locationListener, mainLooper
                )
            }
            val last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (last != null) locationListener.onLocationChanged(last)
        } catch (e: SecurityException) {
            // 权限被拒
        }
    }

    private fun updateWeakSignal() {
        val stale = System.currentTimeMillis() - lastFixTime > 6000L
        val weak = stale || lastAccuracy > 30f
        overlay.weakSignal = weak
        if (!weak) {
            weakSince = 0L
            return
        }
        if (weakSince == 0L) weakSince = System.currentTimeMillis()
        if (!twoDMode && System.currentTimeMillis() - weakSince > 8000L) setTwoD(true)
    }

    private fun setTwoD(on: Boolean) {
        twoDMode = on
        previewView.visibility = if (on) View.GONE else View.VISIBLE
        overlay.visibility = if (on) View.GONE else View.VISIBLE
        rose.visibility = if (on) View.VISIBLE else View.GONE
        btn2d.text = if (on) "AR" else "2D"
        if (on) {
            rose.headingDeg = overlay.headingDeg
            rose.topInsetPx = overlay.topInsetPx
            rose.refresh()
        }
    }

    override fun onHeadingChanged(headingDeg: Float, accuracyDeg: Float) {
        overlay.poseMatrix = heading.poseMatrix()
        overlay.headingDeg = headingDeg
        overlay.refresh()
        rose.headingDeg = headingDeg
        if (twoDMode) rose.refresh()
        if (System.currentTimeMillis() - lastHeadingLog > 1000) {
            lastHeadingLog = System.currentTimeMillis()
            Log.d(
                TAG,
                String.format(
                    Locale.US,
                    "heading=%.1f acc=%.1f fov=%.1f rel=%.1f arrowX=%.0f",
                    headingDeg, accuracyDeg, overlay.horizontalFovDeg, overlay.lastRelDeg, overlay.lastArrowX
                )
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        heading.stop()
        try {
            locationManager.removeUpdates(locationListener)
        } catch (e: Exception) {
        }
        cameraExecutor.shutdown()
    }

    companion object {
        const val TAG = "CampusArNav"
    }
}