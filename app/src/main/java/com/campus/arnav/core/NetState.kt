package com.campus.arnav.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 粗粒度联网判断。
 *
 * 有网络才谈得上接入云端模型；没有网络时，问答走离线知识库、录活动走本机规则，
 * 这样断网（校园网维护、地铁里）也不会出现「没有 Key 用不了」的死路。
 */
object NetState {

    fun online(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            true
        } else {
            val net = cm.activeNetwork
            val cap = if (net == null) null else cm.getNetworkCapabilities(net)
            if (cap == null) {
                false
            } else {
                cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        }
    } catch (e: Exception) {
        // 判断不了就当他有网，交给请求本身失败兜底，避免误判把云端能力关掉。
        true
    }
}
