package com.photon.remote.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

/** 浅色基准配色（Material 3 基线紫色系默认值） */
private val LightColors = lightColorScheme()

/** 深色基准配色（Material 3 基线默认值） */
private val DarkColors = darkColorScheme()

/**
 * 由种子色 + 明度生成简化的 M3 风格配色（非动态取色设备使用，计划 §5.1）。
 * 仅覆盖 primary 族：primary / onPrimary / primaryContainer / onPrimaryContainer，
 * 其余沿用基线配色，保证强调色肉眼可辨。
 */
private fun schemeFromSeed(seed: Color, dark: Boolean): ColorScheme {
    val base = if (dark) DarkColors else LightColors
    val seedLum = seed.luminance()
    val onPrimary = if (seedLum > 0.5f) Color(0xFF000000) else Color(0xFFFFFFFF)
    // 容器色：浅色模式向白色混合、深色模式向黑色混合
    val container = if (dark) lerp(seed, Color.Black, 0.55f) else lerp(seed, Color.White, 0.72f)
    val onContainer = if (dark) lerp(seed, Color.White, 0.8f) else lerp(seed, Color.Black, 0.55f)
    return base.copy(
        primary = seed,
        onPrimary = onPrimary,
        primaryContainer = container,
        onPrimaryContainer = onContainer
    )
}

/**
 * 纯黑 AMOLED 变体（计划 §5.1，P2 设置开关，骨架阶段默认关闭）：
 * 将表面色族压到纯黑（省电 + 对比度），仅在深色模式启用。
 */
private fun ColorScheme.toAmoledDark(): ColorScheme {
    val black = Color.Black
    val raised = Color(0xFF111113)      // 极低抬升（卡片等）
    val raisedMore = Color(0xFF1C1B1F)  // 稍高抬升（浮层/分隔）
    return copy(
        background = black,
        surface = black,
        surfaceContainerLowest = black,
        surfaceContainerLow = raised,
        surfaceContainer = raised,
        surfaceContainerHigh = raisedMore,
        surfaceContainerHighest = raisedMore,
        surfaceVariant = raisedMore,
    )
}

/**
 * 应用主题入口（计划 §5.1）。
 *
 * @param darkTheme 深色模式（默认跟随系统）
 * @param pureBlackAmoled 纯黑 AMOLED 变体（仅深色模式下生效）
 * @param accentSeed 强调色种子（Android 12+ 动态取色时忽略，仅低版本设备使用）
 */
@Composable
fun PhotonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    pureBlackAmoled: Boolean = false,
    accentSeed: Color = AccentSeed,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        // Android 12+（API 31+）：动态取色，跟随壁纸
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        // 低版本设备：用强调色种子生成配色
        else -> schemeFromSeed(accentSeed, darkTheme)
    }
    val finalScheme = if (pureBlackAmoled && darkTheme) colorScheme.toAmoledDark() else colorScheme
    MaterialTheme(
        colorScheme = finalScheme,
        typography = Typography,
        content = content
    )
}
