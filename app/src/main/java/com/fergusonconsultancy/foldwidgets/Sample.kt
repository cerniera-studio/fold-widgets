package com.fergusonconsultancy.foldwidgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import java.util.Calendar

/**
 * Invented content for drawing widgets without the phone's own (22 Nov 2026): the picker's
 * previews and the size matrix the emulator test draws. While [on], the painters read these
 * instead of the media session, the calendar and the stored forecast, so every preview is drawn by
 * the widget's own painter and can never drift from what a placed widget looks like.
 */
object Sample {
    @Volatile var on = false

    private var art: Bitmap? = null

    private fun artwork(): Bitmap = art ?: Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).also { b ->
        val cv = Canvas(b)
        cv.drawRect(0f, 0f, 512f, 512f, Paint().apply {
            shader = LinearGradient(0f, 0f, 512f, 512f, intArrayOf(0xFF2E3A66.toInt(), 0xFF7A4F7E.toInt(), 0xFFD98A5B.toInt()), null, Shader.TileMode.CLAMP)
        })
        cv.drawCircle(300f, 210f, 92f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF1D9B5.toInt() })
        art = b
    }

    fun music(c: Context): NowPlaying = NowPlaying(
        pkg = c.packageName, app = "Player", icon = null,
        title = "Evening Light", artist = "The Quiet Hours", album = "Long Shadows", art = artwork(), accent = 0xFFE0A77A.toInt(),
        playing = false, position = 84_000L, updated = 0L, speed = 1f, duration = 240_000L, actions = 0L, open = null)

    fun place() = Place("Leeds", 53.8, -1.55)

    fun forecast(): Forecast {
        val hours = (0 until 8).map { i -> val h = (9 + i) % 24; Hour("$h:00", 7.0 + i % 3, if (i % 3 == 0) 3 else 1, true, h, if (i == 4) 40 else null) }
        val cal = Calendar.getInstance()
        val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val days = (0 until 8).map { i -> val d = f.format(cal.time); cal.add(Calendar.DAY_OF_YEAR, 1); Day(d, listOf(1, 3, 61, 2, 0, 3, 80, 1)[i], 9.0 + i % 4, 2.0 + i % 3, if (i % 3 == 2) 60 else null) }
        return Forecast(temp = 8.0, code = 2, day = true, high = 10.0, low = 3.0, hours = hours, fetched = System.currentTimeMillis(), days = days,
            feels = 5.0, humidity = 81, wind = 12.0, windDir = 240, pressure = 1012.0, uv = 1.0, visibility = 18000.0, dew = 4.0)
    }

    fun agenda(): List<Occurrence> {
        val today = Calendar.getInstance()
        fun at(dayOffset: Int, h: Int, m: Int) = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, dayOffset); set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m) }
        fun ev(id: Long, title: String, d: Int, h: Int, m: Int, mins: Int, colour: Int, place: String = "", allDay: Boolean = false): Occurrence {
            val b = at(d, h, m)
            return Occurrence(id, title, b.timeInMillis, b.timeInMillis + mins * 60_000L, allDay, colour, Agenda.dayKey(b), place)
        }
        val green = 0xFF3E9C6A.toInt(); val gold = 0xFFD9A441.toInt(); val red = 0xFFD7262E.toInt()
        today.timeInMillis
        return listOf(
            ev(1, "Team stand-up", 0, 23, 30, 15, green, "Studio"),
            ev(2, "Lunch with Sam", 1, 13, 0, 60, gold, "The Corn Exchange"),
            ev(3, "Bank holiday", 1, 0, 0, 1440, green, allDay = true),
            ev(4, "Five-a-side", 1, 18, 30, 60, red, "Soccer Centre"),
            ev(5, "Dentist", 2, 9, 0, 30, gold),
            ev(6, "Arsenal v Spurs", 3, 16, 30, 120, red),
        ).sortedWith(compareBy<Occurrence> { it.day }.thenBy { !it.allDay }.thenBy { it.begin })
    }
}
