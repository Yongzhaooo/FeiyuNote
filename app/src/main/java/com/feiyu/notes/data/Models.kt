package com.feiyu.notes.data

/** App-owned data types mirroring spec §5. Stored as lowercase strings in SQLite. */

enum class NotebookKind { COURSE, PRACTICE }

data class Notebook(
    val id: Long,
    val kind: NotebookKind,
    val name: String,
    /** Practice books only: the optional linked course. */
    val linkedCourseId: Long?,
    val defaultTemplateId: Long?,
    val createdAt: Long,
)

data class Lesson(
    val id: Long,
    val notebookId: Long,
    val title: String,
    val createdAt: Long,
)

enum class EntryKind { USER, ASSISTANT, NOTE }

/** User entries only; summaries never create a user entry. */
enum class EntryAction { ASK, EXPAND, MISTAKE }

/** Assistant entries only. */
enum class EntryState { PENDING, COMPLETE, FAILED, CANCELLED, INTERRUPTED }

/** Root user entries in practice books only. */
enum class Mastery { UNMASTERED, MASTERED }

data class Entry(
    val id: Long,
    val lessonId: Long,
    val kind: EntryKind,
    val action: EntryAction? = null,
    val text: String,
    val parentEntryId: Long? = null,
    /** note: summarized entries; user: the selected reference note. */
    val sourceEntryIds: List<Long> = emptyList(),
    /** Ordered file names inside the notebook's image directory. */
    val imagePaths: List<String> = emptyList(),
    /** User entries whose photos were attached to this follow-up. */
    val attachedImageEntryIds: List<Long> = emptyList(),
    /** Template actually used; may dangle after the template is deleted. */
    val templateId: Long? = null,
    val state: EntryState? = null,
    val archived: Boolean = false,
    val mastery: Mastery? = null,
    val createdAt: Long = 0,
) {
    val isRoot: Boolean get() = kind == EntryKind.USER && parentEntryId == null
}

data class Template(
    val id: Long,
    val name: String,
    val instruction: String,
    val createdAt: Long,
    /** null = written by the user; [BUILTIN_GUIDED] = preinstalled; otherwise the Skill's source URL. */
    val source: String? = null,
) {
    val isSkill: Boolean get() = source != null && source != BUILTIN_GUIDED

    companion object { const val BUILTIN_GUIDED = "builtin:guided" }
}

enum class ReviewStatus(val db: String) {
    PENDING("pending"),
    UNDERSTOOD("understood"),
    CONFUSED("confused");

    companion object {
        fun fromDb(value: String): ReviewStatus =
            entries.firstOrNull { it.db == value } ?: PENDING
    }
}

data class ReviewRecord(
    val id: Long,
    val notebookId: Long,
    val topic: String,
    val notes: String,
    val sourceEntryId: Long? = null,
    val sourceDeleted: Boolean = false,
    val status: ReviewStatus = ReviewStatus.PENDING,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

sealed interface ReviewInsertResult {
    data class Success(val record: ReviewRecord) : ReviewInsertResult
    data class AlreadyExists(val existingRecord: ReviewRecord) : ReviewInsertResult
    data object SourceNotFound : ReviewInsertResult
    data object InvalidCourse : ReviewInsertResult
    data object Failed : ReviewInsertResult
}
