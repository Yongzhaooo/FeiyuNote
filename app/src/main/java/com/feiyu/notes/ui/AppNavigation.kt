package com.feiyu.notes.ui

import com.feiyu.notes.R
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.feiyu.notes.app
import com.feiyu.notes.study.StudyViewModel
import kotlinx.serialization.Serializable

// Navigation keys carry IDs only; screens load their own data.
@Serializable data object NotebookListKey : NavKey
@Serializable data class LessonListKey(val notebookId: Long) : NavKey
@Serializable data class LessonKey(val notebookId: Long, val lessonId: Long, val focusEntryId: Long? = null) : NavKey
@Serializable data class NoteKey(val notebookId: Long, val lessonId: Long, val noteId: Long) : NavKey
@Serializable data class ArchivedKey(val notebookId: Long, val lessonId: Long) : NavKey
@Serializable data class ReviewListKey(val notebookId: Long) : NavKey
@Serializable data object SettingsKey : NavKey
@Serializable data object TemplatesKey : NavKey
@Serializable data object SupportKey : NavKey

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun AppNavigation(initial: List<NavKey>) {
    val context = LocalContext.current
    val app = context.app
    val backStack = rememberNavBackStack(*initial.toTypedArray())
    // PopLatest: the default (PopUntilScaffoldValueChange) sees no change between list levels in the
    // two-pane layout, disables back and lets the system close the app.
    val listDetail = rememberListDetailSceneStrategy<NavKey>(backNavigationBehavior = BackNavigationBehavior.PopLatest)
    val back: () -> Unit = { if (backStack.size > 1) backStack.removeLastOrNull() }

    NavDisplay(
        backStack = backStack,
        onBack = back,
        sceneStrategies = listOf(listDetail),
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<NotebookListKey>(
                // The scene shows the first list entry's placeholder, so keep both neutral.
                metadata = ListDetailSceneStrategy.listPane(detailPlaceholder = { Placeholder(context.getString(R.string.choose_session)) }),
            ) {
                NotebookListScreen(
                    store = app.store,
                    generator = app.generator,
                    onOpen = { backStack.openList(LessonListKey(it.id)) },
                    onSettings = { backStack.add(SettingsKey) },
                    onGeneralChat = { backStack.openDetail(LessonKey(it.notebookId, it.id)) },
                )
            }
            entry<LessonListKey>(
                metadata = ListDetailSceneStrategy.listPane(detailPlaceholder = { Placeholder(context.getString(R.string.choose_session)) }),
            ) { key ->
                val selected = (backStack.lastOrNull { it is LessonKey } as? LessonKey)?.lessonId
                LessonListScreen(
                    store = app.store,
                    generator = app.generator,
                    notebookId = key.notebookId,
                    selectedLessonId = selected,
                    onOpen = { backStack.openDetail(LessonKey(key.notebookId, it.id)) },
                    onOpenReview = { backStack.openDetail(ReviewListKey(key.notebookId)) },
                    // The list pane's back goes up a level, closing any open detail with it.
                    onBack = { while (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
                )
            }
            entry<ReviewListKey>(metadata = ListDetailSceneStrategy.detailPane()) { key ->
                CourseReviewScreen(
                    store = app.store,
                    notebookId = key.notebookId,
                    onBack = back,
                    onNavigateToSource = { destination ->
                        when (destination) {
                            is ReviewSourceDestination.Note ->
                                backStack.add(NoteKey(key.notebookId, destination.lessonId, destination.noteId))
                            is ReviewSourceDestination.Archived ->
                                backStack.add(ArchivedKey(key.notebookId, destination.lessonId))
                            is ReviewSourceDestination.Chat ->
                                backStack.add(LessonKey(key.notebookId, destination.lessonId, focusEntryId = destination.entryId))
                        }
                    },
                )
            }
            entry<LessonKey>(metadata = ListDetailSceneStrategy.detailPane()) { key ->
                val vm = studyViewModel(key.notebookId, key.lessonId)
                ChatScreen(
                    vm = vm,
                    focusEntryId = key.focusEntryId,
                    onBack = back,
                    onOpenNote = { backStack.add(NoteKey(key.notebookId, key.lessonId, it)) },
                    onOpenArchived = { backStack.add(ArchivedKey(key.notebookId, key.lessonId)) },
                )
            }
            entry<NoteKey>(metadata = ListDetailSceneStrategy.detailPane()) { key ->
                NoteScreen(
                    store = app.store,
                    notebookId = key.notebookId,
                    lessonId = key.lessonId,
                    noteId = key.noteId,
                    onBack = back,
                    onOpenSource = { backStack.openDetail(LessonKey(key.notebookId, key.lessonId, focusEntryId = it)) },
                )
            }
            entry<ArchivedKey>(metadata = ListDetailSceneStrategy.detailPane()) { key ->
                ArchivedScreen(vm = studyViewModel(key.notebookId, key.lessonId), onBack = back)
            }
            entry<SettingsKey> {
                SettingsScreen(
                    settings = app.apiSettings,
                    onOpenTemplates = { backStack.add(TemplatesKey) },
                    onOpenSupport = { backStack.add(SupportKey) },
                    navigationIcon = { ActionIcon(R.drawable.ic_back, context.getString(R.string.back), back) },
                )
            }
            entry<TemplatesKey> { TemplateScreen(store = app.store, onBack = back) }
            entry<SupportKey> {
                SupportScreen(vm = viewModel(factory = viewModelFactory { initializer { SupportViewModel(app) } }), onBack = back)
            }
        },
    )

    // Spec §9 L02: offered once after a crash; viewing or ignoring both retire this crash's prompt.
    var crashPrompt by rememberSaveable { mutableStateOf(app.diagnostics.hasPendingCrash()) }
    if (crashPrompt) AlertDialog(
        onDismissRequest = { app.diagnostics.dismissCrash(); crashPrompt = false },
        title = { Text(context.getString(R.string.crash_title)) },
        text = { Text(context.getString(R.string.crash_body)) },
        confirmButton = {
            TextButton(onClick = {
                app.diagnostics.dismissCrash(); crashPrompt = false
                app.feedbackDrafts.save(app.feedbackDrafts.load().copy(includeDiagnostics = true, pending = null))
                backStack.add(SupportKey)
            }, modifier = Modifier.testTag("crash-review")) { Text(context.getString(R.string.crash_view)) }
        },
        dismissButton = {
            TextButton(onClick = { app.diagnostics.dismissCrash(); crashPrompt = false }) { Text(context.getString(R.string.crash_ignore)) }
        },
    )
}

@Composable
private fun studyViewModel(notebookId: Long, lessonId: Long): StudyViewModel {
    val app = LocalContext.current.app
    return viewModel(factory = viewModelFactory {
        initializer { StudyViewModel(app, notebookId, lessonId, createSavedStateHandle()) }
    })
}

/** Opens a list level (e.g. a notebook's lessons) directly above the notebook list. */
private fun NavBackStack<NavKey>.openList(key: NavKey) {
    while (size > 1) removeAt(lastIndex)
    add(key)
}

/** Replaces whatever detail is open with [key], keeping the list pane one level deep. */
private fun NavBackStack<NavKey>.openDetail(key: NavKey) {
    while (lastOrNull()?.let { it !is NotebookListKey && it !is LessonListKey } == true) removeAt(lastIndex)
    add(key)
}

@Composable
private fun Placeholder(text: String) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(Modifier.then(Modifier), horizontalAlignment = Alignment.CenterHorizontally) {
            WelcomeCard()
            Text(text)
        }
    }
}
