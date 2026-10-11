package com.feiyu.notes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import com.feiyu.notes.R
import com.feiyu.notes.math.MathRenderer
import com.feiyu.notes.math.MathText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Native text and formula images share the app's typography, theme and accessibility scaling. */
@Composable
fun MathContent(source: String, modifier: Modifier = Modifier, markdown: Boolean = false, textStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge) {
    val parts = remember(source) { MathText.parse(source) }
    val density = LocalDensity.current
    val style = textStyle
    val color = LocalContentColor.current
    val size = with(density) { style.fontSize.toPx() }
    val images by produceState<Map<Int, Bitmap>>(emptyMap(), source, size, color) {
        value = emptyMap()
        value = withContext(Dispatchers.Default) {
            parts.mapIndexedNotNull { index, part ->
                if (part is MathText.Formula) MathRenderer.render(part.latex, size, color.toArgb())?.let { index to it }
                else null
            }.toMap()
        }
    }
    val context = LocalContext.current
    var copied by remember(source) { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val available = with(density) { maxWidth.toPx() }
        // Split only around display equations or inline equations wider than the reading column.
        val groups = remember(parts, images, available) {
            buildList<List<Int>> {
                var paragraph = mutableListOf<Int>()
                parts.forEachIndexed { index, part ->
                    val separate = part is MathText.Formula && (part.display || (images[index]?.width ?: 0) > available)
                    if (separate) {
                        if (paragraph.isNotEmpty()) add(paragraph)
                        add(listOf(index))
                        paragraph = mutableListOf()
                    } else paragraph += index
                }
                if (paragraph.isNotEmpty()) add(paragraph)
            }
        }
        Column {
            SelectionContainer {
                Column {
                    groups.forEach { indices ->
                        val first = parts[indices.first()]
                        val bitmap = images[indices.first()]
                        if (indices.size == 1 && first is MathText.Formula && bitmap != null &&
                            (first.display || bitmap.width > available)) {
                            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp).testTag("math-scroll")) {
                                Image(bitmap.asImageBitmap(), first.raw,
                                    Modifier.size(with(density) { bitmap.width.toDp() }, with(density) { bitmap.height.toDp() })
                                        .testTag("math-display"))
                            }
                            if (bitmap.width > available) Text(context.getString(R.string.scroll_formula), style = MaterialTheme.typography.bodySmall)
                        } else {
                            val inline = indices.mapNotNull { index ->
                                val image = images[index] ?: return@mapNotNull null
                                index.toString() to InlineTextContent(Placeholder(
                                    with(density) { image.width.toSp() }, with(density) { image.height.toSp() },
                                    PlaceholderVerticalAlign.TextCenter,
                                )) { Image(image.asImageBitmap(), null, Modifier.fillMaxSize().testTag("math-inline")) }
                            }.toMap()
                            val text = buildAnnotatedString {
                                indices.forEach { index ->
                                    if (images[index] != null) appendInlineContent(index.toString(), parts[index].raw)
                                    else if (markdown) append(markdownText(parts[index].raw)) else append(parts[index].raw)
                                }
                            }
                            if (text.isNotBlank()) Text(text, style = style, color = color, inlineContent = inline)
                        }
                    }
                }
            }
            if (parts.any { it is MathText.Formula }) TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("LaTeX", source))
                copied = true
            }, modifier = Modifier.testTag("copy-math-source")) {
                Text(context.getString(if (copied) R.string.copied else R.string.copy_math_source))
            }
        }
    }
}

private fun markdownText(source: String): androidx.compose.ui.text.AnnotatedString = buildAnnotatedString {
    val expression = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|\\*([^*]+)\\*|_([^_]+)_")
    var cursor = 0
    expression.findAll(source).forEach { match ->
        append(source.substring(cursor, match.range.first))
        val bold = match.groupValues[1].isNotEmpty() || match.groupValues[2].isNotEmpty()
        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = if (bold) androidx.compose.ui.text.font.FontWeight.Bold else null,
            fontStyle = if (bold) null else androidx.compose.ui.text.font.FontStyle.Italic))
        append(match.groupValues.drop(1).first { it.isNotEmpty() })
        pop()
        cursor = match.range.last + 1
    }
    append(source.substring(cursor))
}
