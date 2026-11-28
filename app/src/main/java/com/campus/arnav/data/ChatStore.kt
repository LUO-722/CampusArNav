package com.campus.arnav.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.util.UUID

/**
 * 对话记录持久化（AppDb 的 `chats` 表 + `chat_sessions` 表）。
 *
 * 每次冷启动新开一个会话（[newSession]），旧会话留在 [sessions] 列表里，可以回看/删除；
 * 单条会话最多保留 [MAX_ROWS] 条消息。当前会话的 sid 记在 SharedPreferences
 * `zhijia_chat/current_sid`。
 *
 * 老库迁移过来的消息没有会话 id，统一挂到 [LEGACY_SID]（标题「历史记录」）会话下。
 */
object ChatStore {

    private const val PREF = "zhijia_chat"
    private const val KEY_CURRENT = "current_sid"
    private const val MAX_ROWS = 200

    /** 会话标题最多保留的字数（超出截断，第 19 个字起丢弃）。 */
    const val TITLE_MAX = 18

    /** 老库没有会话概念，迁移过来的历史消息统一挂在它下面。 */
    const val LEGACY_SID = "legacy"

    private fun db(ctx: Context): SQLiteDatabase = AppDb.get(ctx).writableDatabase

    /** 数据库结构异常（老库缺表/缺列）时做一次修复，避免直接把用户崩掉。 */
    private fun safe(ctx: Context, block: (SQLiteDatabase) -> Unit) {
        runCatching { block(db(ctx)) }.onFailure {
            runCatching { AppDb.repairChats(ctx) }
            runCatching { block(db(ctx)) }
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ---- 会话 ----

    /** 新建一个会话并记为当前会话，返回 sid。 */
    fun newSession(ctx: Context): String {
        var sid = ""
        safe(ctx) { d -> sid = createSession(d, System.currentTimeMillis()) }
        if (sid.isNotBlank()) prefs(ctx).edit().putString(KEY_CURRENT, sid).apply()
        return sid
    }

    /** 当前会话；没有（或已被删掉）就新开一个。 */
    fun currentSession(ctx: Context): String {
        val cur = prefs(ctx).getString(KEY_CURRENT, null)
        if (!cur.isNullOrBlank()) {
            var ok = false
            safe(ctx) { d -> ok = sessionExists(d, cur) }
            if (ok) return cur
        }
        return newSession(ctx)
    }

    /** 会话列表，最近更新的在前；没有消息的会话不显示（刚开的空会话、迁移过来的空 legacy 都不占一行）。 */
    fun sessions(ctx: Context): List<ChatSession> {
        var out: List<ChatSession> = emptyList()
        safe(ctx) { d -> out = sessionList(d) }
        return out
    }

    /** 某个会话的消息，按时间正序。 */
    fun messages(ctx: Context, sid: String): List<ChatMsg> {
        if (sid.isBlank()) return emptyList()
        var out: List<ChatMsg> = emptyList()
        safe(ctx) { d -> out = messagesIn(d, sid) }
        return out
    }

    /** 追加一条消息（顺带刷新会话时间；首条 user 消息取前 18 字当标题）。 */
    fun add(ctx: Context, sid: String, msg: ChatMsg) {
        if (sid.isBlank()) return
        safe(ctx) { d -> addTo(d, sid, msg, System.currentTimeMillis()) }
    }

    /** 手动改会话标题（同样按 [TITLE_MAX] 截断）。 */
    fun setTitle(ctx: Context, sid: String, title: String) {
        if (sid.isBlank()) return
        safe(ctx) { d -> setTitleIn(d, sid, title.trim().take(TITLE_MAX)) }
    }

    /** 删除会话及其消息；删的是当前会话时顺便清掉「当前会话」指针。 */
    fun delete(ctx: Context, sid: String) {
        if (sid.isBlank()) return
        safe(ctx) { d -> deleteIn(d, sid) }
        if (prefs(ctx).getString(KEY_CURRENT, null) == sid) {
            prefs(ctx).edit().remove(KEY_CURRENT).apply()
        }
    }

    // ---- 兼容旧调用方（ChatTab / SettingsActivity 现有签名不变）----

    /** 旧签名：往当前会话追加一条。 */
    fun append(ctx: Context, role: String, content: String) {
        add(ctx, currentSession(ctx), ChatMsg(role, content))
    }

    /** 旧签名：当前会话最近 limit 条（正序）。 */
    fun recent(ctx: Context, limit: Int = 30): List<ChatMsg> {
        var out: List<ChatMsg> = emptyList()
        val sid = currentSession(ctx)
        if (sid.isNotBlank()) safe(ctx) { d -> out = recentIn(d, sid, limit) }
        return out
    }

    /** 旧签名：清空当前会话的消息（会话本身保留、标题重置）。 */
    fun clear(ctx: Context) {
        val sid = currentSession(ctx)
        if (sid.isBlank()) return
        safe(ctx) { d ->
            d.delete("chats", "sid=?", arrayOf(sid))
            setTitleIn(d, sid, "")
        }
    }

    // ---- 拿 db 直接干活的实现层：自检用临时库跑同一套代码，不碰用户真实数据 ----

    internal fun createSession(d: SQLiteDatabase, now: Long): String {
        val sid = UUID.randomUUID().toString().replace("-", "").substring(0, 12)
        val v = ContentValues().apply {
            put("sid", sid)
            put("title", "")
            put("created_at", now)
            put("updated_at", now)
        }
        d.insert("chat_sessions", null, v)
        return sid
    }

    internal fun sessionExists(d: SQLiteDatabase, sid: String): Boolean =
        d.rawQuery("SELECT 1 FROM chat_sessions WHERE sid=? LIMIT 1", arrayOf(sid)).use { it.moveToFirst() }

    internal fun sessionList(d: SQLiteDatabase): List<ChatSession> {
        val out = ArrayList<ChatSession>()
        d.rawQuery(
            "SELECT sid,title,created_at,updated_at FROM chat_sessions ORDER BY updated_at DESC, _id DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val sid = c.getString(0) ?: continue
                // 空会话不进列表：ChatTab 每次构造都会 newSession() 开一个空会话，
                // 不过滤的话「聊天记录」会被一堆空行塞满；legacy 会话没消息时同理。
                if (countMessages(d, sid) == 0) continue
                out.add(
                    ChatSession(
                        id = sid,
                        title = c.getString(1) ?: "",
                        createdAt = c.getLong(2),
                        updatedAt = c.getLong(3)
                    )
                )
            }
        }
        return out
    }

    internal fun messagesIn(d: SQLiteDatabase, sid: String): List<ChatMsg> {
        val out = ArrayList<ChatMsg>()
        d.query("chats", arrayOf("role", "content"), "sid=?", arrayOf(sid), null, null, "_id ASC").use { c ->
            while (c.moveToNext()) out.add(ChatMsg(c.getString(0), c.getString(1)))
        }
        return out
    }

    internal fun recentIn(d: SQLiteDatabase, sid: String, limit: Int): List<ChatMsg> {
        val out = ArrayList<ChatMsg>()
        d.query(
            "chats", arrayOf("role", "content"), "sid=?", arrayOf(sid), null, null, "_id DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) out.add(ChatMsg(c.getString(0), c.getString(1)))
        }
        out.reverse()
        return out
    }

    internal fun addTo(d: SQLiteDatabase, sid: String, msg: ChatMsg, now: Long) {
        val v = ContentValues().apply {
            put("role", msg.role)
            put("content", msg.content)
            put("ts", now)
            put("sid", sid)
        }
        d.insert("chats", null, v)
        // 每个会话只留最近 MAX_ROWS 条
        d.execSQL(
            "DELETE FROM chats WHERE sid=? AND _id NOT IN " +
                "(SELECT _id FROM chats WHERE sid=? ORDER BY _id DESC LIMIT $MAX_ROWS)",
            arrayOf(sid, sid)
        )
        if (msg.role == "user" && titleOf(d, sid).isBlank()) setTitleIn(d, sid, headline(msg.content))
        d.execSQL("UPDATE chat_sessions SET updated_at=? WHERE sid=?", arrayOf<Any>(now, sid))
    }

    internal fun setTitleIn(d: SQLiteDatabase, sid: String, title: String) {
        d.execSQL("UPDATE chat_sessions SET title=? WHERE sid=?", arrayOf<Any>(title, sid))
    }

    internal fun deleteIn(d: SQLiteDatabase, sid: String) {
        d.delete("chats", "sid=?", arrayOf(sid))
        d.delete("chat_sessions", "sid=?", arrayOf(sid))
    }

    private fun titleOf(d: SQLiteDatabase, sid: String): String =
        d.rawQuery("SELECT title FROM chat_sessions WHERE sid=?", arrayOf(sid)).use {
            if (it.moveToFirst()) it.getString(0) ?: "" else ""
        }

    private fun countMessages(d: SQLiteDatabase, sid: String): Int =
        d.rawQuery("SELECT COUNT(*) FROM chats WHERE sid=?", arrayOf(sid)).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    /** 标题 = 首条 user 消息的前 [TITLE_MAX] 个字（不加省略号；emoji 被切一半时丢掉半个代理对）。 */
    private fun headline(text: String): String {
        val s = text.trim()
        if (s.length <= TITLE_MAX) return s
        var cut = s.substring(0, TITLE_MAX)
        if (cut.lastOrNull()?.isHighSurrogate() == true) cut = cut.dropLast(1)
        return cut
    }
}