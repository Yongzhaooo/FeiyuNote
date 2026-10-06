package com.feiyu.notes.ui

import com.feiyu.notes.R
import com.feiyu.notes.app
import com.feiyu.notes.support.DiagnosticOperation
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.feiyu.notes.data.EntryKind
import com.feiyu.notes.data.NotebookStore
import com.feiyu.notes.export.NoteExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NoteScreen(
    store: NotebookStore,
    notebookId: Long,
    lessonId: Long,
    noteId: Long,
    onBack: () -> Unit,
    onOpenSource: (Long) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val note by rememberStoreValue(store, noteId) { getEntry(noteId)?.takeIf { it.kind == EntryKind.NOTE } }
    val notebook by rememberStoreValue(store, notebookId) { getNotebook(notebookId) }
    val lesson by rememberStoreValue(store, lessonId) { getLesson(lessonId) }
    val sources by rememberStoreValue(store, note?.sourceEntryIds) {
        note?.sourceEntryIds.orEmpty().let { ids -> ids to getEntries(ids).associateBy { it.id } }
    }
    var text by rememberSaveable(noteId) { mutableStateOf<String?>(null) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable(noteId) { mutableStateOf(false) }
    var addingToReview by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(note?.id) { if (text == null) note?.let { text = it.text } }

    val notebookName = if (notebook?.id == com.feiyu.notes.data.NotebookStore.GENERAL_ID) context.getString(R.string.general_chat) else notebook?.name.orEmpty()
    val lessonName = lesson?.let { lessonTitle(context, it) }.orEmpty()
    fun html(): String = NoteExporter.renderNote(
        notebookName, lessonName, note?.text.orEmpty(), LocalDate.now().toString(), context.resources.configuration.locales[0].language,
        formulaImage = com.feiyu.notes.math.MathRenderer::dataUri,
    )
    val fileName = NoteExporter.fileName(notebookName, lessonName)
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        if (uri != null) scope.launch {
            message = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)!!.use { it.write(html().toByteArray(Charsets.UTF_8)) }
                }
            }.fold({ context.getString(R.string.exported) }, {
                context.app.diagnostics.recordFailure(DiagnosticOperation.NOTE_EXPORT, it)
                context.getString(R.string.export_failed)
            })
        }
    }
    val dirty = note != null && text != null && text != note?.text

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.note_number, noteId)) },
                navigationIcon = { ActionIcon(R.drawable.ic_back, context.getString(R.string.back), onBack) },
            )
        },
    ) { padding ->
        val current = note
        if (current == null) {
            Text(context.getString(R.string.note_deleted), Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        Column(
            Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp).widthIn(max = 840.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (editing) OutlinedTextField(
                value = text.orEmpty(),
                onValueChange = { text = it },
                label = { Text(context.getString(R.string.note_content)) },
                minLines = 8,
                modifier = Modifier.fillMaxWidth().testTag("note-editor"),
            ) else MathContent(text.orEmpty())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { editing = !editing }) {
                    Text(context.getString(if (editing) R.string.preview_note else R.string.edit_note))
                }
                Button(enabled = dirty, onClick = {
                    scope.launch { message = if (store.updateNoteText(noteId, text.orEmpty())) context.getString(R.string.saved) else context.getString(R.string.save_failed) }
                }) { Text(context.getString(R.string.save)) }
                OutlinedButton(enabled = !dirty, onClick = { saveLauncher.launch(fileName) }) { Text(context.getString(R.string.export_html)) }
                OutlinedButton(enabled = !dirty, onClick = {
                    scope.launch {
                        runCatching {
                            val file = withContext(Dispatchers.IO) {
                                File(context.cacheDir, "exports").apply { mkdirs() }.resolve(fileName).apply { writeText(html()) }
                            }
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
                            val send = Intent(Intent.ACTION_SEND)
                                .setType("text/html")
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(Intent.createChooser(send, context.getString(R.string.share_note)))
                        }.onFailure {
                            context.app.diagnostics.recordFailure(DiagnosticOperation.NOTE_SHARE, it)
                            message = context.getString(R.string.share_failed)
                        }
                    }
                }) { Text(context.getString(R.string.share)) }
                if (notebook?.kind == com.feiyu.notes.data.NotebookKind.COURSE && notebookId != com.feiyu.notes.data.NotebookStore.GENERAL_ID) {
                    OutlinedButton(
                        onClick = { addingToReview = true },
                        modifier = Modifier.testTag("note-add-to-review"),
                    ) {
                        Text(context.getString(R.string.add_to_review))
                    }
                }
                OutlinedButton(onClick = { confirmDelete = true }) { Text(context.getString(R.string.delete_note)) }
            }
            if (dirty) Text(context.getString(R.string.unsaved_note), style = MaterialTheme.typography.bodySmall)
            message?.let { Text(it) }
            Text(context.getString(R.string.sources), style = MaterialTheme.typography.titleSmall)
            val (ids, byId) = sources ?: (emptyList<Long>() to emptyMap())
            if (ids.isEmpty()) Text(context.getString(R.string.none), style = MaterialTheme.typography.bodySmall)
            ids.forEach { id ->
                val source = byId[id]
                Text(
                    text = if (source == null) context.getString(R.string.deleted_source, id) else "#$id ${source.text.take(40).ifBlank { context.getString(R.string.photo_placeholder) }}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = if (source == null) Modifier else Modifier.clickable { onOpenSource(id) },
                    color = if (source == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    if (confirmDelete) ConfirmDialog(
        title = context.getString(R.string.delete_note_title),
        text = context.getString(R.string.delete_note_body),
        onConfirm = { scope.launch { if (store.deleteNote(noteId)) onBack() } },
        onDismiss = { confirmDelete = false },
    )
    val activeNote = note
    if (addingToReview && activeNote != null) {
        val (defaultTopic, defaultNotes) = ReviewDefaults.fromNote(
            noteText = activeNote.text,
            fallbackTitle = context.getString(R.string.note_number, noteId),
        )

        ReviewRecordEditDialog(
            title = context.getString(R.string.add_to_review),
            initialTopic = defaultTopic,
            initialNotes = defaultNotes,
            sourceEntryId = noteId,
            onSave = { topic, notes ->
                val result = store.insertReviewRecord(
                    notebookId = notebookId,
                    topic = topic,
                    notes = notes,
                    sourceEntryId = noteId,
                )
                when (result) {
                    is com.feiyu.notes.data.ReviewInsertResult.Success -> {
                        message = context.getString(R.string.added_to_review)
                        null
                    }
                    is com.feiyu.notes.data.ReviewInsertResult.AlreadyExists -> context.getString(R.string.already_in_review)
                    is com.feiyu.notes.data.ReviewInsertResult.SourceNotFound -> context.getString(R.string.source_not_found)
                    is com.feiyu.notes.data.ReviewInsertResult.InvalidCourse -> context.getString(R.string.save_failed)
                    is com.feiyu.notes.data.ReviewInsertResult.Failed -> context.getString(R.string.save_failed)
                }
            },
            onDismiss = { addingToReview = false },
        )
    }
}
