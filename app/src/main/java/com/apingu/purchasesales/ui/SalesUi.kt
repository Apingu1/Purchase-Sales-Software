package com.apingu.purchasesales.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.apingu.purchasesales.data.*
import com.apingu.purchasesales.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SalesScreen(vm: AppViewModel, nav: NavHostController) {
    val allSales by vm.sales.collectAsStateWithLifecycle()
    val allCreditNotes by vm.creditNotes.collectAsStateWithLifecycle()
    val period by vm.selectedAccountingPeriod.collectAsStateWithLifecycle()
    val customers by vm.customers.collectAsStateWithLifecycle()
    val lines by vm.saleLines.collectAsStateWithLifecycle()
    var deleteSale by remember { mutableStateOf<SaleEntity?>(null) }
    var downloadSale by remember { mutableStateOf<SaleEntity?>(null) }
    var downloadCredit by remember { mutableStateOf<CreditNoteEntity?>(null) }

    val invoiceDownloadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val selected = downloadSale
        if (uri != null && selected != null) vm.exportSaleInvoice(selected.id, uri)
        downloadSale = null
    }
    val creditDownloadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val selected = downloadCredit
        if (uri != null && selected != null) vm.exportCreditNote(selected.id, uri)
        downloadCredit = null
    }

    val customerMap = customers.associateBy { it.id }
    val sales = if (period == null) emptyList() else allSales.filter { it.saleDateEpochDay in period!!.startEpochDay..period!!.endEpochDay }
    val credits = if (period == null) emptyList() else allCreditNotes.filter { it.creditDateEpochDay in period!!.startEpochDay..period!!.endEpochDay }
    val saleMap = allSales.associateBy { it.id }

    data class LedgerRow(val day: Long, val sortId: Long, val sale: SaleEntity? = null, val credit: CreditNoteEntity? = null)
    val ledger = (sales.map { LedgerRow(it.saleDateEpochDay, it.id, sale = it) } +
        credits.map { LedgerRow(it.creditDateEpochDay, it.id, credit = it) })
        .sortedWith(compareByDescending<LedgerRow> { it.day }.thenByDescending { it.sortId })

    Scaffold(
        topBar = { TopAppBar(title = { Text("Sales") }) },
        floatingActionButton = {
            FloatingActionButton({ nav.navigate("sale/new") }) { Icon(Icons.Default.Add, "New sale") }
        }
    ) { inner ->
        Column(Modifier.padding(inner)) {
            AccountingPeriodSelector(vm, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            if (ledger.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState("No sales invoices or credit notes in this accounting period")
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(ledger, key = { row -> if (row.sale != null) "S-${row.sale.id}" else "C-${row.credit!!.id}" }) { row ->
                        val sale = row.sale
                        val credit = row.credit
                        if (sale != null) {
                            val saleItems = lines.filter { it.saleId == sale.id }
                            val totalQty = saleItems.sumOf { it.quantity }
                            val itemSummary = when {
                                saleItems.isEmpty() -> "No item lines"
                                saleItems.size == 1 -> "${saleItems.first().item} × ${saleItems.first().quantity}"
                                else -> {
                                    val firstTwo = saleItems.take(2).joinToString(" • ") { "${it.item} × ${it.quantity}" }
                                    if (saleItems.size > 2) "$firstTwo • +${saleItems.size - 2} more" else firstTwo
                                }
                            }
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Row(Modifier.fillMaxWidth()) {
                                        Column(Modifier.weight(1f)) {
                                            Text(sale.invoiceNo, fontWeight = FontWeight.Bold)
                                            Text(customerMap[sale.customerId]?.companyName ?: "Customer", style = MaterialTheme.typography.bodySmall)
                                        }
                                        Text(formatMoney(sale.grossPence), fontWeight = FontWeight.Bold)
                                    }
                                    Text(itemSummary, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${saleItems.size} item line${if (saleItems.size == 1) "" else "s"} • $totalQty units • ${displayDate(sale.saleDateEpochDay)} • ${VatTypes.label(sale.vatType)}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Row {
                                        TextButton({ nav.navigate("sale/${sale.id}") }) {
                                            Icon(Icons.Default.Edit, null)
                                            Text(" Edit")
                                        }
                                        TextButton({
                                            downloadSale = sale
                                            invoiceDownloadLauncher.launch("${sale.invoiceNo}.pdf")
                                        }) {
                                            Icon(Icons.Default.Download, null)
                                            Text(" Download PDF")
                                        }
                                    }
                                    Row {
                                        TextButton({ nav.navigate("credit-note/${sale.id}") }) {
                                            Icon(Icons.Default.KeyboardReturn, null)
                                            Text(" Issue credit note")
                                        }
                                        TextButton({ deleteSale = sale }) {
                                            Icon(Icons.Default.DeleteOutline, null)
                                            Text(" Delete invoice")
                                        }
                                    }
                                }
                            }
                        } else if (credit != null) {
                            val original = saleMap[credit.saleId]
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(credit.creditNoteNo, fontWeight = FontWeight.Bold)
                                            Text("CREDIT NOTE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                        }
                                        Text("-${formatMoney(credit.grossPence)}", fontWeight = FontWeight.Bold)
                                    }
                                    Text(customerMap[original?.customerId]?.companyName ?: "Customer", style = MaterialTheme.typography.bodySmall)
                                    Text("Against ${original?.invoiceNo ?: "sales invoice"} • ${displayDate(credit.creditDateEpochDay)}", style = MaterialTheme.typography.bodySmall)
                                    if (credit.notes.isNotBlank()) Text(credit.notes, style = MaterialTheme.typography.bodySmall)
                                    Row {
                                        TextButton({ nav.navigate("credit-note/edit/${credit.id}") }) {
                                            Icon(Icons.Default.Edit, null)
                                            Text(" Edit")
                                        }
                                        TextButton({
                                            downloadCredit = credit
                                            creditDownloadLauncher.launch("${credit.creditNoteNo}.pdf")
                                        }) {
                                            Icon(Icons.Default.Download, null)
                                            Text(" Download credit note")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(70.dp)) }
                }
            }
        }
    }

    deleteSale?.let { sale ->
        AlertDialog(
            onDismissRequest = { deleteSale = null },
            title = { Text("Delete ${sale.invoiceNo}?") },
            text = {
                Text("This permanently removes the invoice only when it has no credit-note history. If a credit note has been issued, the original invoice is retained for traceability.")
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteSale(sale)
                    deleteSale = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton({ deleteSale = null }) { Text("Cancel") } }
        )
    }
}

private data class SaleLineForm(var item: String, var qty: String, var unitNet: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaleEditor(vm: AppViewModel, nav: NavHostController, id: Long) {
    val context = LocalContext.current
    val autoInvoiceNumber = remember { InvoiceNumberPreferences.isAutoEnabled(context) }
    val sales by vm.sales.collectAsStateWithLifecycle()
    val allLines by vm.saleLines.collectAsStateWithLifecycle()
    val customers by vm.customers.collectAsStateWithLifecycle()
    val inventory by vm.inventory.collectAsStateWithLifecycle()
    val sale = sales.firstOrNull { it.id == id }
    if (id > 0 && sale == null) {
        LoadingScreen()
        return
    }

    val existingLines = allLines.filter { it.saleId == id }
    var invoiceNo by remember(sale?.id, autoInvoiceNumber) { mutableStateOf(sale?.invoiceNo.orEmpty()) }
    var date by remember(sale?.id) { mutableStateOf(editDate(sale?.saleDateEpochDay ?: epochDayToday())) }
    var customerId by remember(sale?.id, customers.size) { mutableLongStateOf(sale?.customerId ?: customers.firstOrNull()?.id ?: 0L) }
    var vatType by remember(sale?.id) { mutableStateOf(sale?.vatType ?: VatTypes.STANDARD) }
    var notes by remember(sale?.id) { mutableStateOf(sale?.notes.orEmpty()) }
    val forms = remember(sale?.id, existingLines.size) {
        mutableStateListOf<SaleLineForm>().apply {
            if (existingLines.isNotEmpty()) {
                existingLines.forEach { line ->
                    val existingUnitNet = breakdownFromGross(line.unitGrossPence, sale?.vatType ?: VatTypes.STANDARD).netPence
                    add(SaleLineForm(line.item, line.quantity.toString(), formatMoneyPlain(existingUnitNet)))
                }
            } else {
                add(SaleLineForm(inventory.firstOrNull { it.available > 0 }?.item.orEmpty(), "1", ""))
            }
        }
    }

    ScreenScaffold(if (id == 0L) "New sale" else "Edit sale", onBack = { nav.popBackStack() }) { inner ->
        Column(
            Modifier.padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (customers.isEmpty()) {
                ElevatedCard {
                    Column(Modifier.padding(16.dp)) {
                        Text("Create a customer before generating an invoice.")
                        TextButton({ nav.navigate("customers") }) { Text("Go to customers") }
                    }
                }
            }

            if (autoInvoiceNumber) {
                Text(
                    if (sale == null) "Invoice number will be generated automatically when you save." else "Invoice number: ${sale.invoiceNo}",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                FormField("Invoice number", invoiceNo, { invoiceNo = it.take(64) })
                Text("Manual invoice numbering is enabled in Business Details. Invoice numbers must be unique.", style = MaterialTheme.typography.bodySmall)
            }
            FormField("Sale date (DD/MM/YYYY)", date, { date = it })
            CustomerSelector(customers, customerId) { customerId = it }
            VatSelector(vatType) { vatType = it }
            Text(
                when (vatType) {
                    VatTypes.STANDARD -> "Enter the Unit Net selling price. VAT at 20% is calculated automatically."
                    VatTypes.REVERSE -> "Enter the Unit Net selling price. Customer VAT charged is £0.00; reverse VAT is calculated notionally for accounting."
                    else -> "Enter the Unit Net selling price. No VAT is added."
                },
                style = MaterialTheme.typography.bodySmall
            )

            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Items in this sale", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Identical purchases are consolidated in inventory. Select an item once and enter the total quantity to sell, e.g. Qty 10 for ten separately purchased phones.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                AssistChip(onClick = {}, label = { Text("${forms.size} line${if (forms.size == 1) "" else "s"}") })
            }

            forms.forEachIndexed { index, form ->
                SaleLineCard(
                    index = index,
                    form = form,
                    inventory = inventory,
                    vatType = vatType,
                    onChange = { updated -> forms[index] = updated },
                    onDelete = { if (forms.size > 1) forms.removeAt(index) },
                    canDelete = forms.size > 1
                )
            }

            OutlinedButton(
                onClick = {
                    forms.add(SaleLineForm(inventory.firstOrNull { it.available > 0 }?.item.orEmpty(), "1", ""))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(6.dp))
                Text("Add another item")
            }

            val previewBreakdowns = forms.map { form ->
                val unitNet = runCatching { moneyToPence(form.unitNet) }.getOrDefault(0)
                val quantity = form.qty.toIntOrNull() ?: 0
                breakdownFromNet(unitNet * quantity, vatType)
            }
            val totalQty = forms.sumOf { it.qty.toIntOrNull() ?: 0 }
            val totalNet = previewBreakdowns.sumOf { it.netPence }
            val totalVat = previewBreakdowns.sumOf { it.vatPence }
            val totalGross = previewBreakdowns.sumOf { it.grossPence }
            val totalReverse = previewBreakdowns.sumOf { it.reverseVatPence }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Invoice total ${formatMoney(totalGross)}", fontWeight = FontWeight.Bold)
                    Text("${forms.size} item line${if (forms.size == 1) "" else "s"} • $totalQty total units")
                    Text("Net ${formatMoney(totalNet)} • VAT ${formatMoney(totalVat)}", style = MaterialTheme.typography.bodySmall)
                    if (vatType == VatTypes.REVERSE) {
                        Text("Reverse VAT notional ${formatMoney(totalReverse)} • VAT charged to customer £0.00", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            FormField("Invoice notes", notes, { notes = it }, singleLine = false)
            Button(
                {
                    vm.saveSale(
                        SaleDraft(
                            id = id,
                            dateEpochDay = parseDateOrToday(date),
                            customerId = customerId,
                            vatType = vatType,
                            notes = notes,
                            lines = forms.map {
                                SaleLineDraft(
                                    it.item,
                                    it.qty.toIntOrNull() ?: 0,
                                    runCatching { moneyToPence(it.unitNet) }.getOrDefault(0)
                                )
                            },
                            manualInvoiceNo = if (autoInvoiceNumber) null else invoiceNo
                        )
                    ) { nav.popBackStack() }
                },
                Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.PictureAsPdf, null)
                Spacer(Modifier.width(6.dp))
                Text(if (id == 0L) "Generate invoice" else "Save & regenerate invoice")
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SaleLineCard(
    index: Int,
    form: SaleLineForm,
    inventory: List<InventoryRow>,
    vatType: String,
    onChange: (SaleLineForm) -> Unit,
    onDelete: () -> Unit,
    canDelete: Boolean
) {
    var menu by remember { mutableStateOf(false) }
    val quantity = form.qty.toIntOrNull() ?: 0
    val unitNet = runCatching { moneyToPence(form.unitNet) }.getOrDefault(0)
    val lineBreakdown = breakdownFromNet(quantity * unitNet, vatType)
    val selectedInventory = inventory.firstOrNull { it.item.trim().equals(form.item.trim(), ignoreCase = true) }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Item ${index + 1}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (lineBreakdown.netPence > 0) Text("Net ${formatMoney(lineBreakdown.netPence)}", fontWeight = FontWeight.SemiBold)
                if (canDelete) {
                    IconButton(onDelete) { Icon(Icons.Default.DeleteOutline, "Remove") }
                }
            }
            Box {
                OutlinedButton({ menu = true }, Modifier.fillMaxWidth()) {
                    Text(form.item.ifBlank { "Select inventory item" }, Modifier.weight(1f))
                    Icon(Icons.Default.ArrowDropDown, null)
                }
                DropdownMenu(menu, { menu = false }) {
                    inventory.filter { it.available > 0 || it.item.equals(form.item, true) }.forEach { item ->
                        DropdownMenuItem(
                            text = { Text("${item.item} (${item.available} available)") },
                            onClick = {
                                onChange(form.copy(item = item.item))
                                menu = false
                            }
                        )
                    }
                }
            }
            selectedInventory?.let { stock ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Consolidated stock available", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    AssistChip(onClick = {}, label = { Text("Qty ${stock.available}") })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    form.qty,
                    { onChange(form.copy(qty = it)) },
                    label = { Text("Qty to sell") },
                    supportingText = {
                        selectedInventory?.let { Text("Up to ${it.available} currently available") }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    form.unitNet,
                    { onChange(form.copy(unitNet = it)) },
                    label = { Text("Unit net £") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(2f),
                    singleLine = true
                )
            }
            if (selectedInventory != null && quantity > selectedInventory.available) {
                Text(
                    "Only ${selectedInventory.available} available. The invoice cannot be saved with Qty $quantity.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (lineBreakdown.netPence > 0) {
                when (vatType) {
                    VatTypes.STANDARD -> Text(
                        "Line net ${formatMoney(lineBreakdown.netPence)} • VAT ${formatMoney(lineBreakdown.vatPence)} • Gross ${formatMoney(lineBreakdown.grossPence)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    VatTypes.REVERSE -> Text(
                        "Line net ${formatMoney(lineBreakdown.netPence)} • VAT charged £0.00 • Reverse VAT notional ${formatMoney(lineBreakdown.reverseVatPence)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    else -> Text("Line net ${formatMoney(lineBreakdown.netPence)} • No VAT", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}


private data class CreditLineForm(
    val saleLineId: Long,
    val quantity: String = "0",
    val restock: Boolean = true,
    val selectedIdentifiers: Set<String> = emptySet()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditNoteEditor(
    vm: AppViewModel,
    nav: NavHostController,
    saleId: Long = 0L,
    creditNoteId: Long = 0L
) {
    val context = LocalContext.current
    val sales by vm.sales.collectAsStateWithLifecycle()
    val creditNotes by vm.creditNotes.collectAsStateWithLifecycle()
    val allLines by vm.saleLines.collectAsStateWithLifecycle()
    val returns by vm.saleReturns.collectAsStateWithLifecycle()
    val creditedImeis by vm.creditNoteImeis.collectAsStateWithLifecycle()
    val allocations by vm.allocations.collectAsStateWithLifecycle()
    val purchases by vm.purchases.collectAsStateWithLifecycle()
    val purchaseOrders by vm.purchaseOrders.collectAsStateWithLifecycle()
    val customers by vm.customers.collectAsStateWithLifecycle()

    val existingCredit = creditNotes.firstOrNull { it.id == creditNoteId }
    if (creditNoteId > 0 && existingCredit == null) {
        LoadingScreen()
        return
    }

    val resolvedSaleId = existingCredit?.saleId ?: saleId
    val sale = sales.firstOrNull { it.id == resolvedSaleId }
    if (sale == null) {
        LoadingScreen()
        return
    }

    val lines = allLines.filter { it.saleId == resolvedSaleId }
    val currentReturns = if (creditNoteId > 0) returns.filter { it.creditNoteId == creditNoteId } else emptyList()
    val currentImeis = if (creditNoteId > 0) creditedImeis.filter { it.creditNoteId == creditNoteId } else emptyList()
    val customer = customers.firstOrNull { it.id == sale.customerId }
    val orderMap = purchaseOrders.associateBy { it.id }
    val purchaseMap = purchases.associateBy { it.id }

    val forms = remember(resolvedSaleId, creditNoteId, lines.size, currentReturns.size, currentImeis.size) {
        mutableStateListOf<CreditLineForm>().apply {
            lines.forEach { line ->
                val existingReturn = currentReturns.firstOrNull { it.saleLineId == line.id }
                val selected = currentImeis.filter { it.saleLineId == line.id }.map { it.identifier }.toSet()
                add(
                    CreditLineForm(
                        saleLineId = line.id,
                        quantity = existingReturn?.quantity?.toString() ?: "0",
                        restock = existingReturn?.restock ?: true,
                        selectedIdentifiers = selected
                    )
                )
            }
        }
    }

    var date by remember(creditNoteId, existingCredit?.creditDateEpochDay) {
        mutableStateOf(editDate(existingCredit?.creditDateEpochDay ?: epochDayToday()))
    }
    var notes by remember(creditNoteId, existingCredit?.notes) {
        mutableStateOf(existingCredit?.notes.orEmpty())
    }

    fun assignedUnits(line: SaleLineEntity): List<CreditNoteUnitDraft> {
        val alreadyCreditedElsewhere = creditedImeis
            .filter { it.saleLineId == line.id && it.creditNoteId != creditNoteId }
            .map { it.identifier }
            .toSet()
        val assigned = ImeiAssignmentStore.get(context, line.id).filter { it !in alreadyCreditedElsewhere }
        val lineAllocations = allocations.filter { it.saleLineId == line.id }
        return assigned.map { identifier ->
            val exactPurchase = lineAllocations.asSequence()
                .mapNotNull { purchaseMap[it.purchaseId] }
                .firstOrNull { purchase ->
                    identifier in trackedIdentifiers(purchase, orderMap[purchase.purchaseOrderId]?.notes.orEmpty())
                }
            CreditNoteUnitDraft(identifier, exactPurchase?.id ?: 0L)
        }
    }

    fun creditedElsewhere(lineId: Long): Int =
        returns.filter { it.saleLineId == lineId && it.creditNoteId != creditNoteId }.sumOf { it.quantity }

    val validity = forms.all { form ->
        val line = lines.first { it.id == form.saleLineId }
        val alreadyReturned = creditedElsewhere(line.id)
        val availableQty = (line.quantity - alreadyReturned).coerceAtLeast(0)
        val qty = form.quantity.toIntOrNull() ?: 0
        val tracked = assignedUnits(line)
        qty in 0..availableQty && (qty <= 0 || tracked.isEmpty() || form.selectedIdentifiers.size == qty)
    }
    val anySelected = forms.any { (it.quantity.toIntOrNull() ?: 0) > 0 }
    val editing = existingCredit != null

    ScreenScaffold(
        if (editing) "Edit ${existingCredit!!.creditNoteNo}" else "Issue credit note",
        onBack = { nav.popBackStack() }
    ) { inner ->
        Column(
            Modifier.padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (editing) {
                        Text(existingCredit!!.creditNoteNo, fontWeight = FontWeight.Bold)
                    }
                    Text("Original invoice ${sale.invoiceNo}", fontWeight = FontWeight.Bold)
                    Text(customer?.companyName ?: "Customer")
                    Text(
                        if (editing) "Changes update this existing credit note and recalculate stock, profit and VAT."
                        else "Select only the item(s) being returned or credited. The original invoice remains unchanged.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            FormField("Date credit note issued (DD/MM/YYYY)", date, { date = it })
            Text(
                "Tap the calendar icon to choose the date the credit note was issued.",
                style = MaterialTheme.typography.bodySmall
            )

            lines.forEach { line ->
                val index = forms.indexOfFirst { it.saleLineId == line.id }
                val form = forms[index]
                val alreadyReturned = creditedElsewhere(line.id)
                val availableQty = (line.quantity - alreadyReturned).coerceAtLeast(0)
                val qty = form.quantity.toIntOrNull() ?: 0
                val units = assignedUnits(line)

                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(line.item, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Sold ${line.quantity} • Credited on other notes $alreadyReturned • Available for this note $availableQty",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(
                            value = form.quantity,
                            onValueChange = { value ->
                                val clean = value.filter { it.isDigit() }.take(3)
                                val newQty = clean.toIntOrNull() ?: 0
                                forms[index] = form.copy(
                                    quantity = clean,
                                    selectedIdentifiers = if (newQty <= 0) emptySet() else form.selectedIdentifiers.take(newQty).toSet()
                                )
                            },
                            label = { Text("Quantity to credit") },
                            supportingText = { Text("Maximum $availableQty") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = form.restock,
                                onCheckedChange = { forms[index] = form.copy(restock = it) }
                            )
                            Text("Return credited item(s) to inventory")
                        }

                        if (units.isNotEmpty() && qty > 0) {
                            Text("Select the exact IMEI / serial number(s) being credited", fontWeight = FontWeight.Medium)
                            units.forEach { unit ->
                                val checked = unit.identifier in form.selectedIdentifiers
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { newValue ->
                                            val selected = form.selectedIdentifiers.toMutableSet()
                                            if (newValue) {
                                                if (selected.size < qty) selected += unit.identifier
                                            } else {
                                                selected -= unit.identifier
                                            }
                                            forms[index] = form.copy(selectedIdentifiers = selected)
                                        }
                                    )
                                    Text(unit.identifier)
                                }
                            }
                            Text("Selected ${form.selectedIdentifiers.size} / $qty", style = MaterialTheme.typography.bodySmall)
                        }

                        if (qty > availableQty) {
                            Text(
                                "Quantity exceeds the amount available to credit.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            val selectedForms = forms.filter { (it.quantity.toIntOrNull() ?: 0) > 0 }
            val preview = selectedForms.mapNotNull { form ->
                val line = lines.firstOrNull { it.id == form.saleLineId } ?: return@mapNotNull null
                val qty = form.quantity.toIntOrNull() ?: 0
                val inferredUnitNet = breakdownFromGross(line.unitGrossPence, sale.vatType).netPence
                if (inferredUnitNet * line.quantity == line.lineNetPence) {
                    breakdownFromNet(inferredUnitNet * qty, sale.vatType)
                } else {
                    breakdownFromGross(line.unitGrossPence * qty, sale.vatType)
                }
            }
            val totalNet = preview.sumOf { it.netPence }
            val totalVat = preview.sumOf { it.vatPence }
            val totalGross = preview.sumOf { it.grossPence }

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Credit total", style = MaterialTheme.typography.labelLarge)
                    Text(formatMoney(totalGross), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Net ${formatMoney(totalNet)} • VAT ${formatMoney(totalVat)}", style = MaterialTheme.typography.bodySmall)
                }
            }

            FormField("Credit note notes (optional)", notes, { notes = it }, singleLine = false)

            Button(
                enabled = anySelected && validity,
                onClick = {
                    val draftLines = forms.mapNotNull { form ->
                        val qty = form.quantity.toIntOrNull() ?: 0
                        if (qty <= 0) return@mapNotNull null
                        val line = lines.first { it.id == form.saleLineId }
                        val units = assignedUnits(line).filter { it.identifier in form.selectedIdentifiers }
                        CreditNoteLineDraft(
                            saleLineId = line.id,
                            quantity = qty,
                            restock = form.restock,
                            units = units
                        )
                    }
                    vm.saveCreditNote(
                        CreditNoteDraft(
                            id = creditNoteId,
                            saleId = sale.id,
                            dateEpochDay = parseDateOrToday(date),
                            notes = notes,
                            lines = draftLines
                        )
                    ) { nav.popBackStack() }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(if (editing) Icons.Default.Save else Icons.Default.ReceiptLong, null)
                Spacer(Modifier.width(6.dp))
                Text(if (editing) "Save credit note changes" else "Issue credit note")
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun CustomerReturnDialog(
    vm: AppViewModel,
    sale: SaleEntity,
    lines: List<SaleLineEntity>,
    onDismiss: () -> Unit
) {
    var lineId by remember { mutableLongStateOf(lines.firstOrNull()?.id ?: 0) }
    var qty by remember { mutableStateOf("1") }
    var date by remember { mutableStateOf(editDate(epochDayToday())) }
    var restock by remember { mutableStateOf(true) }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Return / refund ${sale.invoiceNo}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SaleLineSelector(lines, lineId) { lineId = it }
                FormField("Return date", date, { date = it })
                FormField("Quantity", qty, { qty = it }, KeyboardType.Number)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(restock, { restock = it })
                    Text("Return item to inventory")
                }
                FormField("Notes", notes, { notes = it }, singleLine = false)
            }
        },
        confirmButton = {
            TextButton({
                vm.recordReturn(lineId, parseDateOrToday(date), qty.toIntOrNull() ?: 0, restock, notes, onDismiss)
            }) { Text("Record return") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}
