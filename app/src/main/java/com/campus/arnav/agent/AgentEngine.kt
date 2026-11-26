package com.campus.arnav.agent

import android.content.Context
import com.campus.arnav.data.ChatMsg
import com.campus.arnav.data.CourseStore
import com.campus.arnav.data.SettingsStore
import com.campus.arnav.data.ScheduleLogic
import com.campus.arnav.kb.KbIndex
import com.campus.arnav.net.LlmClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 聊天 Agent：知识库检索 + 模型决策 + 动作执行。
 * 模型按约定返回 {"reply": "...", "actions": [...]}；动作由 [ActionExecutor] 执行。
 */
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

    fun ask(ctx: Context, history: List<ChatMsg>, userText: String): Result {
        val provider = SettingsStore.chatProvider(ctx)
        if (!provider.ready()) {
            return Result(
                "现在是离线模式：不填 Key 也能查知识库、看课程表；想要对话式问答与拍照识通知，" +
                    "可在「☰ → 设置」里填 DeepSeek（文本）或自建代理地址，都是可选的。",
                emptyList(), emptyList()
            )
        }

        val (system, kbHits) = buildSystem(ctx, userText)
        val msgs = ArrayList<ChatMsg>()
        msgs.add(ChatMsg("system", system))
        // 只带最近 8 条，避免上下文过长；聊天页传入的 history 可能已包含本轮 user 消息，避免重复追加
        val recent = history.takeLast(8).filter { it.role != "system" }
        recent.forEach { msgs.add(ChatMsg(it.role, it.content)) }
        val last = recent.lastOrNull()
        if (last == null || last.role != "user" || last.content != userText) {
            msgs.add(ChatMsg("user", userText))
        }

        val raw = LlmClient.chat(provider, msgs, jsonMode = true)
        return handle(ctx, raw, kbHits)
    }

    /** 聊天页发图片：必须用通义 VL（多模态），其余流程与 [ask] 一致。 */
    fun askImage(ctx: Context, history: List<ChatMsg>, userText: String, imageBase64: String): Result {
        val provider = SettingsStore.vision(ctx)
        if (!provider.ready()) {
            return Result(
                "识图需要先在「设置」里填通义 VL 的 API Key，填好就能发通知截图，我帮你提取活动。",
                emptyList(), emptyList()
            )
        }
        val (system, kbHits) = buildSystem(ctx, userText)
        val raw = LlmClient.visionChat(provider, system, history, userText, imageBase64)
        return handle(ctx, raw, kbHits)
    }

    private fun buildSystem(ctx: Context, userText: String): Pair<String, List<KbIndex.Hit>> {
        val kbHits = KbIndex.search(ctx, userText, 4)
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
            append("涉及学校事实必须优先依据下面的【知识库资料】；资料没有就说“知识库里没写”，不要编造。\n")
            append(ACTION_SPEC).append("\n\n")
            append("输出必须是严格 json：{\"reply\":\"给用户看的回答\",\"actions\":[...]}。").append("\n")
            append("动作只是补充，能直接回答就不要加动作；用户明确要求录入/设闹钟时才加。\n")
        append("reply 必须是完整的中文句子（不要只回一个词、不要照抄用户原话、不要加引号）；知识库里没有的就直说没有。\n")
            if (kbContext.isNotBlank()) {
                append("\n【知识库资料】\n").append(kbContext).append("\n")
            }
            append("\n【当前课表摘要】\n")
            append(scheduleSummary(ctx))
        }
        return system to kbHits
    }

    private fun handle(ctx: Context, raw: String, kbHits: List<KbIndex.Hit>): Result {
        val parsed = parse(raw)
        val actionLogs = ArrayList<String>()
        val pending = ArrayList<JSONObject>()
        if (parsed != null) {
            val arr = parsed.optJSONArray("actions") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("type") in WRITE_ACTIONS) {
                    pending.add(o)
                } else {
                    actionLogs.add(ActionExecutor.execute(ctx, o))
                }
            }
            val reply = parsed.optString("reply").ifBlank { "已处理。" }
            return Result(reply, actionLogs, kbHits.map { it.title }, pending)
        }
        // 模型没按 JSON 返回时，退化为纯文本回答
        return Result(cleanText(raw), emptyList(), kbHits.map { it.title })
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
