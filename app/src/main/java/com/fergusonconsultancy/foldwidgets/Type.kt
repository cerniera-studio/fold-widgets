package com.fergusonconsultancy.foldwidgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils

import com.cerniera.kit.Kit

/**
 * The family's palette and type, from the Kit (Cerniera's shared design system, 12 Nov 2026):
 * smoked green-black, ivory ink, champagne for what is chosen. Italiana names things; Jost does
 * everything else. Fold Widgets always draws in the Visconti (night) tokens: it never calls
 * Kit.follow, because widgets are drawn in the background and their finishes set their own light.
 * IRON and BRONZE are the pack's own, for its finishes.
 */
object Palette {
    val GROUND get() = Kit.GROUND
    val SURFACE get() = Kit.SURFACE
    val RAISED get() = Kit.RAISED
    val HAIRLINE get() = Kit.HAIRLINE
    val IVORY get() = Kit.INK
    val MUTED get() = Kit.MUTED
    val CHAMPAGNE get() = Kit.CHAMPAGNE
    val ROSSO get() = Kit.ROSSO
    val GOOD get() = Kit.GOOD
    val WARN get() = Kit.WARN
    const val IRON = 0xFF16191A.toInt()
    const val BRONZE = 0xFF7A5A28.toInt()
}

/** Italiana and Jost, from the Kit. */
object Type {
    fun display(c: Context): Typeface = Kit.display(c)
    fun sans(c: Context, weight: Int = 400): Typeface = Kit.sans(c, weight)
}

/** A paint for text at [size] px. */
fun Context.ink(size: Float, colour: Int, weight: Int = 400, display: Boolean = false, tracking: Float = 0f) =
    TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = if (display) Type.display(this@ink) else Type.sans(this@ink, weight)
        textSize = size
        color = colour
        letterSpacing = tracking
        // No shadows or glows on type: every letter stays as crisp as the system's own (31 Oct)
        isLinearText = false
    }

/**
 * The pack's type, defined once (1 Nov: the same roles had drifted into five label styles, and
 * Italiana had crept onto words). Two faces, each with one job:
 * - Italiana: the one large figure a widget is about (the date, the unread count, the temperature).
 *   Never words: its hairlines only hold at figure sizes.
 * - Jost: every word. Heading 15 at 600; primary line 14.5 at 600 (unread) or 500 (read);
 *   body 14 at 450; meta 12.5 at 450, muted; label 11 at 600, capitals tracked 0.14.
 * Widgets that scale (music, month) multiply these by their scale; lists keep them fixed.
 */
fun Context.heading(colour: Int, k: Float = 1f, size: Float = 15f) = ink(size * k, colour, 600)

/** The quiet title of an empty or setup state: a heading, a step larger. */
fun Context.statement(colour: Int, k: Float = 1f) = ink(17f * k, colour, 600)

/** Secondary text (times, artist, a place): 12.5 at 500, set in the muted ink. */
fun Context.meta(colour: Int, k: Float = 1f, size: Float = 12.5f) = ink(size * k, colour, 500)

/** Small tracked capitals: day headings, section dividers, the player's name, detail labels. */
fun Context.label(colour: Int, k: Float = 1f, size: Float = 11f) = ink(size * k, colour, 600, tracking = 0.14f)

/** [s] cut to [width] with an ellipsis. */
fun fit(s: CharSequence, p: TextPaint, width: Float): String =
    TextUtils.ellipsize(s, p, width.coerceAtLeast(1f), TextUtils.TruncateAt.END).toString()

/**
 * [s] broken into at most [lines] lines of [width], only ever between words. If the words run past
 * the last line, it ends on a whole word and an ellipsis. A single word wider than the line is the
 * only thing ever cut, and then with an ellipsis, never split across lines.
 */
fun wrap(s: String, p: TextPaint, width: Float, lines: Int): List<String> {
    val words = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return listOf("")
    val out = ArrayList<String>()
    var i = 0
    while (i < words.size && out.size < lines) {
        var line = words[i]
        if (p.measureText(line) > width) { out += fit(line, p, width); i++; continue }
        i++
        while (i < words.size && p.measureText("$line ${words[i]}") <= width) { line += " " + words[i]; i++ }
        out += line
    }
    if (i < words.size && out.isNotEmpty()) {
        // More words than lines: end the last line on a whole word with an ellipsis
        val last = out.removeAt(out.size - 1).split(" ").toMutableList()
        while (last.size > 1 && p.measureText(last.joinToString(" ") + "…") > width) last.removeAt(last.size - 1)
        out += fit(last.joinToString(" ") + "…", p, width)
    }
    return out
}

/** Whether every word of [s] fits [width] and the whole fits [lines] lines without an ellipsis. */
fun fitsWhole(s: String, p: TextPaint, width: Float, lines: Int): Boolean {
    val words = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.any { p.measureText(it) > width }) return false
    var count = 1; var line = ""
    for (w in words) {
        val next = if (line.isEmpty()) w else "$line $w"
        if (p.measureText(next) <= width) line = next else { count++; line = w }
    }
    return count <= lines
}

/**
 * A title set as large as it can be, up to [lines] lines, never breaking a word: each size in
 * [sizes] is tried from largest; the first at which the whole title fits wins. If none does, the
 * smallest is used and the title ends on a whole word and an ellipsis.
 */
fun Context.title(s: String, colour: Int, width: Float, lines: Int, sizes: List<Float>, weight: Int = 400, display: Boolean = false): Pair<TextPaint, List<String>> {
    for (size in sizes) {
        val p = ink(size, colour, weight, display)
        if (fitsWhole(s, p, width, lines)) return p to wrap(s, p, width, lines)
    }
    val p = ink(sizes.last(), colour, weight, display)
    return p to wrap(s, p, width, lines)
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
