package com.apingu.purchasesales.mail

import android.content.Context
import android.util.Base64
import androidx.room.withTransaction
import com.apingu.purchasesales.data.*
import com.apingu.purchasesales.util.InvoiceCandidateCache
import com.apingu.purchasesales.util.InvoiceFinderRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.time.LocalDate

data class InvoiceScanOutcome(
    val purchasesChecked: Int,
    val newCandidates: Int,
    val skippedPurchases: Int,
    val errors: List<String>
)

/**
 * Manual, bounded scan of ONLY the exact connected account recorded on
 * each invoice-less purchase. Never cross-search mailboxes. Nothing is
 * approved or uploaded until a user confirms the staged invoice.
 */
class InvoiceEmailFinder(private val context: Context, private val db: AppDatabase) {
    private val dao = db.dao()
    suspend fun scan(): InvoiceScanOutcome = withContext(Dispatchers.IO) {
        val orders = dao.getPurchaseOrders()
        val approved = dao.getAllPurchaseInvoices()
        val accounts = dao.getEmailAccounts()
        val errors = mutableListOf<String>()
        var searched = 0
        var found = 0
        var skipped = 0
        val tokens = mutableMapOf<String, String>()
        orders.forEach { order ->
            val blocker = InvoiceFinderRules.blockReason(order, approved, accounts)
            if (blocker != null) { skipped++; return@forEach }
            val mailbox = accounts.first { it.emailAddress.equals(order.accountUsername.trim(), true) }
            try {
                val token = tokens[mailbox.emailAddress] ?: when (mailbox.provider) {
                    "GMAIL" -> {
                        val access = MailOAuth.googleSilentToken(context, mailbox.emailAddress)
                        require(MailOAuth.verifiedGmailAddress(access)
                            .equals(mailbox.emailAddress, ignoreCase = true)) {
                            "Gmail returned a different account. Reconnect " + mailbox.emailAddress
                        }
                        access
                    }
                    "MICROSOFT" -> MailOAuth.microsoftToken(context, mailbox.emailAddress)
                    else -> error("Unsupported mailbox provider " + mailbox.provider)
                }.also { tokens[mailbox.emailAddress] = it }
                searched++
                val results = when (mailbox.provider) {
                    "GMAIL" -> gmailCandidates(token, order.orderNumber)
                    "MICROSOFT" -> microsoftCandidates(token, order.orderNumber)
                    else -> emptyList()
                }
                results.take(30).forEach { match ->
                    val id = db.withTransaction {
                        // Repeat eligibility inside transaction: a newly approved invoice
                        // must not receive further email candidates.
                        val current = dao.getPurchaseOrder(order.id) ?: return@withTransaction 0L
                        if (current.orderNumber.trim() != order.orderNumber.trim() ||
                            !current.accountUsername.trim().equals(mailbox.emailAddress, ignoreCase = true)) {
                            return@withTransaction 0L
                        }
                        if (InvoiceFinderRules.blockReason(current, dao.getAllPurchaseInvoices(),
                                dao.getEmailAccounts()) != null) return@withTransaction 0L
                        val candidate = InvoiceCandidateEntity(
                            purchaseOrderId = current.id,
                            accountEmail = mailbox.emailAddress,
                            messageId = match.messageId,
                            attachmentId = match.attachmentId,
                            originalFileName = match.fileName,
                            mimeType = match.mimeType
                        )
                        val inserted = dao.insertInvoiceCandidate(candidate)
                        if (inserted > 0) inserted else dao.findInvoiceCandidate(
                            order.id, mailbox.emailAddress, match.messageId, match.attachmentId
                        )?.takeIf { it.status == "PENDING_REVIEW" }?.id ?: 0L
                    }
                    if (id <= 0) return@forEach
                    val before = InvoiceCandidateCache.previewFile(context, id)
                    if (before == null) {
                        val content = when (mailbox.provider) {
                            "GMAIL" -> gmailAttachment(token, match)
                            else -> microsoftAttachment(token, match)
                        }
                        require(content.isNotEmpty() && content.size <= 25 * 1024 * 1024) {
                            "Invoice attachment is empty or exceeds 25 MB"
                        }
                        InvoiceCandidateCache.stage(context, id, match.mimeType,
                            ByteArrayInputStream(content))
                        found++
                    }
                }
                dao.updateEmailAccount(mailbox.copy(lastCheckedAtMillis = System.currentTimeMillis()))
            } catch (ex: Exception) {
                errors += order.supplier.take(36) + " / " + order.orderNumber.take(30) +
                    " (" + mailbox.emailAddress + "): " + (ex.message ?: "search error")
            }
        }
        InvoiceScanOutcome(searched, found, skipped, errors.take(10))
    }
}

data class MailAttachmentMatch(
    val messageId: String, val attachmentId: String,
    val fileName: String, val mimeType: String
)

private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

private fun supported(name: String, declaredType: String): String? {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        else -> null
    }
}

private fun gmailCandidates(token: String, orderNumber: String): List<MailAttachmentMatch> {
    val q = "has:attachment " + "\"" + orderNumber.replace("\"", "") + "\""
    val listed = MailRest.getJson(
        "https://gmail.googleapis.com/gmail/v1/users/me/messages?maxResults=50&q=" + enc(q), token
    ).optJSONArray("messages") ?: JSONArray()
    val results = mutableListOf<MailAttachmentMatch>()
    for (i in 0 until minOf(listed.length(), 50)) {
        val mid = listed.optJSONObject(i)?.optString("id").orEmpty()
        if (mid.isBlank()) continue
        val message = MailRest.getJson(
            "https://gmail.googleapis.com/gmail/v1/users/me/messages/" + enc(mid) + "?format=full", token)
        val payload = message.optJSONObject("payload") ?: continue
        fun collect(part: JSONObject) {
            val name = part.optString("filename")
            val mime = supported(name, part.optString("mimeType"))
            val body = part.optJSONObject("body")
            val attachmentId = body?.optString("attachmentId").orEmpty()
            if (mime != null && name.isNotBlank() &&
                (body?.optLong("size", 0) ?: 0L) <= 25L * 1024 * 1024) {
                // Gmail may embed a MIME body without a standalone attachmentId.
                results += MailAttachmentMatch(mid, attachmentId.ifBlank { "inline:" + part.optString("partId") },
                    name, mime)
            }
            val nested = part.optJSONArray("parts") ?: return
            for (n in 0 until nested.length()) nested.optJSONObject(n)?.let(::collect)
        }
        collect(payload)
    }
    return results.distinctBy { it.messageId + ":" + it.attachmentId }.take(30)
}

private fun gmailAttachment(token: String, match: MailAttachmentMatch): ByteArray {
    val data = if (match.attachmentId.startsWith("inline:")) {
        val message = MailRest.getJson(
            "https://gmail.googleapis.com/gmail/v1/users/me/messages/" + enc(match.messageId) + "?format=full", token)
        val target = match.attachmentId.removePrefix("inline:")
        fun seek(part: JSONObject): String? {
            if (part.optString("partId") == target) return part.optJSONObject("body")?.optString("data")
            val parts = part.optJSONArray("parts") ?: return null
            for (n in 0 until parts.length()) {
                val found = parts.optJSONObject(n)?.let(::seek)
                if (!found.isNullOrBlank()) return found
            }
            return null
        }
        seek(message.getJSONObject("payload")).orEmpty()
    } else {
        MailRest.getJson("https://gmail.googleapis.com/gmail/v1/users/me/messages/" +
            enc(match.messageId) + "/attachments/" + enc(match.attachmentId), token)
            .optString("data")
    }
    require(data.isNotBlank()) { "Gmail attachment data is unavailable" }
    return Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP)
}

private fun microsoftCandidates(token: String, orderNumber: String): List<MailAttachmentMatch> {
    // Graph $search looks up subject/from/body on consumer and business mailboxes.
    // Provider search only finds *candidates*; human approval is compulsory.
    val uri = "https://graph.microsoft.com/v1.0/me/messages?%24search=" +
        enc("\"" + orderNumber.replace("\"", "") + "\"") +
        "&%24select=id,subject,hasAttachments&%24top=50"
    val messages = MailRest.getJson(uri, token).optJSONArray("value") ?: JSONArray()
    val results = mutableListOf<MailAttachmentMatch>()
    for (i in 0 until minOf(messages.length(), 50)) {
        val m = messages.optJSONObject(i) ?: continue
        if (!m.optBoolean("hasAttachments")) continue
        val mid = m.optString("id")
        if (mid.isBlank()) continue
        val atts = MailRest.getJson("https://graph.microsoft.com/v1.0/me/messages/" +
            enc(mid) + "/attachments?%24select=id,name,contentType,size,isInline&%24top=50", token)
            .optJSONArray("value") ?: JSONArray()
        for (a in 0 until minOf(atts.length(), 50)) {
            val item = atts.optJSONObject(a) ?: continue
            val name = item.optString("name")
            val mime = supported(name, item.optString("contentType")) ?: continue
            if (!item.optBoolean("isInline") && item.optLong("size", 0) <= 25L * 1024 * 1024) {
                results += MailAttachmentMatch(mid, item.optString("id"), name, mime)
            }
        }
    }
    return results.distinctBy { it.messageId + ":" + it.attachmentId }.take(30)
}

private fun microsoftAttachment(token: String, match: MailAttachmentMatch): ByteArray {
    val json = MailRest.getJson("https://graph.microsoft.com/v1.0/me/messages/" +
        enc(match.messageId) + "/attachments/" + enc(match.attachmentId), token)
    require(json.optString("@odata.type").contains("fileAttachment", ignoreCase = true)) {
        "Attachment is not a downloadable file"
    }
    val bytes = json.optString("contentBytes")
    require(bytes.isNotBlank()) { "Graph did not include file bytes" }
    return Base64.decode(bytes, Base64.DEFAULT)
}
