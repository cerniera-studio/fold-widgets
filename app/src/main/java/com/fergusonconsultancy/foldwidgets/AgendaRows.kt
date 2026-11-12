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
            intent.getFloatExtra(EXTRA_W, 0f), intent.getFloatExtra(EXTRA_H, 0f))

    companion object {
        /** The widget size (dp) a list is drawn for: each orientation has its own list. */
        const val EXTRA_W = "fw.rows.w"
        const val EXTRA_H = "fw.rows.h"
    }

    /** A day heading (with whether it is the first) or an event. */
    private class Row(val day: Int, val first: Boolean, val event: Occurrence?)

    private class Rows(val c: Context, val id: Int, val w: Float, val h: Float) : RemoteViewsFactory {
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
        override fun getLoadingView(): RemoteViews? = null

        override fun getViewAt(position: Int): RemoteViews {
            val rv = RemoteViews(c.packageName, R.layout.list_row)
            val r = rows.getOrNull(position) ?: return rv
            val frame = run {
                val now = Widgets.reportedSize(c, id)
                if (w <= 0f || h <= 0f || (Math.abs(now.width - w) < 1.5f && Math.abs(now.height - h) < 1.5f)) Widgets.frameOf(c, id)
                else { val d = Backdrop.density(c); Widgets.Frame(Math.round(w * d), Math.round(h * d), d) }
            }
            val (d, px, _) = Widgets.drawn(frame)
            val whole = Widgets.surfaceFor(c, id, frame)
            val k = Kind.AGENDA.scaleFor(whole.w, whole.h)
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
