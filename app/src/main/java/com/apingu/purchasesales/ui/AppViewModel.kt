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
    private val dao = (app as PurchaseSalesApplication).database.dao()

    val business = repo.business.map { it ?: BusinessEntity() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BusinessEntity())
    val accountingPeriods = repo.accountingPeriods.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val customers = repo.customers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val purchaseOrders = repo.purchaseOrders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val purchases = repo.purchases.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sales = repo.sales.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val saleLines = repo.saleLines.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val allocations = repo.allocations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val saleReturns = repo.saleReturns.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val returnAllocations = repo.returnAllocations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val expenses = repo.expenses.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedAccountingPeriod = combine(business, accountingPeriods) { b, periods ->
        periods.firstOrNull { it.id == b.selectedAccountingPeriodId }
            ?: periods.maxByOrNull { it.startEpochDay }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val inventory = combine(purchases, allocations, returnAllocations) { p, a, r -> buildInventory(p, a, r) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private data class FinancePart(
        val p: List<PurchaseEntity>, val s: List<SaleEntity>, val l: List<SaleLineEntity>,
        val a: List<SaleAllocationEntity>, val r: List<SaleReturnEntity>
    )
    private data class FinancePart2(val f: FinancePart, val ra: List<SaleReturnAllocationEntity>, val e: List<ExpenseEntity>)

    private val financePart = combine(purchases, sales, saleLines, allocations, saleReturns) { p, s, l, a, r -> FinancePart(p, s, l, a, r) }
    private val financePart2 = combine(financePart, returnAllocations, expenses) { f, ra, e -> FinancePart2(f, ra, e) }
    val summary = combine(selectedAccountingPeriod, financePart2) { period, d ->
        if (period == null) FinanceSummary()
        else buildFinanceSummary(period.startEpochDay, period.endEpochDay, d.f.p, d.f.s, d.f.l, d.f.a, d.f.r, d.ra, d.e)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FinanceSummary())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun clearMessage() { _message.value = null }

    init { viewModelScope.launch { repo.ensureBusinessDefaults() } }

    fun saveBusiness(value: BusinessEntity, onSuccess: () -> Unit = {}) = action("Business details saved", onSuccess) { repo.saveBusiness(value) }
    fun saveAccountingPeriod(value: AccountingPeriodEntity, onSuccess: () -> Unit = {}) = action("Accounting period saved", onSuccess) { repo.saveAccountingPeriod(value) }
    fun selectAccountingPeriod(value: AccountingPeriodEntity) = action("Accounting period changed") { repo.selectAccountingPeriod(value.id) }
    fun deleteAccountingPeriod(value: AccountingPeriodEntity) = action("Accounting period deleted") { repo.deleteAccountingPeriod(value) }

    fun saveCustomer(value: CustomerEntity, onSuccess: () -> Unit = {}) = action("Customer saved", onSuccess) { repo.addCustomer(value) }
    fun deleteCustomer(value: CustomerEntity) = action("Customer deleted") { repo.deleteCustomer(value) }

    fun savePurchaseOrder(
        value: PurchaseOrderDraft,
        attachmentUri: Uri?,
        itemNotes: List<String> = emptyList(),
        onSuccess: () -> Unit
    ) = action("Purchase saved", onSuccess) {
        val orderId = repo.savePurchaseOrder(value, attachmentUri)
        if (itemNotes.isNotEmpty()) persistPurchaseItemNotes(orderId, value.items, itemNotes)
        repo.enqueueSync()
        orderId
    }

    private suspend fun persistPurchaseItemNotes(orderId: Long, drafts: List<PurchaseItemDraft>, notes: List<String>) {
        val savedLines = dao.getPurchasesForOrder(orderId)
        val existingById = savedLines.associateBy { it.id }
        val requestedExistingIds = drafts.mapNotNull { it.id.takeIf { id -> id > 0 } }.toSet()
        val newLines = savedLines.filter { it.id !in requestedExistingIds }.sortedBy { it.id }.iterator()
        val now = System.currentTimeMillis()

        drafts.forEachIndexed { index, draft ->
            val target = if (draft.id > 0) existingById[draft.id] else if (newLines.hasNext()) newLines.next() else null
            if (target != null) {
                dao.updatePurchase(target.copy(notes = notes.getOrNull(index).orEmpty().trim(), updatedAtMillis = now))
            }
        }
    }

    fun markReceivedAllOrder(orderId: Long) = action("Purchase received") { repo.markReceivedAllOrder(orderId) }

    fun savePurchase(value: PurchaseDraft, attachmentUri: Uri?, onSuccess: () -> Unit) = action("Purchase saved", onSuccess) { repo.savePurchase(value, attachmentUri) }
    fun markReceivedAll(value: PurchaseEntity) = action("Purchase received") { repo.markReceivedAll(value) }

    fun saveSale(value: SaleDraft, onSuccess: () -> Unit) {
        viewModelScope.launch {
            runCatching { prepareImeiSelectionGroups(value) }
                .onSuccess { groups ->
                    val choiceGroups = groups.filter { it.requiredQuantity < it.totalAvailableQuantity }
                    if (choiceGroups.isEmpty()) {
                        val automatic = groups.associate { group ->
                            group.key to group.candidates.take(group.requiredQuantity)
                        }
                        completeSale(value, automatic, onSuccess)
                    } else {
                        ImeiSelectionCoordinator.show(
                            ImeiSelectionRequest(groups = choiceGroups) { selectedIds ->
                                val resolved = groups.associate { group ->
                                    val identifiers = if (group.requiredQuantity < group.totalAvailableQuantity) {
                                        selectedIds[group.key].orEmpty()
                                    } else {
                                        group.candidates.take(group.requiredQuantity).map { it.identifier }.toSet()
                                    }
                                    group.key to group.candidates.filter { it.identifier in identifiers }
                                }
                                completeSale(value, resolved, onSuccess)
                            }
                        )
                    }
                }
                .onFailure { _message.value = it.message ?: "Something went wrong" }
        }
    }

    private fun completeSale(
        value: SaleDraft,
        selectedByItem: Map<String, List<ImeiCandidate>>,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            runCatching {
                val oldLineIds = if (value.id > 0) dao.getSaleLinesForSale(value.id).map { it.id } else emptyList()
                val saleId = repo.saveSale(value)
                oldLineIds.forEach { ImeiAssignmentStore.clear(context, it) }
                applyImeiSelections(saleId, selectedByItem)
                refreshTraceableInvoice(saleId)
                repo.enqueueSync()
                saleId
            }.onSuccess {
                _message.value = "Invoice generated"
                onSuccess()
            }.onFailure {
                _message.value = it.message ?: "Something went wrong"
            }
        }
    }

    private suspend fun prepareImeiSelectionGroups(value: SaleDraft): List<ImeiSelectionGroup> {
        val allPurchases = dao.getPurchases()
        val allOrders = dao.getPurchaseOrders().associateBy { it.id }
        val allAllocations = dao.getSaleAllocations()
        val allReturnAllocations = dao.getSaleReturnAllocations()
        val allReturns = dao.getSaleReturns()
        val currentLines = if (value.id > 0) dao.getSaleLinesForSale(value.id) else emptyList()
        val currentLineIds = currentLines.map { it.id }.toSet()

        val returnedByAllocation = allReturnAllocations.groupBy { it.saleAllocationId }
            .mapValues { (_, rows) -> rows.sumOf { it.quantity } }
        val allocationsOutsideCurrent = allAllocations.filter { it.saleLineId !in currentLineIds }
        val netSoldByPurchase = allocationsOutsideCurrent.groupBy { it.purchaseId }.mapValues { (_, rows) ->
            rows.sumOf { allocation ->
                (allocation.quantity - (returnedByAllocation[allocation.id] ?: 0)).coerceAtLeast(0)
            }
        }

        val restockedByLine = allReturns.filter { it.restock && it.saleLineId !in currentLineIds }
            .groupBy { it.saleLineId }
            .mapValues { (_, rows) -> rows.sumOf { it.quantity } }

        val exactUsedIdentifiers = mutableSetOf<String>()
        dao.getSaleLines().filter { it.id !in currentLineIds }.forEach { line ->
            val assigned = ImeiAssignmentStore.get(context, line.id)
            val restocked = (restockedByLine[line.id] ?: 0).coerceAtMost(assigned.size)
            exactUsedIdentifiers += if (restocked > 0) assigned.dropLast(restocked) else assigned
        }

        val candidatesByItem = mutableMapOf<String, MutableList<ImeiCandidate>>()
        val availableByItem = mutableMapOf<String, Int>()

        allPurchases.forEach { purchase ->
            if (purchase.receivedQty <= 0) return@forEach
            val key = normalizeItemKey(purchase.item)
            val netSold = netSoldByPurchase[purchase.id] ?: 0
            val stockCapacity = (purchase.receivedQty - purchase.returnedQty - netSold).coerceAtLeast(0)
            if (stockCapacity <= 0) return@forEach
            availableByItem[key] = (availableByItem[key] ?: 0) + stockCapacity

            val orderNote = allOrders[purchase.purchaseOrderId]?.notes.orEmpty()
            val identifiers = trackedIdentifiers(purchase, orderNote)
            if (identifiers.isEmpty()) return@forEach

            val exactUsedHere = identifiers.filter { it in exactUsedIdentifiers }.toSet()
            val legacyConsumedCount = (netSold - exactUsedHere.size).coerceAtLeast(0)
            val legacyReserved = identifiers.filter { it !in exactUsedHere }.take(legacyConsumedCount).toSet()
            val availableIdentifiers = identifiers
                .filter { it !in exactUsedHere && it !in legacyReserved }
                .take(stockCapacity)

            availableIdentifiers.forEach { identifier ->
                candidatesByItem.getOrPut(key) { mutableListOf() } += ImeiCandidate(identifier, purchase.id)
            }
        }

        val requestedByItem = value.lines.groupBy { normalizeItemKey(it.item) }
            .mapValues { (_, rows) -> rows.sumOf { it.quantity.coerceAtLeast(0) } }

        val previousSelectionsByItem = mutableMapOf<String, MutableSet<String>>()
        currentLines.forEach { line ->
            previousSelectionsByItem.getOrPut(normalizeItemKey(line.item)) { mutableSetOf() }
                .addAll(ImeiAssignmentStore.get(context, line.id))
        }

        return requestedByItem.mapNotNull { (key, requested) ->
            if (requested <= 0) return@mapNotNull null
            val available = availableByItem[key] ?: 0
            val candidates = candidatesByItem[key].orEmpty().distinctBy { it.identifier }
            if (available <= 0 || candidates.size < requested) return@mapNotNull null
            val itemName = value.lines.firstOrNull { normalizeItemKey(it.item) == key }?.item?.trim().orEmpty()
            ImeiSelectionGroup(
                key = key,
                item = itemName,
                requiredQuantity = requested,
                totalAvailableQuantity = available,
                candidates = candidates,
                preselected = previousSelectionsByItem[key].orEmpty().filter { previous -> candidates.any { it.identifier == previous } }.toSet()
            )
        }
    }

    private suspend fun applyImeiSelections(
        saleId: Long,
        selectedByItem: Map<String, List<ImeiCandidate>>
    ) {
        if (selectedByItem.isEmpty()) return
        val lines = dao.getSaleLinesForSale(saleId)
        val purchaseMap = dao.getPurchases().associateBy { it.id }
        val existingAllocations = dao.getAllocationsForSale(saleId)
        val queues = selectedByItem.mapValues { (_, values) -> values.toMutableList() }.toMutableMap()
        val replacementLineIds = mutableSetOf<Long>()
        val replacementAllocations = mutableListOf<SaleAllocationEntity>()

        lines.forEach { line ->
            val key = normalizeItemKey(line.item)
            val queue = queues[key] ?: return@forEach
            if (queue.size < line.quantity) return@forEach

            val chosen = buildList {
                repeat(line.quantity) { add(queue.removeAt(0)) }
            }
            replacementLineIds += line.id
            ImeiAssignmentStore.put(context, line.id, chosen.map { it.identifier })

            chosen.groupBy { it.purchaseId }.forEach { (purchaseId, selected) ->
                val purchase = purchaseMap[purchaseId] ?: return@forEach
                val effectiveNet = (purchase.netPence - if (purchase.partialRefund) purchase.refundNetPence else 0).coerceAtLeast(0)
                val unitNetCost = if (purchase.quantity > 0) effectiveNet / purchase.quantity else 0
                replacementAllocations += SaleAllocationEntity(
                    saleLineId = line.id,
                    purchaseId = purchaseId,
                    quantity = selected.size,
                    unitNetCostPence = unitNetCost
                )
            }
        }

        if (replacementLineIds.isEmpty()) return
        val preserved = existingAllocations.filter { it.saleLineId !in replacementLineIds }
        dao.deleteAllocationsForSale(saleId)
        preserved.forEach { dao.insertSaleAllocation(it.copy(id = 0)) }
        replacementAllocations.forEach { dao.insertSaleAllocation(it) }
    }

    private fun normalizeItemKey(value: String): String = value.trim().lowercase()

    fun deleteSale(value: SaleEntity, onSuccess: () -> Unit = {}) = action("Sales invoice deleted", onSuccess) {
        val lineIds = dao.getSaleLinesForSale(value.id).map { it.id }
        repo.deleteSale(value.id)
        lineIds.forEach { ImeiAssignmentStore.clear(context, it) }
    }

    fun exportSaleInvoice(saleId: Long, uri: Uri) = action("Sales invoice downloaded") {
        var sale = dao.getSale(saleId) ?: error("Sales invoice not found")
        var source = sale.pdfPath?.let(::File)
        if (source == null || !source.exists()) {
            refreshTraceableInvoice(saleId)
            sale = dao.getSale(saleId) ?: error("Sales invoice not found")
            source = sale.pdfPath?.let(::File)
        }
        val sourceFile = source?.takeIf { it.exists() } ?: error("Sales invoice PDF is not available")
        context.contentResolver.openOutputStream(uri).use { out ->
            requireNotNull(out) { "Unable to open download location" }
            sourceFile.inputStream().use { it.copyTo(out) }
        }
    }

    private suspend fun refreshTraceableInvoice(saleId: Long) {
        val sale = dao.getSale(saleId) ?: return
        val customer = dao.getCustomer(sale.customerId) ?: return
        val business = dao.getBusiness() ?: BusinessEntity()
        val lines = dao.getSaleLinesForSale(saleId)
        val lineIds = lines.map { it.id }.toSet()
        val allocations = dao.getAllocationsForSale(saleId).filter { it.saleLineId in lineIds }
        val purchaseMap = dao.getPurchases().associateBy { it.id }
        val orderMap = dao.getPurchaseOrders().associateBy { it.id }

        val preciseNotes = lines.mapNotNull { line ->
            val assigned = ImeiAssignmentStore.get(context, line.id)
            if (assigned.isEmpty()) null else line.id to assigned.joinToString("\n")
        }.toMap()

        val legacyNotes = allocations.groupBy { it.saleLineId }.mapValues { (_, lineAllocations) ->
            lineAllocations.mapNotNull { allocation ->
                val purchase = purchaseMap[allocation.purchaseId] ?: return@mapNotNull null
                val note = purchase.notes.trim()
                val legacyOrderNote = orderMap[purchase.purchaseOrderId]?.notes?.trim().orEmpty()
                note.takeIf { it.isNotBlank() && it != legacyOrderNote }
            }.distinct().joinToString("\n")
        }.filterValues { it.isNotBlank() }

        val sourceNotes = legacyNotes + preciseNotes
        val pdfPath = TraceableInvoicePdf.create(context, business, customer, sale, lines, sourceNotes)
        dao.updateSale(sale.copy(pdfPath = pdfPath, updatedAtMillis = System.currentTimeMillis()))
    }

    fun recordReturn(lineId: Long, day: Long, qty: Int, restock: Boolean, notes: String, onSuccess: () -> Unit = {}) = action("Customer return recorded", onSuccess) { repo.recordCustomerReturn(lineId, day, qty, restock, notes) }
    fun saveExpense(value: ExpenseDraft, attachmentUri: Uri?, onSuccess: () -> Unit) = action("Expense saved", onSuccess) { repo.saveExpense(value, attachmentUri) }
    fun syncNow() = action("Dropbox sync queued") { repo.enqueueSync() }

    fun exportExcel(uri: Uri) = action("Excel workbook exported") {
        val period = selectedAccountingPeriod.value ?: error("Set an accounting period first")
        val data = repo.getFullData()
        val summary = buildFinanceSummary(period.startEpochDay, period.endEpochDay, data.purchases, data.sales, data.saleLines, data.allocations, data.saleReturns, data.returnAllocations, data.expenses)
        val temp = File(context.cacheDir, "Business_Records_${safePeriodFileName(period.name)}.xlsx")
        XlsxExport.create(
            temp,
            data.purchases.filter { it.purchaseDateEpochDay in period.startEpochDay..period.endEpochDay && !isVoidedPurchaseForAccounting(it) },
            data.sales.filter { it.saleDateEpochDay in period.startEpochDay..period.endEpochDay },
            data.saleLines,
            data.saleReturns.filter { it.returnDateEpochDay in period.startEpochDay..period.endEpochDay },
            data.customers,
            data.expenses.filter { it.expenseDateEpochDay in period.startEpochDay..period.endEpochDay },
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

    private fun safePeriodFileName(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "Accounting_Period" }

    companion object {
        fun factory(app: PurchaseSalesApplication): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(app, app.repository) as T
        }
    }
}
