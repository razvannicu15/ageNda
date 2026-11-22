package ro.noraz.nagenda

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("nagenda", Context.MODE_PRIVATE)

    fun theme(c: Context) = sp(c).getString("theme", "auto") ?: "auto"
    fun colors(c: Context) = sp(c).getString("colors", "dynamic") ?: "dynamic"
    fun lang(c: Context) = sp(c).getString("lang", "auto") ?: "auto"
    fun opacity(c: Context) = sp(c).getInt("opacity", 90)
    fun days(c: Context) = sp(c).getInt("days", 14)
    fun showLocation(c: Context) = sp(c).getBoolean("location", true)
    fun showCountdown(c: Context) = sp(c).getBoolean("countdown", false)
    fun hidden(c: Context): Set<Long> =
        (sp(c).getStringSet("hidden", emptySet()) ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet()

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
