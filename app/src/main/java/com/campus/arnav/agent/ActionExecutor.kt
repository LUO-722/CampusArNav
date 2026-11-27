package com.campus.arnav.agent

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import com.campus.arnav.ArNavActivity
import com.campus.arnav.core.RouteRepository
import com.campus.arnav.data.ActivityDraft
import com.campus.arnav.data.CourseStore
import com.campus.arnav.data.ScheduleLogic
import com.campus.arnav.kb.KbIndex
import org.json.JSONObject
import java.util.Locale

/**
 * Agent 动作执行器。模型返回 actions 数组，这里逐条落地。
 * 所有方法在后台线程调用；只有闹钟 Intent 会切到系统时钟。
 */
object ActionExecutor {

    private const val TAG = "ActionExecutor"

    /**
     * 闹钟两句话术固定在这里：既要告诉用户「失败了」，又要告诉用户**怎么自己补上**。
     * 「闹钟和提醒」是 Android 12+ 的「闹钟与提醒」特殊权限，名字与系统设置里一致，用户能照着找。
     */
    private const val ALARM_DENIED =
        "系统拒绝设置闹钟：请在系统设置里给「科文智嘉」打开「闹钟和提醒」权限，或装最新版"
    private const val ALARM_NO_APP = "这台手机没有系统闹钟应用，请在时钟 App 里手动添加"

    /** 协议键 → 中文名，给用户看的失败提示用；与 AgentEngine.describe 的口径保持一致。 */
    private val ACTION_NAMES = mapOf(
        "add_course" to "加课",
        "add_activity" to "录活动",
        "set_alarm" to "设闹钟",
        "search_kb" to "查知识库",
        "list_schedule" to "查日程",
        "navigate" to "导航",
        "delete_course" to "删课",
        "delete_activity" to "删活动"
    )

    /**
     * 动作执行结果。
     *
     * [ok] 是**事实**（动作到底成没成），[message] 才是给用户看的那句话。
     * 两者必须分开：以前只返回一句话，模型「我已经改好了」这种措辞就能盖住失败，
     * 结果用户在界面上看到的是成功、实际什么都没发生。
     *
     * @param kind 动作类型（`set_alarm` 等），便于调用方按类型补文案
     */
    data class Outcome(val ok: Boolean, val kind: String, val message: String)

    /** 兼容旧调用：只要那句给人看的文案（失败时也返回说明，不抛异常）。 */
    fun execute(ctx: Context, action: JSONObject): String = executeOutcome(ctx, action).message

    /** 执行一个动作，同时把「成没成功」交给调用方，由它决定回复该怎么写。 */
    fun executeOutcome(ctx: Context, action: JSONObject): Outcome {
        val type = action.optString("type").trim()
        return try {
            when (type) {
                "add_course" -> addCourse(ctx, action)
                "add_activity" -> addActivity(ctx, action)
                "set_alarm" -> setAlarm(ctx, action)
                // 只读动作：执行完就算成功，没命中只是「没查到」，不是失败
                "search_kb" -> Outcome(true, type, searchKb(ctx, action))
                "list_schedule" -> Outcome(true, type, listSchedule(ctx, action))
                "navigate" -> navigate(ctx, action)
                "delete_course" -> deleteCourse(ctx, action)
                "delete_activity" -> deleteActivity(ctx, action)
                else -> Outcome(false, type, "未识别的动作：$type")
            }
        } catch (e: Exception) {
            logFailure(type, e)
            Outcome(false, type, humanize(type, e))
        }
    }

    /** 把系统异常翻译成人话。绝不把 e.message 这种英文原文抛给用户。 */
    fun humanize(type: String, e: Throwable): String {
        val name = ACTION_NAMES[type] ?: "这个操作"
        return when (e) {
            is SecurityException ->
                "系统拒绝了「智嘉」执行「$name」的权限，我没法替你完成。请在手机系统设置里允许本应用对应权限后再试，或手动做一次。"
            is android.content.ActivityNotFoundException ->
                "这台设备上没有能处理「$name」的应用，请手动操作一次。"
            is IllegalArgumentException ->
                "「$name」的参数不对，请换一种说法再试。"
            else ->
                "「$name」没成功，可能是系统限制或数据异常；详细原因已写入日志。"
        }
    }

    /** 原始异常只进日志（含堆栈），不进聊天界面。 */
    fun logFailure(type: String, e: Throwable) {
        Log.e(TAG, "action=$type failed: ${e.javaClass.simpleName}", e)
    }

    private fun addCourse(ctx: Context, a: JSONObject): Outcome {
        val name = a.optString("name").ifBlank { a.optString("course") }.trim()
        if (name.isBlank()) return Outcome(false, "add_course", "添加课程失败：没有课程名")
        val wd = a.optInt("weekday", 1).coerceIn(1, 7)
        val st = a.optInt("start_period", a.optInt("start", 1)).coerceIn(1, 12)
        val en = a.optInt("end_period", a.optInt("end", st)).coerceIn(st, 12)
        val weeks = a.optString("weeks", "1-16").ifBlank { "1-16" }
        val location = a.optString("location").trim()
        val teacher = a.optString("teacher").trim()
        val dup = CourseStore.courses(ctx).any {
            it.name == name && it.weekday == wd && it.startPeriod == st && it.endPeriod == en
        }
        if (dup) return Outcome(
            true, "add_course",
            "课程表里已有「$name」：周${ScheduleLogic.weekdayName(wd)} 第 $st-$en 节，未重复添加。"
        )
        CourseStore.addCourse(
            ctx, name, teacher, location, wd, st, en, weeks,
            color = 0xFFF8AECB.toInt(), source = "agent"
        )
        return Outcome(
            true, "add_course",
            "已添加到课程表：$name，周${ScheduleLogic.weekdayName(wd)} 第 $st-$en 节，$weeks 周，$location"
        )
    }

    private fun addActivity(ctx: Context, a: JSONObject): Outcome {
        val title = a.optString("title").ifBlank { "未命名活动" }.trim()
        val rawDate = a.optString("date").trim()
        val date: String
        if (rawDate.isBlank()) {
            date = CourseStore.today()
        } else {
            val normalized = NoticeExtractor.normalizeDate(rawDate)
            val parsed = ScheduleLogic.parseDate(normalized)
                ?: return Outcome(
                    false, "add_activity",
                    "录入活动失败：日期「$rawDate」无法识别，应为 yyyy-MM-dd。"
                )
            date = ScheduleLogic.formatDate(parsed)
        }
        val st = NoticeExtractor.normalizeTime(a.optString("start_time"))
        val en = NoticeExtractor.normalizeTime(a.optString("end_time"))
        val draft = ActivityDraft(
            title = title, date = date, startTime = st, endTime = en,
            location = a.optString("location").trim(), organizer = a.optString("organizer").trim(),
            note = a.optString("note").trim()
        )
        val dup = CourseStore.activities(ctx).any {
            it.title == title && it.date == date && it.startTime == st
        }
        if (dup) return Outcome(true, "add_activity", "活动「$title」$date $st 已在课表里，未重复录入。")
        val wk = ScheduleLogic.weekOf(date)
        val conflicts = if (st.isNotBlank() && en.isNotBlank()) {
            val skips = CourseStore.skipsFor(ctx, date)
            ScheduleLogic.conflicts(CourseStore.courses(ctx), skips, date, st, en)
        } else emptyList()
        CourseStore.addActivity(ctx, draft, conflicts.isNotEmpty(), "agent")
        return if (conflicts.isEmpty()) {
            Outcome(true, "add_activity", "已录入活动：$title，$date $st-$en，${draft.location}")
        } else {
            Outcome(
                true, "add_activity",
                "已录入活动：$title（第 $wk 周）。与 ${conflicts.joinToString("、") { it.name }} 时间冲突，已同时保留并标记冲突。"
            )
        }
    }

    private fun setAlarm(ctx: Context, a: JSONObject): Outcome {
        val h = a.optInt("hour", -1)
        val m = a.optInt("minute", 0).coerceIn(0, 59)
        if (h !in 0..23) return Outcome(false, "set_alarm", "设置闹钟失败：小时不合法")
        val label = a.optString("label", "智嘉提醒")
        val whenText = timeLabel(h, m)
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, h)
            putExtra(AlarmClock.EXTRA_MINUTES, m)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            // false = 打开系统闹钟页让用户确认，不偷懒静默建（静默建失败时用户完全无感）
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            // 非 Activity 的 Context 调 startActivity 必须带 NEW_TASK，否则直接崩；
            // 从 Activity 里调就不加，保证闹钟页返回键能回到 App 自己
            if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(ctx.packageManager) == null) {
            return Outcome(false, "set_alarm", ALARM_NO_APP)
        }
        return try {
            ctx.startActivity(intent)
            // 说「已提交」而不是「已设置好」：真正落库在系统时钟里，App 这边无法确认
            Outcome(true, "set_alarm", "已提交给系统闹钟：$whenText（请在闹钟列表确认）")
        } catch (e: SecurityException) {
            logFailure("set_alarm", e)
            Outcome(false, "set_alarm", ALARM_DENIED)
        } catch (e: ActivityNotFoundException) {
            logFailure("set_alarm", e)
            Outcome(false, "set_alarm", ALARM_NO_APP)
        }
    }

    /** 12 小时制 + 时段前缀，如「早上 9:30」「下午 2:00」，比裸 09:30 更像人话。 */
    private fun timeLabel(h: Int, m: Int): String {
        val ampm = when (h) {
            in 0..4 -> "凌晨"
            in 5..10 -> "早上"
            in 11..12 -> "中午"
            in 13..17 -> "下午"
            else -> "晚上"
        }
        val h12 = when {
            h == 0 -> 12
            h > 12 -> h - 12
            else -> h
        }
        return String.format(Locale.US, "%s %d:%02d", ampm, h12, m)
    }

    private fun searchKb(ctx: Context, a: JSONObject): String {
        val q = a.optString("query").ifBlank { a.optString("q") }
        if (q.isBlank()) return "检索失败：缺少关键词"
        val hits = KbIndex.search(ctx, q, 4)
        if (hits.isEmpty()) return "知识库里没有找到「$q」相关内容。"
        return hits.joinToString("\n") { "· ${it.title}：${it.text.take(160).replace("\n", " ")}" }
    }

    private fun listSchedule(ctx: Context, a: JSONObject): String {
        val rawDate = a.optString("date").ifBlank { CourseStore.today() }
        val date = ScheduleLogic.parseDate(rawDate)?.let { ScheduleLogic.formatDate(it) } ?: CourseStore.today()
        val wd = ScheduleLogic.weekdayOf(date)
        val wk = ScheduleLogic.weekOf(date)
        val skips = CourseStore.skipsFor(ctx, date)
        val courses = CourseStore.courses(ctx).filter {
            it.weekday == wd && ScheduleLogic.weekMatches(it.weeks, wk) && it.id !in skips
        }.sortedBy { it.startPeriod }
        val acts = CourseStore.activitiesOn(ctx, date)
        val sb = StringBuilder("$date（周${ScheduleLogic.weekdayName(wd)}，第 $wk 周）\n")
        if (courses.isEmpty() && acts.isEmpty()) sb.append("没有课，也没有活动。")
        courses.forEach {
            sb.append("· ${it.startPeriod}-${it.endPeriod} 节  ${it.name}  ${it.location}\n")
        }
        acts.forEach {
            sb.append("· ${it.startTime}-${it.endTime}  ${it.title}  ${it.location}\n")
        }
        return sb.toString().trim()
    }

    private fun navigate(ctx: Context, a: JSONObject): Outcome {
        val place = a.optString("place").ifBlank { a.optString("name") }
        if (place.isBlank()) return Outcome(false, "navigate", "导航失败：没有目的地")
        val list = RouteRepository.buildings(ctx)
        val b = list.firstOrNull { it.name.contains(place) || place.contains(it.name) }
            ?: list.firstOrNull { it.name.contains(place.take(2)) }
        return if (b == null) {
            Outcome(
                false, "navigate",
                "没找到「$place」。可导航的地点有：" + list.take(8).joinToString("、") { it.name } + " 等。"
            )
        } else {
            ctx.startActivity(
                Intent(ctx, ArNavActivity::class.java)
                    .putExtra("buildingId", b.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Outcome(true, "navigate", "已打开 ${b.name} 的 AR 步行导航（${b.subtitle}）。")
        }
    }

    private fun deleteCourse(ctx: Context, a: JSONObject): Outcome {
        val name = a.optString("name").trim()
        if (name.isBlank()) return Outcome(false, "delete_course", "删除失败：缺少课程名")
        val hit = CourseStore.courses(ctx).firstOrNull {
            it.name.contains(name) || name.contains(it.name)
        } ?: return Outcome(false, "delete_course", "没有找到课程「$name」")
        CourseStore.deleteCourse(ctx, hit.id)
        return Outcome(true, "delete_course", "已删除课程：${hit.name}")
    }

    private fun deleteActivity(ctx: Context, a: JSONObject): Outcome {
        val title = a.optString("title").trim()
        if (title.isBlank()) return Outcome(false, "delete_activity", "删除活动失败：缺少活动名")
        val rawDate = a.optString("date").trim()
        val date = if (rawDate.isBlank()) "" else {
            ScheduleLogic.parseDate(NoticeExtractor.normalizeDate(rawDate))
                ?.let { ScheduleLogic.formatDate(it) } ?: ""
        }
        val all = CourseStore.activities(ctx)
        val hit = all.firstOrNull {
            (it.title.contains(title) || title.contains(it.title)) &&
                (date.isBlank() || it.date == date)
        } ?: return Outcome(
            false, "delete_activity",
            "没有找到活动「$title」${if (date.isNotBlank()) "（$date）" else ""}"
        )
        CourseStore.deleteActivity(ctx, hit.id)
        return Outcome(
            true, "delete_activity",
            "已删除活动：${hit.title}（${hit.date} ${hit.startTime}-${hit.endTime}）"
        )
    }
}
