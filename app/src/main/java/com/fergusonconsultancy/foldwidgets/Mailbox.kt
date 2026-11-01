package com.fergusonconsultancy.foldwidgets

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.sun.mail.iap.Response
import com.sun.mail.imap.IMAPFolder
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.mail.FetchProfile
import javax.mail.Folder
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import org.json.JSONArray
import org.json.JSONObject

/**
 * Secrets (the Gmail app password, Microsoft's sign-in token) sealed with a key held in the phone's
 * keystore. The key never leaves the phone and cannot be read out of it, even by this app.
 */
object Vault {
    private const val ALIAS = "foldwidgets_mail"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return gen.generateKey()
    }

    fun put(c: Context, name: String, value: String?) {
        if (value == null) { Prefs.of(c).edit().remove("vault_$name").apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        Prefs.of(c).edit().putString("vault_$name", Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    fun get(c: Context, name: String): String? {
        val stored = Prefs.of(c).getString("vault_$name", null) ?: return null
        return runCatching {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        }.getOrNull()
    }
}

/** The sign-in was refused: the account must be connected again. Other failures are retried. */
class SignInRefused(message: String) : Exception(message)

/**
 * The inboxes themselves, so the widget shows what Gmail and Outlook show (31 Oct): an email deleted
 * or archived in either app leaves the widget at the next look, read mail turns read.
 *
 * Gmail: IMAP with an app password, read-only, never marking anything read. Outlook.com: Microsoft
 * Graph, signed in with Microsoft (Microsoft stopped accepting passwords from other apps in 2024).
 * Both read only the inbox: sender, subject, a line of the opening, the time and whether it is read.
 * Kept on the phone in this app's private storage; secrets sealed in the keystore. Nothing goes
 * anywhere except to Google and Microsoft themselves.
 *
 * When it looks: every 15 minutes in the background; every 3 minutes while the screen is on; a few
 * seconds after an email app's notifications change; and at intervals after the widget opens an
 * email, so a deletion made there shows within about a minute.
 */
object Mailbox {
    const val GMAIL = "gmail"
    const val OUTLOOK = "outlook"
    private const val SHOW = 25

    private val timer = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var lastSync = 0L

    fun address(c: Context, kind: String): String? = Prefs.of(c).getString("mb_addr_$kind", null)
    fun connected(c: Context) = address(c, GMAIL) != null || address(c, OUTLOOK) != null
    fun addresses(c: Context) = listOfNotNull(address(c, GMAIL), address(c, OUTLOOK))

    /** Only Gmail's Primary tab and Outlook's Focused inbox, as the apps notify. On by default. */
    fun mainOnly(c: Context) = Prefs.of(c).getBoolean("mb_main_only", true)
    fun setMainOnly(c: Context, on: Boolean) { Prefs.of(c).edit().putBoolean("mb_main_only", on).apply(); syncSoon(c) }

    /** A problem worth showing: an account whose sign-in was refused, or which has not been reached lately. */
    fun problem(c: Context, kind: String): String? = Prefs.of(c).getString("mb_problem_$kind", null)
    fun syncedAt(c: Context, kind: String) = Prefs.of(c).getLong("mb_synced_$kind", 0L)

    /** Which app a tap opens: Outlook, which Mechack reads both accounts in, else the account's own app. */
    fun openPackage(c: Context, kind: String): String {
        val outlook = "com.microsoft.office.outlook"
        val gmail = "com.google.android.gm"
        return when {
            // Mechack reads both accounts in Outlook: every email opens there (31 Oct)
            Mail.installed(c, outlook) -> outlook
            Mail.installed(c, gmail) -> gmail
            else -> outlook
        }
    }

    // ---- Connecting -------------------------------------------------------------------------------

    /** Checks the app password by reading the inbox once. Returns a sentence if it failed. Not on the main thread. */
    fun connectGmail(c: Context, address: String, appPassword: String): String? {
        val addr = address.trim()
        val pw = appPassword.replace(" ", "").trim()
        if (!addr.contains("@")) return "Enter the full Gmail address"
        if (pw.length < 16) return "An app password has 16 letters. Create one at myaccount.google.com/apppasswords"
        return try {
            val items = Gmail.fetch(c, addr, pw, mainOnly(c))
            Vault.put(c, "gmail", pw)
            Prefs.of(c).edit().putString("mb_addr_$GMAIL", addr).remove("mb_problem_$GMAIL").apply()
            store(c, GMAIL, items)
            Widgets.later { Widgets.renderAll(c, Kind.MAIL) }
            RefreshJob.scheduleMail(c)
            null
        } catch (e: javax.mail.AuthenticationFailedException) {
            "Google refused that address and app password. Check both, and that IMAP is on in Gmail's settings."
        } catch (e: Exception) {
            "Couldn't reach Gmail: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun disconnect(c: Context, kind: String) {
        Vault.put(c, kind, null)
        if (kind == OUTLOOK) Microsoft.forget(c)
        Prefs.of(c).edit().remove("mb_addr_$kind").remove("mb_cache_$kind").remove("mb_problem_$kind").remove("mb_synced_$kind").apply()
        Widgets.later { Widgets.renderAll(c, Kind.MAIL) }
    }

    // ---- Looking ----------------------------------------------------------------------------------

    fun syncSoon(c: Context, delayMs: Long = 0) {
        val app = c.applicationContext
        timer.schedule({ runCatching { sync(app) } }, delayMs, TimeUnit.MILLISECONDS)
    }

    /** After the widget opens an email: look again while Mechack is likely to be acting on it. */
    fun followUp(c: Context) = listOf(20_000L, 60_000L, 150_000L, 300_000L).forEach { syncSoon(c, it) }

    fun syncIfStale(c: Context, ageMs: Long) { if (System.currentTimeMillis() - lastSync > ageMs) syncSoon(c) }

    /** Reads both inboxes now. Runs one at a time; a look within 8 seconds of the last is skipped. */
    @Synchronized fun sync(c: Context, force: Boolean = false) {
        if (!connected(c)) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSync < 8_000L) return
        lastSync = now
        address(c, GMAIL)?.let { addr ->
            look(c, GMAIL) {
                val pw = Vault.get(c, "gmail") ?: throw SignInRefused("Connect Gmail again")
                try { Gmail.fetch(c, addr, pw, mainOnly(c)) }
                catch (e: javax.mail.AuthenticationFailedException) { throw SignInRefused("Gmail refused the app password. Connect again.") }
            }
        }
        if (address(c, OUTLOOK) != null) look(c, OUTLOOK) { Microsoft.fetch(c, mainOnly(c)) }
        Widgets.later { Widgets.renderAll(c, Kind.MAIL) }
    }

    private fun look(c: Context, kind: String, read: () -> List<MailItem>) {
        try {
            store(c, kind, read())
            Prefs.of(c).edit().remove("mb_problem_$kind").apply()
        } catch (e: SignInRefused) {
            Prefs.of(c).edit().putString("mb_problem_$kind", e.message).apply()
        } catch (e: Exception) {
            // Offline or a passing fault: keep what was last seen, and say so only once it is old
            android.util.Log.w("FoldWidgets", "mail $kind", e)
        }
    }

    private fun store(c: Context, kind: String, items: List<MailItem>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("k", it.key).put("s", it.sender).put("j", it.subject).put("v", it.preview)
                .put("a", it.account).put("t", it.time).put("u", it.fresh).put("l", it.link).put("i", it.ids))
        }
        Prefs.of(c).edit().putString("mb_cache_$kind", arr.toString()).putLong("mb_synced_$kind", System.currentTimeMillis()).apply()
    }

    /** Both inboxes as last read, newest first. [fresh] means unread. */
    fun items(c: Context): List<MailItem> =
        listOf(GMAIL, OUTLOOK).filter { address(c, it) != null }.flatMap { kind ->
            val pkg = openPackage(c, kind)
            runCatching {
                val arr = JSONArray(Prefs.of(c).getString("mb_cache_$kind", "[]"))
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val sender = o.optString("s"); val subject = o.optString("j")
                    MailItem(o.optString("k"), pkg, sender, subject, o.optString("v"), o.optString("a"), o.optLong("t"),
                        Mail.linkFor(sender, subject), o.optBoolean("u"), o.optString("l"), kind, o.optString("i"))
                }
            }.getOrDefault(emptyList())
        }.sortedByDescending { it.time }

    /** The line under the heading when something is wrong, or the inbox has not been reached for a while. */
    fun warning(c: Context): String? {
        for (kind in listOf(GMAIL, OUTLOOK)) {
            if (address(c, kind) == null) continue
            problem(c, kind)?.let { return it }
            val at = syncedAt(c, kind)
            if (at > 0 && System.currentTimeMillis() - at > 45 * 60_000L) {
                val time = android.text.format.DateFormat.getTimeFormat(c).format(java.util.Date(at))
                return "${if (kind == GMAIL) "Gmail" else "Outlook"} not reached since $time"
            }
        }
        return null
    }

    // ---- Shared ----------------------------------------------------------------------------------

    /** The first lines of an email's text, as one line. */
    fun opening(text: String): String = text
        .replace(Regex("(?s)<style.*?</style>|<script.*?</script>"), " ")
        .let { if (it.contains('<') && it.contains('>')) android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_COMPACT).toString() else it }
        .replace('\u00A0', ' ').replace(Regex("[\\u200B-\\u200D\\uFEFF\\u034F\\uFFFC\\uFFFD]"), "")
        .replace(Regex("[-_=*~]{3,}"), " ")
        .replace(Regex("\\s+"), " ").trim().take(200)
}

/** Gmail over IMAP, read-only. Opening a message here never marks it read. */
object Gmail {
    private val previews = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun fetch(c: Context, address: String, password: String, primaryOnly: Boolean): List<MailItem> {
        val props = Properties().apply {
            // The library's Gmail mode reads Gmail's own message and thread ids (1 Nov: asked for
            // the generic way, the library rejected them and no ids arrived)
            for (proto in listOf("imaps", "gimaps")) {
                put("mail.$proto.host", "imap.gmail.com")
                put("mail.$proto.port", "993")
                put("mail.$proto.ssl.checkserveridentity", "true")
                put("mail.$proto.connectiontimeout", "15000")
                put("mail.$proto.timeout", "20000")
                // Read bodies with PEEK: looking at an email must never mark it read
                put("mail.$proto.peek", "true")
            }
        }
        val store = com.sun.mail.gimap.GmailSSLStore(Session.getInstance(props), null)
        store.connect("imap.gmail.com", 993, address, password)
        try {
            val inbox = store.getFolder("INBOX") as IMAPFolder
            inbox.open(Folder.READ_ONLY)
            val count = inbox.messageCount
            if (count == 0) return emptyList()
            val numbers = (if (primaryOnly) primary(inbox) else null)
                ?: (maxOf(1, count - (2 * 25) + 1)..count).toList()
            val chosen = numbers.sorted().takeLast(25)
            if (chosen.isEmpty()) return emptyList()
            val msgs = inbox.getMessages(chosen.toIntArray())
            inbox.fetch(msgs, FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE); add(FetchProfile.Item.FLAGS); add(UIDFolder.FetchProfileItem.UID)
            })
            runCatching { inbox.fetch(msgs, FetchProfile().apply {
                add(com.sun.mail.gimap.GmailFolder.FetchProfileItem.MSGID); add(com.sun.mail.gimap.GmailFolder.FetchProfileItem.THRID)
            }) }.onFailure { Prefs.of(c).edit().putString("gmail_ids_error", it.toString()).apply() }
            val items = msgs.reversed().mapIndexed { i, m ->
                val uid = inbox.getUID(m)
                val g = (m as? com.sun.mail.gimap.GmailMessage)?.let { gm -> runCatching { gm.msgId to gm.thrId }.getOrNull() }
                val header = runCatching { (m as javax.mail.internet.MimeMessage).messageID }.getOrNull().orEmpty()
                val key = "g$uid"
                val from = m.from?.firstOrNull() as? InternetAddress
                val sender = from?.personal?.takeIf { it.isNotBlank() } ?: from?.address.orEmpty()
                // A line of the opening for the newest dozen, read once and kept
                val preview = previews[key] ?: if (i < 12) runCatching { Mailbox.opening(text(m).orEmpty()) }.getOrDefault("").also { previews[key] = it } else ""
                MailItem(key, "", sender, m.subject.orEmpty().trim(), preview, address,
                    (m.receivedDate ?: m.sentDate)?.time ?: 0L, null,
                    fresh = !m.flags.contains(javax.mail.Flags.Flag.SEEN),
                    link = "",
                    source = Mailbox.GMAIL,
                    ids = if (g == null) "" else "${g.first}|${g.second}|$header")
            }
            inbox.close(false)
            return items
        } finally {
            runCatching { store.close() }
        }
    }

    /** Message numbers in the Primary tab, using Gmail's own search. Null if Gmail declines. */
    private fun primary(inbox: IMAPFolder): List<Int>? = runCatching {
        @Suppress("UNCHECKED_CAST")
        val replies = inbox.doCommand(IMAPFolder.ProtocolCommand { p -> p.command("SEARCH X-GM-RAW \"category:primary\"", null) }) as Array<Response>
        if (replies.any { it.isBAD || it.isNO }) return@runCatching null
        // Without a recognisable SEARCH line, show the whole inbox rather than nothing
        val lines = replies.map { it.toString().trim().removePrefix("*").trim() }.filter { it.startsWith("SEARCH") }
        if (lines.isEmpty()) return@runCatching null
        lines.flatMap { line -> line.removePrefix("SEARCH").trim().split(' ').mapNotNull { it.trim().toIntOrNull() } }
    }.getOrNull()

    /** Gmail's conversation id for each message number: what its web and app links are built from. */
    /** The plain text of a message, or its HTML if it has no plain part. */
    private fun text(p: Part, depth: Int = 0): String? {
        if (depth > 5) return null
        return when {
            p.isMimeType("text/plain") -> p.content as? String
            p.isMimeType("multipart/*") -> {
                val mp = p.content as Multipart
                val parts = (0 until mp.count).map { mp.getBodyPart(it) }
                parts.firstNotNullOfOrNull { if (it.isMimeType("text/plain") || it.isMimeType("multipart/*")) text(it, depth + 1) else null }
                    ?: parts.firstNotNullOfOrNull { if (it.isMimeType("text/html")) it.content as? String else null }
            }
            p.isMimeType("text/html") -> p.content as? String
            else -> null
        }
    }
}

/**
 * Outlook.com through Microsoft Graph. Signing in happens in the browser on Microsoft's own page,
 * with a one-time code exchanged here (PKCE), so no password ever passes through this app. Microsoft
 * requires every app to be registered; Mechack registers this one and pastes its ID in settings.
 */
object Microsoft {
    const val REDIRECT = "foldwidgets://auth"
    private const val AUTH = "https://login.microsoftonline.com/consumers/oauth2/v2.0"
    private const val SCOPES = "offline_access User.Read Mail.Read"
    @Volatile private var access: String? = null
    @Volatile private var expires = 0L

    fun clientId(c: Context): String? = Prefs.of(c).getString("ms_client", null)
    fun setClientId(c: Context, id: String) = Prefs.of(c).edit().putString("ms_client", id.trim()).apply()

    fun forget(c: Context) { access = null; expires = 0L; Vault.put(c, "outlook", null) }

    /** Microsoft's sign-in page, ready to open in the browser. */
    fun signInUri(c: Context): Uri {
        val rnd = SecureRandom()
        fun token(n: Int) = Base64.encodeToString(ByteArray(n).also { rnd.nextBytes(it) }, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val verifier = token(48)
        val state = token(16)
        Prefs.of(c).edit().putString("ms_verifier", verifier).putString("ms_state", state).apply()
        val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        return Uri.parse("$AUTH/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId(c))
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT)
            .appendQueryParameter("response_mode", "query")
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .appendQueryParameter("prompt", "select_account")
            .build()
    }

    /** Finishes signing in with the code Microsoft returned. Returns a sentence if it failed. Not on the main thread. */
    fun finish(c: Context, uri: Uri): String? {
        uri.getQueryParameter("error")?.let { return uri.getQueryParameter("error_description")?.substringBefore("\r")?.substringBefore("Trace ID") ?: it }
        val code = uri.getQueryParameter("code") ?: return "Microsoft did not return a sign-in code"
        if (uri.getQueryParameter("state") != Prefs.of(c).getString("ms_state", null)) return "That sign-in didn't come from this phone. Try again."
        val verifier = Prefs.of(c).getString("ms_verifier", null) ?: return "Start signing in from settings again"
        return try {
            tokens(c, mapOf("grant_type" to "authorization_code", "code" to code, "redirect_uri" to REDIRECT, "code_verifier" to verifier))
            Prefs.of(c).edit().remove("ms_verifier").remove("ms_state").apply()
            val me = JSONObject(get("https://graph.microsoft.com/v1.0/me?\$select=mail,userPrincipalName", access!!))
            val addr = me.optString("mail").takeIf { it.isNotBlank() && it != "null" } ?: me.optString("userPrincipalName")
            Prefs.of(c).edit().putString("mb_addr_${Mailbox.OUTLOOK}", addr).remove("mb_problem_${Mailbox.OUTLOOK}").apply()
            Mailbox.sync(c, force = true)
            RefreshJob.scheduleMail(c)
            null
        } catch (e: Exception) {
            "Couldn't finish signing in: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** The inbox's newest emails. Focused only when [focusedOnly]. */
    fun fetch(c: Context, focusedOnly: Boolean): List<MailItem> {
        val token = accessToken(c)
        val url = "https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messages?\$top=50" +
            "&\$select=id,subject,bodyPreview,receivedDateTime,isRead,from,inferenceClassification,internetMessageId" +
            "&\$orderby=" + URLEncoder.encode("receivedDateTime desc", "UTF-8")
        val list = JSONObject(get(url, token)).optJSONArray("value") ?: JSONArray()
        // Outlook's own link to one email carries both of Microsoft's ids for it, as Microsoft To Do's
        // "Open in Outlook" does; the immutable one comes from the same list asked for in that form
        val immutable = runCatching {
            val alt = JSONObject(get("https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messages?\$top=50" +
                "&\$select=id,internetMessageId&\$orderby=" + URLEncoder.encode("receivedDateTime desc", "UTF-8"),
                token, immutableIds = true)).optJSONArray("value") ?: JSONArray()
            (0 until alt.length()).map { alt.getJSONObject(it) }.associate { it.optString("internetMessageId") to it.optString("id") }
        }.getOrDefault(emptyMap())
        val addr = Mailbox.address(c, Mailbox.OUTLOOK).orEmpty()
        return (0 until list.length()).map { list.getJSONObject(it) }
            .filter { !focusedOnly || it.optString("inferenceClassification") != "other" }
            .take(25)
            .map { o ->
                val from = o.optJSONObject("from")?.optJSONObject("emailAddress")
                val sender = from?.optString("name")?.takeIf { it.isNotBlank() } ?: from?.optString("address").orEmpty()
                val id = o.optString("id")
                MailItem("o" + id.takeLast(24), "", sender, o.optString("subject").trim(),
                    Mailbox.opening(o.optString("bodyPreview")), addr,
                    runCatching { java.time.Instant.parse(o.optString("receivedDateTime")).toEpochMilli() }.getOrDefault(0L), null,
                    fresh = !o.optBoolean("isRead", true),
                    // Outlook's own link to one message. Tested on Mechack's phone (1 Nov, Outlook 5.2642):
                    // this form, and the same without the immutable id, open the email itself; the
                    // immutable id alone, other paths and Outlook's web read links do not
                    link = "ms-outlook://emails/message/open?restID=${Uri.encode(id)}" +
                        (immutable[o.optString("internetMessageId")]?.let { "&immutableID=${Uri.encode(it)}" } ?: "") +
                        "&account=${Uri.encode(addr)}",
                    source = Mailbox.OUTLOOK)
            }
    }

    private fun accessToken(c: Context): String {
        access?.let { if (System.currentTimeMillis() < expires - 60_000L) return it }
        val refresh = Vault.get(c, "outlook") ?: throw SignInRefused("Sign in to Outlook again")
        tokens(c, mapOf("grant_type" to "refresh_token", "refresh_token" to refresh))
        return access!!
    }

    private fun tokens(c: Context, form: Map<String, String>) {
        val id = clientId(c) ?: throw SignInRefused("Add the app's Microsoft ID in settings")
        val body = (form + mapOf("client_id" to id, "scope" to SCOPES)).entries
            .joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val conn = (URL("$AUTH/token").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        val o = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
        if (code !in 200..299) {
            val err = o.optString("error")
            val why = o.optString("error_description").substringBefore("\r").substringBefore("Trace ID").trim()
            if (err == "invalid_grant" || err == "interaction_required" || err == "invalid_client" || err == "unauthorized_client")
                throw SignInRefused("Outlook needs signing in again")
            throw java.io.IOException(why.ifEmpty { "Microsoft answered $code" })
        }
        access = o.getString("access_token")
        expires = System.currentTimeMillis() + o.optLong("expires_in", 3600L) * 1000L
        // Microsoft hands out a new refresh token each time: keep the newest
        o.optString("refresh_token").takeIf { it.isNotBlank() }?.let { Vault.put(c, "outlook", it) }
    }

    private fun get(url: String, token: String, immutableIds: Boolean = false): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            if (immutableIds) setRequestProperty("Prefer", "IdType=\"ImmutableId\"")
        }
        val code = conn.responseCode
        if (code == 401) { access = null; throw java.io.IOException("Outlook token expired") }
        if (code !in 200..299) throw java.io.IOException("Outlook answered $code")
        return conn.inputStream.bufferedReader().use { it.readText() }
    }
}

/** Microsoft's sign-in page returns here with its one-time code. */
class AuthActivity : android.app.Activity() {
    override fun onCreate(saved: android.os.Bundle?) {
        super.onCreate(saved)
        val uri = intent?.data
        val app = applicationContext
        if (uri != null) Thread {
            val failed = Microsoft.finish(app, uri)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(app, failed ?: "Outlook connected", android.widget.Toast.LENGTH_LONG).show()
            }
        }.start()
        startActivity(android.content.Intent(this, SettingsActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}
