package com.fergusonconsultancy.foldwidgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.SizeF
import com.cerniera.kit.Finish
import com.cerniera.kit.Ground
import java.io.File

/**
 * Debug builds only (22 Nov 2026): every widget drawn by its own painter, over sample content, at
 * the sizes Mechack uses on both screens, written as PNGs for the emulator test to collect; and the
 * picker's previews, all on one canvas at one scale, so that in the picker every Cerniera widget
 * shows at its true size relative to the others, across every app of the family.
 */
object Matrix {
    /** The family's preview canvas: 960 px square at 2.625 px per dp (the cover screen's density). */
    const val PREVIEW_PX = 960
    const val PREVIEW_DENSITY = 2.625f

    /** Sizes in dp: the cover screen's cells, the main screen's, and Mechack's own from his 22 Nov screenshots. */
    val SIZES = listOf(
        SizeF(192f, 164f), SizeF(255f, 164f), SizeF(255f, 267f), SizeF(255f, 361f), SizeF(128f, 267f),
        SizeF(177f, 226f), SizeF(177f, 300f), SizeF(177f, 480f), SizeF(385f, 226f), SizeF(385f, 480f), SizeF(560f, 300f),
        SizeF(300f, 120f), SizeF(110f, 110f))

    fun render(c: Context) {
        Sample.on = true
        Momento.sample = listOf("17:30 · Newcastle v Arsenal", "Carry on · The Watch Enthusiast Who Wasn't Buying · 9 min left")
        try {
            val dir = File(c.filesDir, "matrix").apply { deleteRecursively(); mkdirs() }
            val d = 2.625f
            val ground = Ground.flat(0xFF1B211E.toInt())
            for ((n, kind) in Kind.entries.withIndex()) for (s in SIZES) {
                val bmp = whole(c, -10 - n, kind, s, d, Finish.VETRO, ground)
                save(bmp, File(dir, "${kind.name.lowercase()}-${s.width.toInt()}x${s.height.toInt()}.png"))
            }
            // The picker's previews, one per entry, at its default size
            val m = AppWidgetManager.getInstance(c)
            val previews = File(c.filesDir, "previews").apply { deleteRecursively(); mkdirs() }
            val names = mapOf("MusicCompact" to "music_compact", "MusicSmall" to "music_wide", "MusicLarge" to "music_poster",
                "AgendaSmall" to "agenda_list", "AgendaLarge" to "agenda_detail", "WeatherSmall" to "weather_today",
                "WeatherLarge" to "weather_forecast", "MonthSmall" to "month_compact", "MonthLarge" to "month_full", "MomentoWidget" to "momento")
            for (info in m.getInstalledProvidersForPackage(c.packageName, null)) {
                val cls = info.provider.className.substringAfterLast('.')
                val name = names[cls] ?: continue
                val kind = Widgets.kindForClass(cls) ?: continue
                // minWidth and minHeight are given in px at the screen's density
                val dm = c.resources.displayMetrics.density
                val size = SizeF(info.minWidth / dm, info.minHeight / dm)
                // A fixed id per entry, so variant-dependent painters read the right variant
                val bmp = whole(c, previewId(cls), kind, size, PREVIEW_DENSITY, Finish.CARBONIO, Ground.flat(0xFF16191A.toInt()))
                save(onCanvas(bmp), File(previews, "preview_$name.png"))
            }
        } finally { Sample.on = false; Momento.sample = null }
    }

    /** Ids below −100 stand for picker entries: the painters read the variant from them. */
    fun previewId(cls: String) = -100 - listOf("MusicCompact", "MusicSmall", "MusicLarge", "AgendaSmall", "AgendaLarge",
        "WeatherSmall", "WeatherLarge", "MonthSmall", "MonthLarge", "MomentoWidget").indexOf(cls)

    fun variantOf(id: Int): String? = listOf("MusicCompact", "MusicSmall", "MusicLarge", "AgendaSmall", "AgendaLarge",
        "WeatherSmall", "WeatherLarge", "MonthSmall", "MonthLarge", "MomentoWidget").getOrNull(-100 - id)

    /** [b] centred on the family's preview canvas. */
    fun onCanvas(b: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(PREVIEW_PX, PREVIEW_PX, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(b, ((PREVIEW_PX - b.width) / 2).toFloat(), ((PREVIEW_PX - b.height) / 2).toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** A widget at [size] dp, its scrolling list (if any) drawn in place, as the phone shows it. */
    fun whole(c: Context, id: Int, kind: Kind, size: SizeF, d: Float, finish: Finish, ground: Ground): Bitmap {
        val frame = Widgets.Frame(Math.round(size.width * d), Math.round(size.height * d), d)
        val (bmp, scene) = Widgets.draw(c, id, kind, frame, finish, ground)
        if (scene.listTop < 0f) return bmp
        val k = scene.listScale
        val all = com.cerniera.kit.Surface(finish, ground, size.width, if (scene.listFaceH > 0f) scene.listFaceH else size.height,
            Prefs.radius(c).toFloat(), 1.1f / d).scaled(k)
        val st = AgendaPainter.rowStyle(c, all, Widgets.detailed(c, id))
        val cv = Canvas(bmp).apply { scale(d, d) }
        val bottom = if (scene.listBottom >= 0f) scene.listBottom else size.height - 6f
        cv.clipRect(0f, scene.listTop, size.width, bottom)
        var y = scene.listTop
        var last = -1
        for (o in Agenda.ahead(c, id)) {
            if (y > bottom) break
            if (o.day != last) {
                cv.save(); cv.translate(0f, y); cv.scale(k, k)
                AgendaPainter.paintDay(c, cv, all, st, o.day, last == -1, all.h * 0.6f); cv.restore()
                y += st.height(null, last == -1) * k; last = o.day
            }
            cv.save(); cv.translate(0f, y); cv.scale(k, k)
            AgendaPainter.paintEvent(c, cv, all, st, o, size.width / k, all.h * 0.6f); cv.restore()
            y += st.height(o, false) * k
        }
        return bmp
    }

    private fun save(b: Bitmap, f: File) = f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
}
