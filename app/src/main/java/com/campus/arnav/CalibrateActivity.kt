package com.campus.arnav

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.campus.arnav.core.PoiStore
import com.campus.arnav.core.RouteRepository
import com.campus.arnav.ui.applySystemBarInsets
import java.util.Locale

/**
 * 现场取点：站在楼门口点一下该楼栋，把当前 GPS 记成入口坐标。
 * 存下的坐标会覆盖 [RouteRepository] 里用平面图估算的默认值。
 */
class CalibrateActivity : AppCompatActivity() {

    private lateinit var lm: LocationManager
    private lateinit var statusView: TextView
    private lateinit var listBox: LinearLayout
    private var last: Location? = null
    private val ui = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            refreshStatus()
            ui.postDelayed(this, 1000)
        }
    }

    private val listener = object : android.location.LocationListener {
        override fun onLocationChanged(location: Location) {
            val cur = last
            if (cur == null || location.accuracy <= cur.accuracy) last = location
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(ContextCompat.getColor(this, R.color.bg_dark))
        scroll.clipToPadding = false
        scroll.isFillViewport = true
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)
        scroll.addView(root)
        setContentView(scroll)

        scroll.applySystemBarInsets { top, bottom, _, _ ->
            root.setPadding(pad, pad + top, pad, pad + bottom)
        }

        root.addView(TextView(this).apply {
            text = "校园取点校准"
            textSize = 24f
            setTextColor(ContextCompat.getColor(this@CalibrateActivity, R.color.text_primary))
        })

        root.addView(TextView(this).apply {
            text = "走到楼门口，点一下对应楼栋即可记录。记录值会覆盖平面图估算坐标，误差可到 5–10 米。"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@CalibrateActivity, R.color.text_secondary))
            setPadding(0, (10 * density).toInt(), 0, 0)
        })

        statusView = TextView(this).apply {
            textSize = 15f
            setTextColor(ContextCompat.getColor(this@CalibrateActivity, R.color.accent))
            setPadding(0, (16 * density).toInt(), 0, (6 * density).toInt())
        }
        root.addView(statusView)

        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 101)
        } else {
            startUpdates()
        }

        listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        root.addView(listBox)

        val addBtn = Button(this)
        addBtn.text = "＋ 把当前位置存成新地点"
        addBtn.isAllCaps = false
        addBtn.textSize = 14f
        addBtn.setBackgroundResource(R.drawable.bg_card)
        addBtn.setTextColor(ContextCompat.getColor(this, R.color.accent))
        val addLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (52 * density).toInt()
        )
        addLp.topMargin = (14 * density).toInt()
        addBtn.layoutParams = addLp
        addBtn.setOnClickListener { addCustom() }
        root.addView(addBtn)

        rebuildList()

        val reset = Button(this)
        reset.text = "重置全部取点（回到平面图估算值）"
        reset.isAllCaps = false
        reset.textSize = 14f
        reset.setBackgroundResource(R.drawable.bg_card)
        reset.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (52 * density).toInt()
        )
        lp.topMargin = (14 * density).toInt()
        reset.layoutParams = lp
        reset.setOnClickListener {
            PoiStore.clear(this)
            rebuildList()
            Toast.makeText(this, "已清除全部取点", Toast.LENGTH_SHORT).show()
        }
        root.addView(reset)
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            lm.removeUpdates(listener)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun startUpdates() {
        try {
            last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener)
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, listener)
        } catch (e: SecurityException) {
            Toast.makeText(this, "没有定位权限", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startUpdates()
        }
    }

    private fun refreshStatus() {
        val loc = last
        statusView.text = if (loc == null) {
            "正在定位…（到室外或窗边等一下）"
        } else {
            String.format(
                Locale.US, "当前 GPS：%.6f, %.6f   精度 ±%.0f 米",
                loc.latitude, loc.longitude, loc.accuracy
            )
        }
    }

    private fun rebuildList() {
        listBox.removeAllViews()
        val density = resources.displayMetrics.density
        val saved = PoiStore.saved(this)
        for (b in RouteRepository.buildings(this)) {
            val has = saved.containsKey(b.id)
            val btn = Button(this)
            btn.text = if (has) {
                String.format(Locale.US, "✓ %s   %.6f, %.6f", b.name, b.lat, b.lon)
            } else {
                b.name + "   ·   未取点，点此记录当前位置"
            }
            btn.textSize = 14f
            btn.isAllCaps = false
            btn.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            btn.setBackgroundResource(if (has) R.drawable.bg_card else R.drawable.bg_card)
            btn.setTextColor(
                ContextCompat.getColor(
                    this, if (has) R.color.accent else R.color.text_primary
                )
            )
            btn.setPadding((18 * density).toInt(), 0, (18 * density).toInt(), 0)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (56 * density).toInt()
            )
            lp.topMargin = (8 * density).toInt()
            btn.layoutParams = lp
            btn.setOnClickListener { record(b) }
            btn.setOnLongClickListener {
                if (RouteRepository.isDefaultId(b.id)) {
                    false
                } else {
                    PoiStore.removeCustom(this, b.id)
                    rebuildList()
                    Toast.makeText(this, "已删除 " + b.name, Toast.LENGTH_SHORT).show()
                    true
                }
            }
            listBox.addView(btn)
        }
    }

    private fun addCustom() {
        val loc = last
        if (loc == null) {
            Toast.makeText(this, "还没拿到定位，到室外等一下", Toast.LENGTH_SHORT).show()
            return
        }
        val input = android.widget.EditText(this)
        input.hint = "例如：3 号宿舍楼下 / 图书馆东门"
        input.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        input.setHintTextColor(ContextCompat.getColor(this, R.color.text_secondary))

        android.app.AlertDialog.Builder(this)
            .setTitle(String.format(Locale.US, "存成新地点（当前精度 ±%.0f 米）", loc.accuracy))
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "名字不能为空", Toast.LENGTH_SHORT).show()
                } else {
                    val id = "u" + System.currentTimeMillis()
                    PoiStore.saveCustom(this, id, name, loc.latitude, loc.longitude)
                    rebuildList()
                    Toast.makeText(this, "已保存：$name", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun record(b: com.campus.arnav.core.Building) {
        val loc = last
        if (loc == null) {
            Toast.makeText(this, "还没拿到定位，到室外等一下", Toast.LENGTH_SHORT).show()
            return
        }
        if (loc.accuracy > 60f) {
            Toast.makeText(
                this,
                String.format(Locale.US, "精度只有 ±%.0f 米，走到开阔处再点一次", loc.accuracy),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        PoiStore.save(this, b.id, loc.latitude, loc.longitude)
        rebuildList()
        Toast.makeText(
            this,
            String.format(Locale.US, "已记录 %s：%.6f, %.6f", b.name, loc.latitude, loc.longitude),
            Toast.LENGTH_SHORT
        ).show()
    }
}