package com.feiyu.notes.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.feiyu.notes.R
import com.feiyu.notes.settings.ApiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The only screen that takes the API key. It blocks screenshots and never shows stored key text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeySettingsScreen(settings: ApiSettings, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val window = activity?.window
        val flag = android.view.WindowManager.LayoutParams.FLAG_SECURE
        val wasSecure = window != null && window.attributes.flags and flag != 0
        window?.addFlags(flag)
        onDispose { if (!wasSecure) window?.clearFlags(flag) }
    }
    var hasKey by remember { mutableStateOf(settings.hasKey()) }
    // Credentials stay in memory; never put a typed key in saved-instance-state.
    var newKey by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    /** A blank [key] removes the stored one; the default model and effort stay as they are. */
    fun store(key: String) {
        saving = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    settings.save(key, settings.model(), settings.effort())
                    settings.hasKey()
                }
            }.onSuccess {
                newKey = ""; hasKey = it; error = null
                message = context.getString(if (key.isBlank()) R.string.key_cleared else R.string.key_saved)
            }.onFailure { message = null; error = context.getString(R.string.save_failed) }
            saving = false
        }
    }
    fun openPlatform() {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.deepseek.com/api_keys"))) }
            .onFailure { error = context.getString(R.string.browser_unavailable) }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(context.getString(R.string.key_settings)) },
            navigationIcon = { ActionIcon(R.drawable.ic_back, context.getString(R.string.back), onBack) },
            colors = feiyuTopBarColors(),
        )
    }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val colors = MaterialTheme.colorScheme
                HeroCard(if (hasKey) listOf(colors.secondaryContainer, colors.primaryContainer) else listOf(colors.tertiaryContainer, colors.primaryContainer)) {
                    Sticker(if (hasKey) R.drawable.whale_02_03 else R.drawable.whale_02_05, 72.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            context.getString(if (hasKey) R.string.key_ready else R.string.key_needed),
                            Modifier.testTag("key-status"),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(context.getString(R.string.key_hero_body), style = MaterialTheme.typography.bodySmall)
                    }
                }
                SettingsSection(context.getString(if (hasKey) R.string.change_key else R.string.enter_key), R.drawable.ic_key, Accent.BLUSH) {
                    OutlinedTextField(
                        enabled = !saving,
                        value = newKey,
                        onValueChange = { newKey = it; message = null; error = null },
                        label = { Text(if (hasKey) context.getString(R.string.replace_key) else "API Key") },
                        supportingText = { Text(context.getString(R.string.key_private)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().testTag("api-key"),
                    )
                    Button(enabled = !saving && newKey.isNotBlank(), onClick = { store(newKey) }, modifier = Modifier.testTag("save-key")) {
                        Text(context.getString(R.string.save))
                    }
                    message?.let { Text(it, color = colors.primary) }
                    error?.let { Text(it, color = colors.error) }
                    ConnectionTest(settings, newKey.takeIf { it.isNotBlank() }, enabled = !saving)
                    if (hasKey) TextButton(
                        enabled = !saving,
                        onClick = { confirmClear = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.error),
                        modifier = Modifier.testTag("clear-key"),
                    ) { Text(context.getString(R.string.clear_key)) }
                }
                SettingsSection(context.getString(R.string.api_guide_title), R.drawable.ic_help, Accent.SEA) {
                    Text(context.getString(R.string.api_guide), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = ::openPlatform) { Text(context.getString(R.string.open_deepseek)) }
                }
            }
        }
    }
    if (confirmClear) ConfirmDialog(
        title = context.getString(R.string.clear_key_title),
        text = context.getString(R.string.clear_key_body),
        confirm = context.getString(R.string.confirm),
        onConfirm = { store("") },
        onDismiss = { confirmClear = false },
    )
}
