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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    val orders by vm.purchaseOrders.collectAsStateWithLifecycle()
    val docs by vm.purchaseInvoices.collectAsStateWithLifecycle()
    val purchaseLines by vm.purchases.collectAsStateWithLifecycle()
    val suggestions by vm.pendingInvoiceCandidates.collectAsStateWithLifecycle()
    val accounts by vm.emailAccounts.collectAsStateWithLifecycle()
    val running by vm.finderRunning.collectAsStateWithLifecycle()
    val progress by vm.finderProgress.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<Long?>(null) }
    var preview by remember { mutableStateOf<PurchaseInvoiceEntity?>(null) }
    var reject by remember { mutableStateOf<InvoiceCandidateEntity?>(null) }
    var candidatePreview by remember { mutableStateOf<Pair<String, String>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        target?.let { if (uris.isNotEmpty()) vm.addPurchaseInvoices(it, uris) }
        target = null
    }
    val grouped = docs.groupBy { it.purchaseOrderId }
    val linesByOrder = purchaseLines.groupBy { it.purchaseOrderId }
    val eligible = orders.filter { order ->
        val lines = linesByOrder[order.id].orEmpty()
        lines.isNotEmpty() && !lines.all(::isFullyCancelledPurchase)
    }
    val orderMap = eligible.associateBy { it.id }
    val suggestionsForEligible = suggestions.filter { orderMap.containsKey(it.purchaseOrderId) }
    val awaiting = suggestionsForEligible.map { it.purchaseOrderId }.toSet()
    val approved = eligible.filter { !grouped[it.id].isNullOrEmpty() }
    val waived = eligible.filter { grouped[it.id].isNullOrEmpty() && it.invoiceWaived && it.id !in awaiting }
    val missing = eligible.filter { grouped[it.id].isNullOrEmpty() && !it.invoiceWaived && it.id !in awaiting }
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
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Purchase invoice centre", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        TextButton({ nav.navigate("invoice-finder/expenses") }) { Text("Expenses") }
                    }
                    Text(suggestionsForEligible.size.toString() + " pending  •  " + approved.size +
                            " approved  •  " + missing.size + " missing  •  " + waived.size + " waived",
                        style = MaterialTheme.typography.bodySmall)
                    Button({ vm.runInvoiceFinder() }, modifier = Modifier.fillMaxWidth(),
                        enabled = !running && accounts.any { it.isEnabled && it.connectionState == "CONNECTED" }) {
                        Icon(Icons.Default.FindInPage, null)
                        Text(if (running) " Searching..." else " Run Invoice Finder")
                    }
                    OutlinedButton({ nav.navigate("email-accounts") }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Email, null); Text(" Connected mailboxes")
                    }
                    if (progress.isNotBlank()) Text(progress,
                        style = MaterialTheme.typography.bodySmall)
                    Text("Search invoice-less purchases, including waived purchases, using their exact connected mailbox. All matches require approval.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Pending Review (" + suggestionsForEligible.size + ")") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Approved (" + approved.size + ")") })
                Tab(tab == 2, { tab = 2 }, text = { Text("Missing (" + missing.size + ")") })
                Tab(tab == 3, { tab = 3 }, text = { Text("Waived (" + waived.size + ")") })
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
                    val filtered = suggestionsForEligible.filter { c ->
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
                            val staged = InvoiceCandidateCache.previewFile(context, c.id)
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(orderMap[c.purchaseOrderId]?.supplier ?: "Purchase unavailable",
                                        fontWeight = FontWeight.SemiBold)
                                    orderMap[c.purchaseOrderId]?.let { order ->
                                        Text("Purchase: " + editDate(order.purchaseDateEpochDay) +
                                            " • " + order.orderNumber.ifBlank { "No order number" },
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text(c.originalFileName)
                                    Text("Mailbox: " + c.accountEmail, style = MaterialTheme.typography.bodySmall)
                                    if (staged == null) {
                                        Text("Attachment not yet fetched. Preview and approval become available after the email connector retrieves the file.",
                                            style = MaterialTheme.typography.bodySmall)
                                    } else {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button({ candidatePreview = staged.absolutePath to c.originalFileName }) {
                                                Icon(Icons.Default.Visibility, null); Text(" Preview")
                                            }
                                            Button({ vm.approveFoundInvoice(c.id) }) {
                                                Icon(Icons.Default.Check, null); Text(" Approve")
                                            }
                                        }
                                    }
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
                2 -> {
                    val filtered = missing.filter(::matches)
                    if (filtered.isEmpty()) FinderEmpty("No missing invoices", "All purchases have an approved invoice.")
                    else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(filtered, key = { it.id }) { order ->
                            val label = when {
                                suggestions.any { it.purchaseOrderId == order.id } -> "Awaiting review"
                                else -> InvoiceFinderRules.blockReason(order, docs, accounts)
                                    ?: "Ready to search when email integration is enabled"
                            }
                            FinderOrderCard(order, label, "Missing",
                                { nav.navigate("purchase/" + order.id) }, { addInvoice(order.id) },
                                waive = { vm.setInvoiceWaived(order.id, true) })
                        }
                    }
                }
                else -> {
                    val filtered = waived.filter(::matches)
                    if (filtered.isEmpty()) FinderEmpty("No waived invoices",
                        "Waived purchases remain eligible for future invoice searches.")
                    else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(filtered, key = { it.id }) { order ->
                            FinderOrderCard(order, "Still included in future Invoice Finder searches", "Waived",
                                { nav.navigate("purchase/" + order.id) }, { addInvoice(order.id) },
                                undoWaive = { vm.setInvoiceWaived(order.id, false) })
                        }
                    }
                }
            }
        }
    }
    candidatePreview?.let { item ->
        InvoiceFilePreview(item.first, item.second) { candidatePreview = null }
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
    open: () -> Unit, add: () -> Unit,
    waive: (() -> Unit)? = null, undoWaive: (() -> Unit)? = null
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
            if (waive != null) OutlinedButton(waive) { Text("Waive invoice") }
            if (undoWaive != null) OutlinedButton(undoWaive) { Text("Undo waiver") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvoicePurchaseDetailScreen(vm: AppViewModel, nav: NavHostController, orderId: Long) {
    val orders by vm.purchaseOrders.collectAsStateWithLifecycle()
    val allDocs by vm.purchaseInvoices.collectAsStateWithLifecycle()
    val order = orders.firstOrNull { it.id == orderId }
    val docs = allDocs.filter { it.purchaseOrderId == orderId }
        .sortedWith(compareBy<PurchaseInvoiceEntity> { it.invoiceOrdinal }.thenBy { it.id })
    var preview by remember { mutableStateOf<PurchaseInvoiceEntity?>(null) }
    var removal by remember { mutableStateOf<PurchaseInvoiceEntity?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addPurchaseInvoices(orderId, uris)
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Purchase invoices") },
            navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "Back") } }) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        if (order == null) Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Purchase not found")
        } else Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(order.supplier, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(editDate(order.purchaseDateEpochDay) + " • " + order.orderNumber.ifBlank { "No order number" })
            OutlinedButton({ nav.navigate("purchase/" + orderId) }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.OpenInNew, null); Text(" Open purchase")
            }
            Button({ picker.launch(arrayOf("application/pdf", "image/png", "image/jpeg")) },
                Modifier.fillMaxWidth()) { Icon(Icons.Default.AttachFile, null); Text(" Add invoice(s)") }
            Text(docs.size.toString() + " approved document(s)", style = MaterialTheme.typography.titleMedium)
            if (docs.isEmpty()) Text("No approved invoices attached.")
            docs.forEach { doc ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(doc.displayName(order), fontWeight = FontWeight.SemiBold)
                        Text("Original: " + doc.originalFileName.ifBlank { File(doc.filePath).name },
                            style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({ preview = doc }) {
                                Icon(Icons.Default.Visibility, null, Modifier.size(18.dp)); Text(" Preview")
                            }
                            OutlinedButton({ removal = doc }) {
                                Icon(Icons.Default.DeleteOutline, null, Modifier.size(18.dp)); Text(" Remove")
                            }
                        }
                    }
                }
            }
            Text("Approved invoices are queued for Dropbox under their purchase date and supplier, using the configured accounting period.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val shown = preview
    if (shown != null && order != null) InvoiceFilePreview(shown.filePath, shown.displayName(order)) {
        preview = null
    }
    removal?.let { doc ->
        AlertDialog(
            onDismissRequest = { removal = null },
            title = { Text("Remove this invoice?") },
            text = { Text("Other invoices remain linked. This does not change stock or accounting values.") },
            confirmButton = { TextButton({ vm.deletePurchaseInvoice(doc.id); removal = null }) { Text("Remove") } },
            dismissButton = { TextButton({ removal = null }) { Text("Cancel") } }
        )
    }
}

private fun PurchaseInvoiceEntity.displayName(order: PurchaseOrderEntity): String =
    if (order.invoiceSequence > 0)
        InvoiceDocumentNaming.invoiceFileName(order.purchaseDateEpochDay, order.supplier,
            order.invoiceSequence, invoiceOrdinal, File(filePath).extension.ifBlank { "pdf" })
    else originalFileName.ifBlank { File(filePath).name }

@Composable
private fun FinderEmpty(title: String, detail: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.FolderOpen, null, Modifier.size(38.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private sealed interface PreviewResult {
    data object Loading : PreviewResult
    data class Ready(val bitmap: Bitmap, val pages: Int) : PreviewResult
    data class Failed(val message: String) : PreviewResult
}

/** Continuous, lazily rendered PDF pages shared by sales, purchases and expenses. */
@Composable
fun InvoiceFilePreview(path: String, name: String, onClose: () -> Unit) {
    val pageCount by produceState<Int?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(path)
                require(file.isFile) { "Invoice file is missing from this device" }
                if (!file.extension.equals("pdf", true)) 1
                else ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { it.pageCount }
                }
            }.getOrDefault(-1)
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    IconButton(onClose) { Icon(Icons.Default.Close, "Close preview") }
                }
                HorizontalDivider()
                when (val count = pageCount) {
                    null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    -1, 0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Unable to open this invoice on this device")
                    }
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(count) { page ->
                            val result by produceState<PreviewResult>(PreviewResult.Loading, path, page) {
                                value = withContext(Dispatchers.IO) {
                                    runCatching { renderInvoicePage(File(path), page) }
                                        .getOrElse { PreviewResult.Failed(it.message ?: "Unable to open page") }
                                }
                            }
                            when (val state = result) {
                                PreviewResult.Loading -> Box(Modifier.fillMaxWidth().height(160.dp),
                                    contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                                is PreviewResult.Failed -> Text(state.message, Modifier.padding(16.dp))
                                is PreviewResult.Ready -> {
                                    Column {
                                        if (count > 1) Text("Page " + (page + 1) + " of " + count,
                                            style = MaterialTheme.typography.labelSmall)
                                        Image(state.bitmap.asImageBitmap(),
                                            contentDescription = "Invoice page " + (page + 1),
                                            modifier = Modifier.fillMaxWidth().aspectRatio(
                                                state.bitmap.width.toFloat() / state.bitmap.height.toFloat()),
                                            contentScale = ContentScale.Fit)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun renderInvoicePage(file: File, page: Int): PreviewResult {
    require(file.isFile) { "Invoice file is missing from this device" }
    if (file.extension.equals("pdf", ignoreCase = true)) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { pdf ->
                require(pdf.pageCount > 0) { "PDF has no pages" }
                pdf.openPage(page.coerceIn(0, pdf.pageCount - 1)).use { p ->
                    val factor = minOf(2f, 1300f / p.width, 1800f / p.height)
                    val image = Bitmap.createBitmap((p.width * factor).toInt().coerceAtLeast(1),
                        (p.height * factor).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    image.eraseColor(Color.WHITE)
                    p.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return PreviewResult.Ready(image, pdf.pageCount)
                }
            }
        }
    }
    if (file.extension.lowercase() in setOf("png", "jpg", "jpeg")) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Cannot read image" }
        var sample = 1
        while (bounds.outWidth / sample > 1800 || bounds.outHeight / sample > 1800) sample *= 2
        val image = requireNotNull(BitmapFactory.decodeFile(file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample })) { "Image cannot be opened" }
        return PreviewResult.Ready(image, 1)
    }
    return PreviewResult.Failed("Preview supports PDF, PNG and JPEG files.")
}
