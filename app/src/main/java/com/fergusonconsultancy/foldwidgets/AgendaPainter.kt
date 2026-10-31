package com.fergusonconsultancy.foldwidgets

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** One occurrence of an event. All-day events are kept on their own calendar day. */
class Occurrence(val eventId: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean, val colour: Int, val day: Int)

object Agenda {
    const val DAYS = 14

    fun allowed(c: Context) = c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** A day as a number that sorts, yyyymmdd. */
    fun dayKey(cal: Calendar) = cal.get(Calendar.YEAR) * 10000 + (cal.get(Calendar.MONTH) + 1) * 100 + cal.get(Calendar.DAY_OF_MONTH)

    /** Everything from now to [DAYS] days ahead, in order, past events dropped. */
    fun ahead(c: Context): List<Occurrence> {
        if (!allowed(c)) return emptyList()
        val now = System.currentTimeMillis()
        val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val from = start.timeInMillis - 86_400_000L  // all-day events are stored in UTC, so look a day back
        val to = start.timeInMillis + DAYS * 86_400_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from); ContentUris.appendId(it, to)
        }.build()
        val cols = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.DISPLAY_COLOR,
        )
        val out = ArrayList<Occurrence>()
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val local = Calendar.getInstance()
        val today = dayKey(local)
        runCatching {
            c.contentResolver.query(uri, cols, "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cur ->
                while (cur.moveToNext()) {
                    val allDay = cur.getInt(4) == 1
                    val begin = cur.getLong(2); val end = cur.getLong(3)
                    val day = if (allDay) { utc.timeInMillis = begin; dayKey(utc) } else { local.timeInMillis = begin; dayKey(local) }
                    if (allDay) {
                        // An all-day event ends at midnight UTC of the day after its last day
                        utc.timeInMillis = end - 1; if (dayKey(utc) < today) continue
                    } else if (end <= now) continue
                    // A timed event that began before today and runs on belongs to today
                    out += Occurrence(cur.getLong(0), cur.getString(1)?.takeIf { it.isNotBlank() } ?: "Busy", begin, end, allDay,
                        cur.getInt(5) or 0xFF000000.toInt(), if (allDay) day else maxOf(day, today))
                }
            }
        }
        // Today's all-day events that began earlier still show today
        return out.map { if (it.allDay && it.day < today) Occurrence(it.eventId, it.title, it.begin, it.end, true, it.colour, today) else it }
            .sortedWith(compareBy<Occurrence> { it.day }.thenBy { !it.allDay }.thenBy { it.begin })
    }

    /** The moment the agenda next changes on its own: the next event's end, or midnight. */
    fun nextChange(c: Context): Long {
        val midnight = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 5)
        }.timeInMillis
        val nextEnd = ahead(c).filter { !it.allDay }.minOfOrNull { it.end } ?: Long.MAX_VALUE
        return minOf(midnight, nextEnd)
    }

    fun openDay(c: Context, millis: Long): Intent = Intent(Intent.ACTION_VIEW)
        .setData(CalendarContract.CONTENT_URI.buildUpon().appendPath("time").also { ContentUris.appendId(it, millis) }.build())

    fun openEvent(o: Occurrence): Intent = Intent(Intent.ACTION_VIEW)
        .setData(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, o.eventId))
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, o.begin)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, o.end)
}

/**
 * The agenda. The date heads it, the day number in Italiana; then the days ahead, each named in
 * small tracked capitals in the finish's light, each event a line with its calendar's colour as a
 * short bar, its time and its title. As many as fit, then how many more.
 */
object AgendaPainter : Painter {

    override fun paint(c: Context, id: Int, canvas: Canvas, s: Surface): Scene {
        val large = s.w >= 220f && s.h >= 220f
        val k = if (large) minOf(s.w / 256f, s.h / 269f).coerceIn(0.8f, 1.6f) else minOf(s.w / 190f, s.h / 164f).coerceIn(0.66f, 1.4f)
        val p = (if (large) 18f else 14f) * k
        val wide = s.w > s.h * 1.55f && s.w >= 240f
        val hits = ArrayList<Hit>()
        val now = System.currentTimeMillis()

        // The date
        val headW = if (wide) s.w * 0.32f else s.w - 2 * p
        val numP = c.ink((if (large) 46f else 32f) * k, s.ink(p + 20f * k, p + 20f * k), display = true)
        val cal = Calendar.getInstance()
        val num = cal.get(Calendar.DAY_OF_MONTH).toString()
        canvas.textTop(num, p, p, numP)
        val numW = numP.measureText(num)
        val dayP = c.ink((if (large) 13f else 11.5f) * k, s.ink(p + numW + 40f * k, p + 8f * k), 500)
        val monthP = c.ink((if (large) 11.5f else 10f) * k, s.muted(p + numW + 40f * k, p + 22f * k))
        val tx = p + numW + 10f * k
        val capNum = capHeight(numP)
        canvas.drawText(fit(SimpleDateFormat("EEEE", Locale.getDefault()).format(cal.time), dayP, headW - (tx - p)), tx, p + capNum * 0.46f, dayP)
        canvas.drawText(fit(SimpleDateFormat("MMMM", Locale.getDefault()).format(cal.time), monthP, headW - (tx - p)), tx, p + capNum, monthP)
        val headBottom = p + capNum
        hits += Hit(RectF(0f, 0f, if (wide) headW + p else s.w, headBottom + 8f * k), Act.Open(intent = Agenda.openDay(c, now)), "Open calendar")

        if (!Agenda.allowed(c)) {
            val noteP = c.ink(12f * k, s.muted(s.w / 2, headBottom + 30f * k))
            canvas.textTop(fit("Tap to show your calendar", noteP, s.w - 2 * p), p, headBottom + 22f * k, noteP)
            hits += Hit(RectF(0f, headBottom + 8f * k, s.w, s.h), Act.Open(intent = Intent(c, SettingsActivity::class.java)), "Allow calendar access")
            return Scene(hits)
        }

        // The days ahead
        val x0 = if (wide) p + headW + 16f * k else p
        var y = if (wide) p + 2f * k else headBottom + (if (large) 18f else 12f) * k
        val listW = s.w - x0 - p
        val events = Agenda.ahead(c)
        val labelP = c.ink(9.5f * k, s.accent(x0 + listW / 2, y), 500, tracking = 0.16f)
        val timeP = c.ink(11.5f * k, s.ink(x0 + 30f * k, y), 500)
        val titleP = c.ink(12.5f * k, s.ink(x0 + listW / 2, y))
        val rowH = 20f * k
        val labelH = 9.5f * k + 8f * k
        val bottom = s.h - p
        val fmt = android.text.format.DateFormat.getTimeFormat(c)
        // The time column fits the phone's own clock, 24-hour or "10:30 am"
        val widest = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 22); set(Calendar.MINUTE, 58) }
        val timeW = maxOf(timeP.measureText(fmt.format(widest.time)), timeP.measureText("All day")) + 8f * k

        if (events.isEmpty()) {
            val emptyP = c.ink(20f * k, s.ink(x0 + listW / 2, y + 12f * k), display = true)
            canvas.textTop("Nothing ahead", x0, y + 4f * k, emptyP)
            val noteP = c.ink(11.5f * k, s.muted(x0 + listW / 2, y + 34f * k))
            canvas.textTop("Two clear weeks", x0, y + 4f * k + emptyP.textSize * 1.25f, noteP)
            return Scene(hits)
        }

        var shown = 0
        var lastDay = -1
        for ((i, o) in events.withIndex()) {
            val needsLabel = o.day != lastDay
            val need = (if (needsLabel) labelH + (if (lastDay >= 0) 6f * k else 0f) else 0f) + rowH
            // Leave room for the "more" line unless this is the last event
            val reserve = if (i < events.size - 1) rowH else 0f
            if (y + need + reserve > bottom + 4f * k) break
            if (needsLabel) {
                if (lastDay >= 0) y += 6f * k
                labelP.color = s.accent(x0 + listW / 2, y)
                canvas.textTop(dayName(o.day), x0, y, labelP)
                y += labelH
                lastDay = o.day
            }
            val mid = y + rowH / 2 - 3f * k
            // The calendar's colour, as a short bar
            canvas.drawRoundRect(RectF(x0, mid - 6.5f * k, x0 + 3f * k, mid + 6.5f * k), 1.5f * k, 1.5f * k,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = o.colour })
            val tx0 = x0 + 10f * k
            timeP.color = if (o.allDay) s.muted(tx0, mid) else s.ink(tx0, mid)
            val time = when {
                o.allDay -> "All day"
                o.begin <= now -> "Now"
                else -> fmt.format(java.util.Date(o.begin))
            }
            val timeText = fit(time, timeP, timeW)
            canvas.drawText(timeText, tx0, mid + capHeight(timeP) / 2, timeP)
            titleP.color = s.ink(tx0 + timeW + 20f * k, mid)
            canvas.drawText(fit(o.title, titleP, listW - 10f * k - timeW), tx0 + timeW, mid + capHeight(titleP) / 2, titleP)
            if (hits.size < 11) hits += Hit(RectF(x0 - 4f * k, y - 2f * k, s.w, y + rowH), Act.Open(intent = Agenda.openEvent(o)), o.title)
            y += rowH
            shown++
        }
        if (shown < events.size) {
            val moreP = c.ink(10.5f * k, s.muted(x0 + listW / 2, y + 6f * k), 500)
            canvas.textTop("+ ${events.size - shown} more", x0 + 10f * k, y + 2f * k, moreP)
        }
        return Scene(hits)
    }

    /** TODAY, TOMORROW, then MON 2 NOV. */
    private fun dayName(key: Int): String {
        val cal = Calendar.getInstance()
        val today = Agenda.dayKey(cal)
        cal.add(Calendar.DAY_OF_YEAR, 1)
        val tomorrow = Agenda.dayKey(cal)
        return when (key) {
            today -> "TODAY"
            tomorrow -> "TOMORROW"
            else -> {
                val d = Calendar.getInstance().apply { set(key / 10000, (key / 100) % 100 - 1, key % 100) }
                SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(d.time).uppercase()
            }
        }
    }
}
