package com.fergusonconsultancy.foldwidgets

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import com.cerniera.kit.Partenza
import com.cerniera.kit.PartenzaRules

/**
 * Settings → Partenza (24 Nov 2026): the TomTom key, the buffer, where the drive starts from, what
 * it sees now, and the log to live with for a week before anything is drawn. Fold Widgets hosts
 * Partenza for the family: Momento and Lume in Fold Taskbar both read what it works out here.
 */
fun Activity.partenzaSection(column: LinearLayout, rebuild: () -> Unit) {
    val me = this
    val zone = java.time.ZoneId.systemDefault()
    column.addView(eyebrow("Partenza"))
    column.addView(label("A live \"leave by\" for your next event with a place, from today's traffic by car. It re-checks from two hours before, more often near the end, and whenever the calendar changes.",
        13f, Palette.MUTED).apply { setPadding(px(4), 0, px(4), px(10)) })

    // ---- Now
    column.addView(card().apply {
        val d = Partenza.stored(me)
        val now = System.currentTimeMillis()
        val (title, note) = when {
            !Partenza.on(me) -> "Off" to "Add a TomTom key below to start."
            !Partenza.calendarAllowed(me) -> "Waiting for the calendar" to "Allow the calendar under Access."
            d == null || now >= d.start -> "Nothing to leave for" to "No event with a place in the next day, or not checked yet. The log below says which."
            else -> PartenzaRules.line(d, zone) to
                "${d.where} · ${d.delayMin} min of the drive is traffic · ${d.bufferMin} min buffer · from ${d.from} · checked ${PartenzaRules.clock(d.checkedAt, zone)}"
        }
        addView(row(title, note, if (Partenza.on(me)) button("Check now", primary = false) {
            Partenza.schedule(me, now = true)
            android.widget.Toast.makeText(me, "Checking. The result arrives in the log in a moment.", android.widget.Toast.LENGTH_SHORT).show()
        } else null))
    })

    // ---- The key: kept on this phone only
    column.addView(card().apply {
        val key = Partenza.key(me)
        if (key != null) {
            addView(row("TomTom key", "Ending ${key.takeLast(4)}. Kept on this phone only. Partenza uses TomTom's Routing API, and its Search API when the phone can't find a place itself.",
                button("Remove", primary = false) { Partenza.setKey(me, null); rebuild() }))
        } else {
            addView(row("TomTom key", "From developer.tomtom.com: sign in, open Keys, and copy the key with the Routing and Search APIs. Paste it here; it stays on this phone.", null))
            val field = EditText(me).apply {
                hint = "Paste the key"
                setHintTextColor(Palette.MUTED); setTextColor(Palette.IVORY); textSize = 15f
                typeface = Type.sans(context, 400)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions = EditorInfo.IME_ACTION_DONE
                background = rounded(Palette.RAISED, px(14).toFloat(), Palette.HAIRLINE, px(1))
                setPadding(px(14), px(12), px(14), px(12))
            }
            val save = { val k = field.text.toString().trim(); if (k.isNotEmpty()) { Partenza.setKey(me, k); rebuild() } }
            field.setOnEditorActionListener { _, _, _ -> save(); true }
            addView(field, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8) })
            addView(button("Save key") { save() }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = px(14) })
        }
    })

    // ---- Buffer and where the drive starts
    column.addView(card().apply {
        val buffers = listOf(5, 10, 15, 20, 30)
        val b = Partenza.buffer(me)
        addView(row("Buffer", "$b min added to the drive, for parking and walking in", null))
        addView(segments(buffers.map { "$it min" }, buffers.indexOf(b).takeIf { it >= 0 } ?: 1) { i -> Partenza.setBuffer(me, buffers[i]); rebuild() },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(14) })
        val loc = Partenza.locationAllowed(me)
        addView(row("Location", "Where the drive starts. Approximate is enough.",
            if (loc) status(true) else button("Allow") { requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 31) }))
        if (loc) {
            val bg = Partenza.backgroundAllowed(me)
            addView(row("All the time", if (bg) "Re-checks start from where the phone is, even while Fold Widgets is closed"
                else "Without it, re-checks while Fold Widgets is closed start from the last place the phone was seen. Android asks for this on its own page: choose \"Allow all the time\".",
                if (bg) status(true) else button("Allow", primary = false) { requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 32) }))
        }
    })

    // ---- The log: a week of it against the real diary, before anything is drawn
    column.addView(card().apply {
        val lines = Partenza.logLines(me)
        addView(row("Log", if (lines.isEmpty()) "Nothing yet." else "Every check, newest first: the drive, the traffic, where it started, and why it ran.",
            if (lines.isEmpty()) null else button("Share", primary = false) {
                val text = lines.asReversed().joinToString("\n")
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Partenza log").putExtra(Intent.EXTRA_TEXT, text), "Share the Partenza log"))
            }))
        lines.take(12).forEach { l ->
            addView(label(l, 12.5f, Palette.IVORY).apply { setPadding(0, px(6), 0, px(6)) })
        }
        if (lines.size > 12) addView(label("${lines.size - 12} older lines: Share sends them all.", 12.5f, Palette.MUTED).apply { setPadding(0, px(6), 0, px(14)) })
    })
}
