package com.fergusonconsultancy.foldwidgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/**
 * The agenda's scrolling list (1 Nov: the two weeks ahead, scrolled through).
 * Each day heading and each event is a row drawn to its exact pixels, in the pack's type; a tap on
 * an event opens it in the calendar.
 */
class AgendaRows : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Rows(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID),
            intent.getFloatExtra(EXTRA_W, 0f), intent.getFloatExtra(EXTRA_H, 0f),
            intent.getFloatExtra(EXTRA_K, -1f), intent.getFloatExtra(EXTRA_FACE_H, -1f))

    companion object {
        /** The widget size (dp) a list is drawn for: each orientation has its own list. */
        const val EXTRA_W = "fw.rows.w"
        const val EXTRA_H = "fw.rows.h"
        /** The scale the face drew its date at, and the face's height: the rows match them (22 Nov 2026). */
        const val EXTRA_K = "fw.rows.k"
        const val EXTRA_FACE_H = "fw.rows.faceh"
    }

    /** A day heading (with whether it is the first) or an event. */
    private class Row(val day: Int, val first: Boolean, val event: Occurrence?)

    private class Rows(val c: Context, val id: Int, val w: Float, val h: Float, val kk: Float, val faceH: Float) : RemoteViewsFactory {
        private var rows: List<Row> = emptyList()
        override fun onCreate() {}
        override fun onDestroy() {}
        override fun onDataSetChanged() {
            val out = ArrayList<Row>()
            var last = -1
            for (o in Agenda.ahead(c, id)) {
                if (o.day != last) { out += Row(o.day, last == -1, null); last = o.day }
                out += Row(o.day, false, o)
            }
            rows = out
        }
        override fun getCount() = rows.size
        override fun getViewTypeCount() = 1
        override fun hasStableIds() = true
        override fun getItemId(position: Int): Long = rows.getOrNull(position)?.let { r -> r.event?.let { it.eventId * 31 + it.begin } ?: -r.day.toLong() } ?: position.toLong()
        // An empty row while a row is drawn, never the system's own "Loading…" in its own type (22 Nov 2026)
        override fun getLoadingView(): RemoteViews = RemoteViews(c.packageName, R.layout.list_row)

        override fun getViewAt(position: Int): RemoteViews {
            val rv = RemoteViews(c.packageName, R.layout.list_row)
            val r = rows.getOrNull(position) ?: return rv
            val frame = run {
                val now = Widgets.reportedSize(c, id)
                if (w <= 0f || h <= 0f || (Math.abs(now.width - w) < 1.5f && Math.abs(now.height - h) < 1.5f)) Widgets.frameOf(c, id)
                else { val d = Backdrop.density(c); Widgets.Frame(Math.round(w * d), Math.round(h * d), d) }
            }
            val (d, px, _) = Widgets.drawn(frame)
            val full = Widgets.surfaceFor(c, id, frame)
            // The face the rows belong to: Momento draws its agenda above its notes, so it is shorter than the widget
            val whole = if (faceH > 0f && faceH < full.h) com.cerniera.kit.Surface(full.finish, full.ground, full.w, faceH, full.radius, full.hairline) else full
            val k = if (kk > 0f) kk else Kind.AGENDA.scaleFor(whole.w, whole.h)
            val s = whole.scaled(k)
            val st = AgendaPainter.rowStyle(c, s, Widgets.detailed(c, id))
            val hDp = st.height(r.event, r.first) * k
            val bmp = Bitmap.createBitmap(px, Math.round(hDp * d).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            bmp.density = Math.round(d * 160f)
            val cv = Canvas(bmp).apply { scale(d * k, d * k) }
            val sampleY = s.h * 0.6f
            val e = r.event
            if (e == null) AgendaPainter.paintDay(c, cv, s, st, r.day, r.first, sampleY)
            else {
                AgendaPainter.paintEvent(c, cv, s, st, e, px / (d * k), sampleY)
                rv.setContentDescription(R.id.row, e.title)
                rv.setOnClickFillInIntent(R.id.row, Intent().putExtra(Widgets.EXTRA_TARGET, Agenda.openEvent(c, e)))
            }
            rv.setImageViewBitmap(R.id.row_face, bmp)
            return rv
        }
    }
}
