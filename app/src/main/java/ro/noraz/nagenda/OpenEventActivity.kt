package ro.noraz.nagenda

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract

/**
 * Invisible hop for taps in the widget list (Android 14+ needs an explicit target):
 * tap on an event → open it in Calendar; tap on its date/dot → new event on that day.
 */
class OpenEventActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val day = intent.getLongExtra(EXTRA_ADD_DAY, -1L)
            val next = if (day >= 0) {
                val start = day + 9 * 60 * 60 * 1000L
                Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 60 * 60 * 1000L)
            } else {
                Intent(Intent.ACTION_VIEW, intent.data).also { v -> intent.extras?.let { v.putExtras(it) } }
            }
            startActivity(next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { }
        finish()
    }

    companion object { const val EXTRA_ADD_DAY = "add_day" }
}
