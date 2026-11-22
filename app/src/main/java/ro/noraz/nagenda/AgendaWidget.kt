package ro.noraz.nagenda

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Widget 1: agenda only. */
open class AgendaWidget : AppWidgetProvider() {

    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) = updateAll(c)

    override fun onAppWidgetOptionsChanged(c: Context, mgr: AppWidgetManager, id: Int, opts: Bundle) = updateAll(c)

    override fun onReceive(c: Context, intent: Intent) {
        super.onReceive(c, intent)
        when (intent.action) {
            ACTION_REFRESH -> { updateAll(c); Weather.refreshIfStale(c, force = true) }
            ACTION_TICK,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED, Intent.ACTION_DATE_CHANGED -> updateAll(c)
        }
    }

    override fun onEnabled(c: Context) = updateAll(c)

    override fun onDisabled(c: Context) {
        if (count(c) == 0) {
            CalendarWatchJob.cancel(c)
            WeatherJob.cancel(c)
            c.getSystemService(AlarmManager::class.java).cancel(tickIntent(c))
        }
    }

    companion object {
        const val ACTION_REFRESH = "ro.noraz.nagenda.REFRESH"
        const val ACTION_TICK = "ro.noraz.nagenda.TICK"

        private fun ids(c: Context, cls: Class<*>) =
            AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c, cls))

        fun count(c: Context) = countOf(c, AgendaWidget::class.java) + countOf(c, ClockWidget::class.java) + countOf(c, WeatherWidget::class.java)
        fun countOf(c: Context, cls: Class<*>) = ids(c, cls).size

        fun updateAll(c: Context) {
            val mgr = AppWidgetManager.getInstance(c)
            val agendaIds = ids(c, AgendaWidget::class.java)
            val clockIds = ids(c, ClockWidget::class.java)
            val weatherIds = ids(c, WeatherWidget::class.java)
            if (agendaIds.isEmpty() && clockIds.isEmpty() && weatherIds.isEmpty()) return
            val items = Agenda.items(c)
            agendaIds.forEach { safe { render(c, mgr, it, R.layout.widget_agenda, agenda = true, clock = false) } }
            clockIds.forEach { safe { render(c, mgr, it, R.layout.widget_clock, agenda = true, clock = true) } }
            weatherIds.forEach { safe { render(c, mgr, it, R.layout.widget_clockonly, agenda = false, clock = true) } }
            safe {
                @Suppress("DEPRECATION")
                mgr.notifyAppWidgetViewDataChanged(agendaIds + clockIds, R.id.list)
                schedule(c, items)
                if (clockIds.isNotEmpty() || weatherIds.isNotEmpty()) {
                    WeatherJob.ensure(c)
                    Weather.refreshIfStale(c)
                }
            }
            Prefs.setLastRefresh(c, System.currentTimeMillis())
        }

        private inline fun safe(block: () -> Unit) {
            try { block() } catch (e: Exception) { android.util.Log.e("ageNda", "widget", e) }
        }

        fun refreshIntent(c: Context): PendingIntent = PendingIntent.getBroadcast(
            c, 3, Intent(c, AgendaWidget::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE,
        )

        private fun render(c: Context, mgr: AppWidgetManager, id: Int, layout: Int, agenda: Boolean, clock: Boolean) {
            val rv = RemoteViews(c.packageName, layout)
            if (agenda) renderAgenda(c, rv, id)
            if (clock) renderClock(c, rv)
            mgr.updateAppWidget(id, rv)
        }

        private fun renderAgenda(c: Context, rv: RemoteViews, id: Int) {
            val lc = Loc.ctx(c)
            val loc = Loc.locale(c)
            val p = Palettes.of(c)
            val today = LocalDate.now()

            rv.setInt(R.id.bg, "setColorFilter", p.bg)
            rv.setInt(R.id.bg, "setImageAlpha", p.bgAlpha)
            val svc = Intent(c, AgendaService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            @Suppress("DEPRECATION")
            rv.setRemoteAdapter(R.id.list, svc)
            rv.setEmptyView(R.id.list, R.id.empty)
            rv.setPendingIntentTemplate(
                R.id.list,
                PendingIntent.getActivity(
                    c, 10, Intent(c, OpenEventActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                ),
            )

            rv.setTextColor(R.id.empty, p.muted)
            if (Agenda.hasPerm(c)) {
                rv.setTextViewText(R.id.empty, lc.getString(R.string.empty_days, Prefs.days(c)))
                rv.setOnClickPendingIntent(R.id.empty, openCalendar(c))
            } else {
                rv.setTextViewText(R.id.empty, lc.getString(R.string.need_permission))
                rv.setOnClickPendingIntent(R.id.empty, openApp(c))
            }

        }

        private fun renderClock(c: Context, rv: RemoteViews) {
            val lc = Loc.ctx(c)
            val w = Weather.cached(c)
            val unit = "°"
            if (!Weather.hasPlace(c)) {
                rv.setTextViewText(R.id.wx_temp, "—")
                rv.setTextViewText(R.id.wx_cond, lc.getString(R.string.wx_set_place))
                rv.setTextViewText(R.id.wx_hilo, "")
                rv.setViewVisibility(R.id.wx_icon, View.GONE)
                rv.setOnClickPendingIntent(R.id.wx_block, openApp(c))
                rv.setOnClickPendingIntent(R.id.wx_icon, openApp(c))
            } else {
                if (w == null) {
                    rv.setTextViewText(R.id.wx_temp, "—")
                    rv.setTextViewText(R.id.wx_cond, lc.getString(R.string.wx_loading))
                    rv.setTextViewText(R.id.wx_hilo, Prefs.placeName(c))
                    rv.setViewVisibility(R.id.wx_icon, View.GONE)
                } else {
                    rv.setTextViewText(R.id.wx_temp, "${w.temp}$unit")
                    rv.setTextViewText(R.id.wx_cond, lc.getString(Weather.text(w.sym)))
                    rv.setTextViewText(R.id.wx_hilo, "↑${w.max}$unit ↓${w.min}$unit · ${Prefs.placeName(c)}")
                    rv.setViewVisibility(R.id.wx_icon, View.VISIBLE)
                    rv.setImageViewResource(R.id.wx_icon, Weather.icon(w.sym))
                }
                val wx = openWeather(c)
                rv.setOnClickPendingIntent(R.id.wx_block, wx)
                rv.setOnClickPendingIntent(R.id.wx_icon, wx)
            }
            rv.setOnClickPendingIntent(
                R.id.clock_block,
                PendingIntent.getActivity(
                    c, 20, Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }

        private fun openWeather(c: Context) = PendingIntent.getActivity(
            c, 21, Apps.weatherIntent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun openCalendar(c: Context): PendingIntent {
            val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
                .appendPath(System.currentTimeMillis().toString()).build()
            return PendingIntent.getActivity(
                c, 1, Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        private fun openApp(c: Context) = PendingIntent.getActivity(
            c, 4, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )

        private fun tickIntent(c: Context) = PendingIntent.getBroadcast(
            c, 5, Intent(c, AgendaWidget::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** Refresh at midnight, when an event starts/ends, and whenever the calendar changes. */
        private fun schedule(c: Context, items: List<CalItem>) {
            CalendarWatchJob.schedule(c)
            val zone = ZoneId.systemDefault()
            val midnight = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() + 3_000
            val next = minOf(midnight, (Agenda.nextChange(items) ?: Long.MAX_VALUE) + 1_000)
            c.getSystemService(AlarmManager::class.java).setWindow(AlarmManager.RTC, next, 60_000, tickIntent(c))
        }
    }
}

/** Widget 2: clock + weather + agenda. Same behaviour, different layout. */
class ClockWidget : AgendaWidget()

/** Widget 3: clock + weather only. */
class WeatherWidget : AgendaWidget()
