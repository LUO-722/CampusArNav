package com.campus.arnav.agent

import android.util.Log

import android.content.Context
import com.campus.arnav.data.ChatMsg
import com.campus.arnav.data.CourseStore
import com.campus.arnav.data.SettingsStore
import com.campus.arnav.data.ScheduleLogic
import com.campus.arnav.core.NetState
import com.campus.arnav.kb.KbIndex
import com.campus.arnav.net.LlmClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 聊天 Agent：知识库检索 + 模型决策 + 动作执行。
 * 模型按约定返回 {"reply": "...", "actions": [...]}；动作由 [ActionExecutor] 执行。
 */
private const val TAG_AI = "ZhijiaAI"

object AgentEngine {

    data class Result(
        val reply: String,
        val actions: List<String>,
        val sources: List<String>,
        /** 需要用户确认后才执行的写操作（加课/录活动/设闹钟/删课/删活动）。 */
        val pending: List<JSONObject> = emptyList()
    )

    private val WRITE_ACTIONS = setOf(
        "add_course", "add_activity", "set_alarm", "delete_course", "delete_activity"
    )

    /** 把待确认动作翻成中文，展示在确认框里。 */
    fun describe(o: JSONObject): String {
        val t = o.optString("type")
        return when (t) {
            "add_course" -> "添加课程「${o.optString("name")}」" +
                "（周${o.optString("weekday")} 第${o.optString("start_period")}-${o.optString("end_period")}节，" +
                "周次 ${o.optString("weeks")}）"
            "add_activity" -> "录入活动「${o.optString("title")}」" +
                "（${o.optString("date")} ${o.optString("start_time")}-${o.optString("end_time")}" +
                "，${o.optString("location")}）"
            "set_alarm" -> "设闹钟 ${o.optString("hour")}:${o.optString("minute").padStart(2, '0')}" +
                "（${o.optString("label")}）"
            "delete_course" -> "删除课程「${o.optString("name")}」"
            "delete_activity" -> "删除活动「${o.optString("title")}」"
            else -> t
        }
    }

    private const val ACTION_SPEC = """可用动作（actions 数组，可空）：
- {"type":"add_course","name":"课程名","teacher":"","location":"","weekday":1,"start_period":1,"end_period":2,"weeks":"1-16"}
- {"type":"add_activity","title":"活动名","date":"yyyy-MM-dd","start_time":"HH:mm","end_time":"HH:mm","location":"","organizer":"","note":""}
- {"type":"set_alarm","hour":7,"minute":0,"label":"起床"}
- {"type":"search_kb","query":"关键词"}
- {"type":"list_schedule","date":"yyyy-MM-dd"}
- {"type":"navigate","place":"明志楼"}
- {"type":"delete_course","name":"课程名"}
- {"type":"delete_activity","title":"活动名","date":"yyyy-MM-dd"}"""


    /** 流式过程中从「半截 JSON」里尽量抠出 reply 文本，用于逐字预览。 */
    internal fun replySoFar(buf: String): String {
        val m = Regex("\"reply\"\\s*:\\s*\"").find(buf) ?: return ""
        val sb = StringBuilder()
        var i = m.range.last + 1
        while (i < buf.length) {
            val c = buf[i]
            if (c == '"') break
            if (c == '\\' && i + 1 < buf.length) {
                when (val n = buf[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> { }
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'u' -> {
                        if (i + 5 < buf.length) {
                            val code = buf.substring(i + 2, i + 6).toIntOrNull(16)
                            if (code != null) { sb.append(code.toChar()); i += 4 }
                        }
                    }
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    fun ask(
        ctx: Context,
        history: List<ChatMsg>,
        userText: String,
        onPartial: ((String) -> Unit)? = null
    ): Result {
        val provider = SettingsStore.chatProvider(ctx)
        if (!provider.ready()) {
            return Result(
                "现在是离线知识库模式（没有网络，或云端暂时不可用）：我用本机知识库回答，" +
                    "联网后会自动接入云端模型，还能帮你查最新的通知和活动。",
                emptyList(), emptyList()
            )
        }

        val kbHits = KbIndex.search(ctx, userText, 4)
        val web = shouldSearch(ctx, userText, kbHits)
        val system = buildSystem(ctx, kbHits, web)
        val msgs = ArrayList<ChatMsg>()
        msgs.add(ChatMsg("system", system))
        // 只带最近 8 条，避免上下文过长；聊天页传入的 history 可能已包含本轮 user 消息，避免重复追加
        val recent = history.takeLast(8).filter { it.role != "system" }
        recent.forEach { msgs.add(ChatMsg(it.role, it.content)) }
        val last = recent.lastOrNull()
        if (last == null || last.role != "user" || last.content != userText) {
            msgs.add(ChatMsg("user", userText))
        }

        Log.i(TAG_AI, "ASK q=" + userText + " kb=" + kbHits.size + " web=" + web)
        val raw = if (onPartial == null) {
            LlmClient.chat(provider, msgs, jsonMode = true, search = web)
        } else {
            // 流式：边收边把「reply 字段里已经写出来的部分」交给界面，像打字一样出现
            LlmClient.chatStream(provider, msgs, jsonMode = true, search = web) { buf ->
                onPartial(replySoFar(buf))
            }
        }
        Log.i(TAG_AI, "RAW len=" + raw.length + " :" + raw.take(400))
        // 偶发故障兜底：模型这次没吐出可读回答（纯数字/空/乱码）→ 非流式再请求一次
        val firstReply = parse(raw)?.optString("reply") ?: ""
        val retry = if (AnswerRules.looksLikeAnswer(firstReply)) raw else runCatching {
            LlmClient.chat(provider, msgs, jsonMode = true, search = web)
        }.getOrNull()
        if (retry != null && retry !== raw) {
            Log.i(TAG_AI, "RETRY len=" + retry.length + " :" + retry.take(300))
            return handle(ctx, retry, kbHits)
        }
        return handle(ctx, raw, kbHits)
    }

    /** 聊天页发图片：必须用通义 VL（多模态），其余流程与 [ask] 一致。 */
    fun askImage(ctx: Context, history: List<ChatMsg>, userText: String, imageBase64: String): Result {
        val provider = SettingsStore.vision(ctx)
        if (!provider.ready()) {
            return Result(
                "发图前先用「本机识字」把图片里的文字读出来（不需要网络），再按文字问答或提取活动。",
                emptyList(), emptyList()
            )
        }
        val kbHits = KbIndex.search(ctx, userText, 4)
        val system = buildSystem(ctx, kbHits, false)
        val raw = LlmClient.visionChat(provider, system, history, userText, imageBase64)
        return handle(ctx, raw, kbHits)
    }

    /**
     * 要不要联网：知识库没命中/命中太弱，或者问的是“最近/今天/最新”这类时效性问题。
     * 联网由百炼服务端完成，手机端不额外访问任何网站。
     */
    private fun shouldSearch(ctx: Context, userText: String, hits: List<KbIndex.Hit>): Boolean {
        if (!NetState.online(ctx)) return false
        val q = userText.trim()
        if (q.length < 2) return false
        // 一看就知道是「校园知识库外」的问法：直接联网，不再等命中分数
        val external = listOf(
            "搜索", "查一下", "搜一下", "网上", "百度", "外面", "外面的事",
            "天气", "气温", "下雨", "台风", "新闻", "消息", "时事",
            "今天", "明天", "昨天", "最近", "最新", "现在", "本周", "这周", "下周",
            "新闻", "通知", "公告", "通告", "放假", "调课", "停课", "开学",
            "讲座", "比赛", "竞赛", "活动", "招聘", "实习", "分数线", "招生政策",
            "谁", "什么时候", "怎么去", "高铁", "地铁", "公交", "外卖", "快递",
            // 校园里问不到、必须上网的：出行、周边、生活服务
            "打车", "滴滴", "出租车", "单车", "自行车", "共享", "网约车",
            "药店", "医院", "诊所", "银行", "理发", "超市", "附近", "周边",
            "好吃", "好玩", "景点", "门票", "多少钱", "多远", "几个小时",
            "支付宝", "微信支付", "校外", "市区", "火车站", "机场"
        )
        if (external.any { q.contains(it) }) return true
        // 否则看知识库命中强度：弱的就联网补一次
        val best = hits.maxOfOrNull { it.score } ?: 0.0
        // 阈值 8.0：实测校园知识库能答的问题命中分普遍 9 以上，8 以下基本是「库里只有零星字面重合」，
        // 联网补一次更可靠（4.5 时实测 14 道该联网的题只触发 7 道）。
        return hits.isEmpty() || best < 8.0
    }

    private fun buildSystem(
        ctx: Context,
        kbHits: List<KbIndex.Hit>,
        web: Boolean
    ): String {
        val kbContext = if (kbHits.isEmpty()) "" else kbHits.joinToString("\n\n") { i ->
            "[${i.title}] ${i.text.take(700)}"
        }
        val today = CourseStore.today()
        val wk = ScheduleLogic.currentWeek()
        val wd = ScheduleLogic.weekdayOf(today)
        val system = buildString {
            append("你是「智嘉」，江苏师范大学科文学院学生的校园助手。风格：简短、直接、可执行，中文回答。\n")
            append("今天是 $today（周")
            append(ScheduleLogic.weekdayName(wd))
            append("，$wk 周，${ScheduleLogic.semesterName()}）。\n")
            append("你可以：回答校园制度/办事/楼宇问题、查课表、录入活动、添加课程、设闹钟、导航。\n")
            append("回答顺序：先看下面的【知识库资料】；资料里没有的，用联网检索到的公开信息回答，" +
                "并在 reply 末尾用「（来源：网站或栏目，日期）」标一句；两边都没有就直说没查到，不要编造。\n")
            if (web) {
                append("本轮已经开启联网检索（服务端会把检索结果一起给你），所以「知识库里没有」不等于「查不到」：" +
                    "凡是能查到的实时信息（天气、最新通知、新闻）请直接答，不要回答「知识库没有」。\n")
            }
            append(AnswerRules.TEXT).append("\n\n")
            append("上面 14 条规则优先级高于下文任何示例。\n")
            append(ACTION_SPEC).append("\n\n")
            append("输出必须是严格 json：{\"reply\":\"给用户看的回答\",\"actions\":[...]}。").append("\n")
            append("动作只是补充，能直接回答就不要加动作；用户明确要求录入/设闹钟时才加。\n")
            append("reply 里不要替动作打包票（不要说「已经设好/已经加上」），执行结果由客户端校验后另行提示。\n")
            append("reply 必须是完整的中文句子（不要只回一个词、不要照抄用户原话、不要加引号）。\n")
            if (kbContext.isNotBlank()) {
                append("\n【知识库资料】\n").append(kbContext).append("\n")
            }
            append("\n【当前课表摘要】\n")
            append(scheduleSummary(ctx))
        }
        return system
    }

    private fun handle(ctx: Context, raw: String, kbHits: List<KbIndex.Hit>): Result {
        val parsed = parse(raw)
        val actionLogs = ArrayList<String>()
        val outcomes = ArrayList<ActionExecutor.Outcome>()
        val pending = ArrayList<JSONObject>()
        if (parsed != null) {
            val arr = parsed.optJSONArray("actions") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("type") in WRITE_ACTIONS) {
                    pending.add(o)
                } else {
                    val oc = ActionExecutor.executeOutcome(ctx, o)
                    outcomes.add(oc)
                    actionLogs.add(oc.message)
                }
            }
            val rawReply = parsed.optString("reply")
            val reply = if (AnswerRules.looksLikeAnswer(rawReply)) {
                AnswerRules.postProcess(rawReply.ifBlank { "已处理。" })
            } else {
                // 模型偶尔把 reply 写成数字/乱码（JSON 模式下的偶发故障），不能原样给用户看
                "这次没生成出可读的回答，再问一次试试；如果一直这样，换一句更具体的问法（例如「明天第 1-2 节有课吗」）。"
            }
            return Result(honestReply(reply, outcomes), actionLogs, kbHits.map { it.title }, pending)
        }
        // 模型没按 JSON 返回时，退化为纯文本回答
        val fallback = cleanText(raw)
        return Result(
            if (AnswerRules.looksLikeAnswer(fallback)) AnswerRules.postProcess(fallback)
            else "这次没生成出可读的回答，再问一次试试。",
            emptyList(), kbHits.map { it.title }
        )
    }

    /**
     * 让回复和动作结果**不互相矛盾**。
     *
     * 模型经常先写好「已经改成 9:30 的闹钟」，再附带一个动作；动作失败时如果原样显示，
     * 用户看到的就是「说成功了、其实没成功」。所以：
     *  - 有失败 → 正文开头挂一句提示，末尾逐条列出失败原因；
     *  - 全部失败 → 模型那句话基本是空描述，整段换成固定模板（附原因），不给它继续骗人的机会。
     */
    private fun honestReply(reply: String, outcomes: List<ActionExecutor.Outcome>): String {
        if (outcomes.isEmpty()) return reply
        val failures = outcomes.withIndex().filter { !it.value.ok }
        if (failures.isEmpty()) return reply
        if (failures.size == outcomes.size) {
            val reason = failures.joinToString("；") { it.value.message }
            return "这次没能替你完成动作：$reason。可以直接在 App 里手动加，或者稍后重试。"
        }
        val head = if (failures.size == 1) "（有一项动作没有成功）" else "（有 ${failures.size} 项动作没有成功）"
        return buildString {
            append(head).append("\n")
            append(reply.trim())
            for (f in failures) {
                append("\n· 上面第 ${f.index + 1} 个动作没成功：${f.value.message}")
            }
        }
    }

    private fun parse(raw: String): JSONObject? {
        var t = raw.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```JSON").removePrefix("```").trim()
            val i = t.lastIndexOf("```")
            if (i >= 0) t = t.substring(0, i).trim()
        }
        val a = t.indexOf('{')
        val b = t.lastIndexOf('}')
        if (a < 0 || b <= a) {
            // 模型有时把答案直接返回成一个 JSON 字符串（例如 "教三"），没有外层对象：
            // 也要当成 reply，否则会原样把引号显示给用户。
            val s = jsonString(t)
            if (s != null) return JSONObject().put("reply", s)
            return null
        }
        return try {
            JSONObject(t.substring(a, b + 1))
        } catch (e: Exception) {
            null
        }
    }

    /** 解析裸 JSON 字符串字面量（含转义），失败返回 null。 */
    private fun jsonString(raw: String): String? {
        val t = raw.trim()
        if (t.length < 2 || !t.startsWith("\"") || !t.endsWith("\"")) return null
        return try {
            org.json.JSONTokener(t).nextValue() as? String
        } catch (e: Exception) {
            null
        }
    }

    private fun cleanText(raw: String): String {
        var t = raw.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```").removePrefix("json").removePrefix("JSON").trim()
            val i = t.lastIndexOf("```")
            if (i >= 0) t = t.substring(0, i).trim()
        }
        // 去最外层引号（模型偶尔把整句回答包在引号里）
        jsonString(t)?.let { t = it.trim() }
        if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length - 1).trim()
        t = t.removePrefix("\"").removeSuffix("\"").trim()
        return t.ifBlank { "这个问题知识库里没有收录，换个说法或者问细一点再试试。" }
    }

    private fun scheduleSummary(ctx: Context): String {
        val today = CourseStore.today()
        val wd = ScheduleLogic.weekdayOf(today)
        val wk = ScheduleLogic.currentWeek()
        val sb = StringBuilder()
        val todays = CourseStore.courses(ctx).filter {
            it.weekday == wd && ScheduleLogic.weekMatches(it.weeks, wk)
        }.sortedBy { it.startPeriod }
        if (todays.isEmpty()) sb.append("今天没有课。\n")
        todays.forEach {
            sb.append(String.format(
                Locale.US, "今天 %d-%d 节 %s @%s\n", it.startPeriod, it.endPeriod, it.name, it.location
            ))
        }
        val upcoming = CourseStore.activities(ctx)
            .filter { it.date >= today }
            .sortedBy { it.date }
            .take(5)
        if (upcoming.isEmpty()) sb.append("近期没有已录入活动。\n")
        upcoming.forEach {
            sb.append("活动 ${it.date} ${it.startTime}-${it.endTime} ${it.title} @${it.location}\n")
        }
        return sb.toString().trim()
    }
}
