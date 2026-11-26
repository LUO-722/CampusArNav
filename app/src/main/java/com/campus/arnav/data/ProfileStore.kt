package com.campus.arnav.data

import android.content.Context

/**
 * 个人资料：昵称、学号、院系、宿舍、常用目的地。
 * 全部只存本机 SharedPreferences，不上传。
 */
object ProfileStore {

    private const val PREF = "zhijia_profile"

    data class Profile(
        val nick: String,
        val sid: String,
        val college: String,
        val dorm: String
    ) {
        /** 头像用的首字：优先昵称首字，没有就用「嘉」。 */
        val initial: String
            get() = nick.trim().firstOrNull()?.toString() ?: "嘉"
    }

    fun get(ctx: Context): Profile {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return Profile(
            sp.getString("nick", "同学") ?: "同学",
            sp.getString("sid", "") ?: "",
            sp.getString("college", "") ?: "",
            sp.getString("dorm", "") ?: ""
        )
    }

    fun save(ctx: Context, p: Profile) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("nick", p.nick.trim().ifBlank { "同学" })
            .putString("sid", p.sid.trim())
            .putString("college", p.college.trim())
            .putString("dorm", p.dorm.trim())
            .apply()
    }

    /** 个人页用的统计：课程数、本周课程数、活动数、知识库篇数。 */
    data class Stats(
        val courseCount: Int,
        val weekCourseCount: Int,
        val activityCount: Int,
        val kbDocs: Int,
        val currentWeek: Int,
        val totalWeeks: Int,
        val semesterName: String,
        val weekCourses: List<Course>
    )

    fun stats(ctx: Context): Stats {
        val courses = CourseStore.courses(ctx)
        val week = ScheduleLogic.currentWeek()
        val acts = CourseStore.activities(ctx)
        val kb = try {
            com.campus.arnav.kb.KbIndex.docCount(ctx)
        } catch (e: Exception) {
            0
        }
        val weekCourses = courses
            .filter { ScheduleLogic.weekMatches(it.weeks, week) }
            .sortedWith(compareBy({ it.weekday }, { it.startPeriod }))
        return Stats(
            courseCount = courses.size,
            weekCourseCount = weekCourses.size,
            activityCount = acts.size,
            kbDocs = kb,
            currentWeek = week,
            totalWeeks = ScheduleLogic.totalWeeks(),
            semesterName = ScheduleLogic.semesterName(),
            weekCourses = weekCourses
        )
    }
}