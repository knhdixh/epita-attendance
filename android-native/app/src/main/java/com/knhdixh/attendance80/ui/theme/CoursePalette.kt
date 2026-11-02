package com.knhdixh.attendance80.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

// The web's course and countdown colours (--c-* in public/styles.css), light and dark.
private val LIGHT = mapOf("blue" to 0xFF2A6AE0, "green" to 0xFF2F9E57, "teal" to 0xFF12968C, "purple" to 0xFF7C4DDB, "pink" to 0xFFD6458F,
    "red" to 0xFFD1453B, "orange" to 0xFFE07A1F, "yellow" to 0xFFD9A514, "gray" to 0xFF808080)
private val DARK = mapOf("blue" to 0xFF5D8FF0, "green" to 0xFF4FB26B, "teal" to 0xFF2BB5A8, "purple" to 0xFFA882FF, "pink" to 0xFFF06AAC,
    "red" to 0xFFFF6F61, "orange" to 0xFFF0954A, "yellow" to 0xFFE8C04A, "gray" to 0xFF9A9A9A)

/** A named palette colour; unknown names are grey. */
@Composable @ReadOnlyComposable
fun paletteColor(name: String?): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val map = if (dark) DARK else LIGHT
    return Color(map[name] ?: map.getValue("gray"))
}
