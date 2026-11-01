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
class Occurrence(val eventId: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean, val colour: Int, val day: Int, val location: String = "")

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
            CalendarContract.Instances.EVENT_LOCATION,
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
                        cur.getInt(5) or 0xFF000000.toInt(), if (allDay) day else maxOf(day, today), cur.getString(6)?.trim().orEmpty())
                }
            }
        }
        // Today's all-day events that began earlier still show today
        return out.map { if (it.allDay && it.day < today) Occurrence(it.eventId, it.title, it.begin, it.end, true, it.colour, today, it.location) else it }
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
 * bar, its time and its title.
 *
 * Optimised for 4x3, 5x3, 4x4 and 5x4 on the cover screen (31 Oct). The type keeps one size at every
 * span, as Samsung's own agenda does, so a bigger widget shows more rather than shows it larger.
 * From four rows tall each event gains a second line, its time range and place. Below about
 * 130 dp the month's name, the day headings and the count of the rest fall away.
 */
object AgendaPainter : Painter {

    override fun paint(c: Context, id: Int, canvas: Canvas, s: Surface): Scene {
        val now = System.currentTimeMillis()
        val hits = ArrayList<Hit>()
        hits += Hit(RectF(0f, 0f, s.w, s.h), Act.Open(intent = Agenda.openDay(c, now)), "Open calendar")
        val bare = minOf(s.w, s.h) < 130f
        val wide = s.w > s.h * 1.55f && s.w >= 240f
        val tall = s.h >= 330f
        val p = if (bare) 12f else if (minOf(s.w, s.h) >= 220f) 18f else 15f

        // The date
        val cal = Calendar.getInstance()
        val num = cal.get(Calendar.DAY_OF_MONTH).toString()
        val numP = c.ink(if (tall) 42f else if (bare) 26f else 34f, s.ink(p + 20f, p + 18f), display = true)
        canvas.textTop(num, p, p, numP)
        val capNum = capHeight(numP)
        val numW = numP.measureText(num)
        val tx = p + numW + 10f
        val headW = (if (wide) s.w * 0.34f else s.w - p) - tx
        val dayP = c.ink(if (bare) 13f else 15f, s.ink(tx + 30f, p + 6f), 600)
        val monthP = c.meta(s.muted(tx + 30f, p + capNum), 1f, 13f)
        if (bare) canvas.drawText(fit(SimpleDateFormat("EEEE", Locale.getDefault()).format(cal.time), dayP, headW), tx, p + capNum, dayP)
        else {
            canvas.textTop(fit(SimpleDateFormat("EEEE", Locale.getDefault()).format(cal.time), dayP, headW), tx, p + 1f, dayP)
            canvas.drawText(fit(SimpleDateFormat("MMMM", Locale.getDefault()).format(cal.time), monthP, headW), tx, p + capNum, monthP)
        }
        val headBottom = p + capNum

        if (!Agenda.allowed(c)) {
            val noteP = c.ink(13f, s.muted(s.w / 2, headBottom + 30f), 450)
            canvas.textTop(fit("Tap to show your calendar", noteP, s.w - 2 * p), p, headBottom + 22f, noteP)
            hits[0] = Hit(RectF(0f, 0f, s.w, s.h), Act.Open(intent = Intent(c, SettingsActivity::class.java)), "Allow calendar access")
            return Scene(hits)
        }

        // The days ahead
        val x0 = if (wide) s.w * 0.34f + 8f else p
        var y = if (wide) p + 2f else headBottom + (if (bare) 12f else 18f)
        val listW = s.w - x0 - p
        val events = Agenda.ahead(c)
        val labelP = c.label(s.accent(x0 + listW / 2, y))
        val timeP = c.ink(14.5f, s.ink(x0 + 30f, y), 600)
        val titleP = c.ink(14.5f, s.ink(x0 + listW / 2, y), 450)
        val detailP = c.meta(s.muted(x0 + listW / 2, y))
        val fmt = android.text.format.DateFormat.getTimeFormat(c)
        val widest = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 22); set(Calendar.MINUTE, 58) }
        val timeW = timeP.measureText(fmt.format(widest.time)) + 8f
        val rowH = if (tall) 40f else 27f
        val labelH = 11f + 9f
        val bottom = s.h - p
        val todayKey = Agenda.dayKey(Calendar.getInstance())

        if (events.isEmpty()) {
            val emptyP = c.statement(s.ink(x0 + listW / 2, y + 12f))
            canvas.textTop("Nothing ahead", x0, y + 4f, emptyP)
            val noteP = c.ink(13f, s.muted(x0 + listW / 2, y + 34f), 450)
            canvas.textTop("Two clear weeks", x0, y + 4f + emptyP.textSize * 1.3f, noteP)
            return Scene(hits)
        }

        var shown = 0
        var lastDay = -1
        for ((i, o) in events.withIndex()) {
            val needsLabel = o.day != lastDay && !bare
            val thisRow = if (o.allDay) 27f else rowH
            val need = (if (needsLabel) labelH + (if (lastDay >= 0) 9f else 0f) else 0f) + thisRow
            val reserve = if (i < events.size - 1 && !bare) 16f else 0f
            if (y + need + reserve > bottom + 2f) break
            if (needsLabel) {
                if (lastDay >= 0) y += 9f
                labelP.color = s.accent(x0 + listW / 2, y)
                canvas.textTop(dayName(o.day), x0, y, labelP)
                y += labelH
                lastDay = o.day
            }
            val firstMid = y + 9f
            val detailed = tall && !o.allDay
            // The calendar's colour, as a bar spanning the event's lines
            canvas.drawRoundRect(RectF(x0, y + 1f, x0 + 3f, y + (if (detailed) 32f else 17f)), 1.5f, 1.5f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = o.colour })
            val tx0 = x0 + 11f
            val time = when {
                bare && o.day != todayKey -> SimpleDateFormat("EEE", Locale.getDefault()).format(java.util.Date(o.begin))
                o.allDay -> ""
                o.begin <= now -> "Now"
                else -> fmt.format(java.util.Date(o.begin))
            }
            // Timed events: the time, then the title. All-day: the title alone, as Samsung's does
            val titleX = if (time.isEmpty()) tx0 else tx0 + timeW
            if (time.isNotEmpty()) {
                timeP.color = s.ink(tx0, firstMid)
                canvas.drawText(fit(time, timeP, timeW), tx0, firstMid + capHeight(timeP) / 2, timeP)
            }
            titleP.color = s.ink(titleX + 30f, firstMid)
            canvas.drawText(fit(o.title, titleP, s.w - p - titleX), titleX, firstMid + capHeight(titleP) / 2, titleP)
            if (detailed) {
                val range = fmt.format(java.util.Date(o.begin)) + " – " + fmt.format(java.util.Date(o.end))
                val detail = if (o.location.isNotEmpty()) "$range  ·  ${o.location}" else range
                detailP.color = s.muted(titleX + 30f, firstMid + 17f)
                canvas.drawText(fit(detail, detailP, s.w - p - titleX), titleX, firstMid + 17f + capHeight(detailP) / 2, detailP)
            }
            if (hits.size < 12) hits += Hit(RectF(x0 - 4f, y - 3f, s.w, y + thisRow - 3f), Act.Open(intent = Agenda.openEvent(o)), o.title)
            y += thisRow
            shown++
        }
        if (shown < events.size && !bare) {
            val moreP = c.meta(s.muted(x0 + listW / 2, y + 6f))
            canvas.textTop("+ ${events.size - shown} more", x0 + 11f, y + 2f, moreP)
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
