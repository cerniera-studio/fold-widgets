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
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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
        val colour = ground.zones[z]
        faceFor(finish, colour, lightOver(lum(colour)))
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
    fun muted(x: Float, y: Float) = (ink(x, y) and 0x00FFFFFF) or 0x9E000000.toInt()
    /** Faint ink, for tracks and wells. */
    fun faint(x: Float, y: Float) = (ink(x, y) and 0x00FFFFFF) or 0x24000000
    /** The finish's light: champagne over dark faces, bronze over light ones. */
    fun accent(x: Float, y: Float) = faceAt(x / w, y / h).glow
    /** Whether the ink at a point is dark, i.e. the face there is light. */
    fun darkInk(x: Float, y: Float) = lum(ink(x, y)) < 0.5f

    val shape: Path = Path().apply { addRoundRect(RectF(0f, 0f, w, h), radius, radius, Path.Direction.CW) }

    fun paint(c: Canvas) {
        c.save()
        c.clipPath(shape)
        if (finish == Finish.VETRO) glass(c) else zones(c, Face::fill)
        c.restore()
        rim(c)
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
     * Vetro. Seated, the blurred wallpaper behind the widget is drawn a touch richer, then veiled.
     * Not yet seated, there is nothing to draw but the veil, which is translucent, so the real
     * wallpaper shows through it unblurred: still glass, only clear rather than frosted.
     */
    private fun glass(c: Canvas) {
        ground.picture?.takeIf { ground.seated }?.let { pic ->
            val rich = ColorMatrix().apply { setSaturation(1.18f) }
            c.drawBitmap(pic, null, RectF(0f, 0f, w, h), Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(rich) })
        }
        zones(c, Face::fill)
    }

    /**
     * The edge: a hairline in each zone's edge tone, and for glass one highlight along the top
     * that falls off towards the corners, like light on a curved pane. Never white-hot.
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
        c.drawRoundRect(r, radius - inset, radius - inset, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; this.shader = shader
        })
        if (finish != Finish.VETRO) return
        // The one highlight: drawn into a layer, then faded at both ends
        val layer = c.saveLayer(0f, 0f, w, h, null)
        c.drawRoundRect(r, radius - inset, radius - inset, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke * 1.4f
            this.shader = LinearGradient(0f, 0f, 0f, radius * 1.6f, 0x8CFFF8EC.toInt(), 0x00FFF8EC, Shader.TileMode.CLAMP)
        })
        c.drawRect(0f, 0f, w, h, Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(0x00000000, 0xFF000000.toInt(), 0xFF000000.toInt(), 0x00000000),
                floatArrayOf(0f, 0.32f, 0.68f, 1f), Shader.TileMode.CLAMP)
        })
        c.restoreToCount(layer)
    }
}
