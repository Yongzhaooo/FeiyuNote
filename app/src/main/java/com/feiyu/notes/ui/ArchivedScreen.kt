package com.feiyu.notes.ui

import com.feiyu.notes.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feiyu.notes.data.NotebookKind
import com.feiyu.notes.study.StudyViewModel

/** Archived threads of a lesson: readable offline, restorable via each root's menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedScreen(vm: StudyViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val data by vm.data.collectAsStateWithLifecycle()
    val d = data
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.archive_title, d?.lesson?.let { lessonTitle(context, it) }.orEmpty())) },
                navigationIcon = { ActionIcon(R.drawable.ic_back, context.getString(R.string.back), onBack) },
                colors = feiyuTopBarColors(),
            )
        },
    ) { padding ->
        val rows = d?.let { threadRows(it.entries, archived = true) }.orEmpty()
        val numbers = d?.let { qaNumbers(it.entries) }.orEmpty()
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            if (d != null && rows.isEmpty()) item {
                EmptyState(R.drawable.whale_01_05, context.getString(R.string.archive_empty))
            }
            items(rows, key = { it.first.id }) { (entry, depth) ->
                EntryCard(
                    entry = entry,
                    depth = depth,
                    number = numbers[entry.id],
                    all = d!!.entries,
                    templates = d.templates,
                    practice = d.notebook?.kind == NotebookKind.PRACTICE,
                    highlighted = false,
                    vm = vm,
                    readOnly = true,
                    onRetry = {},
                )
            }
        }
    }
}
