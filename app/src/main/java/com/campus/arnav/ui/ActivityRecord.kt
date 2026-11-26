package com.campus.arnav.ui

import com.campus.arnav.R
import android.content.Context
import com.campus.arnav.data.Activity
import com.campus.arnav.data.ActivityDraft
import com.campus.arnav.data.Course
import com.campus.arnav.data.CourseStore
import com.campus.arnav.data.ScheduleLogic
import androidx.appcompat.app.AlertDialog

/**
 * 把活动“录进课程表”：先查与既有课程/活动的冲突，再按用户选择落库。
 *
 * 三种选择（用用户的原话命名）：
 *  - [KEEP_OLD]  保留原课程表里的日程：不录新活动；
 *  - [NEW_ONLY]  只保存最新日程：把冲突的课在当天停掉、删掉冲突的旧活动，录新活动；
 *  - [BOTH]      两个同时录入：课程不动，新活动标记为冲突色一起显示在课表里。
 */
object ActivityRecord {

    const val KEEP_OLD = 0
    const val NEW_ONLY = 1
    const val BOTH = 2

    /** 与某天某段时间重叠的课程。 */
    fun courseClashes(ctx: Context, date: String, start: String, end: String): List<Course> {
        if (date.isBlank() || start.isBlank() || end.isBlank()) return emptyList()
        if (ScheduleLogic.parseDate(date) == null) return emptyList()
        if (!ScheduleLogic.validClock(start) || !ScheduleLogic.validClock(end)) return emptyList()
        return ScheduleLogic.conflicts(
            CourseStore.courses(ctx),
            CourseStore.skipsFor(ctx, date),
            date, start, end
        )
    }

    /** 与某天某段时间重叠的其它活动（excludeId 用于编辑时排除自己）。 */
    fun activityClashes(
        ctx: Context,
        date: String,
        start: String,
        end: String,
        excludeId: Long = 0
    ): List<Activity> {
        if (date.isBlank() || !ScheduleLogic.validClock(start) || !ScheduleLogic.validClock(end)) {
            return emptyList()
        }
        val s = ScheduleLogic.toMinutes(start)
        val e = ScheduleLogic.toMinutes(end)
        if (e <= s) return emptyList()
        return CourseStore.activities(ctx).filter { a ->
            a.id != excludeId && a.date == date &&
                run {
                    val as0 = ScheduleLogic.toMinutes(a.startTime)
                    val ae = ScheduleLogic.toMinutes(a.endTime)
                    as0 < e && s < ae
                }
        }
    }

    fun hasClash(ctx: Context, date: String, start: String, end: String, excludeId: Long = 0): Boolean =
        courseClashes(ctx, date, start, end).isNotEmpty() ||
            activityClashes(ctx, date, start, end, excludeId).isNotEmpty()

    /**
     * 录入活动。有冲突时弹三选一；返回执行结果（[KEEP_OLD]/[NEW_ONLY]/[BOTH]，取消时为 -1）。
     */
    fun record(
        ctx: Context,
        draft: ActivityDraft,
        source: String = "zhijia",
        onDone: (result: Int) -> Unit
    ) {
        val courses = courseClashes(ctx, draft.date, draft.startTime, draft.endTime)
        val acts = activityClashes(ctx, draft.date, draft.startTime, draft.endTime)
        if (courses.isEmpty() && acts.isEmpty()) {
            CourseStore.addActivity(ctx, draft, conflict = false, source = source)
            onDone(BOTH)
            return
        }
        val who = buildList {
            courses.forEach { add("课程「${it.name}」") }
            acts.forEach { add("活动「${it.title}」") }
        }.joinToString("、")
        AlertDialog.Builder(ctx, R.style.Theme_CampusAr_Dialog)
            .setTitle("时间冲突：${draft.title}")
            .setMessage(
                "${draft.date} ${draft.startTime}-${draft.endTime}\n" +
                    "和已有的 $who 重叠。要保留哪一个？"
            )
            .setPositiveButton("两个同时录入") { _, _ ->
                CourseStore.addActivity(ctx, draft, conflict = true, source = source)
                onDone(BOTH)
            }
            .setNeutralButton("保留原课程表日程") { _, _ ->
                onDone(KEEP_OLD)
            }
            .setNegativeButton("只保存最新日程") { _, _ ->
                for (c in courses) CourseStore.skipCourseOn(ctx, c.id, draft.date)
                for (a in acts) CourseStore.deleteActivity(ctx, a.id)
                CourseStore.addActivity(ctx, draft, conflict = false, source = source)
                onDone(NEW_ONLY)
            }
            .setCancelable(false)
            .show()
    }
}
