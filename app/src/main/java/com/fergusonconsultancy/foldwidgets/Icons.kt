package com.fergusonconsultancy.foldwidgets

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.drawable.Drawable
import org.xmlpull.v1.XmlPullParser

/**
 * App icons as the launcher shows them. If an icon pack is in use (Smart Launcher reads the same
 * ADW-style packs as other launchers), the widget takes each icon from that pack, so the Gmail and
 * Outlook marks in the mail widget, and the player's mark in the music widget, are the very icons on
 * the home screen. Without a pack, the app's own icon. Drawn as the drawable itself, never a
 * resampled bitmap, so it stays sharp at any size.
 */
object Icons {
    private val PACK_INTENTS = listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.gau.go.launcherex.theme")

    /** Installed icon packs: package to name. */
    fun packs(c: Context): Map<String, String> {
        val pm = c.packageManager
        return PACK_INTENTS.flatMap { action -> runCatching { pm.queryIntentActivities(Intent(action), 0) }.getOrDefault(emptyList()) }
            .associate { it.activityInfo.packageName to it.loadLabel(pm).toString() }
    }

    /** The pack in use: the one chosen in settings, else the only one installed. */
    fun pack(c: Context): String? {
        val chosen = Prefs.of(c).getString("icon_pack", null)
        if (chosen == "none") return null
        val all = packs(c)
        if (chosen != null && chosen in all) return chosen
        return all.keys.singleOrNull()
    }
    fun setPack(c: Context, pkg: String?) { Prefs.of(c).edit().putString("icon_pack", pkg ?: "none").apply(); filters.clear(); cache.clear() }

    private val filters = HashMap<String, Map<String, String>>()
    private val cache = HashMap<String, Drawable?>()

    /** The icon for an app, from the pack if it has one. */
    fun of(c: Context, pkg: String): Drawable? = synchronized(cache) {
        val pack = pack(c)
        cache.getOrPut("$pack/$pkg") { fromPack(c, pack, pkg) ?: runCatching { c.packageManager.getApplicationIcon(pkg) }.getOrNull() }
    }

    fun draw(cv: Canvas, d: Drawable, r: RectF, alpha: Int = 255) {
        val m = d.mutate()
        m.setBounds(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
        // Drawn in a canvas scaled to dp: bounds must be whole dp, so round and offset for exactness
        cv.save()
        cv.translate(r.left - r.left.toInt(), r.top - r.top.toInt())
        m.alpha = alpha
        m.draw(cv)
        m.alpha = 255
        cv.restore()
    }

    private fun fromPack(c: Context, pack: String?, pkg: String): Drawable? {
        if (pack == null) return null
        return runCatching {
            val map = filters.getOrPut(pack) { read(c, pack) }
            val launch = c.packageManager.getLaunchIntentForPackage(pkg)?.component
            val name = launch?.let { map[it.flattenToString()] } ?: map.entries.firstOrNull { it.key.startsWith("$pkg/") }?.value
                ?: return null
            val res = c.packageManager.getResourcesForApplication(pack)
            val id = res.getIdentifier(name, "drawable", pack).takeIf { it != 0 } ?: return null
            res.getDrawable(id, null)
        }.getOrNull()
    }

    /** The pack's appfilter: launcher component to drawable name. */
    private fun read(c: Context, pack: String): Map<String, String> {
        val out = HashMap<String, String>()
        val res = c.packageManager.getResourcesForApplication(pack)
        val id = res.getIdentifier("appfilter", "xml", pack)
        val parser: XmlPullParser = if (id != 0) res.getXml(id) else {
            val f = android.util.Xml.newPullParser()
            f.setInput(c.createPackageContext(pack, 0).assets.open("appfilter.xml"), "UTF-8"); f
        }
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG && parser.name == "item") {
                val comp = parser.getAttributeValue(null, "component")
                val draw = parser.getAttributeValue(null, "drawable")
                if (comp != null && draw != null && comp.startsWith("ComponentInfo{")) {
                    val inner = comp.removePrefix("ComponentInfo{").removeSuffix("}")
                    ComponentName.unflattenFromString(inner)?.let { out.putIfAbsent(it.flattenToString(), draw) }
                }
            }
            e = parser.next()
        }
        return out
    }
}
