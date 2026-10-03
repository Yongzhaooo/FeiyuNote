package com.feiyu.notes.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** P2 checks on a real device/emulator SQLite, using synthetic data in a throwaway DB. */
@RunWith(AndroidJUnit4::class)
class NotebookStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "store-test.db"
    private lateinit var filesDir: File
    private lateinit var database: NotebookDatabase
    private lateinit var photos: PhotoFiles
    private lateinit var store: NotebookStore

    @Before fun setUp() {
        context.deleteDatabase(dbName)
        filesDir = File(context.cacheDir, "store-test").apply { deleteRecursively(); mkdirs() }
        open()
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase(dbName)
        filesDir.deleteRecursively()
    }

    private fun open() {
        database = NotebookDatabase(context, dbName)
        photos = PhotoFiles(filesDir)
        store = NotebookStore(database, photos)
    }

    private fun reopen() { database.close(); open() }

    private fun photo(notebookId: Long): String =
        photos.allocatePhoto(notebookId).apply { writeText("jpeg") }.name

    private suspend fun ask(lessonId: Long, text: String, parent: Long? = null, image: String? = null, ref: Long? = null) =
        store.insertQuestion(
            Entry(0, lessonId, EntryKind.USER, EntryAction.ASK, text, parentEntryId = parent, imagePaths = listOfNotNull(image),
                sourceEntryIds = listOfNotNull(ref))
        )!!

    @Test fun upgradesV1PhotosAndKeepsTextAndRelations() = runBlocking {
        database.close()
        val legacy = context.openOrCreateDatabase(dbName, 0, null)
        val schema = InstrumentationRegistry.getInstrumentation().context.assets.open("notes-v1.sql").bufferedReader().use { it.readText() }
        schema.split(';').filter { it.isNotBlank() }.forEach { legacy.execSQL(it) }
        legacy.execSQL("INSERT INTO notebooks (id,kind,name,created_at) VALUES (1,'course','existing',1)")
        legacy.execSQL("INSERT INTO lessons (id,notebook_id,title,created_at) VALUES (2,1,'lesson',1)")
        val oldImage = photo(1)
        legacy.execSQL("INSERT INTO entries (id,lesson_id,kind,action,text,image_path,created_at) VALUES (3,2,'user','ask','old question',?,1)", arrayOf(oldImage))
        legacy.execSQL("INSERT INTO entries (id,lesson_id,kind,text,parent_entry_id,state,created_at) VALUES (4,2,'assistant','old answer',3,'complete',2)")
        legacy.version = 1
        legacy.close()
        open()
        val migrated = store.readEntries(2)
        assertEquals(listOf(oldImage), migrated.first().imagePaths)
        assertEquals("old question", migrated.first().text)
        assertEquals(3L, migrated.last().parentEntryId)
        assertEquals("old answer", migrated.last().text)
        assertTrue(migrated.last().imagePaths.isEmpty())
        assertTrue(photos.isUsable(1, oldImage))
        assertEquals(NotebookDatabase.VERSION, database.readableDatabase.version)
        assertEquals(listOf(com.feiyu.notes.data.Template.BUILTIN_GUIDED), store.listTemplates().map { it.source })
    }

    @Test fun multiplePhotosSurviveReopenAndAreDeletedWithTheirOwner() = runBlocking {
        val n = store.createNotebook(NotebookKind.COURSE, "multi")!!
        val l = store.createLesson(n.id, "lesson")!!
        val images = List(3) { photo(n.id) }
        val (q, _) = store.insertQuestion(Entry(0, l.id, EntryKind.USER, EntryAction.ASK, "", imagePaths = images))!!
        reopen()
        assertEquals(images, store.getEntry(q.id)!!.imagePaths)
        assertTrue(store.deleteThread(q.id))
        assertTrue(images.none { photos.isUsable(n.id, it) })
        val more = List(2) { photo(n.id) }
        store.insertQuestion(Entry(0, l.id, EntryKind.USER, EntryAction.ASK, "", imagePaths = more))!!
        assertTrue(store.deleteLesson(l.id))
        assertTrue(more.none { photos.isUsable(n.id, it) })
    }

    @Test fun notebooksAreIsolatedAndRenameKeepsPhotos() = runBlocking {
        val a = store.createNotebook(NotebookKind.COURSE, "数学")!!
        val b = store.createNotebook(NotebookKind.COURSE, "物理")!!
        val la = store.createLesson(a.id, "2026-09-30")!!
        val lb = store.createLesson(b.id, "2026-09-30")!!
        val img = photo(a.id)
        val (q, _) = ask(la.id, "题目", image = img)
        ask(lb.id, "另一门")

        assertTrue(store.updateNotebook(a.copy(name = "高等数学")))
        reopen()

        assertEquals(listOf(la), store.listLessons(a.id))
        val entries = store.readEntries(la.id)
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.lessonId == la.id })
        assertEquals(listOf(img), entries.first { it.id == q.id }.imagePaths)
        assertTrue(photos.isUsable(a.id, img))
        assertEquals("高等数学", store.getNotebook(a.id)!!.name)
    }

    @Test fun parentAndSourceIdsRoundTrip() = runBlocking {
        val n = store.createNotebook(NotebookKind.COURSE, "c")!!
        val l = store.createLesson(n.id, "l")!!
        val (root, reply) = ask(l.id, "q1")
        store.commitReply(reply.id, "a1", EntryState.COMPLETE)
        val note = store.commitSummary(l.id, "note", listOf(root.id, reply.id), null)!!
        val (child, _) = ask(l.id, "q2", parent = reply.id, ref = note.id)
        reopen()
        val byId = store.readEntries(l.id).associateBy { it.id }
        assertEquals(reply.id, byId.getValue(child.id).parentEntryId)
        assertEquals(listOf(note.id), byId.getValue(child.id).sourceEntryIds)
        assertEquals(listOf(root.id, reply.id), byId.getValue(note.id).sourceEntryIds)
    }

    @Test fun failedTransactionLeavesDataReadable() = runBlocking {
        val n = store.createNotebook(NotebookKind.COURSE, "c")!!
        val l = store.createLesson(n.id, "l")!!
        ask(l.id, "kept")
        val before = store.readEntries(l.id)
        // A missing parent is rejected inside the transaction; nothing partial is written.
        assertNull(store.insertQuestion(Entry(0, l.id, EntryKind.USER, EntryAction.ASK, "x", parentEntryId = -1)))
        // A constraint violation aborts the whole transaction.
        assertTrue(runCatching { store.commitReply(-1, "x", EntryState.PENDING) }.isFailure)
        assertEquals(before, store.readEntries(l.id))
    }

    @Test fun pendingBecomesInterruptedCompleteUnchanged() = runBlocking {
        val n = store.createNotebook(NotebookKind.COURSE, "c")!!
        val l = store.createLesson(n.id, "l")!!
        val (_, pending) = ask(l.id, "p")
        val (_, done) = ask(l.id, "d")
        store.commitReply(done.id, "ok", EntryState.COMPLETE)
        reopen()
        assertEquals(1, store.markPendingInterrupted())
        val byId = store.readEntries(l.id).associateBy { it.id }
        assertEquals(EntryState.INTERRUPTED, byId.getValue(pending.id).state)
        assertEquals(EntryState.COMPLETE, byId.getValue(done.id).state)
    }

    @Test fun commitRechecksTarget() = runBlocking {
        val n = store.createNotebook(NotebookKind.COURSE, "c")!!
        val l = store.createLesson(n.id, "l")!!
        val (root, reply) = ask(l.id, "q")
        assertTrue(store.deleteThread(root.id))
        assertFalse(store.commitReply(reply.id, "late", EntryState.COMPLETE))
        assertTrue(store.readEntries(l.id, includeArchived = true).isEmpty())

        assertTrue(store.deleteLesson(l.id))
        assertNull(store.commitSummary(l.id, "late note", emptyList(), null))
    }

    @Test fun archivedThreadsHiddenByDefault() = runBlocking {
        val n = store.createNotebook(NotebookKind.COURSE, "c")!!
        val l = store.createLesson(n.id, "l")!!
        val (root, reply) = ask(l.id, "q")
        ask(l.id, "follow", parent = reply.id)
        val (other, _) = ask(l.id, "other")
        assertFalse(store.setArchived(reply.id, true))
        assertTrue(store.setArchived(root.id, true))
        assertEquals(listOf(other.id), store.readEntries(l.id).filter { it.isRoot }.map { it.id })
        assertEquals(2, store.readEntries(l.id).size)
        // Each question also stores its pending reply: 3 questions -> 6 entries.
        assertEquals(6, store.readEntries(l.id, includeArchived = true).size)
        assertTrue(store.setArchived(root.id, false))
        assertEquals(6, store.readEntries(l.id).size)
    }

    @Test fun masteryOnlyOnPracticeRoots() = runBlocking {
        val course = store.createNotebook(NotebookKind.COURSE, "c")!!
        val practice = store.createNotebook(NotebookKind.PRACTICE, "p")!!
        val cl = store.createLesson(course.id, "l")!!
        val pl = store.createLesson(practice.id, "试卷一")!!
        val (courseRoot, _) = ask(cl.id, "q")
        val (problem, reply) = ask(pl.id, "题")
        val (child, _) = ask(pl.id, "我的解答", parent = reply.id)

        assertNull(courseRoot.mastery)
        assertEquals(Mastery.UNMASTERED, problem.mastery)
        assertFalse(store.setMastery(courseRoot.id, Mastery.MASTERED))
        assertFalse(store.setMastery(child.id, Mastery.MASTERED))
        assertNull(store.getEntry(courseRoot.id)!!.mastery)
        assertNull(store.getEntry(child.id)!!.mastery)
        assertTrue(store.setMastery(problem.id, Mastery.MASTERED))
        assertEquals(Mastery.MASTERED, store.getEntry(problem.id)!!.mastery)
    }

    @Test fun referenceNotesFollowCourseLink() = runBlocking {
        val course = store.createNotebook(NotebookKind.COURSE, "c")!!
        val other = store.createNotebook(NotebookKind.COURSE, "o")!!
        val practice = store.createNotebook(NotebookKind.PRACTICE, "p")!!
        val courseNote = store.commitSummary(store.createLesson(course.id, "l")!!.id, "cn", emptyList(), null)!!
        store.commitSummary(store.createLesson(other.id, "l")!!.id, "on", emptyList(), null)!!
        val ownNote = store.commitSummary(store.createLesson(practice.id, "l")!!.id, "pn", emptyList(), null)!!

        assertEquals(setOf(ownNote.id), store.readReferenceNotes(practice.id).map { it.id }.toSet())
        assertTrue(store.updateNotebook(practice.copy(linkedCourseId = course.id)))
        assertEquals(setOf(ownNote.id, courseNote.id), store.readReferenceNotes(practice.id).map { it.id }.toSet())
        // Courses cannot link; practice books cannot link to practice books.
        assertFalse(store.updateNotebook(course.copy(linkedCourseId = other.id)))
        assertFalse(store.updateNotebook(practice.copy(linkedCourseId = practice.id)))
    }

    @Test fun deletesCascadeAndCleanPhotos() = runBlocking {
        val course = store.createNotebook(NotebookKind.COURSE, "c")!!
        val practice = store.createNotebook(NotebookKind.PRACTICE, "p", linkedCourseId = course.id)!!
        val l1 = store.createLesson(course.id, "l1")!!
        val l2 = store.createLesson(course.id, "l2")!!
        val rootImg = photo(course.id)
        val childImg = photo(course.id)
        val keepImg = photo(course.id)
        val (root, reply) = ask(l1.id, "q", image = rootImg)
        ask(l1.id, "child", parent = reply.id, image = childImg)
        val (keep, keepReply) = ask(l1.id, "keep", image = keepImg)
        val note = store.commitSummary(l1.id, "n", listOf(root.id, reply.id), null)!!

        assertTrue(store.deleteThread(root.id))
        assertFalse(photos.isUsable(course.id, rootImg))
        assertFalse(photos.isUsable(course.id, childImg))
        assertTrue(photos.isUsable(course.id, keepImg))
        val remaining = store.readEntries(l1.id).map { it.id }.toSet()
        assertEquals(setOf(keep.id, keepReply.id, note.id), remaining)
        // Note text survives; its sources now dangle and are shown as deleted.
        assertEquals("n", store.getEntry(note.id)!!.text)
        assertTrue(store.getEntries(store.getEntry(note.id)!!.sourceEntryIds).isEmpty())

        assertTrue(store.deleteLesson(l1.id))
        assertNull(store.getEntry(note.id))
        assertFalse(photos.isUsable(course.id, keepImg))

        val template = store.saveTemplate(Template(0, "严格", "讲证明要严格", 0))!!
        assertTrue(store.updateNotebook(practice.copy(defaultTemplateId = template.id)))
        assertTrue(store.deleteTemplate(template.id))
        assertNull(store.getNotebook(practice.id)!!.defaultTemplateId)

        photo(course.id)
        assertTrue(store.deleteNotebook(course.id))
        assertTrue(store.listLessons(course.id).isEmpty())
        assertNull(store.getLesson(l2.id))
        assertFalse(photos.notebookDir(course.id).exists())
        assertNull(store.getNotebook(practice.id)!!.linkedCourseId)
    }

    @Test fun changesIncrementAndPhotoNamesAreConfined() = runBlocking {
        val before = store.changes.value
        val n = store.createNotebook(NotebookKind.COURSE, "c")
        assertNotNull(n)
        assertTrue(store.changes.value > before)
        assertTrue(runCatching { photos.resolvePhoto(n!!.id, "../notes.db") }.isFailure)
        assertTrue(runCatching { photos.resolvePhoto(n!!.id, "a/b.jpg") }.isFailure)
        assertFalse(photos.isUsable(n!!.id, "..\\x.jpg"))
    }

    @Test fun reviewRecordsIsolatedByCourseNotebook() = runBlocking {
        val courseA = store.createNotebook(NotebookKind.COURSE, "Course A")!!
        val courseB = store.createNotebook(NotebookKind.COURSE, "Course B")!!
        val recordA = store.addReviewRecord(courseA.id, "Topic A", "Notes A")!!
        val recordB = store.addReviewRecord(courseB.id, "Topic B", "Notes B")!!

        val listA = store.listReviewRecords(courseA.id)
        val listB = store.listReviewRecords(courseB.id)

        assertEquals(1, listA.size)
        assertEquals(recordA.id, listA[0].id)
        assertEquals(1, listB.size)
        assertEquals(recordB.id, listB[0].id)
    }

    @Test fun reviewRecordCrossCourseSecurityEnforced() = runBlocking {
        val courseA = store.createNotebook(NotebookKind.COURSE, "Course A")!!
        val courseB = store.createNotebook(NotebookKind.COURSE, "Course B")!!
        val practice = store.createNotebook(NotebookKind.PRACTICE, "Practice")!!
        val recordB = store.addReviewRecord(courseB.id, "Topic B", "Notes B")!!

        // Course A context attempts to read, update, change status, or delete Course B's record
        assertNull(store.getReviewRecord(courseA.id, recordB.id))
        assertFalse(store.updateReviewRecord(courseA.id, recordB.id, "Hacked Topic", "Hacked Notes"))
        assertFalse(store.setReviewStatus(courseA.id, recordB.id, ReviewStatus.UNDERSTOOD))
        assertFalse(store.deleteReviewRecord(courseA.id, recordB.id))

        // Practice notebook context attempts the same operations
        assertNull(store.getReviewRecord(practice.id, recordB.id))
        assertFalse(store.updateReviewRecord(practice.id, recordB.id, "Hacked Topic", "Hacked Notes"))
        assertFalse(store.setReviewStatus(practice.id, recordB.id, ReviewStatus.UNDERSTOOD))
        assertFalse(store.deleteReviewRecord(practice.id, recordB.id))

        // Verify Course B's record remains completely untouched
        val verifyB = store.getReviewRecord(courseB.id, recordB.id)!!
        assertEquals("Topic B", verifyB.topic)
        assertEquals("Notes B", verifyB.notes)
        assertEquals(ReviewStatus.PENDING, verifyB.status)
        assertFalse(verifyB.sourceDeleted)
    }

    @Test fun reviewRecordRejectsCrossCourseSourceEntry() = runBlocking {
        val courseA = store.createNotebook(NotebookKind.COURSE, "Course A")!!
        val courseB = store.createNotebook(NotebookKind.COURSE, "Course B")!!
        val lessonB = store.createLesson(courseB.id, "Lesson B")!!
        val (qB, _) = ask(lessonB.id, "Question in B")

        val result = store.insertReviewRecord(courseA.id, "Topic", "Notes", sourceEntryId = qB.id)
        assertTrue(result is ReviewInsertResult.SourceNotFound)
        assertTrue(store.listReviewRecords(courseA.id).isEmpty())
    }

    @Test fun reviewRecordRejectsNonCourseNotebook() = runBlocking {
        // Explicitly initialize General chat so -2 exists in the notebooks table
        store.generalChat()
        val practice = store.createNotebook(NotebookKind.PRACTICE, "Practice")!!

        val resultPractice = store.insertReviewRecord(practice.id, "Topic", "Notes")
        assertTrue(resultPractice is ReviewInsertResult.InvalidCourse)

        val resultGeneral = store.insertReviewRecord(NotebookStore.GENERAL_ID, "Topic", "Notes")
        assertTrue(resultGeneral is ReviewInsertResult.InvalidCourse)

        // Verify other operations on GENERAL_ID are rejected as well
        assertTrue(store.listReviewRecords(NotebookStore.GENERAL_ID).isEmpty())
        assertNull(store.getReviewRecord(NotebookStore.GENERAL_ID, 1L))
        assertFalse(store.updateReviewRecord(NotebookStore.GENERAL_ID, 1L, "T", "N"))
        assertFalse(store.setReviewStatus(NotebookStore.GENERAL_ID, 1L, ReviewStatus.UNDERSTOOD))
        assertFalse(store.deleteReviewRecord(NotebookStore.GENERAL_ID, 1L))
    }

    @Test fun reviewRecordRejectsBlankTopic() = runBlocking {
        val course = store.createNotebook(NotebookKind.COURSE, "Physics")!!
        val blankResult = store.insertReviewRecord(course.id, "   ", "Notes")
        assertTrue(blankResult is ReviewInsertResult.Failed)

        val valid = store.insertReviewRecord(course.id, "Topic", "Notes")
        assertTrue(valid is ReviewInsertResult.Success)
        val rec = (valid as ReviewInsertResult.Success).record

        assertFalse(store.updateReviewRecord(course.id, rec.id, "   ", "Updated Notes"))
    }

    @Test fun reviewRecordRejectsIncompleteAssistantSource() = runBlocking {
        val course = store.createNotebook(NotebookKind.COURSE, "Math")!!
        val lesson = store.createLesson(course.id, "Lesson")!!
        val (q, pendingA) = ask(lesson.id, "Question")

        // pendingA has state = PENDING
        val resPending = store.insertReviewRecord(course.id, "Topic", "Notes", sourceEntryId = pendingA.id)
        assertTrue(resPending is ReviewInsertResult.SourceNotFound)

        // Complete the assistant reply
        assertTrue(store.commitReply(pendingA.id, "Finished answer", EntryState.COMPLETE))
        val resComplete = store.insertReviewRecord(course.id, "Topic", "Notes", sourceEntryId = pendingA.id)
        assertTrue(resComplete is ReviewInsertResult.Success)
    }

    @Test fun reviewRecordCrudAndReopen() = runBlocking {
        val course = store.createNotebook(NotebookKind.COURSE, "Math")!!
        val lesson = store.createLesson(course.id, "Calculus")!!
        val (q, _) = ask(lesson.id, "Limit definition")

        val record = store.addReviewRecord(course.id, "Limits", "Review eps-delta", sourceEntryId = q.id)!!
        assertEquals(ReviewStatus.PENDING, record.status)
        assertFalse(record.sourceDeleted)
        assertEquals(q.id, record.sourceEntryId)

        reopen()

        val fetched = store.getReviewRecord(course.id, record.id)!!
        assertEquals(record.id, fetched.id)
        assertEquals(course.id, fetched.notebookId)
        assertEquals("Limits", fetched.topic)
        assertEquals("Review eps-delta", fetched.notes)
        assertEquals(ReviewStatus.PENDING, fetched.status)
        assertFalse(fetched.sourceDeleted)

        assertTrue(store.updateReviewRecord(course.id, record.id, "Limits updated", "Updated notes"))
        val updated = store.getReviewRecord(course.id, record.id)!!
        assertEquals("Limits updated", updated.topic)
        assertEquals("Updated notes", updated.notes)
        assertTrue(updated.updatedAt >= updated.createdAt)

        assertTrue(store.setReviewStatus(course.id, record.id, ReviewStatus.UNDERSTOOD))
        val understood = store.getReviewRecord(course.id, record.id)!!
        assertEquals(ReviewStatus.UNDERSTOOD, understood.status)

        assertTrue(store.setReviewStatus(course.id, record.id, ReviewStatus.CONFUSED))
        val confused = store.getReviewRecord(course.id, record.id)!!
        assertEquals(ReviewStatus.CONFUSED, confused.status)

        reopen()
        assertEquals(ReviewStatus.CONFUSED, store.getReviewRecord(course.id, record.id)!!.status)

        assertTrue(store.deleteReviewRecord(course.id, record.id))
        assertNull(store.getReviewRecord(course.id, record.id))
        assertTrue(store.listReviewRecords(course.id).isEmpty())
    }

    @Test fun reviewRecordDuplicateSourcePrevention() = runBlocking {
        val course = store.createNotebook(NotebookKind.COURSE, "Math")!!
        val lesson = store.createLesson(course.id, "Calculus")!!
        val (q, _) = ask(lesson.id, "Derivative rules")

        val first = store.insertReviewRecord(course.id, "Derivatives", "Product rule", sourceEntryId = q.id)
        assertTrue(first is ReviewInsertResult.Success)
        val firstRecord = (first as ReviewInsertResult.Success).record

        val second = store.insertReviewRecord(course.id, "Derivatives 2", "Another note", sourceEntryId = q.id)
        assertTrue(second is ReviewInsertResult.AlreadyExists)
        val secondRecord = (second as ReviewInsertResult.AlreadyExists).existingRecord

        assertEquals(firstRecord.id, secondRecord.id)
        assertEquals(1, store.listReviewRecords(course.id).size)

        val bySource = store.findReviewRecordBySource(course.id, q.id)
        assertNotNull(bySource)
        assertEquals(firstRecord.id, bySource!!.id)
    }

    @Test fun reviewRecordsCascadeOnNotebookDeletion() = runBlocking {
        val course = store.createNotebook(NotebookKind.COURSE, "Math")!!
        val record = store.addReviewRecord(course.id, "Limits", "Notes")!!
        assertNotNull(store.getReviewRecord(course.id, record.id))

        assertTrue(store.deleteNotebook(course.id))
        assertNull(store.getReviewRecord(course.id, record.id))
        assertTrue(store.listReviewRecords(course.id).isEmpty())
    }

    @Test fun sourceDeletedMarkedOnLessonOrThreadDeletion() = runBlocking {
        val course = store.createNotebook(NotebookKind.COURSE, "Physics")!!
        val lesson1 = store.createLesson(course.id, "Mechanics")!!
        val lesson2 = store.createLesson(course.id, "Optics")!!
        val (q1, a1) = ask(lesson1.id, "Newton's laws")
        store.commitReply(a1.id, "Newton's first law text", EntryState.COMPLETE)
        val (q2, a2) = ask(lesson2.id, "Refraction")
        store.commitReply(a2.id, "Snell's law text", EntryState.COMPLETE)
        val note = store.commitSummary(lesson1.id, "Summary note", emptyList(), null)!!

        val record1 = store.addReviewRecord(course.id, "Newton", "Law 1", sourceEntryId = a1.id)!!
        val record2 = store.addReviewRecord(course.id, "Optics", "Snell's Law", sourceEntryId = a2.id)!!
        val recordNote = store.addReviewRecord(course.id, "Note", "Summary Review", sourceEntryId = note.id)!!

        assertFalse(store.getReviewRecord(course.id, record1.id)!!.sourceDeleted)
        assertFalse(store.getReviewRecord(course.id, record2.id)!!.sourceDeleted)
        assertFalse(store.getReviewRecord(course.id, recordNote.id)!!.sourceDeleted)

        assertTrue(store.deleteThread(q1.id))
        val rec1AfterThreadDel = store.getReviewRecord(course.id, record1.id)!!
        assertTrue(rec1AfterThreadDel.sourceDeleted)
        assertEquals(a1.id, rec1AfterThreadDel.sourceEntryId)
        assertFalse(store.getReviewRecord(course.id, record2.id)!!.sourceDeleted)
        assertFalse(store.getReviewRecord(course.id, recordNote.id)!!.sourceDeleted)

        assertTrue(store.deleteLesson(lesson2.id))
        val rec2AfterLessonDel = store.getReviewRecord(course.id, record2.id)!!
        assertTrue(rec2AfterLessonDel.sourceDeleted)
        assertEquals(a2.id, rec2AfterLessonDel.sourceEntryId)
        assertFalse(store.getReviewRecord(course.id, recordNote.id)!!.sourceDeleted)

        assertTrue(store.deleteNote(note.id))
        val recNoteAfterDel = store.getReviewRecord(course.id, recordNote.id)!!
        assertTrue(recNoteAfterDel.sourceDeleted)
        assertEquals(note.id, recNoteAfterDel.sourceEntryId)
    }

    @Test fun isThreadArchivedDetectsThreadStatus() = runBlocking {
        val course = store.createNotebook(NotebookKind.COURSE, "Chemistry")!!
        val lesson = store.createLesson(course.id, "Organic")!!
        val (q, a) = ask(lesson.id, "What is benzene?")

        assertFalse(store.isThreadArchived(a.id))
        assertTrue(store.setArchived(q.id, true))
        assertTrue(store.isThreadArchived(a.id))
    }

    @Test fun upgradesFromV1ToV4PreservesDataAndAddsReviewTable() = runBlocking {
        database.close()
        val legacy = context.openOrCreateDatabase(dbName, 0, null)
        val schema = InstrumentationRegistry.getInstrumentation().context.assets.open("notes-v1.sql").bufferedReader().use { it.readText() }
        schema.split(';').filter { it.isNotBlank() }.forEach { legacy.execSQL(it) }
        legacy.execSQL("INSERT INTO notebooks (id,kind,name,created_at) VALUES (1,'course','Existing Course',1)")
        legacy.execSQL("INSERT INTO lessons (id,notebook_id,title,created_at) VALUES (2,1,'Existing Lesson',1)")
        legacy.execSQL("INSERT INTO entries (id,lesson_id,kind,action,text,image_path,created_at) VALUES (3,2,'user','ask','Existing Q',NULL,1)")
        legacy.version = 1
        legacy.close()

        open()
        assertEquals(NotebookDatabase.VERSION, database.readableDatabase.version)

        val existingEntries = store.readEntries(2)
        assertEquals(1, existingEntries.size)
        assertEquals("Existing Q", existingEntries[0].text)

        val rec = store.addReviewRecord(1, "Migrated Review", "Works after migration", sourceEntryId = 3)
        assertNotNull(rec)
        assertEquals(1, store.listReviewRecords(1).size)
    }

    @Test fun upgradesFromV2ToV4PreservesDataAndSeedsTemplatesAndAddsReviewTable() = runBlocking {
        database.close()
        val legacy = context.openOrCreateDatabase(dbName, 0, null)
        val schema = InstrumentationRegistry.getInstrumentation().context.assets.open("notes-v2.sql").bufferedReader().use { it.readText() }
        schema.split(';').filter { it.isNotBlank() }.forEach { legacy.execSQL(it) }
        legacy.execSQL("INSERT INTO templates (id,name,instruction,created_at) VALUES (10,'User Template','Explain nicely',1)")
        legacy.execSQL("INSERT INTO notebooks (id,kind,name,default_template_id,created_at) VALUES (1,'course','V2 Course',10,1)")
        legacy.execSQL("INSERT INTO lessons (id,notebook_id,title,created_at) VALUES (2,1,'V2 Lesson',1)")
        legacy.execSQL("INSERT INTO entries (id,lesson_id,kind,action,text,image_paths,created_at) VALUES (3,2,'user','ask','V2 Question','[]',1)")
        legacy.version = 2
        legacy.close()

        open()
        assertEquals(NotebookDatabase.VERSION, database.readableDatabase.version)

        // V2 -> V3 migration adds templates.source and seeds guided template
        val templates = store.listTemplates()
        assertEquals(2, templates.size)
        assertTrue(templates.any { it.name == "User Template" && it.source == null })
        assertTrue(templates.any { it.source == Template.BUILTIN_GUIDED })

        val entries = store.readEntries(2)
        assertEquals(1, entries.size)
        assertEquals("V2 Question", entries[0].text)

        val rec = store.addReviewRecord(1, "V2 Review", "Works on migrated V2", sourceEntryId = 3)
        assertNotNull(rec)
        assertEquals(1, store.listReviewRecords(1).size)
    }

    @Test fun upgradesFromV3ToV4PreservesExistingTemplatesWithoutReseeding() = runBlocking {
        database.close()
        val legacy = context.openOrCreateDatabase(dbName, 0, null)
        val schema = InstrumentationRegistry.getInstrumentation().context.assets.open("notes-v3.sql").bufferedReader().use { it.readText() }
        schema.split(';').filter { it.isNotBlank() }.forEach { legacy.execSQL(it) }
        // User already customized their templates in V3:
        legacy.execSQL("INSERT INTO templates (id,name,instruction,created_at,source) VALUES (1,'My Custom Guided','Be strict',1,'builtin:guided')")
        legacy.execSQL("INSERT INTO templates (id,name,instruction,created_at,source) VALUES (2,'Imported Skill','LaTeX only',2,'https://example.com/skill.md')")
        legacy.execSQL("INSERT INTO notebooks (id,kind,name,created_at) VALUES (1,'course','V3 Course',1)")
        legacy.execSQL("INSERT INTO lessons (id,notebook_id,title,created_at) VALUES (2,1,'V3 Lesson',1)")
        legacy.execSQL("INSERT INTO entries (id,lesson_id,kind,action,text,image_paths,created_at) VALUES (3,2,'user','ask','V3 Question','[]',1)")
        legacy.version = 3
        legacy.close()

        open()
        assertEquals(NotebookDatabase.VERSION, database.readableDatabase.version)

        // Crucial verification: V3 -> V4 MUST NOT re-seed builtin templates
        val templates = store.listTemplates()
        assertEquals(2, templates.size)
        assertEquals(setOf("My Custom Guided", "Imported Skill"), templates.map { it.name }.toSet())

        val entries = store.readEntries(2)
        assertEquals(1, entries.size)
        assertEquals("V3 Question", entries[0].text)

        val rec = store.addReviewRecord(1, "V3 Review", "Works on migrated V3", sourceEntryId = 3)
        assertNotNull(rec)
        assertEquals(1, store.listReviewRecords(1).size)
    }
}
