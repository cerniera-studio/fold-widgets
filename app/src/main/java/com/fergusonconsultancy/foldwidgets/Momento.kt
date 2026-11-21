package com.fergusonconsultancy.foldwidgets

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import com.cerniera.kit.Moment
import com.cerniera.kit.Rules
import com.cerniera.kit.SensoEvent
import com.cerniera.kit.SensoPlaying
import com.cerniera.kit.SensoRain
import com.cerniera.kit.Signals
import com.cerniera.kit.Kind as Moments

/**
 * Momento (Fluido phase 2, 18 Nov 2026): one widget that becomes whichever widget the moment needs.
 *
 *  - Leaving soon (an event within 45 minutes, somewhere you'd go): the agenda.
 *  - Listening (something playing): the music widget.
 *  - Rain coming (Cielo's next rain, or this app's own forecast, within two hours): the weather.
 *  - An occasion today (an all-day event, in the morning): the agenda, which leads with it.
 *  - Sunset (the golden hour on a dry evening, 21 Nov 2026): the weather, whose sun tile gives the time.
 *  - Winding down (late, on the charger): the agenda, so tomorrow's first event is the last thing seen.
 *  - Otherwise: the month.
 *
 * The moments are the Kit's own (Senso's rules), read from what is on the phone when the widget is
 * drawn, so Momento, Lume in Fold Taskbar and the rest of the family agree on what the moment is.
 * Rules of trust: one thing at a time, only above 60%; what it showed holds against flicker;
 * Settings → Momento says what each one sees and why; and each can be pinned to one face, which
 * turns Fluido off for it.
 */
object Momento {

    /** What each moment turns the widget into. */
    private val FACE = mapOf(
        Moments.LEAVING_SOON to Kind.AGENDA, Moments.LISTENING to Kind.MUSIC, Moments.RAIN_COMING to Kind.WEATHER,
        Moments.OCCASION to Kind.AGENDA, Moments.SUNSET to Kind.WEATHER, Moments.WINDING_DOWN to Kind.AGENDA,
    )
    val KINDS = FACE.keys

    /** The face when nothing is strong enough to say. */
    val REST = Kind.MONTH

    private val CIELO: Uri get() = Uri.parse("content://com.cerniera.cielo.rain/next")

    /** The pinned face for widget [id], or null: follow the moment. */
    fun pinned(c: Context, id: Int): Kind? = Prefs.of(c).getString("momento_pin_$id", null)?.let { n -> Kind.entries.firstOrNull { it.name == n } }
    fun pin(c: Context, id: Int, k: Kind?) = Prefs.of(c).edit().apply { if (k == null) remove("momento_pin_$id") else putString("momento_pin_$id", k.name) }.apply()

    // ---- What the phone knows now ------------------------------------------------------------

    /** Cielo's rain when it shares it, otherwise this app's own forecast (the weather widget's). */
    private fun rain(c: Context, now: Long): SensoRain? {
        val shared = runCatching {
            c.contentResolver.query(CIELO, null, null, null, null)?.use { cur ->
                if (!cur.moveToFirst()) Unit
                else SensoRain(cur.getLong(cur.getColumnIndexOrThrow("starts_at")), cur.getFloat(cur.getColumnIndexOrThrow("chance")))
            }
        }.getOrNull()
        if (shared is SensoRain) return shared
        if (shared == Unit) return null  // Cielo answered: no rain in sight
        // Our own forecast: the coming hours, each with its hour of the day
        val fc = Weather.forecast(c) ?: return null
        if (now - fc.fetched > 3 * 3_600_000L) return null
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val thisHour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        fc.hours.take(12).forEachIndexed { i, h ->
            val chance = h.rain ?: return@forEachIndexed
            if (chance < 40) return@forEachIndexed
            // Hours run on from now; the first may be this hour
            val ahead = Math.floorMod(h.hour - thisHour, 24).let { if (i > 0 && it == 0) 24 else it }
            val at = java.util.Calendar.getInstance().apply {
                timeInMillis = now; set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                add(java.util.Calendar.HOUR_OF_DAY, ahead)
            }.timeInMillis
            return SensoRain(at, chance / 100f, from = "Open-Meteo")
        }
        return null
    }

    private fun event(c: Context, id: Int, now: Long): SensoEvent? =
        runCatching { Agenda.ahead(c, id).firstOrNull { !it.allDay && it.begin > now } }.getOrNull()
            ?.let { SensoEvent(it.title, it.begin, it.end, it.location.ifBlank { null }) }

    /** Today's first all-day event in this widget's calendars: a birthday, an anniversary. */
    private fun occasion(c: Context, id: Int, now: Long): SensoEvent? = runCatching {
        val today = Agenda.dayKey(java.util.Calendar.getInstance().apply { timeInMillis = now })
        Agenda.ahead(c, id).firstOrNull { it.allDay && it.day == today }
    }.getOrNull()?.let { SensoEvent(it.title, it.begin, it.end, null, allDay = true) }

    /** The sky over the weather's place (the sunset is local), Leeds until one is set. */
    private fun sky(c: Context): com.cerniera.kit.Sky.Place =
        Weather.place(c)?.takeIf { it.lat != 0.0 || it.lon != 0.0 }?.let { com.cerniera.kit.Sky.Place(it.name, it.lat, it.lon) }
            ?: com.cerniera.kit.Sky.Place.LEEDS

    private fun playing(c: Context, now: Long): SensoPlaying? = runCatching { Media.now(c) }.getOrNull()?.let {
        SensoPlaying(it.pkg, it.title, it.artist, it.playing, if (it.playing) now else lastPaused(c, it.pkg, it.title, now))
    }

    /** When the track last stopped playing, remembered across draws, so "paused just now" can count. */
    private fun lastPaused(c: Context, pkg: String, title: String, now: Long): Long {
        val p = Prefs.of(c); val key = "$pkg|$title"
        if (p.getString("momento_paused_key", null) != key) p.edit().putString("momento_paused_key", key).putLong("momento_paused_at", now).apply()
        return p.getLong("momento_paused_at", now)
    }

    private fun headphones(c: Context): Boolean? = runCatching {
        val worn = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET)
        c.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in worn }
    }.getOrNull()

    private fun charging(c: Context): Boolean? = runCatching {
        (c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }.getOrNull()

    fun signals(c: Context, id: Int, now: Long = System.currentTimeMillis()) = Signals(
        playing = playing(c, now), headphones = headphones(c), charging = charging(c),
        event = event(c, id, now), rain = rain(c, now),
        occasion = occasion(c, id, now), place = sky(c),
    )

    /** What widget [id] reads now: every moment, the one offered, and the face it shows. */
    class Reading(val moments: List<Moment>, val offered: Moment?, val face: Kind, val pinned: Boolean)

    fun read(c: Context, id: Int, now: Long = System.currentTimeMillis()): Reading {
        pinned(c, id)?.let { return Reading(emptyList(), null, it, true) }
        val moments = Rules.read(signals(c, id, now), now).filter { it.kind in KINDS }
        val held = Prefs.of(c).getString("momento_held_$id", null)?.let { Moments.of(it) }
        val offered = Rules.pick(moments, KINDS, held)
        if (offered?.kind != held) Prefs.of(c).edit().putString("momento_held_$id", offered?.kind?.id).apply()
        return Reading(moments, offered, offered?.let { FACE.getValue(it.kind) } ?: REST, false)
    }

    /** The face widget [id] shows now. */
    fun face(c: Context, id: Int): Kind = read(c, id).face

    /** When any Momento may next change by itself: an event coming within 45 minutes, or starting; rain two hours out; a pause running out. */
    fun nextChange(c: Context, now: Long = System.currentTimeMillis()): Long {
        val ids = Widgets.ids(c, Kind.MOMENTO)
        if (ids.isEmpty()) return Long.MAX_VALUE
        val times = mutableListOf<Long>()
        ids.forEach { id -> event(c, id, now)?.let { e -> times += e.start - 45 * 60_000L; times += e.start } }
        rain(c, now)?.let { r -> times += r.startsAt - 2 * 3_600_000L; times += r.startsAt - 3_600_000L; times += r.startsAt + 15 * 60_000L }
        // A paused track keeps the music face for Rules.PAUSE_GRACE, then gives way
        playing(c, now)?.takeIf { !it.playing }?.let { p -> times += p.since + Rules.PAUSE_GRACE + 1_000L }
        // The golden hour begins 45 minutes before sunset, and ends with it (the top of each hour
        // already redraws for the occasion's morning and the late evening)
        runCatching {
            val zone = java.time.ZoneId.systemDefault()
            com.cerniera.kit.Sky.day(sky(c), java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), zone).sunset?.toEpochMilli()
        }.getOrNull()?.let { set -> times += set - 45 * 60_000L; times += set }
        return times.filter { it > now + 30_000L }.minOrNull() ?: Long.MAX_VALUE
    }

    /** Draws whichever face the moment needs, in place of the widget's own. */
    object Painter : com.fergusonconsultancy.foldwidgets.Painter {
        override fun paint(c: Context, id: Int, canvas: android.graphics.Canvas, s: com.cerniera.kit.Surface): Scene =
            face(c, id).painter.paint(c, id, canvas, s)
    }
}
