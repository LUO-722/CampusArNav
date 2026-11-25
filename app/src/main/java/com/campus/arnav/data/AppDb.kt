package com.campus.arnav.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class AppDb private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "zhijia.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE courses(
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                teacher TEXT DEFAULT '',
                location TEXT DEFAULT '',
                weekday INTEGER NOT NULL,
                start_period INTEGER NOT NULL,
                end_period INTEGER NOT NULL,
                weeks TEXT DEFAULT '1-16',
                color INTEGER NOT NULL,
                source TEXT DEFAULT 'manual'
            )"""
        )
        db.execSQL(
            """CREATE TABLE activities(
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                date TEXT NOT NULL,
                start_time TEXT DEFAULT '',
                end_time TEXT DEFAULT '',
                location TEXT DEFAULT '',
                note TEXT DEFAULT '',
                conflict INTEGER DEFAULT 0,
                source TEXT DEFAULT 'zhijia'
            )"""
        )
        db.execSQL(
            """CREATE TABLE course_skips(
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                course_id INTEGER NOT NULL,
                date TEXT NOT NULL,
                UNIQUE(course_id, date)
            )"""
        )
        db.execSQL(
            """CREATE TABLE chats(
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                ts INTEGER NOT NULL
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 为首个版本，后续升级在这里处理
    }

    companion object {
        @Volatile
        private var inst: AppDb? = null

        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: AppDb(ctx).also { inst = it }
        }
    }
}
