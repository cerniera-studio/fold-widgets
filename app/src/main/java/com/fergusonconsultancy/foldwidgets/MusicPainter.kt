package com.fergusonconsultancy.foldwidgets

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader

/**
 * The music widget. Its content belongs to whatever is playing: the player's name and icon, the
 * artwork, the title and artist, and the one colour the artwork lends to the play disc and the
 * progress line.
 *
 * Concentrato by size: as the widget shrinks, things fall away in a fixed order until only the
 * vital thing is left. Album, then the player's name, then the progress line and the artist, then
 * the title, until only the artwork and the play disc remain.
 * - Poster, the large default: large artwork, the title set in Italiana beside it.
 * - Stack, the small default: artwork and words above, the line and the play disc below.
 * - Compact: artwork, title and play.
 * - Tile: the artwork alone, with play.
 * - Wide, for long thin shapes: artwork the full height on the left, everything else beside it.
 *
 * One control: play and pause (Mechack, 31 Oct). Everything else is done in the player, which any
 * other tap on the widget opens.
 */
object MusicPainter : Painter {

    override fun paint(c: Context, id: Int, canvas: Canvas, s: Surface): Scene {
        if (!Media.allowed(c)) return locked(c, canvas, s)
        val np = Media.now(c)
        val w = s.w; val h = s.h
        return when {
            minOf(w, h) < 112f -> tile(c, canvas, s, np)
            w > h * 2f -> wide(c, canvas, s, np)
            w >= 220f && h >= 220f -> poster(c, canvas, s, np)
            minOf(w, h) < 150f -> compact(c, canvas, s, np)
            else -> stack(c, canvas, s, np)       // 4x2 and 3x2 on the cover screen
        }
    }

    // ---- Arrangements ---------------------------------------------------------------------------

    /**
     * 4x2 and 3x2: the artwork and the words side by side, the line and the disc beneath. Type keeps
     * one size at every span; the title takes two lines if it needs them and never breaks a word.
     */
    private fun stack(c: Context, cv: Canvas, s: Surface, np: NowPlaying?): Scene {
        val p = 14f
        val r = 21f
        val a = minOf(s.h - 2 * p - 2 * r - 12f, s.w * 0.40f, 104f)
        val art = RectF(p, p, p + a, p + a)
        artwork(cv, art, np, s, 11f)
        val x0 = art.right + 12f
        val tw = s.w - x0 - p
        val (titleP, lines) = c.title(titleOf(np), s.ink(x0 + tw / 2, p + a / 2), tw, 2, listOf(16.5f, 15.5f, 14.5f), 600)
        val artistP = c.ink(13.5f, s.muted(x0 + tw / 2, art.bottom - 6f), 450)
        val eyebrowH = 10f * 1.4f + 7f
        val titleH = { n: Int -> n * titleP.textSize * 1.18f }
        // Whatever does not fit beside the artwork falls away: the player's name first, then a title line
        var shownLines = lines
        var showEyebrow = tw >= 72f
        fun blockH() = (if (showEyebrow) eyebrowH else 0f) + titleH(shownLines.size) + 5f + artistP.textSize * 1.15f
        if (blockH() > a) showEyebrow = false
        if (blockH() > a && shownLines.size > 1) shownLines = wrap(titleOf(np), titleP, tw, 1)
        var y = p + 1f
        if (showEyebrow) { eyebrow(c, cv, x0, y, tw, 10f, np, s); y += eyebrowH }
        for (l in shownLines) { cv.textTop(l, x0, y, titleP); y += titleP.textSize * 1.18f }
        cv.textTop(fit(artistOf(c, np), artistP, tw), x0, y + 5f, artistP)
        return foot(c, cv, s, np, p, s.w - p, s.h - p - r, r, 1.6f, 14f)
    }

    /**
     * 4x3 and larger: a poster. The artwork with the player and artist beside it, the title beneath
     * set across the full width in Italiana, as large as fits in whole words; if Italiana cannot fit
     * it without cutting, Jost takes over. The line and the disc at the foot.
     */
    private fun poster(c: Context, cv: Canvas, s: Surface, np: NowPlaying?): Scene {
        val p = 18f
        val r = 25f
        val footTop = s.h - p - 2 * r
        val a = minOf((s.w - 2 * p) * 0.45f, s.h * 0.42f, 140f)
        val art = RectF(p, p, p + a, p + a)
        artwork(cv, art, np, s, 14f)
        val x0 = art.right + 14f
        val tw = s.w - x0 - p
        eyebrow(c, cv, x0, p + 2f, tw, 10f, np, s)
        var y = p + 2f + 10f * 1.4f + 10f
        val artistP = c.ink(13.5f, s.muted(x0 + tw / 2, y + 8f), 450)
        val artistRoom = ((art.bottom - y) / (artistP.textSize * 1.3f)).toInt().coerceIn(0, 3)
        if (artistRoom > 0) for (line in wrap(artistOf(c, np), artistP, tw, artistRoom)) {
            cv.textTop(line, x0, y, artistP); y += artistP.textSize * 1.3f
        }
        if (np != null && np.album.isNotEmpty() && np.album != np.title && y + 12f * 1.3f < art.bottom) {
            val albumP = c.ink(12f, s.muted(x0 + tw / 2, y + 12f), 450)
            cv.textTop(fit(np.album, albumP, tw), x0, y + 3f, albumP)
        }

        // The title across the full width, in the room between the artwork and the foot
        val titleTop = art.bottom + 14f
        val room = footTop - 10f - titleTop
        val width = s.w - 2 * p
        val colour = s.ink(s.w / 2, titleTop + room / 2)
        val text = titleOf(np)
        var chosen: Pair<android.text.TextPaint, List<String>>? = null
        for (size in listOf(28f, 26f, 24f, 22f, 20f)) {
            val lines = (room / (size * 1.1f)).toInt().coerceIn(1, 3)
            val tp = c.ink(size, colour, display = true)
            if (fitsWhole(text, tp, width, lines)) { chosen = tp to wrap(text, tp, width, lines); break }
        }
        if (chosen == null) {
            val lines = (room / (17f * 1.2f)).toInt().coerceIn(1, 3)
            chosen = c.title(text, colour, width, lines, listOf(17f, 16f, 15f), 600)
        }
        val (tp, lines) = chosen
        val lead = if (tp.typeface == Type.display(c)) 1.1f else 1.2f
        var ty = titleTop
        for (l in lines) { cv.textTop(l, p, ty, tp); ty += tp.textSize * lead }
        return foot(c, cv, s, np, p, s.w - p, s.h - p - r, r, 1.6f, 18f)
    }

    private fun wide(c: Context, cv: Canvas, s: Surface, np: NowPlaying?): Scene {
        val k = (s.h / 110f).coerceIn(0.7f, 1.6f)
        val p = 12f * k
        val a = s.h - 2 * p
        val art = RectF(p, p, p + a, p + a)
        artwork(cv, art, np, s, 10f * k)
        val x0 = art.right + 14f * k
        val tw = s.w - x0 - p
        var y = p + 1f * k
        if (s.h >= 128f) { eyebrow(c, cv, x0, y, tw, 9.5f * k, np, s); y += 9.5f * k * 1.4f + 8f * k }
        val titleP = c.ink(15f * k, s.ink(x0 + tw / 2, y + 8f * k), 560)
        cv.textTop(fit(titleOf(np), titleP, tw), x0, y, titleP); y += titleP.textSize * 1.3f
        val artistP = c.ink(12.5f * k, s.muted(x0 + tw / 2, y + 6f * k))
        cv.textTop(fit(artistOf(c, np), artistP, tw), x0, y, artistP); y += artistP.textSize * 1.2f

        val r = 18f * k
        return foot(c, cv, s, np, x0, s.w - p, s.h - p - r, r, 1.4f * k, 12f * k)
    }

    private fun compact(c: Context, cv: Canvas, s: Surface, np: NowPlaying?): Scene {
        val k = (minOf(s.w, s.h) / 130f).coerceIn(0.8f, 1.2f)
        val p = 12f * k
        val a = minOf(s.h * 0.5f, s.w * 0.44f)
        val art = RectF(p, p, p + a, p + a)
        artwork(cv, art, np, s, 9f * k)
        val r = minOf(s.w, s.h) * 0.17f
        val cx = s.w - p - r; val cy = s.h - p - r
        val titleW = s.w - 2 * p - (if (cy - r < art.bottom + 30f * k) 2 * r + 8f * k else 0f)
        val titleP = c.ink(13.5f * k, s.ink(p + titleW / 2, art.bottom + 16f * k), 560)
        var y = art.bottom + 10f * k
        val room = ((s.h - p - y) / (titleP.textSize * 1.2f)).toInt().coerceIn(1, 2)
        for (line in wrap(titleOf(np), titleP, titleW, room)) { cv.textTop(line, p, y, titleP); y += titleP.textSize * 1.2f }
        disc(cv, s, np, cx, cy, r, r * 0.78f)
        val hits = ArrayList<Hit>()
        Media.openAct(c, np)?.let { hits.add(Hit(RectF(0f, 0f, s.w, s.h), it, "Open ${np?.app ?: "player"}")) }
        hits += Hit(RectF(cx - r * 1.3f, cy - r * 1.3f, s.w, s.h), Act.Send(TapReceiver.ACTION_PLAY), if (np?.playing == true) "Pause" else "Play")
        return Scene(hits)
    }

    private fun tile(c: Context, cv: Canvas, s: Surface, np: NowPlaying?): Scene {
        val full = RectF(0f, 0f, s.w, s.h)
        val art = np?.art
        if (art != null) {
            cv.save(); cv.clipPath(s.shape)
            cv.drawBitmap(art, null, cover(art, full), Paint(Paint.FILTER_BITMAP_FLAG))
            // A soft fall of shade, so the disc reads on any artwork
            cv.drawRect(full, Paint().apply {
                shader = LinearGradient(0f, s.h * 0.35f, 0f, s.h, 0x00000000, 0x8C000000.toInt(), Shader.TileMode.CLAMP)
            })
            cv.restore()
        } else Glyphs.record(cv, full, s.ink(s.w / 2, s.h / 2))
        val r = minOf(s.w, s.h) * 0.19f
        val cx = s.w - r - minOf(s.w, s.h) * 0.1f
        val cy = s.h - r - minOf(s.w, s.h) * 0.1f
        disc(cv, s, np, cx, cy, r, r * 0.8f)
        return Scene(listOf(Hit(full, Act.Send(TapReceiver.ACTION_PLAY), if (np?.playing == true) "Pause" else "Play")))
    }

    private fun locked(c: Context, cv: Canvas, s: Surface): Scene {
        val k = (minOf(s.w, s.h) / 164f).coerceIn(0.6f, 1.6f)
        val title = c.ink(24f * k, s.ink(s.w / 2, s.h / 2 - 10f * k), display = true).apply { textAlign = Paint.Align.CENTER }
        val note = c.ink(11.5f * k, s.muted(s.w / 2, s.h / 2 + 14f * k)).apply { textAlign = Paint.Align.CENTER }
        cv.drawText("Music", s.w / 2, s.h / 2 - 4f * k, title)
        wrap("Tap to let the widget follow your player", note, s.w - 32f * k, 2).forEachIndexed { i, l ->
            cv.drawText(l, s.w / 2, s.h / 2 + 18f * k + i * note.textSize * 1.3f, note)
        }
        val open = Intent(c, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return Scene(listOf(Hit(RectF(0f, 0f, s.w, s.h), Act.Open(intent = open), "Allow music access")))
    }

    // ---- Parts ----------------------------------------------------------------------------------

    private fun titleOf(np: NowPlaying?) = np?.title?.takeIf { it.isNotEmpty() } ?: "Nothing playing"

    private fun artistOf(c: Context, np: NowPlaying?): String {
        if (np != null) return np.artist.ifEmpty { np.album.ifEmpty { np.app } }
        val last = Media.lastPlayer(c)?.let { Media.label(c, it) }
        return if (last != null) "Play to resume $last" else "Start any player"
    }

    /** The player's icon and name, small and tracked: it says where the music comes from. */
    private fun eyebrow(c: Context, cv: Canvas, x: Float, top: Float, width: Float, size: Float, np: NowPlaying?, s: Surface) {
        val pkg = np?.pkg ?: Media.lastPlayer(c) ?: return
        val name = (np?.app ?: Media.label(c, pkg)).uppercase()
        val icon = np?.icon ?: Media.appIcon(c, pkg)
        var tx = x
        val iconSize = size * 1.35f
        if (icon != null) {
            val r = RectF(x, top + size * 0.5f - iconSize / 2, x + iconSize, top + size * 0.5f + iconSize / 2)
            cv.save(); cv.clipPath(Path().apply { addOval(r, Path.Direction.CW) })
            cv.drawBitmap(icon, null, r, Paint(Paint.FILTER_BITMAP_FLAG)); cv.restore()
            tx += iconSize + size * 0.55f
        }
        val p = c.ink(size, s.muted(x + width / 2, top + size / 2), 500, tracking = 0.14f)
        cv.textTop(fit(name, p, x + width - tx), tx, top + size * 0.5f - capHeight(p) / 2, p)
    }

    /** The artwork, or a record where there is none. */
    private fun artwork(cv: Canvas, r: RectF, np: NowPlaying?, s: Surface, corner: Float) {
        val path = Path().apply { addRoundRect(r, corner, corner, Path.Direction.CW) }
        val art = np?.art
        if (art != null) {
            cv.save(); cv.clipPath(path)
            cv.drawBitmap(art, null, r, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            cv.restore()
        } else {
            cv.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = s.faint(r.centerX(), r.centerY()) })
            Glyphs.record(cv, r, s.ink(r.centerX(), r.centerY()))
        }
        cv.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = s.hairline; color = s.faint(r.centerX(), r.centerY())
        })
    }

    /** The colour the artwork lends, tuned to the face it sits on; the finish's own light if none. */
    private fun accent(s: Surface, np: NowPlaying?, x: Float, y: Float): Int =
        np?.accent?.let { Art.against(it, s.darkInk(x, y)) } ?: s.accent(x, y)

    private fun progress(s: Surface, np: NowPlaying?, r: RectF): Line? {
        if (np == null || np.duration <= 0) return null
        return Line(r, np.progress(), s.faint(r.centerX(), r.centerY()), accent(s, np, r.centerX(), r.centerY()))
    }

    /** The play disc: the one filled shape, in the artwork's colour. */
    private fun disc(cv: Canvas, s: Surface, np: NowPlaying?, cx: Float, cy: Float, r: Float, glyph: Float) {
        val fill = accent(s, np, cx, cy)
        cv.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
        val mark = if (lum(fill) > 0.55f) 0xFF15191A.toInt() else Palette.IVORY
        if (np?.playing == true) Glyphs.pause(cv, cx, cy, glyph * 0.9f, mark) else Glyphs.play(cv, cx + glyph * 0.04f, cy, glyph, mark)
    }

    /**
     * The foot of the widget: the progress line running from [left] to the play disc, which sits at
     * [right] on the line's centre [cy]. The disc is the only control; every other tap opens the
     * player, where anything else can be done.
     */
    private fun foot(c: Context, cv: Canvas, s: Surface, np: NowPlaying?, left: Float, right: Float, cy: Float, r: Float,
                     thickness: Float, gap: Float): Scene {
        val cx = right - r
        val line = progress(s, np, RectF(left, cy - thickness, cx - r - gap, cy + thickness))
        disc(cv, s, np, cx, cy, r, r * 0.78f)
        val hits = ArrayList<Hit>()
        Media.openAct(c, np)?.let { hits.add(Hit(RectF(0f, 0f, s.w, s.h), it, "Open ${np?.app ?: "player"}")) }
        val reach = maxOf(r * 1.3f, 24f)
        hits += Hit(RectF(cx - reach, cy - reach, cx + reach, cy + reach), Act.Send(TapReceiver.ACTION_PLAY), if (np?.playing == true) "Pause" else "Play")
        return Scene(hits, line)
    }

    /** [b] scaled to cover [r], centred. */
    private fun cover(b: Bitmap, r: RectF): RectF {
        val sc = maxOf(r.width() / b.width, r.height() / b.height)
        val w = b.width * sc; val h = b.height * sc
        return RectF(r.centerX() - w / 2, r.centerY() - h / 2, r.centerX() + w / 2, r.centerY() + h / 2)
    }
}
