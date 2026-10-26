package com.knhdixh.attendance80.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class ExtraColors(
    val green: Color,
    val orange: Color,
    val red: Color,
    val purple: Color,
    val text2: Color,
    val text3: Color,
    val line: Color,
    val line2: Color,
    val sidebar: Color,
    val raised: Color,
)

val LocalExtraColors = staticCompositionLocalOf {
    ExtraColors(
        green = GreenLight,
        orange = OrangeLight,
        red = RedLight,
        purple = PurpleLight,
        text2 = Text2Light,
        text3 = Text3Light,
        line = LineLight,
        line2 = Line2Light,
        sidebar = SidebarLight,
        raised = RaisedLight,
    )
}

private val LightColorScheme = lightColorScheme(
    background = BgLight,
    surface = BgLight,
    surfaceVariant = SidebarLight,
    surfaceContainerHigh = RaisedLight,
    onBackground = TextLight,
    onSurface = TextLight,
    onSurfaceVariant = Text2Light,
    outline = Line2Light,
    outlineVariant = LineLight,
    primary = AccentLight,
    onPrimary = Color.White,
    primaryContainer = AccentSoftLight,
    onPrimaryContainer = AccentLight,
    error = RedLight,
    onError = Color.White,
)

private val DarkColorScheme = darkColorScheme(
    background = BgDark,
    surface = BgDark,
    surfaceVariant = SidebarDark,
    surfaceContainerHigh = RaisedDark,
    onBackground = TextDark,
    onSurface = TextDark,
    onSurfaceVariant = Text2Dark,
    outline = Line2Dark,
    outlineVariant = LineDark,
    primary = AccentDark,
    onPrimary = Color.Black,
    primaryContainer = AccentSoftDark,
    onPrimaryContainer = AccentDark,
    error = RedDark,
    onError = Color.Black,
)

// Small radii: 4dp chips, 6dp buttons/segmented controls, 8dp cards/inputs, 12-14dp sheets/dialogs
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(14.dp),
)

@Composable
fun Attendance80Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extraColors = if (darkTheme) {
        ExtraColors(
            green = GreenDark,
            orange = OrangeDark,
            red = RedDark,
            purple = PurpleDark,
            text2 = Text2Dark,
            text3 = Text3Dark,
            line = LineDark,
            line2 = Line2Dark,
            sidebar = SidebarDark,
            raised = RaisedDark,
        )
    } else {
        ExtraColors(
            green = GreenLight,
            orange = OrangeLight,
            red = RedLight,
            purple = PurpleLight,
            text2 = Text2Light,
            text3 = Text3Light,
            line = LineLight,
            line2 = Line2Light,
            sidebar = SidebarLight,
            raised = RaisedLight,
        )
    }

    CompositionLocalProvider(LocalExtraColors provides extraColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = Shapes,
            content = content,
        )
    }
}
