package com.apingu.purchasesales.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.apingu.purchasesales.data.*
import com.apingu.purchasesales.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Phase 2: local invoice management only; no email connector exists yet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvoiceFinderScreen(vm: AppViewModel, nav: NavHostController) {
    val orders by vm.purchaseOrders.collectAsStateWithLifecycle()
    val docs by vm.purchaseInvoices.collectAsStateWithLifecycle()
    val suggestions by vm.pendingInvoiceCandidates.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var target by remember { mutableStateOf<Long?>(null) }
    var preview by remember { mutableStateOf<PurchaseInvoiceEntity?>(null) }
    var reject by remember { mutableStateOf<InvoiceCandidateEntity?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        target?.let { if (uris.isNotEmpty()) vm.addPurchaseInvoices(it, uris) }
        target = null
    }
    val grouped = docs.groupBy { it.purchaseOrderId }
    val approved = orders.filter { !grouped[it.id].isNullOrEmpty() }
    val missing = orders.filter { grouped[it.id].isNullOrEmpty() }
    val orderMap = orders.associateBy { it.id }
    fun matches(o: PurchaseOrderEntity): Boolean = query.isBlank() ||
        o.supplier.contains(query, true) || o.orderNumber.contains(query, true) ||
        o.accountUsername.contains(query, true)
    fun addInvoice(id: Long) {
        target = id
        picker.launch(arrayOf("application/pdf", "image/png", "image/jpeg"))
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Invoice Finder") },
            navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "Back") } }) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { insets ->
        Column(Modifier.padding(insets).fillMaxSize()) {
            ElevatedCard(Modifier.fillMaxWidth().padding(12.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Purchase invoice centre", fontWeight = FontWeight.SemiBold)
                    Text(suggestions.size.toString() + " pending  •  " + approved.size + " approved  •  " + missing.size + " missing",
                        style = MaterialTheme.typography.bodySmall)
                    Button({}, modifier = Modifier.fillMaxWidth(), enabled = false) {
                        Icon(Icons.Default.FindInPage, null); Text(" Run Invoice Finder")
                    }
                    Text("Email searching is not yet connected. Manual attachments and local previews are available now.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Pending Review (" + suggestions.size + ")") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Approved (" + approved.size + ")") })
                Tab(tab == 2, { tab = 2 }, text = { Text("Missing (" + missing.size + ")") })
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                label = { Text("Search supplier, order or email") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotBlank()) IconButton({ query = "" }) { Icon(Icons.Default.Close, "Clear") } },
                singleLine = true
            )
            when (tab) {
                0 -> {
                    val filtered = suggestions.filter { c ->
                        (orderMap[c.purchaseOrderId]?.let(::matches) == true) ||
                            c.originalFileName.contains(query, true) || c.accountEmail.contains(query, true)
                    }
                    if (filtered.isEmpty()) FinderEmpty("Nothing awaiting review",
                        "Invoices discovered by mailbox scans will appear here after email integration.")
                    else LazyColumn(
                        Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filtered, key = { it.id }) { c ->
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(orderMap[c.purchaseOrderId]?.supplier ?: "Purchase unavailable",
                                        fontWeight = FontWeight.SemiBold)
                                    Text(c.originalFileName)
                                    Text("Mailbox: " + c.accountEmail, style = MaterialTheme.typography.bodySmall)
                                    Text("Attachment preview and approval require the upcoming email retrieval connector.",
                                        style = MaterialTheme.typography.bodySmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton({ nav.navigate("purchase/" + c.purchaseOrderId) }) { Text("Open purchase") }
                                        OutlinedButton({ reject = c }) { Text("Reject match") }
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> {
                    val filtered = approved.filter(::matches)
                    if (filtered.isEmpty()) FinderEmpty("No approved invoices", "Attach an invoice to a purchase to see it here.")
                    else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(filtered, key = { it.id }) { order ->
                            val inv = grouped[order.id].orEmpty()
                            FinderOrderCard(order, inv.size.toString() + " approved invoice(s)", "Approved",
                                { nav.navigate("invoice-finder/purchase/" + order.id) }, { addInvoice(order.id) })
                            inv.sortedBy { it.invoiceOrdinal }.forEach { item ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.PictureAsPdf, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(item.displayName(order), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    TextButton({ preview = item }) { Text("View") }
                                }
                            }
                        }
                    }
                }
                else -> {
                    val filtered = missing.filter(::matches)
                    if (filtered.isEmpty()) FinderEmpty("No missing invoices", "All purchases have an approved invoice.")
                    else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(filtered, key = { it.id }) { order ->
                            val label = when {
                                suggestions.any { it.purchaseOrderId == order.id } -> "Awaiting review"
                                order.orderNumber.isBlank() -> "Order number required"
                                order.accountUsername.isBlank() -> "Purchasing email required"
                                !order.accountUsername.contains('@') -> "Valid email address required"
                                else -> "No approved invoice attached"
                            }
                            FinderOrderCard(order, label, "Missing",
                                { nav.navigate("purchase/" + order.id) }, { addInvoice(order.id) })
                        }
                    }
                }
            }
        }
    }
    preview?.let { doc ->
        val order = orderMap[doc.purchaseOrderId]
        InvoiceFilePreview(doc.filePath, order?.let { doc.displayName(it) } ?: doc.originalFileName) {
            preview = null
        }
    }
    reject?.let { c ->
        AlertDialog(
            onDismissRequest = { reject = null },
            title = { Text("Reject suggestion?") },
            text = { Text("The candidate will leave Pending Review. Its source email is unchanged.") },
            confirmButton = { TextButton({ vm.rejectFoundInvoice(c.id); reject = null }) { Text("Reject") } },
            dismissButton = { TextButton({ reject = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun FinderOrderCard(
    order: PurchaseOrderEntity, detail: String, status: String,
    open: () -> Unit, add: () -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(order.supplier, fontWeight = FontWeight.SemiBold)
                    Text(editDate(order.purchaseDateEpochDay) + " • " +
                        order.orderNumber.ifBlank { "No order number" }, style = MaterialTheme.typography.bodySmall)
                }
                Text(status, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
            Text(detail, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(open) { Text(if (status == "Missing") "Open purchase" else "Manage") }
                Button(add) { Icon(Icons.Default.AttachFile, null, Modifier.size(16.dp)); Text(" Add invoice") }
            }
        }
    }
}
