package ro.noraz.nagenda

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.text.format.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

data class Cal(val id: Long, val name: String, val account: String, val color: Int)

data class CalItem(
    val id: Long,
    val title: String,
    val location: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val color: Int,
    val calId: Long,
    val startDate: LocalDate,
    val endDate: LocalDate, // inclusive
)

data class Row(
    val item: CalItem,
    val date: String,
    val sub: String,
    val ongoing: Boolean,
    val today: Boolean,
)

object Agenda {
    private const val DAY = 24L * 60 * 60 * 1000

    fun hasPerm(c: Context) =
        c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun calendars(c: Context): List<Cal> {
        if (!hasPerm(c)) return emptyList()
        val out = mutableListOf<Cal>()
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
        )
        try {
            c.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, proj,
                "${CalendarContract.Calendars.VISIBLE} = 1", null,
                "${CalendarContract.Calendars.ACCOUNT_NAME} ASC",
            )?.use { cur ->
                while (cur.moveToNext()) {
                    out += Cal(
                        cur.getLong(0),
                        cur.getString(1) ?: "?",
                        cur.getString(2) ?: "",
                        cur.getInt(3) or 0xFF000000.toInt(),
                    )
                }
            }
        } catch (_: Exception) { }
        return out
    }

    fun items(c: Context): List<CalItem> {
        if (!hasPerm(c)) return emptyList()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val days = Prefs.days(c)
        val last = today.plusDays(days.toLong() - 1)
        val now = System.currentTimeMillis()
        val from = today.atStartOfDay(zone).toInstant().toEpochMilli() - DAY
        val to = today.plusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli() + DAY
        val hidden = Prefs.hidden(c)

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.STATUS,
        )
        val noTitle = Loc.ctx(c).getString(R.string.no_title)
        val out = mutableListOf<CalItem>()
        try {
            c.contentResolver.query(
                uri, proj, "${CalendarContract.Instances.VISIBLE} = 1", null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val calId = cur.getLong(7)
                    if (calId in hidden) continue
                    if (!cur.isNull(8) && cur.getInt(8) == CalendarContract.Events.STATUS_CANCELED) continue
                    val begin = cur.getLong(3)
                    val end = cur.getLong(4)
                    val allDay = cur.getInt(5) != 0
                    val sd: LocalDate
                    val ed: LocalDate
                    if (allDay) {
                        // all-day events are stored at UTC midnight
                        sd = Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate()
                        val excl = Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate()
                        ed = if (excl.isAfter(sd)) excl.minusDays(1) else sd
                    } else {
                        sd = Instant.ofEpochMilli(begin).atZone(zone).toLocalDate()
                        val e = Instant.ofEpochMilli(maxOf(begin, end - 1)).atZone(zone).toLocalDate()
                        ed = if (e.isBefore(sd)) sd else e
                    }
                    if (ed.isBefore(today) || sd.isAfter(last)) continue
                    if (!allDay && end <= now) continue
                    out += CalItem(
                        id = cur.getLong(0),
                        title = cur.getString(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: noTitle,
                        location = cur.getString(2)?.trim() ?: "",
                        begin = begin, end = end, allDay = allDay,
                        color = cur.getInt(6) or 0xFF000000.toInt(),
                        calId = calId, startDate = sd, endDate = ed,
                    )
                }
            }
        } catch (_: Exception) { }
        return out.sortedWith(
            compareBy<CalItem>({ maxOf(it.startDate, today) }, { !it.allDay }, { it.begin }),
        )
    }

    fun todayCount(items: List<CalItem>): Int {
        val today = LocalDate.now()
        return items.count { !it.startDate.isAfter(today) }
    }

    fun rows(c: Context, items: List<CalItem> = items(c)): List<Row> {
        val lc = Loc.ctx(c)
        val loc = Loc.locale(c)
        val today = LocalDate.now()
        val now = System.currentTimeMillis()
        val timeFmt = DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(c)) "HH:mm" else "h:mm a", loc)
        val dayFmt = DateTimeFormatter.ofPattern("d MMM", loc)
        val zone = ZoneId.systemDefault()
        fun t(ms: Long) = timeFmt.format(Instant.ofEpochMilli(ms).atZone(zone))
        val showLoc = Prefs.showLocation(c)
        val countdown = Prefs.showCountdown(c)

        val wdFmt = DateTimeFormatter.ofPattern("EEE", loc)
        val out = mutableListOf<Row>()
        items.forEach { e ->
            val date = maxOf(e.startDate, today)
            val diff = ChronoUnit.DAYS.between(today, date).toInt()
            val label = when (diff) {
                0 -> lc.getString(R.string.today)
                1 -> lc.getString(R.string.tomorrow)
                else -> wdFmt.format(date) + "\n" + dayFmt.format(date)
            }.uppercase(loc)
            val ongoing = !e.allDay && e.begin <= now && e.end > now
            val parts = mutableListOf<String>()
            when {
                e.allDay && e.endDate.isAfter(e.startDate) ->
                    parts += lc.getString(R.string.until_day, dayFmt.format(e.endDate))
                e.allDay -> {}
                ongoing -> parts += lc.getString(R.string.now_until, t(e.end))
                e.endDate.isAfter(e.startDate) -> parts += "${t(e.begin)} → ${dayFmt.format(e.endDate)}, ${t(e.end)}"
                else -> parts += "${t(e.begin)} – ${t(e.end)}"
            }
            if (showLoc && e.location.isNotEmpty()) parts += e.location
            if (countdown && diff >= 2) parts += lc.resources.getQuantityString(R.plurals.in_days, diff, diff)
            out += Row(e, label, parts.joinToString(" · "), ongoing, diff == 0)
        }
        return out
    }

    /** Next moment the list changes on its own (an event starts or ends today). */
    fun nextChange(items: List<CalItem>): Long? {
        val now = System.currentTimeMillis()
        return items.filter { !it.allDay }
            .flatMap { listOf(it.begin, it.end) }
            .filter { it > now }
            .minOrNull()
    }
}

fun String.cap(l: Locale) = replaceFirstChar { if (it.isLowerCase()) it.titlecase(l) else it.toString() }
