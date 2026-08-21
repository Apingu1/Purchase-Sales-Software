package com.apingu.purchasesales.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.apingu.purchasesales.sync.DropboxSyncWorker
import com.apingu.purchasesales.util.*
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate


data class PurchaseDraft(
    val id: Long = 0,
    val dateEpochDay: Long,
    val supplier: String,
    val item: String,
    val quantity: Int,
    val orderNumber: String,
    val accountUsername: String,
    val grossPence: Long,
    val vatType: String,
    val paymentMethod: String,
    val receivedQty: Int,
    val cancelledQty: Int,
    val returnedQty: Int,
    val refundExpectedPence: Long,
    val refundReceivedPence: Long,
    val notes: String,
    val existingInvoicePath: String? = null
)

data class SaleLineDraft(val item: String, val quantity: Int, val unitGrossPence: Long)
data class SaleDraft(val id: Long = 0, val dateEpochDay: Long, val customerId: Long, val vatType: String, val notes: String, val lines: List<SaleLineDraft>)

data class ExpenseDraft(
    val id: Long = 0,
    val dateEpochDay: Long,
    val supplier: String,
    val details: String,
    val account: String,
    val grossPence: Long,
    val vatType: String,
    val paymentMethod: String,
    val comments: String,
    val existingAttachmentPath: String? = null
)

data class FullData(
    val business: BusinessEntity,
    val customers: List<CustomerEntity>,
    val purchases: List<PurchaseEntity>,
    val sales: List<SaleEntity>,
    val saleLines: List<SaleLineEntity>,
    val allocations: List<SaleAllocationEntity>,
    val saleReturns: List<SaleReturnEntity>,
    val returnAllocations: List<SaleReturnAllocationEntity>,
    val expenses: List<ExpenseEntity>
)

class AppRepository(private val context: Context, private val db: AppDatabase) {
    private val dao = db.dao()

    val business: Flow<BusinessEntity?> = dao.observeBusiness()
    val customers = dao.observeCustomers()
    val purchases = dao.observePurchases()
    val sales = dao.observeSales()
    val saleLines = dao.observeSaleLines()
    val allocations = dao.observeSaleAllocations()
    val saleReturns = dao.observeSaleReturns()
    val returnAllocations = dao.observeSaleReturnAllocations()
    val expenses = dao.observeExpenses()

    suspend fun ensureBusinessDefaults() {
        if (dao.getBusiness() == null) {
            val today = LocalDate.now()
            dao.saveBusiness(
                BusinessEntity(
                    accountingStartEpochDay = today.withMonth(1).withDayOfMonth(1).toEpochDay(),
                    accountingEndEpochDay = today.withMonth(12).withDayOfMonth(31).toEpochDay()
                )
            )
        }
    }

    suspend fun saveBusiness(value: BusinessEntity) { dao.saveBusiness(value); enqueueSync() }
    suspend fun addCustomer(value: CustomerEntity): Long {
        require(value.companyName.isNotBlank()) { "Customer name is required" }
        require(value.invoiceCode.trim().length in 2..5) { "Customer invoice code must be 2–5 characters" }
        val normalized = value.copy(invoiceCode = value.invoiceCode.trim().uppercase())
        return if (value.id == 0L) dao.insertCustomer(normalized) else { dao.updateCustomer(normalized); value.id }
    }
    suspend fun deleteCustomer(value: CustomerEntity) = dao.deleteCustomer(value)

    suspend fun savePurchase(draft: PurchaseDraft, attachmentUri: Uri? = null): Long {
        require(draft.supplier.isNotBlank()) { "Supplier/store is required" }
        require(draft.item.isNotBlank()) { "Item is required" }
        require(draft.quantity > 0) { "Quantity must be greater than zero" }
        require(draft.receivedQty >= 0 && draft.cancelledQty >= 0 && draft.returnedQty >= 0) { "Quantities cannot be negative" }
        require(draft.receivedQty <= draft.quantity) { "Received quantity cannot exceed purchased quantity" }
        require(draft.cancelledQty <= draft.quantity - draft.receivedQty) { "Cancelled quantity exceeds the outstanding quantity" }
        require(draft.returnedQty <= draft.receivedQty) { "Returned quantity cannot exceed received quantity" }
        if (draft.id > 0) {
            val allocations = dao.getSaleAllocations().filter { it.purchaseId == draft.id }
            val allocationIds = allocations.map { it.id }.toSet()
            val restored = dao.getSaleReturnAllocations().filter { it.saleAllocationId in allocationIds }.sumOf { it.quantity }
            val sold = allocations.sumOf { it.quantity } - restored
            require(draft.returnedQty <= draft.receivedQty - sold) { "Supplier return exceeds stock still available from this purchase" }
        }
        val vat = breakdownFromGross(draft.grossPence, draft.vatType)
        val invoicePath = attachmentUri?.let { DocumentStore.copyIntoApp(context, it, "PUR_${draft.id.takeIf { n -> n > 0 } ?: "NEW"}") } ?: draft.existingInvoicePath
        val status = derivePurchaseStatus(draft)
        val entity = PurchaseEntity(
            id = draft.id,
            purchaseDateEpochDay = draft.dateEpochDay,
            supplier = draft.supplier.trim(),
            item = draft.item.trim(),
            quantity = draft.quantity,
            orderNumber = draft.orderNumber.trim(),
            accountUsername = draft.accountUsername.trim(),
            grossPence = vat.grossPence,
            netPence = vat.netPence,
            vatPence = vat.vatPence,
            reverseVatPence = vat.reverseVatPence,
            vatType = draft.vatType,
            paymentMethod = draft.paymentMethod.trim(),
            status = status,
            receivedQty = draft.receivedQty,
            cancelledQty = draft.cancelledQty,
            returnedQty = draft.returnedQty,
            refundExpectedPence = draft.refundExpectedPence,
            refundReceivedPence = draft.refundReceivedPence,
            invoicePath = invoicePath,
            notes = draft.notes.trim(),
            updatedAtMillis = System.currentTimeMillis()
        )
        val id = if (draft.id == 0L) dao.insertPurchase(entity) else { dao.updatePurchase(entity); draft.id }
        enqueueSync()
        return id
    }

    private fun derivePurchaseStatus(d: PurchaseDraft): String = when {
        d.refundExpectedPence > 0 && d.refundReceivedPence >= d.refundExpectedPence -> "REFUND_RECEIVED"
        d.refundExpectedPence > d.refundReceivedPence -> "REFUND_PENDING"
        d.returnedQty > 0 && d.returnedQty >= d.receivedQty && d.receivedQty > 0 -> "RETURNED"
        d.cancelledQty >= d.quantity -> "CANCELLED"
        d.receivedQty <= 0 -> "RECEIPT_PENDING"
        d.receivedQty + d.cancelledQty < d.quantity -> "PARTIALLY_RECEIVED"
        else -> "RECEIVED"
    }

    suspend fun markReceivedAll(purchase: PurchaseEntity) {
        val received = (purchase.quantity - purchase.cancelledQty).coerceAtLeast(0)
        savePurchase(
            PurchaseDraft(
                purchase.id, purchase.purchaseDateEpochDay, purchase.supplier, purchase.item, purchase.quantity,
                purchase.orderNumber, purchase.accountUsername, purchase.grossPence, purchase.vatType, purchase.paymentMethod,
                received, purchase.cancelledQty, purchase.returnedQty.coerceAtMost(received), purchase.refundExpectedPence,
                purchase.refundReceivedPence, purchase.notes, purchase.invoicePath
            )
        )
    }

    suspend fun saveExpense(draft: ExpenseDraft, attachmentUri: Uri? = null): Long {
        require(draft.supplier.isNotBlank()) { "Store/supplier is required" }
        require(draft.details.isNotBlank()) { "Expense details are required" }
        val vat = breakdownFromGross(draft.grossPence, draft.vatType)
        val path = attachmentUri?.let { DocumentStore.copyIntoApp(context, it, "EXP_${draft.id.takeIf { n -> n > 0 } ?: "NEW"}") } ?: draft.existingAttachmentPath
        val value = ExpenseEntity(
            id = draft.id,
            expenseDateEpochDay = draft.dateEpochDay,
            supplier = draft.supplier.trim(),
            details = draft.details.trim(),
            account = draft.account.trim(),
            grossPence = vat.grossPence,
            netPence = vat.netPence,
            vatPence = vat.vatPence,
            reverseVatPence = vat.reverseVatPence,
            vatType = draft.vatType,
            paymentMethod = draft.paymentMethod.trim(),
            attachmentPath = path,
            comments = draft.comments.trim(),
            updatedAtMillis = System.currentTimeMillis()
        )
        val id = if (draft.id == 0L) dao.insertExpense(value) else { dao.updateExpense(value); draft.id }
        enqueueSync()
        return id
    }

    suspend fun saveSale(draft: SaleDraft): Long = db.withTransaction {
        require(draft.customerId > 0) { "Select a customer" }
        require(draft.lines.isNotEmpty()) { "Add at least one item" }
        draft.lines.forEach { require(it.item.isNotBlank() && it.quantity > 0 && it.unitGrossPence >= 0) { "Each sale line needs an item, quantity and price" } }
        if (draft.id > 0 && dao.countReturnsForSale(draft.id) > 0) error("This invoice has a customer return. Create a correcting/refund transaction rather than changing its stock lines.")

        val customer = dao.getCustomer(draft.customerId) ?: error("Customer not found")
        val existing = if (draft.id > 0) dao.getSale(draft.id) else null
        if (existing != null) {
            dao.deleteAllocationsForSale(existing.id)
            dao.deleteSaleLinesForSale(existing.id)
        }

        val lineBreakdowns = draft.lines.map { line ->
            val gross = line.unitGrossPence * line.quantity
            line to breakdownFromGross(gross, draft.vatType)
        }
        val totalNet = lineBreakdowns.sumOf { it.second.netPence }
        val totalVat = lineBreakdowns.sumOf { it.second.vatPence }
        val totalGross = lineBreakdowns.sumOf { it.second.grossPence }
        val totalReverse = lineBreakdowns.sumOf { it.second.reverseVatPence }
        val invoiceNo = if (existing != null && existing.customerId == draft.customerId && existing.saleDateEpochDay == draft.dateEpochDay) {
            existing.invoiceNo
        } else generateInvoiceNumber(customer, draft.dateEpochDay)
        var sale = SaleEntity(
            id = draft.id,
            invoiceNo = invoiceNo,
            saleDateEpochDay = draft.dateEpochDay,
            customerId = draft.customerId,
            vatType = draft.vatType,
            netPence = totalNet,
            vatPence = totalVat,
            grossPence = totalGross,
            reverseVatPence = totalReverse,
            notes = draft.notes.trim(),
            pdfPath = existing?.pdfPath,
            updatedAtMillis = System.currentTimeMillis()
        )
        val saleId = if (existing == null) dao.insertSale(sale) else { dao.updateSale(sale); sale.id }
        sale = sale.copy(id = saleId)

        val newLines = mutableListOf<SaleLineEntity>()
        lineBreakdowns.forEach { (line, breakdown) ->
            val lineEntity = SaleLineEntity(
                saleId = saleId,
                item = line.item.trim(),
                quantity = line.quantity,
                unitGrossPence = line.unitGrossPence,
                lineGrossPence = breakdown.grossPence,
                lineNetPence = breakdown.netPence,
                lineVatPence = breakdown.vatPence
            )
            val lineId = dao.insertSaleLine(lineEntity)
            val savedLine = lineEntity.copy(id = lineId)
            newLines += savedLine
            allocateStock(savedLine)
        }
        val business = dao.getBusiness() ?: BusinessEntity()
        val pdfPath = InvoicePdf.create(context, business, customer, sale, newLines)
        dao.updateSale(sale.copy(pdfPath = pdfPath))
        enqueueSync()
        saleId
    }

    private suspend fun generateInvoiceNumber(customer: CustomerEntity, day: Long): String {
        val count = dao.countCustomerSalesOnDay(customer.id, day) + 1
        return "${customer.invoiceCode.uppercase()}-${compactDate(day)}-${count.toString().padStart(2, '0')}"
    }

    private suspend fun allocateStock(line: SaleLineEntity) {
        val purchases = dao.getPurchases().filter { it.item.trim().equals(line.item.trim(), ignoreCase = true) && it.receivedQty > 0 }
        val allocations = dao.getSaleAllocations()
        val returnAllocations = dao.getSaleReturnAllocations()
        val allocationById = allocations.associateBy { it.id }
        val restoredByPurchase = mutableMapOf<Long, Int>()
        returnAllocations.forEach { ra ->
            val pId = allocationById[ra.saleAllocationId]?.purchaseId ?: return@forEach
            restoredByPurchase[pId] = (restoredByPurchase[pId] ?: 0) + ra.quantity
        }
        val soldByPurchase = allocations.groupBy { it.purchaseId }.mapValues { e -> e.value.sumOf { it.quantity } }
        var remaining = line.quantity
        purchases.sortedWith(compareBy<PurchaseEntity> { it.purchaseDateEpochDay }.thenBy { it.id }).forEach { p ->
            if (remaining <= 0) return@forEach
            val available = p.receivedQty - p.returnedQty - (soldByPurchase[p.id] ?: 0) + (restoredByPurchase[p.id] ?: 0)
            if (available <= 0) return@forEach
            val take = minOf(remaining, available)
            val unitNetCost = if (p.quantity > 0) p.netPence / p.quantity else 0
            dao.insertSaleAllocation(SaleAllocationEntity(saleLineId = line.id, purchaseId = p.id, quantity = take, unitNetCostPence = unitNetCost))
            remaining -= take
        }
        require(remaining == 0) { "Not enough received inventory for ${line.item}. Short by $remaining." }
    }

    suspend fun recordCustomerReturn(saleLineId: Long, day: Long, quantity: Int, restock: Boolean, notes: String) = db.withTransaction {
        val lines = dao.getSaleLines()
        val line = lines.firstOrNull { it.id == saleLineId } ?: error("Sale line not found")
        val sale = dao.getSale(line.saleId) ?: error("Sale not found")
        val existingReturns = dao.getSaleReturns().filter { it.saleLineId == saleLineId }.sumOf { it.quantity }
        require(quantity > 0 && existingReturns + quantity <= line.quantity) { "Return quantity exceeds quantity sold" }
        val gross = line.unitGrossPence * quantity
        val breakdown = breakdownFromGross(gross, sale.vatType)
        val returnId = dao.insertSaleReturn(
            SaleReturnEntity(
                saleLineId = saleLineId, returnDateEpochDay = day, quantity = quantity,
                refundGrossPence = breakdown.grossPence, refundNetPence = breakdown.netPence,
                refundVatPence = breakdown.vatPence, restock = restock, notes = notes.trim()
            )
        )
        if (restock) {
            val allocations = dao.getSaleAllocations().filter { it.saleLineId == saleLineId }.sortedByDescending { it.id }
            val previousReturnAllocations = dao.getSaleReturnAllocations()
            val alreadyRestored = previousReturnAllocations.groupBy { it.saleAllocationId }.mapValues { it.value.sumOf { r -> r.quantity } }
            var remaining = quantity
            allocations.forEach { a ->
                if (remaining <= 0) return@forEach
                val availableToRestore = a.quantity - (alreadyRestored[a.id] ?: 0)
                if (availableToRestore <= 0) return@forEach
                val take = minOf(remaining, availableToRestore)
                dao.insertSaleReturnAllocation(SaleReturnAllocationEntity(saleReturnId = returnId, saleAllocationId = a.id, quantity = take))
                remaining -= take
            }
        }
        enqueueSync()
    }

    suspend fun getFullData(): FullData = FullData(
        business = dao.getBusiness() ?: BusinessEntity(),
        customers = dao.getCustomers(),
        purchases = dao.getPurchases(),
        sales = dao.getSales(),
        saleLines = dao.getSaleLines(),
        allocations = dao.getSaleAllocations(),
        saleReturns = dao.getSaleReturns(),
        returnAllocations = dao.getSaleReturnAllocations(),
        expenses = dao.getExpenses()
    )

    fun enqueueSync() = DropboxSyncWorker.enqueue(context)
}
