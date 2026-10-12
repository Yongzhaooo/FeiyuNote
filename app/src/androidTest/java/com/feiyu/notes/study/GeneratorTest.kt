package com.feiyu.notes.study

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feiyu.notes.ai.AiConfig
import com.feiyu.notes.ai.AiError
import com.feiyu.notes.ai.AiInput
import com.feiyu.notes.ai.AiReply
import com.feiyu.notes.data.Entry
import com.feiyu.notes.data.EntryAction
import com.feiyu.notes.data.EntryKind
import com.feiyu.notes.data.EntryState
import com.feiyu.notes.data.Lesson
import com.feiyu.notes.data.Notebook
import com.feiyu.notes.data.NotebookDatabase
import com.feiyu.notes.data.NotebookKind
import com.feiyu.notes.data.NotebookStore
import com.feiyu.notes.data.PhotoFiles
import com.feiyu.notes.data.Template
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** P4/P5 executor behaviour against real SQLite with a controllable fake model. */
@RunWith(AndroidJUnit4::class)
class GeneratorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "generator-test.db"
    private lateinit var database: NotebookDatabase
    private lateinit var photos: PhotoFiles
    private lateinit var store: NotebookStore
    private lateinit var scope: CoroutineScope
    private lateinit var generator: Generator
    private lateinit var notebook: Notebook
    private lateinit var lesson: Lesson

    private val calls = AtomicInteger()
    private val inputs = mutableListOf<AiInput>()
    /** Each call waits on the next gate; tests complete or fail it. */
    private var gate = CompletableDeferred<AiReply>()

    @Before fun setUp() = runBlocking {
        context.deleteDatabase(dbName)
        val dir = File(context.cacheDir, "generator-test").apply { deleteRecursively(); mkdirs() }
        database = NotebookDatabase(context, dbName)
        photos = PhotoFiles(dir)
        store = NotebookStore(database, photos)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        generator = Generator(
            appContext = context,
            store = store,
            photos = photos,
            loadConfig = { AiConfig(apiKey = "test") },
            scope = scope,
            ready = Job().apply { complete() },
            generate = { _: AiConfig, input: AiInput ->
                calls.incrementAndGet()
                synchronized(inputs) { inputs += input }
                gate.await()
            },
        )
        notebook = store.createNotebook(NotebookKind.COURSE, "c")!!
        lesson = store.createLesson(notebook.id, "l")!!
    }

    @After fun tearDown() {
        scope.cancel()
        database.close()
        context.deleteDatabase(dbName)
    }

    private fun question(text: String, parent: Long? = null) =
        Entry(0, lesson.id, EntryKind.USER, EntryAction.ASK, text, parentEntryId = parent)

    private suspend fun idle() = withTimeout(5_000) { generator.status.first { it.running == null } }
    private suspend fun running() = withTimeout(5_000) { generator.status.first { it.running != null } }
    private suspend fun entries() = store.readEntries(lesson.id, includeArchived = true)

    @Test fun oneSendOneRequestAndBusyRejected() = runBlocking {
        assertEquals(StartResult.Started, generator.ask(notebook.id, question("q")))
        running()
        assertEquals(StartResult.Busy, generator.ask(notebook.id, question("q2")))
        gate.complete(AiReply("a"))
        idle()
        assertEquals(1, calls.get())
        val reply = entries().single { it.kind == EntryKind.ASSISTANT }
        assertEquals(EntryState.COMPLETE, reply.state)
        assertEquals("a", reply.text)
        assertEquals(1, entries().count { it.kind == EntryKind.USER })
    }

    @Test fun lateResultAfterDeleteIsDiscarded() = runBlocking {
        generator.ask(notebook.id, question("q"))
        running()
        val root = entries().single { it.kind == EntryKind.USER }
        // Delete without cancelling first: the response still arrives and must not resurrect anything.
        assertTrue(store.deleteThread(root.id))
        gate.complete(AiReply("late"))
        val status = idle()
        assertEquals(com.feiyu.notes.settings.AppLanguage.context(context).getString(com.feiyu.notes.R.string.discarded), status.message)
        assertTrue(entries().isEmpty())
    }

    @Test fun deleteCancelsAffectedRequest() = runBlocking {
        generator.ask(notebook.id, question("q"))
        running()
        val ids = entries().map { it.id }.toSet()
        generator.cancelIfAffected(entryIds = ids)
        store.deleteThread(entries().single { it.kind == EntryKind.USER }.id)
        idle()
        gate.complete(AiReply("late"))
        assertTrue(entries().isEmpty())

        gate = CompletableDeferred()
        generator.ask(notebook.id, question("q2"))
        running()
        generator.cancelIfAffected(lessonId = lesson.id)
        assertTrue(store.deleteLesson(lesson.id))
        idle()
        assertNull(store.getLesson(lesson.id))
    }

    @Test fun cancelAndFailureKeepQuestion() = runBlocking {
        generator.ask(notebook.id, question("q"))
        running()
        generator.cancel()
        idle()
        assertEquals(EntryState.CANCELLED, entries().single { it.kind == EntryKind.ASSISTANT }.state)

        gate = CompletableDeferred()
        val user = entries().single { it.kind == EntryKind.USER }
        assertEquals(StartResult.Started, generator.retry(notebook.id, user.id))
        running()
        gate.completeExceptionally(AiError.Quota(429))
        idle()
        val replies = entries().filter { it.kind == EntryKind.ASSISTANT }
        assertEquals(listOf(EntryState.CANCELLED, EntryState.FAILED), replies.map { it.state })
        assertEquals(1, entries().count { it.kind == EntryKind.USER })
    }

    @Test fun summaryWritesNoteOnlyOnSuccess() = runBlocking {
        generator.ask(notebook.id, question("q"))
        gate.complete(AiReply("a"))
        idle()

        gate = CompletableDeferred()
        generator.summarize(notebook.id, lesson.id, null)
        running()
        assertTrue("nothing pre-written", entries().none { it.kind == EntryKind.NOTE || it.state == EntryState.PENDING })
        gate.completeExceptionally(AiError.EmptyAnswer())
        idle()
        assertTrue(entries().none { it.kind == EntryKind.NOTE })

        gate = CompletableDeferred()
        generator.summarize(notebook.id, lesson.id, null)
        running()
        generator.cancel()
        idle()
        assertTrue(entries().none { it.kind == EntryKind.NOTE })

        gate = CompletableDeferred()
        generator.summarize(notebook.id, lesson.id, null)
        gate.complete(AiReply("笔记"))
        idle()
        val note = entries().single { it.kind == EntryKind.NOTE }
        assertEquals("笔记", note.text)
        assertTrue(inputs.last().messages.all { it.images.isEmpty() })

        gate = CompletableDeferred()
        generator.summarize(notebook.id, lesson.id, null)
        running()
        store.deleteLesson(lesson.id)
        gate.complete(AiReply("迟到笔记"))
        assertEquals(com.feiyu.notes.settings.AppLanguage.context(context).getString(com.feiyu.notes.R.string.discarded), idle().message)
        assertNull(store.getLesson(lesson.id))
    }

    @Test fun multiPhotoRetryKeepsAllImagesAndRejectsAMissingOne() = runBlocking {
        val files = List(2) { photos.allocatePhoto(notebook.id).apply { writeText("synthetic photo") } }
        assertEquals(StartResult.Started, generator.ask(notebook.id, question("").copy(imagePaths = files.map { it.name })))
        gate.complete(AiReply("answer"))
        idle()
        assertEquals(files, inputs.last().messages.last().images)
        val user = entries().single { it.kind == EntryKind.USER }
        gate = CompletableDeferred<AiReply>().apply { complete(AiReply("retry")) }
        assertEquals(StartResult.Started, generator.retry(notebook.id, user.id))
        idle()
        assertEquals(files, inputs.last().messages.last().images)
        files.last().delete()
        assertTrue(generator.retry(notebook.id, user.id) is StartResult.Invalid)
        assertEquals(2, calls.get())
    }

    @Test fun staleReferencesAreRejectedBeforeSaving() = runBlocking {
        val other = store.createNotebook(NotebookKind.COURSE, "other")!!
        val foreignNote = store.commitSummary(store.createLesson(other.id, "x")!!.id, "n", emptyList(), null)!!
        val badRef = question("q").copy(sourceEntryIds = listOf(foreignNote.id))
        assertTrue(generator.ask(notebook.id, badRef) is StartResult.Invalid)

        val template = store.saveTemplate(Template(0, "t", "i", 0))!!
        store.deleteTemplate(template.id)
        assertTrue(generator.ask(notebook.id, question("q").copy(templateId = template.id)) is StartResult.Invalid)

        assertTrue(generator.ask(notebook.id, question("").copy(imagePaths = listOf("missing.jpg"))) is StartResult.Invalid)
        assertTrue(entries().isEmpty())
        assertEquals(0, calls.get())
    }

    @Test fun retryWithDeletedTemplateNeedsExplicitChoice() = runBlocking {
        val template = store.saveTemplate(Template(0, "t", "每步写依据", 0))!!
        generator.ask(notebook.id, question("q").copy(templateId = template.id))
        gate.complete(AiReply("a"))
        idle()
        val user = entries().single { it.kind == EntryKind.USER }
        store.deleteTemplate(template.id)
        assertEquals(StartResult.Invalid(com.feiyu.notes.settings.AppLanguage.context(context).getString(com.feiyu.notes.R.string.template_gone)), generator.retry(notebook.id, user.id))
        assertTrue(store.setEntryTemplate(user.id, null))
        gate = CompletableDeferred<AiReply>().apply { complete(AiReply("b")) }
        assertEquals(StartResult.Started, generator.retry(notebook.id, user.id))
        idle()
        assertTrue(inputs.last().systemText.contains("每步写依据").not())
    }

    @Test fun contextSnapshotSurvivesRetryAndChangedKnowledgeRequiresExplicitRemoval() = runBlocking {
        assertTrue(store.saveCourseDocument(notebook.id, "# 极限\n假设连续，定理条件。", null))
        val knowledge = store.searchCourseKnowledge(notebook.id, "极限条件")
        assertEquals(StartResult.Started, generator.ask(notebook.id, question("极限").copy(requestContext = TurnContext(scratchpad = "发送时记录", knowledge = knowledge))))
        gate.completeExceptionally(AiError.Network(java.io.IOException("synthetic failure")))
        idle()
        val user = entries().single { it.kind == EntryKind.USER }
        store.updateScratchpad(notebook.id, lesson.id, "编辑后的记录")
        gate = CompletableDeferred<AiReply>().apply { complete(AiReply("retry")) }
        assertEquals(StartResult.Started, generator.retry(notebook.id, user.id))
        idle()
        assertTrue(inputs.last().messages.last().text.contains("发送时记录"))
        assertTrue(inputs.last().messages.none { it.text.contains("编辑后的记录") })
        assertTrue(inputs.last().systemText.contains("假设连续"))
        assertTrue(store.saveCourseDocument(notebook.id, "# 极限\n手册新版", 1))
        val count = calls.get()
        assertTrue(generator.retry(notebook.id, user.id) is StartResult.Invalid)
        assertEquals(count, calls.get())
        assertTrue(store.clearEntryKnowledge(notebook.id, user.id))
        assertEquals(StartResult.Started, generator.retry(notebook.id, user.id))
        idle()
        assertTrue(inputs.last().systemText.contains("<课程知识>").not())
        assertTrue(inputs.last().messages.last().text.contains("发送时记录"))
    }

    @Test fun summaryOfScratchpadOnlyCreatesNoteWithoutInventedEntrySources() = runBlocking {
        store.updateScratchpad(notebook.id, lesson.id, "课堂重点")
        assertEquals(StartResult.Started, generator.summarize(notebook.id, lesson.id, null))
        gate.complete(AiReply("摘要")); idle()
        val note = entries().single()
        assertEquals(EntryKind.NOTE, note.kind)
        assertTrue(note.sourceEntryIds.isEmpty())
        assertTrue(inputs.single().messages.single().text.contains("课堂重点"))
    }

    @Test fun deletedHandbookDiscardsInFlightAnswerAndBlocksRetry() = runBlocking {
        store.saveCourseDocument(notebook.id, "# 极限\n连续前提", null)
        val source = store.searchCourseKnowledge(notebook.id, "极限")
        assertEquals(StartResult.Started, generator.ask(notebook.id, question("极限").copy(requestContext = TurnContext(knowledge = source))))
        withTimeout(5000) { while (calls.get() == 0) kotlinx.coroutines.delay(1) }
        val user = entries().single { it.kind == EntryKind.USER }
        store.deleteCourseDocument(notebook.id) // Deliberately bypass UI cancellation to test late commit.
        gate.complete(AiReply("obsolete answer must not be committed")); idle()
        val answer = entries().single { it.kind == EntryKind.ASSISTANT }
        assertEquals(EntryState.FAILED, answer.state)
        assertTrue(answer.text.contains("obsolete answer").not())
        assertTrue(generator.retry(notebook.id, user.id) is StartResult.Invalid)
        assertEquals(1, calls.get())
    }

    @Test fun cancelTitleDiscardsLateUncooperativeModelCandidate() = runBlocking {
        val (q, reply) = store.insertQuestion(question("极限"))!!
        store.commitReply(reply.id, "连续函数", EntryState.COMPLETE)
        val entered = CompletableDeferred<Unit>()
        val late = CompletableDeferred<AiReply>()
        val uncooperative = Generator(context, store, photos, { AiConfig(apiKey = "test") }, scope, Job().apply { complete() },
            generate = { _, _ -> kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { entered.complete(Unit); late.await() } })
        assertEquals(StartResult.Started, uncooperative.suggestTitle(notebook.id, lesson.id))
        entered.await()
        uncooperative.cancelTitle(lesson.id)
        late.complete(AiReply("极限与连续"))
        withTimeout(5000) { uncooperative.status.first { it.running == null } }
        assertNull(uncooperative.status.value.titleSuggestion)
        assertEquals(2, entries().size)
        assertEquals(q.id, entries().first().id)
    }

    @Test fun suggestTitleExtractsCandidateWithoutCreatingChatEntries() = runBlocking {
        assertTrue(generator.suggestTitle(notebook.id, lesson.id) is StartResult.Invalid)

        val otherNotebook = store.createNotebook(NotebookKind.COURSE, "Other")!!
        assertTrue(generator.suggestTitle(otherNotebook.id, lesson.id) is StartResult.Invalid)
        assertTrue(generator.suggestTitle(com.feiyu.notes.data.NotebookStore.GENERAL_ID, com.feiyu.notes.data.NotebookStore.GENERAL_ID) is StartResult.Invalid)

        generator.ask(notebook.id, question("极限是什么"))
        gate.complete(AiReply("极限是趋近的数值"))
        idle()
        val countBefore = entries().size

        gate = CompletableDeferred()
        assertEquals(StartResult.Started, generator.suggestTitle(notebook.id, lesson.id))
        running()
        gate.complete(AiReply("极限与连续"))
        val status = idle()
        org.junit.Assert.assertNotNull(status.titleSuggestion)
        assertEquals(lesson.id, status.titleSuggestion?.lessonId)
        assertTrue(status.titleSuggestion!!.title.endsWith("极限与连续"))
        assertEquals(countBefore, entries().size)

        // Cancelled suggestion does not set suggestion
        gate = CompletableDeferred()
        assertEquals(StartResult.Started, generator.suggestTitle(notebook.id, lesson.id))
        running()
        generator.cancel()
        idle()
        org.junit.Assert.assertNull(generator.status.value.titleSuggestion)
    }
}
