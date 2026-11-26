package com.campus.arnav

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.campus.arnav.core.HeadingProvider
import com.campus.arnav.ui.insLightBars
import com.campus.arnav.ui.ArrowOverlayView
import com.campus.arnav.ui.applySystemBarInsets
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * M1 里程碑：只验证「箭头方向对不对」。
 */
class CompassTestActivity : AppCompatActivity(), HeadingProvider.Listener {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: ArrowOverlayView
    private lateinit var heading: HeadingProvider
    private lateinit var cameraExecutor: ExecutorService

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startCamera() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        insLightBars()
        setContentView(R.layout.activity_compass_test)

        previewView = findViewById(R.id.viewFinder)
        overlay = findViewById(R.id.overlay)
        overlay.destinationName = "正北"
        overlay.distanceMeters = 0.0
        overlay.instruction = ""
        overlay.bearingDeg = 0.0

        val panel = findViewById<View>(R.id.bottomPanel)
        val basePad = panel.paddingBottom
        findViewById<View>(R.id.rootCompass).applySystemBarInsets { top, bottom, _, _ ->
            if (overlay.topInsetPx != top) {
                overlay.topInsetPx = top
                overlay.refresh()
            }
            panel.setPadding(panel.paddingLeft, panel.paddingTop, panel.paddingRight, basePad + bottom)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        heading = HeadingProvider(this)
        heading.listener = this
        heading.start()

        findViewById<Button>(R.id.btnN).setOnClickListener { setBearing(0.0, "正北") }
        findViewById<Button>(R.id.btnE).setOnClickListener { setBearing(90.0, "正东") }
        findViewById<Button>(R.id.btnS).setOnClickListener { setBearing(180.0, "正南") }
        findViewById<Button>(R.id.btnW).setOnClickListener { setBearing(270.0, "正西") }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else permLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun setBearing(deg: Double, name: String) {
        overlay.bearingDeg = deg
        overlay.destinationName = name
        overlay.refresh()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                provider.unbindAll()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            } catch (e: Exception) {
                overlay.instruction = "相机启动失败：" + (e.message ?: "")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onHeadingChanged(headingDeg: Float, accuracyDeg: Float) {
        overlay.headingDeg = headingDeg
        overlay.refresh()
        if (System.currentTimeMillis() - lastLog > 1000) {
            lastLog = System.currentTimeMillis()
            Log.d(TAG, String.format(Locale.US, "compass heading=%.1f acc=%.1f", headingDeg, accuracyDeg))
        }
    }

    private var lastLog = 0L

    override fun onDestroy() {
        super.onDestroy()
        heading.stop()
        cameraExecutor.shutdown()
    }

    companion object {
        const val TAG = "CampusArNav"
    }
}