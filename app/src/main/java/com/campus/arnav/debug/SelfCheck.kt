package com.campus.arnav.debug

import android.content.Context
import com.campus.arnav.agent.AgentEngine
import com.campus.arnav.agent.NoticeExtractor
import com.campus.arnav.data.AppDb
import com.campus.arnav.data.AuthStore
import com.campus.arnav.data.CourseStore
import com.campus.arnav.data.Activity
import com.campus.arnav.data.ScheduleLogic
import com.campus.arnav.kb.KbIndex
import com.campus.arnav.net.JwxtClient
import org.json.JSONObject

/**
 * 离线自检：不联网、不需要测试框架，直接验证课表解析、周次/作息计算、
 * 活动时间归一化和知识库/数据库可用性。结果展示在「设置 → 运行离线自检」。
 */
object SelfCheck {

    data class Check(val name: String, val ok: Boolean, val detail: String)

    fun run(ctx: Context): List<Check> {
        val out = ArrayList<Check>()
        fun check(name: String, block: () -> Boolean) {
            out.add(
                try {
                    val ok = block()
                    Check(name, ok, if (ok) "通过" else "断言未通过")
                } catch (e: Exception) {
                    Check(name, false, e.message ?: e.javaClass.simpleName)
                }
            )
        }

        check("日期解析") {
            val d = ScheduleLogic.parseDate("2026-09-07")
            d != null && ScheduleLogic.formatDate(d) == "2026-09-07"
        }
        check("非法日期拒绝") {
            ScheduleLogic.parseDate("2026-13-01") == null && ScheduleLogic.parseDate("abc") == null
        }
        check("周次与星期") {
            val start = ScheduleLogic.semesterStartText()
            ScheduleLogic.dateOf(1, 1) == start &&
                ScheduleLogic.weekOf(start) == 1 &&
                ScheduleLogic.weekOf(ScheduleLogic.dateOf(2, 1)) == 2 &&
                ScheduleLogic.weekdayOf(ScheduleLogic.dateOf(2, 7)) == 7
        }
        check("周次规则") {
            ScheduleLogic.weekMatches("1-16", 10) &&
                !ScheduleLogic.weekMatches("1-8", 9) &&
                ScheduleLogic.weekMatches("1-16单", 3) &&
                !ScheduleLogic.weekMatches("1-16单", 4) &&
                ScheduleLogic.weekMatches("1-8,10-16", 12) &&
                !ScheduleLogic.weekMatches("1-8,10-16", 9)
        }
        check("作息节次") {
            ScheduleLogic.periodTime(1, "2026-10-10") == "08:15-08:55" &&
                ScheduleLogic.periodTime(6, "2026-10-10") == "13:50-14:30" &&
                ScheduleLogic.periodTime(12, "2026-10-10") == "20:00-20:40" &&
                // 科文作息全年一套：5 月与 10 月同一节次时间一致
                ScheduleLogic.periodTime(1, "2026-05-10") == "08:15-08:55" &&
                ScheduleLogic.periodTime(6, "2026-05-10") == "13:50-14:30"
        }
        check("节次定位") {
            ScheduleLogic.periodForTime("08:15", "2026-10-10") == 1 &&
                ScheduleLogic.periodForTime("09:30", "2026-10-10") == 2 &&
                ScheduleLogic.periodForTime("10:30", "2026-10-10") == 3 &&
                ScheduleLogic.periodForTime("10:40", "2026-10-10") == 4 &&
                ScheduleLogic.periodForTime("23:00", "2026-10-10") == 12
        }
        check("课程行·竖线") {
            val p = CourseStore.parseCourseLine("高等数学|周三|1|2|1-16|格物楼101")
            p != null && p.weekday == 3 && p.start == 1 && p.end == 2 &&
                p.weeks == "1-16" && p.location == "格物楼101"
        }
        check("课程行·教务导出") {
            val p = CourseStore.parseCourseLine("大学物理\t周二\t3-4节\t1-16周\t博学楼305")
            p != null && p.weekday == 2 && p.start == 3 && p.end == 4 &&
                p.weeks == "1-16" && p.location == "博学楼305"
        }
        check("课程行·合并写法") {
            val p = CourseStore.parseCourseLine("大学英语 周一第5-6节 1-16周 明志楼")
            p != null && p.weekday == 1 && p.start == 5 && p.end == 6 &&
                p.weeks == "1-16" && p.location == "明志楼"
        }
        check("课程行·非法输入") {
            CourseStore.parseCourseLine("") == null &&
                CourseStore.parseCourseLine("只有课名") == null
        }
        check("周次边界") {
            ScheduleLogic.weekMatches("1-16", 1) &&
                ScheduleLogic.weekMatches("1-16", 16) &&
                !ScheduleLogic.weekMatches("1-16", 0) &&
                !ScheduleLogic.weekMatches("1-16", 17) &&
                ScheduleLogic.weekOf(ScheduleLogic.dateOf(ScheduleLogic.totalWeeks(), 7)) == ScheduleLogic.totalWeeks()
        }
        check("冲突判断") {
            val c = com.campus.arnav.data.Course(
                id = 99L, name = "自检课程", teacher = "", location = "",
                weekday = 3, startPeriod = 3, endPeriod = 4,
                weeks = "1-16", color = 0, source = "selfcheck"
            )
            val d = ScheduleLogic.dateOf(1, 3)
            val hit = ScheduleLogic.conflicts(listOf(c), emptySet(), d, "10:00", "10:20")
            val miss = ScheduleLogic.conflicts(listOf(c), emptySet(), d, "12:00", "12:30")
            val skipped = ScheduleLogic.conflicts(listOf(c), setOf(99L), d, "10:00", "10:20")
            hit.size == 1 && miss.isEmpty() && skipped.isEmpty()
        }
        check("时间归一") {
            NoticeExtractor.normalizeTime("下午3点") == "15:00" &&
                NoticeExtractor.normalizeTime("晚上7点半") == "19:30" &&
                NoticeExtractor.normalizeTime("中午12:30") == "12:30" &&
                NoticeExtractor.normalizeTime("凌晨1点") == "01:00" &&
                NoticeExtractor.normalizeTime("第3点要求") == "第3点要求"
        }
        check("日期归一") {
            NoticeExtractor.normalizeDate("2026年9月25日") == "2026-09-25" &&
                NoticeExtractor.normalizeDate("2026/9/25") == "2026-09-25" &&
                NoticeExtractor.normalizeDate("2026-13-01") == "2026-13-01"
        }
        check("时间合法性") {
            ScheduleLogic.validClock("08:00") && ScheduleLogic.validClock("23:59") &&
                !ScheduleLogic.validClock("24:00") && !ScheduleLogic.validClock("8点") &&
                !ScheduleLogic.validClock("08:60")
        }
        check("知识库可读") {
            val s = KbIndex.selfCheck(ctx)
            s.contains("知识库") && s.contains("篇")
        }
        check("数据库可用") {
            AppDb.get(ctx).readableDatabase != null
        }
        check("动作确认文案") {
            val a = JSONObject()
                .put("type", "add_activity")
                .put("title", "讲座")
                .put("date", "2026-10-01")
                .put("start_time", "19:00")
            AgentEngine.describe(a).contains("录入活动「讲座」")
        }
        check("备份 JSON") {
            val j = CourseStore.backupJson(
                emptyList(),
                listOf(Activity(title = "讲座", date = "2026-10-01", startTime = "19:00", endTime = "20:00"))
            )
            val acts = j.optJSONArray("activities")
            j.optInt("version") == 2 &&
                j.optJSONArray("courses")?.length() == 0 &&
                acts?.length() == 1 &&
                acts.optJSONObject(0)?.optString("title") == "讲座"
        }
        check("外网时段") {
            !AuthStore.wanBlockedHour(8) && AuthStore.wanBlockedHour(9) &&
                AuthStore.wanBlockedHour(17) && !AuthStore.wanBlockedHour(18) &&
                AuthStore.wanBlockedHour(0) == false
        }
        check("教务登录编码") {
            // 正方 encodeInp：base64(学号) + "%%%" + base64(密码)；"2026010101" / "abc123"
            val enc = JwxtClient.encodeLogin("2026010101", "abc123")
            JwxtClient.b64("abc") == "YWJj" &&
                JwxtClient.b64("").isEmpty() &&
                enc == "MjAyNjAxMDEwMQ==%%%YWJjMTIz" &&
                enc.split("%%%").size == 2 &&
                !enc.contains("\n")
        }
        check("教务课表解析") {
            val html = """
                <table id="kbtable">
                  <tr><th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th><th>星期四</th><th>星期五</th></tr>
                  <tr><td>第1节</td>
                      <td><div>高等数学A(一)<br/>1-16周<br/>格物楼101<br/>张老师</div></td>
                      <td></td><td></td><td></td><td></td></tr>
                  <tr><td>第3节</td>
                      <td></td>
                      <td><div>大学英语(一)<br/>1-8周<br/>博学楼205</div></td>
                      <td></td><td></td><td></td></tr>
                </table>
            """.trimIndent()
            val list = JwxtClient.parseTimetable(html)
            val a = list.firstOrNull { it.name.startsWith("高等数学") }
            val b = list.firstOrNull { it.name.startsWith("大学英语") }
            list.size == 2 &&
                a?.weekday == 1 && a.startPeriod == 1 && a.location.contains("格物") &&
                a.weeks.contains("1") &&
                b?.weekday == 2 && b.startPeriod == 3 &&
                list.all { it.source == "jwxt" }
        }
        check("教务原文保留") {
            val html = """
                <table id="kbtable">
                  <tr><th>节次</th><th>星期一</th><th>星期二</th></tr>
                  <tr><td>第1节</td>
                      <td><div>高等数学A(一)<br/>(1-2节)1-16周<br/>格物楼101<br/>张老师</div></td>
                      <td></td></tr>
                </table>
            """.trimIndent()
            val c = JwxtClient.parseTimetable(html).firstOrNull()
            c != null && c.raw.contains("格物楼101") &&
                c.raw.contains("高等数学A(一)") &&
                c.weekText.contains("周") &&
                c.periodText.contains("节")
        }
        check("考试解析") {
            val html = """
                <table>
                  <tr><th>课程名称</th><th>考试时间</th><th>考试地点</th><th>座位号</th></tr>
                  <tr><td>高等数学A(一)</td><td>2027-01-08 09:00</td><td>博学楼305</td><td>12</td></tr>
                </table>
            """.trimIndent()
            val e = JwxtClient.parseExams(html)
            e.size == 1 && e[0].course.startsWith("高等数学") &&
                e[0].time.contains("2027-01-08") && e[0].location.contains("博学") &&
                e[0].seat == "12" && e[0].raw.contains("博学楼305")
        }
        check("成绩解析") {
            val html = """
                <table>
                  <tr><th>学期</th><th>课程名称</th><th>学分</th><th>总成绩</th><th>绩点</th></tr>
                  <tr><td>2026-2027-1</td><td>高等数学A(一)</td><td>5</td><td>88</td><td>3.8</td></tr>
                </table>
            """.trimIndent()
            val g = JwxtClient.parseGrades(html)
            g.size == 1 && g[0].term.contains("2026") && g[0].course.startsWith("高等数学") &&
                g[0].credit == "5" && g[0].score == "88" && g[0].gpa == "3.8"
        }
        check("学生信息解析") {
            val html = "<td>姓名：张三</td><td>学号：2026010101</td><td>学院：电气与智能制造学院</td>"
            val p = JwxtClient.parseProfile(html)
            p.name == "张三" && p.sid == "2026010101" && p.college.contains("电气")
        }
        check("活动冲突三选一语义") {
            com.campus.arnav.ui.ActivityRecord.KEEP_OLD == 0 &&
                com.campus.arnav.ui.ActivityRecord.NEW_ONLY == 1 &&
                com.campus.arnav.ui.ActivityRecord.BOTH == 2
        }
        return out
    }

    fun format(ctx: Context): String {
        val list = run(ctx)
        val pass = list.count { it.ok }
        val sb = StringBuilder("离线自检：$pass/${list.size} 通过\n")
        list.forEach {
            sb.append(if (it.ok) "✓ " else "✗ ")
                .append(it.name)
            if (!it.ok) sb.append(" — ").append(it.detail)
            sb.append('\n')
        }
        return sb.toString().trim()
    }
}
