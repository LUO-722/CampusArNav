package com.campus.arnav.data

/** 课程：按“周几 + 第几节”重复，weeks 描述生效周次。 */
data class Course(
    val id: Long = 0,
    val name: String,
    val teacher: String = "",
    val location: String = "",
    val weekday: Int,          // 1=周一 … 7=周日
    val startPeriod: Int,      // 1..12
    val endPeriod: Int,
    val weeks: String = "1-16",
    val color: Int,
    val source: String = "manual", // demo/import/manual/jwxt/agent
    /** 教务系统课表单元格原文（一字不改），详情页直接显示，避免二次加工丢信息。 */
    val raw: String = "",
    /** 原始节次文字，如「第1-2节」。 */
    val periodText: String = "",
    /** 原始周次文字，如「1-16周(单)」。 */
    val weekText: String = ""
)

/** 一次性活动/通知，例如讲座、比赛、班会。 */
data class Activity(
    val id: Long = 0,
    val title: String,
    val date: String,          // yyyy-MM-dd
    val startTime: String,     // HH:mm
    val endTime: String,       // HH:mm
    val location: String = "",
    val note: String = "",
    val conflict: Boolean = false,
    val source: String = "zhijia"
)

/** 教务考试安排（原文整行也保留在 raw）。 */
data class Exam(
    val id: Long = 0,
    val course: String,
    val time: String = "",
    val location: String = "",
    val seat: String = "",
    val raw: String = ""
)

/** 教务成绩单一行。 */
data class Grade(
    val id: Long = 0,
    val term: String = "",
    val course: String,
    val credit: String = "",
    val score: String = "",
    val gpa: String = "",
    val raw: String = ""
)

/** 从教务系统抓到的学生信息（原文缺失时留空）。 */
data class JwProfile(
    val name: String = "",
    val sid: String = "",
    val college: String = "",
    val major: String = "",
    val clazz: String = "",
    val raw: String = ""
)

/** 聊天消息。 */
data class ChatMsg(
    val role: String,          // system/user/assistant
    var content: String
)

/**
 * 一次会话：App 每次冷启动新开一个，旧会话留在聊天记录列表里。
 *
 * [title] 默认取首条 user 消息的前 18 个字（见 `ChatStore.TITLE_MAX`），可手动改。
 */
data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)

/** 从通知里解析出来的活动草稿。 */
data class ActivityDraft(
    var title: String = "",
    var date: String = "",
    var startTime: String = "",
    var endTime: String = "",
    var location: String = "",
    var organizer: String = "",
    var note: String = "",
    /** 看图/读文时对应到图上的原文片段，用于让用户核对，不落库。 */
    var evidence: String = ""
)