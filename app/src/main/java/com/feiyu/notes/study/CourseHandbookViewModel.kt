package com.feiyu.notes.study

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feiyu.notes.FeiyuApp
import com.feiyu.notes.R
import com.feiyu.notes.data.*
import com.feiyu.notes.settings.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CourseHandbookViewModel(private val app: FeiyuApp, val notebookId: Long, private val handle: SavedStateHandle) : ViewModel() {
    data class Data(val notebook: Notebook?, val document: CourseDocument?, val sections: List<CourseSection>, val bookmarks: List<CourseBookmark>, val reading: List<String>)
    val data = app.store.changes.map {
        val document = app.store.getCourseDocument(notebookId)
        val reading = withContext(Dispatchers.Default) { document?.let { CourseMarkdown.sections(it.markdown).map { section -> section.readingMarkdown } }.orEmpty() }
        Data(app.store.getNotebook(notebookId), document, app.store.readCourseSections(notebookId), app.store.readCourseBookmarks(notebookId), reading)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val draft = handle.getStateFlow("documentDraft", "")
    val editing = handle.getStateFlow("editingDocument", false)
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val context get() = AppLanguage.context(app)

    fun edit() {
        if (_busy.value || editing.value) return
        handle["documentDraft"] = data.value?.document?.markdown.orEmpty()
        handle["revision"] = data.value?.document?.revision
        handle["editingDocument"] = true
        _error.value = null
    }
    fun setDraft(value: String) { handle["documentDraft"] = value }
    fun cancelEdit() { if (!_busy.value) handle["editingDocument"] = false }

    fun import(uri: Uri) = viewModelScope.launch {
        if (_busy.value) return@launch
        _busy.value = true
        try {
            val markdown = withContext(Dispatchers.IO) {
                app.contentResolver.openInputStream(uri)!!.use { CourseMarkdown.decode(it.readBytesBounded()) }
            }
            if (!editing.value) handle["revision"] = data.value?.document?.revision
            handle["documentDraft"] = markdown
            handle["editingDocument"] = true // Preview/edit before replacing the saved source.
            _error.value = null
        } catch (e: Exception) { _error.value = context.getString(R.string.document_import_failed) }
        finally { _busy.value = false }
    }

    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) { val n = read(buffer); if (n < 0) break; require(bytes.size() + n <= CourseMarkdown.MAX_BYTES); bytes.write(buffer, 0, n) }
        return bytes.toByteArray()
    }

    fun save() = viewModelScope.launch {
        if (_busy.value) return@launch
        _busy.value = true
        try {
            val ok = app.store.saveCourseDocument(notebookId, draft.value, handle.get<Long>("revision"))
            if (ok) { handle["editingDocument"] = false; _error.value = null }
            else _error.value = context.getString(R.string.document_save_conflict)
        } catch (e: Exception) { _error.value = context.getString(R.string.document_save_failed) }
        finally { _busy.value = false }
    }

    fun delete() = viewModelScope.launch {
        if (_busy.value) return@launch
        _busy.value = true
        try {
            app.generator.cancelIfAffected(notebookId = notebookId)
            if (!app.store.deleteCourseDocument(notebookId)) _error.value = context.getString(R.string.save_failed)
        } catch (e: Exception) { _error.value = context.getString(R.string.save_failed) }
        finally { _busy.value = false }
    }
    fun bookmark(sectionId: Long) = viewModelScope.launch {
        try { if (!app.store.addCourseBookmark(notebookId, sectionId)) _error.value = context.getString(R.string.save_failed) }
        catch (e: Exception) { _error.value = context.getString(R.string.save_failed) }
    }
    fun removeBookmark(id: Long) = viewModelScope.launch {
        try { if (!app.store.deleteCourseBookmark(notebookId, id)) _error.value = context.getString(R.string.save_failed) }
        catch (e: Exception) { _error.value = context.getString(R.string.save_failed) }
    }
    fun setEnabled(enabled: Boolean) = viewModelScope.launch {
        try {
            if (!enabled) app.generator.cancelIfAffected(notebookId = notebookId)
            if (!app.store.setCourseKnowledgeEnabled(notebookId, enabled)) _error.value = context.getString(R.string.save_failed)
        } catch (e: Exception) { _error.value = context.getString(R.string.save_failed) }
    }
}
