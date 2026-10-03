package com.feiyu.notes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.feiyu.notes.R
import com.feiyu.notes.data.EntryKind
import com.feiyu.notes.data.NotebookStore
import com.feiyu.notes.data.ReviewInsertResult
import com.feiyu.notes.data.ReviewRecord
import com.feiyu.notes.data.ReviewStatus
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CourseReviewScreen(
    store: NotebookStore,
    notebookId: Long,
    onBack: () -> Unit,
    onNavigateToSource: (ReviewSourceDestination) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notebook by rememberStoreValue(store, notebookId) { getNotebook(notebookId) }
    val records by rememberStoreValue(store, notebookId) { listReviewRecords(notebookId) }

    var statusFilter by rememberSaveable { mutableStateOf<ReviewStatus?>(null) }
    var addingRecord by rememberSaveable { mutableStateOf(false) }
    var editingRecordId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }

    val editingRecord = remember(records, editingRecordId) {
        records?.firstOrNull { it.id == editingRecordId }
    }

    val filteredRecords = remember(records, statusFilter) {
        val list = records.orEmpty()
        if (statusFilter == null) list else list.filter { it.status == statusFilter }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(context.getString(R.string.course_review), style = MaterialTheme.typography.titleMedium)
                        notebook?.name?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    ActionIcon(R.drawable.ic_back, context.getString(R.string.back), onBack)
                },
                actions = {
                    TextButton(onClick = { addingRecord = true }) {
                        Text(context.getString(R.string.new_review_record))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Status filters
            FlowRow(
                Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = statusFilter == null,
                    onClick = { statusFilter = null },
                    label = { Text(context.getString(R.string.filter_all)) },
                )
                FilterChip(
                    selected = statusFilter == ReviewStatus.PENDING,
                    onClick = { statusFilter = ReviewStatus.PENDING },
                    label = { Text(context.getString(R.string.status_pending)) },
                )
                FilterChip(
                    selected = statusFilter == ReviewStatus.CONFUSED,
                    onClick = { statusFilter = ReviewStatus.CONFUSED },
                    label = { Text(context.getString(R.string.status_confused)) },
                )
                FilterChip(
                    selected = statusFilter == ReviewStatus.UNDERSTOOD,
                    onClick = { statusFilter = ReviewStatus.UNDERSTOOD },
                    label = { Text(context.getString(R.string.status_understood)) },
                )
            }

            if (records?.isEmpty() == true) {
                Box(
                    Modifier
                        .weight(1f)
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        context.getString(R.string.review_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            } else {
                LazyColumn(
                    Modifier
                        .widthIn(max = 840.dp)
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filteredRecords, key = { it.id }) { record ->
                        ReviewRecordCard(
                            record = record,
                            onStatusChange = { newStatus ->
                                scope.launch {
                                    val ok = store.setReviewStatus(notebookId, record.id, newStatus)
                                    if (!ok) {
                                        notice = context.getString(R.string.save_failed)
                                    }
                                }
                            },
                            onEdit = { editingRecordId = record.id },
                            onDelete = { deletingId = record.id },
                            onJumpToSource = {
                                val sourceId = record.sourceEntryId ?: return@ReviewRecordCard
                                scope.launch {
                                    val entry = store.getEntry(sourceId)
                                    if (entry != null) {
                                        val destination = when {
                                            entry.kind == EntryKind.NOTE ->
                                                ReviewSourceDestination.Note(entry.lessonId, entry.id)
                                            store.isThreadArchived(entry.id) ->
                                                ReviewSourceDestination.Archived(entry.lessonId)
                                            else ->
                                                ReviewSourceDestination.Chat(entry.lessonId, entry.id)
                                        }
                                        onNavigateToSource(destination)
                                    } else {
                                        notice = context.getString(R.string.source_not_found)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (addingRecord) {
        ReviewRecordEditDialog(
            title = context.getString(R.string.new_review_record),
            initialTopic = "",
            initialNotes = "",
            sourceEntryId = null,
            onSave = { topic, notes ->
                when (val res = store.insertReviewRecord(notebookId, topic, notes)) {
                    is ReviewInsertResult.Success -> null
                    is ReviewInsertResult.AlreadyExists -> context.getString(R.string.already_in_review)
                    is ReviewInsertResult.SourceNotFound -> context.getString(R.string.source_not_found)
                    else -> context.getString(R.string.save_failed)
                }
            },
            onDismiss = { addingRecord = false },
        )
    }

    editingRecord?.let { record ->
        ReviewRecordEditDialog(
            title = context.getString(R.string.edit_review_record),
            initialTopic = record.topic,
            initialNotes = record.notes,
            sourceEntryId = record.sourceEntryId,
            onSave = { topic, notes ->
                val ok = store.updateReviewRecord(notebookId, record.id, topic, notes)
                if (ok) null else context.getString(R.string.save_failed)
            },
            onDismiss = { editingRecordId = null },
        )
    }

    deletingId?.let { id ->
        ConfirmDialog(
            title = context.getString(R.string.delete_review_record),
            text = context.getString(R.string.delete_review_confirm),
            onConfirm = {
                scope.launch {
                    val ok = store.deleteReviewRecord(notebookId, id)
                    if (!ok) {
                        notice = context.getString(R.string.delete_failed)
                    }
                    deletingId = null
                }
            },
            onDismiss = { deletingId = null },
        )
    }

    notice?.let { msg ->
        AlertDialog(
            onDismissRequest = { notice = null },
            title = { Text(context.getString(R.string.notice)) },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { notice = null }) { Text(context.getString(R.string.dismiss)) } },
        )
    }
}

@Composable
private fun ReviewRecordCard(
    record: ReviewRecord,
    onStatusChange: (ReviewStatus) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onJumpToSource: () -> Unit,
) {
    val context = LocalContext.current
    var statusMenu by remember { mutableStateOf(false) }
    val formattedTime = remember(record.updatedAt) {
        Instant.ofEpochMilli(record.updatedAt)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
    }

    Card(
        Modifier
            .fillMaxWidth()
            .testTag("review-record-${record.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Header: Topic and Status Chip
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = record.topic,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )

                Box {
                    val statusText = when (record.status) {
                        ReviewStatus.PENDING -> context.getString(R.string.status_pending)
                        ReviewStatus.UNDERSTOOD -> context.getString(R.string.status_understood)
                        ReviewStatus.CONFUSED -> context.getString(R.string.status_confused)
                    }
                    val statusColor = when (record.status) {
                        ReviewStatus.PENDING -> MaterialTheme.colorScheme.primary
                        ReviewStatus.UNDERSTOOD -> MaterialTheme.colorScheme.tertiary
                        ReviewStatus.CONFUSED -> MaterialTheme.colorScheme.error
                    }

                    SuggestionChip(
                        onClick = { statusMenu = true },
                        modifier = Modifier.testTag("record-status-chip"),
                        label = { Text(statusText) },
                        colors = SuggestionChipDefaults.suggestionChipColors(labelColor = statusColor),
                    )

                    DropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.status_pending)) },
                            onClick = { statusMenu = false; onStatusChange(ReviewStatus.PENDING) },
                        )
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.status_confused)) },
                            onClick = { statusMenu = false; onStatusChange(ReviewStatus.CONFUSED) },
                        )
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.status_understood)) },
                            modifier = Modifier.testTag("status-menu-item-understood"),
                            onClick = { statusMenu = false; onStatusChange(ReviewStatus.UNDERSTOOD) },
                        )
                    }
                }
            }

            // Notes body
            if (record.notes.isNotBlank()) {
                Text(
                    text = record.notes,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Text(
                text = formattedTime,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Source indicator and actions
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    record.sourceDeleted -> {
                        Text(
                            context.getString(R.string.review_source_deleted),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    record.sourceEntryId != null -> {
                        TextButton(
                            onClick = onJumpToSource,
                            modifier = Modifier.testTag("jump-to-source"),
                        ) {
                            Text(context.getString(R.string.review_source, record.sourceEntryId) + " · " + context.getString(R.string.back_to_source))
                        }
                    }
                    else -> {
                        Text(
                            context.getString(R.string.review_manual),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onEdit) {
                        Text(context.getString(R.string.edit))
                    }
                    TextButton(onClick = onDelete) {
                        Text(context.getString(R.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
fun ReviewRecordEditDialog(
    title: String,
    initialTopic: String,
    initialNotes: String,
    sourceEntryId: Long?,
    onSave: suspend (topic: String, notes: String) -> String?,
    onDismiss: () -> Unit,
    state: ReviewDialogState = rememberReviewDialogState(initialTopic, initialNotes, sourceEntryId),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.sourceEntryId != null) {
                    Text(
                        context.getString(R.string.review_source, state.sourceEntryId),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                OutlinedTextField(
                    value = state.topic,
                    onValueChange = {
                        state.topic = it
                        state.errorMessage = null
                    },
                    label = { Text(context.getString(R.string.review_topic)) },
                    singleLine = true,
                    enabled = !state.saving,
                    isError = state.errorMessage != null,
                    modifier = Modifier.fillMaxWidth().testTag("review-topic-input"),
                )
                OutlinedTextField(
                    value = state.notes,
                    onValueChange = {
                        state.notes = it
                        state.errorMessage = null
                    },
                    label = { Text(context.getString(R.string.review_notes)) },
                    minLines = 3,
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth().testTag("review-notes-input"),
                )
                if (state.errorMessage != null) {
                    Text(
                        text = state.errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("review-error-message"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = state.canSave,
                onClick = {
                    scope.launch {
                        val ok = state.submit(context.getString(R.string.save_failed), onSave)
                        if (ok) {
                            onDismiss()
                        }
                    }
                },
                modifier = Modifier.testTag("review-dialog-save"),
            ) {
                Text(context.getString(if (state.saving) R.string.saving else R.string.save))
            }
        },
        dismissButton = {
            TextButton(
                enabled = !state.saving,
                onClick = onDismiss,
                modifier = Modifier.testTag("review-dialog-cancel"),
            ) {
                Text(context.getString(R.string.cancel))
            }
        },
    )
}
