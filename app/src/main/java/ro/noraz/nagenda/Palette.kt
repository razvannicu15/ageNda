package ro.noraz.nagenda

import android.content.Context
import android.content.res.Configuration
import android.os.Build

data class Palette(
    val bg: Int,
    val bgAlpha: Int,   // 0..255
    val text: Int,
    val muted: Int,
    val accent: Int,    // "today"
    val line: Int,      // separators (ARGB)
) {
    val lineRgb get() = line or 0xFF000000.toInt()
    val lineAlpha get() = line ushr 24
}

/** Four simple widget styles. */
object Palettes {
    val STYLES = listOf("glass", "graphite", "paper", "you")

    fun systemDark(c: Context) =
        (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun of(c: Context, style: String = Prefs.style(c)): Palette = when (style) {
        // Sticlă — wallpaper shows through, like Samsung/iOS clear widgets
        "glass" -> Palette(
            bg = 0xFF000000.toInt(), bgAlpha = 92,
            text = 0xFFFFFFFF.toInt(), muted = 0xB3FFFFFF.toInt(),
            accent = 0xFFFFD166.toInt(), line = 0x1FFFFFFF,
        )
        // Hârtie — iOS light
        "paper" -> Palette(
            bg = 0xFFFFFFFF.toInt(), bgAlpha = 255,
            text = 0xFF1C1C1E.toInt(), muted = 0xFF8E8E93.toInt(),
            accent = 0xFFFF3B30.toInt(), line = 0x1A3C3C43,
        )
        // Material You — tones from the wallpaper, follows system light/dark
        "you" -> if (Build.VERSION.SDK_INT >= 31) {
            if (systemDark(c)) Palette(
                bg = c.getColor(android.R.color.system_neutral1_900), bgAlpha = 255,
                text = c.getColor(android.R.color.system_neutral1_50),
                muted = c.getColor(android.R.color.system_neutral2_300),
                accent = c.getColor(android.R.color.system_accent1_200),
                line = (c.getColor(android.R.color.system_neutral2_500) and 0x00FFFFFF) or 0x33000000,
            ) else Palette(
                bg = c.getColor(android.R.color.system_accent2_50), bgAlpha = 255,
                text = c.getColor(android.R.color.system_neutral1_900),
                muted = c.getColor(android.R.color.system_neutral2_600),
                accent = c.getColor(android.R.color.system_accent1_600),
                line = (c.getColor(android.R.color.system_neutral2_400) and 0x00FFFFFF) or 0x33000000,
            )
        } else of(c, if (systemDark(c)) "graphite" else "paper")
        // Grafit — iOS dark
        else -> Palette(
            bg = 0xFF1C1C1E.toInt(), bgAlpha = 255,
            text = 0xFFFFFFFF.toInt(), muted = 0xFF8E8E93.toInt(),
            accent = 0xFFFF453A.toInt(), line = 0x2EFFFFFF,
        )
    }
}
