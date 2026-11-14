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
            val sent = runCatching { pending.send(this, 0, null, null, null, null, options.toBundle()) }
            if (sent.isSuccess) return
        }
        if (target?.getStringExtra(EXTRA_RESOLVE) == RESOLVE_WEATHER) { weather(); return }
        // The exact thing first, then the app itself if that cannot be opened
        @Suppress("DEPRECATION")
        val fallback = intent.getParcelableExtra<Intent>(Widgets.EXTRA_FALLBACK)
        for (each in listOfNotNull(target, fallback)) {
            val started = runCatching { startActivity(each.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            if (started.isSuccess) return
        }
    }

    /**
     * The weather app chosen in settings (Samsung Weather until one is chosen). Samsung Weather by
     * whichever way in exists now. If it offers none, or refuses, say so and
     * show the weather in Google instead: a tap must never do nothing (31 Oct).
     */
    private fun weather() {
        when (val choice = Weather.opener(this)) {
            Weather.GOOGLE -> { google(); return }
            Weather.SAMSUNG_WEATHER -> {}
            else -> {
                val app = packageManager.getLaunchIntentForPackage(choice)
                if (app != null && runCatching { startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
                // The chosen app has gone: say so, and fall through to Samsung Weather
                android.widget.Toast.makeText(applicationContext, "${Weather.openerName(this, choice)} can't be opened, so Samsung Weather opened instead. Choose again in Fold Widgets' settings.",
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
        val samsung = Weather.launchIntent(this)
        if (samsung != null && runCatching { startActivity(samsung.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        android.widget.Toast.makeText(applicationContext,
            "Samsung Weather can't be opened by other apps yet. In Samsung Weather's settings, turn on adding it to the Apps screen.",
            android.widget.Toast.LENGTH_LONG).show()
        google()
    }

    private fun google() {
        val google = Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, "weather")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(google) }
    }

    companion object {
        const val EXTRA_RESOLVE = "fw.resolve"
        const val RESOLVE_WEATHER = "weather"
    }
}

/** Taps that act in place. */
class TapReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Widgets.seatFrom(c.applicationContext, i)
        when (i.action) {
            ACTION_PLAY -> Media.control(c, Media.Control.TOGGLE)
            Meridiana.ACTION_REVEAL -> {
                val id = i.getIntExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                if (id < 0) return
                val app = c.applicationContext
                val pending = goAsync()
                Thread {
                    try {
                        Meridiana.reveal(app, id); Widgets.render(app, id)
                        Thread.sleep(Meridiana.REVEAL_MS + 100)
                        Widgets.render(app, id)
                    } catch (e: Exception) { android.util.Log.w("FoldWidgets", "dial", e) }
                    finally { pending.finish() }
                }.start()
            }
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
        const val ACTION_WEATHER = "com.fergusonconsultancy.foldwidgets.WEATHER"
    }
}
