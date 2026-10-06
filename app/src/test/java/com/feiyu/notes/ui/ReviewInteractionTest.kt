package com.feiyu.notes.ui

import com.feiyu.notes.data.ReviewInsertResult
import com.feiyu.notes.data.ReviewRecord
import com.feiyu.notes.data.ReviewStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReviewInteractionTest {

    @Test
    fun assistantReplyDefaultsExtractsFirstLineAndTruncates() {
        val (topic, notes) = ReviewDefaults.fromAssistantReply(
            parentQuestionText = "什么是柯西-施瓦茨不等式？\n请给出详细证明",
            replyId = 42L,
            replyText = "柯西-施瓦茨不等式是线性代数中的重要不等式。\n证明如下：设...",
            fallbackTitle = "回复 #42",
        )
        assertEquals("什么是柯西-施瓦茨不等式？", topic)
        assertEquals("柯西-施瓦茨不等式是线性代数中的重要不等式。", notes)
    }

    @Test
    fun assistantReplyDefaultsFallsBackWhenQuestionBlank() {
        val (topic, notes) = ReviewDefaults.fromAssistantReply(
            parentQuestionText = "   \n\n",
            replyId = 42L,
            replyText = "回答第一行",
            fallbackTitle = "回复 #42",
        )
        assertEquals("回复 #42", topic)
        assertEquals("回答第一行", notes)
    }

    @Test
    fun noteDefaultsExtractsTitleAndNotesWithBounds() {
        val longLine = "A".repeat(150)
        val (topic, notes) = ReviewDefaults.fromNote(
            noteText = "$longLine\n第二行内容",
            fallbackTitle = "笔记 #1",
        )
        assertEquals(40, topic.length)
        assertEquals(120, notes.length)
    }

    @Test
    fun dialogStateBlocksEmptyTopicAndTrimsOnSubmit() = runTest {
        val state = ReviewDialogState("   ", "notes", sourceEntryId = null)
        assertFalse(state.canSave)

        var passedTopic: String? = null
        var passedNotes: String? = null
        state.topic = "  有效主题  "
        state.notes = "  有效重点备注  "
        assertTrue(state.canSave)

        val ok = state.submit { t, n ->
            passedTopic = t
            passedNotes = n
            null
        }

        assertTrue(ok)
        assertEquals("有效主题", passedTopic)
        assertEquals("有效重点备注", passedNotes)
        assertFalse(state.saving)
        assertNull(state.errorMessage)
    }

    @Test
    fun dialogStatePreservesDraftOnFailure() = runTest {
        val state = ReviewDialogState("我的知识点", "我的详细疑问草稿", sourceEntryId = null)

        val ok = state.submit { _, _ ->
            // Simulate source deletion failure during editing
            "来源内容已删除或不可用"
        }

        assertFalse(ok)
        assertFalse(state.saving)
        assertEquals("来源内容已删除或不可用", state.errorMessage)
        // Draft must be fully preserved
        assertEquals("我的知识点", state.topic)
        assertEquals("我的详细疑问草稿", state.notes)
    }

    @Test
    fun dialogStatePreservesDraftOnException() = runTest {
        val state = ReviewDialogState("我的知识点", "我的草稿", sourceEntryId = null)

        val ok = state.submit { _, _ ->
            throw IllegalStateException("Database locked")
        }

        assertFalse(ok)
        assertFalse(state.saving)
        assertEquals("Database locked", state.errorMessage)
        assertEquals("我的知识点", state.topic)
        assertEquals("我的草稿", state.notes)
    }

    @Test
    fun dialogStateRethrowsCancellationException() = runTest {
        val state = ReviewDialogState("知识点", "备注", sourceEntryId = null)

        try {
            state.submit { _, _ ->
                throw CancellationException("Operation cancelled")
            }
            fail("Expected CancellationException was not thrown")
        } catch (e: CancellationException) {
            assertEquals("Operation cancelled", e.message)
        }
        // Saving state must be cleanly reset even after cancellation
        assertFalse(state.saving)
        assertNull(state.errorMessage)
    }

    @Test
    fun reviewInsertResultDistinguishesAllFailureModes() {
        val dummyRecord = ReviewRecord(1, 10, "T", "N", 5)

        val success = ReviewInsertResult.Success(dummyRecord)
        val alreadyExists = ReviewInsertResult.AlreadyExists(dummyRecord)
        val sourceNotFound = ReviewInsertResult.SourceNotFound
        val invalidCourse = ReviewInsertResult.InvalidCourse
        val failed = ReviewInsertResult.Failed

        // Map results to user-facing feedback codes
        fun resolveFeedback(res: ReviewInsertResult): String = when (res) {
            is ReviewInsertResult.Success -> "added_to_review"
            is ReviewInsertResult.AlreadyExists -> "already_in_review"
            is ReviewInsertResult.SourceNotFound -> "source_not_found"
            is ReviewInsertResult.InvalidCourse -> "save_failed"
            is ReviewInsertResult.Failed -> "save_failed"
        }

        assertEquals("added_to_review", resolveFeedback(success))
        assertEquals("already_in_review", resolveFeedback(alreadyExists))
        assertEquals("source_not_found", resolveFeedback(sourceNotFound))
        assertEquals("save_failed", resolveFeedback(invalidCourse))
        assertEquals("save_failed", resolveFeedback(failed))
    }

    @Test
    fun saverPreservesDraftAndClearsSavingAcrossRestoration() {
        val original = ReviewDialogState("初始主题", "初始重点笔记", sourceEntryId = 42L).apply {
            errorMessage = "临时错误"
            saving = true
        }
        val saver = ReviewDialogState.Saver
        val scope = androidx.compose.runtime.saveable.SaverScope { true }
        val saved = with(saver) { scope.save(original) }
        val restored = saver.restore(saved!!)!!
        assertEquals("初始主题", restored.topic)
        assertEquals("初始重点笔记", restored.notes)
        assertEquals(42L, restored.sourceEntryId)
        assertEquals("临时错误", restored.errorMessage)
        assertFalse("saving must be reset to false upon restoration", restored.saving)
        assertTrue(restored.canSave)
    }

    @Test
    fun concurrentSubmitIsBlockedWhileSaving() = runTest {
        val state = ReviewDialogState("主题", "备注", null)
        var callCount = 0
        val completer = CompletableDeferred<String?>()
        val job = launch {
            state.submit { _, _ ->
                callCount++
                completer.await()
            }
        }
        runCurrent()
        assertTrue(state.saving)
        // Second concurrent submit while saving must be blocked immediately
        val secondResult = state.submit { _, _ ->
            callCount++
            null
        }
        assertFalse(secondResult)
        assertEquals(1, callCount)
        completer.complete(null)
        job.join()
        assertFalse(state.saving)
    }
}
