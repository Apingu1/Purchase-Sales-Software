package com.apingu.purchasesales.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.apingu.purchasesales.sync.DropboxDeletionQueue
import com.apingu.purchasesales.sync.DropboxSyncWorker
import com.apingu.purchasesales.util.*
import kotlinx.coroutines.flow.Flow
import java.io.File
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
    val existingInvoicePath: String? = null,
    val deliveryGrossPence: Long = 0
)

data class SaleLineDraft(val item: String, val quantity: Int, val unitNetPence: Long)
data class SaleDraft(
    val id: Long = 0,
    val dateEpochDay: Long,
    val customerId: Long,
    val vatType: String,
    val notes: String,
    val lines: List<SaleLineDraft>,
    val manualInvoiceNo: String? = null
)

data class CreditNoteUnitDraft(
    val identifier: String,
    val purchaseId: Long = 0
)

data class CreditNoteLineDraft(
    val saleLineId: Long,
    val quantity: Int,
    val restock: Boolean,
    val units: List<CreditNoteUnitDraft> = emptyList()
)

data class CreditNoteDraft(
    val id: Long = 0,
    val saleId: Long,
    val dateEpochDay: Long,
    val notes: String,
    val lines: List<CreditNoteLineDraft>
)

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
    val existingAttachmentPath: String? = null,
    val orderNumber: String = "",
    val accountUsername: String = ""
)

data class FullData(
    val business: BusinessEntity,
    val customers: List<CustomerEntity>,
    val purchaseOrders: List<PurchaseOrderEntity>,
    val purchases: List<PurchaseEntity>,
    val sales: List<SaleEntity>,
    val saleLines: List<SaleLineEntity>,
    val allocations: List<SaleAllocationEntity>,
    val creditNotes: List<CreditNoteEntity>,
    val saleReturns: List<SaleReturnEntity>,
    val returnAllocations: List<SaleReturnAllocationEntity>,
    val creditNoteImeis: List<CreditNoteImeiEntity>,
    val expenses: List<ExpenseEntity>
)

data class AccountingPeriodDeletionImpact(
    val purchaseOrders: Int, val purchases: Int, val sales: Int, val creditNotes: Int,
    val expenses: Int, val documents: Int, val blockers: List<String>
)

class AppRepository(private val context: Context, private val db: AppDatabase) {
    private val dao = db.dao()

    val business: Flow<BusinessEntity?> = dao.observeBusiness()
    val accountingPeriods = dao.observeAccountingPeriods()
    val customers = dao.observeCustomers()
    val purchaseOrders = dao.observePurchaseOrders()
    val purchases = dao.observePurchases()
    val emailAccounts = dao.observeEmailAccounts()
    val pendingInvoiceCandidates = dao.observePendingInvoiceCandidates()
    val purchaseInvoices = dao.observeAllPurchaseInvoices()
    val sales = dao.observeSales()
    val saleLines = dao.observeSaleLines()
    val allocations = dao.observeSaleAllocations()
    val creditNotes = dao.observeCreditNotes()
    val saleReturns = dao.observeSaleReturns()
    val returnAllocations = dao.observeSaleReturnAllocations()
    val creditNoteImeis = dao.observeCreditNoteImeis()
    val expenses = dao.observeExpenses()
    val expenseInvoices = dao.observeExpenseInvoices()
    val pendingExpenseInvoiceCandidates = dao.observePendingExpenseInvoiceCandidates()

    suspend fun ensureBusinessDefaults() {
        var business = dao.getBusiness()
        if (business == null) {
            val today = LocalDate.now()
            business = BusinessEntity(
                accountingStartEpochDay = today.withMonth(1).withDayOfMonth(1).toEpochDay(),
                accountingEndEpochDay = today.withMonth(12).withDayOfMonth(31).toEpochDay()
            )
            dao.saveBusiness(business)
        }

        var periods = dao.getAccountingPeriods()
        if (periods.isEmpty()) {
            val start = business.accountingStartEpochDay.takeIf { it > 0 }
                ?: LocalDate.now().withMonth(1).withDayOfMonth(1).toEpochDay()
            val end = business.accountingEndEpochDay.takeIf { it >= start }
                ?: LocalDate.now().withMonth(12).withDayOfMonth(31).toEpochDay()
            val id = dao.insertAccountingPeriod(
                AccountingPeriodEntity(name = defaultPeriodName(start, end), startEpochDay = start, endEpochDay = end)
            )
            business = business.copy(
                accountingStartEpochDay = start,
                accountingEndEpochDay = end,
                selectedAccountingPeriodId = id
            )
            dao.saveBusiness(business)
            periods = dao.getAccountingPeriods()
        }

        if (business.selectedAccountingPeriodId <= 0 || periods.none { it.id == business.selectedAccountingPeriodId }) {
            val period = periods.maxByOrNull { it.startEpochDay } ?: return
            dao.saveBusiness(
                business.copy(
                    selectedAccountingPeriodId = period.id,
                    accountingStartEpochDay = period.startEpochDay,
                    accountingEndEpochDay = period.endEpochDay
                )
            )
        }
    }

    suspend fun saveBusiness(value: BusinessEntity) {
        val identifier = value.invoiceIdentifier.trim().uppercase()
        require(identifier.isBlank() || identifier.matches(Regex("[A-Z0-9]{2,8}"))) {
            "Business invoice identifier must be 2–8 letters/numbers, e.g. MSG"
        }
        dao.saveBusiness(value.copy(invoiceIdentifier = identifier))
        enqueueSync()
    }

    suspend fun saveAccountingPeriod(value: AccountingPeriodEntity): Long {
        require(value.name.trim().isNotBlank()) { "Accounting period name is required" }
        require(value.startEpochDay <= value.endEpochDay) { "Accounting period end date must be on or after the start date" }
        val existing = dao.getAccountingPeriods().filter { it.id != value.id }
        require(existing.none { value.startEpochDay <= it.endEpochDay && value.endEpochDay >= it.startEpochDay }) {
            "Accounting periods cannot overlap"
        }
        val normalized = value.copy(name = value.name.trim())
        val id = if (value.id == 0L) dao.insertAccountingPeriod(normalized) else {
            dao.updateAccountingPeriod(normalized)
            value.id
        }
        val business = dao.getBusiness() ?: BusinessEntity()
        if (business.selectedAccountingPeriodId <= 0) selectAccountingPeriod(id) else enqueueSync()
        return id
    }

    suspend fun selectAccountingPeriod(id: Long) {
        val period = dao.getAccountingPeriod(id) ?: error("Accounting period not found")
        val business = dao.getBusiness() ?: BusinessEntity()
        dao.saveBusiness(
            business.copy(
                selectedAccountingPeriodId = period.id,
                accountingStartEpochDay = period.startEpochDay,
                accountingEndEpochDay = period.endEpochDay
            )
        )
    }

    /** Preview the effect of a development-era period reset before permitting deletion. */
    suspend fun accountingPeriodDeletionImpact(period: AccountingPeriodEntity): AccountingPeriodDeletionImpact {
        val start = period.startEpochDay
        val end = period.endEpochDay
        val orders = dao.getPurchaseOrders().filter { it.purchaseDateEpochDay in start..end }
        val purchases = dao.getPurchases().filter { it.purchaseDateEpochDay in start..end }
        val sales = dao.getSales()
        val affectedSales = sales.filter { it.saleDateEpochDay in start..end }
        val saleIds = affectedSales.map { it.id }.toSet()
        val saleLines = dao.getSaleLines()
        val saleIdByLine = saleLines.associate { it.id to it.saleId }
        val allocations = dao.getSaleAllocations()
        val purchaseIds = purchases.map { it.id }.toSet()
        val creditNotes = dao.getCreditNotes()
        val returns = dao.getSaleReturns()
        val affectedCreditNotes = creditNotes.filter { it.creditDateEpochDay in start..end }
        val affectedExpenses = dao.getExpenses().filter { it.expenseDateEpochDay in start..end }
        val blockers = mutableListOf<String>()
        if (allocations.any { it.purchaseId in purchaseIds &&
                saleIdByLine[it.saleLineId] !in saleIds })
            blockers += "Stock purchased in this period was sold in another period. Preserve or reverse those later sales first."
        if (creditNotes.any { (it.saleId in saleIds) != (it.creditDateEpochDay in start..end) })
            blockers += "Some credit notes cross this period boundary."
        if (returns.any { (saleIdByLine[it.saleLineId] in saleIds) !=
                (it.returnDateEpochDay in start..end) })
            blockers += "Some customer returns cross this period boundary."
        val orderIds = orders.map { it.id }.toSet()
        if (purchases.any { it.purchaseOrderId > 0 && it.purchaseOrderId !in orderIds })
            blockers += "A purchase line belongs to a purchase order outside this period."
        if (dao.getPurchases().any { it.purchaseOrderId in orderIds && it.purchaseDateEpochDay !in start..end })
            blockers += "A purchase order contains lines in another period."
        val periods = dao.getAccountingPeriods()
        if (periods.size < 2) blockers += "At least one accounting period must remain."
        val sanitized = period.name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(64)
        if (periods.any { it.id != period.id &&
                it.name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(64) == sanitized })
            blockers += "Another accounting period uses the same Dropbox folder name."
        val documents = dao.getAllPurchaseInvoices().count { it.purchaseOrderId in orderIds } +
            dao.getAllExpenseInvoices().count { it.expenseId in affectedExpenses.map { e -> e.id } } +
            affectedSales.count { !it.pdfPath.isNullOrBlank() } +
            affectedCreditNotes.count { !it.pdfPath.isNullOrBlank() } +
            affectedExpenses.count { !it.attachmentPath.isNullOrBlank() }
        return AccountingPeriodDeletionImpact(orders.size, purchases.size,
            affectedSales.size, affectedCreditNotes.size, affectedExpenses.size, documents, blockers)
    }

    /** Deletes linked development data atomically, after a private full database/document backup. */
    suspend fun deleteAccountingPeriodWithData(period: AccountingPeriodEntity): String {
        requireNotNull(dao.getAccountingPeriod(period.id)) { "Accounting period no longer exists" }
        val impact = accountingPeriodDeletionImpact(period)
        require(impact.blockers.isEmpty()) { impact.blockers.joinToString("\n") }
        val oldOrders = dao.getPurchaseOrders().filter { it.purchaseDateEpochDay in period.startEpochDay..period.endEpochDay }
        val orderIds = oldOrders.map { it.id }.toSet()
        val oldPurchases = dao.getPurchases().filter { it.purchaseDateEpochDay in period.startEpochDay..period.endEpochDay }
        val oldSales = dao.getSales().filter { it.saleDateEpochDay in period.startEpochDay..period.endEpochDay }
        val oldCredits = dao.getCreditNotes().filter { it.creditDateEpochDay in period.startEpochDay..period.endEpochDay }
        val oldExpenses = dao.getExpenses().filter { it.expenseDateEpochDay in period.startEpochDay..period.endEpochDay }
        val paths = (
            dao.getAllPurchaseInvoices().filter { it.purchaseOrderId in orderIds }.map { it.filePath } +
                dao.getAllExpenseInvoices().filter { invoice -> oldExpenses.any { it.id == invoice.expenseId } }
                    .map { it.filePath } +
                oldOrders.mapNotNull { it.invoicePath } +
                oldPurchases.mapNotNull { it.invoicePath } +
                oldSales.mapNotNull { it.pdfPath } +
                oldCredits.mapNotNull { it.pdfPath } +
                oldExpenses.mapNotNull { it.attachmentPath }
        ).distinct()
        val backups = File(context.filesDir, "period-reset-backups").apply { mkdirs() }
        val backup = File(backups, "period_" + period.id + "_" + System.currentTimeMillis()).apply { mkdirs() }
        try {
            // Checkpoint WAL before copying the database. Do not proceed without a successful backup.
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                require(cursor.moveToFirst() && cursor.getInt(0) == 0) {
                    "Database is busy; cannot create a safe pre-deletion backup"
                }
            }
            context.getDatabasePath("purchase-sales.db").copyTo(File(backup, "purchase-sales.db"), overwrite = true)
            val fileBackup = File(backup, "documents").apply { mkdirs() }
            val manifest = mutableListOf<String>()
            paths.forEachIndexed { index, path ->
                val source = File(path)
                if (source.isFile) {
                    val name = index.toString() + "_" + source.name
                    source.copyTo(File(fileBackup, name), overwrite = true)
                    manifest += name + "\t" + source.absolutePath
                }
            }
            File(backup, "documents-manifest.txt").writeText(manifest.joinToString("\n"))
        } catch (error: Exception) {
            backup.deleteRecursively()
            throw IllegalStateException("Period was not deleted: backup failed: " + (error.message ?: "Unknown error"))
        }
        db.withTransaction {
            val currentImpact = accountingPeriodDeletionImpact(period)
            require(currentImpact.blockers.isEmpty()) { currentImpact.blockers.joinToString("\n") }
            val sql = db.openHelper.writableDatabase
            // Delete dependent children before parents; do not leave phantom stock allocations.
            sql.execSQL("""DELETE FROM credit_note_imeis WHERE creditNoteId IN
                (SELECT id FROM credit_notes WHERE creditDateEpochDay BETWEEN ? AND ?)""",
                arrayOf(period.startEpochDay, period.endEpochDay))
            sql.execSQL("""DELETE FROM sale_return_allocations WHERE saleReturnId IN
                (SELECT sr.id FROM sale_returns sr JOIN sale_lines sl ON sl.id = sr.saleLineId
                  JOIN sales s ON s.id = sl.saleId WHERE s.saleDateEpochDay BETWEEN ? AND ?)""",
                arrayOf(period.startEpochDay, period.endEpochDay))
            sql.execSQL("""DELETE FROM sale_returns WHERE saleLineId IN
                (SELECT sl.id FROM sale_lines sl JOIN sales s ON s.id = sl.saleId
                  WHERE s.saleDateEpochDay BETWEEN ? AND ?)""",
                arrayOf(period.startEpochDay, period.endEpochDay))
            sql.execSQL("DELETE FROM credit_notes WHERE creditDateEpochDay BETWEEN ? AND ?",
                arrayOf(period.startEpochDay, period.endEpochDay))
            sql.execSQL("""DELETE FROM sale_allocations WHERE saleLineId IN
                (SELECT sl.id FROM sale_lines sl JOIN sales s ON s.id = sl.saleId
                  WHERE s.saleDateEpochDay BETWEEN ? AND ?)""",
                arrayOf(period.startEpochDay, period.endEpochDay))
            sql.execSQL("""DELETE FROM sale_lines WHERE saleId IN
                (SELECT id FROM sales WHERE saleDateEpochDay BETWEEN ? AND ?)""",
                arrayOf(period.startEpochDay, period.endEpochDay))
            sql.execSQL("DELETE FROM sales WHERE saleDateEpochDay BETWEEN ? AND ?",
                arrayOf(period.startEpochDay, period.endEpochDay))
            sql.execSQL("DELETE FROM purchases WHERE purchaseDateEpochDay BETWEEN ? AND ?",
                arrayOf(period.startEpochDay, period.endEpochDay))
            sql.execSQL("DELETE FROM purchase_orders WHERE purchaseDateEpochDay BETWEEN ? AND ?",
                arrayOf(period.startEpochDay, period.endEpochDay))
            sql.execSQL("DELETE FROM expenses WHERE expenseDateEpochDay BETWEEN ? AND ?",
                arrayOf(period.startEpochDay, period.endEpochDay))
            dao.deleteAccountingPeriod(period)
            val business = dao.getBusiness() ?: BusinessEntity()
            if (business.selectedAccountingPeriodId == period.id) {
                val replacement = dao.getAccountingPeriods().maxByOrNull { it.startEpochDay }
                    ?: error("A remaining accounting period is required")
                dao.saveBusiness(business.copy(selectedAccountingPeriodId = replacement.id,
                    accountingStartEpochDay = replacement.startEpochDay,
                    accountingEndEpochDay = replacement.endEpochDay))
            }
        }
        // Document cleanup is intentionally deferred until after the database commit and backup.
        val remainingPaths = buildSet {
            addAll(dao.getAllPurchaseInvoices().map { it.filePath })
            addAll(dao.getPurchaseOrders().mapNotNull { it.invoicePath })
            addAll(dao.getPurchases().mapNotNull { it.invoicePath })
            addAll(dao.getSales().mapNotNull { it.pdfPath })
            addAll(dao.getCreditNotes().mapNotNull { it.pdfPath })
            addAll(dao.getExpenses().mapNotNull { it.attachmentPath })
            addAll(dao.getAllExpenseInvoices().map { it.filePath })
        }
        paths.filterNot { it in remainingPaths }.forEach { File(it).delete() }
        DropboxDeletionQueue.enqueuePeriod(context, period.name)
        enqueueSync()
        return backup.absolutePath
    }

    suspend fun deleteAccountingPeriod(value: AccountingPeriodEntity) {
        val periods = dao.getAccountingPeriods()
        require(periods.size > 1) { "Keep at least one accounting period" }
        dao.deleteAccountingPeriod(value)
        val business = dao.getBusiness() ?: BusinessEntity()
        if (business.selectedAccountingPeriodId == value.id) {
            val replacement = dao.getAccountingPeriods().maxByOrNull { it.startEpochDay } ?: return
            selectAccountingPeriod(replacement.id)
        }
        enqueueSync()
    }

    suspend fun addCustomer(value: CustomerEntity): Long {
        require(value.companyName.isNotBlank()) { "Customer name is required" }
        require(value.invoiceCode.trim().length in 2..5) { "Customer invoice code must be 2–5 characters" }
        val normalized = value.copy(invoiceCode = value.invoiceCode.trim().uppercase())
        return if (value.id == 0L) dao.insertCustomer(normalized) else { dao.updateCustomer(normalized); value.id }
    }

    suspend fun deleteCustomer(value: CustomerEntity) = dao.deleteCustomer(value)

    suspend fun savePurchaseOrder(draft: PurchaseOrderDraft, attachmentUri: Uri? = null): Long {
        require(draft.supplier.isNotBlank()) { "Supplier/store is required" }
        require(draft.deliveryGrossPence >= 0) { "Delivery charge cannot be negative" }
        require(draft.items.isNotEmpty()) { "Add at least one item" }
        draft.items.forEach(::validatePurchaseItem)

        val copiedInvoice = attachmentUri?.let {
            DocumentStore.copyIntoApp(context, it, "PUR_ORDER_${draft.id.takeIf { n -> n > 0 } ?: "NEW"}")
        }
        val oldDropboxOrder = if (draft.id > 0) dao.getPurchaseOrder(draft.id) else null
        val oldDropboxDocuments = if (draft.id > 0) dao.getPurchaseInvoices(draft.id) else emptyList()
        val oldPendingCandidates = if (draft.id > 0) dao.getPendingInvoiceCandidatesForOrder(draft.id) else emptyList()
        val attachmentName = attachmentUri?.let { PurchaseInvoiceFiles.originalName(context, it) }.orEmpty()
        val attachmentFingerprint = try {
            copiedInvoice?.let(PurchaseInvoiceFiles::fingerprint)
        } catch (error: Exception) {
            copiedInvoice?.let { File(it).delete() }
            throw error
        }

        val orderId = try {
            db.withTransaction {
            val existingOrder = if (draft.id > 0) dao.getPurchaseOrder(draft.id) else null
            if (draft.id > 0) requireNotNull(existingOrder) { "Purchase order not found" }

            val invoicePath = copiedInvoice ?: draft.existingInvoicePath ?: existingOrder?.invoicePath
            val now = System.currentTimeMillis()
            val supplierKey = InvoiceDocumentNaming.supplierKey(draft.supplier)
            val sequence = if (existingOrder != null &&
                existingOrder.purchaseDateEpochDay == draft.dateEpochDay &&
                existingOrder.invoiceSupplierKey == supplierKey &&
                existingOrder.invoiceSequence > 0
            ) existingOrder.invoiceSequence
              else dao.nextPurchaseInvoiceSequence(draft.dateEpochDay, supplierKey, draft.id)
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
                updatedAtMillis = now,
                invoiceSequence = sequence,
                invoiceSupplierKey = supplierKey,
                deliveryGrossPence = draft.deliveryGrossPence
            )
            val savedOrderId = if (existingOrder == null) dao.insertPurchaseOrder(header) else {
                dao.updatePurchaseOrder(header)
                header.id
            }
            if (existingOrder != null && (
                existingOrder.orderNumber.trim() != header.orderNumber ||
                !existingOrder.accountUsername.trim().equals(header.accountUsername, ignoreCase = true)
            )) {
                // A candidate found for the OLD mailbox/reference must never be
                // approved against the edited purchase.
                dao.clearPendingInvoiceCandidates(savedOrderId)
            }

            if (copiedInvoice != null) {
                // Old purchase-editor's "Replace invoice" updates only its legacy slot.
                // Documents added through the new multi-invoice repository are preserved.
                val previous = existingOrder?.invoicePath?.let { oldPath ->
                    dao.getPurchaseInvoiceByPath(savedOrderId, oldPath)
                }
                val fingerprint = requireNotNull(attachmentFingerprint)
                val replacement = PurchaseInvoiceEntity(
                    id = previous?.id ?: 0,
                    purchaseOrderId = savedOrderId,
                    invoiceOrdinal = previous?.invoiceOrdinal ?: dao.nextInvoiceOrdinal(savedOrderId),
                    filePath = copiedInvoice,
                    originalFileName = attachmentName,
                    source = "MANUAL",
                    sha256 = fingerprint.sha256,
                    byteSize = fingerprint.byteSize,
                    createdAtMillis = previous?.createdAtMillis ?: now,
                    approvedAtMillis = now
                )
                if (previous == null) dao.insertPurchaseInvoice(replacement)
                else dao.updatePurchaseInvoice(replacement)
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
                    val restored = allReturnAllocations.filter { it.saleAllocationId in allocationIds }.sumOf { it.quantity }
                    val netSold = lineAllocations.sumOf { it.quantity } - restored
                    require(itemDraft.receivedQty - itemDraft.returnedQty >= netSold) {
                        "${old.item} has $netSold unit(s) allocated to sales; received/returned quantities cannot reduce available stock below that"
                    }
                }

                val vat = breakdownFromGross(itemDraft.grossPence, draft.vatType)
                val explicitRefundTotal = if (itemDraft.partialRefund) itemDraft.refundNetPence + itemDraft.refundVatPence else 0
                val refundExpected = if (itemDraft.partialRefund) explicitRefundTotal else itemDraft.refundExpectedPence
                val refundReceived = if (itemDraft.partialRefund) explicitRefundTotal else itemDraft.refundReceivedPence
                val lineStatus = derivePurchaseStatus(itemDraft.quantity, itemDraft.receivedQty, itemDraft.cancelledQty, itemDraft.returnedQty, refundExpected, refundReceived)
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
                val lineId = if (old == null) dao.insertPurchase(line) else { dao.updatePurchase(line); line.id }
                val effectiveNet = (vat.netPence - if (itemDraft.partialRefund) itemDraft.refundNetPence else 0).coerceAtLeast(0)
                val unitNet = if (itemDraft.quantity > 0) effectiveNet / itemDraft.quantity else 0
                dao.updateAllocationUnitCostForPurchase(lineId, unitNet)
            }
            savedOrderId
            }
        } catch (error: Exception) {
            // An invalid purchase must not leave an unattached copy on disk.
            copiedInvoice?.let { File(it).delete() }
            throw error
        }
        if (oldDropboxOrder != null && (
            oldDropboxOrder.orderNumber.trim() != draft.orderNumber.trim() ||
            !oldDropboxOrder.accountUsername.trim().equals(draft.accountUsername.trim(), ignoreCase = true)
        )) {
            oldPendingCandidates.forEach { InvoiceCandidateCache.discard(context, it.id) }
        }
        if (oldDropboxOrder != null) {
            val old = oldDropboxOrder
            val now = dao.getPurchaseOrder(orderId)
            if (now != null && (old.purchaseDateEpochDay != now.purchaseDateEpochDay ||
                old.supplier != now.supplier || old.invoiceSequence != now.invoiceSequence)) {
                oldDropboxDocuments.forEach { doc ->
                    DropboxDeletionQueue.enqueuePurchase(context, old.purchaseDateEpochDay,
                        InvoiceDocumentNaming.invoiceFileName(old.purchaseDateEpochDay, old.supplier,
                            old.invoiceSequence.takeIf { it > 0 } ?: old.id.toInt(),
                            doc.invoiceOrdinal, File(doc.filePath).extension.ifBlank { "pdf" }))
                }
            }
        }
        enqueueSync()
        return orderId
    }


    /** A waiver changes only document tracking, never financials or email eligibility. */
    suspend fun setInvoiceWaived(orderId: Long, waived: Boolean) {
        val order = dao.getPurchaseOrder(orderId) ?: error("Purchase order not found")
        if (waived) require(dao.getPurchaseInvoices(orderId).isEmpty()) {
            "This purchase already has an approved invoice"
        }
        dao.updatePurchaseOrder(order.copy(invoiceWaived = waived, updatedAtMillis = System.currentTimeMillis()))
        enqueueSync()
    }

    /** Attach an additional invoice without overwriting other invoices or any purchase financials. */
    suspend fun attachAdditionalPurchaseInvoice(orderId: Long, uri: Uri): Long {
        requireNotNull(dao.getPurchaseOrder(orderId)) { "Purchase order not found" }
        val path = DocumentStore.copyIntoApp(context, uri, "PUR_INV_${orderId}_${java.util.UUID.randomUUID()}")
        try {
            val fingerprint = PurchaseInvoiceFiles.fingerprint(path)
            val fileName = PurchaseInvoiceFiles.originalName(context, uri)
            val id = db.withTransaction {
                requireNotNull(dao.getPurchaseOrder(orderId)) { "Purchase order not found" }
                val existing = dao.getPurchaseInvoices(orderId)
                require(existing.none { previous ->
                    val priorHash = previous.sha256.ifBlank {
                        runCatching { PurchaseInvoiceFiles.fingerprint(previous.filePath).sha256 }.getOrDefault("")
                    }
                    priorHash.isNotBlank() && priorHash == fingerprint.sha256
                }) {
                    "This invoice is already attached to the purchase"
                }
                val invoiceId = dao.insertPurchaseInvoice(PurchaseInvoiceEntity(
                    purchaseOrderId = orderId,
                    invoiceOrdinal = dao.nextInvoiceOrdinal(orderId),
                    filePath = path,
                    originalFileName = fileName,
                    source = "MANUAL",
                    sha256 = fingerprint.sha256,
                    byteSize = fingerprint.byteSize
                ))
                val refreshed = requireNotNull(dao.getPurchaseOrder(orderId))
                if (refreshed.invoiceWaived) dao.updatePurchaseOrder(refreshed.copy(invoiceWaived = false))
                invoiceId
            }
            enqueueSync()
            return id
        } catch (error: Exception) {
            File(path).delete()
            throw error
        }
    }

    suspend fun getPurchaseInvoiceDocuments(orderId: Long): List<PurchaseInvoiceEntity> =
        dao.getPurchaseInvoices(orderId)

    /** Reject a suggestion without touching the source email or an approved invoice. */
    suspend fun rejectInvoiceCandidate(candidateId: Long) {
        val candidate = dao.getInvoiceCandidate(candidateId) ?: error("Candidate not found")
        require(candidate.status == "PENDING_REVIEW") { "Invoice was already reviewed" }
        dao.updateInvoiceCandidate(candidate.copy(
            status = "REJECTED",
            reviewedAtMillis = System.currentTimeMillis()
        ))
        InvoiceCandidateCache.discard(context, candidateId)
    }

    /**
     * Candidate approval is deliberately constrained to its recorded purchase
     * and EXACT originating purchasing mailbox, never any other account.
     */
    suspend fun approveInvoiceCandidate(candidateId: Long): Long {
        val candidate = dao.getInvoiceCandidate(candidateId) ?: error("Candidate not found")
        require(candidate.status == "PENDING_REVIEW") { "Candidate already reviewed" }
        val order = dao.getPurchaseOrder(candidate.purchaseOrderId) ?: error("Purchase order not found")
        require(order.orderNumber.isNotBlank()) { "The purchase order number is missing" }
        require(order.accountUsername.trim().equals(candidate.accountEmail.trim(), ignoreCase = true)) {
            "Candidate belongs to a different purchasing mailbox"
        }
        val preview = InvoiceCandidateCache.previewFile(context, candidateId)
            ?: error("Preview is unavailable; retrieve the email attachment again")
        val dir = File(context.filesDir, "documents").apply { mkdirs() }
        val saved = File(dir, "EMAIL_INV_" + order.id + "_" + java.util.UUID.randomUUID() +
            "." + preview.extension)
        try {
            preview.copyTo(saved)
            val hash = PurchaseInvoiceFiles.fingerprint(saved.absolutePath)
            val newId = db.withTransaction {
                val current = dao.getInvoiceCandidate(candidateId) ?: error("Candidate not found")
                require(current.status == "PENDING_REVIEW") { "Candidate already reviewed" }
                val currentOrder = dao.getPurchaseOrder(current.purchaseOrderId)
                    ?: error("Purchase order not found")
                require(currentOrder.orderNumber.isNotBlank() &&
                    currentOrder.accountUsername.trim().equals(current.accountEmail.trim(), ignoreCase = true)) {
                    "Purchase order or source mailbox has changed"
                }
                require(dao.getPurchaseInvoices(current.purchaseOrderId).none { invoice ->
                    val priorHash = invoice.sha256.ifBlank {
                        runCatching { PurchaseInvoiceFiles.fingerprint(invoice.filePath).sha256 }.getOrDefault("")
                    }
                    priorHash == hash.sha256
                }) { "This invoice is already attached to the purchase" }
                val id = dao.insertPurchaseInvoice(PurchaseInvoiceEntity(
                    purchaseOrderId = current.purchaseOrderId,
                    invoiceOrdinal = dao.nextInvoiceOrdinal(current.purchaseOrderId),
                    filePath = saved.absolutePath,
                    originalFileName = current.originalFileName,
                    source = "EMAIL",
                    sourceAccountEmail = current.accountEmail,
                    sourceMessageId = current.messageId,
                    sourceAttachmentId = current.attachmentId,
                    sha256 = hash.sha256,
                    byteSize = hash.byteSize
                ))
                if (currentOrder.invoiceWaived)
                    dao.updatePurchaseOrder(currentOrder.copy(invoiceWaived = false))
                dao.updateInvoiceCandidate(current.copy(
                    status = "APPROVED",
                    reviewedAtMillis = System.currentTimeMillis()
                ))
                id
            }
            InvoiceCandidateCache.discard(context, candidateId)
            enqueueSync()
            return newId
        } catch (error: Exception) {
            saved.delete()
            throw error
        }
    }

    /**
     * Removes exactly one document. Never delete a shared local file while another
     * invoice document (or legacy purchase line) still references it.
     */
    suspend fun removePurchaseInvoiceDocument(invoiceId: Long) {
        val invoice = dao.getPurchaseInvoice(invoiceId) ?: error("Invoice not found")
        val order = dao.getPurchaseOrder(invoice.purchaseOrderId) ?: error("Purchase order not found")
        val affectedLegacyLines = dao.getPurchasesForOrder(order.id)
            .filter { it.invoicePath == invoice.filePath }
        val legacySlot = order.invoicePath == invoice.filePath || affectedLegacyLines.isNotEmpty()
        db.withTransaction {
            dao.deletePurchaseInvoice(invoice)
            if (legacySlot) {
                dao.updatePurchaseOrder(order.copy(invoicePath = null, updatedAtMillis = System.currentTimeMillis()))
                affectedLegacyLines.forEach { line ->
                    dao.updatePurchase(line.copy(invoicePath = null, updatedAtMillis = System.currentTimeMillis()))
                }
            }
        }
        // Queue Dropbox deletion only after the local database change commits.
        DropboxDeletionQueue.enqueuePurchase(context, order.purchaseDateEpochDay,
            InvoiceDocumentNaming.invoiceFileName(order.purchaseDateEpochDay, order.supplier,
                order.invoiceSequence.takeIf { it > 0 } ?: order.id.toInt(),
                invoice.invoiceOrdinal, File(invoice.filePath).extension.ifBlank { "pdf" }))
        if (legacySlot) {
            val remoteLines = affectedLegacyLines.ifEmpty { dao.getPurchasesForOrder(order.id).take(1) }
            remoteLines.forEach { line ->
                val file = File(invoice.filePath)
                val safeItem = line.item.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(48)
                DropboxDeletionQueue.enqueuePurchase(context, line.purchaseDateEpochDay,
                    "PUR_${line.id}_${safeItem}.${file.extension.ifBlank { "bin" }}")
            }
        }
        val inOtherInvoices = dao.getAllPurchaseInvoices().any { it.filePath == invoice.filePath }
        val inLegacyOrders = dao.getPurchaseOrders().any { it.invoicePath == invoice.filePath }
        val inLegacyLines = dao.getPurchases().any { it.invoicePath == invoice.filePath }
        if (!inOtherInvoices && !inLegacyOrders && !inLegacyLines) File(invoice.filePath).delete()
        enqueueSync()
    }

    private fun legacyRefundNet(refundGrossPence: Long, vatType: String): Long = if (refundGrossPence > 0) breakdownFromGross(refundGrossPence, vatType).netPence else 0
    private fun legacyRefundVat(refundGrossPence: Long, vatType: String): Long = if (refundGrossPence > 0) breakdownFromGross(refundGrossPence, vatType).vatPence else 0

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

    private fun derivePurchaseStatus(quantity: Int, receivedQty: Int, cancelledQty: Int, returnedQty: Int, refundExpectedPence: Long, refundReceivedPence: Long): String = when {
        refundExpectedPence > 0 && refundReceivedPence >= refundExpectedPence -> "REFUND_RECEIVED"
        refundExpectedPence > refundReceivedPence -> "REFUND_PENDING"
        returnedQty > 0 && returnedQty >= receivedQty && receivedQty > 0 -> "RETURNED"
        cancelledQty >= quantity -> "CANCELLED"
        receivedQty <= 0 -> "RECEIPT_PENDING"
        receivedQty + cancelledQty < quantity -> "PARTIALLY_RECEIVED"
        else -> "RECEIVED"
    }

    suspend fun savePurchase(draft: PurchaseDraft, attachmentUri: Uri? = null): Long {
        val existing = if (draft.id > 0) dao.getPurchase(draft.id) else null
        val orderId = existing?.purchaseOrderId?.takeIf { it > 0 } ?: 0L
        val header = if (orderId > 0) dao.getPurchaseOrder(orderId) else null
        val currentLines = if (orderId > 0) dao.getPurchasesForOrder(orderId) else emptyList()
        val replacement = PurchaseItemDraft(
            id = existing?.id ?: 0, item = draft.item, quantity = draft.quantity, grossPence = draft.grossPence,
            receivedQty = draft.receivedQty, cancelledQty = draft.cancelledQty, returnedQty = draft.returnedQty,
            refundExpectedPence = draft.refundExpectedPence, refundReceivedPence = draft.refundReceivedPence,
            partialRefund = draft.partialRefund, refundNetPence = draft.refundNetPence, refundVatPence = draft.refundVatPence
        )
        val itemDrafts = if (existing == null) listOf(replacement) else currentLines.map { line ->
            if (line.id == existing.id) replacement else PurchaseItemDraft(
                id = line.id, item = line.item, quantity = line.quantity, grossPence = line.grossPence,
                receivedQty = line.receivedQty, cancelledQty = line.cancelledQty, returnedQty = line.returnedQty,
                refundExpectedPence = line.refundExpectedPence, refundReceivedPence = line.refundReceivedPence,
                partialRefund = line.partialRefund, refundNetPence = line.refundNetPence, refundVatPence = line.refundVatPence
            )
        }
        val savedOrderId = savePurchaseOrder(
            PurchaseOrderDraft(
                id = header?.id ?: 0, dateEpochDay = draft.dateEpochDay, supplier = draft.supplier,
                orderNumber = draft.orderNumber, accountUsername = draft.accountUsername, vatType = draft.vatType,
                paymentMethod = draft.paymentMethod, notes = draft.notes, items = itemDrafts,
                existingInvoicePath = header?.invoicePath ?: draft.existingInvoicePath,
                deliveryGrossPence = header?.deliveryGrossPence ?: 0
            ), attachmentUri
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
                dao.updatePurchase(line.copy(
                    receivedQty = received,
                    returnedQty = returned,
                    status = derivePurchaseStatus(line.quantity, received, line.cancelledQty, returned, line.refundExpectedPence, line.refundReceivedPence),
                    updatedAtMillis = System.currentTimeMillis()
                ))
            }
        }
        enqueueSync()
    }

    suspend fun markReceivedAll(purchase: PurchaseEntity) { markReceivedAllOrder(purchase.purchaseOrderId.takeIf { it > 0 } ?: purchase.id) }

    suspend fun deletePurchaseOrder(orderId: Long) {
        val order = dao.getPurchaseOrder(orderId) ?: error("Purchase order not found")
        val lines = dao.getPurchasesForOrder(orderId)
        dao.getPurchaseInvoices(orderId).forEach { doc ->
            DropboxDeletionQueue.enqueuePurchase(context, order.purchaseDateEpochDay,
                InvoiceDocumentNaming.invoiceFileName(order.purchaseDateEpochDay, order.supplier,
                    order.invoiceSequence.takeIf { it > 0 } ?: order.id.toInt(),
                    doc.invoiceOrdinal, File(doc.filePath).extension.ifBlank { "pdf" }))
        }
        val linkedInvoiceFiles = dao.getPurchaseInvoices(orderId).map { it.filePath }
        require(lines.isNotEmpty()) { "Purchase order has no item lines" }
        val allocations = dao.getSaleAllocations()
        val allocated = lines.firstOrNull { line -> allocations.any { it.purchaseId == line.id } }
        require(allocated == null) {
            "${allocated?.item ?: "This purchase"} has sales history and cannot be deleted. Reverse/correct the related sale first."
        }

        lines.forEach { line ->
            line.invoicePath?.let { path ->
                val file = File(path)
                val ext = file.extension.ifBlank { "bin" }
                val safeItem = line.item.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(48)
                DropboxDeletionQueue.enqueuePurchase(context, line.purchaseDateEpochDay, "PUR_${line.id}_${safeItem}.$ext")
            }
        }
        order.invoicePath?.let { path ->
            val first = lines.firstOrNull()
            if (first != null && first.invoicePath.isNullOrBlank()) {
                val file = File(path)
                val ext = file.extension.ifBlank { "bin" }
                val safeItem = first.item.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(48)
                DropboxDeletionQueue.enqueuePurchase(context, order.purchaseDateEpochDay, "PUR_${first.id}_${safeItem}.$ext")
            }
        }

        db.withTransaction {
            lines.forEach { dao.deletePurchase(it) }
            dao.deletePurchaseOrder(order)
        }
        val otherDocuments = dao.getAllPurchaseInvoices().map { it.filePath }.toSet()
        val otherOrderPaths = dao.getPurchaseOrders().mapNotNull { it.invoicePath }.toSet()
        val otherLinePaths = dao.getPurchases().mapNotNull { it.invoicePath }.toSet()
        (lines.mapNotNull { it.invoicePath } + listOfNotNull(order.invoicePath) + linkedInvoiceFiles)
            .distinct().filterNot { it in otherDocuments || it in otherOrderPaths || it in otherLinePaths }
            .forEach { File(it).delete() }
        enqueueSync()
    }

    suspend fun saveExpense(draft: ExpenseDraft, attachmentUri: Uri? = null): Long {
        require(draft.supplier.isNotBlank()) { "Store/supplier is required" }
        require(draft.details.isNotBlank()) { "Expense details are required" }
        require(draft.grossPence >= 0) { "Expense amount cannot be negative" }
        val vat = breakdownFromGross(draft.grossPence, draft.vatType)
        val old = if (draft.id > 0) dao.getExpense(draft.id) else null
        val value = ExpenseEntity(
            id = draft.id, expenseDateEpochDay = draft.dateEpochDay, supplier = draft.supplier.trim(),
            details = draft.details.trim(), account = draft.account.trim(), grossPence = vat.grossPence,
            netPence = vat.netPence, vatPence = vat.vatPence, reverseVatPence = vat.reverseVatPence,
            vatType = draft.vatType, paymentMethod = draft.paymentMethod.trim(),
            attachmentPath = old?.attachmentPath ?: draft.existingAttachmentPath,
            comments = draft.comments.trim(), updatedAtMillis = System.currentTimeMillis(),
            orderNumber = draft.orderNumber.trim(), accountUsername = draft.accountUsername.trim(),
            invoiceWaived = old?.invoiceWaived ?: false
        )
        val id = if (draft.id == 0L) dao.insertExpense(value) else {
            dao.updateExpense(value); draft.id
        }
        if (attachmentUri != null) attachAdditionalExpenseInvoice(id, attachmentUri)
        enqueueSync()
        return id
    }

    suspend fun attachAdditionalExpenseInvoice(expenseId: Long, uri: Uri): Long {
        requireNotNull(dao.getExpense(expenseId)) { "Expense not found" }
        val path = DocumentStore.copyIntoApp(context, uri, "EXP_INV_${expenseId}_${java.util.UUID.randomUUID()}")
        try {
            val fingerprint = PurchaseInvoiceFiles.fingerprint(path)
            val id = db.withTransaction {
                val expense = dao.getExpense(expenseId) ?: error("Expense not found")
                require(dao.getExpenseInvoices(expenseId).none {
                    it.sha256 == fingerprint.sha256 ||
                        (it.sha256.isBlank() &&
                            runCatching { PurchaseInvoiceFiles.fingerprint(it.filePath).sha256 }
                                .getOrDefault("") == fingerprint.sha256)
                }) { "This invoice is already attached to the expense" }
                val id = dao.insertExpenseInvoice(ExpenseInvoiceEntity(
                    expenseId = expenseId,
                    invoiceOrdinal = dao.nextExpenseInvoiceOrdinal(expenseId),
                    filePath = path,
                    originalFileName = PurchaseInvoiceFiles.originalName(context, uri),
                    sha256 = fingerprint.sha256, byteSize = fingerprint.byteSize
                ))
                if (expense.invoiceWaived) dao.updateExpense(expense.copy(invoiceWaived = false))
                id
            }
            enqueueSync()
            return id
        } catch (error: Exception) {
            File(path).delete()
            throw error
        }
    }

    suspend fun removeExpenseInvoice(invoiceId: Long) {
        val doc = dao.getExpenseInvoice(invoiceId) ?: error("Expense invoice not found")
        val expense = dao.getExpense(doc.expenseId) ?: error("Expense not found")
        db.withTransaction {
            dao.deleteExpenseInvoice(doc)
            if (expense.attachmentPath == doc.filePath)
                dao.updateExpense(expense.copy(attachmentPath = null))
        }
        if (dao.getAllExpenseInvoices().none { it.filePath == doc.filePath } &&
            dao.getExpenses().none { it.attachmentPath == doc.filePath }) File(doc.filePath).delete()
        DropboxDeletionQueue.enqueueExpense(context, expense.expenseDateEpochDay,
            "EXP_${expense.id}_${doc.invoiceOrdinal}." +
                File(doc.filePath).extension.ifBlank { "pdf" })
        enqueueSync()
    }

    suspend fun setExpenseInvoiceWaived(expenseId: Long, waived: Boolean) {
        val expense = dao.getExpense(expenseId) ?: error("Expense not found")
        if (waived) require(dao.getExpenseInvoices(expenseId).isEmpty()) {
            "This expense already has an approved invoice"
        }
        dao.updateExpense(expense.copy(invoiceWaived = waived))
        enqueueSync()
    }

    suspend fun rejectExpenseInvoiceCandidate(candidateId: Long) {
        val candidate = dao.getExpenseInvoiceCandidate(candidateId) ?: error("Candidate not found")
        require(candidate.status == "PENDING_REVIEW") { "Candidate already reviewed" }
        dao.updateExpenseInvoiceCandidate(candidate.copy(status = "REJECTED",
            reviewedAtMillis = System.currentTimeMillis()))
        InvoiceCandidateCache.discard(context, -candidateId)
    }

    suspend fun approveExpenseInvoiceCandidate(candidateId: Long) {
        val candidate = dao.getExpenseInvoiceCandidate(candidateId) ?: error("Candidate not found")
        require(candidate.status == "PENDING_REVIEW") { "Candidate already reviewed" }
        val expense = dao.getExpense(candidate.expenseId) ?: error("Expense not found")
        require(expense.orderNumber.isNotBlank() &&
            expense.accountUsername.equals(candidate.accountEmail, true)) {
            "Expense reference or matching mailbox has changed"
        }
        val preview = InvoiceCandidateCache.previewFile(context, -candidateId)
            ?: error("Preview not available")
        val folder = File(context.filesDir, "documents").apply { mkdirs() }
        val destination = File(folder, "EXP_EMAIL_INV_${expense.id}_${java.util.UUID.randomUUID()}.${preview.extension}")
        try {
            preview.copyTo(destination)
            val fingerprint = PurchaseInvoiceFiles.fingerprint(destination.absolutePath)
            db.withTransaction {
                val current = dao.getExpenseInvoiceCandidate(candidateId)
                    ?: error("Candidate not found")
                require(current.status == "PENDING_REVIEW") { "Candidate already reviewed" }
                val existing = dao.getExpenseInvoices(expense.id)
                require(existing.none { it.sha256 == fingerprint.sha256 }) {
                    "This invoice is already attached to this expense"
                }
                dao.insertExpenseInvoice(ExpenseInvoiceEntity(
                    expenseId = expense.id, invoiceOrdinal = dao.nextExpenseInvoiceOrdinal(expense.id),
                    filePath = destination.absolutePath,
                    originalFileName = current.originalFileName,
                    source = "EMAIL", sourceAccountEmail = current.accountEmail,
                    sourceMessageId = current.messageId,
                    sha256 = fingerprint.sha256, byteSize = fingerprint.byteSize
                ))
                dao.updateExpenseInvoiceCandidate(current.copy(status = "APPROVED",
                    reviewedAtMillis = System.currentTimeMillis()))
                dao.getExpense(expense.id)?.let { currentExpense ->
                    if (currentExpense.invoiceWaived)
                        dao.updateExpense(currentExpense.copy(invoiceWaived = false))
                }
            }
            InvoiceCandidateCache.discard(context, -candidateId)
            enqueueSync()
        } catch (ex: Exception) {
            destination.delete()
            throw ex
        }
    }

    suspend fun deleteExpense(expenseId: Long) {
        val expense = dao.getExpense(expenseId) ?: error("Expense not found")
        val docs = dao.getExpenseInvoices(expenseId)
        dao.deleteExpense(expense)
        (docs.map { it.filePath } + listOfNotNull(expense.attachmentPath)).distinct()
            .filter { path ->
                dao.getAllExpenseInvoices().none { it.filePath == path } &&
                    dao.getExpenses().none { it.attachmentPath == path }
            }.forEach { File(it).delete() }
        docs.forEach { doc ->
            DropboxDeletionQueue.enqueueExpense(context, expense.expenseDateEpochDay,
                "EXP_${expense.id}_${doc.invoiceOrdinal}." +
                    File(doc.filePath).extension.ifBlank { "pdf" })
        }
        enqueueSync()
    }

    suspend fun saveSale(draft: SaleDraft): Long {
        var oldDropboxInvoice: Pair<Long, String>? = null
        var oldLocalPdf: String? = null
        var savedInvoiceNo = ""
        val saleId = db.withTransaction {
            require(draft.customerId > 0) { "Select a customer" }
            require(draft.lines.isNotEmpty()) { "Add at least one item" }
            draft.lines.forEach { require(it.item.isNotBlank() && it.quantity > 0 && it.unitNetPence >= 0) { "Each sale line needs an item, quantity and net price" } }
            if (draft.id > 0 && dao.countReturnsForSale(draft.id) > 0) error("This invoice has a customer return. Create a correcting/refund transaction rather than changing its stock lines.")

            val customer = dao.getCustomer(draft.customerId) ?: error("Customer not found")
            val existing = if (draft.id > 0) dao.getSale(draft.id) else null
            val invoiceNo = when {
                draft.manualInvoiceNo != null -> normalizeManualInvoiceNumber(draft.manualInvoiceNo)
                existing != null && existing.customerId == draft.customerId && existing.saleDateEpochDay == draft.dateEpochDay -> existing.invoiceNo
                else -> generateInvoiceNumber(customer, draft.dateEpochDay)
            }
            require(dao.getSales().none {
                it.id != draft.id && it.invoiceNo.equals(invoiceNo, ignoreCase = true)
            }) { "Invoice number $invoiceNo already exists" }
            savedInvoiceNo = invoiceNo

            if (existing != null && (existing.invoiceNo != invoiceNo || existing.saleDateEpochDay != draft.dateEpochDay)) {
                oldDropboxInvoice = existing.saleDateEpochDay to existing.invoiceNo
                if (existing.invoiceNo != invoiceNo) oldLocalPdf = existing.pdfPath
            }
            if (existing != null) { dao.deleteAllocationsForSale(existing.id); dao.deleteSaleLinesForSale(existing.id) }

            val lineBreakdowns = draft.lines.map { line ->
                line to breakdownFromNet(line.unitNetPence * line.quantity, draft.vatType)
            }
            val totalNet = lineBreakdowns.sumOf { it.second.netPence }
            val totalVat = lineBreakdowns.sumOf { it.second.vatPence }
            val totalGross = lineBreakdowns.sumOf { it.second.grossPence }
            val totalReverse = lineBreakdowns.sumOf { it.second.reverseVatPence }
            var sale = SaleEntity(
                id = draft.id, invoiceNo = invoiceNo, saleDateEpochDay = draft.dateEpochDay,
                customerId = draft.customerId, vatType = draft.vatType, netPence = totalNet, vatPence = totalVat,
                grossPence = totalGross, reverseVatPence = totalReverse, notes = draft.notes.trim(),
                pdfPath = existing?.pdfPath, updatedAtMillis = System.currentTimeMillis()
            )
            val savedSaleId = if (existing == null) dao.insertSale(sale) else { dao.updateSale(sale); sale.id }
            sale = sale.copy(id = savedSaleId)

            val newLines = mutableListOf<SaleLineEntity>()
            lineBreakdowns.forEach { (line, breakdown) ->
                val unitGross = breakdownFromNet(line.unitNetPence, draft.vatType).grossPence
                val lineEntity = SaleLineEntity(
                    saleId = savedSaleId, item = line.item.trim(), quantity = line.quantity,
                    unitGrossPence = unitGross, lineGrossPence = breakdown.grossPence,
                    lineNetPence = breakdown.netPence, lineVatPence = breakdown.vatPence
                )
                val lineId = dao.insertSaleLine(lineEntity)
                val savedLine = lineEntity.copy(id = lineId)
                newLines += savedLine
                allocateStock(savedLine)
            }
            val business = dao.getBusiness() ?: BusinessEntity()
            val pdfPath = InvoicePdf.create(context, business, customer, sale, newLines)
            dao.updateSale(sale.copy(pdfPath = pdfPath))
            savedSaleId
        }

        oldDropboxInvoice?.let { (day, invoiceNo) ->
            DropboxDeletionQueue.enqueueSale(context, day, invoiceNo)
        }
        oldLocalPdf?.let { File(it).delete() }
        dao.getSale(saleId)?.let {
            DropboxDeletionQueue.cancelSaleDeletion(context, it.saleDateEpochDay, it.invoiceNo)
        }
        enqueueSync()
        return saleId
    }

    suspend fun deleteSale(saleId: Long) {
        val sale = dao.getSale(saleId) ?: return
        require(dao.getCreditNotesForSale(saleId).isEmpty()) {
            "This invoice has a credit note and must be retained for accounting traceability."
        }
        db.withTransaction {
            val writableDb = db.openHelper.writableDatabase
            writableDb.execSQL(
                "DELETE FROM sale_return_allocations WHERE saleReturnId IN (SELECT id FROM sale_returns WHERE saleLineId IN (SELECT id FROM sale_lines WHERE saleId = ?))",
                arrayOf(saleId)
            )
            writableDb.execSQL(
                "DELETE FROM sale_returns WHERE saleLineId IN (SELECT id FROM sale_lines WHERE saleId = ?)",
                arrayOf(saleId)
            )
            dao.deleteAllocationsForSale(saleId)
            dao.deleteSaleLinesForSale(saleId)
            writableDb.execSQL("DELETE FROM sales WHERE id = ?", arrayOf(saleId))
        }
        sale.pdfPath?.let { File(it).delete() }
        DropboxDeletionQueue.enqueueSale(context, sale.saleDateEpochDay, sale.invoiceNo)
        enqueueSync()
    }

    private fun normalizeManualInvoiceNumber(raw: String): String {
        val value = raw.trim()
        require(value.isNotBlank()) { "Enter an invoice number" }
        require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9 ._-]{0,63}"))) {
            "Invoice number can use letters, numbers, spaces, hyphens, underscores and dots only"
        }
        return value
    }

    private suspend fun generateInvoiceNumber(customer: CustomerEntity, day: Long): String {
        val customerCode = customer.invoiceCode.trim().uppercase()
        val dateCode = compactDate(day)
        val businessCode = dao.getBusiness()?.invoiceIdentifier?.trim()?.uppercase().orEmpty()

        // Continue the customer/day sequence across legacy and business-prefixed invoice formats.
        // Existing invoices are never renamed. Erroneously deleted invoices free their sequence.
        val allKnownNumbers = buildSet {
            dao.getSales().forEach { add(it.invoiceNo.trim().uppercase()) }
        }
        val escapedCustomer = Regex.escape(customerCode)
        val escapedDate = Regex.escape(dateCode)
        val legacy = Regex("^$escapedCustomer-$escapedDate-(\\d+)$", RegexOption.IGNORE_CASE)
        val prefixed = Regex("^[A-Z0-9]{2,8}\\.$escapedCustomer\\.$escapedDate\\.(\\d+)$", RegexOption.IGNORE_CASE)
        val usedSequences = allKnownNumbers.mapNotNull { number ->
            (legacy.matchEntire(number) ?: prefixed.matchEntire(number))
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
        }.toSet()

        var sequence = 1
        while (sequence < 100_000) {
            if (sequence !in usedSequences) {
                val suffix = sequence.toString().padStart(2, '0')
                return if (businessCode.isBlank()) {
                    "$customerCode-$dateCode-$suffix"
                } else {
                    "$businessCode.$customerCode.$dateCode.$suffix"
                }
            }
            sequence++
        }
        error("Unable to generate a unique invoice number")
    }

    private suspend fun allocateStock(line: SaleLineEntity) {
        val purchases = dao.getPurchases().filter { it.item.trim().equals(line.item.trim(), ignoreCase = true) && it.receivedQty > 0 }
        val allocations = dao.getSaleAllocations()
        val returnAllocations = dao.getSaleReturnAllocations()
        val allocationById = allocations.associateBy { it.id }
        val restoredByPurchase = mutableMapOf<Long, Int>()
        returnAllocations.forEach { ra -> allocationById[ra.saleAllocationId]?.purchaseId?.let { pId -> restoredByPurchase[pId] = (restoredByPurchase[pId] ?: 0) + ra.quantity } }
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


    suspend fun saveCreditNote(draft: CreditNoteDraft): Long {
        require(draft.lines.isNotEmpty()) { "Select at least one item to credit" }

        val existingCredit = draft.id.takeIf { it > 0 }?.let { dao.getCreditNote(it) }
        if (draft.id > 0) requireNotNull(existingCredit) { "Credit note not found" }
        require(existingCredit == null || existingCredit.saleId == draft.saleId) {
            "A credit note cannot be moved to a different sales invoice"
        }

        val sale = dao.getSale(draft.saleId) ?: error("Sales invoice not found")
        val saleLines = dao.getSaleLinesForSale(sale.id).associateBy { it.id }

        val oldReturns = existingCredit?.let { dao.getSaleReturnsForCreditNote(it.id) }.orEmpty()
        val oldReturnIds = oldReturns.map { it.id }.toSet()
        val allReturns = dao.getSaleReturns().filterNot { it.creditNoteId == draft.id }
        val allReturnAllocations = dao.getSaleReturnAllocations().filterNot { it.saleReturnId in oldReturnIds }
        val allAllocations = dao.getSaleAllocations()
        val usedIdentifiers = dao.getCreditNoteImeis()
            .filterNot { it.creditNoteId == draft.id }
            .map { it.identifier }
            .toSet()

        data class Prepared(
            val draft: CreditNoteLineDraft,
            val line: SaleLineEntity,
            val breakdown: VatBreakdown
        )

        val prepared = draft.lines.map { lineDraft ->
            val line = saleLines[lineDraft.saleLineId] ?: error("Credit note item does not belong to this invoice")
            val alreadyCredited = allReturns.filter { it.saleLineId == line.id }.sumOf { it.quantity }
            require(lineDraft.quantity > 0) { "Credit quantity must be greater than zero for ${line.item}" }
            require(alreadyCredited + lineDraft.quantity <= line.quantity) {
                "Credit quantity exceeds the remaining sold quantity for ${line.item}"
            }
            if (lineDraft.units.isNotEmpty()) {
                require(lineDraft.units.size == lineDraft.quantity) {
                    "Select exactly ${lineDraft.quantity} IMEI/serial number(s) for ${line.item}"
                }
                require(lineDraft.units.map { it.identifier }.distinct().size == lineDraft.units.size) {
                    "Duplicate IMEI/serial selected for ${line.item}"
                }
                require(lineDraft.units.none { it.identifier in usedIdentifiers }) {
                    "One of the selected IMEI/serial numbers has already been credited"
                }
            }

            val inferredUnitNet = breakdownFromGross(line.unitGrossPence, sale.vatType).netPence
            val breakdown = if (inferredUnitNet * line.quantity == line.lineNetPence) {
                breakdownFromNet(inferredUnitNet * lineDraft.quantity, sale.vatType)
            } else {
                breakdownFromGross(line.unitGrossPence * lineDraft.quantity, sale.vatType)
            }
            Prepared(lineDraft, line, breakdown)
        }

        val totalNet = prepared.sumOf { it.breakdown.netPence }
        val totalVat = prepared.sumOf { it.breakdown.vatPence }
        val totalGross = prepared.sumOf { it.breakdown.grossPence }
        val totalReverse = prepared.sumOf { it.breakdown.reverseVatPence }
        val creditNo = existingCredit?.creditNoteNo ?: generateCreditNoteNumber()

        val creditId = db.withTransaction {
            val headerId = if (existingCredit != null) {
                dao.deleteReturnAllocationsForCreditNote(existingCredit.id)
                dao.deleteCreditNoteImeisForCreditNote(existingCredit.id)
                dao.deleteSaleReturnsForCreditNote(existingCredit.id)
                dao.updateCreditNote(
                    existingCredit.copy(
                        creditDateEpochDay = draft.dateEpochDay,
                        netPence = totalNet,
                        vatPence = totalVat,
                        grossPence = totalGross,
                        reverseVatPence = totalReverse,
                        notes = draft.notes.trim(),
                        updatedAtMillis = System.currentTimeMillis()
                    )
                )
                existingCredit.id
            } else {
                dao.insertCreditNote(
                    CreditNoteEntity(
                        creditNoteNo = creditNo,
                        saleId = sale.id,
                        creditDateEpochDay = draft.dateEpochDay,
                        netPence = totalNet,
                        vatPence = totalVat,
                        grossPence = totalGross,
                        reverseVatPence = totalReverse,
                        notes = draft.notes.trim()
                    )
                )
            }

            prepared.forEach { item ->
                val returnId = dao.insertSaleReturn(
                    SaleReturnEntity(
                        saleLineId = item.line.id,
                        creditNoteId = headerId,
                        returnDateEpochDay = draft.dateEpochDay,
                        quantity = item.draft.quantity,
                        refundGrossPence = item.breakdown.grossPence,
                        refundNetPence = item.breakdown.netPence,
                        refundVatPence = item.breakdown.vatPence,
                        restock = item.draft.restock,
                        notes = draft.notes.trim()
                    )
                )

                item.draft.units.forEach { unit ->
                    dao.insertCreditNoteImei(
                        CreditNoteImeiEntity(
                            creditNoteId = headerId,
                            saleReturnId = returnId,
                            saleLineId = item.line.id,
                            identifier = unit.identifier,
                            purchaseId = unit.purchaseId
                        )
                    )
                }

                if (item.draft.restock) {
                    val lineAllocations = allAllocations.filter { it.saleLineId == item.line.id }.sortedByDescending { it.id }
                    val restoredBefore = allReturnAllocations.groupBy { it.saleAllocationId }
                        .mapValues { (_, rows) -> rows.sumOf { it.quantity } }
                        .toMutableMap()

                    suspend fun restoreFrom(candidates: List<SaleAllocationEntity>, count: Int): Int {
                        var remaining = count
                        candidates.forEach { allocation ->
                            if (remaining <= 0) return@forEach
                            val available = allocation.quantity - (restoredBefore[allocation.id] ?: 0)
                            if (available <= 0) return@forEach
                            val take = minOf(remaining, available)
                            dao.insertSaleReturnAllocation(
                                SaleReturnAllocationEntity(
                                    saleReturnId = returnId,
                                    saleAllocationId = allocation.id,
                                    quantity = take
                                )
                            )
                            restoredBefore[allocation.id] = (restoredBefore[allocation.id] ?: 0) + take
                            remaining -= take
                        }
                        return remaining
                    }

                    var genericCount = item.draft.quantity
                    item.draft.units.filter { it.purchaseId > 0 }.groupBy { it.purchaseId }.forEach { (purchaseId, units) ->
                        val remaining = restoreFrom(lineAllocations.filter { it.purchaseId == purchaseId }, units.size)
                        require(remaining == 0) { "Unable to restore the selected IMEI/serial to its original stock lot" }
                        genericCount -= units.size
                    }
                    if (genericCount > 0) {
                        val remaining = restoreFrom(lineAllocations, genericCount)
                        require(remaining == 0) { "Unable to restore all credited stock to inventory" }
                    }
                }
            }
            headerId
        }

        val credit = dao.getCreditNote(creditId) ?: error("Credit note was not saved")
        val customer = dao.getCustomer(sale.customerId) ?: error("Customer not found")
        val business = dao.getBusiness() ?: BusinessEntity()
        val returns = dao.getSaleReturnsForCreditNote(creditId)
        val imeis = dao.getCreditNoteImeisForCreditNote(creditId).groupBy { it.saleReturnId }
        val pdfLines = returns.mapNotNull { returned ->
            val line = saleLines[returned.saleLineId] ?: return@mapNotNull null
            CreditNotePdfLine(
                item = line.item,
                quantity = returned.quantity,
                netPence = returned.refundNetPence,
                vatPence = returned.refundVatPence,
                grossPence = returned.refundGrossPence,
                restock = returned.restock,
                identifiers = imeis[returned.id].orEmpty().map { it.identifier }
            )
        }
        val pdfPath = CreditNotePdf.create(context, business, customer, sale, credit, pdfLines)
        dao.updateCreditNote(credit.copy(pdfPath = pdfPath, updatedAtMillis = System.currentTimeMillis()))

        if (existingCredit != null && existingCredit.creditDateEpochDay != draft.dateEpochDay) {
            DropboxDeletionQueue.enqueueCreditNote(
                context = context,
                creditDateEpochDay = existingCredit.creditDateEpochDay,
                creditNoteNo = existingCredit.creditNoteNo
            )
        }

        enqueueSync()
        return creditId
    }

    suspend fun deleteCreditNote(creditNoteId: Long) {
        val credit = dao.getCreditNote(creditNoteId) ?: return
        val creditReturns = dao.getSaleReturnsForCreditNote(creditNoteId)
        val creditReturnIds = creditReturns.map { it.id }.toSet()
        val allReturnAllocations = dao.getSaleReturnAllocations()
        val removedReturnAllocations = allReturnAllocations.filter { it.saleReturnId in creditReturnIds }
        val allSaleAllocations = dao.getSaleAllocations()
        val allocationById = allSaleAllocations.associateBy { it.id }

        // Removing a restock must not make a purchase lot negative. If it would, stock restored
        // by this credit note has subsequently been consumed by another sale.
        val affectedPurchaseIds = removedReturnAllocations.mapNotNull {
            allocationById[it.saleAllocationId]?.purchaseId
        }.toSet()
        if (affectedPurchaseIds.isNotEmpty()) {
            val soldByPurchase = allSaleAllocations.groupBy { it.purchaseId }
                .mapValues { (_, rows) -> rows.sumOf { it.quantity } }
            val restoredWithoutThisCredit = allReturnAllocations
                .filterNot { it.saleReturnId in creditReturnIds }
                .mapNotNull { ra ->
                    allocationById[ra.saleAllocationId]?.purchaseId?.let { it to ra.quantity }
                }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, quantities) -> quantities.sum() }
            val purchaseMap = dao.getPurchases().associateBy { it.id }

            affectedPurchaseIds.forEach { purchaseId ->
                val purchase = purchaseMap[purchaseId] ?: return@forEach
                val availableAfterDelete =
                    purchase.receivedQty - purchase.returnedQty -
                        (soldByPurchase[purchaseId] ?: 0) +
                        (restoredWithoutThisCredit[purchaseId] ?: 0)
                require(availableAfterDelete >= 0) {
                    "This credit note cannot be deleted because returned stock has since been sold. Correct the later sale first."
                }
            }
        }

        db.withTransaction {
            dao.deleteReturnAllocationsForCreditNote(creditNoteId)
            dao.deleteCreditNoteImeisForCreditNote(creditNoteId)
            dao.deleteSaleReturnsForCreditNote(creditNoteId)
            dao.deleteCreditNote(credit)
        }

        credit.pdfPath?.let { File(it).delete() }
        DropboxDeletionQueue.enqueueCreditNote(
            context = context,
            creditDateEpochDay = credit.creditDateEpochDay,
            creditNoteNo = credit.creditNoteNo
        )
        enqueueSync()
    }

    private suspend fun generateCreditNoteNumber(): String {
        val used = dao.getCreditNotes().mapNotNull { note ->
            Regex("^CN-(\\d+)$", RegexOption.IGNORE_CASE)
                .matchEntire(note.creditNoteNo.trim())
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
        }.toSet()
        var sequence = 1
        while (sequence < 1_000_000) {
            if (sequence !in used) return "CN-${sequence.toString().padStart(6, '0')}"
            sequence++
        }
        error("Unable to generate a unique credit note number")
    }

    suspend fun recordCustomerReturn(saleLineId: Long, day: Long, quantity: Int, restock: Boolean, notes: String) = db.withTransaction {
        val lines = dao.getSaleLines()
        val line = lines.firstOrNull { it.id == saleLineId } ?: error("Sale line not found")
        val sale = dao.getSale(line.saleId) ?: error("Sale not found")
        val existingReturns = dao.getSaleReturns().filter { it.saleLineId == saleLineId }.sumOf { it.quantity }
        require(quantity > 0 && existingReturns + quantity <= line.quantity) { "Return quantity exceeds quantity sold" }

        // New net-priced lines have lineNet = per-unit net × quantity. Use that exact net basis for
        // refunds. If an older historical line does not match that pattern, retain the legacy gross
        // calculation so existing invoices/returns continue to behave as they did before this update.
        val inferredUnitNet = breakdownFromGross(line.unitGrossPence, sale.vatType).netPence
        val breakdown = if (inferredUnitNet * line.quantity == line.lineNetPence) {
            breakdownFromNet(inferredUnitNet * quantity, sale.vatType)
        } else {
            breakdownFromGross(line.unitGrossPence * quantity, sale.vatType)
        }

        val returnId = dao.insertSaleReturn(SaleReturnEntity(
            saleLineId = saleLineId, returnDateEpochDay = day, quantity = quantity,
            refundGrossPence = breakdown.grossPence, refundNetPence = breakdown.netPence,
            refundVatPence = breakdown.vatPence, restock = restock, notes = notes.trim()
        ))
        if (restock) {
            val allocations = dao.getSaleAllocations().filter { it.saleLineId == saleLineId }.sortedByDescending { it.id }
            val alreadyRestored = dao.getSaleReturnAllocations().groupBy { it.saleAllocationId }.mapValues { it.value.sumOf { r -> r.quantity } }
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
        creditNotes = dao.getCreditNotes(),
        saleReturns = dao.getSaleReturns(),
        returnAllocations = dao.getSaleReturnAllocations(),
        creditNoteImeis = dao.getCreditNoteImeis(),
        expenses = dao.getExpenses()
    )

    fun enqueueSync() = DropboxSyncWorker.enqueue(context)

    private fun defaultPeriodName(start: Long, end: Long): String {
        val s = LocalDate.ofEpochDay(start)
        val e = LocalDate.ofEpochDay(end)
        return if (s.year == e.year) s.year.toString() else "${s.year}-${e.year}"
    }
}
