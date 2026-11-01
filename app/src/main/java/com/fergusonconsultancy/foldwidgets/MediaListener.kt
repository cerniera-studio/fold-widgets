package com.fergusonconsultancy.foldwidgets

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * The app's one long-lived part. Android keeps a notification listener running, and only a
 * notification listener may watch other apps' media sessions. It watches the players, moves the
 * progress line while the screen is on, and redraws every widget when the wallpaper changes. It
 * reads notifications only if Mail is turned on, and then only those of the email apps chosen.
 */
class MediaListener : android.service.notification.NotificationListenerService() {

    private val main = Handler(Looper.getMainLooper())
    private val watched = HashMap<MediaSessionManagerToken, MediaController>()
    private var sessions: MediaSessionManager? = null

    private val onSessions = MediaSessionManager.OnActiveSessionsChangedListener { list -> watch(list.orEmpty()) }

    private val onPlayer = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = soon()
        override fun onMetadataChanged(metadata: MediaMetadata?) = soon()
        override fun onSessionDestroyed() = soon()
    }

    private val onWallpaper = WallpaperManager.OnColorsChangedListener { _, which ->
        if (which and WallpaperManager.FLAG_SYSTEM != 0) {
            Backdrop.invalidate()
            Widgets.later { Widgets.renderAll(this) }
        }
    }

    private val onScreen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_ON) { soon(); main.removeCallbacks(mailPoll); main.post(mailPoll) }
            else { main.removeCallbacks(tick); main.removeCallbacks(mailPoll) }
        }
    }

    override fun onListenerConnected() {
        instance = this
        Probe.note(this, "Widget service started (kept links: ${Mail.keptCount()})", null)
        Widgets.later { Mail.remember(this) }
        mailSoon()
        val msm = getSystemService(MediaSessionManager::class.java)
        sessions = msm
        runCatching {
            msm.addOnActiveSessionsChangedListener(onSessions, ComponentName(this, MediaListener::class.java), main)
            watch(msm.getActiveSessions(ComponentName(this, MediaListener::class.java)))
        }
        runCatching { WallpaperManager.getInstance(this).addOnColorsChangedListener(onWallpaper, main) }
        registerReceiver(onScreen, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }, Context.RECEIVER_NOT_EXPORTED)
        soon()
        main.post(mailPoll)
    }

    override fun onListenerDisconnected() {
        instance = null
        runCatching { sessions?.removeOnActiveSessionsChangedListener(onSessions) }
        runCatching { WallpaperManager.getInstance(this).removeOnColorsChangedListener(onWallpaper) }
        runCatching { unregisterReceiver(onScreen) }
        watched.values.forEach { it.unregisterCallback(onPlayer) }
        watched.clear()
        main.removeCallbacksAndMessages(null)
    }

    private fun watch(list: List<MediaController>) {
        val keep = list.associateBy { MediaSessionManagerToken(it) }
        watched.keys.filter { it !in keep }.forEach { watched.remove(it)?.unregisterCallback(onPlayer) }
        keep.forEach { (k, ctl) -> if (k !in watched) { ctl.registerCallback(onPlayer, main); watched[k] = ctl } }
        soon()
    }

    /** Players report in bursts (state, then metadata, then artwork): redraw once they settle. */
    private val redraw = Runnable {
        Widgets.later {
            Widgets.renderAll(this, Kind.MUSIC)
            main.post { main.removeCallbacks(tick); main.postDelayed(tick, 1000) }
        }
    }

    private fun soon() { main.removeCallbacks(redraw); main.postDelayed(redraw, 160) }

    // ---- Mail: redrawn when an email app's notifications change --------------------------------
    private val mailRedraw = Runnable { Widgets.later { Widgets.renderAll(this, Kind.MAIL) } }
    private fun mailSoon() { main.removeCallbacks(mailRedraw); main.postDelayed(mailRedraw, 400) }

    private fun isMail(sbn: android.service.notification.StatusBarNotification?) =
        sbn != null && (Mail.enabled(this) || Mailbox.connected(this)) && sbn.packageName in Mail.apps(this)

    /** An email app's notifications changed: something arrived, or was dealt with in the app. */
    private fun mailboxNudge(sbn: android.service.notification.StatusBarNotification?) {
        if (sbn != null && sbn.packageName in Mail.APPS.keys && Mailbox.connected(this)) Mailbox.syncSoon(this, 4_000L)
    }

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) {
        if (isMail(sbn)) { Widgets.later { Mail.remember(this) }; mailSoon() }
        mailboxNudge(sbn)
    }
    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification?) {
        if (isMail(sbn)) mailSoon()
        mailboxNudge(sbn)
    }

    /** While the screen is on, the inboxes are read every three minutes; on waking, if older than a minute. */
    private var lastPoll = 0L
    private val mailPoll = object : Runnable {
        override fun run() {
            if (!Mailbox.connected(this@MediaListener) || Widgets.ids(this@MediaListener, Kind.MAIL).isEmpty()) return
            if (!getSystemService(PowerManager::class.java).isInteractive) return
            if (System.currentTimeMillis() - lastPoll > 60_000L) { lastPoll = System.currentTimeMillis(); Mailbox.syncSoon(this@MediaListener) }
            main.postDelayed(this, 180_000L)
        }
    }

    companion object {
        /** The running listener, from which the mail widget reads the notifications on screen. */
        @Volatile var instance: MediaListener? = null
    }

    /** While something plays and the screen is on, the line moves once a second; nothing else is redrawn. */
    private val tick = object : Runnable {
        override fun run() {
            if (!getSystemService(PowerManager::class.java).isInteractive) return
            val ids = Widgets.ids(this@MediaListener, Kind.MUSIC)
            if (ids.isEmpty()) return
            Widgets.later {
                val np = Media.now(this@MediaListener)
                if (np != null && np.playing && np.duration > 0) {
                    val v = np.progress()
                    ids.forEach { Widgets.nudge(this@MediaListener, it, v) }
                    main.postDelayed(this, 1000)
                }
            }
        }
    }
}

/** Sessions compared by their token, which stays the same for the life of a player's session. */
private class MediaSessionManagerToken(ctl: MediaController) {
    private val token = ctl.sessionToken
    override fun equals(other: Any?) = other is MediaSessionManagerToken && other.token == token
    override fun hashCode() = token.hashCode()
}
