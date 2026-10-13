package com.apingu.purchasesales.ui

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import java.time.LocalDate

private val PendingStatuses = setOf("RECEIPT_PENDING", "PARTIALLY_RECEIVED", "REFUND_PENDING", "RETURNED")

@Composable
fun PurchaseSalesRoot() {
    val context = LocalContext.current
    val app = context.applicationContext as PurchaseSalesApplication
    val vm: AppViewModel = viewModel(factory = AppViewModel.factory(app))
    val nav = rememberNavController()
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme() else lightColorScheme()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }
    MaterialTheme(colorScheme = scheme) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { outer ->
            Box(Modifier.padding(outer)) { AppNavigation(nav, vm) }
        }
    }
}

private data class BottomItem(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
private val bottomItems = listOf(
    BottomItem("dashboard", "Home", Icons.Default.Home),
    BottomItem("purchases", "Purchases", Icons.Default.ShoppingCart),
    BottomItem("inventory", "Inventory", Icons.Default.Inventory2),
    BottomItem("sales", "Sales", Icons.Default.ReceiptLong),
    BottomItem("more", "More", Icons.Default.MoreHoriz)
)

@Composable
private fun AppNavigation(nav: NavHostController, vm: AppViewModel) {
    val currentRoute = nav.currentBackStackEntryFlow.collectAsState(initial = nav.currentBackStackEntry).value?.destination?.route
    val showBottom = currentRoute in bottomItems.map { it.route }
    Scaffold(
        bottomBar = {
            if (showBottom) NavigationBar {
                bottomItems.forEach { item ->
                    NavigationBarItem(
                        selected = currentRoute == item.route,
                        onClick = { nav.navigate(item.route) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true } },
                        icon = { Icon(item.icon, item.label) },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(nav, startDestination = "dashboard", modifier = Modifier.padding(padding)) {
            composable("dashboard") { DashboardScreen(vm, nav) }
            composable("purchases") { PurchasesScreen(vm, nav) }
            composable("purchase/new") { PurchaseEditor(vm, nav, 0) }
            composable("purchase/{id}") { PurchaseEditor(vm, nav, it.arguments?.getString("id")?.toLongOrNull() ?: 0) }
            composable("inventory") { InventoryScreen(vm) }
            composable("sales") { SalesScreen(vm, nav) }
            composable("sale/new") { SaleEditor(vm, nav, 0) }
            composable("sale/{id}") { SaleEditor(vm, nav, it.arguments?.getString("id")?.toLongOrNull() ?: 0) }
            composable("more") { MoreScreen(nav) }
            composable("customers") { CustomersScreen(vm, nav) }
            composable("expenses") { ExpensesScreen(vm, nav) }
            composable("expense/new") { ExpenseEditor(vm, nav, 0) }
            composable("expense/{id}") { ExpenseEditor(vm, nav, it.arguments?.getString("id")?.toLongOrNull() ?: 0) }
            composable("reports") { ReportsScreen(vm, nav) }
            composable("documents") { DocumentsScreen(vm, nav) }
            composable("business") { BusinessScreen(vm, nav) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardScreen(vm: AppViewModel, nav: NavHostController) {
    val summary by vm.summary.collectAsStateWithLifecycle()
    val purchases by vm.purchases.collectAsStateWithLifecycle()
    val business by vm.business.collectAsStateWithLifecycle()
    ScreenScaffold("Purchase & Sales", actions = {}) { inner ->
        LazyColumn(Modifier.padding(inner).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(business.businessName.ifBlank { "Business dashboard" }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(if (business.accountingStartEpochDay > 0) "Accounting period ${displayDate(business.accountingStartEpochDay)} – ${displayDate(business.accountingEndEpochDay)}" else "Set your accounting period in Business Details", style = MaterialTheme.typography.bodySmall)
            }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { MetricCard("Net sales", formatMoney(summary.salesNet), Modifier.weight(1f)); MetricCard("Net profit", formatMoney(summary.netProfit), Modifier.weight(1f)) } }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { MetricCard("Inventory", formatMoney(summary.inventoryValue), Modifier.weight(1f)); MetricCard("Refunds pending", formatMoney(summary.refundsPending), Modifier.weight(1f)) } }
            item {
                val vatLabel = if (summary.vatPosition >= 0) "VAT due to HMRC" else "VAT refund expected"
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp)) { Text(vatLabel, style = MaterialTheme.typography.labelLarge); Text(formatMoney(kotlin.math.abs(summary.vatPosition)), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text("Output ${formatMoney(summary.outputVat)} • Input ${formatMoney(summary.inputVat)} • Reverse VAT nets to £0", style = MaterialTheme.typography.bodySmall) } }
            }
            item { Text("Quick actions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { Button({ nav.navigate("purchase/new") }, Modifier.weight(1f)) { Icon(Icons.Default.AddShoppingCart, null); Spacer(Modifier.width(6.dp)); Text("Purchase") }; Button({ nav.navigate("sale/new") }, Modifier.weight(1f)) { Icon(Icons.Default.PointOfSale, null); Spacer(Modifier.width(6.dp)); Text("Sale") } } }
            item { ElevatedCard(Modifier.fillMaxWidth(), onClick = { nav.navigate("purchases") }) { Row(Modifier.padding(18.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.LocalShipping, null); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text("Pending receipts", fontWeight = FontWeight.SemiBold); Text("${purchases.count { it.status in PendingStatuses }} purchases need attention") }; Icon(Icons.Default.ChevronRight, null) } } }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    ElevatedCard(modifier) { Column(Modifier.padding(16.dp)) { Text(label, style = MaterialTheme.typography.labelMedium); Spacer(Modifier.height(4.dp)); Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PurchasesScreen(vm: AppViewModel, nav: NavHostController) {
    val all by vm.purchases.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    val filtered = all.filter { (tab == 1 || it.status in PendingStatuses) && (query.isBlank() || it.item.contains(query, true) || it.supplier.contains(query, true) || it.orderNumber.contains(query, true)) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Purchases") }) },
        floatingActionButton = { FloatingActionButton({ nav.navigate("purchase/new") }) { Icon(Icons.Default.Add, "New purchase") } }
    ) { inner ->
        Column(Modifier.padding(inner)) {
            TabRow(tab) { Tab(tab == 0, { tab = 0 }, text = { Text("Pending (${all.count { it.status in PendingStatuses }})") }); Tab(tab == 1, { tab = 1 }, text = { Text("All") }) }
            OutlinedTextField(query, { query = it }, label = { Text("Search purchases") }, leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth().padding(12.dp), singleLine = true)
            if (filtered.isEmpty()) EmptyState("No purchases here yet") else LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(filtered, key = { it.id }) { p ->
                    ElevatedCard(Modifier.fillMaxWidth(), onClick = { nav.navigate("purchase/${p.id}") }) {
                        Column(Modifier.padding(14.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(p.item, fontWeight = FontWeight.SemiBold); Text("${p.supplier} • ${displayDate(p.purchaseDateEpochDay)}", style = MaterialTheme.typography.bodySmall) }; StatusChip(p.status) }
                            Spacer(Modifier.height(8.dp)); Text("Qty ${p.quantity} • ${formatMoney(p.grossPence)} • ${VatTypes.label(p.vatType)}", style = MaterialTheme.typography.bodyMedium)
                            Text("Received ${p.receivedQty} • Returned ${p.returnedQty} • Refund ${formatMoney(p.refundReceivedPence)} / ${formatMoney(p.refundExpectedPence)}", style = MaterialTheme.typography.bodySmall)
                            if (p.status == "RECEIPT_PENDING" || p.status == "PARTIALLY_RECEIVED") { Spacer(Modifier.height(8.dp)); TextButton({ vm.markReceivedAll(p) }) { Icon(Icons.Default.CheckCircle, null); Spacer(Modifier.width(5.dp)); Text("Receive remaining") } }
                        }
                    }
                }
                item { Spacer(Modifier.height(70.dp)) }
            }
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    AssistChip(onClick = {}, label = { Text(status.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PurchaseEditor(vm: AppViewModel, nav: NavHostController, id: Long) {
    val purchases by vm.purchases.collectAsStateWithLifecycle()
    val p = purchases.firstOrNull { it.id == id }
    val existing = id == 0L || p != null
    if (!existing) { LoadingScreen(); return }
    var date by remember(p?.id) { mutableStateOf(editDate(p?.purchaseDateEpochDay ?: epochDayToday())) }
    var supplier by remember(p?.id) { mutableStateOf(p?.supplier.orEmpty()) }
    var item by remember(p?.id) { mutableStateOf(p?.item.orEmpty()) }
    var qty by remember(p?.id) { mutableStateOf((p?.quantity ?: 1).toString()) }
    var order by remember(p?.id) { mutableStateOf(p?.orderNumber.orEmpty()) }
    var account by remember(p?.id) { mutableStateOf(p?.accountUsername.orEmpty()) }
    var gross by remember(p?.id) { mutableStateOf(p?.grossPence?.let(::formatMoneyPlain).orEmpty()) }
    var vatType by remember(p?.id) { mutableStateOf(p?.vatType ?: VatTypes.STANDARD) }
    var payment by remember(p?.id) { mutableStateOf(p?.paymentMethod.orEmpty()) }
    var received by remember(p?.id) { mutableStateOf((p?.receivedQty ?: 0).toString()) }
    var cancelled by remember(p?.id) { mutableStateOf((p?.cancelledQty ?: 0).toString()) }
    var returned by remember(p?.id) { mutableStateOf((p?.returnedQty ?: 0).toString()) }
    var refundExpected by remember(p?.id) { mutableStateOf(p?.refundExpectedPence?.let(::formatMoneyPlain).orEmpty()) }
    var refundReceived by remember(p?.id) { mutableStateOf(p?.refundReceivedPence?.let(::formatMoneyPlain).orEmpty()) }
    var notes by remember(p?.id) { mutableStateOf(p?.notes.orEmpty()) }
    var attachment by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { attachment = it }
    val breakdown = runCatching { breakdownFromGross(moneyToPence(gross), vatType) }.getOrDefault(VatBreakdown(0,0,0,0))

    ScreenScaffold(if (id == 0L) "New purchase" else "Edit purchase", onBack = { nav.popBackStack() }) { inner ->
        Column(Modifier.padding(inner).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FormField("Purchase date (DD/MM/YYYY)", date, { date = it })
            FormField("Supplier / Store", supplier, { supplier = it })
            FormField("Item", item, { item = it })
            FormField("Quantity", qty, { qty = it }, KeyboardType.Number)
            FormField("Order number", order, { order = it })
            FormField("Email / Username", account, { account = it })
            FormField("Gross cost (£)", gross, { gross = it }, KeyboardType.Decimal)
            VatSelector(vatType) { vatType = it }
            ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Text("Net ${formatMoney(breakdown.netPence)} • VAT ${formatMoney(breakdown.vatPence)}"); if (vatType == VatTypes.REVERSE) Text("Notional reverse VAT ${formatMoney(breakdown.reverseVatPence)} (input and output; net £0)", style = MaterialTheme.typography.bodySmall) } }
            FormField("Payment method", payment, { payment = it })
            HorizontalDivider(); Text("Receipt / return tracking", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            FormField("Quantity received", received, { received = it }, KeyboardType.Number)
            FormField("Quantity cancelled", cancelled, { cancelled = it }, KeyboardType.Number)
            FormField("Quantity returned to supplier", returned, { returned = it }, KeyboardType.Number)
            FormField("Refund expected (£)", refundExpected, { refundExpected = it }, KeyboardType.Decimal)
            FormField("Refund received (£)", refundReceived, { refundReceived = it }, KeyboardType.Decimal)
            FormField("Notes", notes, { notes = it }, singleLine = false)
            OutlinedButton({ picker.launch("*/*") }, Modifier.fillMaxWidth()) { Icon(Icons.Default.AttachFile, null); Spacer(Modifier.width(6.dp)); Text(if (attachment != null) "New invoice selected" else if (!p?.invoicePath.isNullOrBlank()) "Replace purchase invoice" else "Attach purchase invoice (optional)") }
            Button(
                onClick = {
                    vm.savePurchase(
                        PurchaseDraft(id, parseDateOrToday(date), supplier, item, qty.toIntOrNull() ?: 0, order, account, runCatching { moneyToPence(gross) }.getOrDefault(0), vatType, payment, received.toIntOrNull() ?: 0, cancelled.toIntOrNull() ?: 0, returned.toIntOrNull() ?: 0, runCatching { moneyToPence(refundExpected) }.getOrDefault(0), runCatching { moneyToPence(refundReceived) }.getOrDefault(0), notes, p?.invoicePath),
                        attachment
                    ) { nav.popBackStack() }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(6.dp)); Text("Save purchase") }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InventoryScreen(vm: AppViewModel) {
    val inv by vm.inventory.collectAsStateWithLifecycle()
    ScreenScaffold("Inventory") { inner ->
        if (inv.isEmpty()) Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.Center) { EmptyState("Received purchases will appear here") }
        else LazyColumn(Modifier.padding(inner), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(inv.filter { it.available > 0 }, key = { it.item }) { row ->
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Row(Modifier.fillMaxWidth()) { Text(row.item, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text("${row.available} available", fontWeight = FontWeight.Bold) }; Spacer(Modifier.height(6.dp)); Text("Received ${row.received} • Sold ${row.sold} • Supplier returns ${row.supplierReturned}", style = MaterialTheme.typography.bodySmall); Text("Inventory net cost ${formatMoney(row.inventoryNetCostPence)}", style = MaterialTheme.typography.bodySmall) } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { if (onBack != null) IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } }, actions = actions) }, content = content)
}

@Composable
fun FormField(label: String, value: String, onValueChange: (String) -> Unit, keyboard: KeyboardType = KeyboardType.Text, singleLine: Boolean = true) {
    OutlinedTextField(value, onValueChange, label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = Modifier.fillMaxWidth(), singleLine = singleLine, minLines = if (singleLine) 1 else 2)
}

@Composable
fun VatSelector(value: String, onValue: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box { OutlinedButton({ open = true }, Modifier.fillMaxWidth()) { Text("VAT: ${VatTypes.label(value)}", Modifier.weight(1f)); Icon(Icons.Default.ArrowDropDown, null) }; DropdownMenu(open, { open = false }) { VatTypes.all.forEach { v -> DropdownMenuItem(text = { Text(VatTypes.label(v)) }, onClick = { onValue(v); open = false }) } } }
}

@Composable
fun CustomerSelector(customers: List<CustomerEntity>, selected: Long, onValue: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = customers.firstOrNull { it.id == selected }?.companyName ?: "Select customer"
    Box { OutlinedButton({ open = true }, Modifier.fillMaxWidth()) { Text(label, Modifier.weight(1f)); Icon(Icons.Default.ArrowDropDown, null) }; DropdownMenu(open, { open = false }) { customers.forEach { c -> DropdownMenuItem(text = { Text("${c.companyName} (${c.invoiceCode})") }, onClick = { onValue(c.id); open = false }) } } }
}

@Composable
fun SaleLineSelector(lines: List<SaleLineEntity>, selected: Long, onValue: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val line = lines.firstOrNull { it.id == selected }
    Box { OutlinedButton({ open = true }, Modifier.fillMaxWidth()) { Text(line?.let { "${it.item} • ${it.quantity} sold" } ?: "Select item", Modifier.weight(1f)); Icon(Icons.Default.ArrowDropDown, null) }; DropdownMenu(open, { open = false }) { lines.forEach { l -> DropdownMenuItem(text = { Text("${l.item} • ${l.quantity} sold") }, onClick = { onValue(l.id); open = false }) } } }
}

@Composable fun EmptyState(text: String) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.Inbox, null, modifier = Modifier.size(44.dp)); Spacer(Modifier.height(8.dp)); Text(text, style = MaterialTheme.typography.bodyMedium) } }
@Composable fun LoadingScreen() { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
