package com.campus.arnav.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.campus.arnav.data.ActivityDraft
import com.campus.arnav.data.ChatMsg
import com.campus.arnav.data.CourseStore
import com.campus.arnav.data.SettingsStore
import com.campus.arnav.data.ScheduleLogic
import com.campus.arnav.net.LlmClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 从通知文字/图片里抽取活动。优先走 DeepSeek JSON 模式；
 * 图片走通义 VL；失败时报错，让用户在设置里配 Key。
 */
object NoticeExtractor {

    private const val PROMPT = """你是江苏师范大学科文学院的活动信息抽取器。
从下面的通知原文中找出【所有需要学生到场参加的活动】（讲座、比赛、班会、演出、招聘会、报名截止等）。
只输出 JSON，不要解释。格式：
{"activities":[{"title":"活动名","date":"yyyy-MM-dd","start_time":"HH:mm","end_time":"HH:mm","location":"地点","organizer":"主办方","note":"关键要求，如报名方式/对象"}]}
规则：
- date 必须补全年份；今天是 %s。若通知只写“本周五”，按最近的将来日期换算。
- 时间用 24 小时制；只有开始时间时，end_time 可以留空。
- 找不到活动就返回 {"activities":[]}。
- 不要编造地点和主办方，原文没有就留空。

通知原文：
%s"""

    private const val VISION_PROMPT = """识别这张校园通知/海报图片里的文字，并抽取所有学生活动。
只输出 JSON，不要解释，格式：
{"activities":[{"title":"活动名","date":"yyyy-MM-dd","start_time":"HH:mm","end_time":"HH:mm","location":"地点","organizer":"主办方","note":"报名方式/对象等"}]}
今天是 %s。date 必须补全年份；没有活动就返回 {"activities":[]}。"""

    fun extractText(ctx: Context, text: String): List<ActivityDraft> {
        val today = CourseStore.today()
        val provider = SettingsStore.deepseek(ctx)
        val content = LlmClient.chat(
            provider,
            listOf(
                ChatMsg("system", "你只输出 json。"),
                ChatMsg("user", String.format(Locale.US, PROMPT, today, text.take(12_000)))
            ),
            jsonMode = true
        )
        return parse(cleanJson(content))
    }

    fun extractImage(ctx: Context, imageBase64: String, mime: String): List<ActivityDraft> {
        val today = CourseStore.today()
        val provider = SettingsStore.vision(ctx)
        val content = LlmClient.vision(
            provider,
            String.format(Locale.US, VISION_PROMPT, today),
            imageBase64, mime
        )
        return parse(cleanJson(content))
    }

    /** 读取图片并缩到最长边 1600px，供 OCR 与 base64 共用。 */
    fun loadBitmap(ctx: Context, uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("读不到图片")
        val w = decoded.width
        val h = decoded.height
        return if (w > 1600 || h > 1600) {
            val ratio = 1600f / maxOf(w, h)
            val scaled = Bitmap.createScaledBitmap(decoded, (w * ratio).toInt(), (h * ratio).toInt(), true)
            if (scaled !== decoded) decoded.recycle()
            scaled
        } else decoded
    }

    /** 读取图片并缩到最长边 1600px、JPEG 85，避免 base64 过大导致接口拒绝。 */
    fun loadImageBase64(ctx: Context, uri: Uri): String {
        val scaled = loadBitmap(ctx, uri)
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        scaled.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun parse(json: String): List<ActivityDraft> {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw LlmClient.LlmException("模型没有返回可解析的活动 JSON：${json.take(200)}")
        }
        val arr: JSONArray = root.optJSONArray("activities") ?: JSONArray()
        val out = ArrayList<ActivityDraft>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title").trim()
            if (title.isBlank()) continue
            val d = ActivityDraft(
                title = title,
                date = normalizeDate(o.optString("date").trim()),
                startTime = normalizeTime(o.optString("start_time").trim()),
                endTime = normalizeTime(o.optString("end_time").trim()),
                location = o.optString("location").trim(),
                organizer = o.optString("organizer").trim(),
                note = o.optString("note").trim()
            )
            out.add(d)
        }
        return out
    }

    private fun cleanJson(s: String): String {
        var t = s.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```JSON").removePrefix("```").trim()
            val idx = t.lastIndexOf("```")
            if (idx >= 0) t = t.substring(0, idx).trim()
        }
        val a = t.indexOf('{')
        val b = t.lastIndexOf('}')
        if (a >= 0 && b > a) t = t.substring(a, b + 1)
        return t
    }

    internal fun normalizeDate(s: String): String {
        if (s.isBlank()) return CourseStore.today()
        // 2026年9月25日 / 2026/9/25 / 2026-09-25
        val m = Regex("(\\d{4})\\s*[年./-]\\s*(\\d{1,2})\\s*[月./-]\\s*(\\d{1,2})").find(s)
        if (m != null) {
            val y = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            val d = m.groupValues[3].toInt()
            if (mo !in 1..12 || d !in 1..31) return s
            return String.format(Locale.US, "%04d-%02d-%02d", y, mo, d)
        }
        val m2 = Regex("(\\d{1,2})\\s*[月./-]\\s*(\\d{1,2})").find(s)
        if (m2 != null) {
            val mo = m2.groupValues[1].toInt()
            val d = m2.groupValues[2].toInt()
            if (mo !in 1..12 || d !in 1..31) return s
            val now = java.util.Calendar.getInstance()
            var y = now.get(java.util.Calendar.YEAR)
            var cand = String.format(Locale.US, "%04d-%02d-%02d", y, mo, d)
            // 跨年通知（如 12 月发“1 月 5 日”）：若落到过去，顺延一年
            if (cand < CourseStore.today()) {
                y += 1
                cand = String.format(Locale.US, "%04d-%02d-%02d", y, mo, d)
            }
            return cand
        }
        return s
    }

    internal fun normalizeTime(s: String): String {
        if (s.isBlank()) return ""
        // “第3点要求”里的“点”不是时间；加负向后行断言避免误伤
        val m = Regex("(?<!第)(\\d{1,2})\\s*[:：点时]\\s*(\\d{1,2})?").find(s)
        if (m != null) {
            var h = m.groupValues[1].toIntOrNull() ?: return s
            var mi = m.groupValues[2].toIntOrNull() ?: if (s.contains("半")) 30 else 0
            val pm = s.contains("下午") || s.contains("晚上") || s.contains("傍晚") || s.contains("夜")
            if (pm && h in 1..11) h += 12
            if (s.contains("中午") && h in 1..10) h += 12
            if (s.contains("凌晨") && h == 12) h = 0
            if (h == 24) h = 0
            if (h !in 0..23 || mi !in 0..59) return s
            return String.format(Locale.US, "%02d:%02d", h, mi)
        }
        return s
    }

    /** 当前学期第几周，供提示词使用。 */
    fun weekHint(): String = "第 ${ScheduleLogic.currentWeek()} 周"
}
