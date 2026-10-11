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
                created_at INTEGER NOT NULL,
                scratchpad TEXT NOT NULL DEFAULT ''
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
                created_at INTEGER NOT NULL,
                request_context TEXT NOT NULL DEFAULT '{}'
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
        createCourseKnowledge(db)
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
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE lessons ADD COLUMN scratchpad TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE entries ADD COLUMN request_context TEXT NOT NULL DEFAULT '{}'")
        }
        if (oldVersion < 6) createCourseKnowledge(db)
        if (oldVersion < 7) {
            // Early local v6 builds preceded the handbook switch. Preserve both local variants.
            val hasEnabled = db.rawQuery("PRAGMA table_info(course_documents)", null).use { cursor ->
                var found = false
                while (cursor.moveToNext()) if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "enabled") found = true
                found
            }
            if (!hasEnabled) db.execSQL("ALTER TABLE course_documents ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1 CHECK(enabled IN (0,1))")
        }
    }

    private fun createCourseKnowledge(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE course_documents (
            id INTEGER PRIMARY KEY AUTOINCREMENT, notebook_id INTEGER NOT NULL UNIQUE REFERENCES notebooks(id) ON DELETE CASCADE,
            markdown TEXT NOT NULL, revision INTEGER NOT NULL, enabled INTEGER NOT NULL DEFAULT 1 CHECK(enabled IN (0,1)), UNIQUE(id, notebook_id))""")
        db.execSQL("""CREATE TABLE course_sections (
            id INTEGER PRIMARY KEY AUTOINCREMENT, document_id INTEGER NOT NULL, notebook_id INTEGER NOT NULL,
            heading_path TEXT NOT NULL, markdown TEXT NOT NULL, ordinal INTEGER NOT NULL, UNIQUE(id, notebook_id),
            FOREIGN KEY(document_id, notebook_id) REFERENCES course_documents(id, notebook_id) ON DELETE CASCADE)""")
        db.execSQL("""CREATE TABLE course_bookmarks (
            id INTEGER PRIMARY KEY AUTOINCREMENT, notebook_id INTEGER NOT NULL REFERENCES notebooks(id) ON DELETE CASCADE,
            section_id INTEGER, heading_path TEXT NOT NULL,
            FOREIGN KEY(section_id, notebook_id) REFERENCES course_sections(id, notebook_id))""")
        db.execSQL("CREATE VIRTUAL TABLE course_sections_fts USING fts4(tokens, tokenize=unicode61)")
        db.execSQL("""CREATE TRIGGER course_section_deleted AFTER DELETE ON course_sections BEGIN
            DELETE FROM course_sections_fts WHERE docid = OLD.id;
            END""")
        // Null the section only, retaining the bookmark's course and title for visible invalidation.
        db.execSQL("""CREATE TRIGGER course_bookmark_invalidate BEFORE DELETE ON course_sections BEGIN
            UPDATE course_bookmarks SET section_id = NULL WHERE section_id = OLD.id AND notebook_id = OLD.notebook_id;
            END""")
        db.execSQL("""CREATE TRIGGER course_document_course_only BEFORE INSERT ON course_documents
            WHEN NEW.notebook_id = -2 OR NOT EXISTS(SELECT 1 FROM notebooks WHERE id = NEW.notebook_id AND kind = 'course')
            BEGIN SELECT RAISE(ABORT, 'course required'); END""")
    }

    companion object {
        const val NAME = "notes.db"
        const val VERSION = 7
    }
}
