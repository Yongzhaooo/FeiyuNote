package com.feiyu.notes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewRecordModelTest {

    @Test
    fun reviewStatusMapsToExpectedDatabaseStrings() {
        assertEquals("pending", ReviewStatus.PENDING.db)
        assertEquals("understood", ReviewStatus.UNDERSTOOD.db)
        assertEquals("confused", ReviewStatus.CONFUSED.db)
    }

    @Test
    fun reviewStatusParsesFromDatabaseStrings() {
        assertEquals(ReviewStatus.PENDING, ReviewStatus.fromDb("pending"))
        assertEquals(ReviewStatus.UNDERSTOOD, ReviewStatus.fromDb("understood"))
        assertEquals(ReviewStatus.CONFUSED, ReviewStatus.fromDb("confused"))
    }

    @Test
    fun reviewStatusFallsBackToPendingForUnknownValues() {
        assertEquals(ReviewStatus.PENDING, ReviewStatus.fromDb("unknown"))
        assertEquals(ReviewStatus.PENDING, ReviewStatus.fromDb(""))
        assertEquals(ReviewStatus.PENDING, ReviewStatus.fromDb("MASTERED"))
    }

    @Test
    fun reviewRecordDefaultsMatchSpecification() {
        val record = ReviewRecord(
            id = 1L,
            notebookId = 10L,
            topic = "极限定义",
            notes = "ε-δ 语言需要多练例题",
        )

        assertEquals(1L, record.id)
        assertEquals(10L, record.notebookId)
        assertEquals("极限定义", record.topic)
        assertEquals("ε-δ 语言需要多练例题", record.notes)
        assertNull(record.sourceEntryId)
        assertFalse(record.sourceDeleted)
        assertEquals(ReviewStatus.PENDING, record.status)
        assertEquals(0L, record.createdAt)
        assertEquals(0L, record.updatedAt)
    }

    @Test
    fun reviewRecordCopySupportsStatusAndSourceLifecycle() {
        val original = ReviewRecord(
            id = 2L,
            notebookId = 20L,
            topic = "泰勒展开",
            notes = "佩亚诺余项",
            sourceEntryId = 100L,
            sourceDeleted = false,
            status = ReviewStatus.PENDING,
            createdAt = 1000L,
            updatedAt = 1000L,
        )

        val updated = original.copy(
            status = ReviewStatus.UNDERSTOOD,
            notes = "已通过课后习题验证",
            updatedAt = 2000L,
        )
        assertEquals(ReviewStatus.UNDERSTOOD, updated.status)
        assertEquals("已通过课后习题验证", updated.notes)
        assertEquals(2000L, updated.updatedAt)
        assertFalse(updated.sourceDeleted)

        val invalidatedSource = updated.copy(sourceDeleted = true)
        assertTrue(invalidatedSource.sourceDeleted)
        assertEquals(100L, invalidatedSource.sourceEntryId)
    }

    @Test
    fun reviewInsertResultCarriesExpectedData() {
        val record = ReviewRecord(1L, 10L, "Topic", "Notes")
        val success = ReviewInsertResult.Success(record)
        assertEquals(record, success.record)

        val existing = ReviewInsertResult.AlreadyExists(record)
        assertEquals(record, existing.existingRecord)

        assertTrue(ReviewInsertResult.SourceNotFound is ReviewInsertResult)
        assertTrue(ReviewInsertResult.InvalidCourse is ReviewInsertResult)
        assertTrue(ReviewInsertResult.Failed is ReviewInsertResult)
    }
}
