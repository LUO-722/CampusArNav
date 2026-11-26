package com.campus.arnav.net

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.campus.arnav.data.Course
import com.campus.arnav.data.Exam
import com.campus.arnav.data.Grade
import com.campus.arnav.data.JwProfile
import com.campus.arnav.data.ScheduleLogic
import java.io.ByteArrayOutputStream
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 教务系统（正方 jsxsd）客户端：抓登录页、取验证码、表单登录，然后把课表/考试/成绩页面
 * 原文保存到本机，并尽力解析课表。
 *
 * 说明：各校 jsxsd 的表单字段名、接口路径略有差异，这里用「解析登录表单 + 跟随页面里的
 * 课表/考试/成绩链接」的通用做法，不写死某一条路径；解析不出来时至少保留页面原文。
 */
object JwxtClient {

    private const val TIMEOUT = 12_000
    private const val MAX_PAGES = 10
    private var cookieReady = false

    data class Page(val url: String, val status: Int, val body: String)

    /** 登录表单：action 已解析成绝对地址；字段名按常见命名推断。 */
    data class LoginForm(
        val action: String,
        val hidden: Map<String, String>,
        val userField: String,
        val passField: String,
        val codeField: String?,
        val captchaUrl: String?
    )

    data class LoginResult(
        val ok: Boolean,
        val needCaptcha: Boolean,
        val message: String,
        val captcha: Bitmap? = null
    )

    data class SyncResult(
        val ok: Boolean,
        val message: String,
        val saved: List<String>,
        val courses: List<Course>,
        val exams: List<Exam> = emptyList(),
        val grades: List<Grade> = emptyList(),
        val profile: JwProfile = JwProfile(),
        /** 教务课表原文网格（行×列，已转纯文本），用于“一字不差”核对。 */
        val timetableGrid: List<List<String>> = emptyList()
    )

    // ---------------- 基础 HTTP ----------------

    fun installCookies() {
        if (cookieReady) return
        CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ALL))
        cookieReady = true
    }

    private fun normalizeBase(base: String): String {
        var b = base.trim()
        if (b.isEmpty()) return ""
        if (!b.startsWith("http://", true) && !b.startsWith("https://", true)) b = "http://$b"
        if (!b.endsWith("/")) b += "/"
        return b
    }

    private fun open(url: String, method: String, body: ByteArray?, contentType: String?): Page {
        installCookies()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
            instanceFollowRedirects = true
            setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0 Mobile Safari/537.36")
            setRequestProperty("Accept", "text/html,application/xhtml+xml,image/*,*/*;q=0.8")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            if (contentType != null) setRequestProperty("Content-Type", contentType)
        }
        try {
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..399) conn.inputStream else conn.errorStream
            val bytes = stream?.use { readAll(it) } ?: ByteArray(0)
            val charset = charsetOf(conn.contentType, bytes)
            return Page(conn.url.toString(), status, String(bytes, charset))
        } finally {
            conn.disconnect()
        }
    }

    fun get(url: String): Page = open(url, "GET", null, null)

    private fun post(url: String, form: Map<String, String>): Page {
        val body = form.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        return open(url, "POST", body.toByteArray(Charsets.UTF_8),
            "application/x-www-form-urlencoded; charset=UTF-8")
    }

    private fun readAll(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun charsetOf(contentType: String?, bytes: ByteArray): java.nio.charset.Charset {
        Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE)
            .find(contentType ?: "")?.groupValues?.get(1)?.let { return charset(it) }
        val head = String(bytes.copyOfRange(0, minOf(bytes.size, 2048)), Charsets.ISO_8859_1)
        Regex("charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE)
            .find(head)?.groupValues?.get(1)?.let { return charset(it) }
        return Charsets.UTF_8
    }

    private fun charset(name: String): java.nio.charset.Charset = when (name.lowercase()) {
        "gb2312", "gbk", "gb18030" -> Charset_gb18030
        "utf8", "utf-8" -> Charsets.UTF_8
        else -> try {
            java.nio.charset.Charset.forName(name)
        } catch (e: Exception) {
            Charsets.UTF_8
        }
    }

    private val Charset_gb18030: java.nio.charset.Charset = java.nio.charset.Charset.forName("GB18030")

    // ---------------- 登录页解析 ----------------

    fun parseLoginForm(html: String, pageUrl: String): LoginForm? {
        val formBlock = Regex("<form\\b[^>]*>(.*?)</form>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(html)
            .firstOrNull { it.value.contains("password", true) }?.value ?: return null
        val actionRaw = Regex("<form\\b[^>]*action\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
            .find(formBlock)?.groupValues?.get(1) ?: pageUrl
        val action = absolute(pageUrl, actionRaw)
        val hidden = LinkedHashMap<String, String>()
        var userField: String? = null
        var passField: String? = null
        var codeField: String? = null
        Regex("<input\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(formBlock).forEach { m ->
            val tag = m.value
            val name = attr(tag, "name") ?: return@forEach
            val type = (attr(tag, "type") ?: "text").lowercase()
            val value = attr(tag, "value") ?: ""
            when {
                type == "password" -> passField = name
                name.contains("account", true) || name.contains("user", true) ||
                    name.equals("xh", true) || name.contains("loginName", true) -> userField = name
                name.contains("random", true) || name.contains("captcha", true) ||
                    name.contains("validate", true) || name.contains("code", true) ||
                    name.equals("yzm", true) -> codeField = name
                type == "hidden" -> hidden[name] = value
            }
        }
        val captchaUrl = Regex("<img\\b[^>]*src\\s*=\\s*[\"']([^\"']*(?:kaptcha|captcha|validate|checkcode|verify|yzm|randcode)[^\"']*)[\"']",
            RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let { absolute(pageUrl, it) }
        return LoginForm(
            action = action,
            hidden = hidden,
            userField = userField ?: "userAccount",
            passField = passField ?: "userPassword",
            codeField = codeField,
            captchaUrl = captchaUrl
        )
    }

    fun loadImage(url: String): Bitmap? = try {
        installCookies()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
        }
        val bytes = conn.inputStream.use { readAll(it) }
        conn.disconnect()
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) {
        null
    }

    private fun attr(tag: String, key: String): String? {
        val m = Regex("\\b$key\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(tag)
            ?: Regex("\\b$key\\s*=\\s*([^\\s>]+)", RegexOption.IGNORE_CASE).find(tag)
        return m?.groupValues?.get(1)?.trim()
    }

    private fun absolute(pageUrl: String, link: String): String {
        if (link.startsWith("http://", true) || link.startsWith("https://", true)) return link
        val u = URL(pageUrl)
        val port = if (u.port > 0) ":${u.port}" else ""
        val root = "${u.protocol}://${u.host}$port"
        return when {
            link.startsWith("//") -> "${u.protocol}:$link"
            link.startsWith("/") -> root + link
            else -> pageUrl.substringBeforeLast('/') + "/" + link
        }
    }

    // ---------------- 登录 ----------------

    /**
     * 登录教务系统。按正方 jsxsd 网页登录按钮的真实协议：
     *   encoded = base64(学号) + "%%%" + base64(密码)
     *   POST {base}xk/LoginToXk  (loginMethod=LoginToXk / userAccount / userPassword=空 / RANDOMCODE / encoded)
     * 验证码取 {base}verifycode.servlet。[captcha] 为空时先回一次 needCaptcha + 验证码图。
     */
    fun login(base: String, account: String, password: String, captcha: String): LoginResult {
        val root = normalizeBase(base)
        if (root.isEmpty()) return LoginResult(false, false, "还没填教务系统地址。")
        if (account.isBlank() || password.isBlank()) {
            return LoginResult(false, false, "学号和密码都不能为空。")
        }
        return try {
            // 1) 先访问首页拿会话 Cookie（jsxsd 靠 JSESSIONID 串起验证码与登录）
            val landing = get(root)
            if (landing.status !in 200..399) {
                return LoginResult(false, false,
                    "连不上教务系统（HTTP ${landing.status}）：请确认连着校园网，或外网地址填对了。")
            }
            val captchaUrl = root + "verifycode.servlet?t=" + System.currentTimeMillis()
            if (captcha.isBlank()) {
                val bmp = loadImage(captchaUrl)
                return LoginResult(false, true,
                    if (bmp == null) "验证码图片没取到，请检查网络或换个通道。" else "请输入验证码。",
                    bmp)
            }

            // 2) 与网页 submitForm1() 一致的编码与参数
            val payload = LinkedHashMap<String, String>()
            payload["loginMethod"] = "LoginToXk"
            payload["userAccount"] = account.trim()
            payload["userPassword"] = ""
            payload["RANDOMCODE"] = captcha.trim()
            payload["encoded"] = encodeLogin(account, password)
            val resp = post(root + "xk/LoginToXk", payload)
            val after = resp.body
            val loggedIn = resp.status in 300..399 || looksLoggedIn(after)
            if (loggedIn && !after.contains("verifycode.servlet", true)) {
                return LoginResult(true, false, "登录成功。")
            }
            val msg = pickMessage(after) ?: when {
                after.contains("验证码") -> "验证码错误，请重新输入。"
                after.contains("密码") || after.contains("用户名") || after.contains("学号") ->
                    "学号或密码不正确。"
                resp.status == 0 -> "连接被中断，请重试。"
                else -> "登录失败（HTTP ${resp.status}），请检查学号/密码/验证码。"
            }
            LoginResult(false, true, msg, loadImage(captchaUrl))
        } catch (e: java.net.SocketTimeoutException) {
            LoginResult(false, false, "连接超时：请确认连着校园网（内网），或换外网地址再试。")
        } catch (e: java.net.UnknownHostException) {
            LoginResult(false, false, "域名解析失败：教务系统地址可能写错了。")
        } catch (e: java.net.ConnectException) {
            LoginResult(false, false, "连不上服务器：内网地址只在校内网络可用（10.x 网段）；外网地址要在校园网外时段用。")
        } catch (e: Exception) {
            LoginResult(false, false, "登录出错：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 对应网页 encodeInp：标准 Base64（NO_WRAP，无换行）。 */
    fun b64(s: String): String =
        android.util.Base64.encodeToString(s.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)

    /** 正方登录提交的 encoded 字段：base64(学号) + "%%%" + base64(密码)。 */
    fun encodeLogin(account: String, password: String): String =
        b64(account.trim()) + "%%%" + b64(password)

    private fun looksLoggedIn(html: String): Boolean {
        if (html.isBlank()) return false
        val lower = html.lowercase()
        if (lower.contains("type=\"password\"") || lower.contains("type=password")) return false
        return lower.contains("退出") || lower.contains("logout") || lower.contains("xs_main") ||
            lower.contains("xsmain") || lower.contains("我的课表") || lower.contains("学生个人") ||
            lower.contains("kbtable") || html.contains("课表")
    }

    private fun pickMessage(html: String): String? {
        Regex("alert\\(\\s*[\"']([^\"']{2,60})[\"']").find(html)?.let { return clean(it.groupValues[1]) }
        Regex("<(?:div|span|font|p)[^>]*(?:id|class)\\s*=\\s*[\"'][^\"']*(?:error|msg|message|tip|warn)[^\"']*[\"'][^>]*>([^<]{2,60})<",
            RegexOption.IGNORE_CASE).find(html)?.let { return clean(it.groupValues[1]) }
        Regex("<font[^>]*color\\s*=\\s*[\"']?(?:red|#ff0000)[\"']?[^>]*>([^<]{2,60})<",
            RegexOption.IGNORE_CASE).find(html)?.let { return clean(it.groupValues[1]) }
        return null
    }

    private fun clean(s: String): String =
        s.replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").trim().take(60)
// ---------------- 同步：抓课表 / 考试 / 成绩 / 学籍卡片 ----------------

    /** 潘安湖校区编号（教务课表按校区区分，泉山是 3B76…）。 */
    const val CAMPUS_PANANHU = "2AA072D3F1D747B98B4F5F84683493E5"

    /**
     * 登录后把教务系统里的东西尽量全搬回来：课表（单元格原文一字不改）、学籍卡片、
     * 考试安排、成绩单；页面原文另存到 files/jwxt 目录，课表再存一份纯文本网格。
     */
    fun sync(ctx: Context, base: String, onProgress: (String) -> Unit): SyncResult {
        val root = normalizeBase(base)
        if (root.isEmpty()) {
            return SyncResult(false, "还没填教务系统地址。", emptyList(), emptyList())
        }
        val dir = java.io.File(ctx.filesDir, "jwxt").apply { mkdirs() }
        val saved = ArrayList<String>()
        return try {
            onProgress("检查登录会话…")
            var first = get(absolute(root, "framework/xsMainV.htmlx"))
            if (!looksLoggedIn(first.body)) {
                first = get(absolute(root, "framework/xsMain.jsp"))
            }
            if (!looksLoggedIn(first.body)) {
                return SyncResult(false, "会话已失效，请重新登录。", emptyList(), emptyList())
            }
            savePage(dir, "home", first.body)?.let { saved.add(it) }
            val term = ScheduleLogic.semesterId()

            // ① 课表：GET 出来只是空壳，必须 POST 查询表单
            onProgress("读取课表…")
            val tt = post(
                absolute(root, "xskb/xskb_list.do"),
                linkedMapOf(
                    "cj0701id" to "", "zc" to "", "demo" to "", "xnxq01id" to term,
                    "sfFD" to "1", "wkbkc" to "1", "xstzd" to "1", "xswk" to "1",
                    "kbjcmsid" to CAMPUS_PANANHU
                )
            )
            val timetableHtml = if (tt.status in 200..499) tt.body else ""
            if (timetableHtml.isNotBlank()) {
                savePage(dir, "timetable", timetableHtml)?.let { saved.add(it) }
            }
            val courses = parseTimetable(timetableHtml)
            val grid = timetableGrid(timetableHtml)
            if (grid.isNotEmpty()) {
                runCatching {
                    val sb = StringBuilder()
                    for (row in grid) {
                        sb.append(row.joinToString("\t")).append("\n")
                    }
                    java.io.File(dir, "timetable.txt").writeText(sb.toString())
                    if (!saved.contains("timetable.txt")) saved.add("timetable.txt")
                }
            }

            // ② 学籍卡片（姓名 / 学号 / 院系 / 专业 / 班级 / 学制 / 入学日期）
            onProgress("读取学籍信息…")
            val info = get(absolute(root, "grxx/xsxx"))
            val infoHtml = if (info.status in 200..499) info.body else ""
            if (infoHtml.isNotBlank()) savePage(dir, "student", infoHtml)?.let { saved.add(it) }
            val profile = parseProfile(infoHtml)

            // ③ 考试安排：期末查询 + 随堂考查询
            onProgress("读取考试安排…")
            val examHtml = examQuery(root, "xsks/xsksap_list", term)
            if (examHtml.isNotBlank()) savePage(dir, "exam", examHtml)?.let { saved.add(it) }
            var exams = parseExams(examHtml)
            if (exams.isEmpty()) {
                val stk = examQuery(root, "xsks/xsstk_list", term)
                if (stk.isNotBlank()) savePage(dir, "exam_stk", stk)?.let { saved.add(it) }
                exams = parseExams(stk)
            }

            // ④ 成绩单
            onProgress("读取成绩单…")
            val gradeHtml = post(
                absolute(root, "kscj/cjcx_list"),
                linkedMapOf("xnxq01id" to term, "kksj" to "", "kcxz" to "", "kcmc" to "", "xsfs" to "all")
            ).body
            if (gradeHtml.isNotBlank()) savePage(dir, "score", gradeHtml)?.let { saved.add(it) }
            val grades = parseGrades(gradeHtml)

            val msg = buildString {
                append("已保存 ").append(saved.size).append(" 份页面原文")
                append("；课表 ").append(courses.size).append(" 条安排")
                if (exams.isNotEmpty()) append("、考试 ").append(exams.size).append(" 条")
                if (grades.isNotEmpty()) append("、成绩 ").append(grades.size).append(" 条")
                if (profile.name.isNotBlank()) append("；学籍 ").append(profile.name)
                if (courses.isEmpty()) append("（这次没解析出课程，原文已留档，可在「教务原文」里核对）")
            }
            SyncResult(true, msg, saved, courses, exams, grades, profile, grid)
        } catch (e: Exception) {
            SyncResult(false, "同步失败：" + (e.message ?: e.javaClass.simpleName), saved, emptyList())
        }
    }

    /** 考试类查询：表单字段不完全一样，多给几个空值不影响。 */
    private fun examQuery(root: String, path: String, term: String): String {
        val p = try {
            post(
                absolute(root, path),
                linkedMapOf(
                    "xnxq01id" to term, "xqlb" to "", "xqlbmc" to "", "kslx" to "", "kslxmc" to ""
                )
            )
        } catch (e: Exception) {
            return ""
        }
        return if (p.status in 200..499) p.body else ""
    }

    private fun savePage(dir: java.io.File, name: String, html: String): String? = try {
        java.io.File(dir, name + ".html").writeText(html)
        name + ".html"
    } catch (e: Exception) {
        null
    }

    // ---- 课表：强智 / 正方同一套网格 ----

    /** 解析课表：table#timetable，行 = 大节（第一…第五大节），列 = 星期一…星期日。 */
    fun parseTimetable(html: String): List<Course> {
        val table = timetableTable(html)
        if (table.isEmpty()) return emptyList()
        val rows = Regex("<tr[^>]*>([\\s\\S]*?)</tr>", RegexOption.IGNORE_CASE)
            .findAll(table).map { it.groupValues[1] }.toList()
        if (rows.size < 2) return emptyList()

        val days = ArrayList<Int>()
        for (cell in rawCells(rows[0])) {
            val d = weekdayNumber(plain(cell))
            if (d > 0) days.add(d)
        }
        if (days.isEmpty()) days.addAll(listOf(1, 2, 3, 4, 5, 6, 7))

        val out = ArrayList<Course>()
        for (ri in 1 until rows.size) {
            val cells = rawCells(rows[ri])
            if (cells.isEmpty()) continue
            val label = plain(cells[0])
            if (label.contains("备注")) continue
            val span = bigBlockPeriods(label)
            for (ci in 1 until cells.size) {
                val day = days.getOrElse(ci - 1) { ci }
                for (lines in courseBlocks(cells[ci])) {
                    val c = linesToCourse(lines, day, span.first, span.second) ?: continue
                    val dup = out.any {
                        it.name == c.name && it.weekday == c.weekday &&
                            it.startPeriod == c.startPeriod && it.endPeriod == c.endPeriod &&
                            it.weeks == c.weeks
                    }
                    if (!dup) out.add(c)
                }
            }
        }
        return out
    }

    /** 课表原表格的纯文本版（留档、核对「一字不差」用）。 */
    fun timetableGrid(html: String): List<List<String>> {
        val table = timetableTable(html)
        if (table.isEmpty()) return emptyList()
        val out = ArrayList<List<String>>()
        for (r in Regex("<tr[^>]*>([\\s\\S]*?)</tr>", RegexOption.IGNORE_CASE).findAll(table)) {
            val cells = rawCells(r.groupValues[1]).map { plain(it).replace("\n", " / ") }
            if (cells.isNotEmpty()) out.add(cells)
        }
        return out
    }

    private fun timetableTable(html: String): String {
        if (html.isBlank()) return ""
        val key = html.indexOf("id=\"timetable\"")
        if (key < 0) return ""
        val seg = html.substring(key)
        val close = seg.indexOf("</table>")
        return if (close > 0) seg.substring(0, close) else seg
    }

    /** 一格里可能排了好几门课，每门课是一个 div.kbcontent（详细版，含教师/节次/教室）。 */
    private fun courseBlocks(cell: String): List<List<String>> {
        val noImg = cell.replace(
            Regex("<div[^>]*class=['\"]item-box['\"][^>]*>[\\s\\S]*?</div>", RegexOption.IGNORE_CASE),
            ""
        )
        val out = ArrayList<List<String>>()
        for (m in Regex("<div[^>]*class=\"kbcontent\"[^>]*>([\\s\\S]*?)</div>", RegexOption.IGNORE_CASE)
            .findAll(noImg)) {
            val lines = plain(m.groupValues[1]).split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            // 同一格里的多门课用「----------------------」分隔，必须拆开，否则只会剩第一门
            var cur = ArrayList<String>()
            for (ln in lines) {
                if (ln.matches(Regex("[-—\\s]{5,}"))) {
                    if (cur.isNotEmpty()) out.add(cur)
                    cur = ArrayList()
                } else {
                    cur.add(ln)
                }
            }
            if (cur.isNotEmpty()) out.add(cur)
        }
        return out
    }

    /** 把一格里的一段文字变成课程；节次以 [xx-yy节] 为准，缺失时用大节默认值。 */
    private fun linesToCourse(lines: List<String>, day: Int, defStart: Int, defEnd: Int): Course? {
        var name = lines.firstOrNull()?.trim().orEmpty()
        if (name.isEmpty()) return null
        var from = 1
        // 有些课名会拆成两行，如「大学体育A」+「(50607射艺)」
        while (from < lines.size) {
            val nx = lines[from].trim()
            if (nx.startsWith("(") || nx.startsWith("（")) {
                name += nx
                from++
            } else {
                break
            }
        }
        var weeks = ""
        var weekText = ""
        var periodText = ""
        var start = defStart
        var end = defEnd
        var teacher = ""
        var location = ""
        var building = ""
        for (i in from until lines.size) {
            val s = lines[i].trim()
            if (s.isEmpty()) continue
            if (s.contains("[") && s.contains("节")) {
                periodText = s
                val br = Regex("\\[([^\\]]*)\\]").find(s)?.groupValues?.get(1) ?: ""
                // 节次可能写成 01-02、03-04-05，取第一个到最后一个数字才是真实跨度
                val nums = Regex("\\d{1,2}").findAll(br).map { it.value.toIntOrNull() ?: 0 }
                    .filter { it in 1..12 }.toList()
                if (nums.isNotEmpty()) {
                    start = nums.first()
                    end = nums.last().coerceAtLeast(start)
                }
                val w = s.substringBefore("(").substringBefore("（").trim()
                weekText = w
                weeks = w.replace("周", "").trim()
                continue
            }
            if (s.contains("通知单编号")) continue
            if (s.contains("班级")) continue
            if (s.contains("讲课") || s.contains("实验")) continue
            // 教学楼写在【】里，教室紧跟着同一行，例如「【潘安湖致广楼】潘安湖致广楼-303」
            val bm = Regex("【([^】]*)】").find(s)
            if (bm != null) {
                building = bm.groupValues[1].trim()
                val rest = s.substring(bm.range.last + 1).trim()
                if (rest.isNotEmpty()) location = rest
                continue
            }
            if (location.isEmpty() && (s.contains("楼") || s.contains("馆") || s.contains("场") || s.contains("中心"))) {
                location = s
                continue
            }
            if (teacher.isEmpty() && s.length <= 24) teacher = s
        }
        if (location.isEmpty()) location = building
        // 教务写成「潘安湖致广楼-303」，格子里显示时去掉校区前缀（原文仍在 raw 里可核对）
        location = location.replace("潘安湖", "").replace("泉山", "").trim().trimStart('-').trim()
        return Course(
            name = name,
            teacher = teacher,
            location = location,
            weekday = day,
            startPeriod = start.coerceIn(1, 12),
            endPeriod = end.coerceIn(start.coerceIn(1, 12), 12),
            weeks = if (weeks.isBlank()) "1-16" else weeks,
            color = 0,
            source = "jwxt",
            raw = lines.joinToString("\n"),
            periodText = periodText,
            weekText = if (weekText.isBlank()) weeks else weekText
        )
    }

    private fun bigBlockPeriods(label: String): Pair<Int, Int> = when {
        label.contains("一") -> 1 to 2
        label.contains("二") -> 3 to 5
        label.contains("三") -> 6 to 7
        label.contains("四") -> 8 to 9
        label.contains("五") -> 10 to 11
        else -> 1 to 2
    }

    private fun weekdayNumber(s: String): Int {
        val t = s.replace("星期", "").replace("周", "").trim()
        when {
            t.contains("一") -> return 1
            t.contains("二") -> return 2
            t.contains("三") -> return 3
            t.contains("四") -> return 4
            t.contains("五") -> return 5
            t.contains("六") -> return 6
            t.contains("日") || t.contains("天") -> return 7
        }
        return t.toIntOrNull()?.takeIf { it in 1..7 } ?: 0
    }

    // ---- 考试 / 成绩 / 学籍 ----

    /** 考试安排：表头含「课程名称」，列有 考试时间 / 考场 / 座位号。 */
    fun parseExams(html: String): List<Exam> {
        val rows = rowsOf(html)
        val hi = rows.indexOfFirst { r -> r.any { it.contains("课程名称") } }
        if (hi < 0) return emptyList()
        val head = rows[hi]
        val cCourse = colAt(head, "课程名称")
        val cTime = colAt(head, "考试时间")
        val cRoom = colAt(head, "考场", "考试教室")
        val cSeat = colAt(head, "座位号")
        val cWeek = colAt(head, "考试周次")
        val cDay = colAt(head, "考试星期")
        val cJie = colAt(head, "考试节次")
        val out = ArrayList<Exam>()
        for (i in hi + 1 until rows.size) {
            val r = rows[i]
            val course = r.getOrNull(cCourse)?.trim().orEmpty()
            if (course.isEmpty() || course.contains("未查询") || course.contains("序号")) continue
            var time = r.getOrNull(cTime)?.trim().orEmpty()
            if (time.isEmpty() && cWeek >= 0) {
                time = listOf(
                    r.getOrNull(cWeek).orEmpty(), r.getOrNull(cDay).orEmpty(), r.getOrNull(cJie).orEmpty()
                ).filter { it.isNotBlank() }.joinToString(" ")
            }
            out.add(
                Exam(
                    course = course,
                    time = time,
                    location = r.getOrNull(cRoom)?.trim().orEmpty(),
                    seat = r.getOrNull(cSeat)?.trim().orEmpty(),
                    raw = r.joinToString(" | ")
                )
            )
        }
        return out
    }

    /** 成绩单：列有 开课学期 / 课程名称 / 成绩 / 学分 / 绩点。 */
    fun parseGrades(html: String): List<Grade> {
        val rows = rowsOf(html)
        val hi = rows.indexOfFirst { r -> r.any { it.contains("课程名称") } }
        if (hi < 0) return emptyList()
        val head = rows[hi]
        val cTerm = colAt(head, "开课学期")
        val cCourse = colAt(head, "课程名称")
        val cScore = colAt(head, "成绩")
        val cCredit = colAt(head, "学分")
        val cGpa = colAt(head, "绩点")
        val out = ArrayList<Grade>()
        for (i in hi + 1 until rows.size) {
            val r = rows[i]
            val course = r.getOrNull(cCourse)?.trim().orEmpty()
            if (course.isEmpty() || course.contains("未查询") || course.contains("课程名称")) continue
            out.add(
                Grade(
                    term = r.getOrNull(cTerm)?.trim().orEmpty(),
                    course = course,
                    credit = r.getOrNull(cCredit)?.trim().orEmpty(),
                    score = r.getOrNull(cScore)?.trim().orEmpty(),
                    gpa = r.getOrNull(cGpa)?.trim().orEmpty(),
                    raw = r.joinToString(" | ")
                )
            )
        }
        return out
    }

    /**
     * 学籍卡片：院系 / 专业 / 学制 / 班级 / 学号 / 姓名 等，标签后面跟值，
     * 有的写成「院系：人工智能与软件学院」，有的标签与值分属两格，两种都认。
     */
    fun parseProfile(html: String): JwProfile {
        if (html.isBlank()) return JwProfile()
        val flat = html
            .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<br[^>]*>", RegexOption.IGNORE_CASE), "|")
            .replace(Regex("<[^>]+>"), "|")
            .replace("&nbsp;", " ")
        val toks = flat.split("|", "\n").map { it.trim() }.filter { it.isNotEmpty() }
        fun pick(vararg labels: String): String {
            for (label in labels) {
                for (i in toks.indices) {
                    val t = toks[i]
                    if (t == label || t.startsWith(label + "：") || t.startsWith(label + ":")) {
                        if (t.length > label.length + 1) {
                            val v = t.substring(label.length + 1).trim()
                            if (v.isNotEmpty()) return v
                        }
                        val nx = toks.getOrNull(i + 1).orEmpty()
                        if (nx.isNotEmpty() && nx.length <= 40) return nx
                    }
                }
            }
            return ""
        }
        return JwProfile(
            name = pick("姓名"),
            sid = pick("学号"),
            college = pick("院系", "学院", "系部"),
            major = pick("专业"),
            clazz = pick("班级"),
            raw = toks.joinToString(" | ").take(2000)
        )
    }

    private fun rowsOf(html: String): List<List<String>> {
        if (html.isBlank()) return emptyList()
        val body = html.replace(
            Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), ""
        )
        val out = ArrayList<List<String>>()
        for (r in Regex("<tr[^>]*>([\\s\\S]*?)</tr>", RegexOption.IGNORE_CASE).findAll(body)) {
            val cells = rawCells(r.groupValues[1]).map { plain(it) }
            if (cells.isNotEmpty()) out.add(cells)
        }
        return out
    }

    private fun rawCells(row: String): List<String> =
        Regex("<t[dh][^>]*>([\\s\\S]*?)</t[dh]>", RegexOption.IGNORE_CASE)
            .findAll(row).map { it.groupValues[1] }.toList()

    private fun colAt(head: List<String>, vararg keys: String): Int =
        head.indexOfFirst { h -> keys.any { k -> h.contains(k) } }

    /** 去标签、还原换行与实体；返回的每行已 trim。 */
    private fun plain(html: String): String {
        var t = html.replace(Regex("<br[^>]*>", RegexOption.IGNORE_CASE), "\n")
        t = t.replace(Regex("<[^>]+>"), "")
        t = t.replace("&nbsp;", " ")
        t = t.replace("&amp;", "&")
        t = t.replace("&lt;", "<")
        t = t.replace("&gt;", ">")
        t = t.replace("&quot;", "\"")
        return t.split("\n").joinToString("\n") { line ->
            line.replace(Regex("[ \\t\\u00a0]+"), " ").trim()
        }
    }
}