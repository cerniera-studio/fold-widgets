package com.fergusonconsultancy.foldwidgets

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.widget.LinearLayout
import java.io.File

/**
 * Copertina (26 Nov 2026, from App Ideas 26 Nov): while music plays, Solco's sky takes the colours
 * of the sleeve. This app already holds the player's session (notification access, for the music
 * widget), so it works out two colours from the artwork once per track and shares them, with
 * whether anything is playing, at content://com.cerniera.copertina/now. Solco reads them there.
 *
 * Step 1, per claude/copertina-design.md: share the colours and log what artwork each player
 * actually hands over (which key, what size), so Spotify and Poweramp can be checked on the Fold.
 */
object Copertina {
    const val AUTHORITY = "com.cerniera.copertina"
    const val CHANGED = "com.cerniera.COPERTINA_CHANGED"
    const val SOLCO = "com.fergusonconsultancy.solco"
    val COLUMNS = arrayOf("playing", "a", "b", "player", "at")

    private const val KEY = "copertina_now"
    private const val LOG = "copertina.log"
    private const val KEEP = 300

    /** What the artwork gave, set by [Media] when it reads a new track's art: the key and the art's own size. */
    @Volatile var source = ""

    class Now(val playing: Boolean, val a: Int?, val b: Int?, val player: String, val at: Long)

    fun stored(c: Context): Now? {
        val s = Prefs.of(c).getString(KEY, null) ?: return null
        val p = s.split("|")
        if (p.size != 5) return null
        return Now(p[0] == "1", p[1].toIntOrNull(16), p[2].toIntOrNull(16), p[3], p[4].toLongOrNull() ?: 0L)
    }

    /**
     * Called once the players' reports settle (MediaListener's redraw). Cheap when nothing changed:
     * the artwork and its colours are worked out once per track, in [Media.now].
     */
    /** The track that was given a moment for its artwork to arrive (players often send the words first). */
    @Volatile private var waited = ""
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun update(c: Context) {
        val np = Media.now(c)
        val playing = np?.playing == true
        // 26 Nov log: Spotify sends a new track without artwork, then the artwork a moment later.
        // Wait up to 2.5 s for it rather than dropping Solco back to the finish and tinting again.
        val track = np?.let { "${it.pkg}|${it.title}|${it.artist}|${it.album}" } ?: ""
        if (playing && np?.art == null && waited != track) {
            waited = track
            val app = c.applicationContext
            main.postDelayed({ Widgets.later { runCatching { update(app) } } }, 2500)
            return
        }
        val pal = if (playing) np?.art?.let { palette(it) } else null
        val a = pal?.get(0); val b = pal?.get(1)
        val player = np?.pkg ?: ""
        val was = stored(c)
        if (was != null && was.playing == playing && was.a == a && was.b == b && was.player == player) return
        val now = Now(playing, a, b, player, System.currentTimeMillis())
        Prefs.of(c).edit().putString(KEY, listOf(if (playing) "1" else "0", a?.let { hex(it) } ?: "", b?.let { hex(it) } ?: "", player, now.at).joinToString("|")).apply()
        // A new track (or a start or stop): one line, so what each player hands over can be read back
        log(c, buildString {
            append(if (playing) "playing" else "stopped")
            if (player.isNotEmpty()) append(" · ").append(player)
            // Which track, so two sleeves giving the same colours can be told apart
            if (playing && np != null) append(" · ").append(listOf(np.artist, np.title).filter { it.isNotBlank() }.joinToString(" – ").take(60))
            if (playing) {
                append(" · ").append(if (np?.art == null) "no artwork" else source.ifEmpty { "artwork" })
                append(" · ").append(if (a == null) "grey, no colours" else "#${hex(a)} #${hex(b ?: a)}")
            }
        })
        // Solco looks again (only ever a re-read of the provider)
        runCatching { c.sendBroadcast(Intent(CHANGED).setPackage(SOLCO)) }
    }

    /** The artwork's two colours, 0xRRGGBB, or null for grey artwork. */
    fun palette(art: Bitmap): IntArray? {
        val src = if (art.config == Bitmap.Config.HARDWARE) art.copy(Bitmap.Config.ARGB_8888, false) else art
        val small = Bitmap.createScaledBitmap(src, 32, 32, true)
        val px = IntArray(32 * 32).also { small.getPixels(it, 0, 32, 0, 0, 32, 32) }
        return CopertinaRules.palette(px)
    }

    private fun hex(c: Int) = "%06X".format(c and 0xFFFFFF)

    // ---- The log: one line a track, newest last in the file
    private val stamp = java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm")

    @Synchronized fun log(c: Context, line: String) = runCatching {
        val f = File(c.filesDir, LOG)
        val lines = (if (f.exists()) f.readLines() else emptyList()) + "${java.time.LocalDateTime.now().format(stamp)}  $line"
        f.writeText(lines.takeLast(KEEP).joinToString("\n") + "\n")
    }

    fun logLines(c: Context): List<String> = runCatching {
        File(c.filesDir, LOG).takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }?.asReversed()
    }.getOrNull() ?: emptyList()
}

/**
 * The colour rules, kept free of Android so they can be checked off the phone. Hues are scored by
 * how much saturated, mid-bright area carries them (the music widget's accent rule), in 24 buckets.
 * The first colour is the strongest hue; the second is the strongest hue at least 60° away that
 * holds a fifth as much, or else the first hue's darker side, so a one-colour sleeve stays one colour.
 * Even a small patch of colour on a dark sleeve counts (see [GREY]).
 */
object CopertinaRules {
    private const val BUCKETS = 24
    /**
     * Below this the artwork is grey: Solco keeps the real sky. 4 (26 Nov, was 18): a patch of
     * strong colour about a hundredth of a dark sleeve is enough, so a quiet song on a black cover
     * still shows in the grain; a black-and-white photo's compression noise scores about 0.2.
     */
    private const val GREY = 4f

    fun palette(px: IntArray): IntArray? {
        val score = FloatArray(BUCKETS); val r = FloatArray(BUCKETS); val g = FloatArray(BUCKETS); val bl = FloatArray(BUCKETS)
        val dark = FloatArray(BUCKETS); val dr = FloatArray(BUCKETS); val dg = FloatArray(BUCKETS); val db = FloatArray(BUCKETS)
        val hsv = FloatArray(3)
        for (p in px) {
            val pr = (p shr 16) and 0xFF; val pg = (p shr 8) and 0xFF; val pb = p and 0xFF
            hsv(pr, pg, pb, hsv)
            val k = ((hsv[0] / 360f) * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)
            val w = hsv[1] * hsv[1] * (1f - Math.abs(hsv[2] - 0.62f) * 1.4f).coerceAtLeast(0f)
            if (w > 0.02f) { score[k] += w; r[k] += pr * w; g[k] += pg * w; bl[k] += pb * w }
            // The same hue in shadow, for a one-colour sleeve's second colour
            if (hsv[1] > 0.25f && hsv[2] in 0.12f..0.45f) { dark[k] += 1f; dr[k] += pr.toFloat(); dg[k] += pg.toFloat(); db[k] += pb.toFloat() }
        }
        val a = score.indices.maxByOrNull { score[it] } ?: return null
        if (score[a] < GREY) return null
        fun mean(k: Int) = rgb(r[k] / score[k], g[k] / score[k], bl[k] / score[k])
        val first = mean(a)
        val far = score.indices.filter { dist(it, a) >= 4 && score[it] >= score[a] * 0.2f }.maxByOrNull { score[it] }
        val second = when {
            far != null -> mean(far)
            dark[a] >= 12f -> rgb(dr[a] / dark[a], dg[a] / dark[a], db[a] / dark[a])
            else -> first
        }
        return intArrayOf(first, second)
    }

    /** Buckets apart round the colour wheel. */
    private fun dist(i: Int, j: Int): Int { val d = Math.abs(i - j); return minOf(d, BUCKETS - d) }

    private fun rgb(r: Float, g: Float, b: Float) =
        (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)

    /** RGB 0..255 to hue (0..360), saturation and value (0..1), as Android's Color.RGBToHSV. */
    fun hsv(r: Int, g: Int, b: Int, out: FloatArray) {
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = (mx - mn).toFloat()
        out[2] = mx / 255f
        out[1] = if (mx == 0) 0f else d / mx
        out[0] = when {
            d == 0f -> 0f
            mx == r -> (60f * ((g - b) / d) + 360f) % 360f
            mx == g -> 60f * ((b - r) / d) + 120f
            else -> 60f * ((r - g) / d) + 240f
        }
    }
}

/** Shares the two colours with Solco. Only Cerniera's own apps may read them (as Partenza). */
class CopertinaProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val c = context ?: return null
        val caller = callingPackage
        if (caller != c.packageName && caller != Copertina.SOLCO) throw SecurityException("Copertina is shared only with Cerniera's apps")
        return MatrixCursor(Copertina.COLUMNS).apply {
            Copertina.stored(c)?.let { n -> addRow(arrayOf<Any?>(if (n.playing) 1 else 0, n.a, n.b, n.player, n.at)) }
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}

/** Settings → Copertina: what is being shared now, and the log of what each player handed over. */
fun Activity.copertinaSection(column: LinearLayout) {
    val me = this
    column.addView(eyebrow("Copertina"))
    column.addView(label("While music plays, Solco's sky takes the colours of the sleeve. This app reads the artwork and hands Solco two colours; the strength is set in Solco.",
        13f, Palette.MUTED).apply { setPadding(px(4), 0, px(4), px(10)) })
    column.addView(card().apply {
        val n = Copertina.stored(me)
        val now = when {
            !Media.allowed(me) -> "Needs notification access (the music widget's)"
            n == null || !n.playing -> "Nothing playing"
            n.a == null -> "Grey artwork: Solco keeps the real sky"
            else -> "#%06X  #%06X".format(n.a, n.b ?: n.a)
        }
        addView(row("Now", now, null))
        val lines = Copertina.logLines(me)
        addView(row("Log", if (lines.isEmpty()) "Nothing yet. Play something with artwork." else "Each track, newest first: the player, which artwork key it gave and its size, and the two colours.",
            if (lines.isEmpty()) null else button("Share", primary = false) {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Copertina log").putExtra(Intent.EXTRA_TEXT, lines.asReversed().joinToString("\n")), "Share the Copertina log"))
            }))
        lines.take(8).forEach { l -> addView(label(l, 12.5f, Palette.IVORY).apply { setPadding(0, px(6), 0, px(6)) }) }
        if (lines.size > 8) addView(label("${lines.size - 8} older lines: Share sends them all.", 12.5f, Palette.MUTED).apply { setPadding(0, px(6), 0, px(14)) })
    })
}
