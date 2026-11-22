package ro.noraz.nagenda

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri

data class AppEntry(val pkg: String, val label: String, val icon: Drawable)

object Apps {
    /** Weather apps of common phone makers, tried in order when the user hasn't picked one. */
    private val KNOWN_WEATHER = listOf(
        "com.sec.android.daemonapp",            // Samsung
        "com.google.android.apps.weather",      // Google / Pixel
        "com.miui.weather2",                    // Xiaomi
        "com.coloros.weather2", "com.oplus.weather", "net.oneplus.weather", // Oppo / OnePlus
        "com.huawei.android.totemweather",      // Huawei
        "com.asus.weather", "com.motorola.weather",
        "com.accuweather.android", "com.weather.Weather",
    )

    fun launch(c: Context, pkg: String): Intent? =
        if (pkg.isEmpty()) null else c.packageManager.getLaunchIntentForPackage(pkg)

    fun detectedWeatherPkg(c: Context): String? = KNOWN_WEATHER.firstOrNull { launch(c, it) != null }

    fun weatherIntent(c: Context): Intent =
        launch(c, Prefs.weatherApp(c))
            ?: detectedWeatherPkg(c)?.let { launch(c, it) }
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" +
                Uri.encode(Loc.ctx(c).getString(R.string.weather_query, Prefs.placeName(c)))))

    fun label(c: Context, pkg: String): String = try {
        val pm = c.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) { pkg }

    fun launchable(c: Context): List<AppEntry> {
        val pm = c.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        return pm.queryIntentActivities(q, 0)
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
            .distinctBy { it.pkg }
            .filter { it.pkg != c.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
