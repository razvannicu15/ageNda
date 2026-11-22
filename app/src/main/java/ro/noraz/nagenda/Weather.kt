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

data class Wx(val temp: Int, val code: Int, val isDay: Boolean, val max: Int, val min: Int, val at: Long)
data class Place(val name: String, val detail: String, val lat: Double, val lon: Double)

/** Weather from Open-Meteo (free, no account, no ads). */
object Weather {
    private const val STALE = 25 * 60 * 1000L
    @Volatile private var busy = false

    fun hasPlace(c: Context) = Prefs.placeName(c).isNotEmpty()

    fun cached(c: Context): Wx? {
        val sp = c.getSharedPreferences("nagenda", Context.MODE_PRIVATE)
        if (!sp.contains("wx_at")) return null
        return Wx(sp.getInt("wx_t", 0), sp.getInt("wx_c", 0), sp.getBoolean("wx_d", true),
            sp.getInt("wx_max", 0), sp.getInt("wx_min", 0), sp.getLong("wx_at", 0))
    }

    private fun save(c: Context, w: Wx) {
        c.getSharedPreferences("nagenda", Context.MODE_PRIVATE).edit()
            .putInt("wx_t", w.temp).putInt("wx_c", w.code).putBoolean("wx_d", w.isDay)
            .putInt("wx_max", w.max).putInt("wx_min", w.min).putLong("wx_at", w.at).apply()
    }

    fun clear(c: Context) {
        c.getSharedPreferences("nagenda", Context.MODE_PRIVATE).edit().remove("wx_at").apply()
    }

    /** Blocking — call off the main thread. */
    fun fetch(c: Context): Wx? {
        if (!hasPlace(c)) return null
        val f = if (Prefs.units(c) == "f") "&temperature_unit=fahrenheit" else ""
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${Prefs.lat(c)}&longitude=${Prefs.lon(c)}" +
            "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min" +
            "&timezone=auto&forecast_days=1$f"
        return try {
            val j = JSONObject(get(url))
            val cur = j.getJSONObject("current")
            val d = j.getJSONObject("daily")
            Wx(
                temp = cur.getDouble("temperature_2m").roundToInt(),
                code = cur.getInt("weather_code"),
                isDay = cur.optInt("is_day", 1) == 1,
                max = d.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
                min = d.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
                at = System.currentTimeMillis(),
            ).also { save(c, it) }
        } catch (e: Exception) {
            android.util.Log.w("ageNda", "weather", e); null
        }
    }

    /** Refresh in the background if the cache is old, then redraw widgets. */
    fun refreshIfStale(c: Context, force: Boolean = false) {
        if (!hasPlace(c) || busy) return
        val w = cached(c)
        if (!force && w != null && System.currentTimeMillis() - w.at < STALE) return
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
        con.setRequestProperty("User-Agent", "ageNda (by norAz)")
        return con.inputStream.bufferedReader().use { it.readText() }.also { con.disconnect() }
    }

    // ---- WMO weather codes → icon + text
    fun icon(code: Int, day: Boolean): Int = when (code) {
        0 -> if (day) R.drawable.wx_clear_day else R.drawable.wx_clear_night
        1, 2 -> if (day) R.drawable.wx_partly_day else R.drawable.wx_partly_night
        3 -> R.drawable.wx_cloudy
        45, 48 -> R.drawable.wx_fog
        in 51..67, in 80..82 -> R.drawable.wx_rain
        in 71..77, 85, 86 -> R.drawable.wx_snow
        in 95..99 -> R.drawable.wx_storm
        else -> R.drawable.wx_cloudy
    }

    fun text(code: Int): Int = when (code) {
        0 -> R.string.wx_clear
        1 -> R.string.wx_mostly_clear
        2 -> R.string.wx_partly
        3 -> R.string.wx_cloudy
        45, 48 -> R.string.wx_fog
        in 51..57 -> R.string.wx_drizzle
        in 61..67 -> R.string.wx_rain
        in 71..77 -> R.string.wx_snow
        in 80..82 -> R.string.wx_showers
        85, 86 -> R.string.wx_snow_showers
        in 95..99 -> R.string.wx_storm
        else -> R.string.wx_cloudy
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
