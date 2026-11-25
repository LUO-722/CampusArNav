package com.campus.arnav.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 课程、活动、跳课记录的读写。
 */
object CourseStore {

    private val palette = intArrayOf(
        0xFF1F6F5C.toInt(), 0xFF2D5F8B.toInt(), 0xFF7A4E9E.toInt(),
        0xFF9A5B2E.toInt(), 0xFF8B3A52.toInt(), 0xFF3E7C3E.toInt(),
        0xFF6B6B2E.toInt(), 0xFF2E6B75.toInt()
    )

    private fun db(ctx: Context) = AppDb.get(ctx).writableDatabase

    fun courses(ctx: Context): List<Course> {
        val out = ArrayList<Course>()
        db(ctx).query("courses", null, null, null, null, null, "weekday, start_period").use { c ->
            while (c.moveToNext()) out.add(readCourse(c))
        }
        return out
    }

    private fun readCourse(c: Cursor): Course = Course(
        id = c.getLong(c.getColumnIndexOrThrow("_id")),
        name = c.getString(c.getColumnIndexOrThrow("name")),
        teacher = c.getString(c.getColumnIndexOrThrow("teacher")) ?: "",
        location = c.getString(c.getColumnIndexOrThrow("location")) ?: "",
        weekday = c.getInt(c.getColumnIndexOrThrow("weekday")),
        startPeriod = c.getInt(c.getColumnIndexOrThrow("start_period")),
        endPeriod = c.getInt(c.getColumnIndexOrThrow("end_period")),
        weeks = c.getString(c.getColumnIndexOrThrow("weeks")) ?: "1-16",
        color = c.getInt(c.getColumnIndexOrThrow("color")),
        source = c.getString(c.getColumnIndexOrThrow("source")) ?: "manual"
    )

    fun addCourse(
        ctx: Context,
        name: String,
        teacher: String = "",
        location: String = "",
        weekday: Int,
        startPeriod: Int,
        endPeriod: Int,
        weeks: String = "1-16",
        color: Int = palette[0],
        source: String = "manual"
    ): Long {
        val v = ContentValues().apply {
            put("name", name)
            put("teacher", teacher)
            put("location", location)
            put("weekday", weekday)
            put("start_period", startPeriod)
            put("end_period", endPeriod)
            put("weeks", weeks)
            put("color", color)
            put("source", source)
        }
        return db(ctx).insert("courses", null, v)
    }

    fun deleteCourse(ctx: Context, id: Long) {
        db(ctx).delete("courses", "_id=?", arrayOf(id.toString()))
        db(ctx).delete("course_skips", "course_id=?", arrayOf(id.toString()))
    }

    fun updateCourse(
        ctx: Context,
        id: Long,
        name: String,
        teacher: String,
        location: String,
        weekday: Int,
        startPeriod: Int,
        endPeriod: Int,
        weeks: String
    ) {
        val v = ContentValues().apply {
            put("name", name)
            put("teacher", teacher)
            put("location", location)
            put("weekday", weekday.coerceIn(1, 7))
            put("start_period", startPeriod.coerceIn(1, 12))
            put("end_period", endPeriod.coerceIn(startPeriod.coerceIn(1, 12), 12))
            put("weeks", weeks.ifBlank { "1-16" })
        }
        db(ctx).update("courses", v, "_id=?", arrayOf(id.toString()))
    }

    fun clearCourses(ctx: Context) {
        db(ctx).delete("courses", null, null)
        db(ctx).delete("course_skips", null, null)
    }

    /** 某天被跳过（只录入新安排）的课程 id。 */
    fun skipsFor(ctx: Context, date: String): Set<Long> {
        val out = HashSet<Long>()
        db(ctx).query("course_skips", arrayOf("course_id"), "date=?", arrayOf(date), null, null, null)
            .use { c -> while (c.moveToNext()) out.add(c.getLong(0)) }
        return out
    }

    fun skipCourseOn(ctx: Context, courseId: Long, date: String) {
        val v = ContentValues().apply {
            put("course_id", courseId)
            put("date", date)
        }
        db(ctx).insertWithOnConflict("course_skips", null, v, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun unskipCourseOn(ctx: Context, courseId: Long, date: String) {
        db(ctx).delete("course_skips", "course_id=? AND date=?", arrayOf(courseId.toString(), date))
    }

    // ---- 活动 ----

    fun activities(ctx: Context): List<Activity> {
        val out = ArrayList<Activity>()
        db(ctx).query("activities", null, null, null, null, null, "date, start_time").use { c ->
            while (c.moveToNext()) out.add(readActivity(c))
        }
        return out
    }

    fun activitiesOn(ctx: Context, date: String): List<Activity> =
        activities(ctx).filter { it.date == date }

    private fun readActivity(c: Cursor): Activity = Activity(
        id = c.getLong(c.getColumnIndexOrThrow("_id")),
        title = c.getString(c.getColumnIndexOrThrow("title")),
        date = c.getString(c.getColumnIndexOrThrow("date")),
        startTime = c.getString(c.getColumnIndexOrThrow("start_time")) ?: "",
        endTime = c.getString(c.getColumnIndexOrThrow("end_time")) ?: "",
        location = c.getString(c.getColumnIndexOrThrow("location")) ?: "",
        note = c.getString(c.getColumnIndexOrThrow("note")) ?: "",
        conflict = c.getInt(c.getColumnIndexOrThrow("conflict")) == 1,
        source = c.getString(c.getColumnIndexOrThrow("source")) ?: "zhijia"
    )

    fun addActivity(ctx: Context, a: ActivityDraft, conflict: Boolean, source: String = "zhijia"): Long {
        val v = ContentValues().apply {
            put("title", a.title.ifBlank { "未命名活动" })
            put("date", a.date)
            put("start_time", a.startTime)
            put("end_time", a.endTime)
            put("location", a.location)
            put("note", listOf(a.organizer, a.note).filter { it.isNotBlank() }.joinToString(" · "))
            put("conflict", if (conflict) 1 else 0)
            put("source", source)
        }
        return db(ctx).insert("activities", null, v)
    }

    fun deleteActivity(ctx: Context, id: Long) {
        db(ctx).delete("activities", "_id=?", arrayOf(id.toString()))
    }

    fun updateActivity(ctx: Context, id: Long, a: ActivityDraft, conflict: Boolean) {
        val v = ContentValues().apply {
            put("title", a.title.ifBlank { "未命名活动" })
            put("date", a.date)
            put("start_time", a.startTime)
            put("end_time", a.endTime)
            put("location", a.location)
            put("note", listOf(a.organizer, a.note).filter { it.isNotBlank() }.joinToString(" · "))
            put("conflict", if (conflict) 1 else 0)
        }
        db(ctx).update("activities", v, "_id=?", arrayOf(id.toString()))
    }

    // ---- 演示课表 ----

    fun seedDemoIfEmpty(ctx: Context) = seedDemo(ctx, force = false)

    /** force=true 时即使之前播过种也重新写入（用于「示例课表」按钮）。 */
    fun seedDemo(ctx: Context, force: Boolean) {
        if (courses(ctx).isNotEmpty()) return
        val sp = ctx.getSharedPreferences("zhijia_data", Context.MODE_PRIVATE)
        if (!force && sp.getBoolean("demo_seeded", false)) return
        sp.edit().putBoolean("demo_seeded", true).apply()
        val d = listOf(
            arrayOf("高等数学 A(一)", "张老师", "格物楼 101", 1, 1, 2, "1-16"),
            arrayOf("大学英语(一)", "李老师", "博学楼 305", 1, 3, 4, "1-16"),
            arrayOf("程序设计基础", "王老师", "精工楼 204", 2, 1, 2, "1-16"),
            arrayOf("大学物理", "陈老师", "笃行楼 201", 2, 6, 7, "1-16"),
            arrayOf("思想道德与法治", "刘老师", "弘毅楼 302", 3, 3, 5, "1-16"),
            arrayOf("高等数学 A(一)", "张老师", "格物楼 101", 3, 6, 7, "1-16"),
            arrayOf("体育(一)", "赵老师", "体育场", 4, 8, 9, "1-16"),
            arrayOf("程序设计实验", "王老师", "精工楼 机房", 4, 10, 12, "3-16"),
            arrayOf("大学英语(一)", "李老师", "博学楼 305", 5, 1, 2, "1-16"),
            arrayOf("形势与政策", "孙老师", "明志报告厅", 5, 6, 7, "5-12")
        )
        d.forEachIndexed { i, r ->
            addCourse(
                ctx,
                name = r[0] as String,
                teacher = r[1] as String,
                location = r[2] as String,
                weekday = r[3] as Int,
                startPeriod = r[4] as Int,
                endPeriod = r[5] as Int,
                weeks = r[6] as String,
                color = palette[i % palette.size],
                source = "demo"
            )
        }
    }

    // ---- 导入 ----

    /**
     * 导入 JSON。支持两种结构：
     * 1) [{"name":"高数","weekday":1,"start":1,"end":2,"weeks":"1-16","location":"格物101"}]
     * 2) {"courses":[...]}
     * 字段兼容 name/course、weekday/day、start/start_period、end/end_period。
     */
    fun importJson(ctx: Context, text: String): Int {
        val arr: JSONArray = try {
            val t = text.trim()
            if (t.startsWith("{")) JSONObject(t).optJSONArray("courses") ?: JSONArray()
            else JSONArray(t)
        } catch (e: Exception) {
            return -1
        }
        var n = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").ifBlank { o.optString("course") }
            if (name.isBlank()) continue
            val wd = o.optInt("weekday", o.optInt("day", 1)).coerceIn(1, 7)
            val st = o.optInt("start", o.optInt("start_period", 1)).coerceIn(1, 12)
            var en = o.optInt("end", o.optInt("end_period", st)).coerceIn(st, 12)
            if (en < st) en = st
            addCourse(
                ctx, name,
                teacher = o.optString("teacher"),
                location = o.optString("location"),
                weekday = wd, startPeriod = st, endPeriod = en,
                weeks = o.optString("weeks", "1-16").ifBlank { "1-16" },
                color = palette[n % palette.size],
                source = "import"
            )
            n++
        }
        return n
    }

    /** 一行课程解析结果；不写数据库，方便离线自检。 */
    data class ParsedLine(
        val name: String,
        val weekday: Int,
        val start: Int,
        val end: Int,
        val weeks: String,
        val location: String
    )

    /**
     * 解析课表文本，兼容三种常见粘贴格式：
     * 1) `课程|周几|起节|止节|周次|地点`（推荐）
     * 2) 教务导出/Excel 复制：`课程[TAB]周一[TAB]1-2节[TAB]1-16周[TAB]格物101`
     * 3) 空格/逗号分隔的同类字段；也兼容“周一第1-2节”这种合并写法。
     */
    fun importLines(ctx: Context, text: String): Int {
        var n = 0
        for (line in text.split("\n", "；", ";")) {
            val parsed = parseCourseLine(line) ?: continue
            addCourse(
                ctx, parsed.name,
                location = parsed.location,
                weekday = parsed.weekday, startPeriod = parsed.start, endPeriod = parsed.end,
                weeks = parsed.weeks, color = palette[n % palette.size],
                source = "import"
            )
            n++
        }
        return n
    }

    /** 纯函数解析一行课程；无法识别时返回 null。 */
    fun parseCourseLine(line: String): ParsedLine? {
        val raw = fullToHalf(line.trim())
        if (raw.isEmpty() || raw.startsWith("#")) return null
        val expanded = raw.replace(
            Regex("(星期|周|礼拜)([一二三四五六日天1-7])(?:第)?(\\d{1,2})[-~—至到](\\d{1,2})节?"),
            "$1$2 $3-$4"
        )
        val p = splitFields(expanded)
        if (p.size < 2) return null
        val name = p[0].trim()
        if (name.isEmpty()) return null

        var wd: Int?
        var st: Int?
        var en: Int?
        var weeks = "1-16"
        var loc = ""

        if (p.size >= 6 && parseWeekday(p[1]) != null &&
            p[2].toIntOrNull() != null && p[3].toIntOrNull() != null
        ) {
            // 标准 6 列
            wd = parseWeekday(p[1])
            st = p[2].toIntOrNull()
            en = p[3].toIntOrNull()
            weeks = p[4].replace("周", "").ifBlank { "1-16" }
            loc = p[5]
        } else {
            var wi = -1
            for (i in 1 until p.size) {
                if (parseWeekday(p[i]) != null) { wi = i; break }
            }
            if (wi < 0) return null
            wd = parseWeekday(p[wi])
            var pi = -1
            for (i in (wi + 1) until p.size) {
                if (parsePeriod(p[i]) != null) { pi = i; break }
            }
            if (pi < 0) {
                st = p.getOrNull(wi + 1)?.toIntOrNull()
                en = p.getOrNull(wi + 2)?.toIntOrNull() ?: st
            } else {
                val pr = parsePeriod(p[pi])!!
                st = pr.first
                en = pr.second
            }
            val cand = p.getOrNull(pi + 1)
            if (cand != null && p.size > pi + 2) {
                weeks = cand.replace("周", "").ifBlank { "1-16" }
                loc = p.drop(pi + 2).joinToString(" ").trim()
            } else if (cand != null && looksWeeks(cand)) {
                weeks = cand.replace("周", "").ifBlank { "1-16" }
            } else {
                loc = cand?.takeIf { it.isNotBlank() && !looksWeeks(it) } ?: ""
            }
        }

        val weekday = wd ?: return null
        val start = (st ?: return null).coerceIn(1, 12)
        val end = (en ?: start).coerceIn(start, 12)
        return ParsedLine(name, weekday, start, end, weeks, loc)
    }

    private fun fullToHalf(s: String): String = buildString {
        for (ch in s) append(if (ch in '０'..'９') ('0' + (ch - '０')) else ch)
    }

    private fun splitFields(t: String): List<String> = when {
        t.contains("|") || t.contains("｜") -> t.split("|", "｜")
        t.contains("\t") -> t.split("\t")
        t.contains(",") || t.contains("，") -> t.split(",", "，")
        Regex("\\s{2,}").containsMatchIn(t) -> t.split(Regex("\\s{2,}"))
        else -> t.split(Regex("\\s+"))
    }.map { it.trim() }.filter { it.isNotEmpty() }

    private fun parseWeekday(s: String): Int? {
        val t = fullToHalf(s.trim())
            .replace("星期", "").replace("周", "").replace("礼拜", "")
            .trim()
        val n = t.toIntOrNull()
        if (n != null && n in 1..7) return n
        return when (t) {
            "一", "1" -> 1
            "二", "2" -> 2
            "三", "3" -> 3
            "四", "4" -> 4
            "五", "5" -> 5
            "六", "6" -> 6
            "日", "天", "7" -> 7
            else -> null
        }
    }

    /** 解析 "1-2" "1~2节" "第1至2节" 等，返回起止节；单节返回 n..n。 */
    private fun parsePeriod(s: String): Pair<Int, Int>? {
        val t = fullToHalf(s.trim()).replace("第", "").replace("节", "").replace(" ", "").replace("周", "")
        if (t.isEmpty()) return null
        val m = Regex("^(\\d{1,2})[-~—至到](\\d{1,2})$").find(t)
        if (m != null) {
            val a = m.groupValues[1].toIntOrNull() ?: return null
            val b = m.groupValues[2].toIntOrNull() ?: return null
            if (a !in 1..12 || b !in 1..12 || b < a) return null
            return a to b
        }
        val n = t.toIntOrNull() ?: return null
        if (n !in 1..12) return null
        return n to n
    }

    private fun looksWeeks(s: String): Boolean {
        val t = s.trim()
        if (t.contains("周") || t.contains("单") || t.contains("双")) return true
        return false
    }

    private fun cnWeekday(s: String): Int? = parseWeekday(s)

    fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
