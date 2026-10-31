package com.fergusonconsultancy.foldwidgets

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Finding, on the phone itself, how Outlook can be asked to open one email (31 Oct). Microsoft
 * documents no such link for Android, and the one Microsoft To Do uses on iPhone did not open the
 * email here. So instead of guessing: the installed Outlook is read for the links it accepts (its
 * manifest's link rules, and every "ms-outlook" link written into its code), candidate links are
 * checked against those rules, and each email tap records which route it actually took. The report
 * holds no email content, no ids and no addresses.
 */
object Probe {
    const val OUTLOOK = "com.microsoft.office.outlook"

    fun note(c: Context, route: String, error: Throwable?) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.UK).format(Date())
        val line = "$time  $route: " + (error?.let { "FAILED (${it.javaClass.simpleName})" } ?: "started")
        val old = Prefs.of(c).getString("probe_taps", "").orEmpty().lines().filter { it.isNotBlank() }.takeLast(7)
        Prefs.of(c).edit().putString("probe_taps", (old + line).joinToString("\n")).apply()
    }

    fun taps(c: Context) = Prefs.of(c).getString("probe_taps", "").orEmpty()

    private val CANDIDATES = listOf(
        "ms-outlook://emails/message/open?restID=X&immutableID=Y&account=Z",
        "ms-outlook://emails/open?restid=X&account=Z",
        "ms-outlook://emails/message?restID=X",
        "ms-outlook://message/open?restID=X",
        "ms-outlook://mail/open?id=X",
        "ms-outlook://emails",
        "ms-outlook://",
        "https://outlook.live.com/mail/0/inbox/id/X",
        "https://outlook.office.com/mail/deeplink/read/X",
    )

    /** The full report. Slow (reads Outlook's code): not on the main thread. */
    fun report(c: Context): String = buildString {
        val pm = c.packageManager
        val info = runCatching { pm.getPackageInfo(OUTLOOK, 0) }.getOrNull()
        appendLine("Fold Widgets link report")
        appendLine("Outlook: " + (info?.let { "${it.versionName} (${it.longVersionCode})" } ?: "not installed"))
        appendLine()
        appendLine("Last email taps:")
        appendLine(taps(c).ifBlank { "(none yet: tap an email in the widget, then make this report)" })
        appendLine()
        appendLine("Links Outlook says it accepts:")
        for (u in CANDIDATES) {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(u)).setPackage(OUTLOOK)
            val hit = runCatching { pm.queryIntentActivities(i, 0) }.getOrDefault(emptyList())
            appendLine((if (hit.isEmpty()) "  no   " else "  YES  ") + u + (hit.firstOrNull()?.activityInfo?.name?.let { "  -> ${it.substringAfterLast('.')}" } ?: ""))
        }
        if (info == null) return@buildString
        val ai = pm.getApplicationInfo(OUTLOOK, 0)
        val apks = listOf(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())
        val manifest = sortedSetOf<String>()
        val code = sortedSetOf<String>()
        for (apk in apks) runCatching {
            ZipFile(apk).use { zip ->
                zip.getEntry("AndroidManifest.xml")?.let { e -> zip.getInputStream(e).use { manifest += manifestStrings(it.readBytes()) } }
                zip.entries().asSequence().filter { it.name.endsWith(".dex") }.forEach { e ->
                    zip.getInputStream(e).use { code += codeStrings(it) }
                }
            }
        }
        appendLine()
        appendLine("Outlook's link rules (manifest):")
        manifest.take(120).forEach { appendLine("  $it") }
        appendLine()
        appendLine("ms-outlook links and message-id names in Outlook's code:")
        code.take(200).forEach { appendLine("  $it") }
    }

    /** Strings in a binary manifest that look like parts of link rules: schemes, hosts, paths. */
    private fun manifestStrings(b: ByteArray): List<String> {
        fun u16(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun u32(o: Int) = u16(o) or (u16(o + 2) shl 16)
        val out = ArrayList<String>()
        val pool = 8
        if (b.size < pool + 28 || u16(pool) != 0x0001) return out
        val count = u32(pool + 8)
        val utf8 = (u32(pool + 16) and 0x100) != 0
        val start = pool + u32(pool + 20)
        for (i in 0 until count) {
            val off = start + u32(pool + 28 + i * 4)
            val s = runCatching {
                if (utf8) {
                    var o = off
                    o += if (b[o].toInt() and 0x80 != 0) 2 else 1
                    var n = b[o].toInt() and 0xFF
                    if (n and 0x80 != 0) { n = ((n and 0x7F) shl 8) or (b[o + 1].toInt() and 0xFF); o += 2 } else o += 1
                    String(b, o, n, Charsets.UTF_8)
                } else {
                    var n = u16(off); var o = off + 2
                    if (n and 0x8000 != 0) { n = ((n and 0x7FFF) shl 16) or u16(off + 2); o += 2 }
                    String(b, o, n * 2, Charsets.UTF_16LE)
                }
            }.getOrNull() ?: continue
            // Keep what can only be link parts: no class names, no resources
            if (s.length in 2..80 && !s.contains(' ') && s.none { it.isUpperCase() } && !s.startsWith("com.") &&
                !s.startsWith("android") && (s.startsWith("/") || s.contains("outlook") || s.contains("mail") ||
                    s.contains("message") || s.contains("email") || s.matches(Regex("[a-z][a-z0-9.-]*\\.[a-z]{2,}"))))
                out += s
        }
        return out
    }

    private val KEYS = listOf("ms-outlook", "restID", "restId", "restid", "immutableID", "immutableId", "message/open", "emails/")

    /** Readable runs in Outlook's code that mention its links or message ids. */
    private fun codeStrings(input: InputStream): List<String> {
        val out = ArrayList<String>()
        val buf = ByteArray(1 shl 16)
        val run = StringBuilder()
        fun flush() {
            if (run.length in 4..160) { val t = run.toString(); if (KEYS.any { t.contains(it) }) out += t }
            run.setLength(0)
        }
        while (true) {
            val n = input.read(buf); if (n <= 0) break
            for (i in 0 until n) {
                val ch = buf[i].toInt() and 0xFF
                if (ch in 0x20..0x7E) { if (run.length < 200) run.append(ch.toChar()) } else flush()
            }
        }
        flush()
        return out
    }
}
