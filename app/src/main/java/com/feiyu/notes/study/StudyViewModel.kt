package com.feiyu.notes.study

import com.feiyu.notes.R
import com.feiyu.notes.support.DiagnosticOperation
import com.feiyu.notes.support.DiagnosticResult
import com.feiyu.notes.support.ErrorKind
import com.feiyu.notes.settings.AppLanguage
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feiyu.notes.FeiyuApp
import com.feiyu.notes.data.Entry
import com.feiyu.notes.data.EntryAction
import com.feiyu.notes.data.EntryKind
import com.feiyu.notes.data.EntryState
import com.feiyu.notes.data.Lesson
import com.feiyu.notes.data.Mastery
import com.feiyu.notes.data.Notebook
import com.feiyu.notes.data.Template
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * UI state of one lesson screen: draft, pending photos, reply target and selections.
 * Everything a user typed or picked lives in [SavedStateHandle]; requests belong to [Generator].
 */
class StudyViewModel(
    private val app: FeiyuApp,
    val notebookId: Long,
    val lessonId: Long,
    private val handle: SavedStateHandle,
) : ViewModel() {
    private val context get() = AppLanguage.context(app)
    private val store = app.store
    private val generator = app.generator
    private val photos = app.photos

    data class Data(
        val notebook: Notebook?,
        val lesson: Lesson?,
        /** All entries including archived threads, in creation order. */
        val entries: List<Entry>,
        val referenceNotes: List<Entry>,
        val templates: List<Template>,
    )

    val data: StateFlow<Data?> = store.changes
        .map { load() }
        .onEach(::pruneSelections)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val status = generator.status

    val draft = handle.getStateFlow(DRAFT, "")
    val action = handle.getStateFlow(ACTION, EntryAction.ASK.name)
    val parentId = handle.getStateFlow(PARENT, NONE)
    val photoNames = handle.getStateFlow(PHOTOS, arrayListOf<String>())
    private val _importing = MutableStateFlow(false)
    val importing = _importing.asStateFlow()
    private val _sending = MutableStateFlow(false)
    val sending = _sending.asStateFlow()
    val attached = handle.getStateFlow(ATTACHED, LongArray(0))
    val referenceId = handle.getStateFlow(REFERENCE, NONE)
    val templateOverride = handle.getStateFlow(TEMPLATE_OVERRIDE, false)
    val templateChoice = handle.getStateFlow(TEMPLATE, NONE)

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    init {
        app.prefs.setLastLesson(notebookId, lessonId)
    }

    fun setDraft(value: String) { handle[DRAFT] = value }
    fun dismissNotice() { _notice.value = null }

    /** Reply to an assistant entry (null = new top-level question) with the given action. */
    fun setTarget(parent: Long?, action: EntryAction = EntryAction.ASK) {
        if ((parent ?: NONE) != parentId.value) handle[ATTACHED] = LongArray(0)
        handle[PARENT] = parent ?: NONE
        handle[ACTION] = action.name
    }

    fun toggleAttached(entryId: Long) {
        val current = attached.value.toMutableSet()
        if (!current.add(entryId)) current.remove(entryId)
        handle[ATTACHED] = current.toLongArray()
    }

    fun setReference(noteId: Long?) { handle[REFERENCE] = noteId ?: NONE }

    fun setTemplate(templateId: Long?) {
        handle[TEMPLATE_OVERRIDE] = true
        handle[TEMPLATE] = templateId ?: NONE
    }

    /** The template this send would use: an explicit choice, else the notebook default. */
    fun effectiveTemplateId(): Long? =
        if (templateOverride.value) templateChoice.value.orNull() else data.value?.notebook?.defaultTemplateId

    // ---- camera ----

    /** Allocates the capture target; remembered across process death so the result keeps this lesson. */
    fun newCaptureUri(): Uri {
        val file = photos.allocatePhoto(notebookId)
        handle[CAPTURING] = file.name
        return FileProvider.getUriForFile(app, "${app.packageName}.photos", file)
    }

    fun onCaptureResult(success: Boolean) {
        val name = handle.get<String>(CAPTURING) ?: return
        handle[CAPTURING] = null
        if (success && photos.isUsable(notebookId, name)) {
            handle[PHOTOS] = ArrayList(photoNames.value + name)
        } else {
            photos.deletePhotos(notebookId, listOf(name)) // only this capture's empty temp file
        }
    }

    /** Copy selected images sequentially; append usable files and clean up failed/cancelled copies. */
    fun importPhotos(uris: List<Uri>) = viewModelScope.launch {
        if (_importing.value || _sending.value || uris.isEmpty()) return@launch
        _importing.value = true
        var failed = 0
        try {
            for (uri in uris.distinct()) {
                var file: File? = null
                var adopted = false
                try {
                    file = photos.allocatePhoto(notebookId)
                    val target = file
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            app.contentResolver.openInputStream(uri)!!.use { input ->
                                target.outputStream().use { input.copyTo(it) }
                            }
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(target.path, bounds)
                            bounds.outWidth > 0 && bounds.outHeight > 0
                        }.getOrDefault(false)
                    }
                    if (ok) {
                        handle[PHOTOS] = ArrayList(photoNames.value + target.name)
                        adopted = true
                    } else failed++
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                } finally {
                    if (!adopted) file?.delete()
                }
            }
            if (failed > 0) _notice.value = context.getString(R.string.image_import_failed_count, failed)
            app.diagnostics.record(
                DiagnosticOperation.IMAGE_IMPORT,
                if (failed > 0) DiagnosticResult.FAILED else DiagnosticResult.OK,
                error = ErrorKind.IMAGE_UNREADABLE.takeIf { failed > 0 },
            )
        } finally {
            _importing.value = false
        }
    }

    fun removePhoto(name: String) {
        if (_importing.value || _sending.value || name !in photoNames.value) return
        handle[PHOTOS] = ArrayList(photoNames.value - name)
        photos.deletePhotos(notebookId, listOf(name))
    }

    fun photoFiles(entry: Entry): List<File> = entry.imagePaths.map { photos.resolvePhoto(notebookId, it) }
    fun pendingPhotoFile(name: String): File = photos.resolvePhoto(notebookId, name)

    // ---- generation ----

    fun send() = viewModelScope.launch {
        if (_importing.value || _sending.value) return@launch
        _sending.value = true
        try {
            val act = EntryAction.valueOf(action.value)
            val question = Entry(
                id = 0,
                lessonId = lessonId,
                kind = EntryKind.USER,
                action = act,
                text = draft.value.trim(),
                parentEntryId = parentId.value.orNull() ?: if (notebookId == com.feiyu.notes.data.NotebookStore.GENERAL_ID)
                    store.readEntries(lessonId).lastOrNull { it.kind == EntryKind.ASSISTANT && it.state == EntryState.COMPLETE }?.id else null,
                sourceEntryIds = listOfNotNull(referenceId.value.orNull()),
                imagePaths = photoNames.value.toList(),
                attachedImageEntryIds = attached.value.toList(),
                templateId = effectiveTemplateId(),
            )
            if (act == EntryAction.MISTAKE && question.text.isBlank() && question.imagePaths.isEmpty()) {
                _notice.value = context.getString(R.string.solution_required)
                return@launch
            }
            when (val result = generator.ask(notebookId, question)) {
                StartResult.Started -> {
                    handle[DRAFT] = ""
                    handle[PHOTOS] = arrayListOf<String>()
                    handle[ATTACHED] = LongArray(0)
                    handle[PARENT] = NONE
                    handle[ACTION] = EntryAction.ASK.name
                }
                StartResult.Busy -> _notice.value = BUSY
                is StartResult.Invalid -> _notice.value = result.message
            }
        } finally {
            _sending.value = false
        }
    }

    /** Retries with the question's own template; [templateId] replaces it when the old one is gone. */
    fun retry(userEntryId: Long, replaceTemplate: Boolean = false, templateId: Long? = null) = viewModelScope.launch {
        if (replaceTemplate) store.setEntryTemplate(userEntryId, templateId)
        report(generator.retry(notebookId, userEntryId))
    }

    fun summarize(templateId: Long?) = viewModelScope.launch {
        report(generator.summarize(notebookId, lessonId, templateId))
    }

    fun cancel() = generator.cancel()

    suspend fun addToReview(sourceEntryId: Long?, topic: String, notes: String): String? {
        val result = store.insertReviewRecord(
            notebookId = notebookId,
            topic = topic,
            notes = notes,
            sourceEntryId = sourceEntryId,
        )
        return when (result) {
            is com.feiyu.notes.data.ReviewInsertResult.Success -> {
                _notice.value = context.getString(R.string.added_to_review)
                null
            }
            is com.feiyu.notes.data.ReviewInsertResult.AlreadyExists -> context.getString(R.string.already_in_review)
            is com.feiyu.notes.data.ReviewInsertResult.SourceNotFound -> context.getString(R.string.source_not_found)
            is com.feiyu.notes.data.ReviewInsertResult.InvalidCourse -> context.getString(R.string.save_failed)
            is com.feiyu.notes.data.ReviewInsertResult.Failed -> context.getString(R.string.save_failed)
        }
    }

    // ---- threads ----

    fun setMastery(rootId: Long, mastery: Mastery) = viewModelScope.launch { store.setMastery(rootId, mastery) }

    fun setArchived(rootId: Long, archived: Boolean) = viewModelScope.launch { store.setArchived(rootId, archived) }

    fun deleteThread(rootId: Long) = viewModelScope.launch {
        val ids = threadIds(rootId)
        generator.cancelIfAffected(entryIds = ids)
        store.deleteThread(rootId)
    }

    fun threadIds(rootId: Long): Set<Long> {
        val entries = data.value?.entries.orEmpty()
        val ids = mutableSetOf(rootId)
        var grew = true
        while (grew) {
            grew = false
            for (e in entries) if (e.parentEntryId in ids && ids.add(e.id)) grew = true
        }
        return ids
    }

    /** User entries with photos on the chain a follow-up to [parentId] would continue. */
    fun chainPhotos(): List<Entry> {
        val all = data.value?.entries.orEmpty()
        val parent = all.firstOrNull { it.id == parentId.value } ?: return emptyList()
        return (ContextBuilder.ancestors(parent, all) + parent).filter { it.kind == EntryKind.USER && it.imagePaths.isNotEmpty() }
    }

    private fun report(result: StartResult) {
        when (result) {
            StartResult.Started -> Unit
            StartResult.Busy -> _notice.value = BUSY
            is StartResult.Invalid -> _notice.value = result.message
        }
    }

    private suspend fun load(): Data = Data(
        notebook = store.getNotebook(notebookId),
        lesson = store.getLesson(lessonId),
        entries = store.readEntries(lessonId, includeArchived = true),
        referenceNotes = store.readReferenceNotes(notebookId),
        templates = store.listTemplates(),
    )

    /** Cached selections never outlive what they point to (spec §5 失效引用). */
    private fun pruneSelections(d: Data) {
        val byId = d.entries.associateBy { it.id }
        val parent = parentId.value.orNull()
        if (parent != null && byId[parent]?.let { it.kind == EntryKind.ASSISTANT && it.state == EntryState.COMPLETE } != true) {
            handle[PARENT] = NONE
            handle[ACTION] = EntryAction.ASK.name
            handle[ATTACHED] = LongArray(0)
            _notice.value = context.getString(R.string.parent_removed)
        }
        val validPhotos = chainPhotos().map { it.id }.toSet()
        val kept = attached.value.filter { it in validPhotos && byId[it]?.imagePaths?.all { p -> photos.isUsable(notebookId, p) } == true }
        if (kept.size != attached.value.size) {
            handle[ATTACHED] = kept.toLongArray()
            _notice.value = context.getString(R.string.photos_removed)
        }
        referenceId.value.orNull()?.let { ref ->
            if (d.referenceNotes.none { it.id == ref }) {
                handle[REFERENCE] = NONE
                _notice.value = context.getString(R.string.reference_removed)
            }
        }
        if (templateOverride.value) templateChoice.value.orNull()?.let { t ->
            if (d.templates.none { it.id == t }) {
                handle[TEMPLATE_OVERRIDE] = false
                handle[TEMPLATE] = NONE
                _notice.value = context.getString(R.string.template_removed)
            }
        }
        // Keep missing draft attachments visible; send validation must not silently drop them.
    }

    private fun Long.orNull(): Long? = takeIf { it != NONE }

    private val BUSY get() = context.getString(R.string.busy)

    private companion object {
        const val NONE = -1L
        const val DRAFT = "draft"
        const val ACTION = "action"
        const val PARENT = "parent"
        const val PHOTOS = "photos"
        const val CAPTURING = "capturing"
        const val ATTACHED = "attached"
        const val REFERENCE = "reference"
        const val TEMPLATE = "template"
        const val TEMPLATE_OVERRIDE = "templateOverride"
    }
}
