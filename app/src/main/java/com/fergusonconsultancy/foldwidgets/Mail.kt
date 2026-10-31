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
)

/**
 * Mail, read from the notifications of the email apps Mechack chooses (31 Oct). Gmail and Outlook
 * do not let other apps read a mailbox, but each new email arrives as a notification carrying its
 * sender, subject, a preview and a link to that exact email. So the widget lists what is waiting:
 * mail you have read or dismissed in the app leaves the widget too. Nothing is stored and nothing
 * leaves the phone; the list is read afresh from the notifications on screen at each redraw.
 *
 * Opt-in, and only the apps chosen in settings are read. Outlook combines all its accounts, so the
 * widget does too; when the same email is announced by two apps, it appears once.
 */
object Mail {
    /** Known email apps, in the order they are offered. */
    val APPS = linkedMapOf(
        "com.microsoft.office.outlook" to "Outlook",
        "com.google.android.gm" to "Gmail",
        "com.samsung.android.email.provider" to "Samsung Email",
    )

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

    /** What is waiting, newest first. Empty unless mail is on and notification access is granted. */
    fun items(c: Context): List<MailItem> {
        if (!enabled(c)) return emptyList()
        val listener = MediaListener.instance ?: return emptyList()
        val chosen = apps(c)
        val all = runCatching { listener.activeNotifications.toList() }.getOrDefault(emptyList())
        val items = all.filter { it.packageName in chosen && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .mapNotNull { read(it) }
            .sortedByDescending { it.time }
        // The same email from two apps (Outlook and Gmail both set up for one account) shows once
        val seen = HashSet<String>()
        return items.filter { seen.add("${it.sender.lowercase()}|${it.subject.lowercase()}") }
    }

    private fun read(sbn: StatusBarNotification): MailItem? {
        val e = sbn.notification.extras
        fun text(key: String) = e.getCharSequence(key)?.toString()?.trim().orEmpty()
        val sender = text(Notification.EXTRA_TITLE).ifEmpty { text(Notification.EXTRA_CONVERSATION_TITLE) }
        val firstLine = text(Notification.EXTRA_TEXT).lineSequence().firstOrNull().orEmpty().trim()
        val big = text(Notification.EXTRA_BIG_TEXT)
        if (sender.isEmpty() && firstLine.isEmpty()) return null
        // Email apps put the subject first and the opening of the message after it
        val subject = firstLine.ifEmpty { big.lineSequence().firstOrNull().orEmpty().trim() }
        val preview = big.removePrefix(subject).trim().replace(Regex("\\s+"), " ")
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

        // The count, the one large thing
        val numP = c.ink(if (tall) 42f else if (bare) 26f else 34f, s.ink(p + 20f, p + 18f), display = true)
        val count = items.size.toString()
        canvas.textTop(count, p, p, numP)
        val capNum = capHeight(numP)
        val tx = p + numP.measureText(count) + 10f
        val headW = s.w - p - tx
        val inboxP = c.ink(if (bare) 13f else 15f, s.ink(tx + 30f, p + 6f), 600)
        val subP = c.ink(13f, s.muted(tx + 30f, p + capNum), 450)
        val waiting = if (items.size == 1) "New email" else "New emails"
        if (bare) canvas.drawText(fit(waiting, inboxP, headW), tx, p + capNum, inboxP)
        else {
            canvas.textTop(fit(waiting, inboxP, headW), tx, p + 1f, inboxP)
            val sub = when {
                accounts.size > 1 -> "${accounts.size} accounts"
                accounts.size == 1 -> accounts[0]
                else -> Mail.apps(c).mapNotNull { Mail.APPS[it] }.joinToString(" · ")
            }
            canvas.drawText(fit(sub, subP, headW), tx, p + capNum, subP)
        }
        var y = p + capNum + (if (bare) 12f else 18f)

        if (items.isEmpty()) {
            val emptyP = c.ink(22f, s.ink(s.w / 2, y + 12f), display = true)
            canvas.textTop("All caught up", p, y + 2f, emptyP)
            val noteP = c.ink(13f, s.muted(s.w / 2, y + 34f), 450)
            canvas.textTop(fit("New mail appears here as it arrives", noteP, s.w - 2 * p), p, y + 2f + emptyP.textSize * 1.3f, noteP)
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
        for ((i, m) in items.withIndex()) {
            // A row without a preview line is shorter, so no gap is left where it would have been
            val thisRow = if (tall && m.preview.isEmpty()) 44f else rowH
            val reserve = if (i < items.size - 1 && !bare) 18f else 0f
            if (y + thisRow + reserve > s.h - p + 2f) break
            val mid = y + 9f
            val bar = ACCOUNT[(accounts.indexOf(m.account).coerceAtLeast(0)) % ACCOUNT.size]
            canvas.drawRoundRect(RectF(p, y + 1f, p + 3f, y + thisRow - gap - 1f), 1.5f, 1.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bar })
            val time = when (val d = m.time) {
                in startOfToday()..Long.MAX_VALUE -> android.text.format.DateFormat.getTimeFormat(c).format(java.util.Date(d))
                else -> SimpleDateFormat("EEE", Locale.getDefault()).format(java.util.Date(d))
            }
            timeP.color = s.muted(s.w - p, mid)
            val timeW = timeP.measureText(time)
            canvas.drawText(time, s.w - p, mid + capHeight(timeP) / 2, timeP)
            senderP.color = s.ink(x0 + width / 2, mid)
            canvas.drawText(fit(m.sender, senderP, width - timeW - 10f), x0, mid + capHeight(senderP) / 2, senderP)
            if (!bare) {
                subjectP.color = s.ink(x0 + width / 2, mid + 19f)
                canvas.drawText(fit(m.subject, subjectP, width), x0, mid + 19f + capHeight(subjectP) / 2, subjectP)
                if (tall && m.preview.isNotEmpty()) {
                    previewP.color = s.muted(x0 + width / 2, mid + 37f)
                    canvas.drawText(fit(m.preview, previewP, width), x0, mid + 37f + capHeight(previewP) / 2, previewP)
                }
            }
            val open = if (m.open != null) Act.Open(pending = m.open, intent = c.packageManager.getLaunchIntentForPackage(m.pkg)) else inbox
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
