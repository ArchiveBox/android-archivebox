package io.archivebox.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFFA51C50), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E3), onPrimaryContainer = Color(0xFF400018),
    secondary = Color(0xFF74565F), secondaryContainer = Color(0xFFFED9E3),
    tertiary = Color(0xFF006B5E), tertiaryContainer = Color(0xFF9EF2DE),
    background = Color(0xFFFFF8F8), surface = Color(0xFFFFF8F8),
    surfaceContainer = Color(0xFFF6EDEE), surfaceContainerHigh = Color(0xFFF0E7E9)
)
private val Dark = darkColorScheme(
    primary = Color(0xFFFFB0C7), onPrimary = Color(0xFF650A30),
    primaryContainer = Color(0xFF85113F), onPrimaryContainer = Color(0xFFFFD9E3),
    secondary = Color(0xFFE3BDC8), tertiary = Color(0xFF83D5C2),
    background = Color(0xFF181214), surface = Color(0xFF181214),
    surfaceContainer = Color(0xFF251E21), surfaceContainerHigh = Color(0xFF30282B)
)
@Composable fun ArchiveTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
