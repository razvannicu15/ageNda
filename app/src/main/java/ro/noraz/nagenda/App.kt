package ro.noraz.nagenda

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.color.DynamicColors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        applyTheme(Prefs.theme(this))
        applyLang(Prefs.lang(this))
        DynamicColors.applyToActivitiesIfAvailable(this)
    }

    companion object {
        fun applyTheme(t: String) = AppCompatDelegate.setDefaultNightMode(
            when (t) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            },
        )

        fun applyLang(l: String) = AppCompatDelegate.setApplicationLocales(
            if (l == "auto") LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(l),
        )
    }
}
