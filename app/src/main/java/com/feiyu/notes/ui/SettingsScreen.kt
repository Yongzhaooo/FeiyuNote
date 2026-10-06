package com.feiyu.notes.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.feiyu.notes.R
import com.feiyu.notes.app
import com.feiyu.notes.ai.AiDefaults
import com.feiyu.notes.ai.ModelChoice
import com.feiyu.notes.settings.ApiSettings
import com.feiyu.notes.settings.UiLanguage
import com.feiyu.notes.settings.ThemeMode
import com.feiyu.notes.settings.TextSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Everything except the API key, which lives on its own screenshot-protected page ([KeySettingsScreen]). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: ApiSettings,
    onOpenKey: () -> Unit,
    onOpenTemplates: () -> Unit,
    onOpenSupport: () -> Unit,
    navigationIcon: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var showLicense by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text(context.getString(R.string.settings)) }, navigationIcon = navigationIcon, colors = feiyuTopBarColors()) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                KeyStatusCard(settings, onOpenKey)
                GroupCard()
                FeatureSettings()
                DisplaySettings()
                AiSettings(settings, onOpenTemplates)
                AvatarSettings()
                HomeImageSettings()
                AdvancedSettings()
                SettingsSection(context.getString(R.string.about), R.drawable.ic_info, Accent.SEA) {
                    NavRow(
                        R.drawable.ic_help, Accent.INDIGO, context.getString(R.string.support_title), context.getString(R.string.support_hint),
                        onOpenSupport, Modifier.testTag("open-support"),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(context.getString(R.string.art_credit), style = MaterialTheme.typography.bodySmall)
                    Text("Noto Sans SC · SIL Open Font License 1.1", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { showLicense = true }) { Text(context.getString(R.string.font_license)) }
                }
            }
        }
    }
    if (showLicense) AlertDialog(
        onDismissRequest = { showLicense = false }, title = { Text("SIL Open Font License 1.1") },
        text = {
            val license = remember { context.assets.open("licenses/NotoSansSC-OFL.txt").bufferedReader(Charsets.UTF_8).use { it.readText() } }
            Text(license, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
        },
        confirmButton = { TextButton(onClick = { showLicense = false }) { Text(context.getString(R.string.close)) } },
    )
}

@Composable
internal fun SettingsSection(
    title: String,
    @DrawableRes icon: Int? = null,
    accent: Accent = Accent.INDIGO,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, border = softBorder()) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                icon?.let { IconBadge(it, accent, size = 32.dp) }
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            content()
        }
    }
}

/** Key status at the top of Settings; the key itself is only entered on the key page. */
@Composable
private fun KeyStatusCard(settings: ApiSettings, onOpenKey: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    // Re-read whenever Settings re-enters composition, e.g. on return from the key page.
    val hasKey = remember { settings.hasKey() }
    HeroCard(
        colors = if (hasKey) listOf(colors.primaryContainer, colors.secondaryContainer) else listOf(colors.tertiaryContainer, colors.primaryContainer),
        onClick = onOpenKey,
        modifier = Modifier.fillMaxWidth().testTag("open-key-settings"),
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(colors.surface), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_key), null, Modifier.size(26.dp), tint = if (hasKey) colors.primary else colors.tertiary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(context.getString(R.string.key_settings), style = MaterialTheme.typography.titleMedium)
            Text(context.getString(if (hasKey) R.string.key_ready else R.string.key_needed), style = MaterialTheme.typography.bodyMedium)
        }
        Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(24.dp))
    }
}

/** The QQ group sits right under the key card so it is easy to find; the help page keeps its own copy. */
@Composable
private fun GroupCard() {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, border = softBorder(), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(R.drawable.ic_heart, Accent.BLUSH, size = 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(context.getString(R.string.discussion_title), style = MaterialTheme.typography.titleMedium)
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(context.getString(R.string.qq_group, QQ_GROUP), Modifier.testTag("settings-qq-group"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
                Text(context.getString(R.string.group_card_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick = {
                context.getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Feiyu Notes", QQ_GROUP))
                copied = true
            }, modifier = Modifier.testTag("settings-copy-group")) {
                Text(context.getString(if (copied) R.string.copied else R.string.copy_group_number))
            }
        }
    }
}

/** Default model and effort (saved without touching the key), plus the way to explanation templates. */
@Composable
private fun AiSettings(settings: ApiSettings, onOpenTemplates: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var model by rememberSaveable { mutableStateOf(settings.model()) }
    var effort by rememberSaveable { mutableStateOf(settings.effort()) }
    val models by settings.models.collectAsStateWithLifecycle()
    var saving by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    SettingsSection(context.getString(R.string.ai_section), R.drawable.ic_sparkle, Accent.SEA) {
        Text(context.getString(R.string.default_model), style = MaterialTheme.typography.titleSmall)
        Text(context.getString(R.string.default_model_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ModelChoiceFields(ModelChoice(model, effort), { model = it.model; effort = it.effort; result = null }, models, enabled = !saving)
        ConnectionTest(settings, enabled = !saving)
        Button(enabled = !saving, modifier = Modifier.testTag("save-model"), onClick = {
            val selected = model.trim().ifBlank { AiDefaults.MODEL }
            saving = true
            scope.launch {
                // A null key keeps the stored one.
                result = runCatching { withContext(Dispatchers.IO) { settings.save(null, selected, effort) } }
                    .fold({ model = selected; true to context.getString(R.string.saved) }, { false to context.getString(R.string.save_failed) })
                saving = false
            }
        }) { Text(context.getString(R.string.save)) }
        result?.let { (ok, text) -> Text(text, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        NavRow(R.drawable.ic_pencil, Accent.BLUSH, context.getString(R.string.templates), context.getString(R.string.templates_hint), onOpenTemplates)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AvatarSettings() {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            message = runCatching { app.avatars.import(context.contentResolver, uri) }
                .fold({ context.getString(R.string.avatar_saved) }, { context.getString(R.string.avatar_failed) })
            busy = false
        }
    }
    SettingsSection(context.getString(R.string.deepseek_avatar), R.drawable.ic_heart, Accent.BLUSH) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            WhaleAvatar(app.prefs.lastLesson?.second ?: 0, Modifier.size(64.dp))
            Text(context.getString(R.string.avatar_hint), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = {
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, modifier = Modifier.testTag("choose-avatar")) { Text(context.getString(R.string.choose_avatar)) }
            TextButton(enabled = !busy, onClick = {
                app.avatars.reset(); message = context.getString(R.string.avatar_reset)
            }, modifier = Modifier.testTag("reset-avatar")) { Text(context.getString(R.string.random_avatar)) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, Modifier.testTag("avatar-status"), style = MaterialTheme.typography.bodySmall) }
    }
}

/** Turn chat or study off when only one is wanted; the last one on cannot be switched off. */
@Composable
private fun FeatureSettings() {
    val context = LocalContext.current
    val prefs = context.app.prefs
    val features by prefs.features.collectAsStateWithLifecycle()
    SettingsSection(context.getString(R.string.features_section), R.drawable.ic_sparkle, Accent.BLUSH) {
        Text(context.getString(R.string.features_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf(
            Triple(R.string.feature_chat, features.chat, "feature-chat"),
            Triple(R.string.feature_study, features.study, "feature-study"),
        ).forEach { (label, on, tag) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(context.getString(label), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(
                    checked = on,
                    // Only the last remaining one is locked.
                    enabled = !on || (features.chat && features.study),
                    onCheckedChange = { checked ->
                        prefs.setFeatures(if (tag == "feature-chat") features.copy(chat = checked) else features.copy(study = checked))
                    },
                    modifier = Modifier.testTag(tag),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DisplaySettings() {
    val context = LocalContext.current
    val prefs = context.app.prefs
    val display by prefs.display.collectAsStateWithLifecycle()
    SettingsSection(context.getString(R.string.display_settings), R.drawable.ic_palette, Accent.INDIGO) {
        Text(context.getString(R.string.language), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UiLanguage.entries.forEach { language ->
                FilterChip(
                    selected = display.language == language,
                    onClick = { prefs.setLanguage(language) },
                    label = { Text(when (language) {
                        UiLanguage.SYSTEM -> context.getString(R.string.follow_system)
                        UiLanguage.ZH -> "中文"
                        UiLanguage.EN -> "English"
                    }) },
                    modifier = Modifier.testTag("language-${language.name}"),
                )
            }
        }
        Text(context.getString(R.string.appearance), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { theme ->
                FilterChip(
                    selected = display.theme == theme,
                    onClick = { prefs.setTheme(theme) },
                    label = { Text(context.getString(when (theme) {
                        ThemeMode.SYSTEM -> R.string.follow_system
                        ThemeMode.LIGHT -> R.string.light_mode
                        ThemeMode.DARK -> R.string.dark_mode
                    })) },
                    leadingIcon = if (theme == ThemeMode.SYSTEM) null else { {
                        Icon(painterResource(if (theme == ThemeMode.LIGHT) R.drawable.ic_sun else R.drawable.ic_moon), null, Modifier.size(18.dp))
                    } },
                    modifier = Modifier.testTag("theme-${theme.name}"),
                )
            }
        }
        Text(context.getString(R.string.text_size), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextSize.entries.forEach { size ->
                FilterChip(
                    selected = display.textSize == size,
                    onClick = { prefs.setTextSize(size) },
                    label = { Text(context.getString(when (size) {
                        TextSize.STANDARD -> R.string.size_standard
                        TextSize.LARGE -> R.string.size_large
                        TextSize.EXTRA_LARGE -> R.string.size_extra_large
                        TextSize.LARGEST -> R.string.size_largest
                    })) },
                    modifier = Modifier.testTag("text-size-${size.name}"),
                )
            }
        }
        Text(context.getString(R.string.text_size_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f), shape = MaterialTheme.shapes.medium) {
            Text(
                context.getString(R.string.reading_preview),
                Modifier.fillMaxWidth().padding(16.dp).testTag("reading-preview"),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
