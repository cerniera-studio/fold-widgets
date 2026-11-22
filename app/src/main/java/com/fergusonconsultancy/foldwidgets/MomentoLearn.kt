package com.fergusonconsultancy.foldwidgets

import android.content.Context
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import kotlin.math.pow

/**
 * What Momento learns from taps (22 Nov 2026). Rules only, on the phone, nothing sent anywhere.
 *
 * Two kinds of tap teach it:
 *  - A tap on the face (opening the calendar, the player, the weather): this was useful now. +1
 *    for whatever brought the face (a moment, or rest).
 *  - A tap on a note: not this, that. The widget swaps to the note's face for 30 minutes; +2 for
 *    the note's moment, -1 for what was showing.
 *
 * Kept per slot of the week: weekdays or the weekend, and night, morning, afternoon, evening or
 * late, so "Saturday afternoon" and "Tuesday morning" learn apart. Counts fade with a three-week
 * half-life, so old habits give way. Nothing is changed until a slot holds three taps' worth.
 *
 * What it changes:
 *  - A moment's confidence, by up to 0.15 either way, so a moment you keep reaching for crosses
 *    the 60% line sooner, and one you keep swapping away from is held back.
 *  - The face at rest: if, in a slot, you keep swapping the month for the same face, that face
 *    becomes the rest for that slot.
 * Settings › Momento lists what has been learned, with a reset.
 */
object MomentoLearn {
    const val REST = "rest"
    private const val KEY = "momento_learned"
    private const val HALF_LIFE_DAYS = 21.0
    private const val EVIDENCE = 3.0
    const val MAX = 0.15f

    enum class Part(val label: String, val from: Int) {
        NIGHT("night", 0), MORNING("morning", 6), AFTERNOON("afternoon", 12), EVENING("evening", 17), LATE("late evening", 22);
        companion object { fun of(hour: Int) = entries.last { hour >= it.from } }
    }

    /** The slot of the week a moment falls in, e.g. "weekend-afternoon". */
    fun slot(now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(now).atZone(zone)
        val day = if (t.dayOfWeek.value >= 6) "weekend" else "weekday"
        return "$day-${Part.of(t.hour).name.lowercase()}"
    }

    fun describe(slot: String): String {
        val (day, part) = slot.split("-").let { it[0] to it.getOrElse(1) { "" } }
        val p = Part.entries.firstOrNull { it.name.lowercase() == part }?.label ?: part
        return (if (day == "weekend") "Weekend " else "Weekday ") + p + "s"
    }

    // ---- The store: slot → target → [positive, negative, last update ms] -------------------

    private fun load(c: Context) = runCatching { JSONObject(Prefs.of(c).getString(KEY, "{}")!!) }.getOrElse { JSONObject() }
    private fun save(c: Context, j: JSONObject) = Prefs.of(c).edit().putString(KEY, j.toString()).apply()

    private class Tally(val pos: Double, val neg: Double)

    /** Counts faded to now. */
    private fun tally(j: JSONObject, slot: String, target: String, now: Long): Tally {
        val a = j.optJSONObject(slot)?.optJSONArray(target) ?: return Tally(0.0, 0.0)
        val days = (now - a.optLong(2, now)).coerceAtLeast(0) / 86_400_000.0
        val k = 0.5.pow(days / HALF_LIFE_DAYS)
        return Tally(a.optDouble(0, 0.0) * k, a.optDouble(1, 0.0) * k)
    }

    private fun add(c: Context, slot: String, target: String, pos: Double, neg: Double, now: Long) {
        val j = load(c)
        val t = tally(j, slot, target, now)
        val s = j.optJSONObject(slot) ?: JSONObject().also { j.put(slot, it) }
        s.put(target, org.json.JSONArray().put(t.pos + pos).put(t.neg + neg).put(now))
        save(c, j)
    }

    /** A tap on the face: whatever brought it was useful now. */
    fun engaged(c: Context, target: String, now: Long = System.currentTimeMillis()) = add(c, slot(now), target, 1.0, 0.0, now)

    /** A tap on a note: [wanted] rather than [shown]. */
    fun swapped(c: Context, shown: String, wanted: String, now: Long = System.currentTimeMillis()) {
        add(c, slot(now), wanted, 2.0, 0.0, now)
        add(c, slot(now), shown, 0.0, 1.0, now)
        // Rest votes: which face is wanted when nothing is sure, kept apart from the moments' own
        if (shown == REST) add(c, slot(now), "$REST>$wanted", 1.0, 0.0, now)
    }

    /** How much learning moves [target]'s confidence now: up to ±[MAX], nothing without evidence. */
    fun adjust(c: Context, target: String, now: Long = System.currentTimeMillis()): Float {
        val t = tally(load(c), slot(now), target, now)
        val n = t.pos + t.neg
        if (n < EVIDENCE) return 0f
        return (MAX * (t.pos - t.neg) / (n + 2.0)).toFloat().coerceIn(-MAX, MAX)
    }

    /** The face (a moment's id) learned as the rest for this slot, or null for the month. */
    fun rest(c: Context, now: Long = System.currentTimeMillis()): String? {
        val j = load(c)
        val slot = slot(now)
        val s = j.optJSONObject(slot) ?: return null
        val votes = s.keys().asSequence().filter { it.startsWith("$REST>") }
            .map { it.removePrefix("$REST>") to tally(j, slot, it, now).pos }.toList()
        val best = votes.maxByOrNull { it.second } ?: return null
        val others = votes.filter { it !== best }.sumOf { it.second }
        return best.first.takeIf { best.second >= EVIDENCE && best.second > others }
    }

    /** What has been learned, for Settings: one line per slot and target that moves anything. */
    fun summary(c: Context, label: (String) -> String, now: Long = System.currentTimeMillis()): List<String> {
        val j = load(c)
        val out = mutableListOf<String>()
        j.keys().asSequence().sorted().forEach { slot ->
            val s = j.optJSONObject(slot) ?: return@forEach
            s.keys().asSequence().sorted().forEach { target ->
                val t = tally(j, slot, target, now)
                val n = t.pos + t.neg
                if (n < EVIDENCE) return@forEach
                if (target.startsWith("$REST>")) {
                    out += "${describe(slot)}: ${label(target.removePrefix("$REST>"))} when nothing else is due (${t.pos.toInt()} taps)"
                } else {
                    val a = (MAX * (t.pos - t.neg) / (n + 2.0)).toFloat()
                    if (kotlin.math.abs(a) >= 0.02f) out += "${describe(slot)}: ${label(target)} ${if (a > 0) "sooner" else "held back"} (${"%+d".format((a * 100).toInt())} points)"
                }
            }
        }
        return out
    }

    fun reset(c: Context) = Prefs.of(c).edit().remove(KEY).apply()
}
