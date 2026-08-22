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

private val PurchaseAttentionStatuses = setOf("RECEIPT_PENDING", "PARTIALLY_RECEIVED", "REFUND_PENDING", "RETURNED")

@Composable
fun PurchaseSalesRootV2() {
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
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { outer ->
            Box(Modifier.padding(outer)) { AppNavigationV2(nav, vm) }
        }
    }
}

private data class BottomItemV2(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

private val bottomItemsV2 = listOf(
    BottomItemV2("dashboard", "Home", Icons.Default.Home),
    BottomItemV2("purchases", "Purchases", Icons.Default.ShoppingCart),
    BottomItemV2("inventory", "Inventory", Icons.Default.Inventory2),
    BottomItemV2("sales", "Sales", Icons.Default.ReceiptLong),
    BottomItemV2("more", "More", Icons.Default.MoreHoriz)
)

@Composable
private fun AppNavigationV2(nav: NavHostController, vm: AppViewModel) {
    val currentRoute = nav.currentBackStackEntryFlow.collectAsState(initial = nav.currentBackStackEntry).value?.destination?.route
    val showBottom = currentRoute in bottomItemsV2.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottom) {
                NavigationBar {
                    bottomItemsV2.forEach { item ->
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
            composable("dashboard") { DashboardScreenV2(vm, nav) }
            composable("purchases") { PurchasesScreenV2(vm, nav) }
            composable("purchase/new") { PurchaseOrderEditor(vm, nav, 0L) }
            composable("purchase/{id}") {
                PurchaseOrderEditor(vm, nav, it.arguments?.getString("id")?.toLongOrNull() ?: 0L)
            }
            composable("inventory") { InventoryScreenV2(vm) }
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
private fun DashboardScreenV2(vm: AppViewModel, nav: NavHostController) {
    val summary by vm.summary.collectAsStateWithLifecycle()
    val orders by vm.purchaseOrders.collectAsStateWithLifecycle()
    val purchaseLines by vm.purchases.collectAsStateWithLifecycle()
    val business by vm.business.collectAsStateWithLifecycle()
    val pendingOrders = orders.count { order ->
        purchaseOrderStatus(purchaseLines.filter { it.purchaseOrderId == order.id }) in PurchaseAttentionStatuses
    }

    ScreenScaffold("Purchase & Sales") { inner ->
        LazyColumn(
            Modifier.padding(inner).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    business.businessName.ifBlank { "Business dashboard" },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (business.accountingStartEpochDay > 0)
                        "Accounting period ${displayDate(business.accountingStartEpochDay)} – ${displayDate(business.accountingEndEpochDay)}"
                    else "Set your accounting period in Business Details",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("Net sales", formatMoney(summary.salesNet), Modifier.weight(1f))
                    MetricCard("Net profit", formatMoney(summary.netProfit), Modifier.weight(1f))
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("Inventory", formatMoney(summary.inventoryValue), Modifier.weight(1f))
                    MetricCard("Refunds pending", formatMoney(summary.refundsPending), Modifier.weight(1f))
                }
            }
            item {
                val vatLabel = if (summary.vatPosition >= 0) "VAT due to HMRC" else "VAT refund expected"
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        Text(vatLabel, style = MaterialTheme.typography.labelLarge)
                        Text(formatMoney(kotlin.math.abs(summary.vatPosition)), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Output ${formatMoney(summary.outputVat)} • Input ${formatMoney(summary.inputVat)} • Reverse VAT nets to £0",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            item { Text("Quick actions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button({ nav.navigate("purchase/new") }, Modifier.weight(1f)) {
                        Icon(Icons.Default.AddShoppingCart, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Purchase")
                    }
                    Button({ nav.navigate("sale/new") }, Modifier.weight(1f)) {
                        Icon(Icons.Default.PointOfSale, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Sale")
                    }
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth(), onClick = { nav.navigate("purchases") }) {
                    Row(Modifier.padding(18.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocalShipping, null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Pending receipts", fontWeight = FontWeight.SemiBold)
                            Text("$pendingOrders purchase order${if (pendingOrders == 1) "" else "s"} need attention")
                        }
                        Icon(Icons.Default.ChevronRight, null)
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PurchasesScreenV2(vm: AppViewModel, nav: NavHostController) {
    val orders by vm.purchaseOrders.collectAsStateWithLifecycle()
    val allLines by vm.purchases.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }

    val rows = orders.map { order -> order to allLines.filter { it.purchaseOrderId == order.id } }
    val pendingCount = rows.count { (_, lines) -> purchaseOrderStatus(lines) in PurchaseAttentionStatuses }
    val filtered = rows.filter { (order, lines) ->
        val statusMatches = tab == 1 || purchaseOrderStatus(lines) in PurchaseAttentionStatuses
        val queryMatches = query.isBlank() ||
            order.supplier.contains(query, true) ||
            order.orderNumber.contains(query, true) ||
            lines.any { it.item.contains(query, true) }
        statusMatches && queryMatches
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Purchases") }) },
        floatingActionButton = {
            FloatingActionButton({ nav.navigate("purchase/new") }) { Icon(Icons.Default.Add, "New purchase") }
        }
    ) { inner ->
        Column(Modifier.padding(inner)) {
            TabRow(tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Pending ($pendingCount)") })
                Tab(tab == 1, { tab = 1 }, text = { Text("All") })
            }
            OutlinedTextField(
                query,
                { query = it },
                label = { Text("Search supplier, order or item") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                singleLine = true
            )
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EmptyState("No purchase orders here yet") }
            } else {
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(filtered, key = { it.first.id }) { (order, lines) ->
                        PurchaseOrderCard(order, lines, nav, vm)
                    }
                    item { Spacer(Modifier.height(70.dp)) }
                }
            }
        }
    }
}

@Composable
private fun PurchaseOrderCard(
    order: PurchaseOrderEntity,
    lines: List<PurchaseEntity>,
    nav: NavHostController,
    vm: AppViewModel
) {
    val status = purchaseOrderStatus(lines)
    val totalGross = lines.sumOf { it.grossPence }
    val totalQty = lines.sumOf { it.quantity }
    val received = lines.sumOf { it.receivedQty }
    val returned = lines.sumOf { it.returnedQty }
    val partialCredits = lines.filter { it.partialRefund }.sumOf { it.refundNetPence + it.refundVatPence }
    val itemText = when {
        lines.isEmpty() -> "No items"
        lines.size == 1 -> "${lines.first().item} × ${lines.first().quantity}"
        else -> {
            val firstTwo = lines.take(2).joinToString(" • ") { "${it.item} × ${it.quantity}" }
            if (lines.size > 2) "$firstTwo • +${lines.size - 2} more" else firstTwo
        }
    }

    ElevatedCard(Modifier.fillMaxWidth(), onClick = { nav.navigate("purchase/${order.id}") }) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(order.supplier, fontWeight = FontWeight.SemiBold)
                    Text(
                        buildString {
                            append(displayDate(order.purchaseDateEpochDay))
                            if (order.orderNumber.isNotBlank()) append(" • ${order.orderNumber}")
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                StatusChipV2(status)
            }
            Text(itemText, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${lines.size} item line${if (lines.size == 1) "" else "s"} • Qty $totalQty • ${formatMoney(totalGross)} • ${VatTypes.label(order.vatType)}",
                style = MaterialTheme.typography.bodySmall
            )
            Text("Received $received • Returned $returned", style = MaterialTheme.typography.bodySmall)
            if (partialCredits > 0) Text("Partial refund credits ${formatMoney(partialCredits)}", style = MaterialTheme.typography.bodySmall)
            if (status == "RECEIPT_PENDING" || status == "PARTIALLY_RECEIVED") {
                TextButton({ vm.markReceivedAllOrder(order.id) }) {
                    Icon(Icons.Default.CheckCircle, null)
                    Spacer(Modifier.width(5.dp))
                    Text("Receive all remaining items")
                }
            }
        }
    }
}

@Composable
private fun StatusChipV2(status: String) {
    AssistChip(
        onClick = {},
        label = { Text(status.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }) }
    )
}

private data class PurchaseLineFormV2(
    val id: Long = 0,
    val item: String = "",
    val quantity: String = "1",
    val gross: String = "",
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
private fun PurchaseOrderEditor(vm: AppViewModel, nav: NavHostController, id: Long) {
    val orders by vm.purchaseOrders.collectAsStateWithLifecycle()
    val allLines by vm.purchases.collectAsStateWithLifecycle()
    val order = orders.firstOrNull { it.id == id }
    val existingLines = allLines.filter { it.purchaseOrderId == id }.sortedBy { it.id }

    if (id > 0 && (order == null || existingLines.isEmpty())) {
        LoadingScreen()
        return
    }

    var date by remember(order?.id) { mutableStateOf(editDate(order?.purchaseDateEpochDay ?: epochDayToday())) }
    var supplier by remember(order?.id) { mutableStateOf(order?.supplier.orEmpty()) }
    var orderNo by remember(order?.id) { mutableStateOf(order?.orderNumber.orEmpty()) }
    var account by remember(order?.id) { mutableStateOf(order?.accountUsername.orEmpty()) }
    var vatType by remember(order?.id) { mutableStateOf(order?.vatType ?: VatTypes.STANDARD) }
    var payment by remember(order?.id) { mutableStateOf(order?.paymentMethod.orEmpty()) }
    var notes by remember(order?.id) { mutableStateOf(order?.notes.orEmpty()) }
    var attachment by remember { mutableStateOf<Uri?>(null) }

    val forms = remember(order?.id, existingLines.size) {
        mutableStateListOf<PurchaseLineFormV2>().apply {
            if (existingLines.isNotEmpty()) {
                existingLines.forEach { line ->
                    add(
                        PurchaseLineFormV2(
                            id = line.id,
                            item = line.item,
                            quantity = line.quantity.toString(),
                            gross = formatMoneyPlain(line.grossPence),
                            received = line.receivedQty.takeIf { it > 0 }?.toString().orEmpty(),
                            cancelled = line.cancelledQty.takeIf { it > 0 }?.toString().orEmpty(),
                            returned = line.returnedQty.takeIf { it > 0 }?.toString().orEmpty(),
                            refundExpected = line.refundExpectedPence.takeIf { it > 0 && !line.partialRefund }?.let(::formatMoneyPlain).orEmpty(),
                            refundReceived = line.refundReceivedPence.takeIf { it > 0 && !line.partialRefund }?.let(::formatMoneyPlain).orEmpty(),
                            partialRefund = line.partialRefund,
                            refundNet = line.refundNetPence.takeIf { it > 0 && line.partialRefund }?.let(::formatMoneyPlain).orEmpty(),
                            refundVat = line.refundVatPence.takeIf { it > 0 && line.partialRefund }?.let(::formatMoneyPlain).orEmpty()
                        )
                    )
                }
            } else {
                add(PurchaseLineFormV2())
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { attachment = it }
    val totalGross = forms.sumOf { runCatching { moneyToPence(it.gross) }.getOrDefault(0) }
    val totalBreakdown = breakdownFromGross(totalGross, vatType)
    val totalQty = forms.sumOf { it.quantity.toIntOrNull() ?: 0 }
    val totalPartialCredits = forms.filter { it.partialRefund }.sumOf {
        runCatching { moneyToPence(it.refundNet) }.getOrDefault(0) + runCatching { moneyToPence(it.refundVat) }.getOrDefault(0)
    }

    ScreenScaffold(if (id == 0L) "New purchase" else "Edit purchase", onBack = { nav.popBackStack() }) { inner ->
        Column(
            Modifier.padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Purchase details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            FormField("Purchase date (DD/MM/YYYY)", date, { date = it })
            FormField("Supplier / Store", supplier, { supplier = it })
            FormField("Order number", orderNo, { orderNo = it })
            FormField("Email / Username", account, { account = it })
            VatSelector(vatType) { vatType = it }
            FormField("Payment method", payment, { payment = it })

            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Items purchased", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Add every item from this supplier order", style = MaterialTheme.typography.bodySmall)
                }
                AssistChip(onClick = {}, label = { Text("${forms.size} line${if (forms.size == 1) "" else "s"}") })
            }

            forms.forEachIndexed { index, form ->
                PurchaseLineCardV2(
                    index = index,
                    form = form,
                    vatType = vatType,
                    canDelete = forms.size > 1,
                    onChange = { updated -> forms[index] = updated },
                    onDelete = { if (forms.size > 1) forms.removeAt(index) }
                )
            }

            OutlinedButton(
                onClick = { forms.add(PurchaseLineFormV2()) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(6.dp))
                Text("Add another item")
            }

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Purchase total", style = MaterialTheme.typography.labelLarge)
                    Text(formatMoney(totalBreakdown.grossPence), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("${forms.size} item line${if (forms.size == 1) "" else "s"} • $totalQty total units")
                    Text("Net ${formatMoney(totalBreakdown.netPence)} • VAT ${formatMoney(totalBreakdown.vatPence)}", style = MaterialTheme.typography.bodySmall)
                    if (totalPartialCredits > 0) {
                        Text("Partial refund credits ${formatMoney(totalPartialCredits)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    }
                    if (vatType == VatTypes.REVERSE) {
                        Text(
                            "Notional reverse VAT ${formatMoney(totalBreakdown.reverseVatPence)} (input and output; net £0)",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            FormField("Notes", notes, { notes = it }, singleLine = false)
            OutlinedButton({ picker.launch("*/*") }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.AttachFile, null)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (attachment != null) "New invoice selected"
                    else if (!order?.invoicePath.isNullOrBlank()) "Replace purchase invoice"
                    else "Attach purchase invoice (optional)"
                )
            }

            Button(
                onClick = {
                    vm.savePurchaseOrder(
                        PurchaseOrderDraft(
                            id = id,
                            dateEpochDay = parseDateOrToday(date),
                            supplier = supplier,
                            orderNumber = orderNo,
                            accountUsername = account,
                            vatType = vatType,
                            paymentMethod = payment,
                            notes = notes,
                            items = forms.map { form ->
                                PurchaseItemDraft(
                                    id = form.id,
                                    item = form.item,
                                    quantity = form.quantity.toIntOrNull() ?: 0,
                                    grossPence = runCatching { moneyToPence(form.gross) }.getOrDefault(0),
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
                            existingInvoicePath = order?.invoicePath
                        ),
                        attachment
                    ) { nav.popBackStack() }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                Icon(Icons.Default.Save, null)
                Spacer(Modifier.width(6.dp))
                Text("Save purchase")
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun PurchaseLineCardV2(
    index: Int,
    form: PurchaseLineFormV2,
    vatType: String,
    canDelete: Boolean,
    onChange: (PurchaseLineFormV2) -> Unit,
    onDelete: () -> Unit
) {
    val qty = form.quantity.toIntOrNull() ?: 0
    val grossPence = runCatching { moneyToPence(form.gross) }.getOrDefault(0)
    val unitGross = if (qty > 0) grossPence / qty else 0
    val refundNet = runCatching { moneyToPence(form.refundNet) }.getOrDefault(0)
    val refundVat = runCatching { moneyToPence(form.refundVat) }.getOrDefault(0)

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Item ${index + 1}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (canDelete) {
                    IconButton(onDelete) { Icon(Icons.Default.DeleteOutline, "Remove item") }
                }
            }
            FormField("Item", form.item, { onChange(form.copy(item = it)) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    form.quantity,
                    { onChange(form.copy(quantity = it)) },
                    label = { Text("Quantity") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    form.gross,
                    { onChange(form.copy(gross = it)) },
                    label = { Text("Line gross £") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(2f),
                    singleLine = true
                )
            }
            if (grossPence > 0 && qty > 0) {
                Text("Unit gross ${formatMoney(unitGross)}", style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()
            Text("Receipt / return / refund tracking", style = MaterialTheme.typography.labelLarge)
            Text(
                "New order awaiting delivery? Leave Received, Cancelled, Returned and refund fields blank. The order will remain Pending Receipt.",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactNumberField("Received", form.received, Modifier.weight(1f)) { onChange(form.copy(received = it)) }
                CompactNumberField("Cancelled", form.cancelled, Modifier.weight(1f)) { onChange(form.copy(cancelled = it)) }
                CompactNumberField("Returned", form.returned, Modifier.weight(1f)) { onChange(form.copy(returned = it)) }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = form.partialRefund,
                    onCheckedChange = { checked -> onChange(form.copy(partialRefund = checked)) }
                )
                Column(Modifier.weight(1f)) {
                    Text("Partial refund / price adjustment", fontWeight = FontWeight.SemiBold)
                    Text("Use when you keep the item but the supplier refunds part of its price.", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (form.partialRefund) {
                Text(
                    "Enter the refund exactly as credited. Net and VAT are separate because a supplier may refund net only with £0 VAT. Stock quantity is not reduced.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        form.refundNet,
                        { onChange(form.copy(refundNet = it)) },
                        label = { Text("Refund net £") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        form.refundVat,
                        { onChange(form.copy(refundVat = it)) },
                        label = { Text("Refund VAT £") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                Text(
                    "Total partial refund ${formatMoney(refundNet + refundVat)} • Net ${formatMoney(refundNet)} • VAT ${formatMoney(refundVat)}${if (vatType == VatTypes.REVERSE && refundNet > 0) " • reverse VAT adjustment ${formatMoney(breakdownFromGross(refundNet, VatTypes.REVERSE).reverseVatPence)}" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        form.refundExpected,
                        { onChange(form.copy(refundExpected = it)) },
                        label = { Text("Refund expected £") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        form.refundReceived,
                        { onChange(form.copy(refundReceived = it)) },
                        label = { Text("Refund received £") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactNumberField(label: String, value: String, modifier: Modifier, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
        singleLine = true
    )
}

@Composable
private fun InventoryScreenV2(vm: AppViewModel) {
    val inv by vm.inventory.collectAsStateWithLifecycle()
    ScreenScaffold("Inventory") { inner ->
        val available = inv.filter { it.available > 0 }
        if (available.isEmpty()) {
            Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState("Received purchase items will appear here")
            }
        } else {
            LazyColumn(
                Modifier.padding(inner),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(available, key = { it.item }) { row ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(Modifier.fillMaxWidth()) {
                                Text(row.item, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text("${row.available} available", fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Purchased ${row.purchased} • Received ${row.received} • Sold ${row.sold} • Supplier returns ${row.supplierReturned}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text("Inventory net cost ${formatMoney(row.inventoryNetCostPence)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

private fun purchaseOrderStatus(lines: List<PurchaseEntity>): String {
    if (lines.isEmpty()) return "RECEIPT_PENDING"
    if (lines.any { it.status == "REFUND_PENDING" }) return "REFUND_PENDING"

    val receiptPending = lines.any { it.status == "RECEIPT_PENDING" || it.status == "PARTIALLY_RECEIVED" }
    if (receiptPending) {
        val anyReceived = lines.any { it.receivedQty > 0 }
        return if (anyReceived) "PARTIALLY_RECEIVED" else "RECEIPT_PENDING"
    }

    if (lines.all { it.status == "CANCELLED" }) return "CANCELLED"
    if (lines.any { it.status == "RETURNED" }) return "RETURNED"
    if (lines.any { it.status == "REFUND_RECEIVED" }) return "REFUND_RECEIVED"
    return "RECEIVED"
}
