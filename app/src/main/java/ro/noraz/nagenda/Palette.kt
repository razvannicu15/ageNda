package ro.noraz.nagenda

import android.content.Context
import android.content.res.Configuration
import android.os.Build

data class Palette(
    val bg: Int,
    val card: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
)

object Palettes {
    fun isDark(c: Context): Boolean = when (Prefs.theme(c)) {
        "dark" -> true
        "light" -> false
        else -> (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    fun of(c: Context): Palette {
        val dark = isDark(c)
        if (Build.VERSION.SDK_INT >= 31 && Prefs.colors(c) == "dynamic") {
            return if (dark) Palette(
                bg = c.getColor(android.R.color.system_neutral1_1000),
                card = c.getColor(android.R.color.system_neutral1_900),
                text = c.getColor(android.R.color.system_neutral1_50),
                muted = c.getColor(android.R.color.system_neutral2_300),
                accent = c.getColor(android.R.color.system_accent1_200),
            ) else Palette(
                bg = c.getColor(android.R.color.system_neutral2_100),
                card = c.getColor(android.R.color.system_neutral1_10),
                text = c.getColor(android.R.color.system_neutral1_900),
                muted = c.getColor(android.R.color.system_neutral2_600),
                accent = c.getColor(android.R.color.system_accent1_600),
            )
        }
        return if (dark) Palette(
            bg = 0xFF0E0E10.toInt(),
            card = 0xFF232226.toInt(),
            text = 0xFFF2F2F7.toInt(),
            muted = 0xFF9A9AA6.toInt(),
            accent = 0xFFB9A8FF.toInt(),
        ) else Palette(
            bg = 0xFFE4E3EA.toInt(),
            card = 0xFFFFFFFF.toInt(),
            text = 0xFF1C1B22.toInt(),
            muted = 0xFF6E6C78.toInt(),
            accent = 0xFF5B4BC4.toInt(),
        )
    }
}
