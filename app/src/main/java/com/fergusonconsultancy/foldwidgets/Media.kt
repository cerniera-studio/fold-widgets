package com.fergusonconsultancy.foldwidgets

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent

/** What is playing, from whichever app is playing it. */
class NowPlaying(
    val pkg: String,
    val app: String,
    val icon: Bitmap?,
    val title: String,
    val artist: String,
    val album: String,
    val art: Bitmap?,
    val accent: Int?,
    val playing: Boolean,
    private val position: Long,
    private val updated: Long,
    private val speed: Float,
    val duration: Long,
    val actions: Long,
    val open: PendingIntent?,
) {
    /** How far through, 0 to 1, carried forward from the player's last report at its speed. */
    fun progress(): Float {
        if (duration <= 0) return 0f
        val pos = if (playing && updated > 0) position + ((SystemClock.elapsedRealtime() - updated) * speed).toLong() else position
        return (pos.toFloat() / duration).coerceIn(0f, 1f)
    }
}

/**
 * The music widget follows the media session Android marks as active: Spotify, Poweramp or any
 * player that publishes one (all mainstream players do, for the lock screen). Playing beats
 * paused; among equals, the most recently active wins.
 */
object Media {
    enum class Control { TOGGLE }

    private fun component(c: Context) = ComponentName(c, MediaListener::class.java)

    /** Whether the user has allowed this app to see media sessions (via notification access). */
    fun allowed(c: Context): Boolean =
        c.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component(c))

    fun controllers(c: Context): List<MediaController> = runCatching {
        c.getSystemService(MediaSessionManager::class.java).getActiveSessions(component(c))
    }.getOrDefault(emptyList())

    private fun active(state: Int?) = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING ||
        state == PlaybackState.STATE_CONNECTING || state == PlaybackState.STATE_FAST_FORWARDING || state == PlaybackState.STATE_REWINDING

    fun pick(list: List<MediaController>): MediaController? =
        list.firstOrNull { active(it.playbackState?.state) } ?: list.firstOrNull { it.metadata != null } ?: list.firstOrNull()

    /** The followed sessions only (5 Nov 2026: the phone's call screen was being shown as a player). */
    fun followedControllers(c: Context): List<MediaController> {
        val list = controllers(c)
        remember(c, list.map { it.packageName })
        return list.filter { follows(c, it.packageName) }
    }

    /** The last player used, kept for when nothing is playing. */
    fun lastPlayer(c: Context): String? = Prefs.of(c).getString("last_player", null)?.takeIf { follows(c, it) }

    // ---- Players to follow ----------------------------------------------------------------------
    // A media session is not proof of music: the call screen, a video app or a voice note can hold
    // one. A real player offers its library to other apps (a MediaBrowserService: Spotify, Poweramp,
    // YouTube Music, Samsung Music), so those are followed by default and anything else is not,
    // unless chosen in settings.

    private const val BROWSER = "android.media.browse.MediaBrowserService"
    @Volatile private var browsers: Set<String>? = null

    /** Apps that offer a media library, the mark of a player. */
    fun libraries(c: Context): Set<String> = browsers ?: run {
        @Suppress("DEPRECATION")
        val found = c.packageManager.queryIntentServices(Intent(BROWSER), 0).map { it.serviceInfo.packageName }.toSet()
        browsers = found; found
    }

    fun follows(c: Context, pkg: String): Boolean {
        val p = Prefs.of(c)
        if (pkg in p.getStringSet("music_follow", emptySet())!!) return true
        if (pkg in p.getStringSet("music_ignore", emptySet())!!) return false
        return pkg in libraries(c)
    }

    fun setFollows(c: Context, pkg: String, on: Boolean) {
        val p = Prefs.of(c)
        val follow = p.getStringSet("music_follow", emptySet())!!.toMutableSet()
        val ignore = p.getStringSet("music_ignore", emptySet())!!.toMutableSet()
        follow.remove(pkg); ignore.remove(pkg)
        if (on != (pkg in libraries(c))) (if (on) follow else ignore).add(pkg)
        p.edit().putStringSet("music_follow", follow).putStringSet("music_ignore", ignore).apply()
    }

    /** Every app that has held a media session here, so settings can list it. */
    private fun remember(c: Context, pkgs: List<String>) {
        val p = Prefs.of(c)
        val seen = p.getStringSet("music_seen", emptySet())!!
        if (seen.containsAll(pkgs)) return
        p.edit().putStringSet("music_seen", seen + pkgs).apply()
    }

    /** Players settings offers: every installed library app and every app seen holding a session. */
    fun candidates(c: Context): List<String> =
        (libraries(c) + Prefs.of(c).getStringSet("music_seen", emptySet())!!)
            .filter { it != c.packageName && (it in libraries(c) || c.packageManager.getLaunchIntentForPackage(it) != null) }
            .distinct().sortedBy { label(c, it).lowercase() }

    // ---- What Play starts when nothing is playing ------------------------------------------------

    /** The chosen player, or null for the last one used. */
    fun playWith(c: Context): String? = Prefs.of(c).getString("music_play_with", null)
    fun setPlayWith(c: Context, pkg: String?) = Prefs.of(c).edit().apply {
        if (pkg == null) remove("music_play_with") else putString("music_play_with", pkg)
    }.apply()

    /** The player Play will start when nothing is playing, as the idle widget names it. */
    fun idlePlayer(c: Context): String? = playWith(c) ?: lastPlayer(c)

    private var artCache: Pair<String, Pair<Bitmap?, Int?>>? = null

    fun now(c: Context): NowPlaying? {
        val ctl = pick(followedControllers(c)) ?: return null
        val pkg = ctl.packageName
        if (lastPlayer(c) != pkg) Prefs.of(c).edit().putString("last_player", pkg).apply()
        val meta = ctl.metadata
        val state = ctl.playbackState
        fun text(vararg keys: String) = keys.firstNotNullOfOrNull { k -> meta?.getString(k)?.takeIf { it.isNotBlank() } } ?: ""
        val title = text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = text(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val album = text(MediaMetadata.METADATA_KEY_ALBUM)
        // Artwork and its colour are worked out once per track
        val key = "$pkg|$title|$artist|$album"
        val (art, accent) = artCache?.takeIf { it.first == key }?.second ?: run {
            val a = artwork(c, meta)
            val pair = a to a?.let { Art.accent(it) }
            artCache = key to pair
            pair
        }
        return NowPlaying(
            pkg = pkg, app = label(c, pkg), icon = appIcon(c, pkg),
            title = title, artist = artist, album = album, art = art, accent = accent,
            playing = active(state?.state),
            position = state?.position ?: 0L, updated = state?.lastPositionUpdateTime ?: 0L,
            speed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            actions = state?.actions ?: 0L,
            open = ctl.sessionActivity,
        )
    }

    private fun artwork(c: Context, meta: MediaMetadata?): Bitmap? {
        if (meta == null) return null
        for (k in listOf(MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)) {
            meta.getBitmap(k)?.let { return Art.square(it, 512) }
        }
        for (k in listOf(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)) {
            val uri = meta.getString(k)?.takeIf { it.isNotBlank() } ?: continue
            runCatching {
                val src = ImageDecoder.createSource(c.contentResolver, Uri.parse(uri))
                ImageDecoder.decodeBitmap(src) { dec, _, _ -> dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
            }.getOrNull()?.let { return Art.square(it, 512) }
        }
        return null
    }

    fun label(c: Context, pkg: String): String = runCatching {
        c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg.substringAfterLast('.'))

    fun appIcon(c: Context, pkg: String): Bitmap? = runCatching {
        val d = c.packageManager.getApplicationIcon(pkg)
        Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { d.setBounds(0, 0, 96, 96); d.draw(Canvas(it)) }
    }.getOrNull()

    /** Opens the player: its own now-playing screen if it gave one, else the app. */
    fun openAct(c: Context, np: NowPlaying?): Act? {
        val pkg = np?.pkg ?: idlePlayer(c) ?: return null
        val launch = c.packageManager.getLaunchIntentForPackage(pkg)
        if (np?.open == null && launch == null) return null
        return Act.Open(pending = np?.open, intent = launch)
    }

    fun control(c: Context, what: Control) {
        val ctl = pick(followedControllers(c))
        if (ctl == null) { start(c); return }
        val t = ctl.transportControls
        when (what) {
            Control.TOGGLE -> if (active(ctl.playbackState?.state)) t.pause() else t.play()
        }
    }

    /**
     * Nothing followed is playing or paused: start the chosen player, or the last one used. Its
     * own session first if it still has one, then its media button, as a headset would press it,
     * then the app itself. With no player known, the key goes wherever Android sends it.
     */
    private fun start(c: Context) {
        val code = KeyEvent.KEYCODE_MEDIA_PLAY
        val pkg = idlePlayer(c)
        if (pkg == null) {
            val am = c.getSystemService(AudioManager::class.java)
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            return
        }
        controllers(c).firstOrNull { it.packageName == pkg }?.let { it.transportControls.play(); return }
        @Suppress("DEPRECATION")
        val receiver = c.packageManager.queryBroadcastReceivers(Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg), 0).firstOrNull()?.activityInfo
        if (receiver != null) {
            val cn = ComponentName(receiver.packageName, receiver.name)
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                c.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(cn).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, code)))
            }
            return
        }
        c.packageManager.getLaunchIntentForPackage(pkg)?.let { runCatching { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    /** Opens Android's notification access page for this app. */
    fun accessIntent(c: Context): Intent =
        Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS")
            .putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME", component(c).flattenToString())
}

/** Artwork: squared, and its one colour worth borrowing. */
object Art {
    /** A centred square of [b], at most [max] px. */
    fun square(b: Bitmap, max: Int): Bitmap {
        // Players may hand over hardware bitmaps, which cannot be cut or read: copy first
        val soft = if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
        val side = minOf(soft.width, soft.height)
        val sq = Bitmap.createBitmap(soft, (soft.width - side) / 2, (soft.height - side) / 2, side, side)
        return if (side > max) Bitmap.createScaledBitmap(sq, max, max, true) else sq
    }

    /**
     * The artwork's most telling colour: the hue that carries the most saturated, mid-bright area.
     * Null for grey artwork, which then borrows the finish's own light instead.
     */
    fun accent(b: Bitmap): Int? {
        val src = if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
        val small = Bitmap.createScaledBitmap(src, 32, 32, true)
        val px = IntArray(32 * 32).also { small.getPixels(it, 0, 32, 0, 0, 32, 32) }
        val buckets = 24
        val score = FloatArray(buckets); val r = FloatArray(buckets); val g = FloatArray(buckets); val bl = FloatArray(buckets)
        val hsv = FloatArray(3)
        for (p in px) {
            Color.colorToHSV(p, hsv)
            val w = hsv[1] * hsv[1] * (1f - Math.abs(hsv[2] - 0.62f) * 1.4f).coerceAtLeast(0f)
            if (w <= 0.02f) continue
            val k = ((hsv[0] / 360f) * buckets).toInt().coerceIn(0, buckets - 1)
            score[k] += w; r[k] += Color.red(p) * w; g[k] += Color.green(p) * w; bl[k] += Color.blue(p) * w
        }
        val best = score.indices.maxByOrNull { score[it] } ?: return null
        if (score[best] < 18f) return null
        val s = score[best]
        return Color.rgb((r[best] / s).toInt(), (g[best] / s).toInt(), (bl[best] / s).toInt())
    }

    /**
     * [colour] tuned to read against a face: deep over light faces, luminous over dark ones. Null
     * when deepening would spoil it: yellows turn to olive, so on light faces they give way to the
     * finish's own bronze.
     */
    fun against(colour: Int, darkInk: Boolean): Int? {
        val hsv = FloatArray(3).also { Color.colorToHSV(colour, it) }
        if (darkInk && hsv[0] in 42f..78f) return null
        if (darkInk) { hsv[1] = hsv[1].coerceIn(0.45f, 0.85f); hsv[2] = 0.46f }
        else { hsv[1] = hsv[1].coerceIn(0.30f, 0.62f); hsv[2] = 0.93f }
        return Color.HSVToColor(hsv)
    }
}
