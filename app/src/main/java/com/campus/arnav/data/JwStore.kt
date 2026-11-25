package com.campus.arnav.data

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File

/**
 * 教务系统凭据与抓取数据。
 *
 * - 账号密码存 SharedPreferences（Base64 混淆，避免明文出现在 XML 里）
 * - 抓取到的全部内容存 filesDir/jw_cache.json
 * - 记录上次登录时间与所用通道，供启动时免登录判断
 */
object JwStore {

    private const val PREF = "jw_credentials"
    private const val KEY_ACCOUNT = "account"
    private const val KEY_PASSWORD = "password"
    private const val KEY_CHANNEL = "channel"
    private const val KEY_LAST_LOGIN = "last_login"
    private const val KEY_AUTO_LOGIN = "auto_login"
    const val CACHE_FILE = "jw_cache.json"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun b64(s: String): String =
        Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun unb64(s: String): String =
        runCatching { String(Base64.decode(s, Base64.NO_WRAP), Charsets.UTF_8) }.getOrDefault("")

    /** 保存账号密码（登录成功后调用）。 */
    fun saveCredentials(ctx: Context, account: String, password: String, channel: String) {
        sp(ctx).edit()
            .putString(KEY_ACCOUNT, b64(account))
            .putString(KEY_PASSWORD, b64(password))
            .putString(KEY_CHANNEL, channel)
            .putLong(KEY_LAST_LOGIN, System.currentTimeMillis())
            .apply()
    }

    fun account(ctx: Context): String = unb64(sp(ctx).getString(KEY_ACCOUNT, "") ?: "")
    fun password(ctx: Context): String = unb64(sp(ctx).getString(KEY_PASSWORD, "") ?: "")
    fun channel(ctx: Context): String = sp(ctx).getString(KEY_CHANNEL, "INTRANET") ?: "INTRANET"
    fun lastLogin(ctx: Context): Long = sp(ctx).getLong(KEY_LAST_LOGIN, 0L)

    fun hasCredentials(ctx: Context): Boolean =
        account(ctx).isNotBlank() && password(ctx).isNotBlank()

    fun setAutoLogin(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(KEY_AUTO_LOGIN, on).apply()
    }

    fun autoLogin(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_AUTO_LOGIN, true)

    /** 退出登录：清凭据与抓取缓存（保留用户其他数据）。 */
    fun clear(ctx: Context) {
        sp(ctx).edit().clear().apply()
        File(ctx.filesDir, CACHE_FILE).delete()
    }

    // ---------- 抓取数据 ----------

    fun saveCache(ctx: Context, json: String) {
        runCatching { File(ctx.filesDir, CACHE_FILE).writeText(json, Charsets.UTF_8) }
    }

    fun readCache(ctx: Context): JSONObject? = runCatching {
        val f = File(ctx.filesDir, CACHE_FILE)
        if (!f.exists()) null else JSONObject(f.readText(Charsets.UTF_8))
    }.getOrNull()

    /** 缓存是否已存在（表示"已从学校网址提取过内容"）。 */
    fun hasCache(ctx: Context): Boolean = File(ctx.filesDir, CACHE_FILE).let { it.exists() && it.length() > 2 }

    /** 缓存摘要文案，用于登录页显示。 */
    fun cacheSummary(ctx: Context): String {
        val j = readCache(ctx) ?: return "尚未同步"
        val profile = j.optJSONObject("profile")
        val courses = j.optJSONArray("courses")?.length() ?: 0
        val grades = j.optJSONArray("grades")?.length() ?: 0
        val exams = j.optJSONArray("exams")?.length() ?: 0
        val name = profile?.optString("姓名", "") ?: ""
        val head = if (name.isNotBlank()) "$name · " else ""
        return "${head}课程 $courses · 成绩 $grades · 考试 $exams"
    }
}
