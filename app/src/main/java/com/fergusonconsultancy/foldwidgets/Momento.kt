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
    /** [swapped]: showing a note's face after a tap on it. [learned]: the rest face came from learning. */
    class Reading(val moments: List<Moment>, val offered: Moment?, val face: Kind, val pinned: Boolean,
                  val swapped: Boolean = false, val learned: Boolean = false)

    fun read(c: Context, id: Int, now: Long = System.currentTimeMillis()): Reading {
        if (Sample.on && id < 0) return Reading(emptyList(), null, Kind.AGENDA, false)
        pinned(c, id)?.let { return Reading(emptyList(), null, it, true) }
        // Senso's moments, each moved by what Momento has learned for this slot of the week
        val moments = Rules.read(signals(c, id, now), now).filter { it.kind in KINDS }.map { m ->
            val a = MomentoLearn.adjust(c, m.kind.id, now)
            if (a == 0f) m else m.copy(confidence = (m.confidence + a).coerceIn(0f, 1f),
                reasons = m.reasons + "learned (${MomentoLearn.describe(MomentoLearn.slot(now)).lowercase()}) · ${"%+.2f".format(java.util.Locale.UK, a)}")
        }.sortedByDescending { it.confidence }
        // A note tapped in the last 30 minutes: its face, until then
        swap(c, id, now)?.let { key ->
            val k = Moments.of(key)
            return Reading(moments, moments.firstOrNull { it.kind == k }, FACE[k] ?: REST, false, swapped = true)
        }
        val held = Prefs.of(c).getString("momento_held_$id", null)?.let { Moments.of(it) }
        val offered = Rules.pick(moments, KINDS, held)
        if (offered?.kind != held) Prefs.of(c).edit().putString("momento_held_$id", offered?.kind?.id).apply()
        if (offered != null) return Reading(moments, offered, FACE.getValue(offered.kind), false)
        // Nothing sure enough: the month, or the face learned for this slot
        val learned = MomentoLearn.rest(c, now)?.let { Moments.of(it) }?.let { FACE[it] }
        return Reading(moments, null, learned ?: REST, false, learned = learned != null)
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
        // A knot coming due turns its note from a time to "Late"
        knot(c)?.takeIf { !it.late }?.let { times += it.due + 1_000L }
        // A note's swap running out
        Widgets.ids(c, Kind.MOMENTO).forEach { id -> swapEnds(c, id)?.let { times += it + 1_000L } }
        // The golden hour begins 45 minutes before sunset, and ends with it (the top of each hour
        // already redraws for the occasion's morning and the late evening)
        runCatching {
            val zone = java.time.ZoneId.systemDefault()
            com.cerniera.kit.Sky.day(sky(c), java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), zone).sunset?.toEpochMilli()
        }.getOrNull()?.let { set -> times += set - 45 * 60_000L; times += set }
        return times.filter { it > now + 30_000L }.minOrNull() ?: Long.MAX_VALUE
    }

    /** Draws whichever face the moment needs, in place of the widget's own. */
    // ---- Taps, and what they teach (22 Nov 2026) ---------------------------------------------

    private const val SWAP_MS = 30 * 60_000L

    /** The note key widget [id] was swapped to, while the swap lasts. */
    fun swap(c: Context, id: Int, now: Long = System.currentTimeMillis()): String? {
        val v = Prefs.of(c).getString("momento_swap_$id", null) ?: return null
        val key = v.substringBefore("@"); val until = v.substringAfter("@").toLongOrNull() ?: 0L
        return key.takeIf { now < until }
    }

    fun swapEnds(c: Context, id: Int): Long? =
        Prefs.of(c).getString("momento_swap_$id", null)?.substringAfter("@")?.toLongOrNull()?.takeIf { it > System.currentTimeMillis() }

    /** What brought the face widget [id] shows now: a moment's id, or rest. */
    private fun shown(c: Context, id: Int): String {
        swap(c, id)?.let { return it }
        return Prefs.of(c).getString("momento_held_$id", null) ?: MomentoLearn.REST
    }

    /** A tap on the face: useful now. */
    fun faceTapped(c: Context, id: Int) {
        if (pinned(c, id) != null) return
        MomentoLearn.engaged(c, shown(c, id))
    }

    /** A tap on a note: not this, that. Swaps to the note's face for 30 minutes, and learns. */
    fun noteTapped(c: Context, id: Int, key: String) {
        val now = System.currentTimeMillis()
        MomentoLearn.swapped(c, shown(c, id), key, now)
        Prefs.of(c).edit().putString("momento_swap_$id", "$key@${now + SWAP_MS}").apply()
        Widgets.render(c, id)
        runCatching { Clockwork.arm(c) }
    }

    /** A moment's name in Settings' words. */
    fun label(key: String) = when (key) {
        MomentoLearn.REST -> "the month"
        "leaving_soon" -> "agenda (next event)"
        else -> Moments.of(key)?.let { k -> FACE[k]?.let { f -> f.name.lowercase() + " (" + k.label.lowercase() + ")" } } ?: key
    }

    // ---- The family's notes: Nodo and Dorso (22 Nov 2026) ----------------------------------------

    private val NODO: Uri get() = Uri.parse("content://com.cerniera.nodo.cues/next")
    private val DORSO: Uri get() = Uri.parse("content://com.cerniera.dorso.reading/current")

    /** Nodo's knot that matters most: the earliest late one, else the next due within two hours. */
    class Knot(val text: String, val due: Long, val late: Boolean, val lateCount: Int)
    fun knot(c: Context): Knot? = runCatching {
        c.contentResolver.query(NODO, null, null, null, null)?.use { cur ->
            if (!cur.moveToFirst()) null
            else Knot(cur.getString(cur.getColumnIndexOrThrow("text")), cur.getLong(cur.getColumnIndexOrThrow("due")),
                cur.getInt(cur.getColumnIndexOrThrow("late")) == 1, cur.getInt(cur.getColumnIndexOrThrow("late_count")))
        }
    }.getOrNull()

    /** The read you're partway through in Dorso, opened in the last week. */
    class Read(val id: String, val title: String, val minutesLeft: Int)
    fun reading(c: Context): Read? = runCatching {
        c.contentResolver.query(DORSO, null, null, null, null)?.use { cur ->
            if (!cur.moveToFirst()) null
            else Read(cur.getString(cur.getColumnIndexOrThrow("id")), cur.getString(cur.getColumnIndexOrThrow("title")),
                cur.getInt(cur.getColumnIndexOrThrow("minutes_left")))
        }
    }.getOrNull()

    /** Notes that open their app rather than swap the face. */
    const val NODO_NOTE = "nodo"
    const val DORSO_NOTE = "dorso"

    /** What a tap on the Nodo or Dorso note opens: Nodo, or Dorso at your page. */
    fun opens(c: Context, key: String): Intent? = when (key) {
        NODO_NOTE -> c.packageManager.getLaunchIntentForPackage("com.cerniera.nodo")
        DORSO_NOTE -> reading(c)?.let { r ->
            c.packageManager.getLaunchIntentForPackage("com.cerniera.dorso")?.putExtra("read", r.id)
        }
        else -> null
    }

    // ---- Composed faces (21 Nov 2026) ----------------------------------------------------------
    //
    // One face, and along its foot up to two notes: what else is worth knowing now, never what the
    // face already shows. The month with "17:30 · Newcastle v Arsenal" and "Sunset 15:59"; the music
    // with "Rain from 19:00". Notes come first from moments nearly sure enough to have taken the
    // face themselves (45% or more), then from the day's facts. Only where there's room: two from
    // 190 dp tall, one from 150 dp, none below. Settings › Momento switches them off per widget.

    /** Notes on (default) or off for widget [id]. */
    fun notesOn(c: Context, id: Int) = Prefs.of(c).getBoolean("momento_notes_$id", true)
    fun setNotes(c: Context, id: Int, on: Boolean) = Prefs.of(c).edit().putBoolean("momento_notes_$id", on).apply()

    /** Sample notes for the settings' debug preview (ids below zero), so a test sees the composition. */
    var sample: List<String>? = null

    /** A moment this close to the threshold is worth a note even when it hasn't the face. */
    const val NOTE_AT = 0.45f

    /** At most two notes for widget [id] beside the face [r] shows, most telling first. */
    fun notes(c: Context, id: Int, r: Reading, now: Long = System.currentTimeMillis()): List<Pair<String, String>> =
        candidates(c, id, r, now).take(2)

    /**
     * Every note worth showing now, most telling first; [notes] takes the first two. Settings lists
     * the rest as "Also waiting", so a note crowded out isn't mistaken for one missing.
     */
    fun candidates(c: Context, id: Int, r: Reading, now: Long = System.currentTimeMillis()): List<Pair<String, String>> {
        if (id < 0) sample?.let { l -> return l.mapIndexed { i, t -> (if (i == 0) "leaving_soon" else "sunset") to t } }
        if (r.pinned || !notesOn(c, id)) return emptyList()
        val s = signals(c, id, now)
        val zone = java.time.ZoneId.systemDefault()
        fun hm(t: Long) = java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(java.time.Instant.ofEpochMilli(t).atZone(zone))
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun isToday(t: Long) = java.time.Instant.ofEpochMilli(t).atZone(zone).toLocalDate() == today
        val out = LinkedHashMap<String, String>()
        fun note(key: String, text: String?) { if (text != null) out.putIfAbsent(key, text) }
        // Moments nearly sure enough, whose face isn't the one showing
        r.moments.filter { it.kind != r.offered?.kind && !it.dismissed && it.confidence >= NOTE_AT && FACE[it.kind] != r.face }
            .forEach { m ->
                note(m.kind.id, when (m.kind) {
                    Moments.LEAVING_SOON -> s.event?.let { "${hm(it.start)} · ${it.title}" }
                    Moments.RAIN_COMING -> s.rain?.let { "Rain from ${hm(it.startsAt)}" }
                    Moments.OCCASION -> s.occasion?.let { "Today · ${it.title}" }
                    Moments.SUNSET -> m.why
                    Moments.LISTENING -> s.playing?.let { (if (it.playing) "Playing · " else "Paused · ") + it.title }
                    else -> null
                })
            }
        // A late knot in Nodo comes before the day's facts: it's waiting on you
        val k = knot(c)
        if (k != null && k.late) note(NODO_NOTE, (if (k.lateCount > 1) "${k.lateCount} late knots · " else "Late · ") + k.text)
        // The day's facts, where the face doesn't already show them
        if (r.face != Kind.AGENDA) {
            note("occasion", s.occasion?.let { "Today · ${it.title}" })
            note("leaving_soon", s.event?.takeIf { isToday(it.start) }?.let { "${hm(it.start)} · ${it.title}" })
        }
        if (r.face != Kind.WEATHER) {
            note("rain_coming", s.rain?.takeIf { it.chance >= 0.4f && isToday(it.startsAt) && it.startsAt > now - 15 * 60_000L }
                ?.let { "Rain from ${hm(it.startsAt)}" })
            runCatching { com.cerniera.kit.Sky.day(s.place, today, zone).sunset?.toEpochMilli() }.getOrNull()
                ?.takeIf { now < it && it - now < 3 * 3_600_000L }?.let { note("sunset", "Sunset ${hm(it)}") }
        }
        // The read you're partway through, then a knot not yet late (22 Nov 2026, Mechack: the read
        // comes before a knot that can still wait)
        reading(c)?.let { d -> note(DORSO_NOTE, "Carry on · ${d.title} · ${d.minutesLeft} min left") }
        if (k != null && !k.late) note(NODO_NOTE, "${hm(k.due)} · ${k.text}")
        // Never a note for what the face already is
        return out.entries.filter { e -> Moments.of(e.key)?.let { FACE[it] } != r.face }.map { it.key to it.value }
    }

    object Painter : com.fergusonconsultancy.foldwidgets.Painter {
        private const val ROW = 20f
        private const val INSET = 14f   // the family's one inset (Kit Grammar)

        override fun paint(c: Context, id: Int, canvas: android.graphics.Canvas, s: com.cerniera.kit.Surface): Scene {
            val r = read(c, id)
            // Two notes from 190 dp tall; one, on a single line, from 150 dp (21 Nov 2026: Mechack's
            // usual Momento is about 165 dp tall); below that the face stays whole
            val notes = when {
                s.w < 150f || s.h < 150f -> emptyList()
                s.h < 190f -> notes(c, id, r).take(1)
                else -> notes(c, id, r)
            }
            if (notes.isEmpty()) return Widgets.paintFace(c, id, r.face, canvas, s)
            // Side by side when both fit half the width, otherwise one under the other
            val p = c.meta(0)
            val half = (s.w - INSET * 2 - 16f) / 2f
            val sideBySide = notes.size == 1 || notes.all { p.measureText(it.second) + 10f <= half }
            val rows = if (sideBySide) 1 else notes.size
            val band = rows * ROW + 16f
            // The face, in the room above the notes
            val above = com.cerniera.kit.Surface(s.finish, s.ground, s.w, s.h - band, s.radius, s.hairline)
            canvas.save(); canvas.clipRect(0f, 0f, s.w, s.h - band)
            val scene = Widgets.paintFace(c, id, r.face, canvas, above)
            canvas.restore()
            // A hairline, then each note after a small point of the finish's light
            val top = s.h - band
            val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = s.faint(s.w / 2f, top); strokeWidth = s.hairline
            }
            canvas.drawLine(INSET, top + 2f, s.w - INSET, top + 2f, line)
            val dot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            val hits = mutableListOf<Hit>()
            notes.forEachIndexed { i, (key, n) ->
                val x = if (sideBySide) INSET + i * (half + 16f) else INSET
                val rowTop = top + 8f + (if (sideBySide) 0 else i) * ROW
                val width = (if (sideBySide && notes.size > 1) half else s.w - INSET * 2) - 10f
                val cy = rowTop + ROW / 2f
                dot.color = s.accent(x, cy)
                canvas.drawCircle(x + 2f, cy, 2f, dot)
                val tp = c.meta(s.ink(x + 10f, cy))
                val text = fit(n, tp, width)
                canvas.drawText(text, x + 10f, cy + capHeight(tp) / 2f, tp)
                // A tap on a note swaps to its face (and teaches Momento); the row is the target
                val area = android.graphics.RectF(x - 6f, rowTop, x + width + 16f, rowTop + ROW + 4f)
                // Nodo's and Dorso's notes open their app; the others swap the face (and teach Momento)
                val opens = opens(c, key)
                hits += if (opens != null) Hit(area, Act.Open(intent = opens), n)
                    else Hit(area, Act.Send(TapReceiver.ACTION_NOTE + ":" + key), n)
            }
            // The face's own touch areas, then the notes', within the widget's twelve
            return Scene((scene.hits.take(12 - hits.size) + hits), scene.line, scene.listTop, if (scene.listTop >= 0f) s.h - band else -1f, scene.listScale, scene.listFaceH)
        }
    }
}
