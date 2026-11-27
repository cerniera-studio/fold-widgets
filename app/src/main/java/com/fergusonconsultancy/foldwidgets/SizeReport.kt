package com.fergusonconsultancy.foldwidgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.SizeF

/**
 * What each placed widget is told and what it really has (27 Nov 2026).
 *
 * In Smart Launcher's widget stacks a Fold widget was drawn for one shape and shown in another, so it
 * sat shrunk inside its space (Mechack's recording). The emulator can't reproduce his launcher, so
 * this reads it on the phone: for every widget, the sizes the launcher lists, the one taken as in use,
 * the size a tap or live mode measured, and the picture drawn. Shown in Settings › Widget sizes, and
 * shared from there as text.
 */
object SizeReport {
    private fun dp(s: SizeF) = "${Math.round(s.width)}×${Math.round(s.height)}"

    fun lines(c: Context): List<String> {
        val m = AppWidgetManager.getInstance(c)
        val d = Backdrop.density(c)
        val screen = Backdrop.screenSize(c)
        val out = ArrayList<String>()
        out += "Screen now: ${screen.x}×${screen.y} px (${dp(SizeF(screen.x / d, screen.y / d))} dp), ${if (screen.y >= screen.x) "portrait" else "landscape"}, density ${"%.3f".format(d)}"
        out += "Launcher: ${runCatching { c.packageManager.resolveActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName }.getOrNull() ?: "?"}"
        for (kind in Kind.entries) for (id in Widgets.ids(c, kind)) {
            val o = m.getAppWidgetOptions(id)
            @Suppress("DEPRECATION")
            val listed = o.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES).orEmpty().joinToString(", ") { dp(it) }
            val minMax = "min ${o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)}×${o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)}, " +
                "max ${o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)}×${o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)}"
            val seat = Backdrop.seat(c, id)
            val measured = when {
                seat == null -> "not measured yet"
                seat.screenW != screen.x || seat.screenH != screen.y -> "measured on the other screen"
                else -> "${dp(SizeF(seat.width / d, seat.height / d))} dp at ${Math.round(seat.left / d)},${Math.round(seat.top / d)}" +
                    (if (seat.exact) " (exact" + (if (seat.repW > 0) ", when told ${Math.round(seat.repW)}×${Math.round(seat.repH)})" else ")") else " (estimated)")
            }
            val f = Widgets.frameOf(c, id)
            out += ""
            out += "${Widgets.describe(c, id, kind)}  (widget $id)"
            out += "  Listed sizes: ${listed.ifEmpty { "none" }}"
            out += "  Options: $minMax"
            out += "  Taken as in use: ${dp(Widgets.reportedSize(c, id))}"
            out += "  Measured: $measured"
            out += "  Picture drawn: ${dp(f.dp)} dp (${f.px}×${f.py} px)"
        }
        if (out.size == 2) out += "No Fold widgets are placed."
        return out
    }
}

/** Settings › Widget sizes: the report above, and Share to send it whole. */
fun android.app.Activity.sizesSection(column: android.widget.LinearLayout) {
    val me = this
    column.addView(eyebrow("Widget sizes"))
    column.addView(label("What your launcher tells each widget, what it really has, and the picture drawn for it. Share sends it whole.",
        13f, Palette.MUTED).apply { setPadding(px(4), 0, px(4), px(10)) })
    column.addView(card().apply {
        val lines = runCatching { SizeReport.lines(me) }.getOrElse { listOf("Couldn't read the widgets: ${it.message}") }
        addView(row("Report", "${lines.count { it.contains("(widget ") }} widgets placed",
            button("Share", primary = false) {
                startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_SUBJECT, "Fold Widgets sizes").putExtra(android.content.Intent.EXTRA_TEXT, lines.joinToString("\n")), "Share the widget sizes"))
            }))
        lines.forEach { l -> if (l.isNotEmpty()) addView(label(l, 12.5f, if (l.startsWith("  ")) Palette.MUTED else Palette.IVORY).apply { setPadding(0, px(3), 0, px(3)) }) }
    })
}
