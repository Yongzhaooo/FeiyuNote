package com.feiyu.notes.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * All persistence for notebooks, lessons, entries and templates (spec §5).
 * Every write is one transaction; a thrown exception means nothing was committed.
 * Validation failures return false/null without changing data.
 * [changes] increments after each successful write so screens can re-read.
 */
class NotebookStore(
    private val database: NotebookDatabase,
    private val photos: PhotoFiles,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes.asStateFlow()

    private val db: SQLiteDatabase get() = database.writableDatabase

    companion object { const val GENERAL_ID = -2L }

    /** Fixed local chat; negative IDs cannot collide with SQLite auto-generated IDs. */
    suspend fun generalChat(): Lesson = write {
        db.execSQL("INSERT OR IGNORE INTO notebooks(id, kind, name, created_at) VALUES (?, 'course', 'General chat', ?)", arrayOf(GENERAL_ID, clock()))
        db.execSQL("INSERT OR IGNORE INTO lessons(id, notebook_id, title, created_at) VALUES (?, ?, 'General chat', ?)", arrayOf(GENERAL_ID, GENERAL_ID, clock()))
        query("SELECT * FROM lessons WHERE id = ?", GENERAL_ID) { it.toLesson() }.single()
    }

    // ---- notebooks ----

    suspend fun listNotebooks(): List<Notebook> = read {
        query("SELECT * FROM notebooks WHERE id != -2 ORDER BY created_at, id") { it.toNotebook() }
    }

    suspend fun getNotebook(id: Long): Notebook? = read {
        query("SELECT * FROM notebooks WHERE id = ?", id) { it.toNotebook() }.firstOrNull()
    }

    suspend fun createNotebook(kind: NotebookKind, name: String, linkedCourseId: Long? = null): Notebook? = write {
        if (!validLink(kind, linkedCourseId, selfId = null)) return@write null
        val now = clock()
        val id = insert("notebooks", ContentValues().apply {
            put("kind", kind.db)
            put("name", name)
            put("linked_course_id", linkedCourseId)
            put("created_at", now)
        })
        Notebook(id, kind, name, linkedCourseId, null, now)
    }

    /** Updates name, course link and default template. Kind never changes. */
    suspend fun updateNotebook(notebook: Notebook): Boolean = write {
        val current = query("SELECT * FROM notebooks WHERE id = ?", notebook.id) { it.toNotebook() }.firstOrNull()
            ?: return@write false
        if (!validLink(current.kind, notebook.linkedCourseId, selfId = notebook.id)) return@write false
        if (notebook.defaultTemplateId != null && count("SELECT COUNT(*) FROM templates WHERE id = ?", notebook.defaultTemplateId) == 0L) {
            return@write false
        }
        update("notebooks", notebook.id, ContentValues().apply {
            put("name", notebook.name)
            put("linked_course_id", notebook.linkedCourseId)
            put("default_template_id", notebook.defaultTemplateId)
        }) == 1
    }

    /** Deletes the notebook with its lessons, entries, notes and image directory; unlinks practice books. */
    suspend fun deleteNotebook(id: Long): Boolean {
        if (id == GENERAL_ID) return false
        val deleted = write { db.delete("notebooks", "id = ?", args(id)) == 1 }
        if (deleted) withContext(io) { photos.deleteNotebookDir(id) }
        return deleted
    }

    // ---- lessons ----

    suspend fun listLessons(notebookId: Long): List<Lesson> = read {
        query("SELECT * FROM lessons WHERE notebook_id = ? ORDER BY created_at DESC, id DESC", notebookId) { it.toLesson() }
    }

    suspend fun getLesson(id: Long): Lesson? = read {
        query("SELECT * FROM lessons WHERE id = ?", id) { it.toLesson() }.firstOrNull()
    }

    suspend fun createLesson(notebookId: Long, title: String): Lesson? = write {
        if (count("SELECT COUNT(*) FROM notebooks WHERE id = ?", notebookId) == 0L) return@write null
        val now = clock()
        val id = insert("lessons", ContentValues().apply {
            put("notebook_id", notebookId)
            put("title", title)
            put("created_at", now)
        })
        Lesson(id, notebookId, title, now)
    }

    suspend fun renameLesson(id: Long, title: String): Boolean = write {
        update("lessons", id, ContentValues().apply { put("title", title) }) == 1
    }

    suspend fun deleteLesson(id: Long): Boolean {
        if (id == GENERAL_ID) return false
        val notebookId = getLesson(id)?.notebookId ?: return false
        var images = emptyList<String>()
        val deleted = write {
            val now = clock()
            db.execSQL(
                """
                UPDATE review_records SET source_deleted = 1, updated_at = ?
                WHERE source_entry_id IN (SELECT id FROM entries WHERE lesson_id = ?)
                """,
                arrayOf<Any>(now, id)
            )
            images = query("SELECT image_paths FROM entries WHERE lesson_id = ?", id) { Json.decodeFromString<List<String>>(it.getString(0)) }.flatten()
            db.delete("lessons", "id = ?", args(id)) == 1
        }
        if (deleted) withContext(io) { photos.deletePhotos(notebookId, images) }
        return deleted
    }

    // ---- entries ----

    /** Entries of a lesson in creation order; archived threads (root and descendants) excluded unless asked. */
    suspend fun readEntries(lessonId: Long, includeArchived: Boolean = false): List<Entry> = read {
        val all = query("SELECT * FROM entries WHERE lesson_id = ? ORDER BY created_at, id", lessonId) { it.toEntry() }
        if (includeArchived) all else {
            val byId = all.associateBy { it.id }
            all.filterNot { rootOf(it, byId)?.archived == true }
        }
    }

    suspend fun getEntries(ids: Collection<Long>): List<Entry> = read {
        if (ids.isEmpty()) emptyList()
        else query("SELECT * FROM entries WHERE id IN (${ids.joinToString(",") { "?" }})", *ids.toTypedArray()) { it.toEntry() }
    }

    suspend fun getEntry(id: Long): Entry? = getEntries(listOf(id)).firstOrNull()

    /**
     * Saves a question and its pending reply in one transaction (spec §4 Q&A path).
     * Returns (userEntry, pendingAssistant), or null if the lesson or parent no longer exists.
     */
    suspend fun insertQuestion(question: Entry): Pair<Entry, Entry>? = write {
        require(question.kind == EntryKind.USER && question.action != null)
        if (count("SELECT COUNT(*) FROM lessons WHERE id = ?", question.lessonId) == 0L) return@write null
        if (question.parentEntryId != null &&
            count("SELECT COUNT(*) FROM entries WHERE id = ? AND lesson_id = ?", question.parentEntryId, question.lessonId) == 0L
        ) return@write null
        val now = clock()
        val isPracticeRoot = question.parentEntryId == null &&
            count("SELECT COUNT(*) FROM lessons l JOIN notebooks n ON n.id = l.notebook_id WHERE l.id = ? AND n.kind = 'practice'", question.lessonId) == 1L
        val user = question.copy(
            state = null,
            archived = false,
            mastery = if (isPracticeRoot) Mastery.UNMASTERED else null,
            createdAt = now,
        )
        val userId = insert("entries", user.toValues())
        val reply = pendingReplyValues(user.lessonId, userId, now)
        val replyId = insert("entries", reply)
        user.copy(id = userId) to Entry(replyId, user.lessonId, EntryKind.ASSISTANT, text = "", parentEntryId = userId, state = EntryState.PENDING, createdAt = now)
    }

    /** Lets a retry replace a deleted template explicitly (spec §5); never swapped silently. */
    suspend fun setEntryTemplate(userEntryId: Long, templateId: Long?): Boolean = write {
        if (templateId != null && count("SELECT COUNT(*) FROM templates WHERE id = ?", templateId) == 0L) return@write false
        db.update("entries", ContentValues().apply { put("template_id", templateId) }, "id = ? AND kind = 'user'", args(userEntryId)) == 1
    }

    /** Retry: a new pending reply under an existing user entry; the original question is kept. */
    suspend fun insertPendingReply(userEntryId: Long): Entry? = write {
        val user = query("SELECT * FROM entries WHERE id = ? AND kind = 'user'", userEntryId) { it.toEntry() }.firstOrNull()
            ?: return@write null
        val now = clock()
        val id = insert("entries", pendingReplyValues(user.lessonId, user.id, now))
        Entry(id, user.lessonId, EntryKind.ASSISTANT, text = "", parentEntryId = user.id, state = EntryState.PENDING, createdAt = now)
    }

    /**
     * Writes a finished reply only if the target is still an existing pending entry.
     * Returns false when the target was deleted or already settled; late results are dropped.
     */
    suspend fun commitReply(assistantId: Long, text: String, state: EntryState): Boolean = write {
        require(state != EntryState.PENDING)
        db.update(
            "entries",
            ContentValues().apply { put("text", text); put("state", state.db) },
            "id = ? AND kind = 'assistant' AND state = 'pending'",
            args(assistantId),
        ) == 1
    }

    /** Creates a note only if the lesson still exists (spec §4 summary path). */
    suspend fun commitSummary(lessonId: Long, text: String, sourceIds: List<Long>, templateId: Long?): Entry? = write {
        if (count("SELECT COUNT(*) FROM lessons WHERE id = ?", lessonId) == 0L) return@write null
        val note = Entry(0, lessonId, EntryKind.NOTE, text = text, sourceEntryIds = sourceIds, templateId = templateId, createdAt = clock())
        note.copy(id = insert("entries", note.toValues()))
    }

    suspend fun updateNoteText(noteId: Long, text: String): Boolean = write {
        db.update("entries", ContentValues().apply { put("text", text) }, "id = ? AND kind = 'note'", args(noteId)) == 1
    }

    suspend fun deleteNote(noteId: Long): Boolean = write {
        val now = clock()
        db.execSQL(
            "UPDATE review_records SET source_deleted = 1, updated_at = ? WHERE source_entry_id = ? AND source_deleted = 0",
            arrayOf<Any>(now, noteId)
        )
        db.delete("entries", "id = ? AND kind = 'note'", args(noteId)) == 1
    }

    /** Notes usable as reference: this notebook's, plus the linked course's for a practice book. */
    suspend fun readReferenceNotes(notebookId: Long): List<Entry> = read {
        query(
            """
            SELECT e.* FROM entries e JOIN lessons l ON l.id = e.lesson_id
            WHERE e.kind = 'note' AND (
                l.notebook_id = ? OR l.notebook_id = (
                    SELECT linked_course_id FROM notebooks WHERE id = ? AND kind = 'practice'
                )
            )
            ORDER BY e.created_at DESC, e.id DESC
            """,
            notebookId, notebookId,
        ) { it.toEntry() }
    }

    // ---- threads ----

    /** Only root user entries can be archived. */
    suspend fun setArchived(rootId: Long, archived: Boolean): Boolean = write {
        db.update(
            "entries",
            ContentValues().apply { put("archived", if (archived) 1 else 0) },
            "id = ? AND kind = 'user' AND parent_entry_id IS NULL",
            args(rootId),
        ) == 1
    }

    /** Only root user entries of practice books carry mastery. */
    suspend fun setMastery(rootId: Long, mastery: Mastery): Boolean = write {
        db.update(
            "entries",
            ContentValues().apply { put("mastery", mastery.db) },
            """
            id = ? AND kind = 'user' AND parent_entry_id IS NULL AND lesson_id IN (
                SELECT l.id FROM lessons l JOIN notebooks n ON n.id = l.notebook_id WHERE n.kind = 'practice'
            )
            """,
            args(rootId),
        ) == 1
    }

    /** Deletes a root question with all descendants (cascade) and their photos. */
    suspend fun deleteThread(rootId: Long): Boolean {
        var notebookId = 0L
        var images = emptyList<String>()
        val deleted = write {
            val root = query("SELECT * FROM entries WHERE id = ? AND kind = 'user' AND parent_entry_id IS NULL", rootId) { it.toEntry() }
                .firstOrNull() ?: return@write false
            notebookId = query("SELECT notebook_id FROM lessons WHERE id = ?", root.lessonId) { it.getLong(0) }.first()
            images = query(
                """
                WITH RECURSIVE t(id) AS (SELECT ? UNION ALL SELECT e.id FROM entries e JOIN t ON e.parent_entry_id = t.id)
                SELECT image_paths FROM entries WHERE id IN (SELECT id FROM t)
                """,
                rootId,
            ) { Json.decodeFromString<List<String>>(it.getString(0)) }.flatten()
            val now = clock()
            db.execSQL(
                """
                WITH RECURSIVE t(id) AS (SELECT ? UNION ALL SELECT e.id FROM entries e JOIN t ON e.parent_entry_id = t.id)
                UPDATE review_records SET source_deleted = 1, updated_at = ?
                WHERE source_entry_id IN (SELECT id FROM t)
                """,
                arrayOf<Any>(rootId, now)
            )
            db.delete("entries", "id = ?", args(rootId)) == 1
        }
        if (deleted) withContext(io) { photos.deletePhotos(notebookId, images) }
        return deleted
    }

    /** Called once at process start: no request survives the process. */
    suspend fun markPendingInterrupted(): Int = write {
        db.update("entries", ContentValues().apply { put("state", EntryState.INTERRUPTED.db) }, "state = 'pending'", null)
    }

    // ---- templates ----

    suspend fun listTemplates(): List<Template> = read {
        query("SELECT * FROM templates ORDER BY created_at, id") { it.toTemplate() }
    }

    suspend fun getTemplate(id: Long): Template? = read {
        query("SELECT * FROM templates WHERE id = ?", id) { it.toTemplate() }.firstOrNull()
    }

    /** Inserts when id == 0, otherwise updates. */
    suspend fun saveTemplate(template: Template): Template? = write {
        val values = ContentValues().apply {
            put("name", template.name)
            put("instruction", template.instruction)
        }
        if (template.id == 0L) {
            val now = clock()
            values.put("created_at", now)
            values.put("source", template.source) // origin is fixed at creation; edits keep it
            template.copy(id = insert("templates", values), createdAt = now)
        } else {
            if (update("templates", template.id, values) == 1) template.copy(source = templateSource(template.id)) else null
        }
    }

    private fun SQLiteDatabase.templateSource(id: Long): String? =
        query("SELECT * FROM templates WHERE id = ?", id) { it.toTemplate() }.firstOrNull()?.source

    /** Notebooks using it as default fall back to none (FK ON DELETE SET NULL). */
    suspend fun deleteTemplate(id: Long): Boolean = write {
        db.delete("templates", "id = ?", args(id)) == 1
    }

    // ---- review records (spec §12 / course review) ----

    // ---- review records ----

    suspend fun listReviewRecords(notebookId: Long): List<ReviewRecord> = read {
        if (!isCourseNotebook(notebookId)) return@read emptyList()
        query("SELECT * FROM review_records WHERE notebook_id = ? ORDER BY created_at DESC, id DESC", notebookId) {
            it.toReviewRecord()
        }
    }

    suspend fun getReviewRecord(notebookId: Long, recordId: Long): ReviewRecord? = read {
        if (!isCourseNotebook(notebookId)) return@read null
        query("SELECT * FROM review_records WHERE id = ? AND notebook_id = ?", recordId, notebookId) {
            it.toReviewRecord()
        }.firstOrNull()
    }

    suspend fun findReviewRecordBySource(notebookId: Long, sourceEntryId: Long): ReviewRecord? = read {
        if (!isCourseNotebook(notebookId)) return@read null
        query(
            "SELECT * FROM review_records WHERE notebook_id = ? AND source_entry_id = ? AND source_deleted = 0 LIMIT 1",
            notebookId, sourceEntryId
        ) { it.toReviewRecord() }.firstOrNull()
    }

    suspend fun insertReviewRecord(
        notebookId: Long,
        topic: String,
        notes: String,
        sourceEntryId: Long? = null,
        status: ReviewStatus = ReviewStatus.PENDING,
    ): ReviewInsertResult = write {
        if (!isCourseNotebook(notebookId)) return@write ReviewInsertResult.InvalidCourse
        if (topic.isBlank()) return@write ReviewInsertResult.Failed

        if (sourceEntryId != null) {
            val sourceEntry = query(
                """
                SELECT e.kind, e.state, l.notebook_id FROM entries e JOIN lessons l ON e.lesson_id = l.id
                WHERE e.id = ?
                """,
                sourceEntryId
            ) { Triple(it.getString(0), it.getString(1), it.getLong(2)) }.firstOrNull()
            if (sourceEntry == null || sourceEntry.third != notebookId) {
                return@write ReviewInsertResult.SourceNotFound
            }
            val (kindStr, stateStr, _) = sourceEntry
            val isValidSource = when (kindStr?.lowercase()) {
                EntryKind.NOTE.db, EntryKind.USER.db -> true
                EntryKind.ASSISTANT.db -> stateStr?.lowercase() == EntryState.COMPLETE.db
                else -> false
            }
            if (!isValidSource) {
                return@write ReviewInsertResult.SourceNotFound
            }

            val existing = query(
                "SELECT * FROM review_records WHERE notebook_id = ? AND source_entry_id = ? AND source_deleted = 0",
                notebookId, sourceEntryId
            ) { it.toReviewRecord() }.firstOrNull()
            if (existing != null) {
                return@write ReviewInsertResult.AlreadyExists(existing)
            }
        }

        val now = clock()
        val values = ContentValues().apply {
            put("notebook_id", notebookId)
            put("topic", topic.trim())
            put("notes", notes.trim())
            put("source_entry_id", sourceEntryId)
            put("source_deleted", 0)
            put("status", status.db)
            put("created_at", now)
            put("updated_at", now)
        }
        val id = db.insert("review_records", null, values)
        if (id == -1L) return@write ReviewInsertResult.Failed

        val record = query("SELECT * FROM review_records WHERE id = ? AND notebook_id = ?", id, notebookId) {
            it.toReviewRecord()
        }.firstOrNull() ?: return@write ReviewInsertResult.Failed

        ReviewInsertResult.Success(record)
    }

    suspend fun addReviewRecord(
        notebookId: Long,
        topic: String,
        notes: String,
        sourceEntryId: Long? = null,
        status: ReviewStatus = ReviewStatus.PENDING,
    ): ReviewRecord? = when (val res = insertReviewRecord(notebookId, topic, notes, sourceEntryId, status)) {
        is ReviewInsertResult.Success -> res.record
        is ReviewInsertResult.AlreadyExists -> res.existingRecord
        else -> null
    }

    suspend fun updateReviewRecord(
        notebookId: Long,
        recordId: Long,
        topic: String,
        notes: String,
        status: ReviewStatus? = null,
    ): Boolean = write {
        if (!isCourseNotebook(notebookId)) return@write false
        if (topic.isBlank()) return@write false
        val now = clock()
        val values = ContentValues().apply {
            put("topic", topic.trim())
            put("notes", notes.trim())
            if (status != null) put("status", status.db)
            put("updated_at", now)
        }
        db.update("review_records", values, "id = ? AND notebook_id = ?", args(recordId, notebookId)) == 1
    }

    suspend fun setReviewStatus(
        notebookId: Long,
        recordId: Long,
        status: ReviewStatus,
    ): Boolean = write {
        if (!isCourseNotebook(notebookId)) return@write false
        val now = clock()
        val values = ContentValues().apply {
            put("status", status.db)
            put("updated_at", now)
        }
        db.update("review_records", values, "id = ? AND notebook_id = ?", args(recordId, notebookId)) == 1
    }

    suspend fun deleteReviewRecord(notebookId: Long, recordId: Long): Boolean = write {
        if (!isCourseNotebook(notebookId)) return@write false
        db.delete("review_records", "id = ? AND notebook_id = ?", args(recordId, notebookId)) == 1
    }

    suspend fun isThreadArchived(entryId: Long): Boolean = read {
        query(
            """
            WITH RECURSIVE t(id, parent_entry_id, archived) AS (
                SELECT id, parent_entry_id, archived FROM entries WHERE id = ?
                UNION ALL
                SELECT e.id, e.parent_entry_id, e.archived FROM entries e JOIN t ON e.id = t.parent_entry_id
            )
            SELECT archived FROM t WHERE parent_entry_id IS NULL LIMIT 1
            """,
            entryId
        ) { it.getLong(0) == 1L }.firstOrNull() ?: false
    }

    // ---- helpers ----

    private fun SQLiteDatabase.isCourseNotebook(notebookId: Long): Boolean =
        notebookId != GENERAL_ID && count("SELECT COUNT(*) FROM notebooks WHERE id = ? AND kind = 'course'", notebookId) == 1L

    private fun SQLiteDatabase.validLink(kind: NotebookKind, linkedCourseId: Long?, selfId: Long?): Boolean {
        if (linkedCourseId == null) return true
        if (kind != NotebookKind.PRACTICE || linkedCourseId == selfId) return false
        return count("SELECT COUNT(*) FROM notebooks WHERE id = ? AND kind = 'course'", linkedCourseId) == 1L
    }

    private fun rootOf(entry: Entry, byId: Map<Long, Entry>): Entry? {
        var current: Entry? = entry
        while (current?.parentEntryId != null) current = byId[current.parentEntryId]
        return current?.takeIf { it.isRoot }
    }

    private fun pendingReplyValues(lessonId: Long, parentId: Long, now: Long) = ContentValues().apply {
        put("lesson_id", lessonId)
        put("kind", EntryKind.ASSISTANT.db)
        put("text", "")
        put("parent_entry_id", parentId)
        put("state", EntryState.PENDING.db)
        put("created_at", now)
    }

    private suspend fun <T> read(block: SQLiteDatabase.() -> T): T = withContext(io) { db.block() }

    private suspend fun <T> write(block: SQLiteDatabase.() -> T): T = withContext(io) {
        val database = db
        database.beginTransaction()
        val result = try {
            database.block().also { database.setTransactionSuccessful() }
        } finally {
            database.endTransaction()
        }
        _changes.value++
        result
    }

    private fun args(vararg values: Any?): Array<String> = values.map { it.toString() }.toTypedArray()

    private fun <T> SQLiteDatabase.query(sql: String, vararg params: Any?, map: (Cursor) -> T): List<T> =
        rawQuery(sql, args(*params)).use { c -> buildList { while (c.moveToNext()) add(map(c)) } }

    private fun SQLiteDatabase.count(sql: String, vararg params: Any?): Long =
        rawQuery(sql, args(*params)).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    private fun SQLiteDatabase.insert(table: String, values: ContentValues): Long = insertOrThrow(table, null, values)

    private fun SQLiteDatabase.update(table: String, id: Long, values: ContentValues): Int =
        update(table, values, "id = ?", args(id))

    private fun Entry.toValues() = ContentValues().apply {
        put("lesson_id", lessonId)
        put("kind", kind.db)
        put("action", action?.db)
        put("text", text)
        put("parent_entry_id", parentEntryId)
        put("source_entry_ids", Json.encodeToString(sourceEntryIds))
        put("image_paths", Json.encodeToString(imagePaths))
        put("attached_image_entry_ids", Json.encodeToString(attachedImageEntryIds))
        put("template_id", templateId)
        put("state", state?.db)
        put("archived", if (archived) 1 else 0)
        put("mastery", mastery?.db)
        put("created_at", createdAt)
    }

    private fun Cursor.str(col: String): String? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.long(col: String): Long? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.ids(col: String): List<Long> = Json.decodeFromString(str(col) ?: "[]")

    private fun Cursor.toNotebook() = Notebook(
        id = long("id")!!,
        kind = enumOf<NotebookKind>(str("kind"))!!,
        name = str("name")!!,
        linkedCourseId = long("linked_course_id"),
        defaultTemplateId = long("default_template_id"),
        createdAt = long("created_at")!!,
    )

    private fun Cursor.toLesson() = Lesson(long("id")!!, long("notebook_id")!!, str("title")!!, long("created_at")!!)

    private fun Cursor.toTemplate() = Template(long("id")!!, str("name")!!, str("instruction")!!, long("created_at")!!, str("source"))

    private fun Cursor.toEntry() = Entry(
        id = long("id")!!,
        lessonId = long("lesson_id")!!,
        kind = enumOf<EntryKind>(str("kind"))!!,
        action = enumOf<EntryAction>(str("action")),
        text = str("text")!!,
        parentEntryId = long("parent_entry_id"),
        sourceEntryIds = ids("source_entry_ids"),
        imagePaths = Json.decodeFromString(str("image_paths") ?: "[]"),
        attachedImageEntryIds = ids("attached_image_entry_ids"),
        templateId = long("template_id"),
        state = enumOf<EntryState>(str("state")),
        archived = long("archived") == 1L,
        mastery = enumOf<Mastery>(str("mastery")),
        createdAt = long("created_at")!!,
    )

    private fun Cursor.toReviewRecord() = ReviewRecord(
        id = long("id")!!,
        notebookId = long("notebook_id")!!,
        topic = str("topic")!!,
        notes = str("notes")!!,
        sourceEntryId = long("source_entry_id"),
        sourceDeleted = long("source_deleted") == 1L,
        status = ReviewStatus.fromDb(str("status") ?: "pending"),
        createdAt = long("created_at")!!,
        updatedAt = long("updated_at")!!,
    )
}

private val Enum<*>.db: String get() = name.lowercase()

private inline fun <reified E : Enum<E>> enumOf(value: String?): E? =
    value?.let { v -> enumValues<E>().first { it.name.equals(v, ignoreCase = true) } }
