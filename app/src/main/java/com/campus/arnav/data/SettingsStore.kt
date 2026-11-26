package com.campus.arnav.data

import android.content.Context

/**
 * 一次请求要用的通道参数。
 *
 * [proxyBase] 非空表示走自建服务端代理：请求地址改成代理，App 内不再需要 DeepSeek Key；
 * 为空则维持原来的直连逻辑（[base] + [key]）。
 */
data class Provider(
    val base: String,
    val key: String,
    val model: String,
    /** 自建服务端代理地址（OpenAI 兼容），空 = 直连。 */
    val proxyBase: String = "",
    /** 代理访问令牌，可空（代理只认 App 内置 token 时留空即可）。 */
    val proxyToken: String = ""
) {
    fun viaProxy(): Boolean = proxyBase.isNotBlank()

    /** 实际请求的 base：有代理走代理，否则直连。 */
    fun effectiveBase(): String = if (viaProxy()) proxyBase else base

    /** 实际携带的 Bearer：走代理时用代理令牌，直连时用 API Key。 */
    fun effectiveKey(): String = if (viaProxy()) proxyToken else key

    /** 这条通道能不能用：代理填了，或直连的地址/Key/模型都齐了。 */
    fun ready(): Boolean = model.isNotBlank() &&
        if (viaProxy()) true else base.isNotBlank() && key.isNotBlank()
}

/**
 * API Key、模型与自建代理设置。默认给了常用地址；Key 不是必填项——
 * 没 Key 也没代理时，智嘉会降级到本机规则识别 + 离线知识库，功能照常可用。
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
        ).withProxy(ctx)
    }

    fun vision(ctx: Context): Provider {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return Provider(
            sp.getString("vl_base", "https://dashscope.aliyuncs.com/compatible-mode/v1")
                ?: "https://dashscope.aliyuncs.com/compatible-mode/v1",
            sp.getString("vl_key", "") ?: "",
            sp.getString("vl_model", "qwen-vl-max-latest") ?: "qwen-vl-max-latest"
        ).withProxy(ctx)
    }

    /** 配了服务端代理就盖到 Provider 上：调用方（LlmClient / AgentEngine）不需要知道代理。 */
    private fun Provider.withProxy(ctx: Context): Provider {
        val base = aiProxyBase(ctx)
        if (base.isBlank()) return this
        return copy(proxyBase = base, proxyToken = aiProxyToken(ctx))
    }

    /** 聊天用的模型：默认 DeepSeek；用户可在设置里切到识图模型。 */
    fun chatProvider(ctx: Context): Provider =
        if (chatUseVision(ctx)) vision(ctx) else deepseek(ctx)

    fun chatUseVision(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("chat_vision", false)

    fun setChatUseVision(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("chat_vision", v).apply()
    }

    // ---- AI 服务端代理（可选） ----
    // 学校/团队自建一个 OpenAI 兼容的小代理，App 内只放代理地址 + token，
    // 学生就不用自己去申请 DeepSeek Key；两者都没配时降级为本机规则 + 离线知识库。

    /** 自建代理地址（OpenAI 兼容）；空串表示不启用代理。 */
    fun aiProxyBase(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("ai_proxy_base", "") ?: ""

    /** 代理访问令牌；可为空（代理只校验 App 内置 token 时）。 */
    fun aiProxyToken(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("ai_proxy_token", "") ?: ""

    fun saveAiProxy(ctx: Context, base: String, token: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("ai_proxy_base", normalizeBase(base))
            .putString("ai_proxy_token", token.trim())
            .apply()
    }

    /** 是否配置了服务端代理。 */
    fun aiProxyConfigured(ctx: Context): Boolean = aiProxyBase(ctx).isNotBlank()

    /** 抽取活动能否联网（有代理，或直连 Key 齐全）；否则走 [com.campus.arnav.agent.OfflineExtractor]。 */
    fun extractOnline(ctx: Context): Boolean = deepseek(ctx).ready()

    /** 对话能否联网（有代理，或聊天 Provider 可用）；否则走离线知识库。 */
    fun chatOnline(ctx: Context): Boolean = chatProvider(ctx).ready()

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
