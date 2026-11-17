package com.earlab.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF0B1020)
val Surface1 = Color(0xFF141B34)
val Surface2 = Color(0xFF1F2850)
val Teal = Color(0xFF5EEAD4)
val Violet = Color(0xFFA78BFA)
val Amber = Color(0xFFFBBF24)
val Red = Color(0xFFF87171)
val TextMain = Color(0xFFE6E9F5)
val TextDim = Color(0xFF8C95B8)

@Composable
fun EarLabTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Teal,
        onPrimary = Color(0xFF00201C),
        secondary = Violet,
        background = Bg,
        onBackground = TextMain,
        surface = Surface1,
        onSurface = TextMain,
        surfaceVariant = Surface2,
        onSurfaceVariant = TextDim,
        error = Red,
        outline = Color(0xFF2B3563),
    )
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
