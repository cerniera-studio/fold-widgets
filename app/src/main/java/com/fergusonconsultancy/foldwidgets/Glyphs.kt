package com.fergusonconsultancy.foldwidgets

import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * The marks the widgets draw for themselves, so they scale cleanly to any size and take the ink
 * of the zone they sit in. Geometric in the Bauhaus line: circles, bars and triangles with softened
 * corners, nothing decorative.
 */
object Glyphs {

    private fun fill(colour: Int, round: Float = 0f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colour; style = Paint.Style.FILL
        if (round > 0f) pathEffect = CornerPathEffect(round)
    }

    /** A play triangle optically centred in a box of [s] around ([cx], [cy]). */
    fun play(c: Canvas, cx: Float, cy: Float, s: Float, colour: Int) {
        val h = s * 0.92f; val w = h * 0.86f
        val x0 = cx - w * 0.40f  // a triangle's centre of mass sits left of its box's centre
        c.drawPath(Path().apply {
            moveTo(x0, cy - h / 2); lineTo(x0 + w, cy); lineTo(x0, cy + h / 2); close()
        }, fill(colour, s * 0.14f))
    }

    fun pause(c: Canvas, cx: Float, cy: Float, s: Float, colour: Int) {
        val bw = s * 0.28f; val gap = s * 0.22f; val h = s * 0.86f
        val p = fill(colour)
        c.drawRoundRect(RectF(cx - gap / 2 - bw, cy - h / 2, cx - gap / 2, cy + h / 2), bw * 0.32f, bw * 0.32f, p)
        c.drawRoundRect(RectF(cx + gap / 2, cy - h / 2, cx + gap / 2 + bw, cy + h / 2), bw * 0.32f, bw * 0.32f, p)
    }

    /** No artwork: a record, the circle as the perfect graphic shape. */
    fun record(c: Canvas, r: RectF, ink: Int) {
        val cx = r.centerX(); val cy = r.centerY(); val rad = minOf(r.width(), r.height()) * 0.34f
        c.drawCircle(cx, cy, rad, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = rad * 0.07f; color = (ink and 0x00FFFFFF) or 0x80000000.toInt()
        })
        c.drawCircle(cx, cy, rad * 0.3f, fill((ink and 0x00FFFFFF) or 0x80000000.toInt()))
    }

    // ---- Weather ---------------------------------------------------------------------------------

    enum class Sky { CLEAR, PARTLY, CLOUD, FOG, DRIZZLE, RAIN, SNOW, STORM }

    /**
     * The sky in a box of [s] around ([cx], [cy]). The sun is a plain disc in the finish's light,
     * the moon a crescent; cloud is a soft mass of circles in the ink; what falls from it is drawn
     * in short strokes beneath.
     */
    fun sky(c: Canvas, kind: Sky, day: Boolean, cx: Float, cy: Float, s: Float, ink: Int, light: Int) {
        when (kind) {
            Sky.CLEAR -> orb(c, cx, cy, s * 0.30f, day, light, ink)
            Sky.PARTLY -> {
                // The sun in its own layer, so the margin cut round the cloud only clears the sun
                val layer = c.saveLayer(cx - s, cy - s, cx + s, cy + s, null)
                orb(c, cx - s * 0.13f, cy - s * 0.12f, s * 0.22f, day, light, ink)
                c.drawPath(cloudPath(cx + s * 0.06f, cy + s * 0.10f, s * 0.78f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL_AND_STROKE; strokeWidth = s * 0.09f
                    xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
                })
                c.restoreToCount(layer)
                cloud(c, cx + s * 0.06f, cy + s * 0.10f, s * 0.78f, ink)
            }
            Sky.CLOUD -> cloud(c, cx, cy, s, ink)
            Sky.FOG -> {
                cloud(c, cx, cy - s * 0.14f, s * 0.84f, ink)
                bars(c, cx, cy + s * 0.20f, s, ink)
            }
            Sky.DRIZZLE -> { cloud(c, cx, cy - s * 0.12f, s * 0.86f, ink); fall(c, cx, cy + s * 0.22f, s, ink, 3, 0.5f) }
            Sky.RAIN -> { cloud(c, cx, cy - s * 0.12f, s * 0.86f, ink); fall(c, cx, cy + s * 0.22f, s, ink, 3, 1f) }
            Sky.SNOW -> { cloud(c, cx, cy - s * 0.12f, s * 0.86f, ink); flakes(c, cx, cy + s * 0.26f, s, ink) }
            Sky.STORM -> { cloud(c, cx, cy - s * 0.12f, s * 0.86f, ink); bolt(c, cx, cy + s * 0.24f, s, light) }
        }
    }

    private fun orb(c: Canvas, cx: Float, cy: Float, r: Float, day: Boolean, light: Int, ink: Int) {
        if (day) { c.drawCircle(cx, cy, r, fill(light)); return }
        // The crescent: a disc with a second disc taken out of it
        val layer = c.saveLayer(cx - r * 2, cy - r * 2, cx + r * 2, cy + r * 2, null)
        c.drawCircle(cx, cy, r, fill(ink))
        c.drawCircle(cx + r * 0.55f, cy - r * 0.35f, r * 0.86f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        })
        c.restoreToCount(layer)
    }

    /** A cloud [s] wide: three circles over a rounded base. */
    private fun cloudPath(cx: Float, cy: Float, s: Float) = Path().apply {
        val baseH = s * 0.26f
        addRoundRect(RectF(cx - s * 0.40f, cy - baseH * 0.15f, cx + s * 0.40f, cy + baseH), baseH / 2, baseH / 2, Path.Direction.CW)
        addCircle(cx - s * 0.18f, cy - s * 0.02f, s * 0.17f, Path.Direction.CW)
        addCircle(cx + s * 0.06f, cy - s * 0.10f, s * 0.24f, Path.Direction.CW)
        addCircle(cx + s * 0.27f, cy + s * 0.04f, s * 0.13f, Path.Direction.CW)
    }

    private fun cloud(c: Canvas, cx: Float, cy: Float, s: Float, ink: Int) = c.drawPath(cloudPath(cx, cy, s), fill(ink))

    private fun stroke(colour: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colour; style = Paint.Style.STROKE; strokeWidth = w; strokeCap = Paint.Cap.ROUND
    }

    private fun bars(c: Canvas, cx: Float, cy: Float, s: Float, ink: Int) {
        val p = stroke(ink, s * 0.065f)
        c.drawLine(cx - s * 0.34f, cy, cx + s * 0.34f, cy, p)
        c.drawLine(cx - s * 0.24f, cy + s * 0.14f, cx + s * 0.30f, cy + s * 0.14f, p)
        c.drawLine(cx - s * 0.30f, cy + s * 0.28f, cx + s * 0.18f, cy + s * 0.28f, p)
    }

    private fun fall(c: Canvas, cx: Float, cy: Float, s: Float, ink: Int, n: Int, length: Float) {
        val p = stroke(ink, s * 0.06f)
        for (i in 0 until n) {
            val x = cx + (i - (n - 1) / 2f) * s * 0.22f
            val l = s * 0.18f * length
            c.drawLine(x + l * 0.3f, cy, x - l * 0.3f, cy + l, p)
        }
    }

    private fun flakes(c: Canvas, cx: Float, cy: Float, s: Float, ink: Int) {
        val p = fill(ink)
        for ((dx, dy) in listOf(-0.22f to 0f, 0f to 0.10f, 0.22f to 0f)) c.drawCircle(cx + dx * s, cy + dy * s, s * 0.045f, p)
    }

    private fun bolt(c: Canvas, cx: Float, cy: Float, s: Float, light: Int) {
        c.drawPath(Path().apply {
            moveTo(cx + s * 0.04f, cy - s * 0.12f); lineTo(cx - s * 0.08f, cy + s * 0.05f); lineTo(cx + s * 0.01f, cy + s * 0.05f)
            lineTo(cx - s * 0.05f, cy + s * 0.22f); lineTo(cx + s * 0.10f, cy - s * 0.01f); lineTo(cx + s * 0.01f, cy - s * 0.01f); close()
        }, fill(light, s * 0.02f))
    }
}
