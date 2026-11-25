package com.ledger.offline.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.ledger.offline.domain.model.ThemeTone

/** 每种色调的主色 / 容器色。 */
private data class ToneColors(
    val primary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color
)

private val LightTones = mapOf(
    ThemeTone.GREEN to ToneColors(
        Color(0xFF0B6E4F), Color(0xFFB8EBD9), Color(0xFF064D37),
        Color(0xFFB4560A), Color(0xFFFFE0C2), Color(0xFF3B1A00)
    ),
    ThemeTone.BLUE to ToneColors(
        Color(0xFF1D5FA8), Color(0xFFD3E3F8), Color(0xFF0B3C6E),
        Color(0xFF0A6B7C), Color(0xFFCCEFF5), Color(0xFF00363D)
    ),
    ThemeTone.PURPLE to ToneColors(
        Color(0xFF6A4C93), Color(0xFFE6DCF5), Color(0xFF3B2A55),
        Color(0xFF9A5B2E), Color(0xFFFFE1C7), Color(0xFF3B1A00)
    ),
    ThemeTone.ORANGE to ToneColors(
        Color(0xFFB4560A), Color(0xFFFFE0C2), Color(0xFF3B1A00),
        Color(0xFF7A5A00), Color(0xFFFFE9A8), Color(0xFF2B1D00)
    ),
    ThemeTone.SAKURA_PINK to ToneColors(
        Color(0xFFD81B60), Color(0xFFFFD9E2), Color(0xFF5C0A2A),
        Color(0xFF8D6E63), Color(0xFFFFE0D6), Color(0xFF3E2723)
    ),
    ThemeTone.MONO to ToneColors(
        Color(0xFF212121), Color(0xFFE0E0E0), Color(0xFF000000),
        Color(0xFF616161), Color(0xFFEEEEEE), Color(0xFF1A1A1A)
    )
)

private val DarkTones = mapOf(
    ThemeTone.GREEN to ToneColors(
        Color(0xFF7BD5AE), Color(0xFF005236), Color(0xFF9AF2CB),
        Color(0xFFE9B98A), Color(0xFF653E17), Color(0xFFFFDDBD)
    ),
    ThemeTone.BLUE to ToneColors(
        Color(0xFF9CC7F5), Color(0xFF0B3C6E), Color(0xFFD3E3F8),
        Color(0xFF8ACBD6), Color(0xFF00505C), Color(0xFFB8ECF2)
    ),
    ThemeTone.PURPLE to ToneColors(
        Color(0xFFCFB8F0), Color(0xFF4A3566), Color(0xFFEDE0FB),
        Color(0xFFE9B98A), Color(0xFF653E17), Color(0xFFFFDDBD)
    ),
    ThemeTone.ORANGE to ToneColors(
        Color(0xFFF2C08C), Color(0xFF653E17), Color(0xFFFFDDBD),
        Color(0xFFE9C46A), Color(0xFF4A3A00), Color(0xFFFFE9A8)
    ),
    ThemeTone.SAKURA_PINK to ToneColors(
        Color(0xFFF48FB1), Color(0xFF5C0A2A), Color(0xFFFFD9E2),
        Color(0xFFD7B98C), Color(0xFF4A3A1A), Color(0xFFFFE9C7)
    ),
    ThemeTone.MONO to ToneColors(
        Color(0xFFE0E0E0), Color(0xFF424242), Color(0xFFFFFFFF),
        Color(0xFFBDBDBD), Color(0xFF333333), Color(0xFFEDEDED)
    )
)

private fun lightColors(tone: ToneColors): ColorScheme = lightColorScheme(
    primary = tone.primary,
    onPrimary = Color.White,
    primaryContainer = tone.primaryContainer,
    onPrimaryContainer = tone.onPrimaryContainer,
    secondary = tone.secondary,
    onSecondary = Color.White,
    secondaryContainer = tone.secondaryContainer,
    onSecondaryContainer = tone.onSecondaryContainer,
    background = Color(0xFFFBF7F0),
    onBackground = Color(0xFF1D1B17),
    surface = Color(0xFFFFFBF5),
    onSurface = Color(0xFF1D1B17),
    surfaceVariant = Color(0xFFEDE7DD),
    onSurfaceVariant = Color(0xFF4C463C),
    outline = Color(0xFF7D776C),
    error = Color(0xFFB3261E),
    onError = Color.White
)

private fun darkColors(tone: ToneColors): ColorScheme = darkColorScheme(
    primary = tone.primary,
    onPrimary = Color(0xFF10221C),
    primaryContainer = tone.primaryContainer,
    onPrimaryContainer = tone.onPrimaryContainer,
    secondary = tone.secondary,
    onSecondary = Color(0xFF1F1A12),
    secondaryContainer = tone.secondaryContainer,
    onSecondaryContainer = tone.onSecondaryContainer,
    background = Color(0xFF14130F),
    onBackground = Color(0xFFE7E2DA),
    surface = Color(0xFF1C1B16),
    onSurface = Color(0xFFE7E2DA),
    surfaceVariant = Color(0xFF4C463C),
    onSurfaceVariant = Color(0xFFCFC7BA),
    outline = Color(0xFF979184),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410)
)

/**
 * 全应用主题：按用户选择的色调（墨绿 / 钴蓝 / 暮紫 / 暖橙）生成配色，
 * 深色模式跟随系统。色值只影响观感，不承载业务逻辑。
 */
@Composable
fun LedgerTheme(
    tone: ThemeTone = ThemeTone.GREEN,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val palette = if (darkTheme) {
        DarkTones[tone] ?: DarkTones.getValue(ThemeTone.GREEN)
    } else {
        LightTones[tone] ?: LightTones.getValue(ThemeTone.GREEN)
    }
    MaterialTheme(
        colorScheme = if (darkTheme) darkColors(palette) else lightColors(palette),
        content = content
    )
}
