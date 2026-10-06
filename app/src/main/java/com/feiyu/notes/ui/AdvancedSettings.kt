package com.feiyu.notes.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.feiyu.notes.R
import com.feiyu.notes.app
import com.feiyu.notes.data.Template
import com.feiyu.notes.settings.AvatarFiles
import com.feiyu.notes.settings.SkillImport
import com.feiyu.notes.study.PromptKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Welcome and general-chat illustrations; private copies, default whale art on reset. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeImageSettings() {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<AvatarFiles?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val files = target
        if (uri != null && files != null) scope.launch {
            busy = true
            message = runCatching { files.import(context.contentResolver, uri) }
                .fold({ context.getString(R.string.image_saved) }, { context.getString(R.string.image_failed) })
            busy = false
        }
    }
    SettingsSection(context.getString(R.string.home_images), R.drawable.ic_gallery, Accent.SEA) {
        Text(context.getString(R.string.home_images_hint), style = MaterialTheme.typography.bodySmall)
        listOf(
            Triple(app.welcomeImage, R.drawable.whale_02_01, R.string.welcome_image),
            Triple(app.chatImage, R.drawable.whale_01_07, R.string.chat_image),
        ).forEach { (files, default, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CustomImage(files, default, Modifier.size(56.dp))
                Text(context.getString(label), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = {
                    target = files
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text(context.getString(R.string.choose_image)) }
                TextButton(enabled = !busy, onClick = { files.reset(); message = context.getString(R.string.image_reset) }) {
                    Text(context.getString(R.string.restore_default))
                }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Collapsed by default: editing built-in prompts and installing Skills are for users who know what they are. */
@Composable
fun AdvancedSettings() {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    SettingsSection(context.getString(R.string.advanced), R.drawable.ic_tools, Accent.INDIGO) {
        Text(context.getString(R.string.advanced_hint), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("advanced-toggle")) {
            Text(context.getString(if (expanded) R.string.hide_advanced else R.string.show_advanced))
        }
        if (expanded) {
            BuiltInPrompts()
            HorizontalDivider()
            SkillInstaller()
        }
    }
}

@Composable
private fun BuiltInPrompts() {
    val context = LocalContext.current
    val prefs = context.app.prefs
    var editing by rememberSaveable { mutableStateOf<PromptKind?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    Text(context.getString(R.string.builtin_prompts), style = MaterialTheme.typography.titleSmall)
    Text(context.getString(R.string.builtin_prompts_hint), style = MaterialTheme.typography.bodySmall)
    PromptKind.entries.forEach { kind ->
        val custom = remember(revision) { prefs.isPromptCustom(kind) }
        ListItem(
            headlineContent = { Text(context.getString(promptLabel(kind))) },
            supportingContent = { Text(context.getString(if (custom) R.string.prompt_custom else R.string.prompt_default)) },
            modifier = Modifier.clickable { editing = kind }.testTag("prompt-${kind.name}"),
        )
    }
    editing?.let { kind ->
        var text by rememberSaveable(kind) { mutableStateOf(prefs.prompt(kind, com.feiyu.notes.settings.AppLanguage.current(context))) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(context.getString(promptLabel(kind))) },
            text = {
                OutlinedTextField(text, { text = it }, minLines = 8,
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("prompt-editor"))
            },
            confirmButton = { TextButton(modifier = Modifier.testTag("prompt-save"), onClick = { prefs.setPrompt(kind, text); revision++; editing = null }) { Text(context.getString(R.string.save)) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { prefs.setPrompt(kind, null); revision++; editing = null }) { Text(context.getString(R.string.restore_default)) }
                    TextButton(onClick = { editing = null }) { Text(context.getString(R.string.cancel)) }
                }
            },
        )
    }
}

private fun promptLabel(kind: PromptKind) = when (kind) {
    PromptKind.STUDY -> R.string.prompt_study
    PromptKind.GENERAL -> R.string.general_chat
    PromptKind.SUMMARY -> R.string.prompt_summary
}

@Composable
private fun SkillInstaller() {
    val context = LocalContext.current
    val store = context.app.store
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<SkillImport.Skill?>(null) }
    Text(context.getString(R.string.skill_title), style = MaterialTheme.typography.titleSmall)
    Text(context.getString(R.string.skill_explain), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
        value = url, onValueChange = { url = it; message = null }, enabled = !busy, singleLine = true,
        label = { Text(context.getString(R.string.skill_url)) },
        supportingText = { Text(context.getString(R.string.skill_url_hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().testTag("skill-url"),
    )
    OutlinedButton(enabled = !busy && url.isNotBlank(), modifier = Modifier.testTag("skill-fetch"), onClick = {
        scope.launch {
            busy = true; message = null
            try {
                preview = SkillImport.fetch(url)
            } catch (e: CancellationException) { throw e
            } catch (e: SkillImport.SkillException) {
                message = context.getString(when (e.failure) {
                    SkillImport.Failure.INVALID_URL -> R.string.skill_invalid_url
                    SkillImport.Failure.NOT_FOUND -> R.string.skill_not_found
                    SkillImport.Failure.TOO_LARGE -> R.string.skill_too_large
                    SkillImport.Failure.EMPTY -> R.string.skill_empty
                    SkillImport.Failure.NETWORK -> R.string.skill_failed
                })
            } finally { busy = false }
        }
    }) { Text(context.getString(if (busy) R.string.skill_fetching else R.string.skill_fetch)) }
    message?.let { Text(it, Modifier.testTag("skill-status"), style = MaterialTheme.typography.bodySmall) }

    preview?.let { skill ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(context.getString(R.string.skill_preview_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(skill.name, style = MaterialTheme.typography.titleMedium)
                    if (skill.description.isNotBlank()) Text(skill.description, style = MaterialTheme.typography.bodyMedium)
                    Text(context.getString(R.string.skill_source, skill.url), style = MaterialTheme.typography.bodySmall)
                    Text(context.getString(R.string.skill_text_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    HorizontalDivider()
                    Text(skill.instruction, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(modifier = Modifier.testTag("skill-install"), onClick = {
                    preview = null
                    scope.launch {
                        store.saveTemplate(Template(0, skill.name, skill.instruction, 0, source = skill.url))
                        message = context.getString(R.string.skill_installed, skill.name); url = ""
                    }
                }) { Text(context.getString(R.string.skill_install)) }
            },
            dismissButton = { TextButton(onClick = { preview = null }) { Text(context.getString(R.string.cancel)) } },
        )
    }
}
