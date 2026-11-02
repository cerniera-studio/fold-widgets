package com.fergusonconsultancy.foldwidgets

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Environment
import android.view.Display

/**
 * What is behind a widget. A launcher draws widgets over the wallpaper and tells them nothing about
 * where they sit, so the widgets learn it from your taps: every tap carries the bounds of the thing
 * tapped on screen, and the widget knows where that thing sits inside itself. That is its seat. With
 * a seat and the wallpaper image, the widget knows exactly which part of the wallpaper is behind it,
 * zone by zone, like the taskbar reading the screen beyond its edges.
 *
 * Until a widget has been touched once it reads the wallpaper's overall colours, which Android
 * gives to any app, and wears one tone.
 */
object Backdrop {

    /**
     * Where a widget sits: its bounds on a screen of [screenW] x [screenH] px. [exact] when the
     * bounds came from the widget's full-size touch area, so the size is the size the launcher
     * really gives it, to the pixel, not what it reports.
     */
    data class Seat(val left: Float, val top: Float, val width: Float, val height: Float, val screenW: Int, val screenH: Int,
                    val exact: Boolean = false, val repW: Float = 0f, val repH: Float = 0f) {
        /** The cover screen is tall and narrow; the main screen is close to square. */
        val onCover get() = screenH > screenW * 1.6f || screenW > screenH * 1.6f
        fun encode() = listOf(left, top, width, height, screenW, screenH, if (exact) 1 else 0, repW, repH).joinToString(",")
        companion object {
            fun decode(s: String?): Seat? = runCatching {
                val p = s!!.split(",")
                Seat(p[0].toFloat(), p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), p[4].toInt(), p[5].toInt(), p.getOrNull(6) == "1",
                    p.getOrNull(7)?.toFloat() ?: 0f, p.getOrNull(8)?.toFloat() ?: 0f)
            }.getOrNull()
        }
    }

    fun seat(c: Context, id: Int): Seat? = Seat.decode(Prefs.of(c).getString("seat_$id", null))

    /**
     * Records where widget [id] sits, from a tap's [intent]. Its source bounds are the tapped area's
     * rectangle on screen. When the area is the widget's full-size one ([root]), those bounds are
     * the widget itself, exactly. Otherwise [hit] says where the area sits inside the widget, in dp,
     * which places the widget but cannot measure it. Returns true if the seat changed, so the
     * caller redraws the widget at its true size and against its new ground.
     */
    fun record(c: Context, intent: Intent, id: Int, hit: RectF?, sizeDp: android.util.SizeF?, root: Boolean, reported: android.util.SizeF): Boolean {
        val b = intent.sourceBounds ?: return false
        if (b.width() <= 0) return false
        val screen = screenSize(c)
        val old = seat(c, id)
        val seat = if (root) Seat(b.left.toFloat(), b.top.toFloat(), b.width().toFloat(), b.height().toFloat(), screen.x, screen.y, true, reported.width, reported.height) else {
            if (hit == null || sizeDp == null || hit.width() <= 0f) return false
            val d = density(c)
            val left = b.left - hit.left * d; val top = b.top - hit.top * d
            if (old != null && old.exact && old.screenW == screen.x && old.screenH == screen.y) old.copy(left = left, top = top)
            else Seat(left, top, sizeDp.width * d, sizeDp.height * d, screen.x, screen.y)
        }
        val moved = old == null || old.screenW != seat.screenW || old.screenH != seat.screenH || old.exact != seat.exact ||
            Math.abs(old.left - seat.left) > 2f || Math.abs(old.top - seat.top) > 2f ||
            Math.abs(old.width - seat.width) > 1f || Math.abs(old.height - seat.height) > 1f
        if (moved) Prefs.of(c).edit().putString("seat_$id", seat.encode()).apply()
        return moved
    }

    /**
     * The density of the screen in use now. On a Fold the app's own resources can carry the other
     * screen's density, if the process started while the phone was open; drawing at that density
     * makes a picture the launcher then has to stretch, which is blur.
     */
    fun density(c: Context): Float {
        val d = c.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            ?: return c.resources.displayMetrics.density
        return runCatching { c.createDisplayContext(d).resources.displayMetrics.density }.getOrDefault(c.resources.displayMetrics.density)
    }

    /** The real size of the screen in use now: the cover screen when folded, the main one when open. */
    @Suppress("DEPRECATION")
    fun screenSize(c: Context): Point {
        val p = Point()
        val d = c.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        d?.getRealSize(p)
        if (p.x <= 0) { p.x = c.resources.displayMetrics.widthPixels; p.y = c.resources.displayMetrics.heightPixels }
        return p
    }

    // ---- The wallpaper ------------------------------------------------------------------------

    private var cached: Bitmap? = null
    private var cachedKey: String? = null

    /** Forgets the wallpaper, after it changes. */
    fun invalidate() = synchronized(this) { cached = null; cachedKey = null }

    /** Whether the wallpaper image itself can be read: Android 13 and later ask for all-files access. */
    fun canReadWallpaper(): Boolean = Environment.isExternalStorageManager()

    /**
     * The image behind the widgets on the screen described by [seat], at a modest size (the glass
     * blurs it anyway). An image chosen for that screen in settings comes first, for phones that
     * keep a separate cover-screen wallpaper the system does not hand out.
     */
    fun wallpaper(c: Context, seat: Seat?): Bitmap? = synchronized(this) {
        val chosen = Prefs.of(c).getString(if (seat?.onCover == true) "image_cover" else "image_main", null)
        val key = chosen ?: "system"
        if (cachedKey == key) cached?.let { return it }
        val bmp = if (chosen != null) runCatching { decode(c, Uri.parse(chosen)) }.getOrNull() else systemWallpaper(c)
        cached = bmp; cachedKey = key
        bmp
    }

    private fun systemWallpaper(c: Context): Bitmap? = runCatching {
        if (!canReadWallpaper()) return null
        val d = WallpaperManager.getInstance(c).drawable ?: return null
        val src = (d as? BitmapDrawable)?.bitmap
        if (src != null) shrink(src, 1080) else {
            val w = d.intrinsicWidth.coerceIn(1, 1080); val h = (d.intrinsicHeight * w / d.intrinsicWidth.coerceAtLeast(1)).coerceAtLeast(1)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { d.setBounds(0, 0, w, h); d.draw(Canvas(it)) }
        }
    }.getOrNull()

    private fun decode(c: Context, uri: Uri): Bitmap {
        val src = ImageDecoder.createSource(c.contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val s = 1080f / maxOf(info.size.width, info.size.height)
            if (s < 1f) dec.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
        }
    }

    private fun shrink(b: Bitmap, longest: Int): Bitmap {
        val s = longest.toFloat() / maxOf(b.width, b.height)
        if (s >= 1f) return b.copy(Bitmap.Config.ARGB_8888, false)
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
    }

    // ---- The ground behind one widget -----------------------------------------------------------

    /**
     * The ground behind widget [id]: the slice of wallpaper behind its seat when both are known,
     * otherwise the wallpaper's overall colours.
     */
    fun ground(c: Context, id: Int): Ground {
        val seat = seat(c, id)
        val wall = wallpaper(c, seat)
        // Live mode knows where the launcher has the wallpaper on this widget's page, and its tint
        val live = Live.viewOf(c, id)?.takeIf { seat != null && it.screenW == seat.screenW && it.screenH == seat.screenH && LiveService.enabled(c) }
        if (seat != null && wall != null) slice(wall, seat, live)?.let { return it }
        return Ground.flat(wallpaperColour(c))
    }

    /**
     * The part of [wall] behind [seat], with a margin of what lies just beyond each edge: clear
     * glass bends that in at its rim. Launchers scale the wallpaper to fill the screen and centre
     * it, so the screen sees the middle of the image at that scale. Beyond the wallpaper's own
     * edges the outermost pixels repeat.
     */
    private fun slice(wall: Bitmap, seat: Seat, live: Live.View? = null): Ground? {
        if (seat.width < 2f || seat.height < 2f) return null
        val sw = seat.screenW.toFloat(); val sh = seat.screenH.toFloat()
        val scale = maxOf(sw / wall.width, sh / wall.height)
        // Where the launcher really has it, when live mode has seen it; else centred, the usual guess
        val ox = live?.ox ?: ((wall.width * scale - sw) / 2f)
        val oy = live?.oy ?: ((wall.height * scale - sh) / 2f)
        val margin = 0.15f * minOf(seat.width, seat.height)     // screen px beyond each edge
        val s = INNER_WIDTH / seat.width                         // output px per screen px
        val outW = ((seat.width + 2 * margin) * s).toInt().coerceAtLeast(8)
        val outH = ((seat.height + 2 * margin) * s).toInt().coerceAtLeast(8)
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val m = android.graphics.Matrix().apply {
            setScale(scale * s, scale * s)
            postTranslate((-ox - (seat.left - margin)) * s, (-oy - (seat.top - margin)) * s)
        }
        Canvas(out).drawRect(0f, 0f, outW.toFloat(), outH.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG).apply {
            shader = android.graphics.BitmapShader(wall, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
                .apply { setLocalMatrix(m) }
            // The launcher's own tint over the wallpaper (One UI dims it in dark mode), as measured
            live?.gain?.let { g -> colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setScale(g[0], g[1], g[2], 1f) }) }
        })
        return Ground.of(out, (margin * s).toInt(), true)
    }

    /** Width in px of the wallpaper slice behind a widget: sharp enough for clear glass. */
    private const val INNER_WIDTH = 360f

    /** The wallpaper's main colour and whether it is light, which Android gives without asking. */
    private fun wallpaperColour(c: Context): Int {
        val wc = runCatching { WallpaperManager.getInstance(c).getWallpaperColors(WallpaperManager.FLAG_SYSTEM) }.getOrNull()
            ?: return 0xFF2A2F2C.toInt()
        val primary = wc.primaryColor.toArgb()
        val darkText = (wc.colorHints and android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
        // Keep the hue, but trust the hint about lightness: it is measured over the whole image
        val hsv = FloatArray(3).also { Color.colorToHSV(primary, it) }
        if (darkText && hsv[2] < 0.7f) hsv[2] = 0.82f
        if (!darkText && lum(primary) > 0.5f) hsv[2] = 0.3f
        return Color.HSVToColor(hsv)
    }
}

/**
 * The ground behind a widget, [COLS] zones across and [ROWS] down, with two pictures of it:
 * [frost], heavily blurred and exactly the widget's area, for Vetro; and [clear], barely softened
 * and reaching [margin] px beyond every edge, for Cristallo's lens.
 */
/**
 * [bright] and [dark] (1 Nov): each zone's lightest and darkest tenth, averaged. A zone's mean hides
 * the bright lines of a patterned wallpaper, and those lines show through translucent finishes
 * right behind the type, so legibility is judged against both, never the mean alone.
 */
class Ground private constructor(val frost: Bitmap?, val clear: Bitmap?, val margin: Int, val zones: IntArray, val seated: Boolean,
                                 val bright: IntArray = zones, val dark: IntArray = zones) {
    /** The zone colour nearest a point given as fractions of the widget's width and height. */
    fun at(fx: Float, fy: Float): Int {
        val x = (fx * COLS).toInt().coerceIn(0, COLS - 1)
        val y = (fy * ROWS).toInt().coerceIn(0, ROWS - 1)
        return zones[y * COLS + x]
    }

    companion object {
        const val COLS = 4
        const val ROWS = 4

        fun flat(colour: Int) = Ground(null, null, 0, IntArray(COLS * ROWS) { colour }, false)

        /** From a picture of the ground reaching [margin] px beyond the widget on every side. */
        fun of(picture: Bitmap, margin: Int = 0, seated: Boolean = true): Ground {
            val inner = Bitmap.createBitmap(picture, margin, margin,
                (picture.width - 2 * margin).coerceAtLeast(1), (picture.height - 2 * margin).coerceAtLeast(1))
            val w = inner.width; val h = inner.height
            val px = IntArray(w * h).also { inner.getPixels(it, 0, w, 0, 0, w, h) }
            val zones = IntArray(COLS * ROWS)
            val bright = IntArray(COLS * ROWS)
            val dark = IntArray(COLS * ROWS)
            // Per zone: a histogram of luminance carrying each bin's colour, so the mean, the
            // lightest tenth and the darkest tenth come from one pass
            val count = IntArray(256); val rs = LongArray(256); val gs = LongArray(256); val bs = LongArray(256)
            for (z in 0 until COLS * ROWS) {
                java.util.Arrays.fill(count, 0); java.util.Arrays.fill(rs, 0); java.util.Arrays.fill(gs, 0); java.util.Arrays.fill(bs, 0)
                val cx = z % COLS; val cy = z / COLS
                val x0 = cx * w / COLS; val x1 = ((cx + 1) * w / COLS).coerceAtLeast(x0 + 1)
                val y0 = cy * h / ROWS; val y1 = ((cy + 1) * h / ROWS).coerceAtLeast(y0 + 1)
                var r = 0L; var g = 0L; var b = 0L; var n = 0
                for (y in y0 until minOf(y1, h)) for (x in x0 until minOf(x1, w)) {
                    val p = px[y * w + x]; val pr = (p shr 16) and 255; val pg = (p shr 8) and 255; val pb = p and 255
                    r += pr; g += pg; b += pb; n++
                    val l = ((pr * 54 + pg * 183 + pb * 19) shr 8).coerceIn(0, 255)
                    count[l]++; rs[l] += pr.toLong(); gs[l] += pg.toLong(); bs[l] += pb.toLong()
                }
                if (n == 0) { zones[z] = 0xFF2A2F2C.toInt(); bright[z] = zones[z]; dark[z] = zones[z]; continue }
                zones[z] = Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
                fun tenth(range: IntProgression): Int {
                    val want = maxOf(1, n / 10); var k = 0; var tr = 0L; var tg = 0L; var tb = 0L
                    for (l in range) { if (k >= want) break; k += count[l]; tr += rs[l]; tg += gs[l]; tb += bs[l] }
                    return if (k == 0) zones[z] else Color.rgb((tr / k).toInt(), (tg / k).toInt(), (tb / k).toInt())
                }
                bright[z] = tenth(255 downTo 0)
                dark[z] = tenth(0..255)
            }
            // Frost: blurred small with its margin, so the blur has real ground at the edges, then cut
            val f = 96f / w
            val small = Bitmap.createScaledBitmap(picture, (picture.width * f).toInt().coerceAtLeast(4), (picture.height * f).toInt().coerceAtLeast(4), true)
            val soft = Blur.frost(small, 5)
            val fm = (margin * f).toInt()
            val frost = Bitmap.createBitmap(soft, fm.coerceAtMost(soft.width - 2), fm.coerceAtMost(soft.height - 2),
                (soft.width - 2 * fm).coerceAtLeast(2), (soft.height - 2 * fm).coerceAtLeast(2))
            return Ground(frost, Blur.frost(picture, 2), margin, zones, seated, bright, dark)
        }
    }
}

/** A box blur in three passes, which converges on a soft Gaussian. Run on small pictures only. */
object Blur {
    fun frost(src: Bitmap, radius: Int = 5): Bitmap {
        val w = src.width; val h = src.height
        var a = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        var b = IntArray(w * h)
        repeat(3) {
            pass(a, b, w, h, radius, true); pass(b, a, w, h, radius, false)
        }
        return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
    }

    /** One pass along rows ([horizontal]) or columns, edges clamped. */
    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val div = 2 * r + 1
        for (line in 0 until lines) {
            fun idx(i: Int): Int { val k = i.coerceIn(0, len - 1); return if (horizontal) line * w + k else k * w + line }
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) { val p = src[idx(i)]; sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255 }
            for (i in 0 until len) {
                dst[idx(i)] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val out = src[idx(i - r)]; val inn = src[idx(i + r + 1)]
                sr += ((inn shr 16) and 255) - ((out shr 16) and 255)
                sg += ((inn shr 8) and 255) - ((out shr 8) and 255)
                sb += (inn and 255) - (out and 255)
            }
        }
    }
}
