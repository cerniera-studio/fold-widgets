package com.fergusonconsultancy.foldwidgets

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** One email, as its app announced it. */
class MailItem(
    val key: String, val pkg: String, val sender: String, val subject: String, val preview: String,
    val account: String, val time: Long, val open: PendingIntent?,
    /** Its notification is still up: not yet opened or cleared in the email app. */
    val fresh: Boolean = true,
) {
    val id get() = "${sender.lowercase()}|${subject.lowercase()}"
}

/**
 * Mail, from the notifications of the email apps Mechack chooses. Gmail and Outlook do not let
 * other apps read a mailbox, but each new email arrives as a notification carrying its sender,
 * subject, a preview and a link to that exact email.
 *
 * Corrected 31 Oct: the first version listed only the notifications on screen, and opening one email
 * in Outlook clears all of Outlook's mail notifications, so the widget emptied and said "all caught
 * up" while the inbox was not. Now each email is remembered as it arrives and stays listed, newest
 * first, for a week or until forty newer ones push it off. Mail whose notification is still up is
 * new and bright; once opened or cleared in the app it dims but stays. The widget never claims to
 * know the inbox: it shows what arrived.
 *
 * What is kept: sender, subject, the preview line, the account and the time, in this app's private
 * storage on the phone only, cleared from settings. Nothing is sent anywhere.
 */
object Mail {
    /** Known email apps, in the order they are offered. */
    val APPS = linkedMapOf(
        "com.microsoft.office.outlook" to "Outlook",
        "com.google.android.gm" to "Gmail",
        "com.samsung.android.email.provider" to "Samsung Email",
    )
    private const val KEEP = 40
    private const val KEEP_MS = 7 * 86_400_000L

    fun enabled(c: Context) = Prefs.of(c).getBoolean("mail_on", false)
    fun setEnabled(c: Context, on: Boolean) = Prefs.of(c).edit().putBoolean("mail_on", on).apply()

    /** The apps read: those chosen, or by default every known email app on the phone. */
    fun apps(c: Context): Set<String> = Prefs.of(c).getStringSet("mail_apps", null)
        ?: APPS.keys.filter { installed(c, it) }.toSet()
    fun setApp(c: Context, pkg: String, on: Boolean) {
        val now = apps(c).toMutableSet().apply { if (on) add(pkg) else remove(pkg) }
        Prefs.of(c).edit().putStringSet("mail_apps", now).apply()
    }
    fun installed(c: Context, pkg: String) = c.packageManager.getLaunchIntentForPackage(pkg) != null

    /** Links into each email, while its app still honours them. Held in memory only. */
    private val links = java.util.concurrent.ConcurrentHashMap<String, PendingIntent>()

    /** The emails announced right now by the chosen apps. */
    private fun onScreen(c: Context): List<MailItem> {
        val listener = MediaListener.instance ?: return emptyList()
        val chosen = apps(c)
        val all = runCatching { listener.activeNotifications.toList() }.getOrDefault(emptyList())
        return all.filter { it.packageName in chosen && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .mapNotNull { read(it) }
    }

    /** Remembers every email on screen now. Called whenever a chosen app's notifications change. */
    @Synchronized fun remember(c: Context) {
        if (!enabled(c)) return
        val now = onScreen(c)
        if (now.isEmpty()) return
        val kept = load(c).associateBy { it.id }.toMutableMap()
        for (m in now) {
            m.open?.let { links[m.id] = it }
            val old = kept[m.id]
            kept[m.id] = if (old == null || m.time >= old.time) m else old
        }
        save(c, kept.values.toList())
    }

    /** What arrived, newest first, each marked new if its notification is still up. */
    fun items(c: Context): List<MailItem> {
        if (!enabled(c)) return emptyList()
        remember(c)
        val fresh = onScreen(c).map { it.id }.toSet()
        val cutoff = System.currentTimeMillis() - KEEP_MS
        return load(c).filter { it.time >= cutoff }.sortedByDescending { it.time }.take(KEEP).map {
            MailItem(it.key, it.pkg, it.sender, it.subject, it.preview, it.account, it.time, links[it.id], it.id in fresh)
        }
    }

    fun clear(c: Context) { links.clear(); Prefs.of(c).edit().remove("mail_kept").apply() }

    private fun load(c: Context): List<MailItem> = runCatching {
        val arr = org.json.JSONArray(Prefs.of(c).getString("mail_kept", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            MailItem(o.optString("k"), o.optString("p"), o.optString("s"), o.optString("j"), o.optString("v"),
                o.optString("a"), o.optLong("t"), null)
        }
    }.getOrDefault(emptyList())

    private fun save(c: Context, items: List<MailItem>) {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        val arr = org.json.JSONArray()
        items.filter { it.time >= cutoff }.sortedByDescending { it.time }.take(KEEP).forEach {
            arr.put(org.json.JSONObject().put("k", it.key).put("p", it.pkg).put("s", it.sender).put("j", it.subject)
                .put("v", it.preview).put("a", it.account).put("t", it.time))
        }
        Prefs.of(c).edit().putString("mail_kept", arr.toString()).apply()
    }

    private fun read(sbn: StatusBarNotification): MailItem? {
        val e = sbn.notification.extras
        fun text(key: String) = e.getCharSequence(key)?.toString()?.trim().orEmpty()
        val sender = text(Notification.EXTRA_TITLE).ifEmpty { text(Notification.EXTRA_CONVERSATION_TITLE) }
        val firstLine = text(Notification.EXTRA_TEXT).lineSequence().firstOrNull().orEmpty().trim()
        val big = text(Notification.EXTRA_BIG_TEXT)
        if (sender.isEmpty() && firstLine.isEmpty()) return null
        // Gmail gives the subject alone, with the message's opening after a line break in the long
        // form. Outlook gives "subject · opening" on one line (seen on the phone, 31 Oct), and the
        // long form runs the two together, so split on its separator first
        var subject = firstLine.ifEmpty { big.lineSequence().firstOrNull().orEmpty().trim() }
        var preview = ""
        val sep = subject.indexOf(" · ")
        if (sep > 0) { preview = subject.substring(sep + 3).trim(); subject = subject.substring(0, sep).trim() }
        if (preview.isEmpty()) preview = big.removePrefix(firstLine).trim().removePrefix(subject).trim()
        preview = preview.replace(Regex("\\s+"), " ")
        return MailItem(sbn.key, sbn.packageName, sender, subject, preview, text(Notification.EXTRA_SUB_TEXT),
            sbn.notification.`when`.takeIf { it > 0 } ?: sbn.postTime, sbn.notification.contentIntent)
    }

    fun openInbox(c: Context): Act? {
        val pkg = apps(c).firstOrNull { installed(c, it) } ?: return null
        return c.packageManager.getLaunchIntentForPackage(pkg)?.let { Act.Open(intent = it) }
    }
}

/**
 * The mail widget, in the agenda's family: the count of what is waiting in Italiana, then each
 * email as a sender in the ink and its time, the subject beneath, and from four rows tall a line of
 * its opening. A short bar in front marks the account, so two accounts combined stay legible:
 * the colour is information, not ornament. Type keeps one size at every span (4x3 to 5x4).
 */
object MailPainter : Painter {

    /** Account colours from the family: champagne, sage, rosso, steel. */
    private val ACCOUNT = intArrayOf(0xFFD9BB86.toInt(), 0xFF8FB58A.toInt(), 0xFFD0505A.toInt(), 0xFF8AA4C8.toInt())

    override fun paint(c: Context, id: Int, canvas: Canvas, s: Surface): Scene {
        val hits = ArrayList<Hit>()
        val bare = minOf(s.w, s.h) < 130f
        val tall = s.h >= 330f
        val p = if (bare) 12f else if (minOf(s.w, s.h) >= 220f) 18f else 15f
        val inbox = Mail.openInbox(c)
        hits += Hit(RectF(0f, 0f, s.w, s.h), inbox ?: Act.Open(intent = Intent(c, SettingsActivity::class.java)), "Open mail")

        if (!Mail.enabled(c) || !Media.allowed(c)) {
            val titleP = c.ink(24f, s.ink(s.w / 2, s.h / 2), display = true)
            canvas.textTop("Mail", p, p, titleP)
            val noteP = c.ink(13f, s.muted(s.w / 2, s.h / 2), 450)
            wrap("Tap to show new mail from Outlook and Gmail here", noteP, s.w - 2 * p, 2).forEachIndexed { i, l ->
                canvas.textTop(l, p, p + capHeight(titleP) + 14f + i * noteP.textSize * 1.3f, noteP)
            }
            hits[0] = Hit(RectF(0f, 0f, s.w, s.h), Act.Open(intent = Intent(c, SettingsActivity::class.java)), "Set up mail")
            return Scene(hits)
        }

        val items = Mail.items(c)
        val accounts = items.map { it.account }.filter { it.isNotEmpty() }.distinct()
        val fresh = items.count { it.fresh }

        // The count of what is new is the one large thing, shown only when there is some: a zero in
        // Italiana reads as a letter, and the widget cannot know the inbox is empty anyway
        val tx: Float
        val capNum: Float
        if (fresh > 0) {
            val numP = c.ink(if (tall) 42f else if (bare) 26f else 34f, s.ink(p + 20f, p + 18f), display = true)
            canvas.textTop(fresh.toString(), p, p, numP)
            capNum = capHeight(numP)
            tx = p + numP.measureText(fresh.toString()) + 10f
        } else {
            capNum = capHeight(c.ink(if (tall) 42f else if (bare) 26f else 34f, 0, display = true))
            tx = p
        }
        val headW = s.w - p - tx
        val inboxP = c.ink(if (bare) 13f else 15f, s.ink(tx + 30f, p + 6f), 600)
        val subP = c.ink(13f, s.muted(tx + 30f, p + capNum), 450)
        val heading = when { fresh == 1 -> "New email"; fresh > 1 -> "New emails"; else -> "Recent mail" }
        if (bare) canvas.drawText(fit(heading, inboxP, headW), tx, p + capNum, inboxP)
        else {
            canvas.textTop(fit(heading, inboxP, headW), tx, p + 1f, inboxP)
            val sub = when {
                accounts.size > 1 -> "${accounts.size} accounts"
                accounts.size == 1 -> accounts[0]
                else -> Mail.apps(c).filter { Mail.installed(c, it) }.mapNotNull { Mail.APPS[it] }.joinToString(" · ")
            }
            canvas.drawText(fit(sub, subP, headW), tx, p + capNum, subP)
        }
        var y = p + capNum + (if (bare) 12f else 18f)

        if (items.isEmpty()) {
            val emptyP = c.ink(22f, s.ink(s.w / 2, y + 12f), display = true)
            canvas.textTop("No mail yet", p, y + 2f, emptyP)
            val noteP = c.ink(13f, s.muted(s.w / 2, y + 34f), 450)
            wrap("Emails appear here as they arrive, and stay for a week", noteP, s.w - 2 * p, 2).forEachIndexed { i, l ->
                canvas.textTop(l, p, y + 2f + emptyP.textSize * 1.3f + i * noteP.textSize * 1.3f, noteP)
            }
            return Scene(hits)
        }

        val senderP = c.ink(14.5f, s.ink(s.w / 2, y), 600)
        val timeP = c.ink(12.5f, s.muted(s.w - p, y), 500).apply { textAlign = Paint.Align.RIGHT }
        val subjectP = c.ink(14f, s.ink(s.w / 2, y), 450)
        val previewP = c.ink(12.5f, s.muted(s.w / 2, y), 450)
        val rowH = if (bare) 22f else if (tall) 62f else 44f
        val gap = if (bare) 4f else 10f
        val x0 = p + 11f
        val width = s.w - p - x0
        var shown = 0
        // New and earlier mail under their own headings, so which is which is never in doubt
        val labelP = c.ink(11.5f, s.accent(s.w / 2, y), 600, tracking = 0.14f)
        var section: Boolean? = null
        for ((i, m) in items.withIndex()) {
            // A row without a preview line is shorter, so no gap is left where it would have been
            val thisRow = if (tall && m.preview.isEmpty()) 44f else rowH
            val reserve = if (i < items.size - 1 && !bare) 18f else 0f
            val heading = !bare && section != m.fresh
            val headingH = if (heading) 20f + (if (section != null) 6f else 0f) else 0f
            if (y + headingH + thisRow + reserve > s.h - p + 2f) break
            if (heading) {
                if (section != null) y += 6f
                labelP.color = if (m.fresh) s.accent(s.w / 2, y) else s.muted(s.w / 2, y)
                canvas.textTop(if (m.fresh) "NEW" else "EARLIER", p, y, labelP)
                y += 20f
                section = m.fresh
            }
            val mid = y + 9f
            // New mail bright; mail already opened or cleared in the app dims but stays
            val accent = ACCOUNT[(accounts.indexOf(m.account).coerceAtLeast(0)) % ACCOUNT.size]
            val bar = if (m.fresh) accent else (accent and 0x00FFFFFF) or 0x59000000
            canvas.drawRoundRect(RectF(p, y + 1f, p + 3f, y + thisRow - gap - 1f), 1.5f, 1.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bar })
            val time = when (val d = m.time) {
                in startOfToday()..Long.MAX_VALUE -> android.text.format.DateFormat.getTimeFormat(c).format(java.util.Date(d))
                else -> SimpleDateFormat("EEE", Locale.getDefault()).format(java.util.Date(d))
            }
            timeP.color = s.muted(s.w - p, mid)
            val timeW = timeP.measureText(time)
            canvas.drawText(time, s.w - p, mid + capHeight(timeP) / 2, timeP)
            senderP.color = if (m.fresh) s.ink(x0 + width / 2, mid) else s.muted(x0 + width / 2, mid)
            senderP.typeface = Type.sans(c, if (m.fresh) 600 else 500)
            canvas.drawText(fit(m.sender, senderP, width - timeW - 10f), x0, mid + capHeight(senderP) / 2, senderP)
            if (!bare) {
                subjectP.color = if (m.fresh) s.ink(x0 + width / 2, mid + 19f) else s.muted(x0 + width / 2, mid + 19f)
                canvas.drawText(fit(m.subject, subjectP, width), x0, mid + 19f + capHeight(subjectP) / 2, subjectP)
                if (tall && m.preview.isNotEmpty()) {
                    previewP.color = s.muted(x0 + width / 2, mid + 37f)
                    canvas.drawText(fit(m.preview, previewP, width), x0, mid + 37f + capHeight(previewP) / 2, previewP)
                }
            }
            // The email itself while its link is live; otherwise its app
            val app = c.packageManager.getLaunchIntentForPackage(m.pkg)
            val open = if (m.open != null) Act.Open(pending = m.open, intent = app) else if (app != null) Act.Open(intent = app) else inbox
            if (open != null && hits.size < 12) hits += Hit(RectF(0f, y - 3f, s.w, y + thisRow - gap + 3f), open, "${m.sender}: ${m.subject}")
            y += thisRow
            shown++
        }
        if (shown < items.size && !bare) {
            val moreP = c.ink(12.5f, s.muted(s.w / 2, y + 6f), 500)
            canvas.textTop("+ ${items.size - shown} more", x0, y + 2f, moreP)
        }
        return Scene(hits)
    }

    private fun startOfToday() = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
