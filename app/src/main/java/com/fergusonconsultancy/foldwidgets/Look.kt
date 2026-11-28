package com.fergusonconsultancy.foldwidgets

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor

/**
 * Fold Widgets' look, shared with the rest of the family (14 Nov 2026): the finish chosen for the
 * whole app, the corners, and the images chosen for the glass on each screen. Nodo's widget follows
 * it, so one choice here dresses every widget on the home screen, Nodo's included.
 *
 * Since 15 Nov 2026 it also passes on where live mode saw Nodo's widgets (content://…look/seats),
 * so their adaptive finishes read the same ground as the Fold widgets beside them. Since 21 Nov
 * Dorso's widget follows too; each follower reads only its own widgets' places, at
 * content://…look/seats/<package> (the bare path stays Nodo's, so Nodo needs no change).
 *
 * Read-only. The chosen images are opened here, where the permission to read them was granted, and
 * handed over as a stream.
 *
 * 26 Nov 2026, one look for the whole family: every app with a widget wears it through the Kit's
 * Family (see kit Family.kt), which reads it here. Fold Widgets now finds its followers itself, hands
 * over the wallpaper its own glass reads, and versions the look with a stamp.
 */
class LookProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val c = context ?: return null
        val p = Prefs.of(c)
        // content://…look/seats is Nodo's, as it always was; content://…look/seats/<package> is that app's.
        // Where widgets sit on the home screen is shared with the family only (26 Nov 2026).
        val seg = uri.pathSegments
        if (seg.firstOrNull() == "seats") { family(c); return Look.seats(c, seg.getOrNull(1) ?: Look.NODO) }
        // The glass's picture is always handed over (26 Nov 2026): the image chosen for that screen,
        // else a still of the system wallpaper, so every follower's glass shows the same picture as
        // Fold Widgets' own without asking for all-files access itself. "stamp" versions the look.
        // A picture to hand over: the wallpaper with all-files access, or Solco's still of itself (27 Nov 2026)
        val wall = Backdrop.canReadWallpaper() || com.cerniera.kit.Backdrop.solcoShowing(c)
        return MatrixCursor(arrayOf("finish", "radius", "image_cover", "image_main", "stamp")).apply {
            addRow(arrayOf<Any>(Prefs.defaultFinish(c).name, Prefs.radius(c),
                if (p.getString("image_cover", null) != null || wall) 1 else 0, if (p.getString("image_main", null) != null || wall) 1 else 0,
                Look.stamp(c)))
        }
    }

    /**
     * content://…look/image/main or /image/cover: the picture behind that screen's glass, the image
     * chosen in Glass settings or else the system wallpaper as this app reads it. Family only.
     */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val c = context ?: return null
        if (mode != "r") throw SecurityException("Read only")
        family(c)
        val key = when (uri.pathSegments.getOrNull(1) ?: uri.lastPathSegment) { "main" -> "image_main"; "cover" -> "image_cover"; else -> return null }
        Prefs.of(c).getString(key, null)?.let { return c.contentResolver.openFileDescriptor(Uri.parse(it), "r") }
        val still = Look.wallpaperStill(c, key == "image_cover") ?: return null
        return ParcelFileDescriptor.open(still, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** The wallpaper and where widgets sit are the phone owner's: only Cerniera's own apps read them. */
    private fun family(c: Context) {
        val caller = callingPackage
        if (caller != c.packageName && !com.cerniera.kit.FamilyRules.isFamily(caller)) throw SecurityException("Shared only with Cerniera's apps")
    }

    override fun getType(uri: Uri): String? = if (uri.pathSegments.firstOrNull() == "image") "image/*" else null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}

/** A follower's widget seen by live mode: where it is, and whose it is. */
class Follower(val box: android.graphics.Rect, val pkg: String)

object Look {
    const val CHANGED = com.cerniera.kit.Family.CHANGED
    const val NODO = "com.cerniera.nodo"
    const val DORSO = "com.cerniera.dorso"
    const val RISERVA = "com.cerniera.riserva"
    /** Known followers, told even if the phone hides them from the search below. */
    private val KNOWN = listOf(NODO, DORSO, RISERVA)

    /**
     * Every app that wears this look (26 Nov 2026): every Cerniera app with a receiver for
     * [CHANGED]. It was a fixed list, and the list could miss an app, whose widget was
     * never told when the finish changed and stayed in whatever it last drew. Now an app joins by
     * declaring the receiver; nothing here has to change.
     */
    fun followers(c: Context): List<String> {
        val now = System.currentTimeMillis()
        cache?.let { (at, list) -> if (now - at < 30_000L) return list }
        val found = runCatching {
            @Suppress("DEPRECATION")
            c.packageManager.queryBroadcastReceivers(Intent(CHANGED), 0).map { it.activityInfo.packageName }
        }.getOrDefault(emptyList())
        return (found + KNOWN).filter { it != c.packageName && com.cerniera.kit.FamilyRules.isFamily(it) }.distinct()
            .filter { pkg -> runCatching { c.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false) }
            .also { cache = now to it }
    }
    @Volatile private var cache: Pair<Long, List<String>>? = null

    fun appName(c: Context, pkg: String): String = runCatching {
        c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() })

    /** The look's version: bumped on every change, so a follower never draws from a stale picture. */
    fun stamp(c: Context): Long {
        val own = Prefs.of(c).getLong(STAMP, 0L)
        // Over Solco, a new still is a new version of the look's picture too (27 Nov 2026)
        if (!com.cerniera.kit.Backdrop.solcoShowing(c)) return own
        return own + (com.cerniera.kit.SolcoStill.stamp(c, false) ?: 0L) % 1_000_000L + (com.cerniera.kit.SolcoStill.stamp(c, true) ?: 0L) % 1_000_000L
    }

    /**
     * The look (or the wallpaper behind it) changed: a new version, and every follower is told to
     * redraw. Fold Widgets' own widgets are redrawn by whoever made the change.
     */
    fun changed(c: Context) {
        Prefs.of(c).edit().putLong(STAMP, System.currentTimeMillis()).apply()
        tell(c)
    }

    /** Tells every follower to redraw (the look itself unchanged: new places only). */
    fun tell(c: Context) {
        followers(c).forEach { pkg -> runCatching { c.sendBroadcast(Intent(CHANGED).setPackage(pkg)) } }
    }

    /**
     * The system wallpaper as this app reads it, saved once per version of the look, for followers'
     * glass. Null without all-files access, when the followers take the wallpaper's colours instead.
     */
    @Synchronized fun wallpaperStill(c: Context, cover: Boolean = false): java.io.File? {
        val dir = java.io.File(c.cacheDir, "look").apply { mkdirs() }
        val solco = com.cerniera.kit.Backdrop.solcoShowing(c)
        val f = java.io.File(dir, "wall-${stamp(c)}" + (if (solco) (if (cover) "-cover" else "-main") else "") + ".png")
        if (f.length() > 0) return f
        // Over Solco, its still of the screen asked for; else the system wallpaper (27 Nov 2026)
        val bmp = (if (solco) com.cerniera.kit.SolcoStill.read(c, cover) else com.cerniera.kit.Backdrop.systemWallpaper(c)) ?: return null
        dir.listFiles()?.filter { !it.name.startsWith("wall-${stamp(c)}") }?.forEach { it.delete() }
        val tmp = java.io.File(dir, "wall.tmp")
        runCatching { tmp.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; tmp.renameTo(f) }
            .onFailure { tmp.delete(); return null }
        return f
    }

    private const val STAMP = "look_stamp"

    /**
     * Passes on what live mode saw of the followers' widgets (15 Nov 2026): where each one sits on
     * this screen, to the pixel, and where the launcher has the wallpaper and how it tints it. A
     * launcher never tells a widget where it sits, and Nodo has no eyes of its own; with these its
     * adaptive finishes read the same ground, zone by zone, as the Fold widgets beside it.
     * Returns true when something changed, and then tells the followers to redraw.
     */
    fun share(c: Context, boxes: List<Follower>, sw: Int, sh: Int, view: Live.View?): Boolean {
        // Only widgets wholly on this page: the launcher also reports the pages either side
        val here = boxes.filter { it.box.left >= 0 && it.box.top >= 0 && it.box.right <= sw && it.box.bottom <= sh }
        val enc = here.sortedWith(compareBy({ it.box.top }, { it.box.left })).joinToString(";") { "${it.pkg}@${it.box.left},${it.box.top},${it.box.width()},${it.box.height()}" } +
            "|$sw,$sh|" + (view?.encode() ?: "")
        val p = Prefs.of(c)
        if (here.isEmpty() || p.getString(SHARED, null) == enc) return false
        p.edit().putString(SHARED, enc).putLong(SHARED_AT, System.currentTimeMillis()).apply()
        tell(c)
        return true
    }

    /** What [share] last passed on for [pkg]'s widgets: one row per widget of that app seen. */
    fun seats(c: Context, pkg: String): Cursor {
        val cols = arrayOf("left", "top", "width", "height", "screen_w", "screen_h", "ox", "oy", "gain_r", "gain_g", "gain_b", "live", "at", "package")
        val out = MatrixCursor(cols)
        val p = Prefs.of(c)
        val parts = p.getString(SHARED, null)?.split("|") ?: return out
        if (parts.size < 3) return out
        val screen = parts[1].split(",").mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 } ?: return out
        val view = Live.View.decode(parts[2].ifEmpty { null })?.takeIf { it.screenW == screen[0] && it.screenH == screen[1] }
        val at = p.getLong(SHARED_AT, 0L)
        for (entry in parts[0].split(";")) {
            // "package@l,t,w,h"; an entry saved before 21 Nov has no package and was Nodo's
            val owner = if ('@' in entry) entry.substringBefore('@') else NODO
            if (owner != pkg) continue
            val b = entry.substringAfter('@').split(",").mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 } ?: continue
            out.addRow(arrayOf<Any>(b[0], b[1], b[2], b[3], screen[0], screen[1], view?.ox ?: 0f, view?.oy ?: 0f,
                view?.gain?.get(0) ?: 1f, view?.gain?.get(1) ?: 1f, view?.gain?.get(2) ?: 1f, if (view != null) 1 else 0, at, owner))
        }
        return out
    }

    private const val SHARED = "follower_seats"
    private const val SHARED_AT = "follower_seats_at"
}
