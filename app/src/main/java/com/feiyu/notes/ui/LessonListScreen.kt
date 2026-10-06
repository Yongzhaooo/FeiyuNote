package com.feiyu.notes.ui

import com.feiyu.notes.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.feiyu.notes.data.Lesson
import com.feiyu.notes.data.NotebookKind
import com.feiyu.notes.data.NotebookStore
import com.feiyu.notes.study.Generator
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LessonListScreen(
    store: NotebookStore,
    generator: Generator,
    notebookId: Long,
    selectedLessonId: Long?,
    onOpen: (Lesson) -> Unit,
    onBack: () -> Unit,
    onOpenReview: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val notebook by rememberStoreValue(store, notebookId) { getNotebook(notebookId) }
    val lessons by rememberStoreValue(store, notebookId) { listLessons(notebookId) }
    val scope = rememberCoroutineScope()
    var renamingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val practice = notebook?.kind == NotebookKind.PRACTICE
    val unit = if (practice) context.getString(R.string.chapter) else context.getString(R.string.session)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(notebook?.name ?: "") },
                navigationIcon = { ActionIcon(R.drawable.ic_back, context.getString(R.string.back), onBack) },
                actions = {
                    if (!practice && notebookId != com.feiyu.notes.data.NotebookStore.GENERAL_ID && onOpenReview != null) {
                        TextButton(onClick = onOpenReview) {
                            Text(context.getString(R.string.course_review))
                        }
                    }
                },
                colors = feiyuTopBarColors(),
            )
        },
        floatingActionButton = {
            // The content-slot variant keeps the label in the semantics tree, so screen readers announce it.
            ExtendedFloatingActionButton(
                onClick = { scope.launch { store.createLesson(notebookId, LocalDate.now().toString())?.let(onOpen) } },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(painterResource(R.drawable.ic_plus), null)
                Text(context.getString(R.string.new_session, unit), Modifier.padding(start = 10.dp))
            }
        },
    ) { padding ->
        // Bottom padding keeps the last lesson clear of the floating button.
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)) {
            if (lessons?.isEmpty() == true) item {
                EmptyState(if (practice) R.drawable.whale_01_01 else R.drawable.whale_01_02, context.getString(R.string.sessions_empty, unit))
            }
            items(lessons.orEmpty(), key = { it.id }) { lesson ->
                var menu by remember { mutableStateOf(false) }
                val selected = lesson.id == selectedLessonId
                Surface(
                    onClick = { onOpen(lesson) },
                    shape = MaterialTheme.shapes.medium,
                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    border = if (selected) null else softBorder(),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        IconBadge(if (practice) R.drawable.ic_pencil else R.drawable.ic_calendar, Accent.of(lesson.id))
                        Text(lesson.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Box {
                            ActionIcon(R.drawable.ic_more, context.getString(R.string.more_options), { menu = true })
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(context.getString(R.string.rename)) }, onClick = { menu = false; renamingId = lesson.id })
                                DropdownMenuItem(text = { Text(context.getString(R.string.delete)) }, onClick = { menu = false; deletingId = lesson.id })
                            }
                        }
                    }
                }
            }
        }
    }

    renamingId?.let { id ->
        val lesson = lessons?.firstOrNull { it.id == id }
        if (lesson != null) TextInputDialog(
            title = context.getString(R.string.rename_session, unit),
            initial = lesson.title,
            label = context.getString(R.string.title),
            onConfirm = { title -> scope.launch { store.renameLesson(id, title) } },
            onDismiss = { renamingId = null },
        )
    }
    deletingId?.let { id ->
        val lesson = lessons?.firstOrNull { it.id == id }
        if (lesson != null) ConfirmDialog(
            title = context.getString(R.string.delete_named, lesson.title),
            text = context.getString(R.string.delete_lesson_body),
            onConfirm = {
                generator.cancelIfAffected(lessonId = id)
                scope.launch { store.deleteLesson(id) }
            },
            onDismiss = { deletingId = null },
        )
    }
}
