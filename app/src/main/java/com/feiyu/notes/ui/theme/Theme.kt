package com.feiyu.notes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.SideEffect
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feiyu.notes.app
import com.feiyu.notes.settings.AppLanguage
import com.feiyu.notes.settings.ThemeMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.feiyu.notes.R

// Taken from the whale girl: indigo hair, sky-blue streaks, blush cheeks and white frills.
// Pages use a pale-sea background; cards and sheets are white (light) or lifted navy (dark).
val LocalDarkTheme = staticCompositionLocalOf { false }

private val LightColors = lightColorScheme(
    primary = Color(0xFF3F57A8), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE3FF), onPrimaryContainer = Color(0xFF142A63),
    secondary = Color(0xFF1F78A3), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3EEFB), onSecondaryContainer = Color(0xFF0B4A66),
    tertiary = Color(0xFFB0476E), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCE6), onTertiaryContainer = Color(0xFF5B1733),
    background = Color(0xFFF2F6FD), onBackground = Color(0xFF1C2640),
    surface = Color.White, onSurface = Color(0xFF1C2640),
    surfaceVariant = Color(0xFFE5EBF7), onSurfaceVariant = Color(0xFF4A5671),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7F9FE),
    surfaceContainer = Color(0xFFF0F4FC), surfaceContainerHigh = Color(0xFFEAEFF9),
    surfaceContainerHighest = Color(0xFFE3E9F5),
    outline = Color(0xFF7A86A1), outlineVariant = Color(0xFFCCD5E6),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFB4C4FF), onPrimary = Color(0xFF15296A),
    primaryContainer = Color(0xFF2F4486), onPrimaryContainer = Color(0xFFDDE3FF),
    secondary = Color(0xFF8ED1F3), onSecondary = Color(0xFF00344A),
    secondaryContainer = Color(0xFF134D68), onSecondaryContainer = Color(0xFFD3EEFB),
    tertiary = Color(0xFFFFB1C8), onTertiary = Color(0xFF5B1733),
    tertiaryContainer = Color(0xFF7A2D4B), onTertiaryContainer = Color(0xFFFFDCE6),
    background = Color(0xFF0E1424), onBackground = Color(0xFFE3E8F6),
    surface = Color(0xFF182035), onSurface = Color(0xFFE3E8F6),
    surfaceVariant = Color(0xFF2B3550), onSurfaceVariant = Color(0xFFBCC6DD),
    surfaceContainerLowest = Color(0xFF0B101D), surfaceContainerLow = Color(0xFF141B2E),
    surfaceContainer = Color(0xFF1B2339), surfaceContainerHigh = Color(0xFF222B42),
    surfaceContainerHighest = Color(0xFF2A344D),
    outline = Color(0xFF8792AD), outlineVariant = Color(0xFF3A4561),
)
private val ReadingFont = FontFamily(
    Font(R.font.noto_sans_sc_regular, weight = FontWeight.Normal),
    Font(R.font.noto_sans_sc_medium, weight = FontWeight.Medium),
)
private val Base = Typography()
private val ReadingType = Typography(
    displayLarge = Base.displayLarge.copy(fontFamily = ReadingFont),
    displayMedium = Base.displayMedium.copy(fontFamily = ReadingFont),
    displaySmall = Base.displaySmall.copy(fontFamily = ReadingFont),
    headlineLarge = Base.headlineLarge.copy(fontFamily = ReadingFont),
    headlineMedium = Base.headlineMedium.copy(fontFamily = ReadingFont),
    headlineSmall = Base.headlineSmall.copy(fontFamily = ReadingFont),
    titleLarge = Base.titleLarge.copy(fontFamily = ReadingFont, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 30.sp),
    titleMedium = Base.titleMedium.copy(fontFamily = ReadingFont, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 26.sp),
    titleSmall = Base.titleSmall.copy(fontFamily = ReadingFont),
    bodyLarge = Base.bodyLarge.copy(fontFamily = ReadingFont, fontSize = 18.sp, lineHeight = 28.sp, letterSpacing = 0.sp),
    bodyMedium = Base.bodyMedium.copy(fontFamily = ReadingFont, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
    bodySmall = Base.bodySmall.copy(fontFamily = ReadingFont, fontSize = 14.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
    labelLarge = Base.labelLarge.copy(fontFamily = ReadingFont),
    labelMedium = Base.labelMedium.copy(fontFamily = ReadingFont),
    labelSmall = Base.labelSmall.copy(fontFamily = ReadingFont, fontSize = 13.sp, lineHeight = 18.sp),
)

@Composable
fun FeiyuTheme(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val display by base.app.prefs.display.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val dark = when (display.theme) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val activity = LocalActivity.current
    SideEffect {
        activity?.window?.let { window ->
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    // Recompose in place so language/font changes keep navigation and unsaved drafts.
    val localized = androidx.compose.runtime.remember(base, display.language, LocalConfiguration.current) { AppLanguage.context(base) }
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDarkTheme provides dark,
        LocalContext provides localized,
        LocalConfiguration provides localized.resources.configuration,
        LocalDensity provides Density(density.density, density.fontScale * display.textSize.scale),
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = ReadingType,
            // Rounder than Material defaults: soft chips, bubbly cards and pill-like fields.
            shapes = Shapes(
                extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(20.dp),
                large = RoundedCornerShape(26.dp), extraLarge = RoundedCornerShape(32.dp),
            ),
            content = content,
        )
    }
}
