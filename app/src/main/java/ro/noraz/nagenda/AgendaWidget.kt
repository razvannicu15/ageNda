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

        fun count(c: Context) = ids(c, AgendaWidget::class.java).size + ids(c, ClockWidget::class.java).size
        fun clockCount(c: Context) = ids(c, ClockWidget::class.java).size

        fun updateAll(c: Context) {
            val mgr = AppWidgetManager.getInstance(c)
            val agendaIds = ids(c, AgendaWidget::class.java)
            val clockIds = ids(c, ClockWidget::class.java)
            if (agendaIds.isEmpty() && clockIds.isEmpty()) return
            val items = Agenda.items(c)
            agendaIds.forEach { safe { render(c, mgr, it, items, clock = false) } }
            clockIds.forEach { safe { render(c, mgr, it, items, clock = true) } }
            safe {
                @Suppress("DEPRECATION")
                mgr.notifyAppWidgetViewDataChanged(agendaIds + clockIds, R.id.list)
                schedule(c, items)
                if (clockIds.isNotEmpty()) {
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

        private fun render(c: Context, mgr: AppWidgetManager, id: Int, items: List<CalItem>, clock: Boolean) {
            val rv = RemoteViews(c.packageName, if (clock) R.layout.widget_clock else R.layout.widget_agenda)
            renderAgenda(c, rv, id)
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
            rv.setInt(R.id.header_line, "setColorFilter", p.lineRgb)
            rv.setInt(R.id.header_line, "setImageAlpha", p.lineAlpha)
            for (ic in intArrayOf(R.id.ic_cal, R.id.btn_add, R.id.btn_refresh)) rv.setInt(ic, "setColorFilter", p.text)
            rv.setTextViewText(R.id.weekday, DateTimeFormatter.ofPattern("EEEE, d MMMM", loc).format(today).cap(loc))
            rv.setTextColor(R.id.weekday, p.text)

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

            rv.setOnClickPendingIntent(R.id.header, openCalendar(c))
            rv.setOnClickPendingIntent(
                R.id.btn_add,
                PendingIntent.getActivity(
                    c, 2,
                    Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            rv.setOnClickPendingIntent(R.id.btn_refresh, refreshIntent(c))
        }

        private fun renderClock(c: Context, rv: RemoteViews) {
            val lc = Loc.ctx(c)
            val w = Weather.cached(c)
            val unit = "°"
            if (!Weather.hasPlace(c)) {
                rv.setTextViewText(R.id.wx_temp, "—")
                rv.setTextViewText(R.id.wx_place, lc.getString(R.string.wx_set_place))
                rv.setTextViewText(R.id.wx_cond, "")
                rv.setTextViewText(R.id.wx_hilo, "")
                rv.setViewVisibility(R.id.wx_icon, View.INVISIBLE)
                rv.setOnClickPendingIntent(R.id.wx_block, openApp(c))
                rv.setOnClickPendingIntent(R.id.wx_icon, openApp(c))
            } else {
                rv.setTextViewText(R.id.wx_place, Prefs.placeName(c))
                if (w == null) {
                    rv.setTextViewText(R.id.wx_temp, "—")
                    rv.setTextViewText(R.id.wx_cond, lc.getString(R.string.wx_loading))
                    rv.setTextViewText(R.id.wx_hilo, "")
                    rv.setViewVisibility(R.id.wx_icon, View.INVISIBLE)
                } else {
                    rv.setTextViewText(R.id.wx_temp, "${w.temp}$unit")
                    rv.setTextViewText(R.id.wx_cond, lc.getString(Weather.text(w.code)))
                    rv.setTextViewText(R.id.wx_hilo, "↑${w.max}$unit / ↓${w.min}$unit")
                    rv.setViewVisibility(R.id.wx_icon, View.VISIBLE)
                    rv.setImageViewResource(R.id.wx_icon, Weather.icon(w.code, w.isDay))
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
