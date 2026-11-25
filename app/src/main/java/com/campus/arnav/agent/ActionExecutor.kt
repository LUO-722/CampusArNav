package com.campus.arnav.agent

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
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

    fun execute(ctx: Context, action: JSONObject): String {
        val type = action.optString("type").trim()
        return try {
            when (type) {
                "add_course" -> addCourse(ctx, action)
                "add_activity" -> addActivity(ctx, action)
                "set_alarm" -> setAlarm(ctx, action)
                "search_kb" -> searchKb(ctx, action)
                "list_schedule" -> listSchedule(ctx, action)
                "navigate" -> navigate(ctx, action)
                "delete_course" -> deleteCourse(ctx, action)
                "delete_activity" -> deleteActivity(ctx, action)
                else -> "未识别的动作：$type"
            }
        } catch (e: Exception) {
            "动作 $type 执行失败：${e.message}"
        }
    }

    private fun addCourse(ctx: Context, a: JSONObject): String {
        val name = a.optString("name").ifBlank { a.optString("course") }.trim()
        if (name.isBlank()) return "添加课程失败：没有课程名"
        val wd = a.optInt("weekday", 1).coerceIn(1, 7)
        val st = a.optInt("start_period", a.optInt("start", 1)).coerceIn(1, 12)
        val en = a.optInt("end_period", a.optInt("end", st)).coerceIn(st, 12)
        val weeks = a.optString("weeks", "1-16").ifBlank { "1-16" }
        val location = a.optString("location").trim()
        val teacher = a.optString("teacher").trim()
        val dup = CourseStore.courses(ctx).any {
            it.name == name && it.weekday == wd && it.startPeriod == st && it.endPeriod == en
        }
        if (dup) return "课程表里已有「$name」：周${ScheduleLogic.weekdayName(wd)} 第 $st-$en 节，未重复添加。"
        CourseStore.addCourse(
            ctx, name, teacher, location, wd, st, en, weeks,
            color = 0xFF2D5F8B.toInt(), source = "agent"
        )
        return "已添加到课程表：$name，周${ScheduleLogic.weekdayName(wd)} 第 $st-$en 节，$weeks 周，$location"
    }

    private fun addActivity(ctx: Context, a: JSONObject): String {
        val title = a.optString("title").ifBlank { "未命名活动" }.trim()
        val rawDate = a.optString("date").trim()
        val date: String
        if (rawDate.isBlank()) {
            date = CourseStore.today()
        } else {
            val normalized = NoticeExtractor.normalizeDate(rawDate)
            val parsed = ScheduleLogic.parseDate(normalized)
                ?: return "录入活动失败：日期「$rawDate」无法识别，应为 yyyy-MM-dd。"
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
        if (dup) return "活动「$title」$date $st 已在课表里，未重复录入。"
        val wk = ScheduleLogic.weekOf(date)
        val conflicts = if (st.isNotBlank() && en.isNotBlank()) {
            val skips = CourseStore.skipsFor(ctx, date)
            ScheduleLogic.conflicts(CourseStore.courses(ctx), skips, date, st, en)
        } else emptyList()
        CourseStore.addActivity(ctx, draft, conflicts.isNotEmpty(), "agent")
        return if (conflicts.isEmpty()) {
            "已录入活动：$title，$date $st-$en，${draft.location}"
        } else {
            "已录入活动：$title（第 $wk 周）。与 ${conflicts.joinToString("、") { it.name }} 时间冲突，已同时保留并标记冲突。"
        }
    }

    private fun setAlarm(ctx: Context, a: JSONObject): String {
        val h = a.optInt("hour", -1)
        val m = a.optInt("minute", 0).coerceIn(0, 59)
        if (h !in 0..23) return "设置闹钟失败：小时不合法"
        val label = a.optString("label", "智嘉提醒")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, h)
            putExtra(AlarmClock.EXTRA_MINUTES, m)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
        return String.format(Locale.US, "已打开系统闹钟：%02d:%02d（%s）。请在时钟应用里确认保存。", h, m, label)
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

    private fun navigate(ctx: Context, a: JSONObject): String {
        val place = a.optString("place").ifBlank { a.optString("name") }
        if (place.isBlank()) return "导航失败：没有目的地"
        val list = RouteRepository.buildings(ctx)
        val b = list.firstOrNull { it.name.contains(place) || place.contains(it.name) }
            ?: list.firstOrNull { it.name.contains(place.take(2)) }
        return if (b == null) {
            "没找到「$place」。可导航的地点有：" + list.take(8).joinToString("、") { it.name } + " 等。"
        } else {
            ctx.startActivity(
                Intent(ctx, ArNavActivity::class.java)
                    .putExtra("buildingId", b.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "已打开 ${b.name} 的 AR 步行导航（${b.subtitle}）。"
        }
    }

    private fun deleteCourse(ctx: Context, a: JSONObject): String {
        val name = a.optString("name").trim()
        if (name.isBlank()) return "删除失败：缺少课程名"
        val hit = CourseStore.courses(ctx).firstOrNull {
            it.name.contains(name) || name.contains(it.name)
        } ?: return "没有找到课程「$name」"
        CourseStore.deleteCourse(ctx, hit.id)
        return "已删除课程：${hit.name}"
    }

    private fun deleteActivity(ctx: Context, a: JSONObject): String {
        val title = a.optString("title").trim()
        if (title.isBlank()) return "删除活动失败：缺少活动名"
        val rawDate = a.optString("date").trim()
        val date = if (rawDate.isBlank()) "" else {
            ScheduleLogic.parseDate(NoticeExtractor.normalizeDate(rawDate))
                ?.let { ScheduleLogic.formatDate(it) } ?: ""
        }
        val all = CourseStore.activities(ctx)
        val hit = all.firstOrNull {
            (it.title.contains(title) || title.contains(it.title)) &&
                (date.isBlank() || it.date == date)
        } ?: return "没有找到活动「$title」${if (date.isNotBlank()) "（$date）" else ""}"
        CourseStore.deleteActivity(ctx, hit.id)
        return "已删除活动：${hit.title}（${hit.date} ${hit.startTime}-${hit.endTime}）"
    }
}
