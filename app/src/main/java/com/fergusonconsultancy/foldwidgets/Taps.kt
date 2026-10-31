package com.fergusonconsultancy.foldwidgets

import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle

/**
 * A tap that opens something lands here for an instant: the widget learns where it sits, then the
 * real destination opens (a player's own screen, the calendar). Never drawn, never animated.
 */
class TapActivity : Activity() {
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        Widgets.seatFrom(applicationContext, intent)
        runCatching { go() }.onFailure { android.util.Log.w("FoldWidgets", "open", it) }
        finish()
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0) else noTransition()
    }

    @Suppress("DEPRECATION")
    private fun noTransition() = overridePendingTransition(0, 0)

    private fun go() {
        @Suppress("DEPRECATION")
        val pending = intent.getParcelableExtra<PendingIntent>(Widgets.EXTRA_PENDING)
        @Suppress("DEPRECATION")
        val target = intent.getParcelableExtra<Intent>(Widgets.EXTRA_TARGET)
        if (pending != null) {
            // This activity is in front, so it may start the other app's screen on its behalf
            val options = ActivityOptions.makeBasic()
            if (Build.VERSION.SDK_INT >= 34) options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            val ok = runCatching { pending.send(this, 0, null, null, null, null, options.toBundle()) }.isSuccess
            if (ok) return
        }
        if (target != null) startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Taps that act in place. */
class TapReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Widgets.seatFrom(c.applicationContext, i)
        when (i.action) {
            ACTION_PLAY -> Media.control(c, Media.Control.TOGGLE)
            ACTION_NEXT -> Media.control(c, Media.Control.NEXT)
            ACTION_PREVIOUS -> Media.control(c, Media.Control.PREVIOUS)
            ACTION_WEATHER -> {
                val pending = goAsync()
                Thread {
                    try { Weather.refresh(c.applicationContext); Widgets.renderAll(c.applicationContext, Kind.WEATHER) }
                    catch (e: Exception) { android.util.Log.w("FoldWidgets", "weather", e) }
                    finally { pending.finish() }
                }.start()
            }
        }
    }

    companion object {
        const val ACTION_PLAY = "com.fergusonconsultancy.foldwidgets.PLAY"
        const val ACTION_NEXT = "com.fergusonconsultancy.foldwidgets.NEXT"
        const val ACTION_PREVIOUS = "com.fergusonconsultancy.foldwidgets.PREVIOUS"
        const val ACTION_WEATHER = "com.fergusonconsultancy.foldwidgets.WEATHER"
    }
}
