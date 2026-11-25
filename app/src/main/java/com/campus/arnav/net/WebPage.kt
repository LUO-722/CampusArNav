package com.campus.arnav.net

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 抓网页正文：不引第三方库，去掉 script/style/标签，压缩空白。
 */
object WebPage {

    fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 25_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
            )
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain")
            val charset = conn.contentType?.substringAfter("charset=", "")?.trim()?.trim('"')
                ?.ifBlank { null } ?: "utf-8"
            val code = conn.responseCode
            if (code !in 200..299) throw LlmClient.LlmException("网页返回 $code")
            val raw = BufferedReader(InputStreamReader(conn.inputStream, charset)).use { it.readText() }
            return htmlToText(raw)
        } finally {
            conn.disconnect()
        }
    }

    fun htmlToText(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<script.*?</script>"), " ")
        s = s.replace(Regex("(?is)<style.*?</style>"), " ")
        s = s.replace(Regex("(?is)<noscript.*?</noscript>"), " ")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</(p|div|li|tr|h[1-6]|section|article)>"), "\n")
        s = s.replace(Regex("(?s)<[^>]+>"), " ")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&ldquo;", "“").replace("&rdquo;", "”")
            .replace("&lsquo;", "‘").replace("&rsquo;", "’")
            .replace("&mdash;", "—").replace("&ndash;", "–")
            .replace("&hellip;", "…").replace("&emsp;", " ").replace("&ensp;", " ")
            .replace("&times;", "×").replace("&middot;", "·")
        s = s.replace(Regex("&#(\\d{2,5});")) {
            val code = it.groupValues[1].toIntOrNull()
            if (code != null && code in 32..0x10FFFF) String(Character.toChars(code)) else " "
        }
        s = s.replace(Regex("&[a-zA-Z]{2,8};"), " ")
        s = s.replace(Regex("[ \\t\\u00A0]+"), " ")
        s = s.replace(Regex("\\n\\s*\\n+"), "\n\n")
        return s.trim().take(30_000)
    }
}
