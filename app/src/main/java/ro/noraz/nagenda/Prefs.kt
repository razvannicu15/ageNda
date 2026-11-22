package ro.noraz.nagenda

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("nagenda", Context.MODE_PRIVATE)

    fun theme(c: Context) = sp(c).getString("theme", "auto") ?: "auto"
    fun style(c: Context) = sp(c).getString("style", "glass") ?: "glass"
    fun lang(c: Context) = sp(c).getString("lang", "auto") ?: "auto"
    fun days(c: Context) = sp(c).getInt("days", 14)
    fun showLocation(c: Context) = sp(c).getBoolean("location", true)
    fun showCountdown(c: Context) = sp(c).getBoolean("countdown", false)
    fun hidden(c: Context): Set<Long> =
        (sp(c).getStringSet("hidden", emptySet()) ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet()

    fun units(c: Context) = sp(c).getString("units", "c") ?: "c"
    fun placeName(c: Context) = sp(c).getString("place", "") ?: ""
    fun lat(c: Context) = sp(c).getFloat("lat", 0f).toDouble()
    fun lon(c: Context) = sp(c).getFloat("lon", 0f).toDouble()
    fun weatherApp(c: Context) = sp(c).getString("wx_app", "") ?: ""
    fun previewKind(c: Context) = sp(c).getString("preview", "clock") ?: "clock"
    fun setPlace(c: Context, name: String, lat: Double, lon: Double) {
        sp(c).edit().putString("place", name).putFloat("lat", lat.toFloat()).putFloat("lon", lon.toFloat()).apply()
    }

    fun textScale(c: Context) = if (sp(c).getString("size", "normal") == "large") 1.15f else 1f
    fun size(c: Context) = sp(c).getString("size", "normal") ?: "normal"

    fun dot(c: Context) = sp(c).getString("dot", "mid") ?: "mid"

    fun dedupe(c: Context) = sp(c).getBoolean("dedupe", true)
    fun hourly(c: Context) = sp(c).getBoolean("hourly", true)

    fun lastRefresh(c: Context) = sp(c).getLong("last", 0L)
    fun setLastRefresh(c: Context, t: Long) = sp(c).edit().putLong("last", t).apply()

    fun put(c: Context, key: String, value: Any) {
        val e = sp(c).edit()
        when (value) {
            is String -> e.putString(key, value)
            is Int -> e.putInt(key, value)
            is Boolean -> e.putBoolean(key, value)
        }
        e.apply()
    }

    fun setHidden(c: Context, ids: Set<Long>) {
        sp(c).edit().putStringSet("hidden", ids.map { it.toString() }.toSet()).apply()
    }
}
