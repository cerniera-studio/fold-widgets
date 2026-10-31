package com.fergusonconsultancy.foldwidgets

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

/**
 * The finishes. All but Visconti and Paper come from Fold Taskbar and read what is behind them,
 * zone by zone, exactly as the bar does: dark over light, light over dark, with a trace of the
 * colour underneath. Vetro is new for the widgets: real glass, the wallpaper behind the widget
 * blurred and veiled.
 */
enum class Finish(val label: String, val fill: Int, val ink: Int, val edge: Int, val indicator: Int, val adaptive: Boolean = false) {
    /**
     * Vetro (31 Oct 2026): frosted glass, tinted by what it sits on. The wallpaper behind the
     * widget, heavily blurred, under a veil that takes the ground's own colour, deepened over dark
     * ground (ivory ink) and lifted over light (iron ink), so green wallpaper makes green glass.
     * A lit bevel all round, strongest at the upper left.
     */
    VETRO("Vetro frost (adaptive)", 0x66101412, 0xFFF4F1EA.toInt(), 0x33FFFFFF, 0xFFD3B27C.toInt(), adaptive = true),
    /**
     * Cristallo (31 Oct 2026): clear glass, a slab rather than a veil. The wallpaper behind it barely
     * softened, bent inwards at the rim as a curved edge bends light, with a faint colour fringe at
     * the very edge. A light veil only, so the wallpaper stays the subject.
     */
    CRISTALLO("Cristallo clear (adaptive)", 0x30101412, 0xFFF4F1EA.toInt(), 0x66FFFFFF, 0xFFD3B27C.toInt(), adaptive = true),
    /**
     * Smoked glass (31 Oct): always dark and sombre, translucent, thicker and darker towards its
     * edges as real smoked glass is where the light passes through more of it. Adapts its depth.
     */
    SMOKE("Smoked glass (adaptive)", 0xDB121614.toInt(), 0xFFE6E8E2.toInt(), 0x14FFFFFF, 0xFFD3B27C.toInt(), adaptive = true),
    MEMBRANE("Membrane (adaptive)", 0xC8141816.toInt(), 0xFFEDEAE2.toInt(), 0x1AFFFFFF, 0xFFD3B27C.toInt(), adaptive = true),
    ROSSO("Rosso lacquer (adaptive)", 0xF0861018.toInt(), 0xFFF6F1E6.toInt(), 0x40D9BB86, 0xFFD9BB86.toInt(), adaptive = true),
    AVORIO("Avorio (adaptive)", 0xF0F3ECDD.toInt(), 0xFF2A211A.toInt(), 0x22000000, 0xFF9E1B22.toInt(), adaptive = true),
    CARBONIO("Carbonio (adaptive)", 0xF216181A.toInt(), 0xFFE9E6DF.toInt(), 0x14FFFFFF, 0xFFD3B27C.toInt(), adaptive = true),
    CERAMICA("Ceramica (adaptive)", 0xF2264E8C.toInt(), 0xFFF4F1EA.toInt(), 0x40FFFFFF, 0xFFE9CF9E.toInt(), adaptive = true),
    VISCONTI("Visconti", 0xF01A3F31.toInt(), 0xFFEFE6D3.toInt(), 0x73D3B27C, 0xFFD3B27C.toInt()),
    PAPER("Paper", 0xF2F6F4EE.toInt(), 0xFF1D2620.toInt(), 0x14000000, 0xFF1F4A3A.toInt());

    val short get() = label.substringBefore(" (")
}

/** One face of a finish: the glass, its ink, its edge and its light. */
data class Face(val fill: Int, val ink: Int, val edge: Int, val glow: Int)

/**
 * How [finish] looks over a stretch of ground whose colour is [colour], when it should be the
 * light one ([light], over dark ground) or the dark one. The same definitions as Fold Taskbar's,
 * so a widget and the bar wearing the same finish match.
 */
fun faceFor(finish: Finish, colour: Int, light: Boolean): Face {
    fun mixWith(base: Int, tint: Float, shift: Int) = ((((base shr shift) and 255) * (1 - tint)) + (((colour shr shift) and 255) * tint)).toInt()
    fun tinted(base: Int, tint: Float, alpha: Int) = (alpha shl 24) or (mixWith(base, tint, 16) shl 16) or (mixWith(base, tint, 8) shl 8) or mixWith(base, tint, 0)
    return when (finish) {
        // Vetro's fill is the veil laid over the blurred wallpaper, not a solid
        // Over dark ground the glass deepens so ivory reads; over light it frosts so iron reads.
        // Either way it carries a good share of the ground's own colour.
        Finish.VETRO -> if (light) Face(tinted(0x121614, 0.40f, 0x6E), 0xFFF7F4EE.toInt(), 0x4DFFFFFF, 0xFFE2C48E.toInt())
            else Face(tinted(0xF6F2EA, 0.32f, 0x9E), 0xFF15191A.toInt(), 0x80FFFFFF.toInt(), 0xFF7A5A28.toInt())
        // Enough veil that type reads without a shadow, little enough that the wallpaper stays the subject
        Finish.CRISTALLO -> if (light) Face(tinted(0x0A0D0C, 0.22f, 0x54), 0xFFF7F4EE.toInt(), 0x66FFFFFF, 0xFFE2C48E.toInt())
            else Face(tinted(0xFFFFFF, 0.18f, 0x61), 0xFF111415.toInt(), 0x80FFFFFF.toInt(), 0xFF7A5A28.toInt())
        Finish.MEMBRANE -> if (light) Face(tinted(0xF2EEE6, 0.42f, 0xC4), 0xFF141A16.toInt(), 0x26000000, 0xFF7A5A28.toInt())
            else Face(tinted(0x0E1210, 0.45f, 0xB8), 0xFFF4F1EA.toInt(), 0x24FFFFFF, 0xFFE2C48E.toInt())
        Finish.ROSSO -> if (light) Face(tinted(0xB8141E, 0.06f, 0xF0), 0xFFF6F1E6.toInt(), 0x55F6D9A8, 0xFFE9CF9E.toInt())
            else Face(tinted(0x6E0A12, 0.05f, 0xF2), 0xFFF6F1E6.toInt(), 0x40D9BB86, 0xFFE9CF9E.toInt())
        Finish.AVORIO -> if (light) Face(tinted(0xF3ECDD, 0.06f, 0xF0), 0xFF2A211A.toInt(), 0x22000000, 0xFF9E1B22.toInt())
            else Face(tinted(0xCFC3AE, 0.08f, 0xF2), 0xFF231B14.toInt(), 0x33000000, 0xFF8E1820.toInt())
        Finish.CARBONIO -> if (light) Face(tinted(0x2C2F31, 0.06f, 0xF0), 0xFFF1EEE7.toInt(), 0x40FFFFFF, 0xFFE2C48E.toInt())
            else Face(tinted(0x16181A, 0.04f, 0xF2), 0xFFE9E6DF.toInt(), 0x14FFFFFF, 0xFFD3B27C.toInt())
        Finish.CERAMICA -> {
            val hsv = FloatArray(3).also { Color.colorToHSV(colour, it) }
            val grey = hsv[1] < 0.08f
            val h = if (grey) 215f else hsv[0]
            val s = if (grey) 0.62f else hsv[1].coerceIn(0.45f, 0.72f)
            val glaze = Color.HSVToColor(0xF2, floatArrayOf(h, if (light) s * 0.55f else s, if (light) 0.86f else 0.34f))
            if (light) Face(glaze, 0xFF14181A.toInt(), 0x66FFFFFF, 0xFF6E4A16.toInt())
            else Face(glaze, 0xFFF4F1EA.toInt(), 0x40FFFFFF, 0xFFE9CF9E.toInt())
        }
        // Smoke is always the dark one (31 Oct): sombre over everything. What adapts is its depth and
        // its trace of colour: deepest over dark ground, a shade denser over light so ivory still reads
        Finish.SMOKE -> if (light) Face(tinted(0x080A09, 0.14f, 0xCC), 0xFFEDEAE2.toInt(), 0x14FFFFFF, 0xFFD3B27C.toInt())
            else Face(tinted(0x0E1110, 0.07f, 0xE8), 0xFFEDEAE2.toInt(), 0x1FFFFFFF, 0xFFD3B27C.toInt())
        else -> Face(finish.fill, finish.ink, finish.edge, finish.indicator)
    }
}

/**
 * Whether a finish should show its "light" face, the one for dark ground, over ground of
 * luminance [lum]. Static images never flicker, so one threshold is enough.
 */
fun lightOver(lum: Float): Boolean = lum < 0.45f

/** Relative luminance of a colour, 0 to 1. */
fun lum(c: Int): Float = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f

/** Every setting the widgets keep. */
object Prefs {
    private const val FILE = "fold_widgets"
    fun of(c: Context): SharedPreferences = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun defaultFinish(c: Context): Finish =
        runCatching { Finish.valueOf(of(c).getString("finish", Finish.VETRO.name)!!) }.getOrDefault(Finish.VETRO)
    fun setDefaultFinish(c: Context, f: Finish) = of(c).edit().putString("finish", f.name).apply()

    /** A widget's own finish, or the default if it has none. */
    fun finish(c: Context, id: Int): Finish = of(c).getString("finish_$id", null)
        ?.let { runCatching { Finish.valueOf(it) }.getOrNull() } ?: defaultFinish(c)
    fun setFinish(c: Context, id: Int, f: Finish?) = of(c).edit().apply {
        if (f == null) remove("finish_$id") else putString("finish_$id", f.name)
    }.apply()
    fun hasOwnFinish(c: Context, id: Int) = of(c).contains("finish_$id")

    fun forget(c: Context, id: Int) = of(c).edit().remove("finish_$id").remove("seat_$id").apply()

    /** Corner radius in dp. 24 matches Android's own widget corners. */
    fun radius(c: Context): Int = of(c).getInt("radius", 24)
    fun setRadius(c: Context, dp: Int) = of(c).edit().putInt("radius", dp).apply()
}
