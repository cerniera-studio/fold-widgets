package com.fergusonconsultancy.foldwidgets

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader

/**
 * The finish of one widget, painted, and the tones its marks should take. Each zone of the ground
 * gets the finish's face for that zone; the zones are drawn as a tiny grid and stretched with
 * smoothing, so they blend into one continuous gradient, as the taskbar's do.
 */
/**
 * Everything is measured in dp: the widget's canvas is scaled once, so [w], [h], [radius] and the
 * [hairline] (one physical pixel or so, in dp) are all dp.
 */
class Surface(val finish: Finish, val ground: Ground, val w: Float, val h: Float, val radius: Float, val hairline: Float) {

    private val faces: Array<Face> = Array(Ground.COLS * Ground.ROWS) { z ->
        legibleFace(finish, ground.zones[z], ground.bright[z], ground.dark[z], throughBlur = finish == Finish.VETRO && ground.seated && ground.frost != null)
    }

    /** The face under a point, as fractions of the widget's width and height. */
    fun faceAt(fx: Float, fy: Float): Face {
        val x = (fx * Ground.COLS).toInt().coerceIn(0, Ground.COLS - 1)
        val y = (fy * Ground.ROWS).toInt().coerceIn(0, Ground.ROWS - 1)
        return faces[y * Ground.COLS + x]
    }

    /** Ink for a mark whose centre is at ([x], [y]) px. */
    fun ink(x: Float, y: Float) = faceAt(x / w, y / h).ink
    /** Secondary ink: the same tone, quieter. */
    fun muted(x: Float, y: Float) = faceAt(x / w, y / h).let { (it.ink and 0x00FFFFFF) or (it.mutedAlpha shl 24) }
    /** Faint ink, for tracks and wells. */
    fun faint(x: Float, y: Float) = (ink(x, y) and 0x00FFFFFF) or 0x24000000
    /** The finish's light: champagne over dark faces, bronze over light ones. */
    fun accent(x: Float, y: Float) = faceAt(x / w, y / h).glow
    /** Whether the ink at a point is dark, i.e. the face there is light. */
    fun darkInk(x: Float, y: Float) = lum(ink(x, y)) < 0.5f

    val shape: Path = Shapes.squircle(RectF(0f, 0f, w, h), radius)

    /** The same glass measured in units [k] times larger: for content drawn on a canvas scaled by [k]. */
    fun scaled(k: Float) = if (k == 1f) this else Surface(finish, ground, w / k, h / k, radius / k, hairline / k)

    val glassy get() = finish == Finish.VETRO || finish == Finish.CRISTALLO

    fun paint(c: Canvas) {
        c.save()
        c.clipPath(shape)
        when (finish) {
            Finish.VETRO -> frost(c)
            Finish.CRISTALLO -> crystal(c)
            Finish.SMOKE -> { zones(c, Face::fill); edges(c) }
            else -> zones(c, Face::fill)
        }
        c.restore()
        if (glassy) bevel(c) else rim(c)
    }

    /** The zones' [part] as a smooth field over the whole widget. */
    private fun zones(c: Canvas, part: (Face) -> Int) {
        val grid = Bitmap.createBitmap(Ground.COLS, Ground.ROWS, Bitmap.Config.ARGB_8888)
        for (z in faces.indices) grid.setPixel(z % Ground.COLS, z / Ground.COLS, part(faces[z]))
        c.drawBitmap(stretch(grid), null, RectF(0f, 0f, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
    }

    /**
     * Scaling a 4 x 4 grid straight up smears its outer zones into hard bands; scaling it in two
     * steps through a middle size gives an even, soft field.
     */
    private fun stretch(grid: Bitmap): Bitmap {
        val mid = Bitmap.createScaledBitmap(grid, Ground.COLS * 4, Ground.ROWS * 4, true)
        return Bitmap.createScaledBitmap(mid, Ground.COLS * 16, Ground.ROWS * 16, true)
    }

    /**
     * Vetro, frosted. Seated, the wallpaper behind the widget heavily blurred and a touch richer,
     * under a veil tinted by the ground itself (green glass over a green wallpaper), and a soft
     * sheen falling from the top where the light enters. Not yet seated, the veil alone, which is
     * translucent: clear glass rather than frosted.
     */
    private fun frost(c: Canvas) {
        ground.frost?.takeIf { ground.seated }?.let { pic ->
            val rich = ColorMatrix().apply { setSaturation(1.2f) }
            c.drawBitmap(pic, null, RectF(0f, 0f, w, h), Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(rich) })
        }
        zones(c, Face::fill)
        sheen(c, 0x1F)
    }

    /**
     * Cristallo, clear. The wallpaper barely softened, seen through a slab whose rim bends in what
     * lies beyond the edge, under a light veil. Not yet seated it has nothing to bend, so it is the
     * veil over the real wallpaper.
     */
    private fun crystal(c: Canvas) {
        val clear = ground.clear?.takeIf { ground.seated }
        if (clear != null) {
            val innerW = (clear.width - 2 * ground.margin).coerceAtLeast(1)
            val lens = Lens.refract(clear, ground.margin, radius * 1.3f * innerW / w)
            c.drawBitmap(lens, null, RectF(0f, 0f, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        zones(c, Face::fill)
        sheen(c, 0x14)
    }

    /**
     * Smoked glass is darker towards its edges, where light crosses more of it: a soft fall of
     * shade from the middle outwards. The force is the glass's thickness.
     */
    private fun edges(c: Canvas) {
        val r = maxOf(w, h) * 0.75f
        c.drawRect(0f, 0f, w, h, Paint().apply {
            shader = android.graphics.RadialGradient(w / 2f, h * 0.45f, r, intArrayOf(0x00000000, 0x00000000, 0x47000000),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        })
    }

    /** Light entering the top of the slab, fading by half way down. */
    private fun sheen(c: Canvas, alpha: Int) {
        val top = Daylight.light(alpha)
        c.drawRect(0f, 0f, w, h, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h * 0.5f, top, top and 0x00FFFFFF, Shader.TileMode.CLAMP)
        })
    }

    /**
     * The glass's edge, lit from the upper left as every icon in the family is. One highlight: a
     * warm line along the rim, strongest at the upper-left corner and gone by the far corner, with
     * a soft glow inside it where the bevel catches the light. Its colour keeps the time of day.
     */
    private fun bevel(c: Canvas) {
        val st = maxOf(hairline * 1.4f, 0.9f)
        val inset = st / 2f
        val rim = Shapes.squircle(RectF(inset, inset, w - inset, h - inset), radius - inset)
        val diag = { a: Int, b: Int ->
            LinearGradient(0f, 0f, w, h, intArrayOf(Daylight.light(a), Daylight.light(b), Daylight.light(0), Daylight.light(0)),
                floatArrayOf(0f, 0.34f, 0.7f, 1f), Shader.TileMode.CLAMP)
        }
        // The glow inside the bevel: a broad, blurred stroke, kept inside the glass
        c.save(); c.clipPath(shape)
        c.drawPath(rim, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = minOf(w, h) * 0.05f
            maskFilter = android.graphics.BlurMaskFilter(minOf(w, h) * 0.035f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            shader = diag(0x4D, 0x0F)
        })
        c.restore()
        // The rim itself: lit at the upper left, and where the light has gone, the faintest edge
        c.drawPath(rim, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = st
            shader = diag(0xE6, 0x40)
        })
        c.drawPath(rim, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = hairline; color = 0x1AFFFFFF
        })
    }

    /**
     * The edge of the solid finishes: a hairline in each zone's edge tone.
     */
    private fun rim(c: Canvas) {
        val stroke = hairline
        val inset = stroke / 2f
        val r = RectF(inset, inset, w - inset, h - inset)
        val grid = Bitmap.createBitmap(Ground.COLS, Ground.ROWS, Bitmap.Config.ARGB_8888)
        for (z in faces.indices) grid.setPixel(z % Ground.COLS, z / Ground.COLS, faces[z].edge)
        val field = stretch(grid)
        val shader = BitmapShader(field, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(w / field.width, h / field.height) })
        }
        c.drawPath(Shapes.squircle(r, radius - inset), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; this.shader = shader
        })
    }
}
