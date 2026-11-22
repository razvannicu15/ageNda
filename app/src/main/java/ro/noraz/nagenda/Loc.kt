package ro.noraz.nagenda

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** Language chosen in the app (Auto / Română / English), used by the widget too. */
object Loc {
    fun locale(c: Context): Locale = when (Prefs.lang(c)) {
        "ro" -> Locale.forLanguageTag("ro-RO")
        "en" -> Locale.forLanguageTag("en")
        else -> c.resources.configuration.locales[0]
    }

    fun ctx(c: Context): Context {
        if (Prefs.lang(c) == "auto") return c
        val conf = Configuration(c.resources.configuration)
        conf.setLocale(locale(c))
        return c.createConfigurationContext(conf)
    }
}
