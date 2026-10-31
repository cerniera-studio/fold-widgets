package com.fergusonconsultancy.foldwidgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.util.concurrent.Executors

/** What a touch area does. */
sealed class Act {
    /** Opens something: a pending intent another app gave us (a player's own screen), or an intent. */
    class Open(val pending: PendingIntent? = null, val intent: Intent? = null) : Act()
    /** Acts in place: play and pause, refresh. */
    class Send(val action: String) : Act()
}

/** A touch area, in dp inside the widget. */
class Hit(val rect: RectF, val act: Act, val label: String)

/** The progress line, in dp inside the widget, with its value from 0 to 1. */
class Line(val rect: RectF, val value: Float, val track: Int, val fill: Int)

/** What a painter leaves besides the picture: where touches go, and the line if there is one. */
class Scene(val hits: List<Hit>, val line: Line? = null)

/** Paints one kind of widget on a canvas measured in dp, over its finish. */
interface Painter {
    fun paint(c: Context, id: Int, canvas: Canvas, s: Surface): Scene
}

enum class Kind(val painter: Painter) {
    MUSIC(MusicPainter), AGENDA(AgendaPainter), WEATHER(WeatherPainter), MONTH(MonthPainter), STORAGE(StoragePainter)
}

/**
 * Every widget is one picture drawn at its exact size, so the type, artwork and controls scale with
 * it and nothing is ever stretched. On a resize the launcher reports the new size and the picture
 * is drawn again.
 */
abstract class Widget : AppWidgetProvider() {
    abstract val kind: Kind

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        Widgets.inBackground(this) { ids.forEach { Widgets.render(c, it) } }
        Clockwork.arm(c)
    }

    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, options: Bundle) {
        Widgets.inBackground(this) { Widgets.render(c, id) }
    }

    override fun onDeleted(c: Context, ids: IntArray) = ids.forEach { Prefs.forget(c, it) }
}

class MusicSmall : Widget() { override val kind = Kind.MUSIC }
class MusicLarge : Widget() { override val kind = Kind.MUSIC }
class AgendaSmall : Widget() { override val kind = Kind.AGENDA }
class AgendaLarge : Widget() { override val kind = Kind.AGENDA }
class MonthSmall : Widget() { override val kind = Kind.MONTH }
class MonthLarge : Widget() { override val kind = Kind.MONTH }
class StorageSmall : Widget() { override val kind = Kind.STORAGE }
class WeatherSmall : Widget() {
    override val kind = Kind.WEATHER
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) { super.onUpdate(c, m, ids); RefreshJob.schedule(c) }
}
class WeatherLarge : Widget() {
    override val kind = Kind.WEATHER
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) { super.onUpdate(c, m, ids); RefreshJob.schedule(c) }
}

object Widgets {
    const val EXTRA_HIT = "fw.hit"
    const val EXTRA_SIZE = "fw.size"
    const val EXTRA_PENDING = "fw.pending"
    const val EXTRA_TARGET = "fw.target"

    private val providers = mapOf(
        Kind.MUSIC to listOf(MusicSmall::class.java, MusicLarge::class.java),
        Kind.AGENDA to listOf(AgendaSmall::class.java, AgendaLarge::class.java),
        Kind.WEATHER to listOf(WeatherSmall::class.java, WeatherLarge::class.java),
        Kind.MONTH to listOf(MonthSmall::class.java, MonthLarge::class.java),
        Kind.STORAGE to listOf(StorageSmall::class.java),
    )

    /** One worker, so redraws never overlap and never block the main thread. */
    private val worker = Executors.newSingleThreadExecutor()

    fun later(work: () -> Unit) = worker.execute { runCatching(work).onFailure { android.util.Log.e("FoldWidgets", "draw", it) } }

    fun inBackground(receiver: android.content.BroadcastReceiver, work: () -> Unit) {
        val pending = receiver.goAsync()
        worker.execute {
            try { runCatching(work).onFailure { android.util.Log.e("FoldWidgets", "draw", it) } } finally { pending.finish() }
        }
    }

    fun ids(c: Context, kind: Kind): IntArray {
        val m = AppWidgetManager.getInstance(c)
        return providers.getValue(kind).flatMap { m.getAppWidgetIds(ComponentName(c, it)).toList() }.toIntArray()
    }

    fun kindOf(c: Context, id: Int): Kind? {
        val name = AppWidgetManager.getInstance(c).getAppWidgetInfo(id)?.provider?.className ?: return null
        return providers.entries.firstOrNull { (_, list) -> list.any { it.name == name } }?.key
    }

    fun renderAll(c: Context, kind: Kind? = null) =
        (if (kind == null) Kind.entries else listOf(kind)).forEach { k -> ids(c, k).forEach { render(c, it) } }

    /** The widget's size in dp: the first size the launcher reports, else its portrait bounds. */
    fun sizeOf(c: Context, id: Int): SizeF {
        val o = AppWidgetManager.getInstance(c).getAppWidgetOptions(id)
        @Suppress("DEPRECATION")
        val sizes = o.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
        sizes?.firstOrNull()?.takeIf { it.width > 0 && it.height > 0 }?.let { return it }
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        if (w > 0 && h > 0) return SizeF(w.toFloat(), h.toFloat())
        val info = AppWidgetManager.getInstance(c).getAppWidgetInfo(id)
        val d = c.resources.displayMetrics.density
        return SizeF((info?.minWidth ?: 380) / d, (info?.minHeight ?: 330) / d)
    }

    /** Draws widget [id] at its size and hands it to the launcher. */
    fun render(c: Context, id: Int) {
        val kind = kindOf(c, id) ?: return
        val size = sizeOf(c, id)
        val (bitmap, scene) = draw(c, id, kind, size, Prefs.finish(c, id), Backdrop.ground(c, id))
        val rv = RemoteViews(c.packageName, R.layout.widget)
        rv.setImageViewBitmap(R.id.face, bitmap)
        rv.setContentDescription(R.id.face, kind.name.lowercase())
        HITS.forEachIndexed { i, viewId ->
            val hit = scene.hits.getOrNull(i)
            if (hit == null) { rv.setViewVisibility(viewId, View.GONE); return@forEachIndexed }
            place(rv, viewId, hit.rect)
            rv.setViewVisibility(viewId, View.VISIBLE)
            rv.setContentDescription(viewId, hit.label)
            rv.setOnClickPendingIntent(viewId, pendingFor(c, id, i, hit, size))
        }
        val line = scene.line
        if (line == null) rv.setViewVisibility(R.id.progress, View.GONE) else {
            place(rv, R.id.progress, line.rect)
            rv.setViewVisibility(R.id.progress, View.VISIBLE)
            rv.setProgressBar(R.id.progress, 1000, (line.value.coerceIn(0f, 1f) * 1000).toInt(), false)
            rv.setColorStateList(R.id.progress, "setProgressTintList", ColorStateList.valueOf(line.fill))
            rv.setColorStateList(R.id.progress, "setProgressBackgroundTintList", ColorStateList.valueOf(line.track))
        }
        AppWidgetManager.getInstance(c).updateAppWidget(id, rv)
    }

    /** Moves the progress line without redrawing the picture: cheap enough to do every second. */
    fun nudge(c: Context, id: Int, value: Float) {
        val rv = RemoteViews(c.packageName, R.layout.widget)
        rv.setProgressBar(R.id.progress, 1000, (value.coerceIn(0f, 1f) * 1000).toInt(), false)
        AppWidgetManager.getInstance(c).partiallyUpdateAppWidget(id, rv)
    }

    /**
     * The picture. Drawn on a canvas scaled to dp, so painters think in dp and the result is
     * sharp at the screen's density. [ground] is what is behind the widget.
     */
    fun draw(c: Context, id: Int, kind: Kind, size: SizeF, finish: Finish, ground: Ground): Pair<Bitmap, Scene> {
        var d = c.resources.displayMetrics.density
        // Stay well inside what a launcher will accept for one widget's picture
        val longest = maxOf(size.width, size.height) * d
        if (longest > 1400f) d *= 1400f / longest
        val bmp = Bitmap.createBitmap((size.width * d).toInt().coerceAtLeast(1), (size.height * d).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp).apply { scale(d, d) }
        val surface = Surface(finish, ground, size.width, size.height, Prefs.radius(c).toFloat().coerceAtMost(minOf(size.width, size.height) / 2f), 1.1f / d)
        surface.paint(canvas)
        Type.glass = surface.glassy
        val scene = try { kind.painter.paint(c, id, canvas, surface) } finally { Type.glass = false }
        return bmp to scene
    }

    private fun place(rv: RemoteViews, viewId: Int, r: RectF) {
        val dip = TypedValue.COMPLEX_UNIT_DIP
        rv.setViewLayoutWidth(viewId, r.width(), dip)
        rv.setViewLayoutHeight(viewId, r.height(), dip)
        rv.setViewLayoutMargin(viewId, RemoteViews.MARGIN_LEFT, r.left, dip)
        rv.setViewLayoutMargin(viewId, RemoteViews.MARGIN_TOP, r.top, dip)
    }

    /**
     * Every touch passes through this app first, carrying where it landed, so the widget learns
     * its seat. Mutable on purpose: the launcher fills in the touch's bounds on the way through,
     * and an immutable intent would drop them. The intents are explicit, to our own components.
     */
    private fun pendingFor(c: Context, id: Int, index: Int, hit: Hit, size: SizeF): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val code = id * 32 + index
        fun Intent.mark() = apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            putExtra(EXTRA_HIT, floatArrayOf(hit.rect.left, hit.rect.top, hit.rect.right, hit.rect.bottom))
            putExtra(EXTRA_SIZE, floatArrayOf(size.width, size.height))
            data = Uri.parse("foldwidgets://tap/$id/$index")
        }
        return when (val act = hit.act) {
            is Act.Open -> PendingIntent.getActivity(c, code, Intent(c, TapActivity::class.java).mark().apply {
                act.pending?.let { putExtra(EXTRA_PENDING, it) }
                act.intent?.let { putExtra(EXTRA_TARGET, it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }, flags)
            is Act.Send -> PendingIntent.getBroadcast(c, code, Intent(c, TapReceiver::class.java).setAction(act.action).mark(), flags)
        }
    }

    /** Reads a tap's touch area and widget size back out of its intent. */
    fun hitOf(i: Intent): RectF? = i.getFloatArrayExtra(EXTRA_HIT)?.takeIf { it.size == 4 }?.let { RectF(it[0], it[1], it[2], it[3]) }
    fun sizeOf(i: Intent): SizeF? = i.getFloatArrayExtra(EXTRA_SIZE)?.takeIf { it.size == 2 }?.let { SizeF(it[0], it[1]) }

    /** Learns the seat from a tap, and redraws the widget if it moved. */
    fun seatFrom(c: Context, i: Intent) {
        val id = i.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        if (Backdrop.record(c, i, id, hitOf(i), sizeOf(i))) later { render(c, id) }
    }

    private val HITS = intArrayOf(R.id.hit0, R.id.hit1, R.id.hit2, R.id.hit3, R.id.hit4, R.id.hit5,
        R.id.hit6, R.id.hit7, R.id.hit8, R.id.hit9, R.id.hit10, R.id.hit11)
}
