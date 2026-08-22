package com.apingu.purchasesales.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.apingu.purchasesales.sync.DropboxSyncWorker
import com.apingu.purchasesales.util.*
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** Legacy single-line draft retained so the original V1 screen remains source-compatible. */
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
    val existingInvoicePath: String? = null,
    val partialRefund: Boolean = false,
    val refundNetPence: Long = 0,
    val refundVatPence: Long = 0
)

data class PurchaseItemDraft(
    val id: Long = 0,
    val item: String,
    val quantity: Int,
    val grossPence: Long,
    val receivedQty: Int = 0,
    val cancelledQty: Int = 0,
    val returnedQty: Int = 0,
    val refundExpectedPence: Long = 0,
    val refundReceivedPence: Long = 0,
    val partialRefund: Boolean = false,
    val refundNetPence: Long = 0,
    val refundVatPence: Long = 0
)

data class PurchaseOrderDraft(
    val id: Long = 0,
    val dateEpochDay: Long,
    val supplier: String,
    val orderNumber: String,
    val accountUsername: String,
    val vatType: String,
    val paymentMethod: String,
    val notes: String,
    val items: List<PurchaseItemDraft>,
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
    val purchaseOrders: List<PurchaseOrderEntity>,
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
    val purchaseOrders = dao.observePurchaseOrders()
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

    /**
     * Saves one purchase/order header and all of its item lines atomically.
     * Each PurchaseEntity remains an independent inventory lot so different items in the same
     * supplier order can be received/cancelled/returned independently and keep their own cost.
     * A partial monetary refund is independent of quantity returns and can have an explicit net/VAT split.
     */
    suspend fun savePurchaseOrder(draft: PurchaseOrderDraft, attachmentUri: Uri? = null): Long {
        require(draft.supplier.isNotBlank()) { "Supplier/store is required" }
        require(draft.items.isNotEmpty()) { "Add at least one item" }
        draft.items.forEach(::validatePurchaseItem)

        val copiedInvoice = attachmentUri?.let {
            DocumentStore.copyIntoApp(context, it, "PUR_ORDER_${draft.id.takeIf { n -> n > 0 } ?: "NEW"}")
        }

        val orderId = db.withTransaction {
            val existingOrder = if (draft.id > 0) dao.getPurchaseOrder(draft.id) else null
            if (draft.id > 0) requireNotNull(existingOrder) { "Purchase order not found" }

            val invoicePath = copiedInvoice ?: draft.existingInvoicePath ?: existingOrder?.invoicePath
            val now = System.currentTimeMillis()
            val header = PurchaseOrderEntity(
                id = draft.id,
                purchaseDateEpochDay = draft.dateEpochDay,
                supplier = draft.supplier.trim(),
                orderNumber = draft.orderNumber.trim(),
                accountUsername = draft.accountUsername.trim(),
                vatType = draft.vatType,
                paymentMethod = draft.paymentMethod.trim(),
                invoicePath = invoicePath,
                notes = draft.notes.trim(),
                updatedAtMillis = now
            )
            val savedOrderId = if (existingOrder == null) dao.insertPurchaseOrder(header) else {
                dao.updatePurchaseOrder(header)
                header.id
            }

            val existingLines = if (existingOrder == null) emptyList() else dao.getPurchasesForOrder(savedOrderId)
            val existingById = existingLines.associateBy { it.id }
            val requestedIds = draft.items.filter { it.id > 0 }.map { it.id }
            require(requestedIds.size == requestedIds.distinct().size) { "Duplicate purchase line" }
            requestedIds.forEach { require(it in existingById) { "Purchase line does not belong to this order" } }

            val allAllocations = dao.getSaleAllocations()
            val allReturnAllocations = dao.getSaleReturnAllocations()

            existingLines.filter { old -> old.id !in requestedIds }.forEach { old ->
                require(allAllocations.none { it.purchaseId == old.id }) {
                    "${old.item} has sales history and cannot be removed from the purchase. Adjust its quantities instead."
                }
                dao.deletePurchase(old)
            }

            draft.items.forEachIndexed { index, itemDraft ->
                val old = existingById[itemDraft.id]
                val lineAllocations = if (old == null) emptyList() else allAllocations.filter { it.purchaseId == old.id }
                if (old != null && lineAllocations.isNotEmpty()) {
                    require(old.item.trim().equals(itemDraft.item.trim(), ignoreCase = true)) {
                        "${old.item} has sales history, so its item name cannot be changed"
                    }
                    val allocationIds = lineAllocations.map { it.id }.toSet()
                    val restored = allReturnAllocations
                        .filter { it.saleAllocationId in allocationIds }
                        .sumOf { it.quantity }
                    val netSold = lineAllocations.sumOf { it.quantity } - restored
                    require(itemDraft.receivedQty - itemDraft.returnedQty >= netSold) {
                        "${old.item} has $netSold unit(s) allocated to sales; received/returned quantities cannot reduce available stock below that"
                    }
                }

                val vat = breakdownFromGross(itemDraft.grossPence, draft.vatType)
                val explicitRefundTotal = if (itemDraft.partialRefund) itemDraft.refundNetPence + itemDraft.refundVatPence else 0
                val refundExpected = if (itemDraft.partialRefund) explicitRefundTotal else itemDraft.refundExpectedPence
                val refundReceived = if (itemDraft.partialRefund) explicitRefundTotal else itemDraft.refundReceivedPence
                val lineStatus = derivePurchaseStatus(
                    itemDraft.quantity,
                    itemDraft.receivedQty,
                    itemDraft.cancelledQty,
                    itemDraft.returnedQty,
                    refundExpected,
                    refundReceived
                )
                val line = PurchaseEntity(
                    id = itemDraft.id,
                    purchaseOrderId = savedOrderId,
                    purchaseDateEpochDay = draft.dateEpochDay,
                    supplier = draft.supplier.trim(),
                    item = itemDraft.item.trim(),
                    quantity = itemDraft.quantity,
                    orderNumber = draft.orderNumber.trim(),
                    accountUsername = draft.accountUsername.trim(),
                    grossPence = vat.grossPence,
                    netPence = vat.netPence,
                    vatPence = vat.vatPence,
                    reverseVatPence = vat.reverseVatPence,
                    vatType = draft.vatType,
                    paymentMethod = draft.paymentMethod.trim(),
                    status = lineStatus,
                    receivedQty = itemDraft.receivedQty,
                    cancelledQty = itemDraft.cancelledQty,
                    returnedQty = itemDraft.returnedQty,
                    refundExpectedPence = refundExpected,
                    refundReceivedPence = refundReceived,
                    partialRefund = itemDraft.partialRefund,
                    refundNetPence = if (itemDraft.partialRefund) itemDraft.refundNetPence else legacyRefundNet(refundExpected, draft.vatType),
                    refundVatPence = if (itemDraft.partialRefund) itemDraft.refundVatPence else legacyRefundVat(refundExpected, draft.vatType),
                    invoicePath = if (index == 0) invoicePath else null,
                    notes = draft.notes.trim(),
                    updatedAtMillis = now
                )
                val lineId = if (old == null) dao.insertPurchase(line) else {
                    dao.updatePurchase(line)
                    line.id
                }
                val effectiveNet = (vat.netPence - if (itemDraft.partialRefund) itemDraft.refundNetPence else 0).coerceAtLeast(0)
                val unitNet = if (itemDraft.quantity > 0) effectiveNet / itemDraft.quantity else 0
                dao.updateAllocationUnitCostForPurchase(lineId, unitNet)
            }
            savedOrderId
        }
        enqueueSync()
        return orderId
    }

    private fun legacyRefundNet(refundGrossPence: Long, vatType: String): Long =
        if (refundGrossPence > 0) breakdownFromGross(refundGrossPence, vatType).netPence else 0

    private fun legacyRefundVat(refundGrossPence: Long, vatType: String): Long =
        if (refundGrossPence > 0) breakdownFromGross(refundGrossPence, vatType).vatPence else 0

    private fun validatePurchaseItem(item: PurchaseItemDraft) {
        require(item.item.isNotBlank()) { "Each purchase line needs an item" }
        require(item.quantity > 0) { "Quantity must be greater than zero" }
        require(item.grossPence >= 0) { "Gross cost cannot be negative" }
        require(item.receivedQty >= 0 && item.cancelledQty >= 0 && item.returnedQty >= 0) { "Quantities cannot be negative" }
        require(item.receivedQty <= item.quantity) { "Received quantity cannot exceed purchased quantity for ${item.item}" }
        require(item.cancelledQty <= item.quantity - item.receivedQty) { "Cancelled quantity exceeds the outstanding quantity for ${item.item}" }
        require(item.returnedQty <= item.receivedQty) { "Returned quantity cannot exceed received quantity for ${item.item}" }
        require(item.refundExpectedPence >= 0 && item.refundReceivedPence >= 0) { "Refund values cannot be negative" }
        require(item.refundNetPence >= 0 && item.refundVatPence >= 0) { "Refund net/VAT values cannot be negative" }
        if (item.partialRefund) {
            require(item.refundNetPence + item.refundVatPence > 0) { "Enter the net and/or VAT amount of the partial refund for ${item.item}" }
            val original = breakdownFromGross(item.grossPence, VatTypes.STANDARD)
            require(item.refundNetPence <= item.grossPence) { "Partial refund net exceeds the purchase value for ${item.item}" }
            require(item.refundVatPence <= original.vatPence || item.refundVatPence <= item.grossPence) { "Partial refund VAT exceeds the purchase value for ${item.item}" }
            require(item.refundNetPence + item.refundVatPence <= item.grossPence) { "Partial refund exceeds the purchase value for ${item.item}" }
        }
    }

    private fun derivePurchaseStatus(
        quantity: Int,
        receivedQty: Int,
        cancelledQty: Int,
        returnedQty: Int,
        refundExpectedPence: Long,
        refundReceivedPence: Long
    ): String = when {
        refundExpectedPence > 0 && refundReceivedPence >= refundExpectedPence -> "REFUND_RECEIVED"
        refundExpectedPence > refundReceivedPence -> "REFUND_PENDING"
        returnedQty > 0 && returnedQty >= receivedQty && receivedQty > 0 -> "RETURNED"
        cancelledQty >= quantity -> "CANCELLED"
        receivedQty <= 0 -> "RECEIPT_PENDING"
        receivedQty + cancelledQty < quantity -> "PARTIALLY_RECEIVED"
        else -> "RECEIVED"
    }

    /** Legacy one-item entry adapter. */
    suspend fun savePurchase(draft: PurchaseDraft, attachmentUri: Uri? = null): Long {
        val existing = if (draft.id > 0) dao.getPurchase(draft.id) else null
        val orderId = existing?.purchaseOrderId?.takeIf { it > 0 } ?: 0L
        val header = if (orderId > 0) dao.getPurchaseOrder(orderId) else null
        val currentLines = if (orderId > 0) dao.getPurchasesForOrder(orderId) else emptyList()
        val replacement = PurchaseItemDraft(
            id = existing?.id ?: 0,
            item = draft.item,
            quantity = draft.quantity,
            grossPence = draft.grossPence,
            receivedQty = draft.receivedQty,
            cancelledQty = draft.cancelledQty,
            returnedQty = draft.returnedQty,
            refundExpectedPence = draft.refundExpectedPence,
            refundReceivedPence = draft.refundReceivedPence,
            partialRefund = draft.partialRefund,
            refundNetPence = draft.refundNetPence,
            refundVatPence = draft.refundVatPence
        )
        val itemDrafts = if (existing == null) listOf(replacement) else currentLines.map { line ->
            if (line.id == existing.id) replacement else PurchaseItemDraft(
                id = line.id,
                item = line.item,
                quantity = line.quantity,
                grossPence = line.grossPence,
                receivedQty = line.receivedQty,
                cancelledQty = line.cancelledQty,
                returnedQty = line.returnedQty,
                refundExpectedPence = line.refundExpectedPence,
                refundReceivedPence = line.refundReceivedPence,
                partialRefund = line.partialRefund,
                refundNetPence = line.refundNetPence,
                refundVatPence = line.refundVatPence
            )
        }
        val savedOrderId = savePurchaseOrder(
            PurchaseOrderDraft(
                id = header?.id ?: 0,
                dateEpochDay = draft.dateEpochDay,
                supplier = draft.supplier,
                orderNumber = draft.orderNumber,
                accountUsername = draft.accountUsername,
                vatType = draft.vatType,
                paymentMethod = draft.paymentMethod,
                notes = draft.notes,
                items = itemDrafts,
                existingInvoicePath = header?.invoicePath ?: draft.existingInvoicePath
            ),
            attachmentUri
        )
        return if (existing != null) existing.id else dao.getPurchasesForOrder(savedOrderId).first().id
    }

    suspend fun markReceivedAllOrder(orderId: Long) {
        db.withTransaction {
            val lines = dao.getPurchasesForOrder(orderId)
            require(lines.isNotEmpty()) { "Purchase order not found" }
            lines.forEach { line ->
                val received = (line.quantity - line.cancelledQty).coerceAtLeast(0)
                val returned = line.returnedQty.coerceAtMost(received)
                dao.updatePurchase(
                    line.copy(
                        receivedQty = received,
                        returnedQty = returned,
                        status = derivePurchaseStatus(
                            line.quantity, received, line.cancelledQty, returned,
                            line.refundExpectedPence, line.refundReceivedPence
                        ),
                        updatedAtMillis = System.currentTimeMillis()
                    )
                )
            }
        }
        enqueueSync()
    }

    suspend fun markReceivedAll(purchase: PurchaseEntity) {
        markReceivedAllOrder(purchase.purchaseOrderId.takeIf { it > 0 } ?: purchase.id)
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
            val effectiveNet = (p.netPence - if (p.partialRefund) p.refundNetPence else 0).coerceAtLeast(0)
            val unitNetCost = if (p.quantity > 0) effectiveNet / p.quantity else 0
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
        purchaseOrders = dao.getPurchaseOrders(),
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
