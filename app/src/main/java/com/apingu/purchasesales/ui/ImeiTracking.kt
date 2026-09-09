package com.apingu.purchasesales.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** One available IMEI / serial identifier and the purchase line it belongs to. */
data class ImeiCandidate(
    val identifier: String,
    val purchaseId: Long
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
                    "Some of this item will remain in inventory. Select exactly which identifiers are being sold on this invoice.",
                    style = MaterialTheme.typography.bodyMedium
                )
                active.groups.forEach { group ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(group.item, fontWeight = FontWeight.Bold)
                        Text(
                            "Choose ${group.requiredQuantity} of ${group.totalAvailableQuantity} available unit${if (group.totalAvailableQuantity == 1) "" else "s"}.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        group.candidates.forEach { candidate ->
                            val current = selected[group.key].orEmpty()
                            val checked = candidate.identifier in current
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
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
                                Text(candidate.identifier)
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

private const val WHATS_NEW_VERSION = "2026-09-09-imei-allocation-v1"

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
                Text("September 2026 update", fontWeight = FontWeight.Bold)
                Text("• IMEI / serial tracking now recognises one identifier per line in the existing purchase textbox.")
                Text("• When a sale leaves some of the same item in stock, the app asks which IMEIs are being sold so the correct identifiers appear on that customer’s invoice.")
                Text("• When the entire remaining stock of that item is sold, no extra step is required — all available identifiers are used automatically.")
                Text("• Sales invoices now show Unit Net and Total Net with clearer separation between multiple products.")
                Text("• Duplicate reverse-charge notices at the bottom of reverse VAT invoices have been removed; the reverse-charge notice at the top remains.")
            }
        },
        confirmButton = {
            TextButton(onClick = ::dismiss) { Text("Got it") }
        }
    )
}
