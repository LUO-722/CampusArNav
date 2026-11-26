package com.campus.arnav.net

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.campus.arnav.data.Course
import com.campus.arnav.data.Exam
import com.campus.arnav.data.Grade
import com.campus.arnav.data.JwProfile
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

// ---------------- 同步：抓课表 / 考试 / 成绩 / 学生信息 ----------------

    /**
     * 登录后把教务系统里的东西尽量全搬回来：学生信息、课表（连单元格原文一起留）、
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
            onProgress("读取学生首页…")
            val home = get(root)
            val first = if (looksLoggedIn(home.body)) home else get(absolute(root, "framework/xsMain.jsp"))
            if (!looksLoggedIn(first.body)) {
                return SyncResult(false, "会话已失效，请重新登录。", emptyList(), emptyList())
            }
            savePage(dir, "home", first.body)?.let { saved.add(it) }

            val links = collectLinks(first.body, first.url)
            val kbLinks = links.filter { it.second.contains("课表") || it.second.contains("课程") }
            val examLinks = links.filter { it.second.contains("考试") }
            val gradeLinks = links.filter { it.second.contains("成绩") }
            val infoLinks = links.filter { it.second.contains("个人信息") || it.second.contains("学籍") }

            // ① 课表：先试首页链接，再试正方常见入口，直到能解析出课程为止
            onProgress("读取课表…")
            val kbUrls = ArrayList<String>()
            kbLinks.forEach { kbUrls.add(it.first) }
            kbUrls.add(absolute(root, "xskb/xskb_list.do"))
            kbUrls.add(absolute(root, "xskb/xskb_list.do?demo=1"))
            var timetableHtml = ""
            for (u in kbUrls.distinct()) {
                val p = try {
                    get(u)
                } catch (e: Exception) {
                    continue
                }
                if (p.status !in 200..399 || p.body.length < 300) continue
                if (timetableHtml.isEmpty()) timetableHtml = p.body
                if (parseTimetable(p.body).isNotEmpty()) {
                    timetableHtml = p.body
                    break
                }
            }
            if (timetableHtml.isNotBlank()) savePage(dir, "timetable", timetableHtml)?.let { saved.add(it) }

            // ② 考试
            onProgress("读取考试安排…")
            val examUrls = ArrayList<String>()
            examLinks.forEach { examUrls.add(it.first) }
            examUrls.add(absolute(root, "kwgl/kscx_cxXsksxxIndex.do"))
            val examPage = fetchFirst(examUrls)
            var examHtml = ""
            if (examPage != null) {
                examHtml = examPage.body
                savePage(dir, "exam", examHtml)?.let { saved.add(it) }
            }

            // ③ 成绩
            onProgress("读取成绩单…")
            val gradeUrls = ArrayList<String>()
            gradeLinks.forEach { gradeUrls.add(it.first) }
            gradeUrls.add(absolute(root, "kscj/cjcx_list.do"))
            val gradePage = fetchFirst(gradeUrls)
            var gradeHtml = ""
            if (gradePage != null) {
                gradeHtml = gradePage.body
                savePage(dir, "score", gradeHtml)?.let { saved.add(it) }
            }

            // ④ 学生信息（姓名 / 学号 / 学院 / 专业 / 班级）
            onProgress("读取学生信息…")
            var infoHtml = first.body
            val infoPage = fetchFirst(infoLinks.map { it.first }, minLen = 200)
            if (infoPage != null) {
                infoHtml = infoPage.body
                savePage(dir, "student", infoHtml)?.let { saved.add(it) }
            }

            onProgress("解析课表…")
            val courses = if (timetableHtml.isNotBlank()) parseTimetable(timetableHtml) else emptyList()
            val grid = if (timetableHtml.isNotBlank()) pickGrid(timetableHtml) else emptyList()
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
            val exams = if (examHtml.isNotBlank()) parseExams(examHtml) else emptyList()
            val grades = if (gradeHtml.isNotBlank()) parseGrades(gradeHtml) else emptyList()
            val profile = parseProfile(infoHtml)

            val msg = buildString {
                append("已保存 ")
                append(saved.size)
                append(" 个文件到本机")
                append("；课表 ")
                append(courses.size)
                append(" 门")
                if (exams.isNotEmpty()) append("、考试 ").append(exams.size).append(" 条")
                if (grades.isNotEmpty()) append("、成绩 ").append(grades.size).append(" 条")
                if (courses.isEmpty()) append("（课表这次没解析出课程，原文已保留，可在「教务原文」里对照）")
            }
            SyncResult(true, msg, saved, courses, exams, grades, profile, grid)
        } catch (e: Exception) {
            SyncResult(false, "同步失败：" + (e.message ?: e.javaClass.simpleName), saved, emptyList())
        }
    }

    /** 依次试几个候选地址，返回第一个像样的页面。 */
    private fun fetchFirst(urls: List<String>, minLen: Int = 300): Page? {
        for (u in urls.distinct().take(6)) {
            val p = try {
                get(u)
            } catch (e: Exception) {
                continue
            }
            if (p.status !in 200..399) continue
            if (p.body.length < minLen) continue
            return p
        }
        return null
    }

    private fun savePage(dir: java.io.File, name: String, html: String): String? = try {
        val f = java.io.File(dir, name + ".html")
        f.writeText(html)
        name + ".html"
    } catch (e: Exception) {
        null
    }

    private fun collectLinks(html: String, pageUrl: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val re = Regex(
            "<a\\b[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        for (m in re.findAll(html)) {
            val href = m.groupValues[1].trim()
            if (href.startsWith("javascript", true) || href.startsWith("#")) continue
            val text = m.groupValues[2].replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").trim()
            if (text.isEmpty()) continue
            out.add(absolute(pageUrl, href) to text)
        }
        return out.distinctBy { it.first }
    }

    // ---------------- 通用表格 → 文本网格 ----------------

    /** 把页面里所有表格抽成「行 × 列」的纯文本网格，单元格内换行保留。 */
    fun tables(html: String): List<List<List<String>>> {
        val out = ArrayList<List<List<String>>>()
        val tableRe = Regex("<table\\b[^>]*>.*?</table>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val rowRe = Regex("<tr\\b[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val cellRe = Regex("<t[dh]\\b[^>]*>(.*?)</t[dh]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        for (tm in tableRe.findAll(html)) {
            val rows = ArrayList<List<String>>()
            for (rm in rowRe.findAll(tm.value)) {
                val cells = ArrayList<String>()
                for (cm in cellRe.findAll(rm.groupValues[1])) {
                    cells.add(cellLines(cm.groupValues[1]).joinToString("\n"))
                }
                if (cells.isNotEmpty()) rows.add(cells)
            }
            if (rows.isNotEmpty()) out.add(rows)
        }
        return out
    }

    /** 选最像课表的表格（kbtable / kbcontent 优先，否则取格子最多的那张）。 */
    private fun pickTimetableTable(html: String): String? {
        val all = Regex("<table\\b[^>]*>.*?</table>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(html).map { it.value }.toList()
        if (all.isEmpty()) return null
        for (t in all) {
            if (t.contains("kbtable", true) || t.contains("kbcontent", true)) return t
        }
        var best: String? = null
        var bestScore = -1
        for (t in all) {
            var score = Regex("<t[dh]\\b", RegexOption.IGNORE_CASE).findAll(t).count()
            if (t.contains("星期")) score += 500
            if (score > bestScore) {
                bestScore = score
                best = t
            }
        }
        return best
    }

    /** 课表的纯文本网格（含表头），用于原文核对。 */
    fun pickGrid(html: String): List<List<String>> {
        val table = pickTimetableTable(html) ?: return emptyList()
        val rows = ArrayList<List<String>>()
        val rowRe = Regex("<tr\\b[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val cellRe = Regex("<t[dh]\\b[^>]*>(.*?)</t[dh]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        for (rm in rowRe.findAll(table)) {
            val cells = ArrayList<String>()
            for (cm in cellRe.findAll(rm.groupValues[1])) {
                cells.add(cellLines(cm.groupValues[1]).joinToString("\n"))
            }
            var any = false
            for (c in cells) if (c.isNotBlank()) any = true
            if (any) rows.add(cells)
        }
        return rows
    }

    // ---------------- 课表解析（保留原文） ----------------

    /**
     * 把教务系统课表表格转成课程列表。
     * 支持：行=节次、列=星期一…星期日，单元格可带 rowspan 合并连续节次。
     * 每门课都带上单元格原文（Course.raw）、原始节次文字与实际周次文字，供详情页原样展示。
     */
    fun parseTimetable(html: String): List<Course> {
        val table = pickTimetableTable(html) ?: return emptyList()
        val rowRe = Regex("<tr\\b[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val cellRe = Regex(
            "<t[dh]\\b([^>]*)>(.*?)</t[dh]>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val rowList = rowRe.findAll(table).map { it.groupValues[1] }.toList()
        if (rowList.size < 2) return emptyList()

        // 每个单元格存成「属性 + 分隔符 + 正文」，避免再拼一次正则
        val cellsOf: (String) -> List<Pair<String, String>> = { row ->
            cellRe.findAll(row).map { it.groupValues[1] to it.groupValues[2] }.toList()
        }

        // 1) 表头认星期列
        val dayOfColumn = HashMap<Int, Int>()
        run {
            var c = 0
            for ((attrs, body) in cellsOf(rowList[0])) {
                val m = Regex("(?:星期|周)([一二三四五六日天1-7])").find(plain(body))
                if (m != null) dayOfColumn[c] = weekdayOf(m.groupValues[1])
                var span = Regex("colspan\\s*=\\s*[\"']?(\\d+)", RegexOption.IGNORE_CASE)
                    .find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                if (span < 1) span = 1
                c += span
            }
        }
        val headerHasDays = dayOfColumn.size >= 3

        val occupied = HashSet<Int>()
        val out = ArrayList<Course>()
        val firstColIsLabel = run {
            val head = cellsOf(rowList[0]).firstOrNull() ?: return@run false
            val t = plain(head.second)
            t.contains("节次") || t.contains("时间") || t.contains("星期")
        }

        for ((r, rowHtml) in rowList.withIndex()) {
            if (r == 0) continue
            var c = 0
            for ((attrs, body) in cellsOf(rowHtml)) {
                while (occupied.contains(r * 16 + c)) c++
                val rowspan = Regex("rowspan\\s*=\\s*[\"']?(\\d+)", RegexOption.IGNORE_CASE)
                    .find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val colspan = Regex("colspan\\s*=\\s*[\"']?(\\d+)", RegexOption.IGNORE_CASE)
                    .find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                if (rowspan > 1 || colspan > 1) {
                    for (dr in 0 until rowspan) {
                        for (dc in 0 until colspan) {
                            if (dr == 0 && dc == 0) continue
                            occupied.add((r + dr) * 16 + (c + dc))
                        }
                    }
                }
                val col = c
                c += colspan
                val lines = cellLines(body)
                if (lines.isEmpty()) continue

                // 节次列本身（第1节 / 1-2 节）不作为课程块
                if (firstColIsLabel && col == 0) continue

                val weekday = when {
                    headerHasDays -> dayOfColumn[col] ?: (if (col == 0 && !firstColIsLabel) 1 else -1)
                    else -> col
                }
                if (weekday !in 1..7) continue

                // 节次：优先用行首标签的数字，否则按行号推算
                val labelText = plain(lines.first())
                val labelNum = Regex("(\\d{1,2})").find(labelText)?.groupValues?.get(1)?.toIntOrNull()
                val startPeriod = (labelNum ?: r).coerceIn(1, 12)
                val endPeriod = (startPeriod + rowspan - 1).coerceIn(startPeriod, 12)

                var name = ""
                for (line in lines) {
                    if (line.length < 2) continue
                    if (line.contains("星期") || line.contains("节次")) continue
                    if (Regex("^第?\\d{1,2}(-\\d{1,2})?节?$").matches(line)) continue
                    name = line
                    break
                }
                if (name.isEmpty()) continue
                val rest = lines.filter { it != name }
                var weekLine: String? = null
                for (line in rest) {
                    if (line.contains("周")) {
                        weekLine = line
                        break
                    }
                }
                val periodInCell = if (weekLine == null) {
                    null
                } else {
                    Regex("\\(([^)]*节[^)]*)\\)").find(weekLine!!)?.groupValues?.get(1)
                }
                var location = ""
                for (line in rest) {
                    if (line == weekLine) continue
                    if (line.contains("楼") || line.contains("教") || line.contains("室") ||
                        line.contains("馆") || line.contains("场") || line.contains("区") ||
                        line.contains("机房") || line.contains("实验")
                    ) {
                        location = line
                        break
                    }
                }
                var teacher = ""
                for (line in rest) {
                    if (line == weekLine || line == location) continue
                    if (line.length in 2..20 && Regex("[\\u4e00-\\u9fa5·,，、 ]{2,20}").matches(line)) {
                        teacher = line
                        break
                    }
                }
                out.add(
                    Course(
                        name = name.take(60),
                        teacher = teacher,
                        location = location.take(40),
                        weekday = weekday,
                        startPeriod = startPeriod,
                        endPeriod = endPeriod,
                        weeks = normalizeWeeks(weekLine),
                        color = 0,
                        source = "jwxt",
                        raw = lines.joinToString("\n"),
                        periodText = periodInCell ?: labelText,
                        weekText = weekLine ?: ""
                    )
                )
            }
        }
        return out.distinctBy {
            it.name + "|" + it.weekday + "|" + it.startPeriod + "|" + it.endPeriod + "|" + it.weeks
        }
    }

    // ---------------- 考试 / 成绩 / 学生信息 ----------------

    /** 找表头里同时出现这些关键字的行；找不到返回 null。 */
    private fun headerRowOf(rows: List<List<String>>, vararg keys: String): Pair<Int, List<String>>? {
        for ((i, row) in rows.withIndex()) {
            val joined = row.joinToString(" ")
            var all = true
            for (k in keys) if (!joined.contains(k)) all = false
            if (all) return i to row
        }
        return null
    }

    private fun colOf(header: List<String>, vararg keys: String): Int {
        for ((i, cell) in header.withIndex()) {
            for (k in keys) if (cell.contains(k)) return i
        }
        return -1
    }

    fun parseExams(html: String): List<Exam> {
        for (rows in tables(html)) {
            val hit = headerRowOf(rows, "课程", "考试") ?: headerRowOf(rows, "考试") ?: null ?: continue
            val header = hit.second
            val ciCourse = colOf(header, "课程", "科目")
            val ciTime = colOf(header, "时间", "日期")
            val ciLoc = colOf(header, "地点", "考场", "教室")
            val ciSeat = colOf(header, "座位", "座号")
            if (ciCourse < 0) continue
            val out = ArrayList<Exam>()
            for (row in rows.drop(hit.first + 1)) {
                val course = row.getOrNull(ciCourse)?.trim().orEmpty()
                if (course.isBlank() || course == "课程") continue
                out.add(
                    Exam(
                        course = course,
                        time = row.getOrNull(ciTime)?.trim().orEmpty(),
                        location = row.getOrNull(ciLoc)?.trim().orEmpty(),
                        seat = row.getOrNull(ciSeat)?.trim().orEmpty(),
                        raw = row.joinToString(" | ")
                    )
                )
            }
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    fun parseGrades(html: String): List<Grade> {
        for (rows in tables(html)) {
            val hit = headerRowOf(rows, "课程", "成绩") ?: continue
            val header = hit.second
            val ciTerm = colOf(header, "学期", "学年")
            val ciCourse = colOf(header, "课程", "科目")
            val ciCredit = colOf(header, "学分")
            val ciScore = colOf(header, "成绩", "分数", "总评")
            val ciGpa = colOf(header, "绩点")
            if (ciCourse < 0) continue
            val out = ArrayList<Grade>()
            for (row in rows.drop(hit.first + 1)) {
                val course = row.getOrNull(ciCourse)?.trim().orEmpty()
                if (course.isBlank() || course == "课程") continue
                out.add(
                    Grade(
                        term = row.getOrNull(ciTerm)?.trim().orEmpty(),
                        course = course,
                        credit = row.getOrNull(ciCredit)?.trim().orEmpty(),
                        score = row.getOrNull(ciScore)?.trim().orEmpty(),
                        gpa = row.getOrNull(ciGpa)?.trim().orEmpty(),
                        raw = row.joinToString(" | ")
                    )
                )
            }
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    /** 从学生首页 / 个人信息页里抠姓名、学号、学院、专业、班级。 */
    fun parseProfile(html: String): JwProfile {
        val text = cellLines(html)
        fun lineAfter(vararg keys: String): String {
            for (key in keys) {
                for ((i, line) in text.withIndex()) {
                    val idx = line.indexOf(key)
                    if (idx < 0) continue
                    val after = line.substring(idx + key.length).trim().trim('：', ':', ' ', '>')
                    if (after.isNotBlank()) return after.take(40)
                    val next = text.getOrNull(i + 1)?.trim().orEmpty()
                    if (next.isNotBlank()) return next.take(40)
                }
            }
            return ""
        }
        var sid = ""
        val m = Regex("(?:学号|帐号|账号)[：:]?[ 0-9]{8,14}").find(html)
        if (m != null) {
            sid = Regex("[0-9]{8,14}").find(m.value)?.value.orEmpty()
        }
        val sidText = if (sid.isNotEmpty()) sid else lineAfter("学号", "帐号", "账号")
        return JwProfile(
            name = lineAfter("姓名"),
            sid = sidText,
            college = lineAfter("学院", "系别", "院系"),
            major = lineAfter("专业"),
            clazz = lineAfter("班级"),
            raw = text.joinToString("\n").take(2000)
        )
    }

    // ---------------- 文本工具 ----------------

    /** 把单元格 HTML 拆成若干行文本（换行标签、段落结束都当换行），去掉空行与占位符。 */
    private fun cellLines(html: String): List<String> {
        val text = html
            .replace(Regex("<br[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</t[dh][^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</tr[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
        return text.split("\n").map { it.trim() }.filter { it.isNotEmpty() && it != "-" }
    }

    private fun plain(html: String): String =
        html.replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").trim()

    private fun weekdayOf(ch: String): Int = when (ch) {
        "一", "1" -> 1
        "二", "2" -> 2
        "三", "3" -> 3
        "四", "4" -> 4
        "五", "5" -> 5
        "六", "6" -> 6
        else -> 7
    }

    /** 周次归一：保留区间与单双周信息，例如「1-16」「1-8,10-16 单」。 */
    private fun normalizeWeeks(raw: String?): String {
        if (raw.isNullOrBlank()) return "1-16"
        val nums = ArrayList<Int>()
        for (m in Regex("\\d+").findAll(raw)) {
            val v = m.value.toIntOrNull() ?: 0
            if (v in 1..30) nums.add(v)
        }
        if (nums.isEmpty()) return "1-16"
        val first = nums[0]
        val second = if (nums.size > 1) nums[1] else first
        val lo = if (first < second) first else second
        val hi = if (first < second) second else first
        val base = if (lo == hi) "" + lo else "" + lo + "-" + hi
        return when {
            raw.contains("单") -> base + " 单"
            raw.contains("双") -> base + " 双"
            else -> base
        }
    }
}