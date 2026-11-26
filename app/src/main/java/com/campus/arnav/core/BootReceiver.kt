package com.campus.arnav.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机 / 应用被替换后闹钟会丢，这里补排一次。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val a = intent.action ?: return
        if (a == Intent.ACTION_BOOT_COMPLETED ||
            a == Intent.ACTION_MY_PACKAGE_REPLACED ||
            a == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Reminder.scheduleAsync(ctx)
        }
    }
}
