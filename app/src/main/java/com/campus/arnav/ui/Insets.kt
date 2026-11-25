package com.campus.arnav.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * targetSdk 35+ 起系统强制 edge-to-edge：内容会画到状态栏 / 导航栏下面。
 * 相机预览铺满是对的，但文字和按钮必须自己让位。
 */
fun View.applySystemBarInsets(onInsets: (top: Int, bottom: Int, left: Int, right: Int) -> Unit) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        onInsets(bars.top, bars.bottom, bars.left, bars.right)
        insets
    }
}