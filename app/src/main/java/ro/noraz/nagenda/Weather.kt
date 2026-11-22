package ro.noraz.nagenda

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

data class Wx(val temp: Int, val sym: String, val max: Int, val min: Int, val at: Long) {
    val isDay get() = !sym.endsWith("_night")
}
data class Hour(val label: String, val temp: Int, val sym: String)
data class Place(val name: String, val detail: String, val lat: Double, val lon: Double)

/** Weather from yr.no / MET Norway (free, no account, no ads). City search: Open-Meteo geocoding. */
object Weather {
    private const val STALE = 25 * 60 * 1000L
    @Volatile private var busy = false

    fun hasPlace(c: Context) = Prefs.placeName(c).isNotEmpty()

    fun cached(c: Context): Wx? {
        val sp = c.getSharedPreferences("nagenda", Context.MODE_PRIVATE)
        if (!sp.contains("wx_at") || !sp.contains("wx_sym")) return null
        return Wx(sp.getInt("wx_t", 0), sp.getString("wx_sym", "cloudy") ?: "cloudy",
            sp.getInt("wx_max", 0), sp.getInt("wx_min", 0), sp.getLong("wx_at", 0))
    }

    private fun save(c: Context, w: Wx) {
        c.getSharedPreferences("nagenda", Context.MODE_PRIVATE).edit()
            .putInt("wx_t", w.temp).putString("wx_sym", w.sym)
            .putInt("wx_max", w.max).putInt("wx_min", w.min).putLong("wx_at", w.at).apply()
    }

    fun hours(c: Context): List<Hour> =
        (c.getSharedPreferences("nagenda", Context.MODE_PRIVATE).getString("wx_hours", "") ?: "")
            .split(';').mapNotNull { p ->
                val f = p.split('|'); if (f.size == 3) f[1].toIntOrNull()?.let { Hour(f[0], it, f[2]) } else null
            }

    fun clear(c: Context) {
        c.getSharedPreferences("nagenda", Context.MODE_PRIVATE).edit().remove("wx_at").remove("wx_day").apply()
    }

    /** Blocking — call off the main thread. */
    fun fetch(c: Context): Wx? {
        if (!hasPlace(c)) return null
        // MET terms: max 4 decimals, identifying User-Agent
        val lat = String.format(java.util.Locale.US, "%.4f", Prefs.lat(c))
        val lon = String.format(java.util.Locale.US, "%.4f", Prefs.lon(c))
        return try {
            val j = JSONObject(get("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=$lat&lon=$lon"))
            val series = j.getJSONObject("properties").getJSONArray("timeseries")
            val zone = java.time.ZoneId.systemDefault()
            val today = java.time.LocalDate.now(zone)
            val first = series.getJSONObject(0).getJSONObject("data")
            val now = first.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature")
            val sym = first.optJSONObject("next_1_hours")?.optJSONObject("summary")?.optString("symbol_code")
                ?: first.optJSONObject("next_6_hours")?.optJSONObject("summary")?.optString("symbol_code")
                ?: "cloudy"
            var max = now; var min = now
            for (i in 0 until series.length()) {
                val e = series.getJSONObject(i)
                val day = java.time.Instant.parse(e.getString("time")).atZone(zone).toLocalDate()
                if (day != today) { if (day.isAfter(today)) break else continue }
                val t = e.getJSONObject("data").getJSONObject("instant").getJSONObject("details").getDouble("air_temperature")
                if (t > max) max = t; if (t < min) min = t
            }
            // keep the day's extremes across refreshes (the forecast only covers "from now on")
            val sp = c.getSharedPreferences("nagenda", Context.MODE_PRIVATE)
            if (sp.getString("wx_day", "") == today.toString()) {
                max = maxOf(max, sp.getFloat("wx_rmax", max.toFloat()).toDouble())
                min = minOf(min, sp.getFloat("wx_rmin", min.toFloat()).toDouble())
            }
            sp.edit().putString("wx_day", today.toString()).putFloat("wx_rmax", max.toFloat()).putFloat("wx_rmin", min.toFloat()).apply()
            fun u(v: Double) = (if (Prefs.units(c) == "f") v * 9 / 5 + 32 else v).roundToInt()
            // next hours: every 2 h, 5 slots
            val hf = java.time.format.DateTimeFormatter.ofPattern(
                if (android.text.format.DateFormat.is24HourFormat(c)) "HH:mm" else "h a", Loc.locale(c))
            val hours = (0 until 5).mapNotNull { k ->
                val i = 1 + k * 2
                if (i >= series.length()) return@mapNotNull null
                val e = series.getJSONObject(i)
                val d = e.getJSONObject("data")
                val hs = d.optJSONObject("next_1_hours")?.optJSONObject("summary")?.optString("symbol_code")
                    ?: d.optJSONObject("next_6_hours")?.optJSONObject("summary")?.optString("symbol_code") ?: "cloudy"
                val t = d.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature")
                "${hf.format(java.time.Instant.parse(e.getString("time")).atZone(zone))}|${u(t)}|$hs"
            }
            sp.edit().putString("wx_hours", hours.joinToString(";")).apply()
            Wx(u(now), sym, u(max), u(min), System.currentTimeMillis()).also { save(c, it) }
        } catch (e: Exception) {
            android.util.Log.w("ageNda", "weather", e); null
        }
    }

    /** Refresh in the background if the cache is old, then redraw widgets. */
    fun refreshIfStale(c: Context, force: Boolean = false) {
        if (!hasPlace(c) || busy) return
        val w = cached(c)
        val hasHours = c.getSharedPreferences("nagenda", Context.MODE_PRIVATE).contains("wx_hours")
        if (!force && hasHours && w != null && System.currentTimeMillis() - w.at < STALE) return
        busy = true
        val app = c.applicationContext
        Thread {
            try { if (fetch(app) != null) AgendaWidget.updateAll(app) } finally { busy = false }
        }.start()
    }

    fun search(query: String, lang: String): List<Place> = try {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val j = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=8&language=$lang&format=json"))
        val arr = j.optJSONArray("results")
        (0 until (arr?.length() ?: 0)).map { i ->
            val o = arr!!.getJSONObject(i)
            Place(
                o.getString("name"),
                listOfNotNull(o.optString("admin1").takeIf { it.isNotEmpty() }, o.optString("country").takeIf { it.isNotEmpty() })
                    .joinToString(", "),
                o.getDouble("latitude"), o.getDouble("longitude"),
            )
        }
    } catch (e: Exception) { emptyList() }

    private fun get(url: String): String {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 10_000; con.readTimeout = 10_000
        con.setRequestProperty("User-Agent", "ageNda/1.0 github.com/razvannicu15/ageNda")
        return con.inputStream.bufferedReader().use { it.readText() }.also { con.disconnect() }
    }

    // ---- MET symbol codes → icon + text  (e.g. "partlycloudy_day", "lightrainshowers_night", "heavyrainandthunder")
    fun icon(sym: String): Int {
        val night = sym.endsWith("_night")
        val b = sym.substringBefore('_')
        return when {
            "thunder" in b -> R.drawable.wx_storm
            "snow" in b || "sleet" in b -> R.drawable.wx_snow
            "rain" in b -> R.drawable.wx_rain
            b == "fog" -> R.drawable.wx_fog
            b == "cloudy" -> R.drawable.wx_cloudy
            b == "partlycloudy" || b == "fair" -> if (night) R.drawable.wx_partly_night else R.drawable.wx_partly_day
            b == "clearsky" -> if (night) R.drawable.wx_clear_night else R.drawable.wx_clear_day
            else -> R.drawable.wx_cloudy
        }
    }

    fun text(sym: String): Int {
        val b = sym.substringBefore('_')
        return when {
            "thunder" in b -> R.string.wx_storm
            "sleet" in b -> R.string.wx_sleet
            "snowshowers" in b -> R.string.wx_snow_showers
            "snow" in b -> R.string.wx_snow
            "rainshowers" in b -> R.string.wx_showers
            b.startsWith("light") && "rain" in b -> R.string.wx_light_rain
            b.startsWith("heavy") && "rain" in b -> R.string.wx_heavy_rain
            "rain" in b -> R.string.wx_rain
            b == "fog" -> R.string.wx_fog
            b == "cloudy" -> R.string.wx_cloudy
            b == "partlycloudy" -> R.string.wx_partly
            b == "fair" -> R.string.wx_mostly_clear
            b == "clearsky" -> R.string.wx_clear
            else -> R.string.wx_cloudy
        }
    }
}

/** Periodic weather refresh (every ~30 min when online). */
class WeatherJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            Weather.fetch(applicationContext)
            AgendaWidget.updateAll(applicationContext)
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val ID = 4712

        fun ensure(c: Context) {
            val js = c.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(ID) != null) return
            try {
                js.schedule(
                    JobInfo.Builder(ID, ComponentName(c, WeatherJob::class.java))
                        .setPeriodic(30 * 60 * 1000L)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPersisted(true)
                        .build(),
                )
            } catch (e: Exception) { android.util.Log.w("ageNda", "wxjob", e) }
        }

        fun cancel(c: Context) = c.getSystemService(JobScheduler::class.java).cancel(ID)
    }
}
