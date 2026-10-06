package com.feiyu.notes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation3.runtime.NavKey
import com.feiyu.notes.ui.AppNavigation
import com.feiyu.notes.ui.LessonKey
import com.feiyu.notes.ui.LessonListKey
import com.feiyu.notes.ui.NotebookListKey
import com.feiyu.notes.ui.theme.FeiyuTheme
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A restored back stack wins over this; it only seeds a cold start.
        // ponytail: one indexed row lookup on the main thread at cold start; move behind a splash if it ever shows up in startup traces.
        val initial = if (savedInstanceState == null) runBlocking { startStack() } else listOf(NotebookListKey)
        setContent {
            FeiyuTheme {
                // Pane gaps, placeholders and transitions show the page colour, not the light window default.
                androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                    AppNavigation(initial)
                }
            }
        }
    }

    /** Reopens the last lesson if it still exists; otherwise the notebook list (spec §5 失效引用). */
    private suspend fun startStack(): List<NavKey> {
        val (notebookId, lessonId) = app.prefs.lastLesson ?: return listOf(NotebookListKey)
        val lesson = app.store.getLesson(lessonId)
        val general = notebookId == com.feiyu.notes.data.NotebookStore.GENERAL_ID
        val features = app.prefs.features.value
        if (lesson == null || lesson.notebookId != notebookId || (general && !features.chat) || (!general && !features.study)) {
            app.prefs.clearLastLesson()
            return listOf(NotebookListKey)
        }
        return if (notebookId == com.feiyu.notes.data.NotebookStore.GENERAL_ID) listOf(NotebookListKey, LessonKey(notebookId, lessonId))
        else listOf(NotebookListKey, LessonListKey(notebookId), LessonKey(notebookId, lessonId))
    }
}
