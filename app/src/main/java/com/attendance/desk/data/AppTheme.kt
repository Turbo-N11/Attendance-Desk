package com.attendance.desk.data

import androidx.compose.ui.graphics.Color

data class AppTheme(
    val id: String,
    val name: String,
    val subtitle: String,
    val bg: Color,
    val surface: Color,
    val card: Color,
    val border: Color,
    val primary: Color,
    val primaryDark: Color,
    val secondary: Color,
    val accentLight: Color,
    val text: Color,
    val textSec: Color,
    val muted: Color
)

object AppThemes {
    val Cream = AppTheme(
        id = "cream",
        name = "Cream Paper",
        subtitle = "Warm • Paper #FFFBF0",
        bg = Color(0xFFFFFBF0),
        surface = Color(0xFFFFF8E8),
        card = Color(0xFFFFFFFF),
        border = Color(0xFFE8DDD0),
        primary = Color(0xFFA67C52),
        primaryDark = Color(0xFF8C6239),
        secondary = Color(0xFFE9D5B8),
        accentLight = Color(0xFFF5E6CC),
        text = Color(0xFF4A3426),
        textSec = Color(0xFF8C7A6B),
        muted = Color(0xFFB0A090)
    )
    val Ocean = AppTheme(
        id = "ocean",
        name = "Ocean Blue",
        subtitle = "Cool • Blue #F0F7FF",
        bg = Color(0xFFF0F7FF),
        surface = Color(0xFFE6F0FF),
        card = Color(0xFFFFFFFF),
        border = Color(0xFFC2D5E9),
        primary = Color(0xFF1565C0),
        primaryDark = Color(0xFF0D47A1),
        secondary = Color(0xFF90CAF9),
        accentLight = Color(0xFFE3F2FD),
        text = Color(0xFF1A2E4D),
        textSec = Color(0xFF5A7090),
        muted = Color(0xFF8A9BB8)
    )
    val Forest = AppTheme(
        id = "forest",
        name = "Forest Green",
        subtitle = "Fresh • Green #F1F8E6",
        bg = Color(0xFFF1F8F0),
        surface = Color(0xFFE8F5E9),
        card = Color(0xFFFFFFFF),
        border = Color(0xFFC8E6C9),
        primary = Color(0xFF2E7D32),
        primaryDark = Color(0xFF1B5E20),
        secondary = Color(0xFFA5D6A7),
        accentLight = Color(0xFFE8F5E9),
        text = Color(0xFF1B331C),
        textSec = Color(0xFF5A7A5C),
        muted = Color(0xFF8BA98C)
    )
    val Sunset = AppTheme(
        id = "sunset",
        name = "Sunset Amber",
        subtitle = "Warm • Amber #FFF8F0",
        bg = Color(0xFFFFF8F0),
        surface = Color(0xFFFFF3E0),
        card = Color(0xFFFFFFFF),
        border = Color(0xFFFFE0B2),
        primary = Color(0xFFEF6C00),
        primaryDark = Color(0xFFE65100),
        secondary = Color(0xFFFFCC80),
        accentLight = Color(0xFFFFF3E0),
        text = Color(0xFF4A2C12),
        textSec = Color(0xFF8C6A4A),
        muted = Color(0xFFB08D6B)
    )
    val Lavender = AppTheme(
        id = "lavender",
        name = "Lavender Mist",
        subtitle = "Soft • Purple #F8F0FF",
        bg = Color(0xFFF8F0FF),
        surface = Color(0xFFF3E8FF),
        card = Color(0xFFFFFFFF),
        border = Color(0xFFE0C3FC),
        primary = Color(0xFF7C3AED),
        primaryDark = Color(0xFF6D28D9),
        secondary = Color(0xFFDDD6FE),
        accentLight = Color(0xFFF5F3FF),
        text = Color(0xFF2D1B4E),
        textSec = Color(0xFF6B5A8C),
        muted = Color(0xFF9A8BB8)
    )
    val Midnight = AppTheme(
        id = "midnight",
        name = "Midnight",
        subtitle = "Dark • Slate #121318",
        bg = Color(0xFF121318),
        surface = Color(0xFF1E1E24),
        card = Color(0xFF2A2A32),
        border = Color(0xFF3A3A42),
        primary = Color(0xFF90CAF9),
        primaryDark = Color(0xFF64B5F6),
        secondary = Color(0xFF37474F),
        accentLight = Color(0xFF2C3A4A),
        text = Color(0xFFE8E8EF),
        textSec = Color(0xFFA0A0B2),
        muted = Color(0xFF75758A)
    )

    val all = listOf(Cream, Ocean, Forest, Sunset, Lavender, Midnight)
    fun byId(id: String): AppTheme = all.find { it.id == id } ?: Cream
}

// Simple manager to make global Cream* vals react to theme changes via composition
object ThemeManager {
    var current: AppTheme = AppThemes.Cream
        private set
    private var listeners = mutableListOf<() -> Unit>()
    fun setTheme(theme: AppTheme) {
        current = theme
        listeners.forEach { it() }
    }
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
}
