package com.feiyu.notes.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feiyu.notes.R
import com.feiyu.notes.data.NotebookKind
import com.feiyu.notes.data.NotebookStore
import com.feiyu.notes.study.CourseHandbookViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseHandbookScreen(vm: CourseHandbookViewModel, sectionId: Long?, onBack: () -> Unit) {
    val context = LocalContext.current
    val data by vm.data.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val editing by vm.editing.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var outline by rememberSaveable { mutableStateOf(false) }
    var bookmarks by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val sections = data?.sections.orEmpty()
    LaunchedEffect(sectionId, sections.map { it.id }) { sections.indexOfFirst { it.id == sectionId }.takeIf { it >= 0 }?.let { list.scrollToItem(it) } }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::import) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(context.getString(R.string.course_handbook)) }, navigationIcon = { ActionIcon(R.drawable.ic_back, context.getString(R.string.back), onBack) },
            actions = {
                if (!editing) {
                    TextButton(onClick = { outline = true }, enabled = sections.isNotEmpty()) { Text(context.getString(R.string.document_outline)) }
                    TextButton(onClick = { bookmarks = true }) { Text(context.getString(R.string.document_bookmarks)) }
                }
            }, colors = feiyuTopBarColors())
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            when {
                data == null -> Text(context.getString(R.string.loading))
                data?.notebook?.kind != NotebookKind.COURSE || vm.notebookId == NotebookStore.GENERAL_ID -> Text(context.getString(R.string.lesson_deleted))
                else -> {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("document-error")) }
                    if (!editing && data?.document != null) Row {
                        Checkbox(checked = data?.document?.enabled == true, onCheckedChange = vm::setEnabled)
                        Text(context.getString(R.string.use_course_knowledge), Modifier.padding(top = 12.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (editing) {
                            TextButton(onClick = { vm.save() }, enabled = !busy, modifier = Modifier.testTag("save-document")) { Text(context.getString(R.string.save)) }
                            TextButton(onClick = vm::cancelEdit, enabled = !busy) { Text(context.getString(R.string.cancel)) }
                        } else {
                            TextButton(onClick = vm::edit, enabled = !busy, modifier = Modifier.testTag("edit-document")) { Text(context.getString(R.string.edit)) }
                            TextButton(onClick = { importer.launch(arrayOf("text/*", "application/octet-stream")) }, enabled = !busy) { Text(context.getString(R.string.document_import)) }
                            if (data?.document != null) TextButton(onClick = { deleting = true }, enabled = !busy) { Text(context.getString(R.string.delete)) }
                        }
                    }
                    if (editing) {
                        Text(context.getString(R.string.document_format), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(value = draft, onValueChange = vm::setDraft, enabled = !busy,
                            modifier = Modifier.fillMaxWidth().weight(1f).testTag("document-input"), label = { Text("Markdown") })
                    } else if (sections.isEmpty()) Text(context.getString(R.string.document_empty))
                    else LazyColumn(state = list, modifier = Modifier.weight(1f).testTag("document-reader"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(sections, key = { it.id }) { section ->
                            Column {
                                TextButton(onClick = { vm.bookmark(section.id) }) { Text(context.getString(R.string.document_add_bookmark)) }
                                // Outline sections overlap by hierarchy; reading shows each source line once.
                                MarkdownContent(data?.reading?.getOrNull(section.ordinal).orEmpty())
                            }
                        }
                    }
                }
            }
        }
    }
    if (outline) AlertDialog(onDismissRequest = { outline = false }, title = { Text(context.getString(R.string.document_outline)) },
        text = { LazyColumn { items(sections, key = { it.id }) { section -> TextButton(onClick = { outline = false; scope.launch { list.scrollToItem(section.ordinal) } }) { Text(section.headingPath.ifBlank { context.getString(R.string.document_intro) }) } } } },
        confirmButton = { TextButton(onClick = { outline = false }) { Text(context.getString(R.string.dismiss)) } })
    if (bookmarks) AlertDialog(onDismissRequest = { bookmarks = false }, title = { Text(context.getString(R.string.document_bookmarks)) },
        text = { LazyColumn { items(data?.bookmarks.orEmpty(), key = { it.id }) { bookmark ->
            Column {
                TextButton(enabled = bookmark.sectionId != null, onClick = { bookmarks = false; sections.indexOfFirst { it.id == bookmark.sectionId }.takeIf { it >= 0 }?.let { scope.launch { list.scrollToItem(it) } } }) {
                    Text(bookmark.headingPath.ifBlank { context.getString(R.string.document_intro) } + if (bookmark.sectionId == null) " · " + context.getString(R.string.document_bookmark_missing) else "")
                }
                TextButton(onClick = { vm.removeBookmark(bookmark.id) }) { Text(context.getString(R.string.delete)) }
            }
        } } }, confirmButton = { TextButton(onClick = { bookmarks = false }) { Text(context.getString(R.string.dismiss)) } })
    if (deleting) ConfirmDialog(title = context.getString(R.string.delete), text = context.getString(R.string.document_delete_body), onConfirm = { vm.delete() }, onDismiss = { deleting = false })
}

/** Supported native subset: headings, paragraphs, lists, emphasis and formulas. Code/tables stay readable source. */
@Composable
fun MarkdownContent(source: String) {
    val blocks = remember(source) { markdownBlocks(source) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { (code, block) ->
            val lines = block.lines()
            if (code || lines.any { it.trim().startsWith('|') }) {
                Text(block, fontFamily = FontFamily.Monospace, modifier = Modifier.horizontalScroll(rememberScrollState()))
            } else {
                var index = 0
                while (index < lines.size) {
                    val line = lines[index]
                    val heading = Regex("^ {0,3}(#{1,6}) +(.+?) *#* *$").matchEntire(line)
                    val setext = lines.getOrNull(index + 1)?.let { Regex("^ {0,3}(=+|-+) *$").matchEntire(it) }
                    if (heading != null || setext != null) {
                        val level = heading?.groupValues?.get(1)?.length ?: if (setext!!.groupValues[1].first() == '=') 1 else 2
                        MathContent(heading?.groupValues?.get(2) ?: line, markdown = true, textStyle = if (level <= 2) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
                        index += if (setext != null) 2 else 1
                    } else {
                        // Keep consecutive paragraph/math lines together so display equations remain intact.
                        val end = (index + 1 until lines.size).firstOrNull { Regex("^ {0,3}#{1,6} +").containsMatchIn(lines[it]) } ?: lines.size
                        val text = lines.subList(index, end).joinToString("\n") { it.replace(Regex("^([ \\t]*)[-+*] +"), "$1• ") }
                        MathContent(text, markdown = true)
                        index = end
                    }
                }
            }
        }
    }
}

internal fun markdownBlocks(source: String): List<Pair<Boolean, String>> = buildList {
    var fence: String? = null
    val lines = mutableListOf<String>()
    fun flush(code: Boolean) { if (lines.isNotEmpty()) { add(code to lines.joinToString("\n")); lines.clear() } }
    source.lines().forEach { line ->
        val marker = Regex("^ {0,3}(`{3,}|~{3,})").find(line)?.groupValues?.get(1)
        val current = fence
        when {
            current != null -> {
                lines += line
                if (marker != null && marker.first() == current.first() && marker.length >= current.length && line.substringAfter(marker).isBlank()) { flush(true); fence = null }
            }
            marker != null -> { flush(false); fence = marker; lines += line }
            line.isBlank() -> flush(false)
            else -> lines += line
        }
    }
    flush(fence != null)
}
