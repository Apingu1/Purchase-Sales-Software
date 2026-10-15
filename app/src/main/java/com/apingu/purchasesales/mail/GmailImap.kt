package com.apingu.purchasesales.mail

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.apingu.purchasesales.util.InvoiceCandidateCache
import java.io.File
import java.security.KeyStore
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store
import javax.mail.UIDFolder
import javax.mail.internet.MimeUtility
import javax.mail.search.BodyTerm
import javax.mail.search.OrTerm
import javax.mail.search.SubjectTerm

/** Each Gmail App Password is a mailbox credential, NOT the account's normal password. */
object GmailImapCredentials {
    private const val KEY = "purchase_sales_gmail_imap_aes_v1"
    private const val PREFS = "purchase_sales_imap_secrets"
    private fun key(): java.security.Key {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.getKey(KEY, null)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(KEY,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    fun normalizePassword(input: String): String = input.filterNot(Char::isWhitespace)
    fun requireAppPassword(input: String): String {
        val normalized = normalizePassword(input)
        require(normalized.matches(Regex("[A-Za-z0-9]{16}"))) {
            "Enter the 16-character Google App Password (not your normal Gmail password)."
        }
        return normalized
    }

    private fun identifier(email: String) =
        "gmail_imap_" + email.trim().lowercase(java.util.Locale.ROOT)

    fun save(context: Context, email: String, password: String) {
        val normalized = requireAppPassword(password)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encoded = Base64.encodeToString(
            cipher.iv + cipher.doFinal(normalized.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP
        )
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(identifier(email), encoded).commit()) {
            "Unable to securely save this Gmail App Password"
        }
    }

    fun load(context: Context, email: String): String {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(identifier(email), null)
            ?: error("Reconnect Gmail account " + email + " using a Google App Password")
        return try {
            val packed = Base64.decode(encoded, Base64.DEFAULT)
            require(packed.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(),
                GCMParameterSpec(128, packed.copyOfRange(0, 12)))
            String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            error("Stored Gmail App Password is unavailable. Reconnect " + email)
        }
    }

    fun remove(context: Context, email: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(identifier(email)).commit()
    }
}

/**
 * Only connects to Google's fixed Gmail IMAP endpoint, never user-supplied servers.
 * READ_ONLY folder, TLS with hostname verification, no debug logging.
 * Reuse one connection for all purchase order queries for a given mailbox.
 */
class GmailImapSession(private val email: String, appPassword: String) : AutoCloseable {
    private val store: Store
    private val folder: Folder
    private val uidFolder: UIDFolder
    private val uidValidity: Long

    init {
        require(email.trim().matches(Regex("^[A-Za-z0-9._%+\\-]+@gmail\\.com$", RegexOption.IGNORE_CASE))) {
            "Gmail IMAP App Passwords currently support personal @gmail.com addresses"
        }
        val props = Properties().apply {
            setProperty("mail.store.protocol", "imaps")
            setProperty("mail.imaps.host", "imap.gmail.com")
            setProperty("mail.imaps.port", "993")
            setProperty("mail.imaps.ssl.enable", "true")
            setProperty("mail.imaps.ssl.checkserveridentity", "true")
            setProperty("mail.imaps.connectiontimeout", "15000")
            setProperty("mail.imaps.timeout", "30000")
            setProperty("mail.imaps.writetimeout", "30000")
            setProperty("mail.imaps.peek", "true")
            setProperty("mail.debug", "false")
        }
        val instance = Session.getInstance(props)
        val connected = instance.getStore("imaps")
        try {
            connected.connect("imap.gmail.com", 993, email, appPassword)
            // Prefer All Mail so invoices outside Inbox can still be located.
            // Gmail servers may expose localized labels, so fall back to INBOX.
            val all = connected.getFolder("[Gmail]/All Mail")
            val selected = if (all.exists()) all else connected.getFolder("INBOX")
            selected.open(Folder.READ_ONLY)
            store = connected
            folder = selected
            uidFolder = selected as UIDFolder
            uidValidity = uidFolder.uidValidity
        } catch (error: Exception) {
            runCatching { connected.close() }
            throw IllegalStateException(
                "Cannot connect to Gmail IMAP for " + email +
                    ". Check 2-Step Verification, the App Password, and connectivity.", error
            )
        }
    }

    fun search(orderNumber: String): List<MailAttachmentMatch> {
        val target = orderNumber.trim()
        require(target.isNotBlank())
        // IMAP searches subject and message body server-side. The order number
        // must be present in indexed message text, not just a PDF's pixels.
        val term = OrTerm(SubjectTerm(target), BodyTerm(target))
        val matches = folder.search(term).sortedByDescending { it.messageNumber }.take(50)
        val result = mutableListOf<MailAttachmentMatch>()
        for (message in matches) {
            val uid = uidFolder.getUID(message)
            if (uid <= 0L) continue
            val messageKey = "imap:" + uidValidity + ":" + uid
            fun visit(part: Part, path: String) {
                val original = runCatching {
                    part.fileName?.let { MimeUtility.decodeText(it) }
                }.getOrNull().orEmpty()
                val mime = supportedImapAttachment(original)
                if (mime != null && path.isNotBlank() &&
                    (part.size < 0 || part.size <= 25 * 1024 * 1024)) {
                    result += MailAttachmentMatch(messageKey, "part:" + path, original, mime)
                }
                if (part.isMimeType("multipart/*")) {
                    val multiparts = part.content as? Multipart ?: return
                    for (idx in 0 until multiparts.count) {
                        visit(multiparts.getBodyPart(idx), if (path.isBlank()) idx.toString() else path + "." + idx)
                    }
                }
            }
            visit(message, "")
            if (result.size >= 30) break
        }
        return result.distinctBy { it.messageId + ":" + it.attachmentId }.take(30)
    }

    fun stageAttachment(context: Context, candidateId: Long, match: MailAttachmentMatch): File {
        val keys = match.messageId.removePrefix("imap:").split(":")
        require(keys.size == 2 && match.messageId.startsWith("imap:"))
        require(keys[0].toLongOrNull() == uidValidity) { "Gmail mailbox changed; run Finder again" }
        val uid = keys[1].toLongOrNull() ?: error("Invalid Gmail message identifier")
        val message = uidFolder.getMessageByUID(uid) ?: error("Gmail message is no longer available")
        require(match.attachmentId.startsWith("part:"))
        val indices = match.attachmentId.removePrefix("part:").split(".").map {
            it.toIntOrNull()?.takeIf { index -> index in 0..500 }
                ?: error("Invalid MIME part")
        }
        require(indices.isNotEmpty() && indices.size <= 12)
        var part: Part = message
        for (i in indices) {
            require(part.isMimeType("multipart/*"))
            val multi = part.content as Multipart
            require(i < multi.count)
            part = multi.getBodyPart(i)
        }
        require(supportedImapAttachment(
            runCatching { part.fileName?.let(MimeUtility::decodeText) }.getOrNull().orEmpty()
        ) == match.mimeType) { "Gmail attachment type changed" }
        // Bounded streaming to private cache, never place app passwords or
        // unapproved attachment data in Dropbox.
        return InvoiceCandidateCache.stage(context, candidateId, match.mimeType, part.inputStream)
    }

    override fun close() {
        runCatching { folder.close(false) }
        runCatching { store.close() }
    }
}

internal fun supportedImapAttachment(fileName: String): String? =
    when (fileName.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)) {
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        else -> null
    }
