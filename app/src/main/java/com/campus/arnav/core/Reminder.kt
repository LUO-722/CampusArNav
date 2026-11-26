package com.campus.arnav.core

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.campus.arnav.data.Course
import com.campus.arnav.data.CourseStore
import com.campus.arnav.data.ScheduleLogic
import com.campus.arnav.data.SettingsStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 上课 / 活动提醒：把未来两周里每个「课程开始」和「活动开始」都提前 N 分钟（默认 10）排一个闹钟，
 * 到点由 [ReminderReceiver] 发一条通知。纯本机，不联网、不需要登录。
 *
 * 设计取舍：
 *  - 不用 WorkManager（要多一个依赖），直接 AlarmManager + 重新排期；
 *  - 每次进入 App / 改完课表都会重排一遍，先撤掉上一轮排过的，避免改了时间后旧闹钟还在响；
 *  - 精确闹钟在 Android 12+ 需要权限，拿不到就退回非精确（宁可晚几分钟也要能响）。
 */
object Reminder {

    const val CHANNEL_ID = "zhijia_reminder"
    private const val PREF = "zhijia_reminder"
    private const val KEY_CODES = "codes"
    private const val HORIZON_DAYS = 14
    private const val ACTION = "com.campus.arnav.REMIND"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, "上课与活动提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "课程或活动开始前提醒你，例如「10 分钟后上课：高等数学ⅠA」"
            enableVibration(true)
        }
        nm.createNotificationChannel(ch)
    }

    @Volatile
    private var lastAsyncAt = 0L

    /**
     * 后台线程调用：重排未来两周的提醒。
     * [force] = true 时立刻重排（刚录入活动/改课表）；否则 20 秒内只排一次，避免频繁刷界面时反复排期。
     */
    fun scheduleAsync(ctx: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastAsyncAt < 20_000) return
        lastAsyncAt = now
        val app = ctx.applicationContext
        Thread {
            try {
                schedule(app)
            } catch (_: Exception) {
                // 排期失败不影响 App 其它功能
            }
        }.start()
    }

    fun schedule(ctx: Context) {
        ensureChannel(ctx)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val enabled = SettingsStore.remindEnabled(ctx)
        val before = SettingsStore.remindBefore(ctx)
        cancelAll(ctx, am)
        if (!enabled) return

        val now = System.currentTimeMillis()
        val codes = ArrayList<Int>()
        for (offset in 0 until HORIZON_DAYS) {
            val date = dateOfOffset(offset)
            val week = ScheduleLogic.weekOf(date)
            val skips = try { CourseStore.skipsFor(ctx, date) } catch (_: Exception) { emptySet() }

            for (c in try { CourseStore.courses(ctx) } catch (_: Exception) { emptyList<Course>() }) {
                if (c.weekday != ScheduleLogic.weekdayOf(date)) continue
                if (!ScheduleLogic.weekMatches(c.weeks, week)) continue
                if (skips.contains(c.id)) continue          // 当天已停课
                val start = startClock(c.startPeriod) ?: continue
                val trigger = triggerAt(date, start, before) ?: continue
                if (trigger <= now + 30_000) continue
                val body = buildString {
                    append(start)
                    if (c.endPeriod > c.startPeriod) append("—").append(endClock(c.endPeriod).orEmpty())
                    if (c.location.isNotBlank()) append(" · ").append(c.location)
                    append(" · 第 ").append(c.startPeriod)
                    if (c.endPeriod > c.startPeriod) append("-").append(c.endPeriod)
                    append(" 节")
                }
                val code = codeOf("c" + c.id + "@" + date)
                codes.add(code)
                setAlarm(ctx, am, code, trigger, "${before} 分钟后上课：${c.name}", body)
            }

            for (a in try { CourseStore.activitiesOn(ctx, date) } catch (_: Exception) { emptyList() }) {
                if (a.startTime.isBlank()) continue
                val trigger = triggerAt(date, a.startTime, before) ?: continue
                if (trigger <= now + 30_000) continue
                val body = buildString {
                    append(a.startTime)
                    if (a.endTime.isNotBlank()) append("—").append(a.endTime)
                    if (a.location.isNotBlank()) append(" · ").append(a.location)
                }
                val code = codeOf("a" + a.id + "@" + date)
                codes.add(code)
                setAlarm(ctx, am, code, trigger, "${before} 分钟后：${a.title}", body)
            }
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_CODES, codes.map { it.toString() }.toSet()).apply()
    }

    /** 只撤掉上一轮排过的闹钟（不误伤系统里别的提醒）。 */
    private fun cancelAll(ctx: Context, am: AlarmManager) {
        val old = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getStringSet(KEY_CODES, emptySet()) ?: emptySet()
        for (s in old) {
            val code = s.toIntOrNull() ?: continue
            val pi = PendingIntent.getBroadcast(
                ctx, code, intentOf(ctx, ""), PendingIntent.FLAG_NO_CREATE or flags()
            )
            if (pi != null) {
                am.cancel(pi)
                pi.cancel()
            }
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY_CODES).apply()
    }

    private fun flags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        PendingIntent.FLAG_IMMUTABLE
    } else 0

    private fun intentOf(ctx: Context, extra: String): Intent =
        Intent(ctx, ReminderReceiver::class.java).setAction(ACTION).putExtra("k", extra)

    private fun setAlarm(ctx: Context, am: AlarmManager, code: Int, trigger: Long, title: String, body: String) {
        val intent = intentOf(ctx, title).putExtra(ReminderReceiver.EXTRA_TITLE, title)
            .putExtra(ReminderReceiver.EXTRA_BODY, body).putExtra(ReminderReceiver.EXTRA_CODE, code)
        val pi = PendingIntent.getBroadcast(
            ctx, code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or flags()
        )
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        }
    }

    private fun codeOf(key: String): Int = key.hashCode() and 0x0FFFFFFF

    private fun startClock(period: Int): String? {
        val range = ScheduleLogic.periodTime(period, "")
        val start = range.substringBefore("-").trim()
        return start.ifBlank { null }
    }

    private fun endClock(period: Int): String? {
        val range = ScheduleLogic.periodTime(period, "")
        val end = range.substringAfter("-", "").trim()
        return end.ifBlank { null }
    }

    private fun dateOfOffset(offset: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, offset)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    }

    /** 把「日期 + HH:mm」换成毫秒；时间不合法返回 null。 */
    private fun triggerAt(date: String, hhmm: String, beforeMinutes: Int): Long? {
        if (!ScheduleLogic.validClock(hhmm)) return null
        val d = ScheduleLogic.parseDate(date) ?: return null
        val parts = hhmm.split(":")
        val cal = Calendar.getInstance()
        cal.time = Date(d.time)
        cal.set(Calendar.HOUR_OF_DAY, parts[0].toInt())
        cal.set(Calendar.MINUTE, parts[1].toInt())
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis - beforeMinutes * 60_000L
    }
}
