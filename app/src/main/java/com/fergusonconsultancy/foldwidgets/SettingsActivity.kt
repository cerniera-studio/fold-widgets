package com.fergusonconsultancy.foldwidgets

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

/**
 * Settings, applying the doctrine to itself: one main action per row, a permission shows its state
 * and only asks when something is missing, choices you compare are tiles of the thing itself.
 */
class SettingsActivity : Activity() {

    private lateinit var column: LinearLayout

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        window.statusBarColor = Palette.GROUND
        window.navigationBarColor = Palette.GROUND
        val (scroll, col) = screen()
        column = col
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        build()
        Widgets.later { Widgets.renderAll(this) }
        RefreshJob.schedule(this)
        Clockwork.arm(this)
    }

    private fun build() {
        column.removeAllViews()
        column.addView(label("Fold Widgets", 36f, Palette.IVORY, display = true))
        column.addView(label("Music, agenda and weather, in the finishes of the taskbar", 14f, Palette.MUTED).apply { setPadding(0, px(8), 0, 0) })

        // ---- Access
        column.addView(eyebrow("Access"))
        column.addView(card().apply {
            val music = Media.allowed(this@SettingsActivity)
            addView(row("Music", "Follows Spotify, Poweramp or any player. Nothing is read from your notifications.",
                if (music) status(true) else button("Allow") { startActivity(Media.accessIntent(this@SettingsActivity)) }))
            val cal = Agenda.allowed(this@SettingsActivity)
            addView(row("Calendar", "The agenda reads your calendars on the phone",
                if (cal) status(true) else button("Allow") { requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 1) }))
            val wall = Backdrop.canReadWallpaper()
            addView(row("Glass", "The glass reads your wallpaper. Android only lets an app read it with all-files access.",
                if (wall) status(true) else button("Allow") {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                }))
        })

        // ---- Finish
        column.addView(eyebrow("Finish"))
        column.addView(finishGrid(Finish.entries.toList(), { it == Prefs.defaultFinish(this) }) { f ->
            if (f != null) Prefs.setDefaultFinish(this, f)
            Widgets.later { Widgets.renderAll(this) }
        })
        column.addView(label("Every widget wears this unless it has its own. To give one widget its own finish, hold it on the home screen and open its settings.",
            13f, Palette.MUTED).apply { setPadding(px(4), 0, px(4), 0) })

        // ---- Corners
        column.addView(eyebrow("Corners"))
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf("Square" to 0, "Soft" to 14, "Round" to 24, "Full" to 32).forEachIndexed { i, (name, dp) ->
                val on = Prefs.radius(this@SettingsActivity) == dp
                addView(button(name, primary = on) {
                    Prefs.setRadius(this@SettingsActivity, dp); build(); Widgets.later { Widgets.renderAll(this@SettingsActivity) }
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = px(8) })
            }
        })

        // ---- Weather
        column.addView(eyebrow("Weather"))
        column.addView(card().apply {
            val place = Weather.place(this@SettingsActivity)
            addView(row(place?.name ?: "No place yet", if (place == null) "Search below, or use where the phone is" else "Weather from Open-Meteo, every half hour",
                button("Use my location", primary = place == null) { useLocation() }))
            val field = EditText(this@SettingsActivity).apply {
                hint = "Search a town or city"
                setHintTextColor(Palette.MUTED); setTextColor(Palette.IVORY); textSize = 15f
                typeface = Type.sans(context, 400)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                background = rounded(Palette.RAISED, px(14).toFloat(), Palette.HAIRLINE, px(1))
                setPadding(px(14), px(12), px(14), px(12))
            }
            val results = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL }
            field.setOnEditorActionListener { v, _, _ -> search(v.text.toString(), results); true }
            addView(field, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8) })
            addView(results)
        })

        // ---- Glass
        column.addView(eyebrow("Glass"))
        column.addView(card().apply {
            val seats = Kind.entries.flatMap { Widgets.ids(this@SettingsActivity, it).toList() }
            val seated = seats.count { Backdrop.seat(this@SettingsActivity, it) != null }
            addView(row("Seating", if (seats.isEmpty()) "Add a widget to your home screen first"
                else "$seated of ${seats.size} widgets know where they sit. Tap any part of a widget once and its glass lines up with the wallpaper behind it.",
                if (seated > 0) button("Reset", primary = false) {
                    seats.forEach { Prefs.of(this@SettingsActivity).edit().remove("seat_$it").apply() }
                    build(); Widgets.later { Widgets.renderAll(this@SettingsActivity) }
                } else null))
            for ((key, name) in listOf("image_cover" to "Cover screen image", "image_main" to "Main screen image")) {
                val set = Prefs.of(this@SettingsActivity).getString(key, null) != null
                addView(row(name, if (set) "Your own image is used behind the glass on this screen"
                    else "Uses the system wallpaper. If the glass shows the wrong picture here, choose the same image you use as this screen's wallpaper.",
                    if (set) button("Clear", primary = false) { Prefs.of(this@SettingsActivity).edit().remove(key).apply(); Backdrop.invalidate(); build(); Widgets.later { Widgets.renderAll(this@SettingsActivity) } }
                    else button("Choose", primary = false) { pickImage(key) }))
            }
        })

        column.addView(eyebrow("How it works"))
        column.addView(label(NOTES, 13.5f, Palette.MUTED).apply { setPadding(px(4), 0, px(4), 0) })
    }

    private fun search(name: String, into: LinearLayout) {
        if (name.isBlank()) return
        into.removeAllViews()
        into.addView(label("Searching…", 13f, Palette.MUTED).apply { setPadding(px(4), px(8), 0, px(8)) })
        Thread {
            val found = runCatching { Weather.search(name.trim()) }
            runOnUiThread {
                into.removeAllViews()
                val list = found.getOrNull()
                if (list.isNullOrEmpty()) {
                    into.addView(label(if (found.isFailure) "Couldn't reach Open-Meteo. Check the connection." else "No places called $name", 13f, Palette.MUTED)
                        .apply { setPadding(px(4), px(8), 0, px(8)) })
                    return@runOnUiThread
                }
                list.forEach { p ->
                    into.addView(row(p.name, p.detail, button("Use", primary = false) { choose(p) }))
                }
            }
        }.start()
    }

    private fun choose(p: Place) {
        Weather.setPlace(this, p)
        RefreshJob.schedule(this)
        Thread {
            runCatching { Weather.refresh(this) }
            Widgets.renderAll(this, Kind.WEATHER)
        }.start()
        build()
    }

    private fun useLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 2); return
        }
        Thread {
            val here = Weather.here(this)
            // Name the spot by the nearest town Open-Meteo knows
            val named = here?.let { h ->
                runCatching { reverseName(h) }.getOrNull()?.let { Place(it, h.lat, h.lon) } ?: Place("Here", h.lat, h.lon)
            }
            runOnUiThread {
                if (named == null) Toast.makeText(this, "No recent location. Open Maps once, then try again.", Toast.LENGTH_LONG).show()
                else choose(named)
            }
        }.start()
    }

    /**
     * Open-Meteo's geocoder searches by name only, so the spot is named from the phone's own
     * geocoder when it has one.
     */
    private fun reverseName(p: Place): String? {
        @Suppress("DEPRECATION")
        val list = android.location.Geocoder(this).getFromLocation(p.lat, p.lon, 1)
        return list?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
    }

    private fun pickImage(key: String) {
        pendingImageKey = key
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), 10)
    }

    private var pendingImageKey: String? = null

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        val key = pendingImageKey
        if (requestCode == 10 && resultCode == RESULT_OK && uri != null && key != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            Prefs.of(this).edit().putString(key, uri.toString()).apply()
            Backdrop.invalidate()
            Widgets.later { Widgets.renderAll(this) }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2 && grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) useLocation()
        build()
        Clockwork.arm(this)
        Widgets.later { Widgets.renderAll(this) }
    }

    companion object {
        const val NOTES =
            "Each widget is drawn at its exact size, so the type, artwork and controls scale as you resize it. " +
            "The small and large defaults match the two widgets on your cover screen.\n\n" +
            "The adaptive finishes read what is behind each widget in a grid of zones, as the taskbar reads the screen: dark over light, " +
            "light over dark, with a trace of the colour beneath. A launcher never tells a widget where it sits, so each one learns it from " +
            "your first tap. Until then it reads the wallpaper's overall colour.\n\n" +
            "The glass is the wallpaper itself, thickened. Cristallo is clear: near its rim it bends in what lies just beyond the edge, " +
            "as a curved edge bends light. Vetro is frosted and takes the wallpaper's colour. Both carry one highlight at the upper left, " +
            "cool by day and warm and dim at night. Before a widget's first tap its glass is clear and flat.\n\n" +
            "As a widget gets smaller, things fall away in a fixed order until only the vital thing is left: the play disc, today's date, " +
            "the temperature, the share of storage used. Music has one control, play and pause; any other tap opens the player.\n\n" +
            "The music widget follows the player Android marks as active. Playing beats paused; among equals, the most recent wins."
    }
}

/**
 * Opened by the launcher when a widget is placed or reconfigured: that widget's own finish.
 * Placing works without it too, wearing the default.
 */
class ConfigureActivity : Activity() {
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        // Leaving without a choice keeps the widget, wearing whatever it wore
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        window.statusBarColor = Palette.GROUND
        window.navigationBarColor = Palette.GROUND
        val (scroll, column) = screen()
        column.addView(label("This widget", 32f, Palette.IVORY, display = true))
        column.addView(label("Its finish. Default follows the choice in Fold Widgets.", 14f, Palette.MUTED).apply { setPadding(0, px(8), 0, 0) })
        column.addView(eyebrow("Finish"))
        column.addView(finishGrid(listOf<Finish?>(null) + Finish.entries,
            { f -> if (f == null) !Prefs.hasOwnFinish(this, id) else Prefs.hasOwnFinish(this, id) && Prefs.finish(this, id) == f }) { f ->
            Prefs.setFinish(this, id, f)
            Widgets.later { Widgets.render(this, id) }
        })
        column.addView(button("Done") { finish() }.apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
        column.addView(View(this), LinearLayout.LayoutParams(-1, px(20)))
        setContentView(scroll)
        Widgets.later { Widgets.render(this, id) }
    }
}
