package com.apingu.purchasesales.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apingu.purchasesales.data.AccountingPeriodEntity
import com.apingu.purchasesales.util.displayDate
import com.apingu.purchasesales.util.editDate
import com.apingu.purchasesales.util.epochDayToday
import com.apingu.purchasesales.util.parseDateOrToday
import java.time.LocalDate

@Composable
fun AccountingPeriodSelector(vm: AppViewModel, modifier: Modifier = Modifier) {
    val periods by vm.accountingPeriods.collectAsStateWithLifecycle()
    val selected by vm.selectedAccountingPeriod.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }

    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = periods.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(selected?.name ?: "No accounting period", fontWeight = FontWeight.SemiBold)
                selected?.let { Text("${displayDate(it.startEpochDay)} – ${displayDate(it.endEpochDay)}", style = MaterialTheme.typography.bodySmall) }
            }
            Icon(Icons.Default.ArrowDropDown, "Choose accounting period")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            periods.forEach { period ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(period.name, fontWeight = if (period.id == selected?.id) FontWeight.Bold else FontWeight.Normal)
                            Text("${displayDate(period.startEpochDay)} – ${displayDate(period.endEpochDay)}", style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    onClick = {
                        vm.selectAccountingPeriod(period)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun AccountingPeriodsManager(vm: AppViewModel) {
    val periods by vm.accountingPeriods.collectAsStateWithLifecycle()
    val selected by vm.selectedAccountingPeriod.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<AccountingPeriodEntity?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Accounting periods", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Dashboard, Purchases, Sales, Profit & VAT, Excel exports and Dropbox accounting folders use the selected period. Inventory remains all-time.",
            style = MaterialTheme.typography.bodySmall
        )
        AccountingPeriodSelector(vm)

        periods.forEach { period ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(period.name, fontWeight = FontWeight.SemiBold)
                        Text("${displayDate(period.startEpochDay)} – ${displayDate(period.endEpochDay)}", style = MaterialTheme.typography.bodySmall)
                        if (period.id == selected?.id) Text("Currently selected", style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton({ editing = period }) { Icon(Icons.Default.Edit, "Edit period") }
                    IconButton({ vm.deleteAccountingPeriod(period) }, enabled = periods.size > 1) { Icon(Icons.Default.DeleteOutline, "Delete period") }
                }
            }
        }

        OutlinedButton({ creating = true }, Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(6.dp))
            Text("Add accounting period")
        }
    }

    if (creating) {
        AccountingPeriodDialog(null, onDismiss = { creating = false }) {
            vm.saveAccountingPeriod(it) { creating = false }
        }
    }
    editing?.let { period ->
        AccountingPeriodDialog(period, onDismiss = { editing = null }) {
            vm.saveAccountingPeriod(it) { editing = null }
        }
    }
}

@Composable
private fun AccountingPeriodDialog(
    existing: AccountingPeriodEntity?,
    onDismiss: () -> Unit,
    onSave: (AccountingPeriodEntity) -> Unit
) {
    val today = LocalDate.ofEpochDay(epochDayToday())
    val defaultStart = today.withMonth(1).withDayOfMonth(1).toEpochDay()
    val defaultEnd = today.withMonth(12).withDayOfMonth(31).toEpochDay()
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var start by remember(existing?.id) { mutableStateOf(editDate(existing?.startEpochDay ?: defaultStart)) }
    var end by remember(existing?.id) { mutableStateOf(editDate(existing?.endEpochDay ?: defaultEnd)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add accounting period" else "Edit accounting period") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FormField("Period name", name, { name = it })
                FormField("Period start (DD/MM/YYYY)", start, { start = it })
                FormField("Period end (DD/MM/YYYY)", end, { end = it })
                Text("Periods cannot overlap. The transaction date determines which period a purchase or sale belongs to.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton({
                val startDay = parseDateOrToday(start)
                val endDay = parseDateOrToday(end)
                val generatedName = name.trim().ifBlank {
                    val s = LocalDate.ofEpochDay(startDay)
                    val e = LocalDate.ofEpochDay(endDay)
                    if (s.year == e.year) s.year.toString() else "${s.year}-${e.year}"
                }
                onSave(AccountingPeriodEntity(existing?.id ?: 0L, generatedName, startDay, endDay, existing?.createdAtMillis ?: System.currentTimeMillis()))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}
