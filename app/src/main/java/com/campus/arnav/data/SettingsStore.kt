package com.campus.arnav.data

import android.content.Context

data class Provider(
    val base: String,
    val key: String,
    val model: String
) {
    fun ready(): Boolean = key.isNotBlank() && base.isNotBlank() && model.isNotBlank()
}

/**
 * API Key 与模型设置。默认给了常用地址，Key 需要用户自己填。
 */
object SettingsStore {

    private const val PREF = "zhijia_settings"

    fun saveDeepSeek(ctx: Context, base: String, key: String, model: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("ds_base", normalizeBase(base))
            .putString("ds_key", key.trim())
            .putString("ds_model", model.trim().ifBlank { "deepseek-chat" })
            .apply()
    }

    fun saveVision(ctx: Context, base: String, key: String, model: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("vl_base", normalizeBase(base))
            .putString("vl_key", key.trim())
            .putString("vl_model", model.trim().ifBlank { "qwen-vl-max-latest" })
            .apply()
    }

    /** 用户常只填域名；补 https:// 并去掉末尾斜杠，避免 URL 解析崩溃。 */
    private fun normalizeBase(base: String): String {
        var t = base.trim().trimEnd('/')
        if (t.isBlank()) return ""
        if (!t.startsWith("http://", true) && !t.startsWith("https://", true)) t = "https://$t"
        return t
    }

    fun deepseek(ctx: Context): Provider {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return Provider(
            sp.getString("ds_base", "https://api.deepseek.com") ?: "https://api.deepseek.com",
            sp.getString("ds_key", "") ?: "",
            sp.getString("ds_model", "deepseek-chat") ?: "deepseek-chat"
        )
    }

    fun vision(ctx: Context): Provider {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return Provider(
            sp.getString("vl_base", "https://dashscope.aliyuncs.com/compatible-mode/v1")
                ?: "https://dashscope.aliyuncs.com/compatible-mode/v1",
            sp.getString("vl_key", "") ?: "",
            sp.getString("vl_model", "qwen-vl-max-latest") ?: "qwen-vl-max-latest"
        )
    }

    /** 聊天用的模型：默认 DeepSeek；用户可在设置里切到识图模型。 */
    fun chatProvider(ctx: Context): Provider =
        if (chatUseVision(ctx)) vision(ctx) else deepseek(ctx)

    fun chatUseVision(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("chat_vision", false)

    fun setChatUseVision(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("chat_vision", v).apply()
    }

    // ---- 学期 ----

    fun saveSemester(ctx: Context, startDate: String, totalWeeks: Int, name: String) {
        // 只写合法值，避免下次启动 applySemester 静默忽略却与界面不一致。
        val start = ScheduleLogic.parseDate(startDate.trim())
            ?: ScheduleLogic.parseDate(ScheduleLogic.DEFAULT_SEMESTER_START)!!
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("sem_start", ScheduleLogic.formatDate(start))
            .putInt("sem_weeks", totalWeeks.coerceIn(1, 30))
            .putString("sem_name", name.trim().ifBlank { ScheduleLogic.DEFAULT_SEMESTER_NAME })
            .apply()
    }

    fun semesterStart(ctx: Context): String {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return sp.getString("sem_start", ScheduleLogic.DEFAULT_SEMESTER_START)
            ?: ScheduleLogic.DEFAULT_SEMESTER_START
    }

    fun semesterWeeks(ctx: Context): Int {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return sp.getInt("sem_weeks", ScheduleLogic.DEFAULT_TOTAL_WEEKS)
            .coerceIn(1, 30)
    }

    fun semesterName(ctx: Context): String {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return sp.getString("sem_name", ScheduleLogic.DEFAULT_SEMESTER_NAME)
            ?: ScheduleLogic.DEFAULT_SEMESTER_NAME
    }

    /** 启动/保存后把 SharedPreferences 里的学期配置灌进 ScheduleLogic。 */
    fun applySemester(ctx: Context) {
        ScheduleLogic.configure(semesterStart(ctx), semesterWeeks(ctx), semesterName(ctx))
    }
}
