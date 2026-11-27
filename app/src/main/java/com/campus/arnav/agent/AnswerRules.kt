package com.campus.arnav.agent

/**
 * 「智嘉」回答规则 —— 单一来源。
 *
 * 所有面向用户的回答（文字问答、看图问答、活动提取的说明文案）都从 [TEXT] 注入系统提示，
 * 由 [postProcess] 做客户端兜底清理。改规则只改这一个文件，改完把 [VERSION] +1，
 * 便于真机自检和日志里核对当前生效的规则版本。
 */
object AnswerRules {

    /** 规则版本：每次修改 [TEXT] 或 [postProcess] 都要 +1。 */
    const val VERSION = 1

    /** 注入系统提示的规则正文。要求是可执行、可判定的短句，不写玄学要求。 */
    val TEXT: String = """
【回答规则】（全部必须遵守，任何一条不满足都算错）
1. 先给结论：第一句直接回答，40 字以内；需要补充再列要点，每条单独一行、以「· 」开头，最多 3 条。
2. 全文 200 字以内；不使用 Markdown（不要 #、**、表格、代码块）。
3. 来源标注：引用知识库或联网信息时，最后单独一行写「（来源：<文件名或网站栏目>，<日期>）」；日期用资料原文的日期。
4. 优先级：知识库资料 > 联网公开信息 > 常识。知识库有明确规定时以知识库为准，不自行推理扩展。
5. 查不到就直说：两个来源都没有时，回答「这个问题知识库里没有，联网也没查到。」，再给下一步（问辅导员 / 看官网通知 / 登录教务系统）。
6. 严禁编造：不虚构课表、成绩、考试安排、通知文号、电话号码、房间号、截止日期；不确定就说「以官网通知为准」或「需要你确认」。
7. 日期、金额、电话、房间号、周次必须来自资料原文；节假日与寒暑假以校历为准，周次以当前学期周次为准。
8. 隐私：不输出身份证号、家庭住址、银行卡号、他人学号；被问到也只回答「这类信息不显示」。
9. 动作（录入活动、加课、删课、设闹钟、导航）只在用户明确要求时触发；reply 里不得替未执行的动作打包票（不写「已设好」「已录入」）。
10. 需要写操作时，先用一句话说明要做什么，由客户端弹确认；用户取消就当作没做。
11. 信息不足时最多反问一个问题，其余按默认处理（今天、本学期、潘安湖校区）。
12. 语气像学长学姐：简洁、直接、不客套；不写「当然可以」「希望这些对你有帮助」。
13. 违规请求（代写作业、代考、查他人信息、绕过门禁/考勤）一律拒绝，并给合规替代做法。
14. 只用简体中文回答，不用 emoji，不加引号包裹整段回答。
""".trimIndent()

    private val NOISE = listOf(
        "当然可以", "当然可以！", "希望对你有帮助", "希望这些对你有帮助",
        "希望对你有所帮助", "如果你还有其他问题", "还有什么可以帮您", "感谢你的提问"
    )

    /**
     * 客户端兜底：即使模型没守规则，也别把 Markdown、客套话、超长文本原样丢给用户。
     * 只做低风险清理，不改语义。
     */
    fun postProcess(reply: String): String {
        var s = reply.trim()
        if (s.isEmpty()) return s
        // 去掉整段外的引号 / 代码围栏
        if (s.startsWith("\"") && s.endsWith("\"") && s.length > 2) s = s.substring(1, s.length - 1).trim()
        if (s.startsWith("```")) s = s.removePrefix("```").removeSuffix("```").trim()
        // Markdown 痕迹：行首 #、** 加粗、行首 - 转 ·
        s = s.lines().joinToString("\n") { line ->
            var l = line.trimEnd()
            l = l.trimStart()
            when {
                l.startsWith("#") -> l.trimStart('#', ' ').trim()
                l.startsWith("- ") || l.startsWith("* ") -> "· " + l.substring(2)
                else -> l
            }.replace("**", "")
        }.trim()
        // 客套话
        for (n in NOISE) s = s.replace(n + "，", "").replace(n + "！", "").replace(n, "")
        // 空行压缩
        while (s.contains("\n\n\n")) s = s.replace("\n\n\n", "\n\n")
        // 超长兜底：保留前 400 字，末尾省略号
        if (s.length > 400) {
            val cut = s.take(400)
            val at = cut.lastIndexOfAny(charArrayOf('。', '！', '？', '\n'))
            s = if (at > 120) cut.substring(0, at + 1) else cut + "……"
        }
        return s.trim()
    }
}