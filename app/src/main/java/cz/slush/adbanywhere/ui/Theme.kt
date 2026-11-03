/*
 * Theme - Material 3 with dynamic color on Android 12+.
 */
package cz.slush.adbanywhere.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7ED8B0),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF00513D),
    onPrimaryContainer = Color(0xFF9CF5CB),
    secondary = Color(0xFFB4CCBF),
    secondaryContainer = Color(0xFF354B41),
    surface = Color(0xFF0F1512),
    background = Color(0xFF0F1512),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B52),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9CF5CB),
    onPrimaryContainer = Color(0xFF002117),
    secondary = Color(0xFF4B6358),
    secondaryContainer = Color(0xFFCDE9DB),
    surface = Color(0xFFF6FBF7),
    background = Color(0xFFF6FBF7),
)

@Composable
fun AdbAnywhereTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        content = content,
    )
}
