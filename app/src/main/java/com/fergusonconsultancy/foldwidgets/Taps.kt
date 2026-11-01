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
        // Opening an email: look at the inboxes again while it is likely being read, deleted or archived
        val opened = pending?.creatorPackage ?: target?.`package` ?: target?.component?.packageName
        if (opened != null && opened in Mail.APPS.keys && Mailbox.connected(this)) Mailbox.followUp(applicationContext)
        if (pending != null) {
            // This activity is in front, so it may start the other app's screen on its behalf
            val options = ActivityOptions.makeBasic()
            if (Build.VERSION.SDK_INT >= 34) options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            val sent = runCatching { pending.send(this, 0, null, null, null, null, options.toBundle()) }
            if (opened in Mail.APPS.keys) Probe.note(this, "Notification link [${target?.getStringExtra(Probe.EXTRA_SOURCE) ?: "?"}]", sent.exceptionOrNull())
            if (sent.isSuccess) return
        }
        if (target?.getStringExtra(EXTRA_RESOLVE) == RESOLVE_WEATHER) { weather(); return }
        // The exact thing first (an email in its app), then the app itself if that cannot be opened
        @Suppress("DEPRECATION")
        val fallback = intent.getParcelableExtra<Intent>(Widgets.EXTRA_FALLBACK)
        for (each in listOfNotNull(target, fallback)) {
            val started = runCatching { startActivity(each.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            // Which way an email tap actually went, so it can be read back from settings
            if (opened in Mail.APPS.keys) Probe.note(this, (if (each.data != null) "Message link ${each.data?.scheme}://${each.data?.host}${each.data?.path}" else "Outlook itself (fallback)") +
                " [${each.getStringExtra(Probe.EXTRA_SOURCE) ?: "?"}]" + (if (pending == null) ", no notification link for this email (${Mail.keptCount()} kept)" else ""), started.exceptionOrNull())
            if (started.isSuccess) return
        }
    }

    /**
     * Samsung Weather, by whichever way in exists now. If it offers none, or refuses, say so and
     * show the weather in Google instead: a tap must never do nothing (31 Oct).
     */
    private fun weather() {
        val samsung = Weather.launchIntent(this)
        if (samsung != null && runCatching { startActivity(samsung.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        android.widget.Toast.makeText(applicationContext,
            "Samsung Weather can't be opened by other apps yet. In Samsung Weather's settings, turn on adding it to the Apps screen.",
            android.widget.Toast.LENGTH_LONG).show()
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
