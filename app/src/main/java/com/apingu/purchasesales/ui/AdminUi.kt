package com.apingu.purchasesales.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.apingu.purchasesales.data.*
import com.apingu.purchasesales.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomersScreen(vm: AppViewModel, nav: NavHostController) {
    val customers by vm.customers.collectAsStateWithLifecycle()
    var edit by remember { mutableStateOf<CustomerEntity?>(null) }
    var newDialog by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text("Customers") }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "Back") } }) }, floatingActionButton = { FloatingActionButton({ newDialog = true }) { Icon(Icons.Default.Add, null) } }) { inner ->
        if (customers.isEmpty()) Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.Center) { EmptyState("Create customers for sales invoices") }
        else LazyColumn(Modifier.padding(inner), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { items(customers, key = { it.id }) { c -> ElevatedCard(Modifier.fillMaxWidth(), onClick = { edit = c }) { Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(c.companyName, fontWeight = FontWeight.SemiBold); Text("Invoice code ${c.invoiceCode}", style = MaterialTheme.typography.bodySmall) }; Icon(Icons.Default.ChevronRight, null) } } } }
    }
    if (newDialog) CustomerDialog(null, { newDialog = false }) { vm.saveCustomer(it) { newDialog = false } }
    edit?.let { c -> CustomerDialog(c, { edit = null }) { vm.saveCustomer(it) { edit = null } } }
}

@Composable
private fun CustomerDialog(existing: CustomerEntity?, onDismiss: () -> Unit, onSave: (CustomerEntity) -> Unit) {
    var name by remember(existing?.id) { mutableStateOf(existing?.companyName.orEmpty()) }
    var code by remember(existing?.id) { mutableStateOf(existing?.invoiceCode.orEmpty()) }
    var address by remember(existing?.id) { mutableStateOf(existing?.address.orEmpty()) }
    var email by remember(existing?.id) { mutableStateOf(existing?.email.orEmpty()) }
    var phone by remember(existing?.id) { mutableStateOf(existing?.phone.orEmpty()) }
    var vat by remember(existing?.id) { mutableStateOf(existing?.vatNumber.orEmpty()) }
    var company by remember(existing?.id) { mutableStateOf(existing?.companyNumber.orEmpty()) }
    var notes by remember(existing?.id) { mutableStateOf(existing?.notes.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "New customer" else "Edit customer") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) { FormField("Company / customer name", name, { name = it }); FormField("Invoice code (2–5 chars)", code, { code = it.uppercase().take(5) }); FormField("Billing address", address, { address = it }, singleLine = false); FormField("Email", email, { email = it }); FormField("Phone", phone, { phone = it }); FormField("VAT number", vat, { vat = it }); FormField("Company number", company, { company = it }); FormField("Notes", notes, { notes = it }, singleLine = false) } }, confirmButton = { TextButton({ onSave(CustomerEntity(existing?.id ?: 0L, name, code, address, email, phone, vat, company, notes)) }) { Text("Save") } }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpensesScreen(vm: AppViewModel, nav: NavHostController) {
    val expenses by vm.expenses.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Expenses") }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "Back") } }) }, floatingActionButton = { FloatingActionButton({ nav.navigate("expense/new") }) { Icon(Icons.Default.Add, null) } }) { inner ->
        if (expenses.isEmpty()) Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.Center) { EmptyState("No expenses recorded") }
        else LazyColumn(Modifier.padding(inner), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { items(expenses, key = { it.id }) { e -> ElevatedCard(Modifier.fillMaxWidth(), onClick = { nav.navigate("expense/${e.id}") }) { Column(Modifier.padding(14.dp)) { Row(Modifier.fillMaxWidth()) { Text(e.details, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text(formatMoney(e.grossPence), fontWeight = FontWeight.Bold) }; Text("${e.supplier} • ${displayDate(e.expenseDateEpochDay)} • ${VatTypes.label(e.vatType)}", style = MaterialTheme.typography.bodySmall) } } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseEditor(vm: AppViewModel, nav: NavHostController, id: Long) {
    val expenses by vm.expenses.collectAsStateWithLifecycle()
    val e = expenses.firstOrNull { it.id == id }
    if (id > 0 && e == null) { LoadingScreen(); return }
    var date by remember(e?.id) { mutableStateOf(editDate(e?.expenseDateEpochDay ?: epochDayToday())) }
    var supplier by remember(e?.id) { mutableStateOf(e?.supplier.orEmpty()) }
    var details by remember(e?.id) { mutableStateOf(e?.details.orEmpty()) }
    var account by remember(e?.id) { mutableStateOf(e?.account.orEmpty()) }
    var gross by remember(e?.id) { mutableStateOf(e?.grossPence?.let(::formatMoneyPlain).orEmpty()) }
    var vatType by remember(e?.id) { mutableStateOf(e?.vatType ?: VatTypes.STANDARD) }
    var payment by remember(e?.id) { mutableStateOf(e?.paymentMethod.orEmpty()) }
    var comments by remember(e?.id) { mutableStateOf(e?.comments.orEmpty()) }
    var attachment by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { attachment = it }
    val b = breakdownFromGross(runCatching { moneyToPence(gross) }.getOrDefault(0), vatType)
    ScreenScaffold(if (id == 0L) "New expense" else "Edit expense", onBack = { nav.popBackStack() }) { inner -> Column(Modifier.padding(inner).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FormField("Date", date, { date = it }); FormField("Store / supplier", supplier, { supplier = it }); FormField("Details", details, { details = it }); FormField("Account / category", account, { account = it }); FormField("Gross total (£)", gross, { gross = it }, KeyboardType.Decimal); VatSelector(vatType) { vatType = it }; Text("Net ${formatMoney(b.netPence)} • VAT ${formatMoney(b.vatPence)}", style = MaterialTheme.typography.bodySmall); FormField("Payment method", payment, { payment = it }); FormField("Comments", comments, { comments = it }, singleLine = false); OutlinedButton({ picker.launch("*/*") }, Modifier.fillMaxWidth()) { Icon(Icons.Default.AttachFile, null); Text(if (attachment != null) " Receipt selected" else if (!e?.attachmentPath.isNullOrBlank()) " Replace receipt/invoice" else " Attach receipt/invoice") }; Button({ vm.saveExpense(ExpenseDraft(id, parseDateOrToday(date), supplier, details, account, runCatching { moneyToPence(gross) }.getOrDefault(0), vatType, payment, comments, e?.attachmentPath), attachment) { nav.popBackStack() } }, Modifier.fillMaxWidth()) { Text("Save expense") }; Spacer(Modifier.height(20.dp))
    } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(vm: AppViewModel, nav: NavHostController) {
    val s by vm.summary.collectAsStateWithLifecycle()
    val period by vm.selectedAccountingPeriod.collectAsStateWithLifecycle()
    val excelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { it?.let(vm::exportExcel) }
    ScreenScaffold("Profit & VAT", onBack = { nav.popBackStack() }) { inner -> LazyColumn(Modifier.padding(inner), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { AccountingPeriodSelector(vm) }
        item { MetricCard("Net sales", formatMoney(s.salesNet), Modifier.fillMaxWidth()) }; item { MetricCard("Cost of goods sold", formatMoney(s.cogsNet), Modifier.fillMaxWidth()) }; item { MetricCard("Gross profit", formatMoney(s.grossProfit), Modifier.fillMaxWidth()) }; item { MetricCard("Expenses", formatMoney(s.expensesNet), Modifier.fillMaxWidth()) }; item { MetricCard("Net trading profit", formatMoney(s.netProfit), Modifier.fillMaxWidth()) }
        item { HorizontalDivider(); Text("VAT analysis", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        item { ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { ReportLine("Output VAT on sales", s.outputVat); ReportLine("Recoverable purchase/expense VAT", s.inputVat); ReportLine("Reverse VAT output (notional)", s.reverseOutputVat); ReportLine("Reverse VAT input (notional)", -s.reverseInputVat); HorizontalDivider(); ReportLine(if (s.vatPosition >= 0) "VAT due to HMRC" else "VAT refund expected", kotlin.math.abs(s.vatPosition), true) } } }
        item { Button({ excelLauncher.launch("Business_Records_${period?.name?.replace(Regex("[^A-Za-z0-9._-]+"), "_") ?: "Accounting_Period"}.xlsx") }, Modifier.fillMaxWidth()) { Icon(Icons.Default.TableView, null); Text(" Export selected-period Excel") } }
    } }
}

@Composable private fun ReportLine(label: String, pence: Long, bold: Boolean = false) { Row(Modifier.fillMaxWidth()) { Text(label, Modifier.weight(1f), fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal); Text(formatMoney(pence), fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal) } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentsScreen(vm: AppViewModel, nav: NavHostController) {
    val business by vm.business.collectAsStateWithLifecycle()
    val period by vm.selectedAccountingPeriod.collectAsStateWithLifecycle()
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::exportDocuments) }
    val excelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { it?.let(vm::exportExcel) }
    ScreenScaffold("Documents & Dropbox", onBack = { nav.popBackStack() }) { inner -> Column(Modifier.padding(inner).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AccountingPeriodSelector(vm)
        ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("Bulk document export", fontWeight = FontWeight.SemiBold); Text("Copies all attached purchase invoices, generated sales invoices and expense receipts to a folder you choose.", style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(10.dp)); Button({ treeLauncher.launch(null) }) { Icon(Icons.Default.FolderOpen, null); Text(" Choose folder & export") } } }
        ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("VAT & accounting spreadsheet", fontWeight = FontWeight.SemiBold); Text("Downloads an up-to-date PUR, SALES, EXPENSES and PROFIT & VAT workbook for ${period?.name ?: "the selected accounting period"}. Fully cancelled and fully closed/refunded purchases are excluded from accounting figures and the purchase sheet.", style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(10.dp)); Button({ excelLauncher.launch("Business_Records_${period?.name?.replace(Regex("[^A-Za-z0-9._-]+"), "_") ?: "Accounting_Period"}.xlsx") }) { Icon(Icons.Default.Download, null); Text(" Download VAT spreadsheet") } } }
        ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("Dropbox auto-sync", fontWeight = FontWeight.SemiBold); Text(if (business.dropboxAutoSync) "Enabled • ${business.dropboxRoot}" else "Disabled — configure in Business Details", style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(10.dp)); OutlinedButton({ vm.syncNow() }, enabled = business.dropboxAutoSync) { Icon(Icons.Default.CloudSync, null); Text(" Sync now") } } }
        Text("Dropbox keeps recovery/inventory dumps globally, while accounting workbooks and individual purchase/sales/expense documents are segregated into their matching accounting-period folders.", style = MaterialTheme.typography.bodySmall)
    } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessScreen(vm: AppViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val b by vm.business.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var showDropboxTokenHelp by remember { mutableStateOf(false) }
    var name by remember(b) { mutableStateOf(b.businessName) }
    var address by remember(b) { mutableStateOf(b.address) }
    var vat by remember(b) { mutableStateOf(b.vatNumber) }
    var company by remember(b) { mutableStateOf(b.companyNumber) }
    var email by remember(b) { mutableStateOf(b.email) }
    var phone by remember(b) { mutableStateOf(b.phone) }
    var bank by remember(b) { mutableStateOf(b.bankDetails) }
    var terms by remember(b) { mutableStateOf(b.invoiceTerms) }
    var footer by remember(b) { mutableStateOf(b.invoiceFooter) }
    var autoInvoiceNumber by remember { mutableStateOf(InvoiceNumberPreferences.isAutoEnabled(context)) }
    var dbToken by remember(b) { mutableStateOf(b.dropboxAccessToken) }
    var dbKey by remember(b) { mutableStateOf(b.dropboxAppKey) }
    var dbRefresh by remember(b) { mutableStateOf(b.dropboxRefreshToken) }
    var dbRoot by remember(b) { mutableStateOf(b.dropboxRoot) }
    var auto by remember(b) { mutableStateOf(b.dropboxAutoSync) }

    ScreenScaffold("Business Details", onBack = { nav.popBackStack() }) { inner -> Column(Modifier.padding(inner).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FormField("Business name", name, { name = it }); FormField("Business address", address, { address = it }, singleLine = false); FormField("VAT number", vat, { vat = it }); FormField("Company number", company, { company = it }); FormField("Email", email, { email = it }); FormField("Phone", phone, { phone = it }); FormField("Bank/payment details for invoices", bank, { bank = it }, singleLine = false); FormField("Invoice terms", terms, { terms = it }); FormField("Invoice footer", footer, { footer = it }, singleLine = false)
        HorizontalDivider(); Text("Sales invoice numbering", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(autoInvoiceNumber, { autoInvoiceNumber = it }); Spacer(Modifier.width(10.dp)); Text("Automatic sales invoice number") }
        Text(if (autoInvoiceNumber) "On — invoice numbers are generated automatically from the customer invoice code and sale date." else "Off — the Sales modal will show an Invoice number field so you can enter your own unique invoice number.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        AccountingPeriodsManager(vm)
        HorizontalDivider(); Text("Dropbox", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text("The simplest setup is to paste a generated Dropbox access token for your own account.", style = MaterialTheme.typography.bodySmall)
        Text("Each purchase invoice, sales PDF, expense receipt and Excel workbook is automatically placed under its matching Accounting Periods/<period name>/ folder based on the transaction date.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { showDropboxTokenHelp = true }, contentPadding = PaddingValues(0.dp)) { Icon(Icons.Default.HelpOutline, null); Spacer(Modifier.width(6.dp)); Text("How do I get an access token?") }
        FormField("Dropbox access token", dbToken, { dbToken = it }); FormField("Dropbox App Key (optional)", dbKey, { dbKey = it }); FormField("Dropbox refresh token (optional)", dbRefresh, { dbRefresh = it }); FormField("Dropbox root folder", dbRoot, { dbRoot = it }); Row(verticalAlignment = Alignment.CenterVertically) { Switch(auto, { auto = it }); Spacer(Modifier.width(10.dp)); Text("Automatic Dropbox sync") }
        Button({ InvoiceNumberPreferences.setAutoEnabled(context, autoInvoiceNumber); vm.saveBusiness(b.copy(businessName = name, address = address, vatNumber = vat, companyNumber = company, email = email, phone = phone, bankDetails = bank, invoiceTerms = terms, invoiceFooter = footer, dropboxAccessToken = dbToken, dropboxAppKey = dbKey, dropboxRefreshToken = dbRefresh, dropboxRoot = dbRoot, dropboxAutoSync = auto)) { nav.popBackStack() } }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Save, null); Text(" Save business details") }; Spacer(Modifier.height(20.dp))
    } }

    if (showDropboxTokenHelp) {
        AlertDialog(
            onDismissRequest = { showDropboxTokenHelp = false },
            title = { Text("Get a Dropbox access token") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("This is the simple method for syncing this app to your own Dropbox account.")
                    Text("1. Open the Dropbox App Console and sign in.")
                    Text("2. Open your existing app. If you do not have one, choose Create app, use Scoped access and App folder access, then give the app a name.")
                    Text("3. Open the Permissions tab. Enable files.content.write and save/submit the permission change.")
                    Text("4. Open the Settings tab and scroll to the OAuth 2 section.")
                    Text("5. Under Generated access token, tap Generate.")
                    Text("6. Copy the generated token and paste it into Dropbox access token on this Business Details screen.")
                    Text("7. Turn on Automatic Dropbox sync and save your Business Details. You can then use Sync now in Documents & Dropbox.")
                    Text("Generated access tokens are intended for your own account/testing. If Dropbox later rejects or expires the token, generate a new one and replace the old token here.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { uriHandler.openUri("https://www.dropbox.com/developers/apps") }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.OpenInNew, null); Spacer(Modifier.width(6.dp)); Text("Open Dropbox App Console")
                    }
                }
            },
            confirmButton = { TextButton({ showDropboxTokenHelp = false }) { Text("Done") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(nav: NavHostController) {
    ScreenScaffold("More") { inner -> LazyColumn(Modifier.padding(inner), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { MoreRow(Icons.Default.People, "Customers", "Saved customer cards and invoice codes") { nav.navigate("customers") } }
        item { MoreRow(Icons.Default.Payments, "Expenses", "Expenses and receipt attachments") { nav.navigate("expenses") } }
        item { MoreRow(Icons.Default.Assessment, "Profit & VAT", "Profit, VAT due/refund and selected-period export") { nav.navigate("reports") } }
        item { MoreRow(Icons.Default.FolderCopy, "Documents & Dropbox", "Period-segregated invoices, VAT spreadsheet and cloud sync") { nav.navigate("documents") } }
        item { MoreRow(Icons.Default.Business, "Business Details", "Company data, invoice numbering, accounting periods and Dropbox") { nav.navigate("business") } }
    } }
}

@Composable
private fun MoreRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) { ElevatedCard(Modifier.fillMaxWidth(), onClick = onClick) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null); Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, style = MaterialTheme.typography.bodySmall) }; Icon(Icons.Default.ChevronRight, null) } } }
