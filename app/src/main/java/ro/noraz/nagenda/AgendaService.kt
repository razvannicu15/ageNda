package ro.noraz.nagenda

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.provider.CalendarContract
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService

class AgendaService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(private val c: Context) : RemoteViewsFactory {
        private var rows: List<Row> = emptyList()
        private var pal: Palette = Palettes.of(c)
        private var alpha = 255

        override fun onCreate() {}

        override fun onDataSetChanged() {
            val token = Binder.clearCallingIdentity()
            try {
                rows = Agenda.rows(c)
                pal = Palettes.of(c)
                alpha = cardAlpha(c)
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        }

        override fun onDestroy() { rows = emptyList() }
        override fun getCount() = rows.size
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false

        override fun getViewAt(position: Int): RemoteViews {
            val rv = RemoteViews(c.packageName, R.layout.widget_row_event)
            val r = rows.getOrNull(position) ?: return rv
            val e = r.item
            rv.setImageViewResource(
                R.id.row_bg,
                if (position == rows.lastIndex) R.drawable.card_last else R.drawable.card_mid,
            )
            rv.setInt(R.id.row_bg, "setColorFilter", pal.card)
            rv.setInt(R.id.row_bg, "setImageAlpha", alpha)

            rv.setTextViewText(R.id.date, r.date)
            rv.setTextColor(R.id.date, if (r.today) pal.accent else pal.muted)
            rv.setTextViewText(R.id.title, e.title)
            rv.setTextColor(R.id.title, pal.text)
            if (r.sub.isEmpty()) rv.setViewVisibility(R.id.sub, View.GONE)
            else {
                rv.setViewVisibility(R.id.sub, View.VISIBLE)
                rv.setTextViewText(R.id.sub, r.sub)
                rv.setTextColor(R.id.sub, if (r.ongoing) pal.accent else pal.muted)
            }
            rv.setInt(R.id.dot, "setColorFilter", e.color)

            val fill = Intent().apply {
                data = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.begin)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.end)
            }
            rv.setOnClickFillInIntent(R.id.row_event, fill)
            return rv
        }
    }
}

/** Cards stay a bit more solid than the frame so text is always readable. */
fun cardAlpha(c: Context): Int {
    val o = Prefs.opacity(c)
    return ((o + (100 - o) * 0.35f) * 255 / 100).toInt().coerceIn(0, 255)
}
