package com.feiyu.notes.data

import android.content.Context
import kotlinx.serialization.json.Json
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Single SQLite database for every notebook (spec §5).
 * Foreign keys cascade lesson/notebook deletes and delete a thread's whole subtree
 * through parent_entry_id. template_id and source/attached ID lists deliberately have
 * no foreign key so the UI can show "已删除" for dangling references.
 */
class NotebookDatabase(private val context: Context, name: String? = NAME) :
    SQLiteOpenHelper(context, name, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE templates (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                instruction TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                source TEXT
            )
            """
        )
        db.execSQL(
            """
            CREATE TABLE notebooks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL CHECK (kind IN ('course', 'practice')),
                name TEXT NOT NULL,
                linked_course_id INTEGER REFERENCES notebooks(id) ON DELETE SET NULL,
                default_template_id INTEGER REFERENCES templates(id) ON DELETE SET NULL,
                created_at INTEGER NOT NULL
            )
            """
        )
        db.execSQL(
            """
            CREATE TABLE lessons (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                notebook_id INTEGER NOT NULL REFERENCES notebooks(id) ON DELETE CASCADE,
                title TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """
        )
        db.execSQL(
            """
            CREATE TABLE entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                lesson_id INTEGER NOT NULL REFERENCES lessons(id) ON DELETE CASCADE,
                kind TEXT NOT NULL CHECK (kind IN ('user', 'assistant', 'note')),
                action TEXT,
                text TEXT NOT NULL,
                parent_entry_id INTEGER REFERENCES entries(id) ON DELETE CASCADE,
                source_entry_ids TEXT NOT NULL DEFAULT '[]',
                image_paths TEXT NOT NULL DEFAULT '[]',
                attached_image_entry_ids TEXT NOT NULL DEFAULT '[]',
                template_id INTEGER,
                state TEXT,
                archived INTEGER NOT NULL DEFAULT 0,
                mastery TEXT,
                created_at INTEGER NOT NULL
            )
            """
        )
        db.execSQL("CREATE INDEX idx_lessons_notebook ON lessons(notebook_id)")
        db.execSQL("CREATE INDEX idx_entries_lesson ON entries(lesson_id)")
        db.execSQL("CREATE INDEX idx_entries_parent ON entries(parent_entry_id)")
        db.execSQL(
            """
            CREATE TABLE review_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                notebook_id INTEGER NOT NULL REFERENCES notebooks(id) ON DELETE CASCADE,
                topic TEXT NOT NULL,
                notes TEXT NOT NULL,
                source_entry_id INTEGER,
                source_deleted INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL CHECK (status IN ('pending', 'understood', 'confused')),
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """
        )
        db.execSQL("CREATE INDEX idx_review_records_notebook ON review_records(notebook_id)")
        seedGuidedTemplate(db)
    }

    /** Preinstalled, editable and deletable like any user template. */
    private fun seedGuidedTemplate(db: SQLiteDatabase) {
        db.execSQL("INSERT INTO templates(name, instruction, created_at, source) VALUES (?, ?, ?, ?)",
            arrayOf<Any>(context.getString(com.feiyu.notes.R.string.guided_template_name),
                com.feiyu.notes.study.StudyPrompts.GUIDED, System.currentTimeMillis(), Template.BUILTIN_GUIDED))
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE entries ADD COLUMN image_paths TEXT NOT NULL DEFAULT '[]'")
            db.rawQuery("SELECT id, image_path FROM entries WHERE image_path IS NOT NULL", null).use { cursor ->
                while (cursor.moveToNext()) {
                    db.execSQL("UPDATE entries SET image_paths = ? WHERE id = ?",
                        arrayOf<Any>(Json.encodeToString(listOf(cursor.getString(1))), cursor.getLong(0)))
                }
            }
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE templates ADD COLUMN source TEXT")
            seedGuidedTemplate(db)
        }
        if (oldVersion < 4) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS review_records (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    notebook_id INTEGER NOT NULL REFERENCES notebooks(id) ON DELETE CASCADE,
                    topic TEXT NOT NULL,
                    notes TEXT NOT NULL,
                    source_entry_id INTEGER,
                    source_deleted INTEGER NOT NULL DEFAULT 0,
                    status TEXT NOT NULL CHECK (status IN ('pending', 'understood', 'confused')),
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_review_records_notebook ON review_records(notebook_id)")
        }
    }

    companion object {
        const val NAME = "notes.db"
        const val VERSION = 4
    }
}
