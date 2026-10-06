package com.feiyu.notes.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feiyu.notes.R
import com.feiyu.notes.support.FeedbackClient
import com.feiyu.notes.support.FeedbackFailure
import com.feiyu.notes.support.UpdateClient
import com.feiyu.notes.support.UpdateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

const val QQ_GROUP = "1079399140"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SupportScreen(vm: SupportViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    var notice by remember { mutableStateOf<String?>(null) }
    val sending = status == FeedbackStatus.Sending
    val (versionName, versionCode) = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() to UpdateClient.installedVersionCode(context)
    }

    fun copy(text: String, message: String = context.getString(R.string.copied)) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Feiyu Notes", text))
        notice = message
    }

    fun share(p: FeedbackPreview) {
        val text = p.shareText(context.getString(R.string.preview_diagnostics_label))
        scope.launch {
            val ok = runCatching {
                val file = withContext(Dispatchers.IO) { writeFeedbackExport(context, text) }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, p.description)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, context.getString(R.string.share_feedback_title)))
            }.onFailure { e ->
                // No receiving app (or no chooser): fall back to the clipboard.
                if (e is ActivityNotFoundException || e is SecurityException) copy(text, context.getString(R.string.share_unavailable))
                else notice = context.getString(R.string.share_failed)
            }.isSuccess
            if (ok) notice = context.getString(R.string.share_handed_off)
            vm.recordShare(ok)
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(context.getString(R.string.support_title)) },
            navigationIcon = { ActionIcon(R.drawable.ic_back, context.getString(R.string.back), onBack) },
            colors = feiyuTopBarColors(),
        )
    }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                SettingsSection(context.getString(R.string.support_version_title), R.drawable.ic_sparkle, Accent.INDIGO) {
                    Text(context.getString(R.string.version_line, versionName, versionCode), Modifier.testTag("app-version"))
                    Button(enabled = update != UpdateState.Checking, onClick = vm::checkUpdate, modifier = Modifier.testTag("check-update")) {
                        Text(context.getString(if (update == UpdateState.Checking) R.string.checking_update else R.string.check_update))
                    }
                    (update as? UpdateState.Done)?.result?.let { result ->
                        UpdateResultView(result, onOpen = { url ->
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                .onFailure { notice = context.getString(R.string.browser_unavailable) }
                        })
                    }
                }

                SettingsSection(context.getString(R.string.feedback_title), R.drawable.ic_pencil, Accent.BLUSH) {
                    Text(context.getString(R.string.feedback_notice), style = MaterialTheme.typography.bodySmall)
                    val count = draft.description.trim().let { it.codePointCount(0, it.length) }
                    OutlinedTextField(
                        value = draft.description,
                        onValueChange = { vm.edit(description = it) },
                        enabled = !sending,
                        label = { Text(context.getString(R.string.feedback_description)) },
                        supportingText = { Text(context.getString(R.string.feedback_counter, count, FeedbackClient.MAX_DESCRIPTION)) },
                        isError = count > FeedbackClient.MAX_DESCRIPTION,
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth().testTag("feedback-description"),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = draft.includeDiagnostics,
                            onCheckedChange = { vm.edit(includeDiagnostics = it) },
                            enabled = !sending,
                            modifier = Modifier.testTag("attach-diagnostics"),
                        )
                        Text(context.getString(R.string.attach_diagnostics))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !sending && (draft.pending != null || FeedbackClient.isValidDescription(draft.description)),
                            onClick = vm::openPreview,
                            modifier = Modifier.testTag("preview-feedback"),
                        ) { Text(context.getString(if (draft.pending != null) R.string.retry_feedback else R.string.preview_feedback)) }
                        if (draft.description.isNotEmpty() || draft.pending != null) {
                            TextButton(enabled = !sending, onClick = vm::discard) { Text(context.getString(R.string.discard_feedback)) }
                        }
                    }
                    status?.let { FeedbackStatusView(it, onCopyId = { copy(it) }) }
                }

                SettingsSection(context.getString(R.string.discussion_title), R.drawable.ic_heart, Accent.SEA) {
                    SelectionContainer { Text(context.getString(R.string.qq_group, QQ_GROUP), Modifier.testTag("qq-group"), style = MaterialTheme.typography.titleSmall) }
                    Text(context.getString(R.string.qq_group_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { copy(QQ_GROUP) }) { Text(context.getString(R.string.copy_group_number)) }
                }
                notice?.let { Text(it, Modifier.testTag("support-notice"), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    preview?.let { p ->
        AlertDialog(
            onDismissRequest = vm::closePreview,
            title = { Text(context.getString(R.string.feedback_preview_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(context.getString(R.string.preview_description_label), style = MaterialTheme.typography.titleSmall)
                    Text(p.description, Modifier.testTag("preview-description"))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { share(p) }, modifier = Modifier.testTag("share-feedback")) { Text(context.getString(R.string.share)) }
                        OutlinedButton(onClick = { copy(p.shareText(context.getString(R.string.preview_diagnostics_label))) }) { Text(context.getString(R.string.copy)) }
                    }
                    Text(context.getString(R.string.preview_diagnostics_label), style = MaterialTheme.typography.titleSmall)
                    Text(
                        p.diagnostics ?: context.getString(R.string.diagnostics_none),
                        Modifier.testTag("preview-diagnostics"),
                        fontFamily = if (p.diagnostics != null) FontFamily.Monospace else null,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { Button(onClick = vm::submit, modifier = Modifier.testTag("submit-feedback")) { Text(context.getString(R.string.submit_feedback)) } },
            dismissButton = { TextButton(onClick = vm::closePreview) { Text(context.getString(R.string.cancel)) } },
        )
    }
}

@Composable
private fun UpdateResultView(result: UpdateResult, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    Column(Modifier.testTag("update-result"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (result) {
            is UpdateResult.Available -> {
                Text(context.getString(R.string.update_available, result.versionName), style = MaterialTheme.typography.titleSmall)
                Text(result.notes, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { onOpen(result.downloadPageUrl) }) { Text(context.getString(R.string.open_download_page)) }
            }
            is UpdateResult.Unsupported -> Text(context.getString(R.string.update_unsupported, result.versionName, result.minSdk))
            UpdateResult.UpToDate -> Text(context.getString(R.string.update_latest))
            is UpdateResult.Failed -> Text(
                result.httpStatus?.let { context.getString(R.string.update_failed_http, it) } ?: context.getString(R.string.update_failed),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun FeedbackStatusView(status: FeedbackStatus, onCopyId: (String) -> Unit) {
    val context = LocalContext.current
    Column(Modifier.testTag("feedback-status"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (status) {
            FeedbackStatus.Sending -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(context.getString(R.string.sending_feedback))
            }
            is FeedbackStatus.Received -> {
                SelectionContainer { Text(context.getString(R.string.feedback_received, status.reportId), color = MaterialTheme.colorScheme.primary) }
                TextButton(onClick = { onCopyId(status.reportId) }) { Text(context.getString(R.string.copy_report_id)) }
            }
            FeedbackStatus.Expired -> Text(context.getString(R.string.feedback_expired), color = MaterialTheme.colorScheme.error)
            is FeedbackStatus.Failed -> Text(
                when (status.reason) {
                    FeedbackFailure.OFFLINE -> context.getString(R.string.feedback_offline)
                    FeedbackFailure.UNCONFIRMED -> context.getString(R.string.feedback_unconfirmed)
                    FeedbackFailure.REJECTED -> context.getString(R.string.feedback_rejected)
                    FeedbackFailure.CONFLICT -> context.getString(R.string.feedback_conflict)
                    FeedbackFailure.TOO_LARGE -> context.getString(R.string.feedback_too_large)
                    FeedbackFailure.RATE_LIMITED -> context.getString(R.string.feedback_rate_limited, (status.retryAfterSeconds ?: 60).toInt())
                    FeedbackFailure.UNAVAILABLE -> context.getString(R.string.feedback_unavailable)
                    FeedbackFailure.BAD_RESPONSE -> context.getString(R.string.feedback_bad_response)
                },
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Writes the shared text to cache/exports and drops this app's feedback exports older than a day. */
private fun writeFeedbackExport(context: Context, text: String): File {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val cutoff = System.currentTimeMillis() - 24 * 3600 * 1000L
    dir.listFiles { f -> f.name.startsWith(FEEDBACK_EXPORT_PREFIX) && f.lastModified() < cutoff }?.forEach { it.delete() }
    return File(dir, "$FEEDBACK_EXPORT_PREFIX${System.currentTimeMillis()}.txt").apply { writeText(text) }
}

private const val FEEDBACK_EXPORT_PREFIX = "feiyu-feedback-"
