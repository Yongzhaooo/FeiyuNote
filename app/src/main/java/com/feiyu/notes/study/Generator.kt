package com.feiyu.notes.study

import android.content.Context
import com.feiyu.notes.R
import com.feiyu.notes.settings.AppLanguage
import com.feiyu.notes.ai.AiConfig
import com.feiyu.notes.ai.AiError
import com.feiyu.notes.ai.AiInput
import com.feiyu.notes.ai.AiReply
import com.feiyu.notes.ai.DeepSeekClient
import com.feiyu.notes.data.Entry
import com.feiyu.notes.data.EntryAction
import com.feiyu.notes.data.EntryKind
import com.feiyu.notes.data.EntryState
import com.feiyu.notes.data.NotebookStore
import com.feiyu.notes.data.PhotoFiles
import com.feiyu.notes.data.Template
import com.feiyu.notes.support.DiagnosticOperation
import com.feiyu.notes.support.DiagnosticResult
import com.feiyu.notes.support.Diagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** What is running now, plus the last user-visible outcome message. */
data class GenerationStatus(val running: Running? = null, val message: String? = null) {
    data class Running(val notebookId: Long, val lessonId: Long, val replyId: Long?, val isSummary: Boolean)
}

sealed interface StartResult {
    data object Started : StartResult
    data object Busy : StartResult
    data class Invalid(val message: String) : StartResult
}

/**
 * App-scoped single executor (spec §3/§4). At most one request at a time; it outlives screens.
 * Two concrete paths: Q&A (question + pending reply saved first, reply committed after) and
 * summary (nothing saved until success). Results are committed only if the target still exists.
 */
class Generator(
    private val appContext: Context,
    private val store: NotebookStore,
    private val photos: PhotoFiles,
    private val loadConfig: suspend (Long) -> AiConfig?,
    private val scope: CoroutineScope,
    /** Completes once leftover pending replies have been marked interrupted. */
    private val ready: Job,
    private val generate: suspend (AiConfig, AiInput) -> AiReply = { config, input -> DeepSeekClient(config).generate(input) },
    /** Current text of a built-in system prompt (user override or default). */
    private val prompt: (PromptKind, String) -> String = { kind, language -> kind.default(language) },
    private val diagnostics: Diagnostics? = null,
) {
    private val context get() = AppLanguage.context(appContext)
    private val language get() = AppLanguage.code(context.resources.configuration.locales[0].language)
    private val _status = MutableStateFlow(GenerationStatus())
    val status: StateFlow<GenerationStatus> = _status.asStateFlow()

    private val busy = AtomicBoolean(false)
    private var job: Job? = null

    /** Saves a new question (user entry with action, parent, photo, attachments, template, reference) and asks. */
    suspend fun ask(notebookId: Long, question: Entry): StartResult = exclusive {
        ready.join()
        validate(notebookId, question)?.let { return@exclusive StartResult.Invalid(it) }
        val (user, reply) = store.insertQuestion(question) ?: return@exclusive StartResult.Invalid(context.getString(R.string.parent_missing))
        launchTurn(notebookId, user, reply)
    }

    /** New reply for an existing question; the original question is kept (spec §5). */
    suspend fun retry(notebookId: Long, userEntryId: Long): StartResult = exclusive {
        ready.join()
        val user = store.getEntry(userEntryId)?.takeIf { it.kind == EntryKind.USER }
            ?: return@exclusive StartResult.Invalid(context.getString(R.string.question_missing))
        validate(notebookId, user)?.let { return@exclusive StartResult.Invalid(it) }
        val reply = store.insertPendingReply(user.id) ?: return@exclusive StartResult.Invalid(context.getString(R.string.question_missing))
        launchTurn(notebookId, user, reply)
    }

    suspend fun summarize(notebookId: Long, lessonId: Long, templateId: Long?): StartResult = exclusive {
        ready.join()
        val template = templateId?.let { store.getTemplate(it) ?: return@exclusive StartResult.Invalid(TEMPLATE_GONE) }
        val (input, sources) = ContextBuilder.buildSummary(store.readEntries(lessonId), template?.localized(), language, prompt(PromptKind.SUMMARY, language))
            ?: return@exclusive StartResult.Invalid(context.getString(R.string.summary_empty))
        val running = GenerationStatus.Running(notebookId, lessonId, null, isSummary = true)
        _status.value = GenerationStatus(running)
        // UNDISPATCHED: the body (and its finally) always runs, even if cancelled right away.
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var message = CANCELLED
            val started = System.nanoTime()
            try {
                val reply = generate(requireConfig(lessonId), input.copy(systemText = input.systemText + "\n" + context.getString(R.string.response_language)))
                diagnostics?.record(DiagnosticOperation.SUMMARIZE, DiagnosticResult.OK, durationMs = elapsed(started))
                message = if (store.commitSummary(lessonId, reply.text, sources, templateId) != null) context.getString(R.string.summary_created) else DISCARDED
            } catch (e: CancellationException) {
                diagnostics?.record(DiagnosticOperation.SUMMARIZE, DiagnosticResult.CANCELLED, durationMs = elapsed(started))
                throw e
            } catch (e: Exception) {
                diagnostics?.recordFailure(DiagnosticOperation.SUMMARIZE, e, elapsed(started))
                message = describe(e)
            } finally {
                finish(running, message)
            }
        }
        StartResult.Started
    }

    fun cancel() {
        job?.cancel()
    }

    /** Called by every delete before touching storage (spec §4). */
    fun cancelIfAffected(notebookId: Long? = null, lessonId: Long? = null, entryIds: Set<Long> = emptySet()) {
        val running = _status.value.running ?: return
        if (running.notebookId == notebookId || running.lessonId == lessonId || running.replyId in entryIds) cancel()
    }

    private fun launchTurn(notebookId: Long, user: Entry, reply: Entry): StartResult {
        val running = GenerationStatus.Running(notebookId, user.lessonId, reply.id, isSummary = false)
        _status.value = GenerationStatus(running)
        // UNDISPATCHED: the body (and its finally) always runs, even if cancelled right away.
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var message: String? = CANCELLED
            val started = System.nanoTime()
            try {
                val entries = store.readEntries(user.lessonId, includeArchived = true)
                val reference = user.sourceEntryIds.firstOrNull()?.let { store.getEntry(it) }
                val template = user.templateId?.let { store.getTemplate(it) }
                val base = prompt(if (notebookId == com.feiyu.notes.data.NotebookStore.GENERAL_ID) PromptKind.GENERAL else PromptKind.STUDY, language)
                val input = ContextBuilder.buildTurn(user, entries, reference, template?.localized(), resolvePhoto = { photos.resolvePhoto(notebookId, it) }, language = language, base = base)
                val answer = generate(requireConfig(user.lessonId), input.copy(systemText = input.systemText + "\n" + context.getString(R.string.response_language)))
                diagnostics?.record(DiagnosticOperation.GENERATE, DiagnosticResult.OK, durationMs = elapsed(started))
                message = if (store.commitReply(reply.id, answer.text, EntryState.COMPLETE)) null else DISCARDED
            } catch (e: CancellationException) {
                diagnostics?.record(DiagnosticOperation.GENERATE, DiagnosticResult.CANCELLED, durationMs = elapsed(started))
                withContext(NonCancellable) { store.commitReply(reply.id, context.getString(R.string.cancelled), EntryState.CANCELLED) }
                throw e
            } catch (e: Exception) {
                diagnostics?.recordFailure(DiagnosticOperation.GENERATE, e, elapsed(started))
                val failure = describe(e)
                message = failure
                withContext(NonCancellable) { store.commitReply(reply.id, failure, EntryState.FAILED) }
            } finally {
                finish(running, message)
            }
        }
        return StartResult.Started
    }

    /** Re-validates every reference right before sending (spec §5 失效引用). */
    private suspend fun validate(notebookId: Long, question: Entry): String? {
        val referenceId = question.sourceEntryIds.firstOrNull()
        if (referenceId != null && store.readReferenceNotes(notebookId).none { it.id == referenceId }) {
            return context.getString(R.string.reference_gone)
        }
        if (question.templateId != null && store.getTemplate(question.templateId) == null) return TEMPLATE_GONE
        question.imagePaths.forEach { if (!photos.isUsable(notebookId, it)) return context.getString(R.string.photo_incomplete) }
        if (question.attachedImageEntryIds.isNotEmpty()) {
            val attached = store.getEntries(question.attachedImageEntryIds)
            val missing = question.attachedImageEntryIds.size - attached.count { e ->
                e.imagePaths.isNotEmpty() && e.imagePaths.all { photos.isUsable(notebookId, it) }
            }
            if (missing > 0) return context.getString(R.string.photos_missing_count, missing)
        }
        if (question.text.isBlank() && question.imagePaths.isEmpty() && question.action == EntryAction.ASK) {
            return context.getString(R.string.question_required)
        }
        return null
    }

    private fun Template.localized() =
        copy(instruction = StudyPrompts.localizedTemplate(instruction, source == Template.BUILTIN_GUIDED, language))

    private suspend fun requireConfig(lessonId: Long): AiConfig = loadConfig(lessonId) ?: throw AiError.MissingKey()

    private inline fun exclusive(block: () -> StartResult): StartResult {
        if (!busy.compareAndSet(false, true)) return StartResult.Busy
        val result = try {
            block()
        } catch (e: Throwable) {
            busy.set(false)
            throw e
        }
        if (result != StartResult.Started) busy.set(false)
        return result
    }

    /**
     * Frees the slot, then publishes the outcome only if no newer request has taken over the status.
     * [job] is left as is: cancelling a finished job is a no-op, and clearing it could drop a newer one.
     */
    private fun finish(running: GenerationStatus.Running, message: String?) {
        busy.set(false)
        _status.update { if (it.running === running) GenerationStatus(message = message) else it }
    }

    private fun elapsed(started: Long) = (System.nanoTime() - started) / 1_000_000

    private fun describe(e: Exception): String = when (e) {
        is AiError.MissingKey -> context.getString(R.string.error_key)
        is AiError.Auth -> context.getString(R.string.error_auth, e.code)
        is AiError.Quota -> context.getString(R.string.error_quota, e.code)
        is AiError.Server -> context.getString(R.string.error_server, e.code)
        is AiError.TooLarge -> context.getString(R.string.error_large)
        is AiError.Network -> context.getString(R.string.error_network)
        is AiError.EmptyAnswer -> context.getString(R.string.error_empty)
        is AiError.BadResponse -> context.getString(R.string.error_response)
        is AiError.ImageUnreadable -> context.getString(R.string.error_image)
        else -> context.getString(R.string.generation_failed)
    }

    private val DISCARDED get() = context.getString(R.string.discarded)
    private val CANCELLED get() = context.getString(R.string.cancelled)
    private val TEMPLATE_GONE get() = context.getString(R.string.template_gone)
}
