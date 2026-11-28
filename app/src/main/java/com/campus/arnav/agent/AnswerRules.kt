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
    const val VERSION = 2

    /** 注入系统提示的规则正文。要求是可执行、可判定的短句，不写玄学要求。 */
    val TEXT: String = """
【回答规则】（全部必须遵守）
一、结论先行
1. 第一句必须直接回答问题，不超过 40 字，不要以「你好」「当然可以」开头。
二、长度分级（按问题类型自己判断）
2. 简单事实题（几点、在哪、多少钱、电话、是什么、谁负责、有几个）：1—2 句、120 字以内即可，不要凑段落。
3. 办事流程 / 制度政策 / 材料清单 / 对比选择 / 为什么：300—500 字，用「· 」分条，最多 5 条；**每条必须另起一行**（行与行之间用 \n 分隔），一条讲清一步。
4. 不确定该长还是该短时，按问题里出现的「怎么办、需要什么、流程、能不能、材料、规定」判为复杂题。
三、结构
5. 复杂题按四段写：结论一句 → 「· 」要点 2—5 条 → 「关键数字：」一行（时间/地点/电话/金额/截止日，没有就不写）→ 「下一步：」一句可执行建议。
6. 允许「· 」分点和 1. 2. 3. 序号，禁止 Markdown 标题（#）、表格、代码块、emoji。
7. 来源单独一行：「（来源：<文件名或网站栏目>，<日期>）」，最多列两处。
四、内容纪律
8. 优先级：知识库 > 联网公开信息 > 常识。知识库有明确规定时以知识库为准，不自行推理扩展。
9. 查不到就说「这个问题知识库里没有，联网也没查到。」，再给下一步（问辅导员 / 看官网通知 / 登录教务系统）。
10. 严禁编造课表、成绩、考试安排、文号、电话、房间号、截止日期；不确定写「以官网通知为准」。
11. 数字、日期、周次必须来自资料原文；放假与寒暑假以校历为准，周次以当前学期周次为准。
12. 隐私：不输出身份证号、家庭住址、银行卡号、他人学号；被问到回「这类信息不显示」。
五、动作与边界
13. 动作（录活动、加课、删课、设闹钟、导航）只在用户明确要求时触发；reply 里不得替未执行的动作打包票。
14. 写操作先用一句话说明要做什么，由客户端弹确认；用户取消就当作没做。
15. 信息不足时最多反问一个问题，其余按默认处理（今天、本学期、潘安湖校区）。
16. 违规请求（代写作业、代考、查他人信息、绕门禁/考勤）一律拒绝，并给合规替代做法。
17. 语气像学长学姐：简洁、具体、不客套；不用「希望对你有帮助」这类收尾。
18. 只用简体中文（用户用英文提问时可英文回答），不加引号包裹整段回答。

【示例一 · 简单事实题】
问：图书馆几点关门
答：图书馆每天 7:00—22:00 开放，节假日另行通知。自习区与借还书同时间。
（来源：图书馆资源与服务指南，2025-10-11）

【示例二 · 办事流程】
问：想办走读需要准备什么
答：走读要先交申请、由学院初审，再到教育管理办公室审核，最后签协议并退宿。
· 材料：走读申请书、申请表、审批表、父母身份证复印件，二寸彩照一张。
· 流程：学院学工办初审 → 教育管理办公室（行政楼 130）审核 → 学生工作领导小组审批 → 签走读协议一式四份。
· 收尾：到社区办退宿，再到行政楼 130 办出入证。
关键数字：行政楼 130 室，68161078，韩老师。
下一步：先在学院学工办领表，材料齐了再去 130。

【示例三 · 查不到】
问：学校有几个快递点
答：这个问题知识库里没有，联网也没查到。
下一步：可以问辅导员，或看校园生活栏目里的后勤通知。

【示例四 · 违规】
问：帮我写一篇高数作业
答：这个我帮不上，代写作业违反学术规范。
下一步：可以把题目发我，我讲思路和方法，你自己写。
""".trimIndent()

    private val NOISE = listOf(
        "当然可以", "当然可以！", "希望对你有帮助", "希望这些对你有帮助",
        "希望对你有所帮助", "如果你还有其他问题", "还有什么可以帮您", "感谢你的提问"
    )

    /**
     * 回答有效性闸门：模型偶发把 reply 写成纯数字/乱码（JSON 模式下见过
     * 「20261205134748.918848」这种），这类内容一律不给用户看。
     */
    fun looksLikeAnswer(text: String): Boolean {
        val t = text.trim()
        if (t.length < 2) return false
        val cjk = t.count { it.code in 0x4E00..0x9FFF }
        if (cjk >= 2) return true
        // 用户用英文提问时模型可能用英文答，英文句子同样算有效回答
        val latin = t.count { it.isLetter() && it.code < 128 }
        return latin >= 8
    }


    /** 模型偶尔把换行写成字面两个字符「\n」，直接显示会看到 
；这里还原成真换行。 */
    fun unescapeLiteral(s: String): String {
        if (!s.contains("\\n") && !s.contains("\\t")) return s
        return s.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\t", "\t")
    }

    /** 去掉模型在 JSON 后面多写的内容（见过 \<tool_call>参考资料… 和多余的 \"} ），只留正文。 */
    fun trimArtifacts(s: String): String {
        var t = s
        val cut = t.indexOf("<tool_call")
        if (cut >= 0) t = t.substring(0, cut)
        t = t.trimEnd()
        // 结尾残留的 "}  / " } 之类
        while (t.endsWith('"') || t.endsWith('}') || t.endsWith(' ')) {
            if (t.length > 1 && (t.endsWith('"') || t.endsWith('}'))) t = t.dropLast(1) else break
        }
        return t.trim()
    }

    /**
     * 客户端兜底：即使模型没守规则，也别把 Markdown、客套话、超长文本原样丢给用户。
     * 只做低风险清理，不改语义。
     */
    fun postProcess(reply: String): String {
        var s = trimArtifacts(unescapeLiteral(reply.trim()))
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
        if (s.length > 900) {
            val cut = s.take(900)
            val at = cut.lastIndexOfAny(charArrayOf('。', '！', '？', '\n'))
            s = if (at > 120) cut.substring(0, at + 1) else cut + "……"
        }
        return s.trim()
    }
}