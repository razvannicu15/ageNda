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
import android.provider.CalendarContract
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class AgendaWidget : AppWidgetProvider() {

    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) {
        updateAll(c)
    }

    override fun onAppWidgetOptionsChanged(c: Context, mgr: AppWidgetManager, id: Int, opts: Bundle) {
        render(c, mgr, id, Agenda.items(c))
    }

    override fun onReceive(c: Context, intent: Intent) {
        super.onReceive(c, intent)
        when (intent.action) {
            ACTION_REFRESH, ACTION_TICK,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED, Intent.ACTION_DATE_CHANGED -> updateAll(c)
        }
    }

    override fun onDisabled(c: Context) {
        CalendarWatchJob.cancel(c)
        c.getSystemService(AlarmManager::class.java).cancel(tickIntent(c))
    }

    companion object {
        const val ACTION_REFRESH = "ro.noraz.nagenda.REFRESH"
        const val ACTION_TICK = "ro.noraz.nagenda.TICK"

        fun updateAll(c: Context) {
            val mgr = AppWidgetManager.getInstance(c)
            val ids = mgr.getAppWidgetIds(ComponentName(c, AgendaWidget::class.java))
            if (ids.isEmpty()) return
            val items = Agenda.items(c)
            ids.forEach { render(c, mgr, it, items) }
            @Suppress("DEPRECATION")
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.list)
            schedule(c, items)
        }

        private fun render(c: Context, mgr: AppWidgetManager, id: Int, items: List<CalItem>) {
            val lc = Loc.ctx(c)
            val loc = Loc.locale(c)
            val p = Palettes.of(c)
            val today = LocalDate.now()
            val rv = RemoteViews(c.packageName, R.layout.widget_agenda)

            rv.setInt(R.id.bg, "setColorFilter", p.bg)
            rv.setInt(R.id.bg, "setImageAlpha", Prefs.opacity(c) * 255 / 100)

            val ca = cardAlpha(c)
            for (id2 in intArrayOf(R.id.header_bg, R.id.empty_bg)) {
                rv.setInt(id2, "setColorFilter", p.card)
                rv.setInt(id2, "setImageAlpha", ca)
            }
            rv.setInt(R.id.ic_cal, "setColorFilter", p.text)
            rv.setTextViewText(R.id.weekday, DateTimeFormatter.ofPattern("EEEE, d MMMM", loc).format(today))
            rv.setTextColor(R.id.weekday, p.text)
            rv.setInt(R.id.btn_add, "setColorFilter", p.text)
            rv.setInt(R.id.btn_refresh, "setColorFilter", p.text)

            // list
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
                    c, 10, Intent(Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                ),
            )

            rv.setTextColor(R.id.empty_text, p.muted)
            if (Agenda.hasPerm(c)) {
                rv.setTextViewText(R.id.empty_text, lc.getString(R.string.empty_days, Prefs.days(c)))
                rv.setOnClickPendingIntent(R.id.empty, openCalendar(c))
            } else {
                rv.setTextViewText(R.id.empty_text, lc.getString(R.string.need_permission))
                rv.setOnClickPendingIntent(R.id.empty, openApp(c))
            }

            rv.setOnClickPendingIntent(R.id.header, openCalendar(c))
            rv.setOnClickPendingIntent(
                R.id.btn_add,
                PendingIntent.getActivity(
                    c, 2,
                    Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            rv.setOnClickPendingIntent(
                R.id.btn_refresh,
                PendingIntent.getBroadcast(
                    c, 3, Intent(c, AgendaWidget::class.java).setAction(ACTION_REFRESH),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            mgr.updateAppWidget(id, rv)
        }

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
            c.getSystemService(AlarmManager::class.java)
                .setWindow(AlarmManager.RTC, next, 60_000, tickIntent(c))
        }
    }
}
