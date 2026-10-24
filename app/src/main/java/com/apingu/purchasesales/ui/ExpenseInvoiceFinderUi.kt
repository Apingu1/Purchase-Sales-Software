package com.apingu.purchasesales.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.apingu.purchasesales.data.*
import com.apingu.purchasesales.util.*
import java.io.File

/** Expense documents share the same mailbox scan, staged review and multiple-invoice workflow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseInvoiceFinderScreen(vm: AppViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val expenses by vm.expenses.collectAsStateWithLifecycle()
    val invoices by vm.expenseInvoices.collectAsStateWithLifecycle()
    val candidates by vm.pendingExpenseInvoiceCandidates.collectAsStateWithLifecycle()
    val running by vm.finderRunning.collectAsStateWithLifecycle()
    val progress by vm.finderProgress.collectAsStateWithLifecycle()
    val accounts by vm.emailAccounts.collectAsStateWithLifecycle()
    val period by vm.selectedAccountingPeriod.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<Long?>(null) }
    var preview by remember { mutableStateOf<Pair<String, String>?>(null) }
    var reject by remember { mutableStateOf<ExpenseInvoiceCandidateEntity?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val expenseId = target
        if (expenseId != null && uris.isNotEmpty()) vm.addExpenseInvoices(expenseId, uris)
        target = null
    }
    fun addInvoice(expenseId: Long) {
        target = expenseId
        picker.launch(arrayOf("application/pdf", "image/png", "image/jpeg"))
    }
    val periodExpenses = expenses.filter {
        period == null || it.expenseDateEpochDay in period!!.startEpochDay..period!!.endEpochDay
    }
    val group = invoices.groupBy { it.expenseId }
    val eligibleIds = periodExpenses.map { it.id }.toSet()
    val pending = candidates.filter { it.expenseId in eligibleIds &&
        group[it.expenseId].isNullOrEmpty() }
    val pendingIds = pending.map { it.expenseId }.toSet()
    val approved = periodExpenses.filter { !group[it.id].isNullOrEmpty() }
    val waived = periodExpenses.filter { group[it.id].isNullOrEmpty() &&
        it.invoiceWaived && it.id !in pendingIds }
    val missing = periodExpenses.filter { group[it.id].isNullOrEmpty() &&
        !it.invoiceWaived && it.id !in pendingIds }
    fun matches(expense: ExpenseEntity): Boolean = query.isBlank() ||
        expense.supplier.contains(query, true) ||
        expense.details.contains(query, true) ||
        expense.orderNumber.contains(query, true) ||
        expense.accountUsername.contains(query, true)

    Scaffold(
        topBar = { TopAppBar(title = { Text("Expense Invoice Finder") },
            navigationIcon = { IconButton({ nav.popBackStack() }) {
                Icon(Icons.Default.ArrowBack, "Back to purchase invoices")
            } }) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            ElevatedCard(Modifier.fillMaxWidth().padding(12.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Expense invoices & receipts", fontWeight = FontWeight.SemiBold)
                    Text("Pending " + pending.size + " · Approved " + approved.size +
                        " · Missing " + missing.size + " · Waived " + waived.size,
                        style = MaterialTheme.typography.bodySmall)
                    Button({ vm.runInvoiceFinder() }, modifier = Modifier.fillMaxWidth(),
                        enabled = !running && accounts.any {
                            it.isEnabled && it.connectionState == "CONNECTED"
                        }) {
                        Icon(Icons.Default.FindInPage, null)
                        Text(if (running) " Searching..." else " Run Invoice Finder")
                    }
                    if (progress.isNotBlank()) Text(progress, style = MaterialTheme.typography.bodySmall)
                    Text("Email matching uses each expense's reference and exact connected mailbox. All matches require approval.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Pending Review (" + pending.size + ")") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Approved (" + approved.size + ")") })
                Tab(tab == 2, { tab = 2 }, text = { Text("Missing (" + missing.size + ")") })
                Tab(tab == 3, { tab = 3 }, text = { Text("Waived (" + waived.size + ")") })
            }
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().padding(12.dp),
                label = { Text("Search expenses") }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) })
            when (tab) {
                0 -> {
                    val rows = pending.filter { candidate ->
                        val expense = expenses.firstOrNull { it.id == candidate.expenseId }
                        expense?.let(::matches) == true ||
                            candidate.originalFileName.contains(query, true)
                    }
                    if (rows.isEmpty()) ExpenseFinderEmpty("No expenses awaiting invoice approval")
                    else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(rows, key = { it.id }) { candidate ->
                            val expense = expenses.firstOrNull { it.id == candidate.expenseId }
                            val cache = InvoiceCandidateCache.previewFile(context, -candidate.id)
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    Text(expense?.supplier ?: "Expense unavailable",
                                        fontWeight = FontWeight.SemiBold)
                                    expense?.let { Text(displayDate(it.expenseDateEpochDay) + " · " +
                                        it.orderNumber.ifBlank { "No reference" },
                                        style = MaterialTheme.typography.bodySmall) }
                                    Text(candidate.originalFileName)
                                    Text("Mailbox: " + candidate.accountEmail,
                                        style = MaterialTheme.typography.bodySmall)
                                    if (cache != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton({ preview = cache.absolutePath to candidate.originalFileName }) {
                                            Text("Preview")
                                        }
                                        Button({ vm.approveExpenseInvoice(candidate.id) }) { Text("Approve") }
                                    } else Text("Attachment preview unavailable; run Finder again.",
                                        style = MaterialTheme.typography.bodySmall)
                                    OutlinedButton({ reject = candidate }) { Text("Reject suggestion") }
                                }
                            }
                        }
                    }
                }
                else -> {
                    val list = when(tab) {
                        1 -> approved
                        2 -> missing
                        else -> waived
                    }.filter(::matches)
                    if (list.isEmpty()) ExpenseFinderEmpty("No expenses in this category")
                    else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(list, key = { it.id }) { expense ->
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(expense.supplier, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                        Text(formatMoney(expense.grossPence), fontWeight = FontWeight.Bold)
                                    }
                                    Text(displayDate(expense.expenseDateEpochDay) + " · " + expense.details +
                                        " · " + expense.orderNumber.ifBlank { "No reference" },
                                        style = MaterialTheme.typography.bodySmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton({ nav.navigate("expense/" + expense.id) }) { Text("Open expense") }
                                        Button({ addInvoice(expense.id) }) { Text("Add invoice") }
                                    }
                                    if (tab == 2) OutlinedButton({
                                        vm.waiveExpenseInvoice(expense.id, true)
                                    }) { Text("Waive") }
                                    if (tab == 3) OutlinedButton({
                                        vm.waiveExpenseInvoice(expense.id, false)
                                    }) { Text("Undo waiver") }
                                    if (tab == 1) group[expense.id].orEmpty().forEach { doc ->
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text(doc.originalFileName.ifBlank { File(doc.filePath).name },
                                                modifier = Modifier.weight(1f),
                                                style = MaterialTheme.typography.bodySmall)
                                            TextButton({ preview = doc.filePath to doc.originalFileName.ifBlank { File(doc.filePath).name } }) {
                                                Text("View")
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
    }
    preview?.let { (path, name) ->
        InvoiceFilePreview(path, name) { preview = null }
    }
    reject?.let { candidate ->
        AlertDialog(onDismissRequest = { reject = null },
            title = { Text("Reject expense invoice suggestion?") },
            text = { Text("The source email is unchanged. A waived expense remains waived.") },
            confirmButton = { TextButton({
                vm.rejectExpenseInvoice(candidate.id); reject = null
            }) { Text("Reject") } },
            dismissButton = { TextButton({ reject = null }) { Text("Cancel") } })
    }
}

@Composable
private fun ExpenseFinderEmpty(message: String) {
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}
