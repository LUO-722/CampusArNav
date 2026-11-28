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

    /** Ins 风格课程配色：粉 / 浅蓝 / 淡紫 / 薄荷 / 蜜桃 / 玫瑰 / 天空蓝 / 藕粉。 */
    private val palette = intArrayOf(
        0xFFF8AECB.toInt(), 0xFFA8D8F2.toInt(), 0xFFC9BCEC.toInt(),
        0xFFA9E3C9.toInt(), 0xFFFFC9A8.toInt(), 0xFFF3BBDB.toInt(),
        0xFF9DCBF0.toInt(), 0xFFE7B7CE.toInt()
    )

    private fun db(ctx: Context) = AppDb.get(ctx).writableDatabase

    /** 给导入的课程安排配色：按序号轮流取调色板。 */
    fun colorAt(index: Int): Int = palette[((index % palette.size) + palette.size) % palette.size]

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
        source = c.getString(c.getColumnIndexOrThrow("source")) ?: "manual",
        raw = c.getString(c.getColumnIndexOrThrow("raw")) ?: "",
        periodText = c.getString(c.getColumnIndexOrThrow("period_text")) ?: "",
        weekText = c.getString(c.getColumnIndexOrThrow("week_text")) ?: ""
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

    /** 按 Course 全字段写入（教务同步用，含单元格原文，一字不改地留着）。 */
    fun addCourseFull(ctx: Context, c: Course, color: Int): Long {
        val v = ContentValues().apply {
            put("name", c.name)
            put("teacher", c.teacher)
            put("location", c.location)
            put("weekday", c.weekday.coerceIn(1, 7))
            put("start_period", c.startPeriod.coerceIn(1, 12))
            put("end_period", c.endPeriod.coerceIn(c.startPeriod.coerceIn(1, 12), 12))
            put("weeks", c.weeks.ifBlank { "1-16" })
            put("color", color)
            put("source", c.source)
            put("raw", c.raw)
            put("period_text", c.periodText)
            put("week_text", c.weekText)
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
    data class JsonImport(val courses: Int, val activities: Int)

    fun importJson(ctx: Context, text: String, replace: Boolean = true): JsonImport {
        val t = text.trim()
        val coursesArr: JSONArray
        val activitiesArr: JSONArray
        try {
            if (t.startsWith("{")) {
                val root = JSONObject(t)
                coursesArr = root.optJSONArray("courses") ?: JSONArray()
                activitiesArr = root.optJSONArray("activities") ?: JSONArray()
            } else {
                coursesArr = JSONArray(t)
                activitiesArr = JSONArray()
            }
        } catch (e: Exception) {
            return JsonImport(-1, 0)
        }
        // 导入是「覆盖」语义：先把旧课表（含示例课、以前同步/手工加的）全部清掉，避免新旧混在一起
        if (replace) clearCourses(ctx)
        var n = 0
        val seenCourses = courses(ctx).map { courseKey(it) }.toMutableSet()
        for (i in 0 until coursesArr.length()) {
            val o = coursesArr.optJSONObject(i) ?: continue
            val name = o.optString("name").ifBlank { o.optString("course") }
            if (name.isBlank()) continue
            val wd = o.optInt("weekday", o.optInt("day", 1)).coerceIn(1, 7)
            val st = o.optInt("start", o.optInt("start_period", 1)).coerceIn(1, 12)
            var en = o.optInt("end", o.optInt("end_period", st)).coerceIn(st, 12)
            if (en < st) en = st
            val weeks = o.optString("weeks", "1-16").ifBlank { "1-16" }
            val key = "$name|$wd|$st|$en|$weeks"
            if (!seenCourses.add(key)) continue
            addCourse(
                ctx, name,
                teacher = o.optString("teacher"),
                location = o.optString("location"),
                weekday = wd, startPeriod = st, endPeriod = en,
                weeks = weeks,
                color = palette[n % palette.size],
                source = "import"
            )
            n++
        }
        var m = 0
        val seenActs = activities(ctx).map { "${it.title}|${it.date}|${it.startTime}" }.toMutableSet()
        for (i in 0 until activitiesArr.length()) {
            val o = activitiesArr.optJSONObject(i) ?: continue
            val title = o.optString("title").ifBlank { o.optString("name") }
            if (title.isBlank()) continue
            val date = ScheduleLogic.parseDate(o.optString("date")) ?: continue
            val st = o.optString("start", o.optString("start_time"))
            val en = o.optString("end", o.optString("end_time"))
            if (!ScheduleLogic.validClock(st) || !ScheduleLogic.validClock(en) || en <= st) continue
            val d = ScheduleLogic.formatDate(date)
            if (!seenActs.add("$title|$d|$st")) continue
            addActivity(
                ctx,
                ActivityDraft(
                    title = title,
                    date = d,
                    startTime = st,
                    endTime = en,
                    location = o.optString("location"),
                    organizer = o.optString("organizer"),
                    note = o.optString("note")
                ),
                conflict = false,
                source = "import"
            )
            m++
        }
        return JsonImport(n, m)
    }

    /** 生成备份 JSON（纯函数，方便自检；导出/分享也用这一份，保证字段一致）。 */
    fun backupJson(courses: List<Course>, acts: List<Activity>): JSONObject {
        val arr = JSONArray()
        for (c in courses) {
            arr.put(JSONObject().apply {
                put("name", c.name)
                put("teacher", c.teacher)
                put("location", c.location)
                put("weekday", c.weekday)
                put("start", c.startPeriod)
                put("end", c.endPeriod)
                put("weeks", c.weeks)
            })
        }
        val actArr = JSONArray()
        for (a in acts) {
            actArr.put(JSONObject().apply {
                put("title", a.title)
                put("date", a.date)
                put("start", a.startTime)
                put("end", a.endTime)
                put("location", a.location)
                put("note", a.note)
            })
        }
        return JSONObject().apply {
            put("app", "科文智嘉")
            put("version", 2)
            put("semesterName", ScheduleLogic.semesterName())
            put("semesterStart", ScheduleLogic.semesterStartText())
            put("totalWeeks", ScheduleLogic.totalWeeks())
            put("courses", arr)
            put("activities", actArr)
        }
    }

    private fun courseKey(c: Course) =
        "${c.name}|${c.weekday}|${c.startPeriod}|${c.endPeriod}|${c.weeks}"

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
    /** 导入结果（给界面做「导入了几门 / 第几行没认出来」的提示） */
    data class ImportReport(
        val imported: Int,
        val skipped: Int,
        val firstBadLine: String,
        val badLineNo: Int
    )

    /**
     * 带诊断的文本导入：
     * - 空行、表头行（课程名/星期/节次）自动跳过，不算错误
     * - 每行都要过一个「能不能认出星期 + 节次」的闸门，认不出就记下来行号和原文
     * - **一行都没认出来时绝不动旧课表**；认出至少一门才清空旧课表再写入
     */
    fun importLinesDetailed(ctx: Context, text: String, replace: Boolean = true): ImportReport {
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        val rawLines = normalized.split("\n")
        val parsed = ArrayList<ParsedLine>()
        var skipped = 0
        var firstBad = ""
        var firstBadNo = 0
        for (idx in rawLines.indices) {
            val t = rawLines[idx].trim()
            if (t.isEmpty()) continue
            if (isHeaderLine(t)) continue
            val one = parseCourseLine(t)
            if (one == null) {
                skipped++
                if (firstBad.isEmpty()) {
                    firstBad = t.take(80)
                    firstBadNo = idx + 1
                }
            } else {
                parsed.add(one)
            }
        }
        if (parsed.isEmpty()) return ImportReport(0, skipped, firstBad, firstBadNo)
        if (replace) clearCourses(ctx)
        var n = 0
        val seen = courses(ctx).map { courseKey(it) }.toMutableSet()
        for (one in parsed) {
            val key = "${one.name}|${one.weekday}|${one.start}|${one.end}|${one.weeks}"
            if (!seen.add(key)) continue
            addCourse(
                ctx, one.name,
                location = one.location,
                weekday = one.weekday, startPeriod = one.start, endPeriod = one.end,
                weeks = one.weeks, color = palette[n % palette.size],
                source = "import"
            )
            n++
        }
        return ImportReport(n, skipped, firstBad, firstBadNo)
    }

    fun importLines(ctx: Context, text: String, replace: Boolean = true): Int =
        importLinesDetailed(ctx, text, replace).imported

    private fun skipKey(name: String, weekday: Int, startPeriod: Int) = "$name|$weekday|$startPeriod"

    /**
     * 用教务系统解析结果替换「上次导入」的课程，返回这次新导入的门数。
     *
     * 三条安全约束：
     * 1) list 为空时直接返回 0，一门课都不删：校园网断、教务维护、外网时段外都可能返回空课表，
     *    照旧「先删后插」会把学生本机课表清成空。这个短路放在库里，防御所有调用方。
     * 2) 删 jwxt 课程会连带删 course_skips，所以删之前先按身份键记下被跳过的日期，插完再挂到新课程 id 上。
     * 3) 整个「删 + 插 + 恢复标记」在一个事务里，中途异常回滚成原课表，不留半空状态。
     */
    fun replaceJwxtCourses(ctx: Context, list: List<Course>): Int {
        if (list.isEmpty()) return 0
        val d = db(ctx)
        var n = 0
        d.beginTransaction()
        try {
            // ① 记下现有 jwxt 课程：要删的 id，以及「身份键 → 被跳过的日期集合」
            val oldIds = ArrayList<Long>()
            val oldSkips = HashMap<String, MutableSet<String>>()
            d.query(
                "courses",
                arrayOf("_id", "name", "weekday", "start_period"),
                "source=?",
                arrayOf("jwxt"),
                null, null, null
            ).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    oldIds.add(id)
                    val key = skipKey(c.getString(1) ?: "", c.getInt(2), c.getInt(3))
                    d.query(
                        "course_skips", arrayOf("date"), "course_id=?", arrayOf(id.toString()),
                        null, null, null
                    ).use { s ->
                        while (s.moveToNext()) {
                            val date = s.getString(0)
                            if (!date.isNullOrEmpty()) oldSkips.getOrPut(key) { HashSet() }.add(date)
                        }
                    }
                }
            }

            // ② 教务课表是权威数据：清空全部旧课程（示例课、以前导入的、手工加的）与其停课标记。
            //    被跳过的日期已在 ① 里按身份键记下，插完新课程会挂回去。
            d.delete("courses", null, null)
            d.delete("course_skips", null, null)

            // ③ 插入新课程，同时按身份键收集新课程 id（同一身份键可能对应多行，全部记下）
            val existing = courses(ctx)
            val inserted = HashSet<String>()
            val newIds = HashMap<String, MutableList<Long>>()
            for (c in list) {
                val full = "${c.name}|${c.weekday}|${c.startPeriod}|${c.endPeriod}|${c.weeks}|${c.location}"
                if (!inserted.add(full)) continue
                val dup = existing.any {
                    it.name == c.name && it.weekday == c.weekday &&
                        it.startPeriod == c.startPeriod && it.endPeriod == c.endPeriod &&
                        it.weeks == c.weeks && it.location == c.location
                }
                if (dup) continue
                val id = addCourseFull(ctx, c.copy(source = "jwxt"), colorAt(n))
                if (id <= 0) continue
                newIds.getOrPut(skipKey(c.name, c.weekday, c.startPeriod)) { ArrayList() }.add(id)
                n++
            }

            // ④ 用同一身份键把停课标记恢复到新课程 id 上
            for ((key, dates) in oldSkips) {
                val ids = newIds[key] ?: continue
                for (id in ids) {
                    for (date in dates) {
                        val v = ContentValues().apply {
                            put("course_id", id)
                            put("date", date)
                        }
                        d.insertWithOnConflict(
                            "course_skips", null, v,
                            android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
                        )
                    }
                }
            }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
        return n
    }

    // ---- 教务：考试 / 成绩 ----

    fun exams(ctx: Context): List<Exam> {
        val out = ArrayList<Exam>()
        db(ctx).query("exams", null, null, null, null, null, "_id").use { c ->
            while (c.moveToNext()) {
                out.add(
                    Exam(
                        id = c.getLong(c.getColumnIndexOrThrow("_id")),
                        course = c.getString(c.getColumnIndexOrThrow("course")) ?: "",
                        time = c.getString(c.getColumnIndexOrThrow("time")) ?: "",
                        location = c.getString(c.getColumnIndexOrThrow("location")) ?: "",
                        seat = c.getString(c.getColumnIndexOrThrow("seat")) ?: "",
                        raw = c.getString(c.getColumnIndexOrThrow("raw")) ?: ""
                    )
                )
            }
        }
        return out
    }

    /** 全量替换考试安排，返回写入条数。 */
    fun replaceExams(ctx: Context, list: List<Exam>): Int {
        val d = db(ctx)
        d.beginTransaction()
        try {
            d.delete("exams", null, null)
            for (e in list) {
                d.insert(
                    "exams", null,
                    ContentValues().apply {
                        put("course", e.course)
                        put("time", e.time)
                        put("location", e.location)
                        put("seat", e.seat)
                        put("raw", e.raw)
                    }
                )
            }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
        return list.size
    }

    fun grades(ctx: Context): List<Grade> {
        val out = ArrayList<Grade>()
        db(ctx).query("grades", null, null, null, null, null, "_id").use { c ->
            while (c.moveToNext()) {
                out.add(
                    Grade(
                        id = c.getLong(c.getColumnIndexOrThrow("_id")),
                        term = c.getString(c.getColumnIndexOrThrow("term")) ?: "",
                        course = c.getString(c.getColumnIndexOrThrow("course")) ?: "",
                        credit = c.getString(c.getColumnIndexOrThrow("credit")) ?: "",
                        score = c.getString(c.getColumnIndexOrThrow("score")) ?: "",
                        gpa = c.getString(c.getColumnIndexOrThrow("gpa")) ?: "",
                        raw = c.getString(c.getColumnIndexOrThrow("raw")) ?: ""
                    )
                )
            }
        }
        return out
    }

    /** 全量替换成绩单，返回写入条数。 */
    fun replaceGrades(ctx: Context, list: List<Grade>): Int {
        val d = db(ctx)
        d.beginTransaction()
        try {
            d.delete("grades", null, null)
            for (g in list) {
                d.insert(
                    "grades", null,
                    ContentValues().apply {
                        put("term", g.term)
                        put("course", g.course)
                        put("credit", g.credit)
                        put("score", g.score)
                        put("gpa", g.gpa)
                        put("raw", g.raw)
                    }
                )
            }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
        return list.size
    }

    /** 纯函数解析一行课程；无法识别时返回 null。 */
    fun parseCourseLine(line: String): ParsedLine? {
        val raw = fullToHalf(cleanInput(line))
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

    /**
     * 只做「清洗」：去 BOM、零宽字符、不换行空格。
     * 注意**不能**在这里剥引号 —— Excel 导出的 CSV 一行里有多组引号
     * （"思想道德与法治","周五",...），整行去引号会把分隔符一起吃掉。
     * 引号由 splitFields 按字段处理；只有整行被一对引号包住时才剥一次。
     */
    private fun cleanInput(s: String): String {
        var t = s.replace("\uFEFF", "").replace("\u200B", "").replace("\u00A0", " ").trim()
        val quotes = t.count { it == '"' || it == '\u201C' || it == '\u201D' }
        if (quotes == 2) t = stripQuotes(t)
        return t
    }

    private fun stripQuotes(s: String): String {
        var t = s.trim()
        val pairs = listOf("\u201C" to "\u201D", "\"" to "\"", "'" to "'", "\u300C" to "\u300D", "\u300E" to "\u300F")
        var changed = true
        var guard = 0
        while (changed && t.length >= 2 && guard++ < 4) {
            changed = false
            for (pr in pairs) {
                if (t.startsWith(pr.first) && t.endsWith(pr.second)) {
                    t = t.substring(pr.first.length, t.length - pr.second.length).trim()
                    changed = true
                }
            }
        }
        return t.replace("\uFEFF", "").replace("\u200B", "").trim()
    }

    /** 表头行（课程名 / 星期 / 节次表头）当作跳过，不算错误 */
    private fun isHeaderLine(s: String): Boolean {
        if (s.any { it.isDigit() }) return false
        return s.contains("课程名称") || s.contains("课程名") ||
            (s.contains("课程") && s.contains("星期")) ||
            (s.contains("星期") && s.contains("节次"))
    }

    /**
     * 切分一行：支持 | 制表符 英文逗号 中文逗号 分号，**且认得 CSV 的引号**
     * （Excel 导出的 CSV 里 "高等数学,实验" 这种带逗号的字段也能正确切开）
     */
    private fun splitFields(t: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quote = false
        for (ch in t) {
            if (ch == '"' || ch == '\u201C' || ch == '\u201D') {
                quote = !quote
                continue
            }
            if (!quote && (ch == '|' || ch == '\t' || ch == ',' || ch == '\uFF0C' ||
                    ch == ';' || ch == '\uFF1B')
            ) {
                out.add(sb.toString())
                sb.setLength(0)
            } else {
                sb.append(ch)
            }
        }
        out.add(sb.toString())
        val cleaned = out.map { stripQuotes(it) }.filter { it.isNotEmpty() }
        if (cleaned.size >= 2) return cleaned
        val bySpace = t.split(Regex("\\s+")).map { stripQuotes(it) }.filter { it.isNotEmpty() }
        return if (bySpace.size >= 2) bySpace else cleaned
    }

    private fun parseWeekday(s: String): Int? {
        val t = fullToHalf(stripQuotes(s.trim()))
            .replace("礼拜", "")
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
