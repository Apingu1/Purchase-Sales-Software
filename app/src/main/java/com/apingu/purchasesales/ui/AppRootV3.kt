package com.apingu.purchasesales.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.apingu.purchasesales.PurchaseSalesApplication
import com.apingu.purchasesales.data.*
import com.apingu.purchasesales.util.*

private val PendingReceiptStatusesV3 = setOf("RECEIPT_PENDING", "PARTIALLY_RECEIVED")

@Composable
fun PurchaseSalesRootV3() {
    val context = LocalContext.current
    val app = context.applicationContext as PurchaseSalesApplication
    val vm: AppViewModel = viewModel(factory = AppViewModel.factory(app))
    val nav = rememberNavController()
    val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    MaterialTheme(colorScheme = scheme) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { outer ->
            Box(Modifier.padding(outer).fillMaxSize()) { AppNavigationV3(nav, vm) }
        }
    }
}

private data class BottomItemV3(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
private val bottomItemsV3 = listOf(
    BottomItemV3("dashboard", "Home", Icons.Default.Home),
    BottomItemV3("purchases", "Purchases", Icons.Default.ShoppingCart),
    BottomItemV3("inventory", "Inventory", Icons.Default.Inventory2),
    BottomItemV3("sales", "Sales", Icons.Default.ReceiptLong),
    BottomItemV3("more", "More", Icons.Default.MoreHoriz)
)

@Composable
private fun AppNavigationV3(nav: NavHostController, vm: AppViewModel) {
    val currentRoute = nav.currentBackStackEntryFlow.collectAsState(initial = nav.currentBackStackEntry).value?.destination?.route
    val showBottom = currentRoute in bottomItemsV3.map { it.route }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottom) {
                NavigationBar(windowInsets = WindowInsets(0, 0, 0, 0)) {
                    bottomItemsV3.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                nav.navigate(item.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, item.label) },
                            label = { Text(item.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(nav, startDestination = "dashboard", modifier = Modifier.padding(padding)) {
            composable("dashboard") { DashboardScreenV3(vm, nav) }
            composable("purchases") { PurchasesScreenV3(vm, nav) }
            composable("purchase/new") { PurchaseOrderEditorV3(vm, nav, 0L) }
            composable("purchase/duplicate/{id}") { PurchaseOrderEditorV3(vm, nav, 0L, it.arguments?.getString("id")?.toLongOrNull() ?: 0L) }
            composable("purchase/{id}") { PurchaseOrderEditorV3(vm, nav, it.arguments?.getString("id")?.toLongOrNull() ?: 0L) }
            composable("inventory") { InventoryScreenV3(vm) }
            composable("sales") { SalesScreen(vm, nav) }
            composable("sale/new") { SaleEditor(vm, nav, 0L) }
            composable("sale/{id}") { SaleEditor(vm, nav, it.arguments?.getString("id")?.toLongOrNull() ?: 0L) }
            composable("more") { MoreScreen(nav) }
            composable("customers") { CustomersScreen(vm, nav) }
            composable("expenses") { ExpensesScreen(vm, nav) }
            composable("expense/new") { ExpenseEditor(vm, nav, 0L) }
            composable("expense/{id}") { ExpenseEditor(vm, nav, it.arguments?.getString("id")?.toLongOrNull() ?: 0L) }
            composable("reports") { ReportsScreen(vm, nav) }
            composable("documents") { DocumentsScreen(vm, nav) }
            composable("business") { BusinessScreen(vm, nav) }
        }
    }
}

@Composable
private fun DashboardScreenV3(vm: AppViewModel, nav: NavHostController) {
    val summary by vm.summary.collectAsStateWithLifecycle()
    val orders by vm.purchaseOrders.collectAsStateWithLifecycle()
    val purchaseLines by vm.purchases.collectAsStateWithLifecycle()
    val business by vm.business.collectAsStateWithLifecycle()
    val period by vm.selectedAccountingPeriod.collectAsStateWithLifecycle()
    val periodOrders = if (period == null) emptyList() else orders.filter { it.purchaseDateEpochDay in period!!.startEpochDay..period!!.endEpochDay }
    val pendingOrders = periodOrders.count { order ->
        purchaseOrderStatusV3(purchaseLines.filter { it.purchaseOrderId == order.id }) in PendingReceiptStatusesV3
    }

    ScreenScaffold("Purchase & Sales") { inner ->
        LazyColumn(
            Modifier.padding(inner).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(business.businessName.ifBlank { "Business dashboard" }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                AccountingPeriodSelector(vm)
            }
            item { ResponsiveMetricPair("Net sales", formatMoney(summary.salesNet), "Net profit", formatMoney(summary.netProfit)) }
            item { ResponsiveMetricPair("Inventory", formatMoney(summary.inventoryValue), "Refunds pending", formatMoney(summary.refundsPending)) }
            item {
                val vatLabel = if (summary.vatPosition >= 0) "VAT due to HMRC" else "VAT refund expected"
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(vatLabel, style = MaterialTheme.typography.labelLarge)
                        Text(formatMoney(kotlin.math.abs(summary.vatPosition)), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("Output ${formatMoney(summary.outputVat)} • Input ${formatMoney(summary.inputVat)} • Reverse VAT nets to £0", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { Text("Quick actions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            item {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth < 380.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({ nav.navigate("purchase/new") }, Modifier.fillMaxWidth()) { Icon(Icons.Default.AddShoppingCart, null); Spacer(Modifier.width(6.dp)); Text("Purchase") }
                            Button({ nav.navigate("sale/new") }, Modifier.fillMaxWidth()) { Icon(Icons.Default.PointOfSale, null); Spacer(Modifier.width(6.dp)); Text("Sale") }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button({ nav.navigate("purchase/new") }, Modifier.weight(1f)) { Icon(Icons.Default.AddShoppingCart, null); Spacer(Modifier.width(6.dp)); Text("Purchase") }
                            Button({ nav.navigate("sale/new") }, Modifier.weight(1f)) { Icon(Icons.Default.PointOfSale, null); Spacer(Modifier.width(6.dp)); Text("Sale") }
                        }
                    }
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth(), onClick = { nav.navigate("purchases") }) {
                    Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocalShipping, null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Pending receipts", fontWeight = FontWeight.SemiBold)
                            Text("$pendingOrders purchase order${if (pendingOrders == 1) "" else "s"} in this accounting period need attention")
                        }
                        Icon(Icons.Default.ChevronRight, null)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResponsiveMetricPair(label1: String, value1: String, label2: String, value2: String) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 380.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard(label1, value1, Modifier.fillMaxWidth())
                MetricCard(label2, value2, Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard(label1, value1, Modifier.weight(1f))
                MetricCard(label2, value2, Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PurchasesScreenV3(vm: AppViewModel, nav: NavHostController) {
    val orders by vm.purchaseOrders.collectAsStateWithLifecycle()
    val allLines by vm.purchases.collectAsStateWithLifecycle()
    val period by vm.selectedAccountingPeriod.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }

    val periodOrders = if (period == null) emptyList() else orders.filter { it.purchaseDateEpochDay in period!!.startEpochDay..period!!.endEpochDay }
    val rows = periodOrders.map { order -> order to allLines.filter { it.purchaseOrderId == order.id } }
    val pendingCount = rows.count { (_, lines) -> purchaseOrderStatusV3(lines) in PendingReceiptStatusesV3 }
    val cancelledRefundedCount = rows.count { (_, lines) -> isCancelledOrRefundedOrderV3(lines) }
    val filtered = rows.filter { (order, lines) ->
        val status = purchaseOrderStatusV3(lines)
        val statusMatches = when (tab) {
            0 -> status in PendingReceiptStatusesV3
            1 -> isCancelledOrRefundedOrderV3(lines)
            else -> true
        }
        val q = query.trim()
        val queryMatches = q.isBlank() || order.supplier.contains(q, true) || order.orderNumber.contains(q, true) || lines.any { it.item.contains(q, true) || it.notes.contains(q, true) }
        statusMatches && queryMatches
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Purchases") }, windowInsets = WindowInsets(0, 0, 0, 0)) },
        floatingActionButton = { FloatingActionButton({ nav.navigate("purchase/new") }) { Icon(Icons.Default.Add, "New purchase") } }
    ) { inner ->
        Column(Modifier.padding(inner)) {
            AccountingPeriodSelector(vm, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Pending receipts") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Cancelled / refunded") })
                Tab(tab == 2, { tab = 2 }, text = { Text("All") })
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Filter by supplier, item or order") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotBlank()) IconButton({ query = "" }) { Icon(Icons.Default.Close, "Clear filter") } },
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
                singleLine = true
            )
            Text(
                when (tab) {
                    0 -> "Showing ${filtered.size} of $pendingCount pending receipt order${if (pendingCount == 1) "" else "s"}"
                    1 -> "Showing ${filtered.size} of $cancelledRefundedCount cancelled/refunded order${if (cancelledRefundedCount == 1) "" else "s"}"
                    else -> "Showing ${filtered.size} of ${rows.size} total order${if (rows.size == 1) "" else "s"}"
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EmptyState("No purchase orders in this accounting period match this view") }
            } else {
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(filtered, key = { it.first.id }) { (order, lines) -> PurchaseOrderCardV3(order, lines, nav, vm) }
                    item { Spacer(Modifier.height(70.dp)) }
                }
            }
        }
    }
}

@Composable
private fun PurchaseOrderCardV3(order: PurchaseOrderEntity, lines: List<PurchaseEntity>, nav: NavHostController, vm: AppViewModel) {
    val status = purchaseOrderStatusV3(lines)
    val isPending = status in PendingReceiptStatusesV3
    var menuExpanded by remember(order.id) { mutableStateOf(false) }
    val totalGross = lines.sumOf { it.grossPence }
    val totalQty = lines.sumOf { it.quantity }
    val received = lines.sumOf { it.receivedQty }
    val returned = lines.sumOf { it.returnedQty }
    val partialCredits = lines.filter { it.partialRefund }.sumOf { it.refundNetPence + it.refundVatPence }
    val itemText = when {
        lines.isEmpty() -> "No items"
        lines.size == 1 -> "${lines.first().item} × ${lines.first().quantity}"
        else -> lines.take(2).joinToString(" • ") { "${it.item} × ${it.quantity}" } + if (lines.size > 2) " • +${lines.size - 2} more" else ""
    }

    ElevatedCard(Modifier.fillMaxWidth(), onClick = { nav.navigate("purchase/${order.id}") }) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(order.supplier, fontWeight = FontWeight.SemiBold)
                    Text(buildString { append(displayDate(order.purchaseDateEpochDay)); if (order.orderNumber.isNotBlank()) append(" • ${order.orderNumber}") }, style = MaterialTheme.typography.bodySmall)
                }
                AssistChip(onClick = {}, label = { Text(status.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }) })
                if (isPending) {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Purchase order actions")
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Duplicate order") },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                                onClick = {
                                    menuExpanded = false
                                    nav.navigate("purchase/duplicate/${order.id}")
                                }
                            )
                        }
                    }
                }
            }
            Text(itemText)
            Text("${lines.size} item line${if (lines.size == 1) "" else "s"} • Qty $totalQty • ${formatMoney(totalGross)} • ${VatTypes.label(order.vatType)}", style = MaterialTheme.typography.bodySmall)
            Text("Received $received • Returned $returned", style = MaterialTheme.typography.bodySmall)
            if (partialCredits > 0) Text("Partial refund credits ${formatMoney(partialCredits)}", style = MaterialTheme.typography.bodySmall)
            if (isPending) {
                TextButton({ vm.markReceivedAllOrder(order.id) }) {
                    Icon(Icons.Default.CheckCircle, null)
                    Spacer(Modifier.width(5.dp))
                    Text("Receive all remaining items")
                }
            }
        }
    }
}

private data class PurchaseLineFormV3(
    val id: Long = 0,
    val item: String = "",
    val quantity: String = "1",
    val gross: String = "",
    val notes: String = "",
    val received: String = "",
    val cancelled: String = "",
    val returned: String = "",
    val refundExpected: String = "",
    val refundReceived: String = "",
    val partialRefund: Boolean = false,
    val refundNet: String = "",
    val refundVat: String = ""
)

@Composable
private fun PurchaseOrderEditorV3(vm: AppViewModel, nav: NavHostController, id: Long, duplicateFromId: Long = 0L) {
    val orders by vm.purchaseOrders.collectAsStateWithLifecycle()
    val allLines by vm.purchases.collectAsStateWithLifecycle()
    val order = orders.firstOrNull { it.id == id }
    val duplicateOrder = orders.firstOrNull { it.id == duplicateFromId }
    val sourceOrder = order ?: duplicateOrder
    val sourceLines = allLines.filter { it.purchaseOrderId == (if (id > 0) id else duplicateFromId) }.sortedBy { it.id }
    val isDuplicate = duplicateFromId > 0L

    if (id > 0 && (order == null || sourceLines.isEmpty())) { LoadingScreen(); return }
    if (isDuplicate && (duplicateOrder == null || sourceLines.isEmpty())) { LoadingScreen(); return }

    var date by remember(sourceOrder?.id, isDuplicate) { mutableStateOf(editDate(sourceOrder?.purchaseDateEpochDay ?: epochDayToday())) }
    var supplier by remember(sourceOrder?.id, isDuplicate) { mutableStateOf(sourceOrder?.supplier.orEmpty()) }
    var orderNo by remember(sourceOrder?.id, isDuplicate) { mutableStateOf(if (isDuplicate) "" else sourceOrder?.orderNumber.orEmpty()) }
    var account by remember(sourceOrder?.id, isDuplicate) { mutableStateOf(sourceOrder?.accountUsername.orEmpty()) }
    var vatType by remember(sourceOrder?.id, isDuplicate) { mutableStateOf(sourceOrder?.vatType ?: VatTypes.STANDARD) }
    var payment by remember(sourceOrder?.id, isDuplicate) { mutableStateOf(sourceOrder?.paymentMethod.orEmpty()) }
    var orderNotes by remember(sourceOrder?.id, isDuplicate) { mutableStateOf(if (isDuplicate) "" else sourceOrder?.notes.orEmpty()) }
    var attachment by remember { mutableStateOf<Uri?>(null) }

    val forms = remember(sourceOrder?.id, sourceLines.size, isDuplicate) {
        mutableStateListOf<PurchaseLineFormV3>().apply {
            if (sourceLines.isNotEmpty()) sourceLines.forEach { line ->
                val legacyDuplicatedOrderNote = sourceOrder?.notes?.trim().orEmpty()
                add(PurchaseLineFormV3(
                    id = if (isDuplicate) 0L else line.id,
                    item = line.item,
                    quantity = line.quantity.toString(),
                    gross = formatMoneyPlain(unitGrossFromStoredLineV3(line.grossPence, line.quantity)),
                    // IMEI/serial notes are unit-specific, so deliberately clear them on a duplicate.
                    notes = if (isDuplicate) "" else line.notes.trim().takeUnless { it.isNotBlank() && it == legacyDuplicatedOrderNote }.orEmpty(),
                    received = if (isDuplicate) "" else line.receivedQty.takeIf { it > 0 }?.toString().orEmpty(),
                    cancelled = if (isDuplicate) "" else line.cancelledQty.takeIf { it > 0 }?.toString().orEmpty(),
                    returned = if (isDuplicate) "" else line.returnedQty.takeIf { it > 0 }?.toString().orEmpty(),
                    refundExpected = if (isDuplicate) "" else line.refundExpectedPence.takeIf { it > 0 && !line.partialRefund }?.let(::formatMoneyPlain).orEmpty(),
                    refundReceived = if (isDuplicate) "" else line.refundReceivedPence.takeIf { it > 0 && !line.partialRefund }?.let(::formatMoneyPlain).orEmpty(),
                    partialRefund = if (isDuplicate) false else line.partialRefund,
                    refundNet = if (isDuplicate) "" else line.refundNetPence.takeIf { it > 0 && line.partialRefund }?.let(::formatMoneyPlain).orEmpty(),
                    refundVat = if (isDuplicate) "" else line.refundVatPence.takeIf { it > 0 && line.partialRefund }?.let(::formatMoneyPlain).orEmpty()
                ))
            } else add(PurchaseLineFormV3())
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { attachment = it }
    val totalGross = forms.sumOf { form ->
        val unitGross = runCatching { moneyToPence(form.gross) }.getOrDefault(0)
        val quantity = (form.quantity.toIntOrNull() ?: 0).coerceAtLeast(0)
        unitGross * quantity.toLong()
    }
    val totalBreakdown = breakdownFromGross(totalGross, vatType)
    val totalQty = forms.sumOf { it.quantity.toIntOrNull() ?: 0 }
    val title = when { isDuplicate -> "Duplicate purchase"; id == 0L -> "New purchase"; else -> "Edit purchase" }

    ScreenScaffold(title, onBack = { nav.popBackStack() }) { inner ->
        Column(Modifier.padding(inner).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (isDuplicate) {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Text(
                        "Copied from ${duplicateOrder?.orderNumber.orEmpty().ifBlank { "the original order" }}. Order number, order notes, item IMEI/serial notes, receipt/refund fields and invoice attachment are intentionally blank. Enter the new order-specific details before saving.",
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Text("Purchase details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            FormField("Purchase date (DD/MM/YYYY)", date, { date = it })
            FormField("Supplier / Store", supplier, { supplier = it })
            FormField("Order number", orderNo, { orderNo = it })
            FormField("Email / Username", account, { account = it })
            VatSelector(vatType) { vatType = it }
            FormField("Payment method", payment, { payment = it })

            HorizontalDivider()
            Text("Items purchased", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Enter the gross purchase cost for one unit. The app multiplies it by Quantity to calculate the line total, net amount and VAT.", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            Text("Item notes follow the allocated stock into the PDF sales invoice. For IMEI/serial-tracked devices, use one item line per device (Qty 1) so the exact identifier follows that unit.", style = MaterialTheme.typography.bodySmall)

            forms.forEachIndexed { index, form ->
                PurchaseLineCardV3(index, form, vatType, forms.size > 1, { forms[index] = it }, { if (forms.size > 1) forms.removeAt(index) })
            }

            OutlinedButton({ forms.add(PurchaseLineFormV3()) }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add another item") }

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Purchase total", style = MaterialTheme.typography.labelLarge)
                    Text(formatMoney(totalBreakdown.grossPence), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("${forms.size} item line${if (forms.size == 1) "" else "s"} • $totalQty total units")
                    Text("Net ${formatMoney(totalBreakdown.netPence)} • VAT ${formatMoney(totalBreakdown.vatPence)}", style = MaterialTheme.typography.bodySmall)
                }
            }

            FormField("Order notes (optional - not carried to sales invoice)", orderNotes, { orderNotes = it }, singleLine = false)
            OutlinedButton({ picker.launch("*/*") }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.AttachFile, null); Spacer(Modifier.width(6.dp))
                Text(if (attachment != null) "New invoice selected" else if (!isDuplicate && !order?.invoicePath.isNullOrBlank()) "Replace purchase invoice" else "Attach purchase invoice (optional)")
            }

            Button(
                onClick = {
                    vm.savePurchaseOrder(
                        value = PurchaseOrderDraft(
                            id = if (isDuplicate) 0L else id,
                            dateEpochDay = parseDateOrToday(date),
                            supplier = supplier,
                            orderNumber = orderNo,
                            accountUsername = account,
                            vatType = vatType,
                            paymentMethod = payment,
                            notes = orderNotes,
                            items = forms.map { form ->
                                val quantity = form.quantity.toIntOrNull() ?: 0
                                val unitGross = runCatching { moneyToPence(form.gross) }.getOrDefault(0)
                                PurchaseItemDraft(
                                    id = if (isDuplicate) 0L else form.id,
                                    item = form.item,
                                    quantity = quantity,
                                    grossPence = unitGross * quantity.coerceAtLeast(0).toLong(),
                                    receivedQty = form.received.toIntOrNull() ?: 0,
                                    cancelledQty = form.cancelled.toIntOrNull() ?: 0,
                                    returnedQty = form.returned.toIntOrNull() ?: 0,
                                    refundExpectedPence = if (form.partialRefund) 0 else runCatching { moneyToPence(form.refundExpected) }.getOrDefault(0),
                                    refundReceivedPence = if (form.partialRefund) 0 else runCatching { moneyToPence(form.refundReceived) }.getOrDefault(0),
                                    partialRefund = form.partialRefund,
                                    refundNetPence = if (form.partialRefund) runCatching { moneyToPence(form.refundNet) }.getOrDefault(0) else 0,
                                    refundVatPence = if (form.partialRefund) runCatching { moneyToPence(form.refundVat) }.getOrDefault(0) else 0
                                )
                            },
                            existingInvoicePath = if (isDuplicate) null else order?.invoicePath
                        ),
                        attachmentUri = attachment,
                        itemNotes = forms.map { it.notes }
                    ) { nav.popBackStack() }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(6.dp)); Text("Save purchase") }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun PurchaseLineCardV3(index: Int, form: PurchaseLineFormV3, vatType: String, canDelete: Boolean, onChange: (PurchaseLineFormV3) -> Unit, onDelete: () -> Unit) {
    val qty = form.quantity.toIntOrNull() ?: 0
    val unitGrossPence = runCatching { moneyToPence(form.gross) }.getOrDefault(0)
    val lineGrossPence = unitGrossPence * qty.coerceAtLeast(0).toLong()
    val refundNet = runCatching { moneyToPence(form.refundNet) }.getOrDefault(0)
    val refundVat = runCatching { moneyToPence(form.refundVat) }.getOrDefault(0)

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Item ${index + 1}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (canDelete) IconButton(onDelete) { Icon(Icons.Default.DeleteOutline, "Remove item") }
            }
            FormField("Item", form.item, { onChange(form.copy(item = it)) })
            ResponsiveMoneyQuantityFields(form, onChange)
            if (unitGrossPence > 0 && qty > 0) {
                Text("Line gross ${formatMoney(lineGrossPence)} • $qty × ${formatMoney(unitGrossPence)}", style = MaterialTheme.typography.bodySmall)
            }
            FormField("Item notes / IMEI / serial number", form.notes, { onChange(form.copy(notes = it)) }, singleLine = false)

            HorizontalDivider()
            Text("Receipt / return / refund tracking", style = MaterialTheme.typography.labelLarge)
            Text("New order awaiting delivery? Leave Received, Cancelled, Returned and refund fields blank. The order will remain Pending Receipt.", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            ResponsiveTrackingFields(form, onChange)

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(form.partialRefund, { onChange(form.copy(partialRefund = it)) })
                Column(Modifier.weight(1f)) {
                    Text("Partial refund / price adjustment", fontWeight = FontWeight.SemiBold)
                    Text("Use when you keep the item but the supplier refunds part of its price.", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (form.partialRefund) {
                Text("Enter the refund exactly as credited. Net and VAT are separate because a supplier may refund net only with £0 VAT. Stock quantity is not reduced.", style = MaterialTheme.typography.bodySmall)
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth < 340.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            DecimalFieldV3("Refund net £", form.refundNet, Modifier.fillMaxWidth()) { onChange(form.copy(refundNet = it)) }
                            DecimalFieldV3("Refund VAT £", form.refundVat, Modifier.fillMaxWidth()) { onChange(form.copy(refundVat = it)) }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DecimalFieldV3("Refund net £", form.refundNet, Modifier.weight(1f)) { onChange(form.copy(refundNet = it)) }
                            DecimalFieldV3("Refund VAT £", form.refundVat, Modifier.weight(1f)) { onChange(form.copy(refundVat = it)) }
                        }
                    }
                }
                Text("Total partial refund ${formatMoney(refundNet + refundVat)} • Net ${formatMoney(refundNet)} • VAT ${formatMoney(refundVat)}${if (vatType == VatTypes.REVERSE && refundNet > 0) " • reverse VAT adjustment ${formatMoney(breakdownFromGross(refundNet, VatTypes.REVERSE).reverseVatPence)}" else ""}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            } else {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth < 340.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            DecimalFieldV3("Refund expected £", form.refundExpected, Modifier.fillMaxWidth()) { onChange(form.copy(refundExpected = it)) }
                            DecimalFieldV3("Refund received £", form.refundReceived, Modifier.fillMaxWidth()) { onChange(form.copy(refundReceived = it)) }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DecimalFieldV3("Refund expected £", form.refundExpected, Modifier.weight(1f)) { onChange(form.copy(refundExpected = it)) }
                            DecimalFieldV3("Refund received £", form.refundReceived, Modifier.weight(1f)) { onChange(form.copy(refundReceived = it)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResponsiveMoneyQuantityFields(form: PurchaseLineFormV3, onChange: (PurchaseLineFormV3) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 340.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberFieldV3("Quantity", form.quantity, Modifier.fillMaxWidth()) { onChange(form.copy(quantity = it)) }
                DecimalFieldV3("Unit gross £", form.gross, Modifier.fillMaxWidth()) { onChange(form.copy(gross = it)) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberFieldV3("Quantity", form.quantity, Modifier.weight(1f)) { onChange(form.copy(quantity = it)) }
                DecimalFieldV3("Unit gross £", form.gross, Modifier.weight(2f)) { onChange(form.copy(gross = it)) }
            }
        }
    }
}

@Composable
private fun ResponsiveTrackingFields(form: PurchaseLineFormV3, onChange: (PurchaseLineFormV3) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 390.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberFieldV3("Received", form.received, Modifier.fillMaxWidth()) { onChange(form.copy(received = it)) }
                NumberFieldV3("Cancelled", form.cancelled, Modifier.fillMaxWidth()) { onChange(form.copy(cancelled = it)) }
                NumberFieldV3("Returned", form.returned, Modifier.fillMaxWidth()) { onChange(form.copy(returned = it)) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NumberFieldV3("Received", form.received, Modifier.weight(1f)) { onChange(form.copy(received = it)) }
                NumberFieldV3("Cancelled", form.cancelled, Modifier.weight(1f)) { onChange(form.copy(cancelled = it)) }
                NumberFieldV3("Returned", form.returned, Modifier.weight(1f)) { onChange(form.copy(returned = it)) }
            }
        }
    }
}

@Composable
private fun NumberFieldV3(label: String, value: String, modifier: Modifier, onValue: (String) -> Unit) {
    OutlinedTextField(value, onValue, label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = modifier, singleLine = true)
}

@Composable
private fun DecimalFieldV3(label: String, value: String, modifier: Modifier, onValue: (String) -> Unit) {
    OutlinedTextField(value, onValue, label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = modifier, singleLine = true)
}

@Composable
private fun InventoryScreenV3(vm: AppViewModel) {
    val inv by vm.inventory.collectAsStateWithLifecycle()
    ScreenScaffold("Inventory") { inner ->
        val available = inv.filter { it.available > 0 }
        if (available.isEmpty()) Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.Center) { EmptyState("Received purchase items will appear here") }
        else LazyColumn(
            Modifier.padding(inner),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    "Identical item names are automatically consolidated. Ten individually purchased units of the same item appear as one inventory item with Qty 10.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            items(available, key = { it.item.lowercase() }) { row ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(row.item, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            AssistChip(onClick = {}, label = { Text("Qty ${row.available}") })
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Purchased ${row.purchased} • Received ${row.received} • Sold ${row.sold} • Supplier returns ${row.supplierReturned}", style = MaterialTheme.typography.bodySmall)
                        Text("Inventory net cost ${formatMoney(row.inventoryNetCostPence)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun unitGrossFromStoredLineV3(lineGrossPence: Long, quantity: Int): Long {
    if (quantity <= 1) return lineGrossPence
    return (lineGrossPence + quantity / 2L) / quantity.toLong()
}

private fun isCancelledOrRefundedOrderV3(lines: List<PurchaseEntity>): Boolean {
    if (lines.isEmpty() || purchaseOrderStatusV3(lines) in PendingReceiptStatusesV3) return false
    return lines.any { line ->
        line.cancelledQty > 0 ||
            line.returnedQty > 0 ||
            line.partialRefund ||
            line.refundExpectedPence > 0 ||
            line.refundReceivedPence > 0 ||
            line.status in setOf("CANCELLED", "RETURNED", "REFUND_PENDING", "REFUND_RECEIVED")
    }
}

private fun purchaseOrderStatusV3(lines: List<PurchaseEntity>): String {
    if (lines.isEmpty()) return "RECEIPT_PENDING"
    if (lines.any { it.status == "REFUND_PENDING" }) return "REFUND_PENDING"
    val receiptPending = lines.any { it.status in PendingReceiptStatusesV3 }
    if (receiptPending) return if (lines.any { it.receivedQty > 0 }) "PARTIALLY_RECEIVED" else "RECEIPT_PENDING"
    if (lines.all { it.status == "CANCELLED" }) return "CANCELLED"
    if (lines.any { it.status == "RETURNED" }) return "RETURNED"
    if (lines.any { it.status == "REFUND_RECEIVED" }) return "REFUND_RECEIVED"
    return "RECEIVED"
}
