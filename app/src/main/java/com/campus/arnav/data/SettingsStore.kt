package com.campus.arnav.data

import android.content.Context
import com.campus.arnav.BuildConfig
import com.campus.arnav.core.NetState

/**
 * 一次请求要用的通道参数。
 *
 * 0.11.0 起只有一条内置通道：阿里云百炼（OpenAI 兼容）。地址 / Key / 模型链都在构建期
 * 从 local.properties 注入 [BuildConfig]，界面上不暴露、也不需要用户填写。
 *
 * 旧版本的「自建代理」字段已删除：即使升级用户手机里还留着代理地址，也没有任何代码
 * 会读它——[SettingsStore.clearLegacy] 会把残留一次性清掉，请求永远不会再被改道。
 */
data class Provider(
    val base: String,
    val key: String,
    val model: String,
    /** 备选模型：主模型 404/429/5xx 时按顺序自动切换（内置通道用）。 */
    val models: List<String> = emptyList()
) {
    /** 依次尝试的模型链：主模型在前，去重去空。 */
    fun modelChain(): List<String> =
        (listOf(model) + models).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** 这条通道能不能用：地址 / Key / 模型都齐全。 */
    fun ready(): Boolean = model.isNotBlank() && base.isNotBlank() && key.isNotBlank()
}

/**
 * 通道与提醒 / 学期设置。云端通道在构建期注入，用户侧没有任何 Key / 地址 / 代理可填：
 * 有网自动走内置云端模型，没网自动降级到本机规则 + 离线知识库，功能照常可用。
 *
 * 旧版（≤0.10.x）把 DeepSeek Key、看图 Key、自建代理写进同一份 SharedPreferences，
 * 其中 `ai_proxy_base` 非空会让所有请求改道到早已不存在的代理——表现为「云端一直失败、
 * 提取不了文字」。[clearLegacy] 用 `legacy_cleared_v1` 标记只跑一次，并从 [builtin] /
 * [vision] / [deepseek] 里惰性触发，保证任何请求发起前旧配置一定已经清掉。
 */
object SettingsStore {

    private const val PREF = "zhijia_settings"

    /** 旧版（≤0.10.x）才有的字段：现在只剩清理逻辑会碰它们，不再有写入方。 */
    private val LEGACY_KEYS = arrayOf(
        "ai_proxy_base", "ai_proxy_token",
        "ds_key", "ds_base", "ds_model",
        "vl_key", "vl_base", "vl_model",
        "chat_vision"
    )

    private const val LEGACY_FLAG = "legacy_cleared_v1"

    /**
     * 一次性清掉旧版残留的 Key / 接口地址 / 代理配置（不碰学期、提醒、教务登录等其他设置）。
     *
     * 只在首次调用时真正删除；`apply()` 会同步更新内存中的 map，所以紧跟其后的读取
     * 已经读不到旧值，不存在「清完又被读出来」的窗口。
     */
    fun clearLegacy(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (sp.getBoolean(LEGACY_FLAG, false)) return
        val e = sp.edit()
        for (k in LEGACY_KEYS) e.remove(k)
        e.putBoolean(LEGACY_FLAG, true).apply()
    }

    /**
     * 旧通道探针：只给离线自检的「旧通道已废弃」检查用（`debug/SelfCheck.kt`），
     * 不参与真实请求。新代码请用 [builtin] / [chatProvider] / [vision]。
     */
    fun deepseek(ctx: Context): Provider {
        clearLegacy(ctx)
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return Provider(
            sp.getString("ds_base", "https://api.deepseek.com") ?: "https://api.deepseek.com",
            sp.getString("ds_key", "") ?: "",
            sp.getString("ds_model", "deepseek-chat") ?: "deepseek-chat"
        )
    }

    /**
     * 看图用的通道：还是内置百炼，但只挂「能看图」的模型。
     * 文本模型收到图片会直接 400，所以不能走 [modelChain] 那条混排链。
     */
    fun vision(ctx: Context): Provider {
        clearLegacy(ctx)
        return Provider(
            BuildConfig.DASHSCOPE_BASE,
            BuildConfig.DASHSCOPE_KEY,
            BuildConfig.DASHSCOPE_VISION_MODEL,
            models = BuildConfig.DASHSCOPE_VISION_MODELS.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        )
    }

    /**
     * 内置云端通道：阿里云百炼（DashScope，OpenAI 兼容）。
     * Key 在构建时从 local.properties 读入 [BuildConfig]，界面上不暴露地址与 Key。
     */
    fun builtin(ctx: Context): Provider {
        clearLegacy(ctx)
        return Provider(
            BuildConfig.DASHSCOPE_BASE,
            BuildConfig.DASHSCOPE_KEY,
            BuildConfig.DASHSCOPE_MODEL,
            models = BuildConfig.DASHSCOPE_MODELS.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        )
    }

    /** 设置页显示的通道名；没内置 Key 时如实写离线。 */
    fun aiApiName(ctx: Context): String = if (BuildConfig.DASHSCOPE_KEY.isBlank()) {
        "离线知识库（云端通道未内置）"
    } else {
        "阿里云百炼 · " + BuildConfig.DASHSCOPE_MODELS.split(",").size + " 个模型自动切换"
    }

    /** 是否内置了云端通道（只看有没有 Key，不看当前网络）。 */
    fun aiApiReady(ctx: Context): Boolean = builtin(ctx).ready()

    /** 聊天用的模型：内置云端通道。 */
    fun chatProvider(ctx: Context): Provider = builtin(ctx)

    /** 课前 / 活动前提醒是否开启（默认开）。 */
    fun remindEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("remind_on", true)

    fun setRemindEnabled(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("remind_on", v).apply()
    }

    /** 提前多少分钟提醒（默认 10 分钟，1—120 之间）。 */
    fun remindBefore(ctx: Context): Int =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("remind_before", 10).coerceIn(1, 120)

    fun setRemindBefore(ctx: Context, minutes: Int) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt("remind_before", minutes.coerceIn(1, 120)).apply()
    }

    /** 抽取活动能否联网（有网且内置通道齐全）；否则走 [com.campus.arnav.agent.OfflineExtractor]。 */
    fun extractOnline(ctx: Context): Boolean = NetState.online(ctx) && builtin(ctx).ready()

    /** 对话能否联网（有网且内置通道齐全）；否则走离线知识库。 */
    fun chatOnline(ctx: Context): Boolean = NetState.online(ctx) && builtin(ctx).ready()

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
