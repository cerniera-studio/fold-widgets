package com.fergusonconsultancy.foldwidgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils

/**
 * The family's palette and type, shared with Fold Motion and Fold Taskbar: smoked green-black,
 * ivory ink, champagne for what is chosen. Italiana names things; Jost, a geometric sans in the
 * Bauhaus line, does everything else.
 */
object Palette {
    const val GROUND = 0xFF0E1311.toInt()
    const val SURFACE = 0xFF151C18.toInt()
    const val RAISED = 0xFF1D2621.toInt()
    const val HAIRLINE = 0x1AF6F1E6
    const val IVORY = 0xFFF6F1E6.toInt()
    const val IRON = 0xFF16191A.toInt()
    const val MUTED = 0xFF96A098.toInt()
    const val CHAMPAGNE = 0xFFD9BB86.toInt()
    const val BRONZE = 0xFF7A5A28.toInt()
    const val ROSSO = 0xFFD7262E.toInt()
    const val GOOD = 0xFF7FC79A.toInt()
    const val WARN = 0xFFE0A15A.toInt()
}

object Type {
    private var display: Typeface? = null
    private val jost = HashMap<Int, Typeface>()

    fun display(c: Context): Typeface = display ?: runCatching {
        Typeface.createFromAsset(c.assets, "fonts/Italiana-Regular.ttf")
    }.getOrDefault(Typeface.SERIF).also { display = it }

    /** Jost at a given weight (variable font), falling back to the system sans. */
    fun sans(c: Context, weight: Int = 400): Typeface = synchronized(jost) {
        jost.getOrPut(weight) {
            runCatching {
                Typeface.Builder(c.assets, "fonts/Jost.ttf").setFontVariationSettings("'wght' $weight").build()
            }.getOrNull() ?: Typeface.create(Typeface.SANS_SERIF, weight, false)
        }
    }
}

/** A paint for text at [size] px. */
fun Context.ink(size: Float, colour: Int, weight: Int = 400, display: Boolean = false, tracking: Float = 0f) =
    TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = if (display) Type.display(this@ink) else Type.sans(this@ink, weight)
        textSize = size
        color = colour
        letterSpacing = tracking
    }

/** [s] cut to [width] with an ellipsis. */
fun fit(s: CharSequence, p: TextPaint, width: Float): String =
    TextUtils.ellipsize(s, p, width.coerceAtLeast(1f), TextUtils.TruncateAt.END).toString()

/**
 * [s] broken into at most [lines] lines of [width], the last ellipsised. Breaks at spaces where it
 * can, so a song title reads as words, not as a cut.
 */
fun wrap(s: String, p: TextPaint, width: Float, lines: Int): List<String> {
    if (lines <= 1 || p.measureText(s) <= width) return listOf(fit(s, p, width))
    val out = ArrayList<String>()
    var rest = s.trim()
    while (rest.isNotEmpty() && out.size < lines - 1) {
        val n = p.breakText(rest, true, width, null)
        if (n >= rest.length) { out.add(rest); rest = ""; break }
        var cut = rest.lastIndexOf(' ', n)
        if (cut <= 0) cut = n
        out.add(rest.substring(0, cut).trim())
        rest = rest.substring(cut).trim()
    }
    if (rest.isNotEmpty()) out.add(fit(rest, p, width))
    return out
}

/** Draws [s] with its cap height's top at [top] rather than on a baseline, so blocks align by eye. */
fun Canvas.textTop(s: String, x: Float, top: Float, p: Paint) {
    drawText(s, x, top + capHeight(p), p)
}

/** The height of a capital in [p], measured from the face itself. */
fun capHeight(p: Paint): Float {
    val r = android.graphics.Rect()
    p.getTextBounds("H", 0, 1, r)
    return -r.top.toFloat()
}

/** The height a line of [p] takes, top of caps to the next line's top. */
fun lineHeight(p: Paint, leading: Float = 1.18f) = p.textSize * leading
