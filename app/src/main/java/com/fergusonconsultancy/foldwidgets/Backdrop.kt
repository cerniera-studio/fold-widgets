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

    /** Where a widget sits: its bounds on a screen of [screenW] x [screenH] px. */
    data class Seat(val left: Float, val top: Float, val width: Float, val height: Float, val screenW: Int, val screenH: Int) {
        /** The cover screen is tall and narrow; the main screen is close to square. */
        val onCover get() = screenH > screenW * 1.6f || screenW > screenH * 1.6f
        fun encode() = listOf(left, top, width, height, screenW, screenH).joinToString(",")
        companion object {
            fun decode(s: String?): Seat? = runCatching {
                val p = s!!.split(",")
                Seat(p[0].toFloat(), p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), p[4].toInt(), p[5].toInt())
            }.getOrNull()
        }
    }

    fun seat(c: Context, id: Int): Seat? = Seat.decode(Prefs.of(c).getString("seat_$id", null))

    /**
     * Records where widget [id] sits, from a tap's [intent]: its source bounds are the tapped area's
     * rectangle on screen, and [hit] is that area inside a widget of [sizeDp]. Returns true if the
     * seat moved, so the caller can redraw the widget against its new ground.
     */
    fun record(c: Context, intent: Intent, id: Int, hit: RectF?, sizeDp: android.util.SizeF?): Boolean {
        val b = intent.sourceBounds ?: return false
        if (hit == null || sizeDp == null || hit.width() <= 0f || b.width() <= 0) return false
        // px per dp as the launcher actually drew it, measured rather than assumed
        val scale = b.width() / hit.width()
        val screen = screenSize(c)
        val seat = Seat(b.left - hit.left * scale, b.top - hit.top * scale, sizeDp.width * scale, sizeDp.height * scale, screen.x, screen.y)
        val old = seat(c, id)
        val moved = old == null || old.screenW != seat.screenW || old.screenH != seat.screenH ||
            Math.abs(old.left - seat.left) > 3f || Math.abs(old.top - seat.top) > 3f ||
            Math.abs(old.width - seat.width) > 3f || Math.abs(old.height - seat.height) > 3f
        if (moved) Prefs.of(c).edit().putString("seat_$id", seat.encode()).apply()
        return moved
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
        if (seat != null && wall != null) slice(wall, seat)?.let { return Ground.of(it, true) }
        return Ground.flat(wallpaperColour(c))
    }

    /**
     * The part of [wall] behind [seat]. Launchers scale the wallpaper to fill the screen and centre
     * it, so the screen sees the middle of the image at that scale.
     */
    private fun slice(wall: Bitmap, seat: Seat): Bitmap? {
        val sw = seat.screenW.toFloat(); val sh = seat.screenH.toFloat()
        val scale = maxOf(sw / wall.width, sh / wall.height)
        val ox = (wall.width * scale - sw) / 2f
        val oy = (wall.height * scale - sh) / 2f
        val src = Rect(
            ((seat.left + ox) / scale).toInt(), ((seat.top + oy) / scale).toInt(),
            ((seat.left + seat.width + ox) / scale).toInt(), ((seat.top + seat.height + oy) / scale).toInt())
        if (!src.intersect(0, 0, wall.width, wall.height) || src.width() < 2 || src.height() < 2) return null
        // Small on purpose: the glass is a blur, and the zones are averages
        val w = 96; val h = (96f * seat.height / seat.width).toInt().coerceIn(8, 256)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(wall, src, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

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
 * The ground behind a widget: a small picture of it (blurred for glass) and its colour in a grid
 * of zones, [COLS] across and [ROWS] down.
 */
class Ground private constructor(val picture: Bitmap?, val zones: IntArray, val seated: Boolean) {
    /** The zone colour nearest a point given as fractions of the widget's width and height. */
    fun at(fx: Float, fy: Float): Int {
        val x = (fx * COLS).toInt().coerceIn(0, COLS - 1)
        val y = (fy * ROWS).toInt().coerceIn(0, ROWS - 1)
        return zones[y * COLS + x]
    }

    companion object {
        const val COLS = 4
        const val ROWS = 4

        fun flat(colour: Int) = Ground(null, IntArray(COLS * ROWS) { colour }, false)

        /** From a picture of the ground: zones are the average of each cell; the picture is blurred. */
        fun of(picture: Bitmap, seated: Boolean): Ground {
            val w = picture.width; val h = picture.height
            val px = IntArray(w * h).also { picture.getPixels(it, 0, w, 0, 0, w, h) }
            val zones = IntArray(COLS * ROWS) { z ->
                val cx = z % COLS; val cy = z / COLS
                val x0 = cx * w / COLS; val x1 = ((cx + 1) * w / COLS).coerceAtLeast(x0 + 1)
                val y0 = cy * h / ROWS; val y1 = ((cy + 1) * h / ROWS).coerceAtLeast(y0 + 1)
                var r = 0L; var g = 0L; var b = 0L; var n = 0
                for (y in y0 until minOf(y1, h)) for (x in x0 until minOf(x1, w)) {
                    val p = px[y * w + x]; r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255; n++
                }
                if (n == 0) 0xFF2A2F2C.toInt() else Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
            }
            return Ground(Blur.frost(picture), zones, seated)
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
