package com.fergusonconsultancy.foldwidgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/**
 * The mail widget's scrolling list (1 Nov: every email, scrolled through, as in Outlook's own
 * widget). Android draws a scrolling list from rows an app hands it one at a time; each row here
 * is a picture drawn to its exact pixels, so it keeps Jost, the icons and the glass's ink, and is
 * as sharp as the rest of the widget. A tap on a row opens that email, by the same route as before.
 */
class MailRows : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Rows(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID))

    private class Rows(val c: Context, val id: Int) : RemoteViewsFactory {
        /** An email, or null for the divider where read mail begins. */
        private var rows: List<MailItem?> = emptyList()
        private var boxes = false

        override fun onCreate() {}
        override fun onDestroy() {}

        override fun onDataSetChanged() {
            val items = Mail.items(c)
            boxes = Mailbox.connected(c)
            val unread = items.filter { it.fresh }
            val read = items.filterNot { it.fresh }
            rows = if (unread.isNotEmpty() && read.isNotEmpty()) unread + listOf(null) + read else items
        }

        override fun getCount() = rows.size
        override fun getViewTypeCount() = 1
        override fun hasStableIds() = true
        override fun getItemId(position: Int): Long = rows.getOrNull(position)?.key?.hashCode()?.toLong() ?: -position.toLong()
        override fun getLoadingView(): RemoteViews? = null

        override fun getViewAt(position: Int): RemoteViews {
            val rv = RemoteViews(c.packageName, R.layout.mail_row)
            val m = rows.getOrNull(position)
            val frame = Widgets.frameOf(c, id)
            val (d, px, _) = Widgets.drawn(frame)
            val whole = Widgets.surfaceFor(c, id, frame)
            // Rows scale with the widget on the main screen, as its header does
            val k = Kind.MAIL.scaleFor(whole.w, whole.h)
            val s = whole.scaled(k)
            val st = MailPainter.rowStyle(s, Widgets.previews(c, id))
            val hDp = (if (m == null) st.divider else st.height(m)) * k
            val bmp = Bitmap.createBitmap(px, Math.round(hDp * d).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            // Shown at its own pixels: the row's picture carries the density it was drawn at
            bmp.density = Math.round(d * 160f)
            val cv = Canvas(bmp).apply { scale(d * k, d * k) }
            val sampleY = s.h * 0.6f
            if (m == null) MailPainter.paintDivider(c, cv, s, st, boxes, sampleY)
            else {
                // No separator above the READ heading or below the last email
                val next = position + 1
                val rule = next < rows.size && rows[next] != null
                MailPainter.paintRow(c, cv, s, st, m, px / (d * k), sampleY, rule)
            }
            rv.setImageViewBitmap(R.id.row_face, bmp)
            if (m != null) {
                rv.setContentDescription(R.id.row, "${m.sender}: ${m.subject}")
                val act = MailPainter.openAct(c, m) as? Act.Open
                if (act != null) rv.setOnClickFillInIntent(R.id.row, Intent().apply {
                    act.pending?.let { putExtra(Widgets.EXTRA_PENDING, it) }
                    act.intent?.let { putExtra(Widgets.EXTRA_TARGET, it) }
                    act.fallback?.let { putExtra(Widgets.EXTRA_FALLBACK, it) }
                })
            }
            return rv
        }
    }
}
