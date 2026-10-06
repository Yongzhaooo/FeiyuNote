package com.feiyu.notes.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feiyu.notes.R
import com.feiyu.notes.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Stable ordering: persisted indices refer to this list, never to generated Android resource IDs.
internal val WhalePortraits = listOf(
    R.drawable.whale_01_01,
    R.drawable.whale_01_02,
    R.drawable.whale_01_03,
    R.drawable.whale_01_04,
    R.drawable.whale_01_05,
    R.drawable.whale_01_06,
    R.drawable.whale_01_07,
    R.drawable.whale_01_08,
    R.drawable.whale_01_09,
    R.drawable.whale_02_01,
    R.drawable.whale_02_02,
    R.drawable.whale_02_03,
    R.drawable.whale_02_04,
    R.drawable.whale_02_05,
    R.drawable.whale_02_06,
    R.drawable.whale_02_07,
    R.drawable.whale_02_08,
    R.drawable.whale_02_09,
    R.drawable.whale_02_10,
    R.drawable.whale_02_11,
    R.drawable.whale_02_12
)

@Composable
fun WhaleAvatar(lessonId: Long, modifier: Modifier = Modifier.size(40.dp)) {
    val context = LocalContext.current
    val app = context.app
    val revision by app.avatars.revision.collectAsStateWithLifecycle()
    val portrait = remember(lessonId, app.prefs) { WhalePortraits[app.prefs.avatarIndex(lessonId, WhalePortraits.size)] }
    val custom by produceState<ImageBitmap?>(null, app.avatars, revision) {
        value = withContext(Dispatchers.IO) {
            runCatching { android.graphics.BitmapFactory.decodeFile(app.avatars.file.path)?.asImageBitmap() }.getOrNull()
        }
    }
    val description = context.getString(R.string.deepseek_avatar)
    val shape = modifier.clip(CircleShape)
    if (custom != null) Image(custom!!, description, shape, contentScale = ContentScale.Crop)
    else Image(painterResource(portrait), description, shape, contentScale = ContentScale.Crop)
}

@Composable
fun ActionIcon(@DrawableRes icon: Int, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size(24.dp))
    }
}

@Composable
fun WelcomeCard(compact: Boolean = false, general: Boolean = false) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    HeroCard(
        colors = if (general) listOf(colors.tertiaryContainer, colors.primaryContainer) else listOf(colors.primaryContainer, colors.secondaryContainer),
        modifier = Modifier.padding(16.dp).fillMaxWidth(),
    ) {
        val ring = Modifier.border(3.dp, Color.White, CircleShape)
        // General chat gets its own banner and illustration, matching its home card.
        if (general) CustomImage(context.app.chatImage, R.drawable.whale_01_07, Modifier.size(88.dp).then(ring))
        else CustomImage(context.app.welcomeImage, R.drawable.whale_02_01, Modifier.size(if (compact) 64.dp else 88.dp).then(ring))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(context.getString(if (general) R.string.general_chat_tag else R.string.welcome_title), style = MaterialTheme.typography.titleMedium)
            Text(context.getString(when {
                general -> R.string.general_chat_welcome
                compact -> R.string.welcome_short
                else -> R.string.welcome_body
            }), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A user-replaceable illustration (Settings > Home illustrations), falling back to [default]. */
@Composable
fun CustomImage(files: com.feiyu.notes.settings.AvatarFiles, @DrawableRes default: Int, modifier: Modifier) {
    val revision by files.revision.collectAsStateWithLifecycle()
    val custom by produceState<ImageBitmap?>(null, files, revision) {
        value = withContext(Dispatchers.IO) {
            runCatching { android.graphics.BitmapFactory.decodeFile(files.file.path)?.asImageBitmap() }.getOrNull()
        }
    }
    val shape = modifier.clip(CircleShape)
    if (custom != null) Image(custom!!, null, shape, contentScale = ContentScale.Crop)
    else Image(painterResource(default), null, shape, contentScale = ContentScale.Crop)
}

/** Home entry to the pinned general chat; styled as a call to action, not as background. */
@Composable
fun GeneralChatCard(onClick: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    HeroCard(
        colors = listOf(colors.tertiaryContainer, colors.secondaryContainer),
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().testTag("general-chat"),
    ) {
        CustomImage(context.app.chatImage, R.drawable.whale_01_07, Modifier.size(72.dp).border(3.dp, Color.White, CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(color = colors.tertiary, contentColor = colors.onTertiary, shape = CircleShape) {
                Text(context.getString(R.string.general_chat_tag), Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
            }
            Text(context.getString(R.string.general_chat), style = MaterialTheme.typography.titleMedium)
            Text(context.getString(R.string.general_chat_hint), style = MaterialTheme.typography.bodySmall)
        }
        Surface(color = colors.primary, contentColor = colors.onPrimary, shape = CircleShape) {
            Icon(painterResource(R.drawable.ic_send), context.getString(R.string.start_chat), Modifier.padding(12.dp).size(20.dp))
        }
    }
}

/** The icon names the action: moon switches to dark, sun switches to light. */
@Composable
fun ThemeToggle() {
    val context = LocalContext.current
    val dark = com.feiyu.notes.ui.theme.LocalDarkTheme.current
    ActionIcon(
        if (dark) R.drawable.ic_sun else R.drawable.ic_moon,
        context.getString(if (dark) R.string.switch_light else R.string.switch_dark),
        { context.app.prefs.setTheme(if (dark) com.feiyu.notes.settings.ThemeMode.LIGHT else com.feiyu.notes.settings.ThemeMode.DARK) },
    )
}
