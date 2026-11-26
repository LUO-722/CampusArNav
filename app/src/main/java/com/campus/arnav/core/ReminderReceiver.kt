package com.campus.arnav.core

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.campus.arnav.MainShellActivity
import com.campus.arnav.R

/** 到点发一条通知：课程 / 活动开始前几分钟。点通知直接进课程表。 */
class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_TITLE = "t"
        const val EXTRA_BODY = "b"
        const val EXTRA_CODE = "c"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "智嘉提醒" }
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        Reminder.ensureChannel(ctx)

        val open = Intent(ctx, MainShellActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("reminder", code)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else PendingIntent.FLAG_UPDATE_CURRENT
        val content = PendingIntent.getActivity(ctx, code, open, flags)

        val n = NotificationCompat.Builder(ctx, Reminder.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                if (body.isBlank()) title else "$title\n$body"
            ))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setColor(0xFFE8447A.toInt())
            .setContentIntent(content)
            .build()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        try {
            nm.notify(code, n)
        } catch (_: SecurityException) {
            // 用户没给通知权限，静默跳过
        }
    }
}
