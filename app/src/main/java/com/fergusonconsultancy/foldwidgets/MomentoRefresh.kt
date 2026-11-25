package com.fergusonconsultancy.foldwidgets

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Nodo (a knot tied, moved or done) and Dorso (a read left) say something changed: Momento looks
 * again, so its notes keep up (22 Nov 2026). Only ever a redraw, so any app may send it. Partenza
 * sends it too when its leave-by changes (24 Nov 2026).
 */
class MomentoRefresh : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val pending = goAsync()
        Thread {
            try {
                Widgets.renderAll(c.applicationContext, Kind.MOMENTO)
                runCatching { Clockwork.arm(c.applicationContext) }
            } catch (e: Exception) { android.util.Log.w("FoldWidgets", "momento refresh", e) }
            finally { pending.finish() }
        }.start()
    }
}
