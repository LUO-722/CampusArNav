package com.campus.arnav.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 学期、周次、节次时间与冲突判断。
 *
 * 默认学期：2026—2027 学年第一学期，2026-09-07 第 1 周周一。
 * 用户可在「设置」里改学期开始日期与总周数；[configure] 会在启动时调用。
 */
object ScheduleLogic {

    const val DEFAULT_SEMESTER_NAME = "2026—2027 学年第一学期"
    const val DEFAULT_SEMESTER_START = "2026-09-07"
    const val DEFAULT_TOTAL_WEEKS = 19

    @Volatile private var semesterStartStr = DEFAULT_SEMESTER_START
    @Volatile private var semesterNameStr = DEFAULT_SEMESTER_NAME
    @Volatile private var totalWeeksValue = DEFAULT_TOTAL_WEEKS

    // SimpleDateFormat 非线程安全；用 ThreadLocal 给每个线程一份。
    private val formatter = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            isLenient = false
        }
    }

    private fun df(): SimpleDateFormat = formatter.get()!!

    /** 设置界面保存后调用；参数不合法时保留原值。 */
    fun configure(startDate: String, totalWeeks: Int, semesterName: String) {
        val d = parseDate(startDate) ?: return
        if (totalWeeks !in 1..30) return
        semesterStartStr = df().format(d)
        totalWeeksValue = totalWeeks
        semesterNameStr = semesterName.ifBlank { DEFAULT_SEMESTER_NAME }
    }

    fun semesterName(): String = semesterNameStr
    fun totalWeeks(): Int = totalWeeksValue

    /**
     * 教务系统用的学期编号，如「2026-2027-1」。
     *
     * 教务系统只认这种写法，界面上的「2026—2027 学年第一学期」需要转一下：
     * 取前两个四位数字年份 + 第/二学期序号。
     */
    fun semesterId(): String {
        val name = semesterNameStr
        val years = Regex("(\\d{4})\\D+(\\d{4})").find(name)
        val second = name.contains("第二学期") || name.contains("春季")
        val idx = if (second) "2" else "1"
        if (years != null) {
            return years.groupValues[1] + "-" + years.groupValues[2] + "-" + idx
        }
        val y = Calendar.getInstance().get(Calendar.YEAR)
        return "$y-${y + 1}-$idx"
    }
    fun semesterStartText(): String = semesterStartStr

    fun parseDate(s: String): Date? = try {
        df().parse(s)
    } catch (e: Exception) {
        null
    }

    fun formatDate(d: Date): String = df().format(d)

    fun semesterStart(): Date = parseDate(semesterStartStr) ?: parseDate(DEFAULT_SEMESTER_START)!!

    /** 距第 1 周周一的天数 / 7 + 1。 */
    fun weekOf(date: String): Int {
        val d = parseDate(date) ?: return 0
        val start = semesterStart()
        val days = ((d.time - start.time) / 86_400_000L).toInt()
        return Math.floorDiv(days, 7) + 1
    }

    /** 周几：1=周一 … 7=周日。 */
    fun weekdayOf(date: String): Int {
        val d = parseDate(date) ?: return 1
        val c = Calendar.getInstance()
        c.time = d
        return c.get(Calendar.DAY_OF_WEEK).let { if (it == Calendar.SUNDAY) 7 else it - 1 }
    }

    fun dateOf(week: Int, weekday: Int): String {
        val c = Calendar.getInstance()
        c.time = semesterStart()
        c.add(Calendar.DAY_OF_YEAR, (week - 1) * 7 + (weekday - 1))
        return formatDate(c.time)
    }

    fun currentWeek(): Int = weekOf(formatDate(Date())).coerceIn(1, totalWeeksValue)

    fun weekLabel(week: Int): String {
        val a = dateOf(week, 1)
        val b = dateOf(week, 7)
        return "第 $week 周  ·  ${a.substring(5)} — ${b.substring(5)}"
    }

    fun weekdayName(weekday: Int): String =
        arrayOf("一", "二", "三", "四", "五", "六", "日").getOrElse(weekday - 1) { "?" }

    // ---- 节次作息（科文学院潘安湖校区《教学作息时间表》，全年一套、不分季节） ----

    private val times = arrayOf(
        "08:15-08:55", "09:00-09:40", "09:55-10:35", "10:40-11:20", "11:25-12:05",
        "13:50-14:30", "14:35-15:15", "15:30-16:10", "16:15-16:55",
        "18:30-19:10", "19:15-19:55", "20:00-20:40"
    )

    /**
     * 节次起止时间。科文学院教学作息全年一套（8:15 开始、20:40 结束），
     * 不区分冬夏，因此 date 参数仅为兼容旧调用保留。
     */
    fun periodTime(period: Int, date: String): String {
        return times.getOrElse(period - 1) { "" }
    }

    fun periodRangeMinutes(period: Int, date: String): Pair<Int, Int> {
        val t = periodTime(period, date)
        if (t.isEmpty()) return 0 to 0
        val parts = t.split("-")
        return toMinutes(parts[0]) to toMinutes(parts[1])
    }

    fun toMinutes(hhmm: String): Int {
        val p = hhmm.trim().split(":")
        if (p.size < 2) return 0
        return (p[0].toIntOrNull() ?: 0) * 60 + (p[1].toIntOrNull() ?: 0)
    }

    /** 是否为合法 HH:mm（00:00–23:59）。 */
    fun validClock(hhmm: String): Boolean {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(hhmm.trim()) ?: return false
        val h = m.groupValues[1].toIntOrNull() ?: return false
        val mi = m.groupValues[2].toIntOrNull() ?: return false
        return h in 0..23 && mi in 0..59
    }

    fun fromMinutes(m: Int): String =
        String.format(Locale.US, "%02d:%02d", m / 60, m % 60)

    /** 时间点落在第几节（用于把活动画进课表）。 */
    fun periodForTime(hhmm: String, date: String): Int {
        val m = toMinutes(hhmm)
        for (p in 1..12) {
            val (a, b) = periodRangeMinutes(p, date)
            if (m in a..b) return p
        }
        for (p in 1..12) {
            val (a, _) = periodRangeMinutes(p, date)
            if (m < a) return p
        }
        return 12
    }

    // ---- 周次与冲突 ----

    /** 支持 "1-16" "1-8,10-16" "1,3,5" "1-16单/双" "第1-16周"。 */
    fun weekMatches(spec: String, week: Int): Boolean {
        val s = spec.replace("周", "").replace("第", "").trim()
        if (s.isEmpty()) return true
        var oddEven = 0
        var body = s
        if (body.endsWith("单")) { oddEven = 1; body = body.dropLast(1) }
        if (body.endsWith("双")) { oddEven = 2; body = body.dropLast(1) }
        for (seg in body.split(",", "，")) {
            val t = seg.trim()
            if (t.isEmpty()) continue
            if (t.contains("-")) {
                val p = t.split("-")
                val a = p.getOrNull(0)?.trim()?.toIntOrNull() ?: continue
                val b = p.getOrNull(1)?.trim()?.toIntOrNull() ?: a
                if (week in a..b) {
                    if (oddEven == 1 && week % 2 == 0) continue
                    if (oddEven == 2 && week % 2 == 1) continue
                    return true
                }
            } else {
                val n = t.toIntOrNull() ?: continue
                if (n == week) {
                    if (oddEven == 1 && week % 2 == 0) continue
                    if (oddEven == 2 && week % 2 == 1) continue
                    return true
                }
            }
        }
        return false
    }

    /** 某天某段时间与已有课程是否冲突；返回冲突课程。 */
    fun conflicts(
        courses: List<Course>,
        skips: Set<Long>,
        date: String,
        startTime: String,
        endTime: String
    ): List<Course> {
        val wd = weekdayOf(date)
        val wk = weekOf(date)
        if (startTime.isBlank() || endTime.isBlank()) return emptyList()
        val s = toMinutes(startTime)
        val e = toMinutes(endTime)
        if (e <= s) return emptyList()
        val out = ArrayList<Course>()
        for (c in courses) {
            if (c.weekday != wd) continue
            if (c.id in skips) continue
            if (!weekMatches(c.weeks, wk)) continue
            val cs = periodRangeMinutes(c.startPeriod, date).first
            val ce = periodRangeMinutes(c.endPeriod, date).second
            if (s < ce && e > cs) out.add(c)
        }
        return out
    }

    /** 一节课在某天的开始/结束分钟。 */
    fun courseRange(c: Course, date: String): Pair<Int, Int> =
        periodRangeMinutes(c.startPeriod, date).first to periodRangeMinutes(c.endPeriod, date).second

    // ---- 周次浏览范围与假期（可翻到学期之外） ----

    /** 允许往前往后翻的周次范围：负数是开学前，超过总周数是学期结束之后。 */
    const val WEEK_MIN = -26
    const val WEEK_MAX = 60

    fun clampWeek(w: Int): Int = w.coerceIn(WEEK_MIN, WEEK_MAX)

    /** 学期之外的周次给个明确说法，别一律叫「第 N 周」。 */
    fun weekTitle(week: Int): String = when {
        week < 1 -> "开学前 " + (1 - week) + " 周"
        week > totalWeeksValue -> "学期结束后 " + (week - totalWeeksValue) + " 周"
        else -> "第 " + week + " 周"
    }

    /** 一段假期。 */
    data class Break(val label: String, val start: String, val end: String) {
        fun covers(date: String): Boolean = date >= start && date <= end
    }

    /** 学院校历里的寒暑假（来源：知识库「校历」，只收录已知学期）。 */
    private val breaks = listOf(
        Break("寒假", "2026-01-17", "2026-03-04"),
        Break("暑假", "2026-07-14", "2026-08-31"),
        Break("寒假", "2027-01-16", "2027-02-26")
    )

    fun breakOf(date: String): Break? = breaks.firstOrNull { it.covers(date) }

    /** 这一周 7 天里只要有一段落进假期，就返回那段假期。 */
    fun weekBreak(week: Int): Break? {
        for (wd in 1..7) {
            val b = breakOf(dateOf(week, wd))
            if (b != null) return b
        }
        return null
    }

    /** 已知节假日与调休（来源：学院 2026 年中秋国庆放假通知）。 */
    private val dayMarks: Map<String, String> = HashMap<String, String>().apply {
        put("2026-09-25", "中秋")
        put("2026-09-26", "国庆")
        put("2026-09-27", "国庆")
        put("2026-09-28", "国庆")
        put("2026-09-29", "国庆")
        put("2026-09-30", "国庆")
        put("2026-10-01", "国庆")
        put("2026-10-02", "国庆")
        put("2026-10-03", "国庆")
        put("2026-10-04", "国庆")
        put("2026-10-05", "国庆")
        put("2026-10-06", "国庆")
        put("2026-10-07", "国庆")
        put("2027-01-01", "元旦")
        put("2026-09-20", "补课")
        put("2026-10-10", "补课")
    }

    fun markOf(date: String): String? = dayMarks[date]

    /** 一周 7 天各自的节假日标记（无标记为 null）。 */
    fun weekMarks(week: Int): Array<String?> = Array(7) { i -> markOf(dateOf(week, i + 1)) }

    fun weekDates(week: Int): Pair<String, String> = dateOf(week, 1) to dateOf(week, 7)
}
