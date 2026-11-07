package xyz.elouan.sobosubamodloader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun SobosubaModLoaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
            dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme(
            primary = Color(0xFFB8C4FF),
            secondary = Color(0xFFC5C4DD),
            tertiary = Color(0xFFE7B9D2)
        )
        else -> lightColorScheme(
            primary = Color(0xFF4F5FA8),
            secondary = Color(0xFF5C5D72),
            tertiary = Color(0xFF86516F)
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
