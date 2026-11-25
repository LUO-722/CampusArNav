package com.campus.arnav

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.campus.arnav.core.RouteRepository
import com.campus.arnav.ui.applySystemBarInsets

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val content = findViewById<View>(R.id.contentRoot)
        val basePad = (24 * resources.displayMetrics.density).toInt()
        content.applySystemBarInsets { top, bottom, _, _ ->
            content.setPadding(basePad, basePad + top, basePad, basePad + bottom)
        }

        findViewById<Button>(R.id.btnCompassTest).setOnClickListener {
            startActivity(Intent(this, CompassTestActivity::class.java))
        }
        findViewById<Button>(R.id.btnCalibrate).setOnClickListener {
            startActivity(Intent(this, CalibrateActivity::class.java))
        }

        val container = findViewById<LinearLayout>(R.id.containerBuildings)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (56 * resources.displayMetrics.density).toInt()
        )
        lp.topMargin = (10 * resources.displayMetrics.density).toInt()

        for (b in RouteRepository.buildings(this)) {
            val btn = Button(this)
            btn.text = b.name + "  ·  " + b.subtitle
            btn.textSize = 14f
            btn.isAllCaps = false
            btn.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            btn.setBackgroundResource(R.drawable.bg_card)
            btn.setTextColor(getColor(R.color.text_primary))
            btn.setPadding((18 * resources.displayMetrics.density).toInt(), 0, (18 * resources.displayMetrics.density).toInt(), 0)
            btn.layoutParams = lp
            btn.setOnClickListener {
                val i = Intent(this, ArNavActivity::class.java)
                i.putExtra("buildingId", b.id)
                startActivity(i)
            }
            container.addView(btn)
        }
    }
}
