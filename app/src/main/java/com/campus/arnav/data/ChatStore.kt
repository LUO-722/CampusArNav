package com.campus.arnav.data

import android.content.ContentValues
import android.content.Context

/**
 * 对话记录持久化（AppDb 的 chats 表）。只保留最近 200 条，避免无限增长。
 */
object ChatStore {

    private const val MAX_ROWS = 200

    private fun db(ctx: Context) = AppDb.get(ctx).writableDatabase

    fun append(ctx: Context, role: String, content: String) {
        val v = ContentValues().apply {
            put("role", role)
            put("content", content)
            put("ts", System.currentTimeMillis())
        }
        val d = db(ctx)
        d.insert("chats", null, v)
        d.execSQL(
            "DELETE FROM chats WHERE _id NOT IN (SELECT _id FROM chats ORDER BY _id DESC LIMIT $MAX_ROWS)"
        )
    }

    /** 按时间正序返回最近 limit 条。 */
    fun recent(ctx: Context, limit: Int = 30): List<ChatMsg> {
        val out = ArrayList<ChatMsg>()
        db(ctx).query(
            "chats", arrayOf("role", "content"), null, null, null, null, "_id DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) out.add(ChatMsg(c.getString(0), c.getString(1)))
        }
        out.reverse()
        return out
    }

    fun clear(ctx: Context) {
        db(ctx).delete("chats", null, null)
    }
}
