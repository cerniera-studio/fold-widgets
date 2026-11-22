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
 * Read-only, and appearance only: nothing personal passes through it. The chosen images are opened
 * here, where the permission to read them was granted, and handed over as a stream.
 */
class LookProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val c = context ?: return null
        val p = Prefs.of(c)
        // content://…look/seats is Nodo's, as it always was; content://…look/seats/<package> is that app's
        val seg = uri.pathSegments
        if (seg.firstOrNull() == "seats") return Look.seats(c, seg.getOrNull(1) ?: Look.NODO)
        return MatrixCursor(arrayOf("finish", "radius", "image_cover", "image_main")).apply {
            addRow(arrayOf<Any>(Prefs.defaultFinish(c).name, Prefs.radius(c),
                if (p.getString("image_cover", null) != null) 1 else 0, if (p.getString("image_main", null) != null) 1 else 0))
        }
    }

    /** content://…look/image/main or /image/cover: the image chosen for that screen's glass. */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val c = context ?: return null
        if (mode != "r") throw SecurityException("Read only")
        val key = when (uri.lastPathSegment) { "main" -> "image_main"; "cover" -> "image_cover"; else -> return null }
        val chosen = Prefs.of(c).getString(key, null) ?: return null
        return c.contentResolver.openFileDescriptor(Uri.parse(chosen), "r")
    }

    override fun getType(uri: Uri): String? = if (uri.pathSegments.firstOrNull() == "image") "image/*" else null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}

/** A follower's widget seen by live mode: where it is, and whose it is. */
class Follower(val box: android.graphics.Rect, val pkg: String)

object Look {
    const val CHANGED = "com.cerniera.LOOK_CHANGED"
    const val NODO = "com.cerniera.nodo"
    const val DORSO = "com.cerniera.dorso"
    /** The apps that follow this look: Nodo (14 Nov 2026) and Dorso's nightstand (21 Nov 2026). */
    const val RISERVA = "com.cerniera.riserva"
    val FOLLOWERS = listOf(NODO, DORSO, RISERVA)

    fun appName(pkg: String) = when (pkg) { NODO -> "Nodo"; DORSO -> "Dorso"; RISERVA -> "Riserva"; else -> pkg.substringAfterLast('.') }

    /** Tells the followers the look (or the wallpaper behind it) changed, so they redraw. */
    fun changed(c: Context) {
        FOLLOWERS.forEach { pkg -> runCatching { c.sendBroadcast(Intent(CHANGED).setPackage(pkg)) } }
    }

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
        changed(c)
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
