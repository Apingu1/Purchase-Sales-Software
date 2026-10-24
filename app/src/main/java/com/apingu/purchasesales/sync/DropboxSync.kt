package com.apingu.purchasesales.sync

import android.content.Context
import androidx.work.*
import com.apingu.purchasesales.data.*
import com.apingu.purchasesales.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

class DropboxSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.create(applicationContext)
            val dao = db.dao()
            val business = dao.getBusiness() ?: return@withContext Result.success()
            if (!business.dropboxAutoSync) return@withContext Result.success()
            var token = business.dropboxAccessToken.trim()
            if (business.dropboxRefreshToken.isNotBlank() && business.dropboxAppKey.isNotBlank()) {
                token = DropboxApi.refreshAccessToken(business.dropboxAppKey.trim(), business.dropboxRefreshToken.trim())
                dao.saveBusiness(business.copy(dropboxAccessToken = token))
            }
            if (token.isBlank()) return@withContext Result.success()

            val periods = dao.getAccountingPeriods()
            val purchases = dao.getPurchases()
            val orders = dao.getPurchaseOrders()
            val approvedInvoices = dao.getAllPurchaseInvoices()
            val customers = dao.getCustomers()
            val sales = dao.getSales()
            val saleLines = dao.getSaleLines()
            val allocations = dao.getSaleAllocations()
            val creditNotes = dao.getCreditNotes()
            val returns = dao.getSaleReturns()
            val returnAllocations = dao.getSaleReturnAllocations()
            val expenses = dao.getExpenses()
            val expenseInvoices = dao.getAllExpenseInvoices()
            val inv = buildInventory(purchases, allocations, returnAllocations)
            val root = "/" + business.dropboxRoot.trim().trim('/').ifBlank { "Purchase-Sales-Software" }

            // Period resets remove the former accounting folder as well as local records.
            val periodDeletes = DropboxDeletionQueue.pendingPeriods(applicationContext)
            periodDeletes.forEach { folder ->
                DropboxApi.deleteIfExists(token, "$root/Accounting Periods/$folder")
            }
            DropboxDeletionQueue.removePeriods(applicationContext, periodDeletes)

            val expenseDeletes = DropboxDeletionQueue.pendingExpenses(applicationContext)
            val removedExpenseDeletes = mutableSetOf<String>()
            expenseDeletes.forEach { raw ->
                val sep = raw.indexOf('\t')
                if (sep > 0) {
                    val date = raw.substring(0, sep).toLongOrNull()
                    val period = periods.firstOrNull {
                        date != null && date in it.startEpochDay..it.endEpochDay
                    }
                    if (period != null) {
                        DropboxApi.deleteIfExists(token,
                            "$root/Accounting Periods/${safePeriod(period)}/Expenses/Invoices/${raw.substring(sep + 1)}")
                        removedExpenseDeletes += raw
                    }
                }
            }
            DropboxDeletionQueue.removeExpenses(applicationContext, removedExpenseDeletes)

            // Apply queued sales-invoice deletions before uploading the current state. This removes
            // stale Dropbox PDFs after local invoice deletion, renumbering or accounting-period moves.
            val pendingSaleDeletes = DropboxDeletionQueue.pendingSales(applicationContext)
            val completedSaleDeletes = mutableListOf<PendingSaleInvoiceDeletion>()
            pendingSaleDeletes.forEach { pendingDelete ->
                val period = periods.firstOrNull {
                    pendingDelete.saleDateEpochDay in it.startEpochDay..it.endEpochDay
                }
                if (period != null) {
                    DropboxApi.deleteIfExists(
                        token,
                        "$root/Accounting Periods/${safePeriod(period)}/Sales/Invoices/${pendingDelete.invoiceNo}.pdf"
                    )
                    completedSaleDeletes += pendingDelete
                }
            }
            DropboxDeletionQueue.removeSales(applicationContext, completedSaleDeletes)

            val pendingPurchaseDeletes = DropboxDeletionQueue.pendingPurchases(applicationContext)
            val completedPurchaseDeletes = mutableListOf<PendingPurchaseInvoiceDeletion>()
            pendingPurchaseDeletes.forEach { pendingDelete ->
                val period = periods.firstOrNull {
                    pendingDelete.purchaseDateEpochDay in it.startEpochDay..it.endEpochDay
                }
                if (period != null) {
                    DropboxApi.deleteIfExists(
                        token,
                        "$root/Accounting Periods/${safePeriod(period)}/Purchases/Invoices/${pendingDelete.fileName}"
                    )
                    completedPurchaseDeletes += pendingDelete
                }
            }
            DropboxDeletionQueue.removePurchases(applicationContext, completedPurchaseDeletes)

            val pendingCreditDeletes = DropboxDeletionQueue.pendingCreditNotes(applicationContext)
            val completedCreditDeletes = mutableListOf<PendingCreditNoteDeletion>()
            pendingCreditDeletes.forEach { pendingDelete ->
                val period = periods.firstOrNull {
                    pendingDelete.creditDateEpochDay in it.startEpochDay..it.endEpochDay
                }
                if (period != null) {
                    DropboxApi.deleteIfExists(
                        token,
                        "$root/Accounting Periods/${safePeriod(period)}/Sales/Credit Notes/${pendingDelete.creditNoteNo}.pdf"
                    )
                    completedCreditDeletes += pendingDelete
                }
            }
            DropboxDeletionQueue.removeCreditNotes(applicationContext, completedCreditDeletes)

            // Recovery and inventory remain global/all-time by design.
            val recoveryDir = File(applicationContext.filesDir, "recovery").apply { mkdirs() }
            val allPurchases = File(recoveryDir, "PURCHASES.txt").apply { writeText(purchasesDump(purchases)) }
            val pending = File(recoveryDir, "PENDING_PURCHASES.txt").apply { writeText(pendingDump(purchases)) }
            val inventory = File(recoveryDir, "INVENTORY.txt").apply { writeText(inventoryDump(inv)) }
            DropboxApi.upload(token, "$root/Recovery/PURCHASES.txt", allPurchases.readBytes())
            DropboxApi.upload(token, "$root/Recovery/PENDING_PURCHASES.txt", pending.readBytes())
            DropboxApi.upload(token, "$root/Recovery/INVENTORY.txt", inventory.readBytes())

            val exportDir = File(applicationContext.filesDir, "exports").apply { mkdirs() }

            // Every accounting period receives its own workbook and document tree. Transaction date
            // determines the folder automatically, so purchases/sales from different periods never mix.
            periods.forEach { period ->
                val periodRoot = "$root/Accounting Periods/${safePeriod(period)}"
                val periodPurchases = purchases.filter { it.purchaseDateEpochDay in period.startEpochDay..period.endEpochDay }
                val periodSales = sales.filter { it.saleDateEpochDay in period.startEpochDay..period.endEpochDay }
                val periodReturns = returns.filter { it.returnDateEpochDay in period.startEpochDay..period.endEpochDay }
                val periodCreditNotes = creditNotes.filter { it.creditDateEpochDay in period.startEpochDay..period.endEpochDay }
                val periodExpenses = expenses.filter { it.expenseDateEpochDay in period.startEpochDay..period.endEpochDay }
                val summary = buildFinanceSummary(
                    period.startEpochDay,
                    period.endEpochDay,
                    purchases,
                    sales,
                    saleLines,
                    allocations,
                    returns,
                    returnAllocations,
                    expenses,
                    orders
                )

                val xlsx = File(exportDir, "Business_Records_${safePeriod(period)}.xlsx")
                XlsxExport.create(
                    xlsx,
                    periodPurchases.filterNot(::isVoidedPurchaseForAccounting),
                    periodSales,
                    saleLines,
                    periodReturns,
                    periodCreditNotes,
                    customers,
                    periodExpenses,
                    summary,
                    orders.filter { it.purchaseDateEpochDay in period.startEpochDay..period.endEpochDay }
                )
                DropboxApi.upload(token, "$periodRoot/Excel/${xlsx.name}", xlsx.readBytes())

                // Order-level approved documents; refunded orders retain accounting evidence.
                orders.filter { it.purchaseDateEpochDay in period.startEpochDay..period.endEpochDay }
                    .forEach { order ->
                        val documents = approvedInvoices.filter { it.purchaseOrderId == order.id }
                        documents.forEach { invoice ->
                            val file = File(invoice.filePath)
                            if (file.isFile) {
                                val name = InvoiceDocumentNaming.invoiceFileName(
                                    order.purchaseDateEpochDay, order.supplier,
                                    order.invoiceSequence.takeIf { it > 0 } ?: order.id.toInt(),
                                    invoice.invoiceOrdinal, file.extension.ifBlank { "pdf" })
                                DropboxApi.upload(token, "$periodRoot/Purchases/Invoices/$name", file.readBytes())
                            }
                        }
                        // Only retire old per-stock-line filenames after all new copies exist.
                        if (documents.isNotEmpty() && documents.all { File(it.filePath).isFile }) {
                            periodPurchases.filter { it.purchaseOrderId == order.id }.forEach { p ->
                                p.invoicePath?.let { old ->
                                    val file = File(old)
                                    DropboxApi.deleteIfExists(token,
                                        "$periodRoot/Purchases/Invoices/PUR_${p.id}_${safe(p.item)}.${file.extension.ifBlank { "bin" }}")
                                }
                            }
                        }
                    }

                periodSales.forEach { s ->
                    s.pdfPath?.let { path ->
                        val f = File(path)
                        if (f.exists()) DropboxApi.upload(token, "$periodRoot/Sales/Invoices/${s.invoiceNo}.pdf", f.readBytes())
                    }
                }

                periodCreditNotes.forEach { credit ->
                    credit.pdfPath?.let { path ->
                        val f = File(path)
                        if (f.exists()) DropboxApi.upload(token, "$periodRoot/Sales/Credit Notes/${credit.creditNoteNo}.pdf", f.readBytes())
                    }
                }

                periodExpenses.forEach { e ->
                    expenseInvoices.filter { it.expenseId == e.id }.forEach { doc ->
                        val file = File(doc.filePath)
                        if (file.isFile) DropboxApi.upload(token,
                            "$periodRoot/Expenses/Invoices/EXP_${e.id}_${doc.invoiceOrdinal}.${file.extension.ifBlank { "pdf" }}",
                            file.readBytes())
                    }
                    e.attachmentPath?.let { path ->
                        val f = File(path)
                        if (f.exists()) DropboxApi.upload(token, "$periodRoot/Expenses/Receipts/EXP_${e.id}_${safe(e.details)}.${f.extension.ifBlank { "bin" }}", f.readBytes())
                    }
                }
            }

            // Previous APKs used /Purchases/<year>, /Sales/<year>, /Expenses/<year> and
            // /Excel/<year>. Only remove those app-generated period subfolders after the new
            // period-aware copies have been written successfully, so old mixed copies cannot
            // remain alongside the new Accounting Periods structure.
            periods.map(::legacyPeriodLabel).distinct().forEach { label ->
                DropboxApi.deleteIfExists(token, "$root/Purchases/$label")
                DropboxApi.deleteIfExists(token, "$root/Sales/$label")
                DropboxApi.deleteIfExists(token, "$root/Expenses/$label")
                DropboxApi.deleteIfExists(token, "$root/Excel/$label")
            }

            db.close()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<DropboxSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("dropbox-auto-sync", ExistingWorkPolicy.REPLACE, request)
        }
    }
}

object DropboxApi {
    fun refreshAccessToken(appKey: String, refreshToken: String): String {
        val body = "grant_type=refresh_token&refresh_token=${enc(refreshToken)}&client_id=${enc(appKey)}"
        val conn = URL("https://api.dropboxapi.com/oauth2/token").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(body.toByteArray()) }
        val response = readResponse(conn)
        val token = Regex("\\\"access_token\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(response)?.groupValues?.get(1)
        if (token.isNullOrBlank()) error("Dropbox token refresh failed: $response")
        return token
    }

    fun upload(token: String, path: String, bytes: ByteArray) {
        val conn = URL("https://content.dropboxapi.com/2/files/upload").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.setRequestProperty("Dropbox-API-Arg", "{\"path\":\"${json(path)}\",\"mode\":\"overwrite\",\"autorename\":false,\"mute\":true}")
        conn.outputStream.use { it.write(bytes) }
        val response = readResponse(conn)
        if (conn.responseCode !in 200..299) error("Dropbox upload failed: $response")
    }

    fun deleteIfExists(token: String, path: String) {
        val conn = URL("https://api.dropboxapi.com/2/files/delete_v2").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write("{\"path\":\"${json(path)}\"}".toByteArray()) }
        val response = readResponse(conn)
        if (conn.responseCode in 200..299) return
        if (conn.responseCode == 409 && response.contains("not_found", ignoreCase = true)) return
        error("Dropbox delete failed: $response")
    }

    private fun readResponse(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }
    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
    private fun json(v: String) = buildString {
        v.forEach { ch ->
            when (ch) {
                92.toChar() -> append(92.toChar()).append(92.toChar())
                34.toChar() -> append(92.toChar()).append(34.toChar())
                else -> if (ch.code < 32 || ch.code > 126) {
                    append(92.toChar()).append("u%04x".format(ch.code))
                } else append(ch)
            }
        }
    }
}

private fun safePeriod(period: AccountingPeriodEntity): String =
    safe(period.name).ifBlank { "Accounting_Period" }

private fun legacyPeriodLabel(period: AccountingPeriodEntity): String {
    val start = LocalDate.ofEpochDay(period.startEpochDay)
    val end = LocalDate.ofEpochDay(period.endEpochDay)
    return if (start.year == end.year) start.year.toString() else "${start.year}-${end.year}"
}

private fun safe(value: String) = value.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(64)

private fun purchasesDump(items: List<PurchaseEntity>) = buildString {
    appendLine("PURCHASES RECOVERY DUMP")
    appendLine("Generated: ${LocalDate.now()}")
    appendLine("============================================================")
    items.sortedByDescending { it.id }.forEach { p ->
        appendLine("PURCHASE ID: P-${p.id.toString().padStart(6, '0')}")
        appendLine("DATE: ${editDate(p.purchaseDateEpochDay)}")
        appendLine("SUPPLIER: ${p.supplier}")
        appendLine("ITEM: ${p.item}")
        appendLine("QUANTITY: ${p.quantity}")
        appendLine("ORDER NO: ${p.orderNumber}")
        appendLine("ACCOUNT: ${p.accountUsername}")
        appendLine("GROSS: ${formatMoney(p.grossPence)}")
        appendLine("NET: ${formatMoney(p.netPence)}")
        appendLine("VAT: ${formatMoney(p.vatPence)}")
        appendLine("REVERSE VAT NOTIONAL: ${formatMoney(p.reverseVatPence)}")
        appendLine("VAT TYPE: ${p.vatType}")
        appendLine("PAYMENT: ${p.paymentMethod}")
        appendLine("STATUS: ${p.status}")
        appendLine("RECEIVED: ${p.receivedQty} | CANCELLED: ${p.cancelledQty} | RETURNED: ${p.returnedQty}")
        appendLine("REFUND EXPECTED: ${formatMoney(p.refundExpectedPence)} | REFUND RECEIVED: ${formatMoney(p.refundReceivedPence)}")
        if (p.partialRefund) {
            appendLine("PARTIAL REFUND / PRICE ADJUSTMENT: YES")
            appendLine("REFUND NET: ${formatMoney(p.refundNetPence)} | REFUND VAT: ${formatMoney(p.refundVatPence)} | REFUND TOTAL: ${formatMoney(p.refundNetPence + p.refundVatPence)}")
            appendLine("EFFECTIVE PURCHASE NET COST: ${formatMoney(effectivePurchaseNetPence(p))}")
        } else if (p.refundExpectedPence > 0) {
            appendLine("REFUND BREAKDOWN: NET ${formatMoney(p.refundNetPence)} | VAT ${formatMoney(p.refundVatPence)}")
        }
        appendLine("INVOICE ATTACHED: ${if (p.invoicePath.isNullOrBlank()) "NO" else "YES"}")
        if (p.notes.isNotBlank()) appendLine("NOTES: ${p.notes}")
        appendLine("------------------------------------------------------------")
    }
}

private fun pendingDump(items: List<PurchaseEntity>) = purchasesDump(
    items.filter { it.status in setOf("RECEIPT_PENDING", "PARTIALLY_RECEIVED", "REFUND_PENDING", "RETURNED") }
)

private fun inventoryDump(items: List<InventoryRow>) = buildString {
    appendLine("INVENTORY RECOVERY DUMP")
    appendLine("Generated: ${LocalDate.now()}")
    appendLine("============================================================")
    items.forEach { i ->
        appendLine(i.item)
        appendLine("Purchased: ${i.purchased}")
        appendLine("Received: ${i.received}")
        appendLine("Sold: ${i.sold}")
        appendLine("Returned to supplier: ${i.supplierReturned}")
        appendLine("Customer returns restocked: ${i.customerRestocked}")
        appendLine("Available: ${i.available}")
        appendLine("Inventory net cost: ${formatMoney(i.inventoryNetCostPence)}")
        appendLine("------------------------------------------------------------")
    }
}
