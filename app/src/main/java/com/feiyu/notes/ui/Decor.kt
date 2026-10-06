package com.feiyu.notes.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.feiyu.notes.R
import com.feiyu.notes.ui.theme.LocalDarkTheme

// Shared playful pieces: accent badges, whale stickers, foam bubbles and the typing indicator.

/** Top bars sit on the page colour, so the pale-sea background reads as one sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun feiyuTopBarColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = Color.Transparent,
    scrolledContainerColor = MaterialTheme.colorScheme.background,
)

/** Hairline edge that keeps white cards visible on the pale page. */
@Composable
fun softBorder() = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))

/** Accent pairs for badges; notebooks and lessons cycle through them by ID so each keeps its colour. */
enum class Accent {
    INDIGO, SEA, BLUSH;

    val container: Color
        @Composable get() = when (this) {
            INDIGO -> MaterialTheme.colorScheme.primaryContainer
            SEA -> MaterialTheme.colorScheme.secondaryContainer
            BLUSH -> MaterialTheme.colorScheme.tertiaryContainer
        }
    val content: Color
        @Composable get() = when (this) {
            INDIGO -> MaterialTheme.colorScheme.onPrimaryContainer
            SEA -> MaterialTheme.colorScheme.onSecondaryContainer
            BLUSH -> MaterialTheme.colorScheme.onTertiaryContainer
        }

    companion object {
        fun of(id: Long): Accent = entries[id.mod(entries.size)]
    }
}

@Composable
fun IconBadge(@DrawableRes icon: Int, accent: Accent, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    Box(modifier.size(size).clip(CircleShape).background(accent.container), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(size * 0.56f), tint = accent.content)
    }
}

/** A notebook's first character on a soft rounded square, like a hand-labelled book; hidden from screen readers. */
@Composable
fun InitialBadge(name: String, accent: Accent, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val initial = name.trim().let { if (it.isEmpty()) "?" else String(Character.toChars(it.codePointAt(0))).uppercase() }
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(accent.container).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, color = accent.content, style = MaterialTheme.typography.titleMedium)
    }
}

/** A whale-girl sticker cropped into a white-ringed circle. */
@Composable
fun Sticker(@DrawableRes res: Int, size: Dp, modifier: Modifier = Modifier) {
    Image(
        painterResource(res), null,
        modifier.size(size).border(3.dp, Color.White, CircleShape).padding(3.dp).clip(CircleShape).background(Color.White),
        contentScale = ContentScale.Crop,
    )
}

/** Friendly empty list: a sticker and one line saying what to do next. */
@Composable
fun EmptyState(@DrawableRes sticker: Int, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Sticker(sticker, 96.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Foam bubbles rising at a card's right edge; [color]'s alpha scales all of them. */
fun Modifier.bubbles(color: Color): Modifier = drawBehind {
    val r = size.minDimension
    val right = size.width
    drawCircle(color.copy(alpha = color.alpha * 0.35f), r * 0.62f, Offset(right - r * 0.12f, r * 0.02f))
    drawCircle(color.copy(alpha = color.alpha * 0.25f), r * 0.26f, Offset(right - r * 0.70f, size.height - r * 0.04f))
    drawCircle(color.copy(alpha = color.alpha * 0.45f), r * 0.09f, Offset(right - r * 0.86f, r * 0.30f))
    drawCircle(color.copy(alpha = color.alpha * 0.40f), r * 0.05f, Offset(right - r * 1.05f, r * 0.62f))
}

/** Gradient banner with foam bubbles: the home welcome, general chat and key status cards. */
@Composable
fun HeroCard(
    colors: List<Color>,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val foam = Color.White.copy(alpha = if (LocalDarkTheme.current) 0.22f else 1f)
    val body = @Composable {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onPrimaryContainer) {
            Row(
                Modifier.fillMaxWidth().background(Brush.linearGradient(colors)).bubbles(foam).padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
    if (onClick == null) Surface(shape = MaterialTheme.shapes.large, color = Color.Transparent, modifier = modifier, content = body)
    else Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = Color.Transparent, modifier = modifier, content = body)
}

/** A settings row that opens another page: badge, title, optional hint and a chevron. */
@Composable
fun NavRow(@DrawableRes icon: Int, accent: Accent, title: String, hint: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconBadge(icon, accent)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Three bobbing dots while DeepSeek is writing; decorative, so screen readers skip it. */
@Composable
fun TypingDots(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val lift by transition.animateFloat(
                initialValue = 0f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(360), RepeatMode.Reverse, StartOffset(i * 120)),
                label = "dot$i",
            )
            Box(
                Modifier.size(7.dp)
                    .graphicsLayer { translationY = -lift * 4.dp.toPx(); alpha = 0.35f + 0.65f * lift }
                    .background(color, CircleShape),
            )
        }
    }
}
