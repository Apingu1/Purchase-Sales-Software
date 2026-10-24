package com.apingu.purchasesales.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apingu.purchasesales.data.PurchaseEntity
import com.apingu.purchasesales.util.displayDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** One available IMEI / serial identifier and the purchase line it belongs to. */
data class ImeiCandidate(
    val identifier: String,
    val purchaseId: Long,
    val supplier: String = "",
    val orderDateEpochDay: Long = 0
)

data class ImeiSelectionGroup(
    val key: String,
    val item: String,
    val requiredQuantity: Int,
    val totalAvailableQuantity: Int,
    val candidates: List<ImeiCandidate>,
    val preselected: Set<String> = emptySet()
)

data class ImeiSelectionRequest(
    val token: Long = System.nanoTime(),
    val groups: List<ImeiSelectionGroup>,
    val onConfirm: (Map<String, Set<String>>) -> Unit
)

object ImeiSelectionCoordinator {
    private val _request = MutableStateFlow<ImeiSelectionRequest?>(null)
    val request: StateFlow<ImeiSelectionRequest?> = _request.asStateFlow()

    fun show(request: ImeiSelectionRequest) {
        _request.value = request
    }

    fun clear() {
        _request.value = null
    }
}

/**
 * Persists the exact identifiers attached to each sale line without changing the Room schema.
 * Sale allocations remain the accounting/inventory source of truth; this store adds unit-level
 * traceability for invoices and later partial sales.
 */
object ImeiAssignmentStore {
    private const val PREFS = "imei_sale_assignments"
    private fun key(saleLineId: Long) = "sale_line_$saleLineId"

    fun get(context: Context, saleLineId: Long): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(saleLineId), null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val value = array.optString(i).trim()
                    if (value.isNotBlank()) add(value)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun put(context: Context, saleLineId: Long, identifiers: List<String>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (identifiers.isEmpty()) {
            prefs.edit().remove(key(saleLineId)).apply()
            return
        }
        val array = JSONArray()
        identifiers.forEach { array.put(it) }
        prefs.edit().putString(key(saleLineId), array.toString()).apply()
    }

    fun clear(context: Context, saleLineId: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key(saleLineId)).apply()
    }
}

/**
 * The purchase form keeps one textbox. For tracked stock, each non-empty line is one identifier.
 * We only promote the textbox into unit-level tracking when the number of identifier-looking lines
 * is sufficient for the received quantity, so ordinary purchase notes are not accidentally treated
 * as IMEIs.
 */
fun trackedIdentifiers(purchase: PurchaseEntity, legacyOrderNote: String = ""): List<String> {
    val note = purchase.notes.trim()
    if (note.isBlank() || note == legacyOrderNote.trim()) return emptyList()
    val lines = note.lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .filter(::looksLikeIdentifier)

    val received = purchase.receivedQty.coerceAtLeast(0)
    if (received <= 0 || lines.size < received) return emptyList()
    return lines.take(received)
}

private fun looksLikeIdentifier(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.length < 6) return false
    val stripped = trimmed
        .replace(Regex("(?i)^IMEI\\s*[:#-]?\\s*"), "")
        .replace(Regex("(?i)^SERIAL(?:\\s+NUMBER)?\\s*[:#-]?\\s*"), "")
        .trim()
    if (stripped.length < 6) return false
    if (stripped.any { it.isWhitespace() }) return false
    return stripped.count { it.isLetterOrDigit() } >= 6
}

@Composable
fun ImeiSelectionOverlay() {
    val request by ImeiSelectionCoordinator.request.collectAsState()
    val active = request ?: return

    var selected by remember(active.token) {
        mutableStateOf(active.groups.associate { it.key to it.preselected.toSet() })
    }

    val valid = active.groups.all { group ->
        selected[group.key].orEmpty().size == group.requiredQuantity
    }

    AlertDialog(
        onDismissRequest = { ImeiSelectionCoordinator.clear() },
        title = { Text("Select IMEI / serial numbers") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Select the exact devices being sold. Supplier and purchase order date are shown for each available IMEI.",
                    style = MaterialTheme.typography.bodyMedium
                )
                active.groups.forEach { group ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(group.item, fontWeight = FontWeight.Bold)
                        Text(
                            "Choose ${group.requiredQuantity} of ${group.totalAvailableQuantity} available units.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        // Borderless column headings stay visible while the identifiers
                        // scroll independently underneath them.
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.width(48.dp))
                            Text("IMEI / SN", Modifier.weight(0.44f),
                                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text("Supplier", Modifier.weight(0.32f),
                                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text("Order date", Modifier.weight(0.24f),
                                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                            group.candidates.forEach { candidate ->
                                val current = selected[group.key].orEmpty()
                                val checked = candidate.identifier in current
                                Row(Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { newValue ->
                                            val updated = current.toMutableSet()
                                            if (newValue) {
                                                if (updated.size < group.requiredQuantity) updated += candidate.identifier
                                            } else {
                                                updated -= candidate.identifier
                                            }
                                            selected = selected + (group.key to updated)
                                        }
                                    )
                                    Text(candidate.identifier, Modifier.weight(0.44f),
                                        style = MaterialTheme.typography.bodySmall)
                                    Text(candidate.supplier.ifBlank { "Unknown" }, Modifier.weight(0.32f),
                                        style = MaterialTheme.typography.bodySmall)
                                    Text(if (candidate.orderDateEpochDay > 0) displayDate(candidate.orderDateEpochDay) else "—",
                                        Modifier.weight(0.24f), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Text(
                            "Selected ${selected[group.key].orEmpty().size} / ${group.requiredQuantity}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val result = selected
                    ImeiSelectionCoordinator.clear()
                    active.onConfirm(result)
                }
            ) { Text("Use selected IMEIs") }
        },
        dismissButton = {
            TextButton(onClick = { ImeiSelectionCoordinator.clear() }) { Text("Cancel") }
        }
    )
}

private const val WHATS_NEW_VERSION = "2026-10-24-purchase-imei-scanner-v1"

@Composable
fun WhatsNewNotice() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("whats_new_notice", Context.MODE_PRIVATE) }
    var show by remember { mutableStateOf(prefs.getString("last_seen", "") != WHATS_NEW_VERSION) }
    if (!show) return

    fun dismiss() {
        prefs.edit().putString("last_seen", WHATS_NEW_VERSION).apply()
        show = false
    }

    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text("What’s new") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("24 October 2026 update", fontWeight = FontWeight.Bold)
                Text("• Scan IMEI barcodes directly while adding or editing purchase items: full-screen camera with scan guide, zoom, torch, camera switch, vibration, multi-scan list and remove controls.")
                Text("• Only valid 15-digit IMEIs are added by default, avoiding accidental product-barcode scans. Optional serial-number mode and manual entry are available. Apply the scan list to the purchase item before saving.")
                androidx.compose.material3.HorizontalDivider()
                Text("Previously added — 22 October 2026", fontWeight = FontWeight.Bold)
                Text("• Inventory now has In Stock and Pending Receipt tabs, with consolidated item quantities and both gross and net purchase-cost totals.")
                Text("• Expense management now has its own navigation tab, selected-period totals, categories, VAT calculations, multiple invoice attachments, and an Expense Invoice Finder with mailbox matching and manual review.")
                Text("• The PDF preview scrolls continuously across all pages of long sales, purchase and expense invoices.")
                Text("• Invoice Finder shows purchase dates in Pending Review and adds a Waived tab. Waiving needs no reason; waived purchases are searched again, cancelled purchases are excluded and refunded purchases remain eligible.")
                Text("• Incorrectly deleted sales invoices free their invoice number for reuse when no other sales invoice uses that number.")
                Text("• Accounting periods can be deleted alone or with eligible related transaction data. Full deletion creates a backup and blocks unsafe cross-period stock/return links.")
                androidx.compose.material3.HorizontalDivider()
                Text("Previously added — 17 October 2026", fontWeight = FontWeight.Bold)
                Text("• Sales invoice preview: open generated sales PDFs directly inside the app from the Sales tab, with a continuously scrollable multi-page preview. Downloading is still available separately.")
                Text("• Purchase delivery charges: enter a gross delivery amount (default £0.00). Delivery is not inventory; it appears in order totals, dashboard profit/VAT calculations and the purchase Excel/VAT worksheet.")
                Text("• IMEI picker: select serials from a clean, borderless IMEI / SN, Supplier, Order date table with column headings that stay visible while the device rows scroll.")
                Text("• Supplier entry: previous-supplier matches now expand inline only when requested, rather than popping over the input after each keystroke.")
                androidx.compose.material3.HorizontalDivider()
                Text("Previously added — 15 October 2026", fontWeight = FontWeight.Bold)
                Text("• Multiple supplier invoices: attach and manage more than one PDF or image invoice against a single purchase order, including split invoices. Add multiple documents at once, preview each one and avoid duplicate attachments.")
                Text("• Invoice Finder: a dedicated invoice centre with Pending Review, Approved and Missing tabs. Run an on-demand search for missing invoices by order number against ONLY the exact purchasing email account recorded on each purchase.")
                Text("• Review before saving: preview PDF, JPG/JPEG and PNG attachments found in email, then approve or reject candidates. Approved invoices are associated with their original purchase; nothing is approved automatically.")
                Text("• Gmail integration: connect individual personal Gmail accounts with Google App Passwords. The built-in ? help explains how to enable 2-Step Verification (2FA), generate an App Password and open Google's official setup pages.")
                Text("• Outlook / Hotmail integration: connect multiple Microsoft accounts through Microsoft's secure browser sign-in. The app's own public sign-in identifier is now included, so friends and family do not have to enter developer settings or API keys.")
                Text("• Dropbox invoice sync: each approved purchase invoice is included in its correct accounting-period folder. Additional documents use 'Invoice 2', 'Invoice 3' and so on, with deletions queued for the next sync.")
                Text("• Invoice Finder only searches on request; each connected mailbox is managed separately on its own phone.")
                androidx.compose.material3.HorizontalDivider()
                Text("Previously added — 13 October 2026", fontWeight = FontWeight.Bold)

                Text("• Business Details now supports an optional business invoice identifier. For example, MSG with customer code CUS produces automatic invoice numbers such as MSG.CUS.131026.01.")
                Text("• Existing sales invoices are not renamed, and manual invoice numbering continues to work exactly as before.")
                Text("• Credit notes can now be deleted. Deleting a credit note removes its sales/VAT adjustment and reverses any stock, COGS and IMEI/serial return created by that credit note.")
                Text("• Credit-note deletion is blocked if stock returned by that credit note has since been sold again.")
                Text("• A deleted CN number becomes available for reuse; for example, deleting CN-000005 allows CN-000005 to be issued again when it is the next available number.")
            }
        },
        confirmButton = {
            TextButton(onClick = ::dismiss) { Text("Got it") }
        }
    )
}
