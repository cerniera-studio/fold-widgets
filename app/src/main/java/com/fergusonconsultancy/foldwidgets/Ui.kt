package com.fergusonconsultancy.foldwidgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The settings screens' kit, in the family's palette: nothing falls back to a stock grey control. */
fun Context.px(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

fun rounded(fill: Int, radius: Float, stroke: Int = 0, strokeW: Int = 0) = GradientDrawable().apply {
    setColor(fill); cornerRadius = radius
    if (strokeW > 0) setStroke(strokeW, stroke)
}

fun Context.label(s: CharSequence, size: Float, colour: Int = Palette.IVORY, weight: Int = 400, display: Boolean = false) =
    TextView(this).apply {
        text = s; textSize = size; setTextColor(colour)
        typeface = if (display) Type.display(context) else Type.sans(context, weight)
        includeFontPadding = false
        setLineSpacing(0f, 1.28f)
    }

/** Small tracked capitals in champagne that head a section. */
fun Context.eyebrow(s: String) = label(s.uppercase(), 11.5f, Palette.CHAMPAGNE, 500).apply {
    letterSpacing = 0.2f
    setPadding(px(4), px(30), 0, px(10))
}

/** A raised card holding rows. */
fun Context.card(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(Palette.SURFACE, px(20).toFloat(), Palette.HAIRLINE, px(1))
    setPadding(px(18), px(6), px(18), px(6))
}

/** A row: a title and a note on the left, an action on the right. */
fun Context.row(title: String, note: String, action: View?): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(0, px(14), 0, px(14))
    addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(title, 16f, Palette.IVORY, 500))
        addView(label(note, 13f, Palette.MUTED).apply { setPadding(0, px(4), 0, 0) })
    }, LinearLayout.LayoutParams(0, -2, 1f))
    if (action != null) addView(action, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(14) })
}

/** The one main action of a row: champagne on smoked glass. */
fun Context.button(text: String, primary: Boolean = true, onClick: () -> Unit) = label(text, 14f, if (primary) Palette.GROUND else Palette.IVORY, 500).apply {
    gravity = Gravity.CENTER
    background = if (primary) rounded(Palette.CHAMPAGNE, px(18).toFloat()) else rounded(Palette.RAISED, px(18).toFloat(), Palette.HAIRLINE, px(1))
    setPadding(px(16), px(10), px(16), px(10))
    setOnClickListener { onClick() }
}

/** A status: a small dot and a word, green when done. */
fun Context.status(done: Boolean, word: String = if (done) "On" else "Off") = label(word, 13f, if (done) Palette.GOOD else Palette.MUTED, 500).apply {
    setCompoundDrawablesRelativeWithIntrinsicBounds(GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(if (done) Palette.GOOD else Palette.MUTED); setSize(px(7), px(7))
    }, null, null, null)
    compoundDrawablePadding = px(8)
}

fun Context.screen(): Pair<ScrollView, LinearLayout> {
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(20), px(40), px(20), px(48))
    }
    val scroll = ScrollView(this).apply {
        setBackgroundColor(Palette.GROUND)
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(FrameLayout(context).apply {
            addView(column, FrameLayout.LayoutParams(minOf(resources.displayMetrics.widthPixels, px(620)), -2, Gravity.CENTER_HORIZONTAL))
        })
    }
    return scroll to column
}

/**
 * A finish shown as a tile of itself: a small widget over a ground that is bright on one side and
 * dark on the other, so an adaptive finish shows both its faces at once. The chosen one is ringed.
 */
class FinishTile(c: Context, val finish: Finish?, var chosen: Boolean, onPick: (Finish?) -> Unit) : LinearLayout(c) {
    private val picture = object : View(c) {
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f) return
            // The ground: warm stone fading to a dark photograph-like green
            val ground = Bitmap.createBitmap(48, 40, Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawRect(0f, 0f, 48f, 40f, Paint().apply {
                    shader = LinearGradient(0f, 0f, 48f, 40f, intArrayOf(0xFFEDE6D8.toInt(), 0xFFC9B79A.toInt(), 0xFF2B3A2F.toInt(), 0xFF101613.toInt()),
                        floatArrayOf(0f, 0.42f, 0.58f, 1f), Shader.TileMode.CLAMP)
                })
            }
            canvas.drawBitmap(ground, null, RectF(0f, 0f, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
            val f = finish ?: Prefs.defaultFinish(context)
            val d = resources.displayMetrics.density
            val inset = 12f * d
            canvas.save(); canvas.translate(inset, inset); canvas.scale(d, d)
            val sw = (w - 2 * inset) / d; val sh = (h - 2 * inset) / d
            val s = Surface(f, Ground.of(ground, 0, true), sw, sh, 14f, 1f / d)
            s.paint(canvas)
            val p = context.ink(15f, s.ink(sw * 0.25f, sh * 0.5f), 500)
            canvas.drawText("Aa", 10f, sh / 2 + 5f, p)
            p.color = s.ink(sw * 0.75f, sh * 0.5f)
            canvas.drawText("Aa", sw / 2 + 10f, sh / 2 + 5f, p)
            canvas.restore()
            if (chosen) canvas.drawRoundRect(RectF(1.5f * d, 1.5f * d, w - 1.5f * d, h - 1.5f * d), 16f * d, 16f * d,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; color = Palette.CHAMPAGNE })
        }
    }.apply {
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, 16f * v.resources.displayMetrics.density)
        }
    }

    init {
        orientation = VERTICAL
        addView(picture, LayoutParams(-1, c.px(84)))
        addView(c.label(finish?.short ?: "Default", 12.5f, if (chosen) Palette.CHAMPAGNE else Palette.MUTED, 500).apply {
            setPadding(c.px(2), c.px(8), 0, 0)
        })
        setOnClickListener { onPick(finish) }
    }
}

/** Finish tiles three to a row. */
fun Context.finishGrid(options: List<Finish?>, isChosen: (Finish?) -> Boolean, onPick: (Finish?) -> Unit): LinearLayout {
    val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val tiles = ArrayList<FinishTile>()
    lateinit var pick: (Finish?) -> Unit
    pick = { f ->
        onPick(f)
        tiles.forEach { t -> t.chosen = isChosen(t.finish); t.getChildAt(0).invalidate()
            (t.getChildAt(1) as TextView).setTextColor(if (t.chosen) Palette.CHAMPAGNE else Palette.MUTED) }
    }
    options.chunked(3).forEach { chunk ->
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chunk.forEachIndexed { i, f ->
            val t = FinishTile(this, f, isChosen(f)) { pick(it) }
            tiles += t
            line.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = px(10) })
        }
        repeat(3 - chunk.size) { line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = px(10) }) }
        grid.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(14) })
    }
    return grid
}
