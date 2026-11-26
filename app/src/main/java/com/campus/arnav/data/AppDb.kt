package com.campus.arnav.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class AppDb private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "zhijia.db", null, 2) {

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
                source TEXT DEFAULT 'manual',
                raw TEXT DEFAULT '',
                period_text TEXT DEFAULT '',
                week_text TEXT DEFAULT ''
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
        createJwTables(db)
    }

    /** 教务同步来的考试与成绩；整行原文另存 raw，便于“一字不差”地回看。 */
    private fun createJwTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS exams(
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                course TEXT NOT NULL,
                time TEXT DEFAULT '',
                location TEXT DEFAULT '',
                seat TEXT DEFAULT '',
                raw TEXT DEFAULT ''
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS grades(
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                term TEXT DEFAULT '',
                course TEXT NOT NULL,
                credit TEXT DEFAULT '',
                score TEXT DEFAULT '',
                gpa TEXT DEFAULT '',
                raw TEXT DEFAULT ''
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 → v2：课程单元格原文三列 + 考试/成绩表
        if (oldVersion < 2) {
            runCatching { db.execSQL("ALTER TABLE courses ADD COLUMN raw TEXT DEFAULT ''") }
            runCatching { db.execSQL("ALTER TABLE courses ADD COLUMN period_text TEXT DEFAULT ''") }
            runCatching { db.execSQL("ALTER TABLE courses ADD COLUMN week_text TEXT DEFAULT ''") }
            createJwTables(db)
        }
    }

    companion object {
        @Volatile
        private var inst: AppDb? = null

        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: AppDb(ctx).also { inst = it }
        }
    }
}