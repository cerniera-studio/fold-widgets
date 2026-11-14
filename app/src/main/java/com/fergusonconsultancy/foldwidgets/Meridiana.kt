package com.fergusonconsultancy.foldwidgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan
import com.cerniera.kit.Sky as Heavens

/**
 * Meridiana · Dial (14 Nov 2026; claude/meridiana-design.md). A sundial that tells the time with
 * the real sun over the phone, from the Kit's one sky, so it agrees with Solco's disc and Sveglia's
 * dawn.
 *
 * - Tall shapes look down on the dial (plan): the gnomon's foot sits on the meridian, the shadow's
 *   direction comes from the sun's azimuth and its length from its altitude, so it is short in June
 *   and long in November. On the main screen, a dial spanning the crease takes the crease as its
 *   meridian; anywhere else the meridian is a champagne hairline up the middle.
 * - Wide shapes look across the horizon (elevation): today's path as a trail of grain from sunrise
 *   to sunset, the sun on it at its true altitude, a post casting its shadow away from the sun. On
 *   the main screen turned, a dial spanning the crease takes it as the horizon (as Sveglia does).
 * - Hour lines are drawn where the shadow will fall at each clock hour today, so the dial tells clock
 *   time (BST, the equation of time and the season included). The 12 line sits a little off the
 *   meridian: that gap is the equation of time, kept visible.
 * - No digits at rest (Giulietta). A tap shows the exact time in Italiana with today's sunrise,
 *   sunset and golden hour, and after 3.2 s it sinks back into the dial.
 * - The membrane: the hour line the shadow is approaching lifts a little; the one it passed settles.
 * - Night: no shadow. Tomorrow's sunrise is marked where its shadow will first fall (or on the
 *   horizon), and the moon's phase sits small at the meridian's head.
 */
object Meridiana : Painter {
    const val ACTION_REVEAL = "com.fergusonconsultancy.foldwidgets.REVEAL"
    const val REVEAL_MS = 3200L
    /** Redrawn every five minutes while a dial is placed (Clockwork), never while the screen is off. */
    const val CADENCE_MS = 5 * 60_000L

    // Debug previews only: a fixed moment, a crease position (fraction across or down), a reveal
    val fixedAt = ConcurrentHashMap<Int, Instant>()
    val fixedCrease = ConcurrentHashMap<Int, Float>()
    val fixedReveal = ConcurrentHashMap<Int, Boolean>()

    /** The weather's place, so the dial and the weather always agree; Leeds until one is set. */
    fun place(c: Context): Heavens.Place =
        Weather.place(c)?.let { Heavens.Place(it.name, it.lat, it.lon) } ?: Heavens.Place.LEEDS

    fun reveal(c: Context, id: Int) = Prefs.of(c).edit().putLong("reveal_$id", System.currentTimeMillis() + REVEAL_MS).apply()
    private fun revealed(c: Context, id: Int) = fixedReveal[id] ?: (Prefs.of(c).getLong("reveal_$id", 0L) > System.currentTimeMillis())

    override fun paint(c: Context, id: Int, canvas: Canvas, s: Surface): Scene {
        val at = fixedAt[id] ?: Instant.now()
        val zone = ZoneId.systemDefault()
        val elevation = s.w > s.h * 1.3f
        val crease = fixedCrease[id] ?: crease(c, id, s, elevation)
        val sky = SkyNow(place(c), at, zone)
        canvas.save()
        canvas.clipPath(s.shape)
        if (elevation) Elevation(c, canvas, s, sky, crease, revealed(c, id)).draw()
        else Plan(c, canvas, s, sky, crease, revealed(c, id)).draw()
        canvas.restore()
        return Scene(listOf(Hit(RectF(0f, 0f, s.w, s.h), Act.Send(ACTION_REVEAL), "Sundial: show the exact time")))
    }

    /**
     * Where the crease crosses this dial, as a fraction across (plan, crease upright) or down
     * (elevation, crease across), from the seat a tap measured; -1 when it doesn't cross, or isn't known.
     */
    private fun crease(c: Context, id: Int, s: Surface, elevation: Boolean): Float {
        val seat = Backdrop.seat(c, id) ?: return -1f
        if (seat.onCover || !seat.exact) return -1f
        val screen = Backdrop.screenSize(c)
        if (screen.x != seat.screenW || screen.y != seat.screenH) return -1f
        // This picture must be the shape the seat was measured at (the other orientation isn't)
        if (abs(seat.width / seat.height - s.w / s.h) > 0.12f * (s.w / s.h)) return -1f
        val upright = seat.screenW <= seat.screenH
        return if (!elevation && upright) {
            ((seat.screenW / 2f - seat.left) / seat.width).takeIf { it in 0.1f..0.9f } ?: -1f
        } else if (elevation && !upright) {
            ((seat.screenH / 2f - seat.top) / seat.height).takeIf { it in 0.2f..0.85f } ?: -1f
        } else -1f
    }

    /** Everything the dial needs about the sky at one moment. */
    class SkyNow(val place: Heavens.Place, val at: Instant, val zone: ZoneId) {
        val local = at.atZone(zone)
        val date: LocalDate = local.toLocalDate()
        val sun = Heavens.sun(place, at)
        val today = Heavens.day(place, date, zone)
        val day = sun.up
        /** The sunrise still to come: today's if before it, else tomorrow's. */
        val nextRise: Instant? = today.sunrise?.takeIf { at.isBefore(it) } ?: Heavens.day(place, date.plusDays(1), zone).sunrise
        val moon = Heavens.moon(at)
        /** The highest the sun ever climbs here (midsummer noon), for scaling. */
        val peak = min(89.0, 90 - abs(place.lat) + 23.44)
        /** The lowest noon of the year, for the plan's gnomon (never below 8°). */
        val lowNoon = max(8.0, 90 - abs(place.lat) - 23.44)
        val south = place.lat >= 0
        /** Fractional clock hour now. */
        val hour = local.hour + local.minute / 60f + local.second / 3600f

        /** The sun at a clock hour today, or null if it's down then. */
        fun atHour(h: Int): Heavens.SunPosition? {
            val t = date.atTime(h, 0).atZone(zone).toInstant()
            return Heavens.sun(place, t).takeIf { it.altitude > 0.5 }
        }

        fun clock(i: Instant?): String = i?.atZone(zone)?.format(HHMM) ?: "none"
    }

    private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

    private fun stroke(colour: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = width; color = colour; strokeCap = Paint.Cap.ROUND
    }
    private fun fill(colour: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour }
    private fun alpha(colour: Int, a: Float) = (colour and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

    /** The moon at [r] around ([cx], [cy]): its unlit face faint, the lit part bounded by the terminator. */
    private fun moon(c: Canvas, cx: Float, cy: Float, r: Float, phase: Double, colour: Int) {
        c.drawCircle(cx, cy, r, fill(alpha(colour, 0.14f)))
        val p = phase.toFloat()
        val circle = RectF(cx - r, cy - r, cx + r, cy + r)
        val rx = max(.01f, r * abs(cos(2 * PI * p)).toFloat())
        val ell = RectF(cx - rx, cy - r, cx + rx, cy + r)
        val path = Path()
        if (p < .5f) {
            path.arcTo(circle, -90f, 180f, true)
            if (p < .25f) path.arcTo(ell, 90f, -180f) else path.arcTo(ell, 90f, 180f)
        } else {
            path.arcTo(circle, 90f, 180f, true)
            if (p > .75f) path.arcTo(ell, 270f, -180f) else path.arcTo(ell, -90f, 180f)
        }
        path.close()
        c.drawPath(path, fill(colour))
    }

    /** The exact time and today's light, shown on a tap. [top] is where the figure's block starts. */
    private fun revealText(ctx: Context, c: Canvas, s: Surface, sky: SkyNow, cx: Float, top: Float, size: Float, width: Float) {
        val numP = ctx.ink(size, s.ink(cx, top + size / 2), display = true).apply { textAlign = Paint.Align.CENTER }
        val time = sky.local.format(HHMM)
        c.drawText(time, cx, top + capHeight(numP), numP)
        val k = (size / 60f).coerceIn(0.8f, 1.3f)
        val metaP = ctx.meta(s.muted(cx, top + size), k).apply { textAlign = Paint.Align.CENTER }
        val d = sky.today
        val golden = d.goldenEveningStart
        val lines = if (sky.day) listOf(
            "Sunrise ${sky.clock(d.sunrise)} · Sunset ${sky.clock(d.sunset)}",
            if (golden != null && sky.at.isBefore(golden)) "Golden hour from ${sky.clock(golden)}"
            else "Golden hour now, until ${sky.clock(d.sunset)}",
        ) else listOf("Sunrise ${sky.clock(sky.nextRise)}", sky.moon.label)
        var y = top + capHeight(numP) + metaP.textSize * 1.3f
        for (l in lines) {
            c.drawText(fit(l, metaP, width), cx, y + capHeight(metaP), metaP)
            y += lineHeight(metaP, 1.35f)
        }
    }

    /** Looking down on the dial. */
    private class Plan(val ctx: Context, val c: Canvas, val s: Surface, val sky: SkyNow, crease: Float, val reveal: Boolean) {
        val pad = min(s.w, s.h) * 0.09f
        val mx = if (crease >= 0f) crease * s.w else s.w / 2
        val footY = s.h - pad * 1.5f
        val reach = footY - pad
        /** The gnomon's height: the year's longest noon shadow reaches 92% of the way up. */
        val g = reach * tan(Math.toRadians(sky.lowNoon)).toFloat() * 0.92f
        val unit = min(s.w, s.h) / 160f

        /** Unit vector of the shadow for a sun at [az] (north up, east right). */
        fun dir(az: Double): Pair<Float, Float> {
            val t = Math.toRadians(az + 180)
            val dx = sin(t).toFloat(); val dy = (-cos(t)).toFloat()
            return if (sky.south) dx to dy else -dx to -dy
        }

        fun draw() {
            val ink = s.ink(mx, s.h / 2)
            val accent = s.accent(mx, s.h / 2)
            val quiet = if (reveal) 0.35f else 1f
            val far = hypot(s.w, s.h)
            // The meridian: the crease itself when the dial spans it, otherwise a hairline
            c.drawLine(mx, footY, mx, pad * 0.6f, stroke(alpha(accent, (if (sky.day) 0.75f else 0.5f) * quiet), max(0.8f, unit * 0.9f)))
            // Hour lines where the shadow will fall at each clock hour today
            val next = kotlin.math.ceil(sky.hour.toDouble()).toInt()
            for (h in 0..23) {
                val pos = sky.atHour(h) ?: continue
                val (dx, dy) = dir(pos.azimuth)
                val major = h % 3 == 0
                var a = when { h == 12 -> 0.85f; major -> 0.5f; else -> 0.3f }
                var w = (if (h == 12) 1.3f else 0.9f) * unit
                if (sky.day && h == next) {
                    // The membrane: the line the shadow is approaching lifts as it nears
                    val near = 1f - (next - sky.hour)
                    a += 0.3f * near; w *= 1f + 0.5f * near
                }
                if (!sky.day) a *= 0.6f
                val r0 = reach * (if (major) 0.16f else 0.24f)
                c.drawLine(mx + dx * r0, footY + dy * r0, mx + dx * far, footY + dy * far, stroke(alpha(ink, a * quiet), w))
            }
            if (sky.day) shadow(ink) else night(ink, accent)
            // The gnomon's foot
            c.drawCircle(mx, footY, 2.6f * unit, fill(alpha(accent, quiet.coerceAtLeast(0.6f))))
            if (reveal) {
                val size = min(s.w * 0.3f, s.h * 0.22f)
                revealText(ctx, c, s, sky, s.w / 2, s.h * 0.2f, size, s.w - 2 * pad)
            }
        }

        fun shadow(ink: Int) {
            val (dx, dy) = dir(sky.sun.azimuth)
            val len = min(g / tan(Math.toRadians(max(sky.sun.altitude, 0.6))).toFloat(), hypot(s.w, s.h))
            val tx = mx + dx * len; val ty = footY + dy * len
            // A soft penumbra under a tapering core, widest at the foot
            c.drawLine(mx, footY, tx, ty, stroke(alpha(ink, 0.12f), 6f * unit))
            val nx = -dy; val ny = dx
            val w0 = 2.2f * unit; val w1 = 0.5f * unit
            val p = Path().apply {
                moveTo(mx + nx * w0, footY + ny * w0); lineTo(tx + nx * w1, ty + ny * w1)
                lineTo(tx - nx * w1, ty - ny * w1); lineTo(mx - nx * w0, footY - ny * w0); close()
            }
            c.drawPath(p, fill(alpha(ink, if (reveal) 0.4f else 0.82f)))
        }

        fun night(ink: Int, accent: Int) {
            // Tomorrow's sunrise: where its first shadow will fall, at the dial's edge
            sky.nextRise?.let { t ->
                val pos = Heavens.sun(sky.place, t.plusSeconds(120))
                val (dx, dy) = dir(pos.azimuth)
                // As far along its line as the dial allows, kept inside the edge
                val edge = 2 * pad / 3
                var r = reach * 0.9f
                if (dx < 0) r = min(r, (mx - edge) / -dx); if (dx > 0) r = min(r, (s.w - edge - mx) / dx)
                if (dy < 0) r = min(r, (footY - edge) / -dy); if (dy > 0) r = min(r, (s.h - edge - footY) / dy)
                c.drawCircle(mx + dx * r, footY + dy * r, 2.2f * unit, fill(alpha(accent, 0.85f)))
            }
            // Tonight's moon, small at the meridian's head
            if (!reveal) moon(c, mx, pad + 6f * unit, 5.5f * unit, sky.moon.fraction, alpha(ink, 0.85f))
        }
    }

    /** Looking across the horizon. */
    private class Elevation(val ctx: Context, val c: Canvas, val s: Surface, val sky: SkyNow, crease: Float, val reveal: Boolean) {
        val pad = min(s.w, s.h) * 0.12f
        val hy = if (crease >= 0f) crease * s.h else s.h * 0.64f
        val mid = s.w / 2
        val unit = min(s.w, s.h) / 120f

        fun x(az: Double): Float {
            val off = if (sky.south) az - 180 else (if (az > 180) az - 360 else az)
            val k = (off / 135.0).coerceIn(-1.0, 1.0).toFloat()
            return mid + k * (mid - pad)
        }
        fun y(alt: Double) = hy - (alt / sky.peak).toFloat().coerceAtLeast(0f) * (hy - pad)

        fun draw() {
            val ink = s.ink(mid, hy / 2)
            val accent = s.accent(mid, hy / 2)
            val quiet = if (reveal) 0.45f else 1f
            // The ground below the horizon, and the horizon itself
            c.drawRect(0f, hy, s.w, s.h, fill(alpha(ink, 0.06f)))
            c.drawLine(0f, hy, s.w, hy, stroke(alpha(ink, 0.45f), max(0.8f, unit * 0.8f)))
            path(ink, accent, quiet)
            if (sky.day) sunAndPost(ink, accent, quiet) else night(ink, accent)
            if (reveal) {
                val size = min(hy * 0.42f, s.w * 0.16f)
                revealText(ctx, c, s, sky, mid, pad * 0.5f, size, s.w - 2 * pad)
                // Sunrise and sunset where the path meets the horizon
                val metaP = ctx.meta(s.muted(mid, hy), (unit * 1.1f).coerceIn(0.8f, 1.2f))
                sky.today.sunrise?.let { t -> val px = x(Heavens.sun(sky.place, t).azimuth)
                    c.drawText(sky.clock(t), (px - metaP.measureText("00:00") / 2).coerceAtLeast(pad * 0.4f), hy + metaP.textSize * 1.3f, metaP) }
                sky.today.sunset?.let { t -> val px = x(Heavens.sun(sky.place, t).azimuth)
                    c.drawText(sky.clock(t), (px - metaP.measureText("00:00") / 2).coerceAtMost(s.w - pad * 0.4f - metaP.measureText("00:00")), hy + metaP.textSize * 1.3f, metaP) }
            }
        }

        /** Today's path, a trail of grain; golden hours in the finish's light; a tick at each clock hour. */
        fun path(ink: Int, accent: Int, quiet: Float) {
            val d = sky.today
            val from = d.sunrise ?: d.solarNoon.minusSeconds(6 * 3600)
            val to = d.sunset ?: d.solarNoon.plusSeconds(6 * 3600)
            val dim = if (sky.day) 1f else 0.5f
            val gm = d.goldenMorningEnd; val ge = d.goldenEveningStart
            var lx = Float.NaN; var ly = Float.NaN
            var t = from
            val step = 3.2f * unit
            while (!t.isAfter(to)) {
                val p = Heavens.sun(sky.place, t)
                if (p.altitude > -1) {
                    val px = x(p.azimuth); val py = y(p.altitude)
                    if (lx.isNaN() || hypot(px - lx, py - ly) >= step) {
                        val golden = (gm != null && t.isBefore(gm)) || (ge != null && t.isAfter(ge))
                        c.drawCircle(px, py, 0.75f * unit, fill(alpha(if (golden) accent else ink, (if (golden) 0.8f else 0.42f) * dim * quiet)))
                        lx = px; ly = py
                    }
                }
                t = t.plusSeconds(120)
            }
            // Clock hours, unnumbered; the line the sun is approaching lifts
            val next = kotlin.math.ceil(sky.hour.toDouble()).toInt()
            for (h in 0..23) {
                val p = sky.atHour(h) ?: continue
                val px = x(p.azimuth); val py = y(p.altitude)
                var a = if (h == 12) 0.8f else 0.4f
                if (sky.day && h == next) a += 0.35f * (1f - (next - sky.hour))
                c.drawLine(px, py - 2.6f * unit, px, py + 2.6f * unit, stroke(alpha(ink, a * dim * quiet), 0.9f * unit))
            }
        }

        fun sunAndPost(ink: Int, accent: Int, quiet: Float) {
            val sx = x(sky.sun.azimuth); val sy = y(sky.sun.altitude)
            c.drawCircle(sx, sy, 3.6f * unit, fill(alpha(accent, quiet.coerceAtLeast(0.7f))))
            // A post on the meridian, its shadow on the ground running away from the sun
            val ground = s.h - hy
            val foot = if (ground > 14f * unit) hy + ground * 0.45f else hy
            val postH = min(hy - pad, 16f * unit)
            c.drawLine(mid, foot, mid, foot - postH, stroke(alpha(ink, 0.8f * quiet), 1.2f * unit))
            val len = min(postH / tan(Math.toRadians(max(sky.sun.altitude, 1.0))).toFloat() * 0.45f, mid - pad * 0.5f)
            val away = if (sx < mid) 1f else -1f
            c.drawLine(mid, foot, mid + away * len, foot, stroke(alpha(ink, 0.7f * quiet), 1.6f * unit))
        }

        fun night(ink: Int, accent: Int) {
            sky.nextRise?.let { t -> c.drawCircle(x(Heavens.sun(sky.place, t).azimuth), hy, 2.2f * unit, fill(alpha(accent, 0.85f))) }
            if (!reveal) moon(c, mid, pad * 0.9f + 4f * unit, 4.5f * unit, sky.moon.fraction, alpha(ink, 0.85f))
        }
    }
}
