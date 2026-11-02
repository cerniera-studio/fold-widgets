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
            addView(row("Music and mail", "Follows Spotify, Poweramp or any player. Notifications are read only for Mail, if you turn it on.",
                if (music) status(true) else button("Allow") { startActivity(Media.accessIntent(this@SettingsActivity)) }))
            val cal = Agenda.allowed(this@SettingsActivity)
            addView(row("Calendar", "The agenda reads your calendars on the phone",
                if (cal) status(true) else button("Allow") { requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 1) }))
            val wall = Backdrop.canReadWallpaper()
            addView(row("Glass", "The glass reads your wallpaper. Android only lets an app read it with all-files access.",
                if (wall) status(true) else button("Allow") {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                }))
            // Live mode (2 Nov 2026): the widgets' eyes, as the taskbar has its own
            val live = LiveService.enabled(this@SettingsActivity)
            addView(row("Live mode", (if (live) Live.lastNote(this@SettingsActivity)?.let { "$it. " } ?: "" else "") +
                "Each time the home screen comes to rest, one screenshot shows where the launcher has the wallpaper, and each widget is redrawn against what is really behind it. Never saved, never sent, home screen only.",
                if (live) status(true) else button("Turn on") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }))
        })

        // ---- Mail: opt-in, and only the apps chosen
        column.addView(eyebrow("Mail"))
        column.addView(card().apply {
            val on = Mail.enabled(this@SettingsActivity)
            addView(row("Show mail", "Reads the notifications of the email apps below, and nothing else. Each email is remembered for a week on this phone: new mail bright, mail you have opened dimmed. Nothing is sent anywhere.",
                button(if (on) "Turn off" else "Turn on", primary = !on) {
                    Mail.setEnabled(this@SettingsActivity, !on)
                    if (!on && !Media.allowed(this@SettingsActivity)) startActivity(Media.accessIntent(this@SettingsActivity))
                    build(); Widgets.later { Widgets.renderAll(this@SettingsActivity, Kind.MAIL) }
                }))
            if (on) addView(row("Clear the list", "Forget every email the widget has kept", button("Clear", primary = false) {
                Mail.clear(this@SettingsActivity); Widgets.later { Widgets.renderAll(this@SettingsActivity, Kind.MAIL) }
            }))
            if (on) Mail.APPS.forEach { (pkg, name) ->
                if (!Mail.installed(this@SettingsActivity, pkg)) return@forEach
                val read = pkg in Mail.apps(this@SettingsActivity)
                addView(row(name, if (read) "Read" else "Not read", button(if (read) "Stop" else "Read", primary = false) {
                    Mail.setApp(this@SettingsActivity, pkg, !read); build(); Widgets.later { Widgets.renderAll(this@SettingsActivity, Kind.MAIL) }
                }))
            }
        })

        // ---- Inboxes: the widget mirrors Gmail and Outlook themselves, so deleted mail leaves it
        column.addView(eyebrow("Inboxes"))
        column.addView(card().apply {
            addView(label("Connect an inbox and the widget shows what the inbox shows: unread and read, and nothing you have deleted or archived. Read-only: nothing is ever marked read, moved or sent. Kept on this phone only.",
                13f, Palette.MUTED).apply { setPadding(0, px(14), 0, px(6)) })

            // Gmail: address and app password
            val gmail = Mailbox.address(this@SettingsActivity, Mailbox.GMAIL)
            if (gmail != null) {
                addView(row("Gmail", Mailbox.problem(this@SettingsActivity, Mailbox.GMAIL) ?: gmail, button("Disconnect", primary = false) {
                    Mailbox.disconnect(this@SettingsActivity, Mailbox.GMAIL); build()
                }))
            } else {
                addView(row("Gmail", "Needs an app password: a 16-letter key Google makes for one app, which you can revoke at any time. Your Google password is never used.",
                    button("Get one", primary = false) { open("https://myaccount.google.com/apppasswords") }))
                val addr = field("Gmail address", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
                val pw = field("App password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
                addView(addr, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8) })
                addView(pw, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8) })
                addView(button("Connect Gmail") {
                    val a = addr.text.toString(); val k = pw.text.toString()
                    toast("Checking with Google…")
                    Thread {
                        val failed = Mailbox.connectGmail(applicationContext, a, k)
                        runOnUiThread { toast(failed ?: "Gmail connected"); if (failed == null) build() }
                    }.start()
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(14) })
            }

            // Outlook.com: Microsoft sign-in, with the app's own registration
            val outlook = Mailbox.address(this@SettingsActivity, Mailbox.OUTLOOK)
            if (outlook != null) {
                addView(row("Outlook", Mailbox.problem(this@SettingsActivity, Mailbox.OUTLOOK) ?: outlook, button("Disconnect", primary = false) {
                    Mailbox.disconnect(this@SettingsActivity, Mailbox.OUTLOOK); build()
                }))
                if (Mailbox.problem(this@SettingsActivity, Mailbox.OUTLOOK) != null) addView(button("Sign in again") { signInMicrosoft() },
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(14) })
            } else {
                addView(row("Outlook", "Signs in on Microsoft's own page. Microsoft requires every app to be registered: paste this app's ID from its registration below.", null))
                val id = field("Application (client) ID", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).apply {
                    setText(Microsoft.clientId(this@SettingsActivity).orEmpty())
                }
                addView(id, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8) })
                addView(button("Sign in with Microsoft") {
                    val v = id.text.toString().trim()
                    if (!Regex("[0-9a-fA-F-]{36}").matches(v)) { toast("The ID looks like 1a2b3c4d-… with 36 characters"); return@button }
                    Microsoft.setClientId(this@SettingsActivity, v)
                    signInMicrosoft()
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(14) })
            }

            if (gmail != null || outlook != null) {
                // Googlemail link test (1 Nov): does Outlook accept Gmail's own ids for a Gmail email?
                addView(row("Tap log", "What happened when you tapped emails, and when Outlook's links were kept. No email content.",
                    button("Share", primary = false) {
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, "Fold Widgets tap log\n" + Probe.taps(this@SettingsActivity).ifBlank { "(empty)" }), "Share the tap log"))
                    }))
                val main = Mailbox.mainOnly(this@SettingsActivity)
                addView(row("Primary and Focused only", if (main) "As the apps notify: Gmail's Primary tab, Outlook's Focused inbox" else "Everything in both inboxes, promotions included",
                    button(if (main) "Show all" else "Main only", primary = false) {
                        Mailbox.setMainOnly(this@SettingsActivity, !main); build()
                    }))
                addView(row("Look now", "The widget looks every few minutes while the screen is on", button("Refresh", primary = false) {
                    Thread { Mailbox.sync(applicationContext, force = true); runOnUiThread { toast("Updated"); build() } }.start()
                }))
            }
        })

        // ---- Icons: the marks in the widgets come from the home screen's icon pack
        val packs = Icons.packs(this)
        if (packs.isNotEmpty()) {
            column.addView(eyebrow("Icons"))
            column.addView(card().apply {
                val inUse = Icons.pack(this@SettingsActivity)
                addView(label("The music widget's player icon is taken from the icon pack you use on the home screen.",
                    13f, Palette.MUTED).apply { setPadding(0, px(14), 0, px(6)) })
                (packs.toList() + (null to "Each app's own icon")).forEach { (pkg, name) ->
                    val on = pkg == inUse
                    addView(row(name, if (on) "In use" else "", if (on) status(true, "In use") else button("Use", primary = false) {
                        Icons.setPack(this@SettingsActivity, pkg); build(); Widgets.later { Widgets.renderAll(this@SettingsActivity) }
                    }))
                }
            })
        }

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

        // ---- Sizes: what each widget was told and what it measured, for tuning on the phone
        val placed = Kind.entries.flatMap { Widgets.ids(this, it).toList() }
        if (placed.isNotEmpty()) {
            column.addView(eyebrow("Sharpness"))
            column.addView(card().apply {
                addView(label("Each widget is drawn to the exact pixels it occupies once you have tapped it. Until then it uses the size the launcher reports, which may be wrong and look soft.",
                    13f, Palette.MUTED).apply { setPadding(0, px(14), 0, px(6)) })
                placed.forEach { id ->
                    val d = Prefs.of(this@SettingsActivity).getString("diag_$id", null) ?: return@forEach
                    addView(label(d, 12.5f, if (d.contains("measured")) Palette.IVORY else Palette.WARN).apply { setPadding(0, px(8), 0, px(8)) })
                }
            })
        }

        column.addView(eyebrow("How it works"))
        column.addView(label(NOTES, 13.5f, Palette.MUTED).apply { setPadding(px(4), 0, px(4), 0) })
    }

    private fun field(hintText: String, type: Int) = EditText(this).apply {
        hint = hintText
        setHintTextColor(Palette.MUTED); setTextColor(Palette.IVORY); textSize = 15f
        typeface = Type.sans(context, 400)
        inputType = type
        isSingleLine = true
        background = rounded(Palette.RAISED, px(14).toFloat(), Palette.HAIRLINE, px(1))
        setPadding(px(14), px(12), px(14), px(12))
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun open(url: String) { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }

    private fun signInMicrosoft() {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Microsoft.signInUri(this))) }
            .onFailure { toast("No browser could open Microsoft's sign-in page") }
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
            "light over dark, with a trace of the colour beneath. Legibility comes first: each zone is checked at its lightest and darkest, " +
            "not its average, and if the type would not reach a contrast of 7 to 1 the finish flips, or its glass thickens just enough. A launcher never tells a widget where it sits, so each one learns it from " +
            "your first tap. Until then it reads the wallpaper's overall colour.\n\n" +
            "The glass is the wallpaper itself, thickened. Cristallo is clear: near its rim it bends in what lies just beyond the edge, " +
            "as a curved edge bends light. Vetro is frosted and takes the wallpaper's colour. Both carry one highlight at the upper left, " +
            "cool by day and warm and dim at night. Before a widget's first tap its glass is clear and flat, and its size is the launcher's guess: one tap measures it.\n\n" +
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
        Widgets.kindOf(this, id)?.let { k -> column.addView(label(Widgets.describe(this, id, k), 14f, Palette.CHAMPAGNE, 500).apply { setPadding(0, px(6), 0, 0) }) }
        column.addView(label("Its finish and what it shows. Follow app keeps this widget in the finish chosen for the whole app.", 14f, Palette.MUTED).apply { setPadding(0, px(8), 0, 0) })
        column.addView(eyebrow("Finish"))
        column.addView(finishGrid(listOf<Finish?>(null) + Finish.entries,
            { f -> if (f == null) !Prefs.hasOwnFinish(this, id) else Prefs.hasOwnFinish(this, id) && Prefs.finish(this, id) == f }) { f ->
            Prefs.setFinish(this, id, f)
            Widgets.later { Widgets.render(this, id) }
        })
        // Weather: what this widget shows beyond its minimum (31 Oct)
        if (Widgets.kindOf(this, id) == Kind.WEATHER) {
            column.addView(eyebrow("Shows"))
            column.addView(label("Always: the place, the temperature and the conditions. Then, as the widget's size allows, whichever of these you choose, in this order.",
                13f, Palette.MUTED).apply { setPadding(px(4), 0, px(4), px(10)) })
            column.addView(card().apply {
                WeatherOptions.Item.entries.forEach { item ->
                    val box = this
                    var on = WeatherOptions.on(this@ConfigureActivity, id, item)
                    lateinit var toggle: android.widget.TextView
                    toggle = button(if (on) "Shown" else "Hidden", primary = on) {
                        on = !on
                        WeatherOptions.set(this@ConfigureActivity, id, item, on)
                        toggle.text = if (on) "Shown" else "Hidden"
                        toggle.setTextColor(if (on) Palette.GROUND else Palette.IVORY)
                        toggle.background = if (on) rounded(Palette.CHAMPAGNE, px(18).toFloat()) else rounded(Palette.RAISED, px(18).toFloat(), Palette.HAIRLINE, px(1))
                        Widgets.later { Widgets.render(this@ConfigureActivity, id) }
                    }
                    box.addView(row(item.label, item.note, toggle))
                }
            })
        }
        column.addView(button("Done") { finish() }.apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
        column.addView(View(this), LinearLayout.LayoutParams(-1, px(20)))
        setContentView(scroll)
        Widgets.later { Widgets.render(this, id) }
    }
}
