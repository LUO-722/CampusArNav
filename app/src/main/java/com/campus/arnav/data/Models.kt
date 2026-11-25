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
    val source: String = "manual" // demo/import/manual
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

/** 聊天消息。 */
data class ChatMsg(
    val role: String,          // system/user/assistant
    var content: String
)

/** 从通知里解析出来的活动草稿。 */
data class ActivityDraft(
    var title: String = "",
    var date: String = "",
    var startTime: String = "",
    var endTime: String = "",
    var location: String = "",
    var organizer: String = "",
    var note: String = ""
)
