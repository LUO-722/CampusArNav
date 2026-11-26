package com.campus.arnav.data

import android.content.Context

/**
 * 个人资料：学籍信息（姓名/学号/院系/专业/班级/学制/入学日期）+ 宿舍 + 常用目的地。
 *
 * 学籍字段由教务系统同步时自动写入，也可以在「我的」页手动改；
 * 身份证号、家庭住址这类敏感字段不落库、不显示。全部只存本机 SharedPreferences，不上传。
 */
object ProfileStore {

    private const val PREF = "zhijia_profile"

    data class Profile(
        val nick: String = "同学",
        val sid: String = "",
        val college: String = "",
        val major: String = "",
        val clazz: String = "",
        val studyLen: String = "",
        val enterDate: String = "",
        val gender: String = "",
        val nation: String = "",
        val dorm: String = ""
    ) {
        /** 头像用的首字：优先昵称首字，没有就用「嘉」。 */
        val initial: String
            get() = nick.trim().firstOrNull()?.toString() ?: "嘉"
    }

    fun get(ctx: Context): Profile {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return Profile(
            nick = sp.getString("nick", "同学") ?: "同学",
            sid = sp.getString("sid", "") ?: "",
            college = sp.getString("college", "") ?: "",
            major = sp.getString("major", "") ?: "",
            clazz = sp.getString("clazz", "") ?: "",
            studyLen = sp.getString("studyLen", "") ?: "",
            enterDate = sp.getString("enterDate", "") ?: "",
            gender = sp.getString("gender", "") ?: "",
            nation = sp.getString("nation", "") ?: "",
            dorm = sp.getString("dorm", "") ?: ""
        )
    }

    fun save(ctx: Context, p: Profile) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("nick", p.nick.trim().ifBlank { "同学" })
            .putString("sid", p.sid.trim())
            .putString("college", p.college.trim())
            .putString("major", p.major.trim())
            .putString("clazz", p.clazz.trim())
            .putString("studyLen", p.studyLen.trim())
            .putString("enterDate", p.enterDate.trim())
            .putString("gender", p.gender.trim())
            .putString("nation", p.nation.trim())
            .putString("dorm", p.dorm.trim())
            .apply()
    }

    /** 把教务系统抓到的学籍信息并进本机资料：教务有值的字段覆盖，空字段保留原值。 */
    fun mergeJwxt(ctx: Context, jw: JwProfile): Profile {
        val old = get(ctx)
        val merged = old.copy(
            nick = jw.name.ifBlank { old.nick },
            sid = jw.sid.ifBlank { old.sid },
            college = jw.college.ifBlank { old.college },
            major = jw.major.ifBlank { old.major },
            clazz = jw.clazz.ifBlank { old.clazz }
        )
        save(ctx, merged)
        return merged
    }

    /** 个人页用的统计：课程数、本周课程数、活动数、知识库篇数、考试/成绩条数。 */
    data class Stats(
        val courseCount: Int,
        val weekCourseCount: Int,
        val activityCount: Int,
        val kbDocs: Int,
        val examCount: Int,
        val gradeCount: Int,
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
        val exams = try {
            CourseStore.exams(ctx).size
        } catch (e: Exception) {
            0
        }
        val grades = try {
            CourseStore.grades(ctx).size
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
            examCount = exams,
            gradeCount = grades,
            currentWeek = week,
            totalWeeks = ScheduleLogic.totalWeeks(),
            semesterName = ScheduleLogic.semesterName(),
            weekCourses = weekCourses
        )
    }
}