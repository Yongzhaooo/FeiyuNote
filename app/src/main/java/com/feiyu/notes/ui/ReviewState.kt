package com.feiyu.notes.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlin.coroutines.cancellation.CancellationException

sealed interface ReviewSourceDestination {
    data class Note(val lessonId: Long, val noteId: Long) : ReviewSourceDestination
    data class Archived(val lessonId: Long) : ReviewSourceDestination
    data class Chat(val lessonId: Long, val entryId: Long) : ReviewSourceDestination
}

object ReviewDefaults {
    fun fromAssistantReply(
        parentQuestionText: String?,
        replyId: Long,
        replyText: String,
        fallbackTitle: String,
    ): Pair<String, String> {
        val topic = parentQuestionText?.lineSequence()?.firstOrNull()?.take(40)?.ifBlank {
            fallbackTitle
        } ?: fallbackTitle
        val notes = replyText.lineSequence().firstOrNull().orEmpty().take(80)
        return topic to notes
    }

    fun fromNote(
        noteText: String,
        fallbackTitle: String,
    ): Pair<String, String> {
        val topic = noteText.lineSequence().firstOrNull()?.take(40)?.ifBlank {
            fallbackTitle
        } ?: fallbackTitle
        val notes = noteText.take(120)
        return topic to notes
    }
}

class ReviewDialogState(
    initialTopic: String,
    initialNotes: String,
    val sourceEntryId: Long?,
) {
    var topic by mutableStateOf(initialTopic)
    var notes by mutableStateOf(initialNotes)
    var saving by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)

    val canSave: Boolean get() = !saving && topic.isNotBlank()

    suspend fun submit(
        fallbackErrorMessage: String? = null,
        onSave: suspend (topic: String, notes: String) -> String?,
    ): Boolean {
        if (!canSave) return false
        saving = true
        errorMessage = null
        return try {
            val err = onSave(topic.trim(), notes.trim())
            if (err != null) {
                errorMessage = err
                false
            } else {
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = fallbackErrorMessage ?: e.message ?: "Save failed"
            false
        } finally {
            saving = false
        }
    }

    companion object {
        val Saver: Saver<ReviewDialogState, Any> = listSaver(
            save = { listOf(it.topic, it.notes, it.sourceEntryId, it.errorMessage) },
            restore = { list ->
                ReviewDialogState(
                    initialTopic = list[0] as String,
                    initialNotes = list[1] as String,
                    sourceEntryId = list[2] as? Long,
                ).apply {
                    errorMessage = list[3] as? String
                }
            }
        )
    }
}

@Composable
fun rememberReviewDialogState(
    initialTopic: String,
    initialNotes: String,
    sourceEntryId: Long?,
): ReviewDialogState = rememberSaveable(
    initialTopic, initialNotes, sourceEntryId,
    saver = ReviewDialogState.Saver
) {
    ReviewDialogState(initialTopic, initialNotes, sourceEntryId)
}
