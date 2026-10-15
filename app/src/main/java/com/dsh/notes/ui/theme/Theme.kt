package com.dsh.notes.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = TealLightPrimary,
    onPrimary = TealLightOnPrimary,
    primaryContainer = TealLightContainer,
    onPrimaryContainer = TealLightOnContainer,
    secondary = AmberLightSecondary,
    onSecondary = AmberLightOnSecondary,
    secondaryContainer = AmberLightContainer,
    onSecondaryContainer = AmberLightOnContainer,
    tertiary = SlateLightTertiary,
    onTertiary = SlateLightOnTertiary,
    tertiaryContainer = SlateLightContainer,
    onTertiaryContainer = SlateLightOnContainer,
    background = PaperLight,
    onBackground = OnPaperLight,
    surface = PaperLight,
    onSurface = OnPaperLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    // 卡片用更亮的表面，和背景形成轻微层次，不靠阴影
    surfaceContainerLow = SurfaceLightHigh,
    surfaceContainer = SurfaceLightHigh,
    surfaceContainerHigh = SurfaceLightHigh,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight
)

private val DarkColors = darkColorScheme(
    primary = TealDarkPrimary,
    onPrimary = TealDarkOnPrimary,
    primaryContainer = TealDarkContainer,
    onPrimaryContainer = TealDarkOnContainer,
    secondary = AmberDarkSecondary,
    onSecondary = AmberDarkOnSecondary,
    secondaryContainer = AmberDarkContainer,
    onSecondaryContainer = AmberDarkOnContainer,
    tertiary = SlateDarkTertiary,
    onTertiary = SlateDarkOnTertiary,
    tertiaryContainer = SlateDarkContainer,
    onTertiaryContainer = SlateDarkOnContainer,
    background = InkDark,
    onBackground = OnInkDark,
    surface = InkDark,
    onSurface = OnInkDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    surfaceContainerLow = SurfaceDarkHigh,
    surfaceContainer = SurfaceDarkHigh,
    surfaceContainerHigh = SurfaceDarkHigh,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark
)

@Composable
fun NoteCraftTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Android 12+ 跟随系统壁纸取色。跟随主题色是默认行为，不想跟就在调用处传 false */
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // 状态栏图标明暗跟随主题，否则深色背景下时间电量看不清
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = NoteCraftTypography,
        content = content
    )
}

/** 录音按钮的红：深色模式下要用更亮的那一档，否则在深背景上发闷 */
val androidx.compose.material3.ColorScheme.recordColor: Color
    @androidx.compose.runtime.Composable
    get() = if (surface.luminance() < 0.5f) RecordRedDark else RecordRed

private fun Color.luminance(): Float =
    (0.299f * red + 0.587f * green + 0.114f * blue)
