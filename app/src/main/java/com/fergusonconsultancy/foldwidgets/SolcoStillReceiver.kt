package com.fergusonconsultancy.foldwidgets

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Solco saved a new still of itself (27 Nov 2026): every widget's glass redraws over it. Only ever a
 * redraw; the still itself is read from Solco's provider, family only.
 */
class SolcoStillReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Widgets.inBackground(this) {
            Backdrop.invalidate()
            Widgets.renderAll(c)
            Look.tell(c)
        }
    }
}
