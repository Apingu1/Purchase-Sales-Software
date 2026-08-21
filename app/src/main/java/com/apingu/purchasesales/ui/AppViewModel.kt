package com.apingu.purchasesales.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apingu.purchasesales.PurchaseSalesApplication
import com.apingu.purchasesales.data.*
import com.apingu.purchasesales.util.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

class AppViewModel(app: Application, private val repo: AppRepository) : AndroidViewModel(app) {
    private val context: Context get() = getApplication<Application>().applicationContext

    val business = repo.business.map { it ?: BusinessEntity() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BusinessEntity())
    val customers = repo.customers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val purchases = repo.purchases.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sales = repo.sales.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val saleLines = repo.saleLines.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val allocations = repo.allocations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val saleReturns = repo.saleReturns.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val returnAllocations = repo.returnAllocations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val expenses = repo.expenses.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val inventory = combine(purchases, allocations, returnAllocations) { p, a, r -> buildInventory(p, a, r) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private data class FinancePart(
        val p: List<PurchaseEntity>, val s: List<SaleEntity>, val l: List<SaleLineEntity>,
        val a: List<SaleAllocationEntity>, val r: List<SaleReturnEntity>
    )
    private data class FinancePart2(val f: FinancePart, val ra: List<SaleReturnAllocationEntity>, val e: List<ExpenseEntity>)

    private val financePart = combine(purchases, sales, saleLines, allocations, saleReturns) { p, s, l, a, r -> FinancePart(p, s, l, a, r) }
    private val financePart2 = combine(financePart, returnAllocations, expenses) { f, ra, e -> FinancePart2(f, ra, e) }
    val summary = combine(business, financePart2) { b, d ->
        val start = b.accountingStartEpochDay.takeIf { it > 0 } ?: epochDayToday()
        val end = b.accountingEndEpochDay.takeIf { it >= start } ?: start
        buildFinanceSummary(start, end, d.f.p, d.f.s, d.f.l, d.f.a, d.f.r, d.ra, d.e)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FinanceSummary())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun clearMessage() { _message.value = null }

    init { viewModelScope.launch { repo.ensureBusinessDefaults() } }

    fun saveBusiness(value: BusinessEntity, onSuccess: () -> Unit = {}) = action("Business details saved", onSuccess) { repo.saveBusiness(value) }
    fun saveCustomer(value: CustomerEntity, onSuccess: () -> Unit = {}) = action("Customer saved", onSuccess) { repo.addCustomer(value) }
    fun deleteCustomer(value: CustomerEntity) = action("Customer deleted") { repo.deleteCustomer(value) }
    fun savePurchase(value: PurchaseDraft, attachmentUri: Uri?, onSuccess: () -> Unit) = action("Purchase saved", onSuccess) { repo.savePurchase(value, attachmentUri) }
    fun markReceivedAll(value: PurchaseEntity) = action("Purchase received") { repo.markReceivedAll(value) }
    fun saveSale(value: SaleDraft, onSuccess: () -> Unit) = action("Invoice generated", onSuccess) { repo.saveSale(value) }
    fun recordReturn(lineId: Long, day: Long, qty: Int, restock: Boolean, notes: String, onSuccess: () -> Unit = {}) = action("Customer return recorded", onSuccess) { repo.recordCustomerReturn(lineId, day, qty, restock, notes) }
    fun saveExpense(value: ExpenseDraft, attachmentUri: Uri?, onSuccess: () -> Unit) = action("Expense saved", onSuccess) { repo.saveExpense(value, attachmentUri) }
    fun syncNow() = action("Dropbox sync queued") { repo.enqueueSync() }

    fun exportExcel(uri: Uri) = action("Excel workbook exported") {
        val data = repo.getFullData()
        val start = data.business.accountingStartEpochDay.takeIf { it > 0 } ?: epochDayToday()
        val end = data.business.accountingEndEpochDay.takeIf { it >= start } ?: start
        val summary = buildFinanceSummary(start, end, data.purchases, data.sales, data.saleLines, data.allocations, data.saleReturns, data.returnAllocations, data.expenses)
        val temp = File(context.cacheDir, "Business_Records.xlsx")
        XlsxExport.create(
            temp,
            data.purchases.filter { it.purchaseDateEpochDay in start..end },
            data.sales.filter { it.saleDateEpochDay in start..end },
            data.saleLines,
            data.saleReturns.filter { it.returnDateEpochDay in start..end },
            data.customers,
            data.expenses.filter { it.expenseDateEpochDay in start..end },
            summary
        )
        context.contentResolver.openOutputStream(uri).use { out -> requireNotNull(out); temp.inputStream().use { it.copyTo(out) } }
    }

    fun exportDocuments(treeUri: Uri) = action("Documents exported") {
        val d = repo.getFullData()
        val count = DocumentStore.exportAllDocuments(context, treeUri, d.purchases, d.sales, d.expenses)
        _message.value = "$count documents exported"
    }

    private fun action(success: String, onSuccess: () -> Unit = {}, block: suspend () -> Any?) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { _message.value = success; onSuccess() }
                .onFailure { _message.value = it.message ?: "Something went wrong" }
        }
    }

    companion object {
        fun factory(app: PurchaseSalesApplication): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(app, app.repository) as T
        }
    }
}
