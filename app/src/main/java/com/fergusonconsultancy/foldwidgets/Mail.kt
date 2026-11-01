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
    /** A link straight to this email in its app, when the inbox gives one. */
    val link: String = "",
    /** Which inbox it came from: gmail or outlook; empty for notification mail. */
    val source: String = "",
    /** Gmail's own ids for it (message id, thread id, Message-ID header), kept for the Outlook link test. */
    val ids: String = "",
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
    /** The same links by subject alone, normalised, for when the sender is written differently. */
    private val bySubject = java.util.concurrent.ConcurrentHashMap<String, PendingIntent>()

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")

    private fun keep(c: Context, m: MailItem) {
        val open = m.open ?: return
        val fresh = !links.containsKey(m.id)
        links[m.id] = open
        if (m.subject.length >= 4) bySubject[norm(m.subject)] = open
        if (fresh) Probe.note(c, "Kept a link from ${APPS[m.pkg] ?: m.pkg}'s notification (now ${links.size})", null)
    }

    fun keptCount() = links.size

    /** Outlook's own link into an email it announced, kept from its notification: opens that email for either account. */
    fun linkFor(sender: String, subject: String): PendingIntent? =
        links["${sender.lowercase()}|${subject.lowercase()}"] ?: bySubject[norm(subject)]

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
        // With an inbox connected the notifications are not listed, but their links into each email
        // are still the surest way in, so they are kept while they live
        if (Mailbox.connected(c)) { onScreen(c).forEach { keep(c, it) }; return }
        if (!enabled(c)) return
        val now = onScreen(c)
        if (now.isEmpty()) return
        val kept = load(c).associateBy { it.id }.toMutableMap()
        for (m in now) {
            keep(c, m)
            val old = kept[m.id]
            kept[m.id] = if (old == null || m.time >= old.time) m else old
        }
        save(c, kept.values.toList())
    }

    /** What arrived, newest first, each marked new if its notification is still up. */
    fun items(c: Context): List<MailItem> {
        // With an inbox connected, the inbox is the truth; notifications only lend their links
        if (Mailbox.connected(c)) {
            remember(c)
            return Mailbox.items(c).take(KEEP)
        }
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
 * The mail widget, in the agenda's family. The count of unread mail in Italiana and the word
 * "Unread"; nothing else in the header unless something is wrong (31 Oct: "2 accounts" told Mechack
 * what he already knows, and a section label repeating the heading was clutter). Each email: the
 * account's own app icon, taken from the icon pack on the home screen so it is the mark he knows,
 * then sender and time, subject, and from four rows tall a line of the opening. Unread is bright,
 * read is muted with its icon faded, under a quiet READ divider. Type keeps one size at every span.
 * A tap opens that email itself, never just the inbox, wherever the app allows it.
 */
object MailPainter : Painter {

    override fun paint(c: Context, id: Int, canvas: Canvas, s: Surface): Scene {
        val hits = ArrayList<Hit>()
        val bare = minOf(s.w, s.h) < 130f
        val tall = s.h >= 330f
        val p = if (bare) 12f else if (minOf(s.w, s.h) >= 220f) 18f else 15f
        val inbox = Mail.openInbox(c)
        hits += Hit(RectF(0f, 0f, s.w, s.h), inbox ?: Act.Open(intent = Intent(c, SettingsActivity::class.java)), "Open mail")

        val boxes = Mailbox.connected(c)
        if (!boxes && (!Mail.enabled(c) || !Media.allowed(c))) {
            val titleP = c.ink(24f, s.ink(s.w / 2, s.h / 2), display = true)
            canvas.textTop("Mail", p, p, titleP)
            val noteP = c.ink(13f, s.muted(s.w / 2, s.h / 2), 450)
            wrap("Tap to connect Gmail and Outlook", noteP, s.w - 2 * p, 2).forEachIndexed { i, l ->
                canvas.textTop(l, p, p + capHeight(titleP) + 14f + i * noteP.textSize * 1.3f, noteP)
            }
            hits[0] = Hit(RectF(0f, 0f, s.w, s.h), Act.Open(intent = Intent(c, SettingsActivity::class.java)), "Set up mail")
            return Scene(hits)
        }

        val items = Mail.items(c)
        val fresh = items.count { it.fresh }
        val warning = if (boxes) Mailbox.warning(c) else null

        // Header: the count and one word. A second line only to say something is wrong, or all is read
        val numP = c.ink(if (tall) 42f else if (bare) 26f else 34f, s.ink(p + 20f, p + 18f), display = true)
        val capNum = capHeight(numP)
        var tx = p
        if (fresh > 0) {
            canvas.textTop(fresh.toString(), p, p, numP)
            tx = p + numP.measureText(fresh.toString()) + 10f
        }
        val headW = s.w - p - tx
        val headP = c.ink(if (bare) 13f else 15f, s.ink(tx + 30f, p + 6f), 600)
        val heading = when {
            boxes -> if (fresh > 0) "Unread" else "Inbox"
            fresh == 1 -> "New email"; fresh > 1 -> "New emails"; else -> "Recent mail"
        }
        val second = warning ?: if (boxes && fresh == 0 && items.isNotEmpty()) "All read" else null
        if (second == null || bare) {
            // One line, centred on the figures
            canvas.drawText(fit(heading, headP, headW), tx, p + capNum / 2 + capHeight(headP) / 2, headP)
        } else {
            val subP = c.ink(13f, if (warning != null) s.accent(tx + 30f, p + capNum) else s.muted(tx + 30f, p + capNum), 450)
            canvas.textTop(fit(heading, headP, headW), tx, p + 1f, headP)
            canvas.drawText(fit(second, subP, headW), tx, p + capNum, subP)
        }
        var y = p + capNum + (if (bare) 12f else 20f)

        if (items.isEmpty()) {
            val emptyP = c.ink(22f, s.ink(s.w / 2, y + 12f), display = true)
            canvas.textTop(if (boxes) "Inbox empty" else "No mail yet", p, y + 2f, emptyP)
            val noteP = c.ink(13f, s.muted(s.w / 2, y + 34f), 450)
            wrap(if (boxes) "Nothing waiting in the inbox" else "Emails appear here as they arrive, and stay for a week", noteP, s.w - 2 * p, 2).forEachIndexed { i, l ->
                canvas.textTop(l, p, y + 2f + emptyP.textSize * 1.3f + i * noteP.textSize * 1.3f, noteP)
            }
            return Scene(hits)
        }

        // The list scrolls, as Outlook's own widget does (1 Nov): the header stays in the picture,
        // and each email is drawn as a row of its own in a scrolling list laid over the glass
        if (!bare) return Scene(hits, listTop = y - 6f)

        val senderP = c.ink(14.5f, s.ink(s.w / 2, y), 600)
        val timeP = c.ink(12.5f, s.muted(s.w - p, y), 500).apply { textAlign = Paint.Align.RIGHT }
        val subjectP = c.ink(14f, s.ink(s.w / 2, y), 450)
        val previewP = c.ink(12.5f, s.muted(s.w / 2, y), 450)
        val rowH = if (bare) 22f else if (tall) 62f else 44f
        val gap = if (bare) 4f else 10f
        val icon = if (bare) 0f else 18f
        val x0 = p + (if (bare) 0f else icon + 11f)
        val width = s.w - p - x0
        val labelP = c.ink(11f, s.muted(s.w / 2, y), 600, tracking = 0.16f)
        // First, how many rows fit; then the room left over is shared between them, so the list fills
        // the widget instead of stopping short above an empty band
        fun plan(): Pair<Int, Float> {
            var yy = y; var n = 0; var done = false
            for ((i, m) in items.withIndex()) {
                val row = if (tall && m.preview.isEmpty()) 44f else rowH
                val reserve = if (i < items.size - 1 && !bare) 18f else 0f
                val div = !bare && !m.fresh && !done && fresh > 0
                val dh = if (div) 26f else 0f
                if (yy + dh + row + reserve > s.h - p + 2f) break
                if (!m.fresh) done = true
                yy += dh + row; n++
            }
            val more = if (n < items.size && !bare) 18f else 0f
            val slack = s.h - p - (yy + more)
            return n to if (n > 1 && !bare) (slack / n).coerceIn(0f, 10f) else 0f
        }
        val (fits, extra) = plan()
        var shown = 0
        var dividerDone = false
        for ((i, m) in items.withIndex()) {
            if (shown >= fits) break
            val thisRow = (if (tall && m.preview.isEmpty()) 44f else rowH) + extra
            // A quiet divider where read mail begins, only when unread mail sits above it
            val divider = !bare && !m.fresh && !dividerDone && fresh > 0
            val dividerH = if (divider) 26f else 0f
            if (divider) {
                labelP.color = s.muted(s.w / 2, y + 10f)
                canvas.textTop(if (boxes) "READ" else "EARLIER", x0, y + 4f, labelP)
                y += dividerH
            }
            if (!m.fresh) dividerDone = true
            val mid = y + 9f

            // The account's mark: its app's icon, as on the home screen; faded once read
            if (!bare) appFor(c, m)?.let { pkg ->
                Icons.of(c, pkg)?.let { d -> Icons.draw(canvas, d, RectF(p, mid - icon / 2, p + icon, mid + icon / 2), if (m.fresh) 255 else 115) }
            }
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
            open(c, m, inbox)?.let { if (hits.size < 12) hits += Hit(RectF(0f, y - 3f, s.w, y + thisRow - gap - extra + 3f), it, "${m.sender}: ${m.subject}") }
            y += thisRow
            shown++
        }
        if (shown < items.size && !bare) {
            val moreP = c.ink(12.5f, s.muted(s.w / 2, y + 6f), 500)
            canvas.textTop("+ ${items.size - shown} more", x0, y + 2f, moreP)
        }
        return Scene(hits)
    }

    /** The measures a row uses, shared by the widget and its scrolling list. */
    class RowStyle(val p: Float, val tall: Boolean) {
        val icon = 18f
        val x0 get() = p + icon + 11f
        fun height(m: MailItem) = if (tall && m.preview.isNotEmpty()) 62f else 44f
        val divider = 30f
    }

    fun rowStyle(s: Surface) = RowStyle(if (minOf(s.w, s.h) >= 220f) 18f else 15f, s.h >= 330f)

    /**
     * One email as a row of the scrolling list, drawn on a transparent canvas [width] dp wide.
     * Ink is read from the glass at [sampleY], the middle of the list, as the row moves over it.
     */
    fun paintRow(c: Context, cv: Canvas, s: Surface, st: RowStyle, m: MailItem, width: Float, sampleY: Float) {
        val p = st.p; val x0 = st.x0; val w = width - p - x0
        val mid = 9f + 3f
        if (appFor(c, m) != null) Icons.of(c, appFor(c, m)!!)?.let { d ->
            Icons.draw(cv, d, RectF(p, mid - st.icon / 2, p + st.icon, mid + st.icon / 2), if (m.fresh) 255 else 115)
        }
        val time = when (val d = m.time) {
            in startOfToday()..Long.MAX_VALUE -> android.text.format.DateFormat.getTimeFormat(c).format(java.util.Date(d))
            else -> SimpleDateFormat("EEE", Locale.getDefault()).format(java.util.Date(d))
        }
        val timeP = c.ink(12.5f, s.muted(width - p, sampleY), 500).apply { textAlign = Paint.Align.RIGHT }
        cv.drawText(time, width - p, mid + capHeight(timeP) / 2, timeP)
        val timeW = timeP.measureText(time)
        val senderP = c.ink(14.5f, if (m.fresh) s.ink(x0 + w / 2, sampleY) else s.muted(x0 + w / 2, sampleY), if (m.fresh) 600 else 500)
        cv.drawText(fit(m.sender, senderP, w - timeW - 10f), x0, mid + capHeight(senderP) / 2, senderP)
        val subjectP = c.ink(14f, if (m.fresh) s.ink(x0 + w / 2, sampleY) else s.muted(x0 + w / 2, sampleY), 450)
        cv.drawText(fit(m.subject, subjectP, w), x0, mid + 19f + capHeight(subjectP) / 2, subjectP)
        if (st.tall && m.preview.isNotEmpty()) {
            val previewP = c.ink(12.5f, s.muted(x0 + w / 2, sampleY), 450)
            cv.drawText(fit(m.preview, previewP, w), x0, mid + 37f + capHeight(previewP) / 2, previewP)
        }
    }

    /** The quiet divider where read mail begins. */
    fun paintDivider(c: Context, cv: Canvas, s: Surface, st: RowStyle, boxes: Boolean, sampleY: Float) {
        val labelP = c.ink(11f, s.muted(s.w / 2, sampleY), 600, tracking = 0.16f)
        cv.textTop(if (boxes) "READ" else "EARLIER", st.x0, 9f, labelP)
    }

    /** What a tap on an email does; shared with the scrolling list. */
    fun openAct(c: Context, m: MailItem): Act? = open(c, m, Mail.openInbox(c))

    /** The app whose icon marks the email's account. */
    private fun appFor(c: Context, m: MailItem): String? = when (m.source) {
        Mailbox.GMAIL -> GMAIL_APP.takeIf { Mail.installed(c, it) } ?: m.pkg
        Mailbox.OUTLOOK -> OUTLOOK_APP
        else -> m.pkg
    }.takeIf { it.isNotEmpty() }

    /**
     * Opening one email: the link its app gave in the notification while that lives (it opens the
     * exact email wherever it was announced), else the inbox's own link to the message (Outlook's
     * message link, Gmail's conversation link), and only if both fail, the app.
     */
    private fun open(c: Context, m: MailItem, inbox: Act?): Act? {
        val src = m.source.ifEmpty { "notification" } + if (m.link.isEmpty()) ", no message link" else ""
        val app = c.packageManager.getLaunchIntentForPackage(m.pkg)?.putExtra(Probe.EXTRA_SOURCE, src)
        val deep = m.link.takeIf { it.isNotEmpty() }?.let {
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(it)).setPackage(OUTLOOK_APP).putExtra(Probe.EXTRA_SOURCE, src)
        }
        if (m.open == null && deep == null) return app?.let { Act.Open(intent = it) } ?: inbox
        return Act.Open(pending = m.open, intent = deep ?: app, fallback = if (deep != null) app else null)
    }

    private const val GMAIL_APP = "com.google.android.gm"
    private const val OUTLOOK_APP = "com.microsoft.office.outlook"

    private fun startOfToday() = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
