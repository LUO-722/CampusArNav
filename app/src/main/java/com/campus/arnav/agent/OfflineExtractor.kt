package com.campus.arnav.agent

import com.campus.arnav.data.ActivityDraft
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本机规则抽取：既没有 API Key、也没有服务端代理时，用纯正则把中文通知拆成活动。
 *
 * 设计约束：
 *  - 纯 Kotlin：不碰 Context、不发网络，[extract] 是可以直接断言的纯函数
 *    （`debug/SelfCheck` 里可像 `NoticeExtractor.normalizeTime` 那样逐条断言）；
 *  - 认不出日期就返回空列表，由 UI 提示用户手动录入，绝不编造活动；
 *  - 与 [NoticeExtractor]（AI 路径）互不依赖：AI 不可用不影响规则，规则失败也不改 AI 行为。
 *
 * 支持的写法（`extract` 会组合识别，同一段里缺哪个字段就留空）：
 *  - 日期：`2026年11月30日` / `2026-11-30` / `2026/11/30` / `2026.11.30` / `11月30日`（补当年）
 *  - 时间：`19:00` / `19：00`（全角冒号）/ `19:00-21:00` / `14:00至16:00` / `19:00~21:00`
 *          / `晚上7点` / `下午2:30` / `上午9点半` / `7点30分`；只有开始时间时结束默认 +90 分钟
 *  - 地点：`地点：xxx`（含“活动地点/举办地点”）、`于xxx举行` / `在xxx举办`、
 *          含「楼/厅/馆/室/场/中心/广场/礼堂」的短句
 *  - 标题：含「讲座/比赛/活动/报名/宣讲/通知…」关键词的行，或首个非时间/地点/称呼行；
 *          去掉“通知/公告”后缀，正常取 4..40 字（「讲座通知」这类关键词标题允许 2..3 字）
 *  - 多条通知：数字序号（`1.` / `2、` / `（3）`）或“第二条通知自己的日期行”处拆分
 *  - 主办方：`主办：xxx` / `主办单位：xxx` / `承办：xxx`；备注：报名/截止/携带/对象等要求行
 */
object OfflineExtractor {

    /** 单次最多分析的行数：粘贴整篇文章时避免无意义的长循环。 */
    private const val MAX_LINES = 400

    // ---------------- 正则 ----------------

    private val DATE_FULL =
        Regex("(\\d{4})\\s*[年./\\-]\\s*(\\d{1,2})\\s*[月./\\-]\\s*(\\d{1,2})\\s*日?")
    private val DATE_MD = Regex("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日?")

    private val TIME_RANGE = Regex(
        "(\\d{1,2})\\s*[:：点时]\\s*(\\d{1,2})?\\s*分?\\s*[-~—－至到]\\s*(\\d{1,2})\\s*[:：点时]\\s*(\\d{1,2})?\\s*分?"
    )
    private val TIME_COLON = Regex("(\\d{1,2})\\s*[:：]\\s*(\\d{2})")
    private val TIME_POINT = Regex("(?<!第)(\\d{1,2})\\s*点\\s*(半|(\\d{1,2})\\s*分)?")

    private val LOC_LABEL =
        Regex("(?:活动地点|举办地点|讲座地点|集合地点|地点)\\s*[:：]\\s*([^\\n，,。；;]{2,40})")
    private val LOC_AT =
        Regex("(?:在|于)\\s*([\\u4e00-\\u9fffA-Za-z0-9（）()\\-]{2,30}?)\\s*(?:举行|举办|召开|开展)")
    private val PLACE_HINT =
        Regex("[楼厅馆室场]|中心|广场|操场|礼堂|报告厅|会议室|体育馆|田径场|教室")

    private val ORGANIZER =
        Regex("(?:主办单位|承办单位|主办方|主办|承办|组织单位)\\s*[:：]?\\s*([^\\n，,。；;]{2,30})")
    private val NOTE_HINT = Regex("截止|报名|扫码|携带|凭|对象|名额|提前|签到|集合")

    /** 报名截止/提交时间这类“第二个日期”不是活动日期，不当作下一条通知的开头。 */
    private val DEADLINE_PREFIX = listOf(
        "报名截止", "截止", "截稿", "报名时间", "提交", "上交", "报送", "投递"
    )

    private val NUMBERED = Regex(
        "^\\s*(?:\\d{1,2}\\s*[.、)）]|[（(]\\s*\\d{1,2}\\s*[)）]|[一二三四五六七八九十]\\s*[、.])\\s*\\S"
    )

    /** 这些行是要求/落款/表头，不是标题。 */
    private val NOISE_PREFIXES = listOf(
        "时间", "日期", "地点", "活动时间", "活动地点", "举办时间", "举办地点",
        "报名时间", "报名方式", "报名截止", "截止", "主讲", "主持人", "嘉宾", "对象",
        "参加对象", "面向", "联系", "咨询", "电话", "邮箱", "主办", "承办", "协办",
        "指导", "附件", "备注", "要求", "请", "注意", "特此", "欢迎", "各位", "同学",
        "老师", "通知对象", "温馨提示", "补充", "说明"
    )

    /** 出现这些词的行更可能是活动标题。 */
    private val TITLE_WORDS = listOf(
        "讲座", "报告会", "报告", "沙龙", "分享会", "交流会", "座谈会", "读书会", "工作坊",
        "比赛", "大赛", "竞赛", "决赛", "初赛", "选拔", "运动会", "展览", "演出", "晚会",
        "音乐会", "观影", "培训", "宣讲", "招聘", "双选", "答辩", "班会", "会议", "开幕式",
        "闭幕式", "活动", "报名", "通知", "公告"
    )

    // ---------------- 入口 ----------------

    /**
     * 从一段通知里抽活动；一条日期都认不出时返回空列表。
     *
     * @param today 基准日期 `yyyy-MM-dd`，决定“11月30日”补哪一年；默认取系统当天，
     *              测试时可以显式传固定日期，保证断言稳定。
     */
    fun extract(text: String, today: String = todayText()): List<ActivityDraft> {
        if (text.isBlank()) return emptyList()
        val norm = normalize(text)
        if (norm.isBlank()) return emptyList()
        val lines = norm.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_LINES)
        if (lines.isEmpty()) return emptyList()

        val year = yearOf(today)
        val out = ArrayList<ActivityDraft>()
        val seen = HashSet<String>()
        for (block in splitBlocks(lines)) {
            val d = parseBlock(block, year) ?: continue
            // 同一标题 + 同一天 + 同一开始时间视为重复
            if (!seen.add(d.title + "|" + d.date + "|" + d.startTime)) continue
            out.add(d)
        }
        return out
    }

    /** 系统当天日期（yyyy-MM-dd）；[extract] 的默认参数用它，所以抽取器本身不依赖 Context。 */
    fun todayText(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** 全角数字/冒号/破折号归一，便于后面只用一套正则。 */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch in '０'..'９' -> sb.append('0' + (ch - '０'))
                ch == '：' -> sb.append(':')
                ch == '－' || ch == '—' || ch == '–' || ch == '～' -> sb.append('-')
                ch == '．' -> sb.append('.')
                ch == '／' -> sb.append('/')
                ch == '　' -> sb.append(' ')
                ch == '\r' -> Unit
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    // ---------------- 拆块（多条通知） ----------------

    private fun splitBlocks(lines: List<String>): List<List<String>> {
        val blocks = ArrayList<MutableList<String>>()
        var cur = ArrayList<String>()
        for (line in lines) {
            val startsItem = NUMBERED.containsMatchIn(line)
            val hasDate = lineHasDate(line) && !isDeadlineLine(line)
            if (startsItem && cur.isNotEmpty()) {
                // 1. / 2、/（3）这类序号：直接开新块
                blocks.add(cur)
                cur = ArrayList()
            } else if (hasDate && cur.any { lineHasDate(it) }) {
                // 已有日期的块里又出现日期行：把它当作下一条通知的开头
                val titleLine = cur.lastOrNull()?.takeIf { isTitleCandidate(it) }
                if (titleLine != null && cur.size > 1) cur.removeAt(cur.size - 1)
                if (cur.isNotEmpty()) blocks.add(cur)
                cur = ArrayList()
                if (titleLine != null) cur.add(titleLine)
            }
            cur.add(line)
        }
        if (cur.isNotEmpty()) blocks.add(cur)
        return blocks
    }

    // ---------------- 单块解析 ----------------

    private fun parseBlock(lines: List<String>, year: Int): ActivityDraft? {
        val text = lines.joinToString("\n")
        val date = findDate(text, year) ?: return null
        val title = findTitle(lines) ?: return null
        val times = findTimes(text)
        return ActivityDraft(
            title = title,
            date = date,
            startTime = times.first,
            endTime = times.second,
            location = findLocation(lines, text),
            organizer = findOrganizer(text),
            note = findNote(lines, title)
        )
    }

    private fun findDate(text: String, year: Int): String? {
        for (m in DATE_FULL.findAll(text)) {
            val y = m.groupValues[1].toIntOrNull() ?: continue
            val mo = m.groupValues[2].toIntOrNull() ?: continue
            val d = m.groupValues[3].toIntOrNull() ?: continue
            if (mo in 1..12 && d in 1..31) return ymd(y, mo, d)
        }
        for (m in DATE_MD.findAll(text)) {
            val mo = m.groupValues[1].toIntOrNull() ?: continue
            val d = m.groupValues[2].toIntOrNull() ?: continue
            if (mo in 1..12 && d in 1..31) return ymd(year, mo, d)
        }
        return null
    }

    private fun findTimes(text: String): Pair<String, String> {
        // 1) 明确区间：19:00-21:00 / 晚上7点至9点 / 下午2:30-4:30
        TIME_RANGE.find(text)?.let { m ->
            val line = lineAround(text, m.range.first)
            val s = clockOf(m.groupValues[1], m.groupValues[2], line)
            val e = clockOf(m.groupValues[3], m.groupValues[4], line)
            if (s != null && e != null) {
                val sm = s.first * 60 + s.second
                var em = e.first * 60 + e.second
                // “晚上7点-1点”：结束时刻补到次日尺度
                if (em <= sm && e.first <= 12) em += 12 * 60
                if (em <= sm) em = sm + 90
                return fmt(sm) to fmt(min(em, 23 * 60 + 59))
            }
        }
        // 2) 单个冒号时间：19:00
        TIME_COLON.find(text)?.let { m ->
            val s = clockOf(m.groupValues[1], m.groupValues[2], lineAround(text, m.range.first))
            if (s != null) {
                val sm = s.first * 60 + s.second
                return fmt(sm) to fmt(min(sm + 90, 23 * 60 + 59))
            }
        }
        // 3) “晚上7点半”这类中文时刻
        TIME_POINT.find(text)?.let { m ->
            val s = clockOf(m.groupValues[1], m.groupValues[2], lineAround(text, m.range.first))
            if (s != null) {
                val sm = s.first * 60 + s.second
                return fmt(sm) to fmt(min(sm + 90, 23 * 60 + 59))
            }
        }
        return "" to ""
    }

    private fun findTitle(lines: List<String>): String? {
        val keywordHits = ArrayList<String>()
        for (line in lines) {
            if (isNoise(line) || !hasTitleWord(line)) continue
            val t = cleanTitle(line)
            if (t.length in 2..40) keywordHits.add(t)
        }
        // 关键词行里优先 4 字以上的（「人工智能讲座」优于「讲座」）
        keywordHits.firstOrNull { it.length in 4..40 }?.let { return it }
        keywordHits.firstOrNull()?.let { return it }
        // 没有关键词：取首个非噪声行
        for (line in lines) {
            if (isNoise(line)) continue
            val t = cleanTitle(line)
            if (t.length in 4..40) return t
        }
        return null
    }

    private fun findLocation(lines: List<String>, text: String): String {
        LOC_LABEL.find(text)?.let { return trimPlace(it.groupValues[1]) }
        LOC_AT.find(text)?.let { return trimPlace(it.groupValues[1]) }
        for (line in lines) {
            if (isNoise(line) || lineHasDate(line)) continue
            if (!PLACE_HINT.containsMatchIn(line)) continue
            if (line.length in 2..30) return trimPlace(line)
        }
        return ""
    }

    private fun findOrganizer(text: String): String {
        val m = ORGANIZER.find(text) ?: return ""
        return trimPlace(m.groupValues[1])
    }

    private fun findNote(lines: List<String>, title: String): String {
        for (line in lines) {
            // 和标题同一套清洗：去掉“2. ”这类序号，避免备注里重复一条标题
            val t = cleanTitle(line)
            if (t == title || t.length !in 2..60) continue
            // 报名截止/提交时间这类行带着日期（也含“报名”），但它们正是要找的备注
            if (isDeadlineLine(t)) {
                if (NOTE_HINT.containsMatchIn(t)) return t
                continue
            }
            if (lineHasDate(t) || isNoise(t) || hasTitleWord(t)) continue
            if (NOTE_HINT.containsMatchIn(t)) return t
        }
        return ""
    }

    // ---------------- 小工具 ----------------

    private fun ymd(y: Int, mo: Int, d: Int): String =
        String.format(Locale.US, "%04d-%02d-%02d", y, mo, d)

    private fun yearOf(today: String): Int =
        today.substringBefore('-').toIntOrNull()?.takeIf { it in 1900..2200 }
            ?: SimpleDateFormat("yyyy", Locale.US).format(Date()).toInt()

    private fun fmt(minutes: Int): String =
        String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60)

    private fun min(a: Int, b: Int): Int = if (a < b) a else b

    private fun lineHasDate(line: String): Boolean =
        DATE_FULL.containsMatchIn(line) || DATE_MD.containsMatchIn(line)

    private fun hasTitleWord(line: String): Boolean =
        TITLE_WORDS.any { line.contains(it) }

    private fun isRequestLine(line: String): Boolean =
        NOISE_PREFIXES.any { line.startsWith(it) }

    /** 报名截止/提交时间这类行：带日期，但不是活动本身的时间。 */
    private fun isDeadlineLine(line: String): Boolean =
        DEADLINE_PREFIX.any { line.trim().startsWith(it) }

    /** 取 index 所在的那一行，用于判断“上午/下午/晚上”这类上下文。 */
    private fun lineAround(text: String, index: Int): String {
        val prev = text.lastIndexOf('\n', (index - 1).coerceAtLeast(0))
        val start = if (prev < 0) 0 else prev + 1
        val next = text.indexOf('\n', index)
        val end = if (next < 0) text.length else next
        return text.substring(start, end)
    }

    private fun isTitleCandidate(line: String): Boolean {
        val t = cleanTitle(line)
        if (t.length !in 2..40) return false
        if (isRequestLine(line) || lineHasDate(line)) return false
        if (LOC_LABEL.containsMatchIn(line)) return false
        if (TIME_RANGE.containsMatchIn(line) || TIME_COLON.containsMatchIn(line)) return false
        if (TIME_POINT.containsMatchIn(line)) return false
        return true
    }

    private fun isNoise(line: String): Boolean {
        val t = line.trim()
        if (t.length <= 1) return true
        if (isRequestLine(t)) return true
        if (lineHasDate(t)) return true
        if (LOC_LABEL.containsMatchIn(t)) return true
        // 纯时间/纯“于某地举行”的行不是标题
        if (!hasTitleWord(t)) {
            if (TIME_RANGE.containsMatchIn(t)) return true
            if (LOC_AT.containsMatchIn(t)) return true
            // 抬头/落款：学院、大学、办公室、团委……
            if (t.endsWith("学院") || t.endsWith("大学") || t.endsWith("学校") ||
                t.endsWith("办公室") || t.endsWith("团委") || t.endsWith("学生会")
            ) return true
        }
        return false
    }

    private fun cleanTitle(raw: String): String {
        var t = raw.trim()
        // 行首序号：1. / 2、/（3）
        t = t.replace(Regex("^[\\s\\d一二三四五六七八九十]{1,3}\\s*[.、)）:：]\\s*"), "")
        // 行首尾装饰括号
        t = t.replace(Regex("^[【\\[（(「『]+"), "").replace(Regex("[】\\]）)」』]+$"), "")
        t = t.replace(Regex("\\s+"), " ")
        // 去掉“通知/公告/通告”后缀（“关于…的通知” → “关于…”）
        t = t.replace(Regex("(?:的)?\\s*(?:通知|公告|通告|函)\\s*$"), "")
        t = t.replace(Regex("的\\s*$"), "")
        t = t.replace(Regex("[\\s，,。；;：:、]+$"), "")
        return t.trim()
    }

    /** 地点清洗：去掉尾随的时间/“举行”以及多余标点。 */
    private fun trimPlace(v: String): String {
        var t = v.trim()
        t = t.replace(Regex("\\s*\\d{1,2}[:：]\\d{2}.*$"), "")
        t = t.replace(Regex("\\s*(?:举行|举办|召开|开展).*$"), "")
        t = t.replace(Regex("[\\s，,。；;：:、]+"), " ")
        t = t.replace(Regex("\\s+"), "")
        return if (t.length in 2..40) t else ""
    }

    /**
     * "19"/"00"/line → (19, 0)；“半”按 30 分；按行内 上午/下午/晚上 修正 12 小时制。
     */
    private fun clockOf(hStr: String?, mStr: String?, line: String): Pair<Int, Int>? {
        val h = hStr?.trim()?.toIntOrNull() ?: return null
        val mi = when {
            mStr.isNullOrBlank() -> 0
            mStr.contains("半") -> 30
            else -> mStr.filter { it.isDigit() }.toIntOrNull() ?: 0
        }
        if (mi !in 0..59) return null
        var hh = h
        val pm = line.contains("下午") || line.contains("晚上") || line.contains("傍晚") ||
            line.contains("今晚") || line.contains("夜里") || line.contains("夜间") ||
            line.contains("午后")
        val noon = line.contains("中午")
        val am = line.contains("上午") || line.contains("早上") || line.contains("清晨") ||
            line.contains("凌晨") || line.contains("明早")
        when {
            pm && hh in 1..11 -> hh += 12
            noon && hh in 1..10 -> hh += 12
            am && hh == 12 -> hh = 0
        }
        if (hh !in 0..23) return null
        return hh to mi
    }
}