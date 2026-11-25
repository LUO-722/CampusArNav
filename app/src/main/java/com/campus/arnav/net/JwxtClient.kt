package com.campus.arnav.net

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar

/** 教务系统内网/外网通道。 */
enum class JwChannel(val label: String, val base: String) {
    INTRANET("内网", "http://10.110.225.76/jsxsd"),
    EXTRANET("外网", "http://222.187.129.200:51234/jsxsd");

    companion object {
        fun of(name: String?): JwChannel =
            if (name == EXTRANET.name) EXTRANET else INTRANET
    }
}

/** 登录结果。 */
sealed class JwResult<out T> {
    data class Ok<T>(val value: T) : JwResult<T>()
    data class Err(val message: String) : JwResult<Nothing>()
}

/**
 * 金智 jsxsd 教务系统客户端。
 *
 * 登录流程（与网页 LoginToXk 完全一致）：
 *  1. GET  /jsxsd/            拿会话 Cookie
 *  2. GET  /jsxsd/verifycode.servlet  取验证码
 *  3. POST /jsxsd/Logon.do?method=logon&flag=sess  → "scode#sxh"
 *  4. 用 scode/sxh 对 "学号%%%密码" 做混淆编码得到 encoded
 *  5. POST /jsxsd/xk/LoginToXk  (userAccount / userPassword / RANDOMCODE / encoded)
 */
object JwxtClient {

    /**
     * 外网可用时段：9:00–18:00 之外才允许（学校规则）。
     * 固定按北京时间判断，不依赖设备时区——设备时区设错时也不能放行。
     */
    fun extranetAllowedAt(hour: Int): Boolean = hour < 9 || hour >= 18

    /** 北京时间（GMT+8）当前小时。 */
    private fun beijingHour(): Int {
        val tz = java.util.TimeZone.getTimeZone("Asia/Shanghai")
        return Calendar.getInstance(tz).get(Calendar.HOUR_OF_DAY)
    }

    fun extranetAllowedNow(): Boolean = extranetAllowedAt(beijingHour())

    /** 当前北京时间描述，便于用户核对。 */
    fun beijingNow(): String {
        val tz = java.util.TimeZone.getTimeZone("Asia/Shanghai")
        val c = Calendar.getInstance(tz)
        return "%02d:%02d（北京时间）".format(
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)
        )
    }

    /** 外网被禁用时的提示。 */
    fun extranetBlockedHint(): String {
        val h = beijingHour()
        return if (h in 9..17) "外网仅 18:00–次日 09:00 可登录（现在 ${beijingNow()}）" else ""
    }

    private const val UA =
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    /** 一次登录会话：保存 Cookie，后续抓取复用。 */
    class Session(val channel: JwChannel) {
        internal val cookies = LinkedHashMap<String, String>()

        internal fun cookieHeader(): String =
            cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

        internal fun captureCookies(conn: HttpURLConnection) {
            conn.headerFields?.forEach { (k, v) ->
                if (k != null && k.equals("Set-Cookie", true)) {
                    v?.forEach { raw ->
                        val first = raw.substringBefore(';')
                        val eq = first.indexOf('=')
                        if (eq > 0) cookies[first.substring(0, eq).trim()] = first.substring(eq + 1).trim()
                    }
                }
            }
        }

        internal fun open(path: String, method: String = "GET"): HttpURLConnection {
            val url = URL(channel.base + path)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            // 服务器响应常为 connection: close，显式声明避免流被提前判定中断
            conn.setRequestProperty("Connection", "close")
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json,*/*;q=0.8")
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            if (cookies.isNotEmpty()) conn.setRequestProperty("Cookie", cookieHeader())
            return conn
        }

        /**
         * 读取响应。教务系统偶发在 connection: close 下不发送完整 body，
         * 这里容错：读到多少用多少，不因流异常丢掉已获取的内容。
         */
        internal fun readText(conn: HttpURLConnection): String {
            var code = 0
            runCatching { code = conn.responseCode }
            captureCookies(conn)
            val stream = runCatching {
                if (code in 200..299) conn.inputStream else conn.errorStream
            }.getOrNull()
            val bytes = runCatching {
                stream?.use { s ->
                    val buf = ByteArrayOutputStream()
                    val tmp = ByteArray(8192)
                    while (true) {
                        val n = s.read(tmp)
                        if (n <= 0) break
                        buf.write(tmp, 0, n)
                    }
                    buf.toByteArray()
                }
            }.getOrElse { e ->
                // 流被中断时，尽力返回已读部分（拿不到就返回空）
                ByteArray(0)
            } ?: ByteArray(0)
            val text = bytes.toString(Charsets.UTF_8)
            return if (code in 200..399 || text.isNotEmpty()) text else ""
        }

        internal fun readBytes(conn: HttpURLConnection): ByteArray {
            captureCookies(conn)
            val code = runCatching { conn.responseCode }.getOrDefault(0)
            val stream = runCatching {
                if (code in 200..299) conn.inputStream else null
            }.getOrNull() ?: return ByteArray(0)
            return runCatching {
                stream.use { s ->
                    val buf = ByteArrayOutputStream()
                    val tmp = ByteArray(8192)
                    while (true) {
                        val n = s.read(tmp)
                        if (n <= 0) break
                        buf.write(tmp, 0, n)
                    }
                    buf.toByteArray()
                }
            }.getOrDefault(ByteArray(0))
        }
    }

    /** 新建会话并访问登录页（用于取验证码前建立 Cookie）。 */
    fun newSession(channel: JwChannel, warmUp: Boolean = true): Session {
        val s = Session(channel)
        if (warmUp) {
            runCatching {
                val conn = s.open("/", "GET")
                s.readText(conn)
                conn.disconnect()
            }
        }
        return s
    }

    /** 取验证码图片字节。失败返回 JwResult.Err。 */
    fun fetchCaptcha(session: Session): JwResult<ByteArray> {
        return try {
            val conn = session.open("/verifycode.servlet?t=${System.currentTimeMillis()}", "GET")
            val bytes = session.readBytes(conn)
            conn.disconnect()
            if (bytes.isEmpty()) JwResult.Err("验证码加载失败（服务器无响应），请检查网络或通道") else JwResult.Ok(bytes)
        } catch (e: Exception) {
            JwResult.Err("验证码加载失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 执行登录。成功返回 Ok(Unit) 并保留 Cookie 供抓取。
     *
     * 与网页登录按钮 submitForm1() 完全一致：
     *   encoded = base64(学号) + "%%%" + base64(密码)
     *   然后 POST /jsxsd/xk/LoginToXk。
     */
    fun login(
        session: Session,
        account: String,
        password: String,
        captcha: String
    ): JwResult<Unit> {
        if (session.channel == JwChannel.EXTRANET && !extranetAllowedNow()) {
            return JwResult.Err(extranetBlockedHint().ifBlank { "当前时段不允许使用外网登录" })
        }
        return try {
            val encoded = b64(account) + "%%%" + b64(password)
            val body = buildString {
                append("loginMethod=").append(enc("LoginToXk"))
                append("&userAccount=").append(enc(account))
                append("&userPassword=").append(enc(""))
                append("&RANDOMCODE=").append(enc(captcha))
                append("&encoded=").append(enc(encoded))
            }
            val conn = session.open("/xk/LoginToXk", "POST")
            conn.doOutput = true
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("Referer", session.channel.base + "/")
            conn.setRequestProperty("Origin", session.channel.base.substringBefore("/jsxsd"))
            val payload = body.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }

            val code = runCatching { conn.responseCode }.getOrDefault(0)
            val location = conn.getHeaderField("Location") ?: ""
            val text = session.readText(conn)
            conn.disconnect()

            // 成功：302 跳转主框架，或页面出现主界面特征
            val redirected = code in 300..399
            val looksLoggedIn = text.contains("xsMain") || text.contains("退出系统") ||
                text.contains("jxgl") || text.contains("课表")
            val hasLoginForm = text.contains("id=\"loginForm\"") || text.contains("btn-login")

            if (redirected || (looksLoggedIn && !hasLoginForm)) {
                return JwResult.Ok(Unit)
            }

            // 失败：从返回页里提取学校给的提示
            val hint = extractMessage(text)
            return JwResult.Err(
                when {
                    hint != null -> hint
                    text.contains("验证码") -> "验证码错误，请重新输入"
                    text.contains("密码") || text.contains("用户名") -> "学号或密码不正确"
                    code == 0 -> "连接被中断，请重试（可能是网络不稳定）"
                    else -> "登录失败（HTTP $code）"
                }
            )
        } catch (e: Exception) {
            JwResult.Err("连接失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 从登录失败页里提取中文提示（如“验证码错误!!”）。 */
    private fun extractMessage(html: String): String? {
        val plain = html.replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), " ")
        val patterns = listOf(
            Regex("([\\u4e00-\\u9fa5]{0,10}(?:错误|不正确|失败|已过期|不存在)[!！]{0,2})"),
            Regex("alert\\(['\"]([^'\"]+)['\"]\\)")
        )
        for (p in patterns) {
            val m = p.find(plain)
            if (m != null) {
                val s = m.groupValues[1].trim()
                if (s.length in 2..30 && !s.contains("计算异常")) return s
            }
        }
        return null
    }

    /** 标准 Base64（对应网页 encodeInp）。 */
    private fun b64(s: String): String =
        android.util.Base64.encodeToString(s.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    // ================= 抓取 =================

    /** 抓取结果集合。 */
    data class JwData(
        val profile: Map<String, String> = emptyMap(),
        val courses: List<JwCourse> = emptyList(),
        val grades: List<Map<String, String>> = emptyList(),
        val exams: List<Map<String, String>> = emptyList(),
        val raw: Map<String, String> = emptyMap()
    )

    /** 从教务解析出的课程。 */
    data class JwCourse(
        val name: String,
        val teacher: String,
        val location: String,
        val weekday: Int,
        val startPeriod: Int,
        val endPeriod: Int,
        val weeks: String
    )

    /**
     * 登录后抓取全部能拿到的内容。每块单独 try，某块失败不影响其它块。
     */
    fun fetchAll(session: Session): JwData {
        val raw = LinkedHashMap<String, String>()

        // 课表
        var courses = emptyList<JwCourse>()
        for (path in listOf(
            "/xskb/xskb_list.do?Ves632DSdyV=NEW_XSD_PYGL",
            "/xskb/xskb_list.do",
            "/framework/xsMain.jsp"
        )) {
            val html = runCatching { get(session, path) }.getOrDefault("")
            if (html.isBlank()) continue
            raw["course:$path"] = html
            val parsed = runCatching { parseCourses(html) }.getOrDefault(emptyList())
            if (parsed.isNotEmpty()) { courses = parsed; break }
        }

        // 成绩
        var grades = emptyList<Map<String, String>>()
        for (path in listOf("/kscj/cjcx_list", "/kscj/cjcx_list?kksj=", "/xskp/xskppsList")) {
            val html = runCatching { get(session, path) }.getOrDefault("")
            if (html.isBlank()) continue
            raw["grade:$path"] = html
            val parsed = runCatching { parseTable(html) }.getOrDefault(emptyList())
            if (parsed.isNotEmpty()) { grades = parsed; break }
        }

        // 考试安排
        var exams = emptyList<Map<String, String>>()
        for (path in listOf("/xsks/xsksap_list", "/xsks/xsksap_list?xnxqid=")) {
            val html = runCatching { get(session, path) }.getOrDefault("")
            if (html.isBlank()) continue
            raw["exam:$path"] = html
            val parsed = runCatching { parseTable(html) }.getOrDefault(emptyList())
            if (parsed.isNotEmpty()) { exams = parsed; break }
        }

        // 个人信息
        val profile = LinkedHashMap<String, String>()
        for (path in listOf("/xsxxgl/xsxxwh", "/xsgz/xsxxx", "/framework/xsMain.jsp")) {
            val html = runCatching { get(session, path) }.getOrDefault("")
            if (html.isBlank()) continue
            raw["profile:$path"] = html
            profile.putAll(runCatching { parseProfile(html) }.getOrDefault(emptyMap()))
            if (profile.isNotEmpty()) break
        }

        return JwData(profile, courses, grades, exams, raw)
    }

    private fun get(session: Session, path: String): String {
        val conn = session.open(path, "GET")
        conn.setRequestProperty("Referer", session.channel.base + "/")
        val text = session.readText(conn)
        conn.disconnect()
        return text
    }

    /** 个人信息：抓“学号/姓名/学院/专业/班级”等标签后的值。 */
    private fun parseProfile(html: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val keys = listOf("学号", "姓名", "学院", "系部", "专业", "班级", "年级", "性别", "学制", "入学时间")
        val plain = html.replace(Regex("\\s+"), " ")
        for (k in keys) {
            val re = Regex("$k[^\\u4e00-\\u9fa5]{0,20}([A-Za-z0-9\\u4e00-\\u9fa5\\-]{2,20})")
            val m = re.find(plain)
            if (m != null) out[k] = m.groupValues[1].trim()
        }
        return out
    }

    /** 解析课表表格：金智课表是 <table> 里 td 带 title/文字。 */
    fun parseCourses(html: String): List<JwCourse> {
        val rows = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(html).map { it.groupValues[1] }.toList()
        val out = ArrayList<JwCourse>()
        for (row in rows) {
            val cells = Regex("<td[^>]*>(.*?)</td>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .findAll(row).map { it.groupValues[1] }.toList()
            if (cells.size < 3) continue
            for ((idx, cell) in cells.withIndex()) {
                val text = cell.replace(Regex("<[^>]+>"), " ")
                    .replace("&nbsp;", " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                if (text.length < 3) continue
                if (text.startsWith("星期一") || text.startsWith("周二")) continue
                // 课表格子通常形如：课程名 教师 周次 地点
                val m = Regex("^(.{2,30}?)\\s+(\\S{1,12})\\s+\\{?([0-9,\\-周单双]+)\\}?\\s+(\\S+)$").find(text)
                if (m != null) {
                    val period = idx.coerceIn(1, 12)
                    out.add(
                        JwCourse(
                            name = m.groupValues[1].trim(),
                            teacher = m.groupValues[2].trim(),
                            location = m.groupValues[4].trim(),
                            weekday = 1,           // 由外层表格位置推断，缺失时置 1
                            startPeriod = period,
                            endPeriod = period,
                            weeks = m.groupValues[3].trim()
                        )
                    )
                }
            }
        }
        return out
    }

    /** 通用表格解析：表头 + 行，用于成绩/考试。 */
    fun parseTable(html: String): List<Map<String, String>> {
        val tables = Regex("<table[^>]*>(.*?)</table>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(html).map { it.groupValues[1] }.toList()
        for (t in tables) {
            val headRow = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .find(t)?.groupValues?.get(1) ?: continue
            val heads = Regex("<t[hd][^>]*>(.*?)</t[hd]>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .findAll(headRow)
                .map { it.groupValues[1].replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").trim() }
                .toList()
            if (heads.size < 2) continue
            val bodyRows = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .findAll(t).drop(1).toList()
            val out = ArrayList<Map<String, String>>()
            for (r in bodyRows) {
                val cells = Regex("<td[^>]*>(.*?)</td>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                    .findAll(r.groupValues[1])
                    .map {
                        it.groupValues[1].replace(Regex("<[^>]+>"), "")
                            .replace("&nbsp;", " ").replace(Regex("\\s+"), " ").trim()
                    }.toList()
                if (cells.isEmpty()) continue
                val row = LinkedHashMap<String, String>()
                heads.forEachIndexed { i, h -> if (i < cells.size && cells[i].isNotBlank()) row[h] = cells[i] }
                if (row.isNotEmpty()) out.add(row)
            }
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    /** 把抓取结果序列化成 JSON 存盘。 */
    fun toJson(data: JwData): String {
        val root = JSONObject()
        root.put("profile", JSONObject(data.profile as Map<*, *>))
        val cs = JSONArray()
        data.courses.forEach {
            cs.put(JSONObject().apply {
                put("name", it.name); put("teacher", it.teacher); put("location", it.location)
                put("weekday", it.weekday); put("start", it.startPeriod)
                put("end", it.endPeriod); put("weeks", it.weeks)
            })
        }
        root.put("courses", cs)
        root.put("grades", JSONArray(data.grades as List<*>))
        root.put("exams", JSONArray(data.exams as List<*>))
        return root.toString()
    }
}
