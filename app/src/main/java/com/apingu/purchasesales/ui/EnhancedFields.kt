package com.apingu.purchasesales.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apingu.purchasesales.PurchaseSalesApplication
import com.apingu.purchasesales.util.editDate
import com.apingu.purchasesales.util.epochDayToday
import com.apingu.purchasesales.util.parseDateOrToday

private const val MILLIS_PER_DAY = 86_400_000L

val LocalSupplierSuggestions = compositionLocalOf<List<String>> { emptyList() }

@Composable
fun PurchaseSalesHost(app: PurchaseSalesApplication) {
    val purchaseOrders by app.repository.purchaseOrders.collectAsStateWithLifecycle(initialValue = emptyList())
    val expenses by app.repository.expenses.collectAsStateWithLifecycle(initialValue = emptyList())
    val suppliers = remember(purchaseOrders, expenses) {
        (purchaseOrders.map { it.supplier } + expenses.map { it.supplier })
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }
    }
    CompositionLocalProvider(LocalSupplierSuggestions provides suppliers) {
        /* Apply and consume the real system-bar safe area once. Nested Scaffolds then use the
           remaining space instead of each reserving the same status/navigation inset again. */
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .consumeWindowInsets(WindowInsets.systemBars)
        ) {
            PurchaseSalesRootV3()
        }
    }
}

/**
 * Exact three-argument overload used by the majority of text fields.
 * Date-labelled fields gain a calendar button while remaining fully editable.
 * Supplier/store fields gain case-insensitive partial-match history autocomplete.
 */
@Composable
fun FormField(label: String, value: String, onValueChange: (String) -> Unit) {
    when {
        isDateFieldLabel(label) -> DateFormField(label, value, onValueChange)
        isSupplierFieldLabel(label) -> SupplierHistoryField(label, value, onValueChange)
        else -> OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            minLines = 1
        )
    }
}

private fun isDateFieldLabel(label: String): Boolean {
    val normalized = label.lowercase()
    return normalized == "date" || normalized.contains(" date") || normalized.startsWith("date") ||
        normalized.contains("dd/mm/yyyy") || normalized.contains("period start") || normalized.contains("period end")
}

private fun isSupplierFieldLabel(label: String): Boolean {
    val normalized = label.lowercase()
    return normalized.contains("supplier") || normalized == "store"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateFormField(label: String, value: String, onValueChange: (String) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    val initialEpochDay = remember(value, showPicker) {
        if (value.isBlank()) epochDayToday() else parseDateOrToday(value)
    }
    val pickerState = androidx.compose.material3.rememberDatePickerState(
        initialSelectedDateMillis = initialEpochDay * MILLIS_PER_DAY
    )

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        trailingIcon = {
            IconButton(onClick = { showPicker = true }) {
                Icon(Icons.Default.CalendarMonth, contentDescription = "Choose date")
            }
        }
    )

    if (showPicker) {
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        onValueChange(editDate(millis / MILLIS_PER_DAY))
                    }
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun SupplierHistoryField(label: String, value: String, onValueChange: (String) -> Unit) {
    val history = LocalSupplierSuggestions.current
    var expanded by remember { mutableStateOf(false) }
    val matches = remember(value, history) {
        val query = value.trim()
        history.filter { supplier -> query.isBlank() || supplier.contains(query, ignoreCase = true) }.take(8)
    }

    Box {
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it); expanded = true },
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { expanded = matches.isNotEmpty() }) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Previous suppliers")
                }
            }
        )
        DropdownMenu(expanded = expanded && matches.isNotEmpty(), onDismissRequest = { expanded = false }) {
            matches.forEach { supplier ->
                DropdownMenuItem(text = { Text(supplier) }, onClick = { onValueChange(supplier); expanded = false })
            }
        }
    }
}
