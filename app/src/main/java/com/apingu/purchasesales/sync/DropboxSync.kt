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
            val customers = dao.getCustomers()
            val sales = dao.getSales()
            val saleLines = dao.getSaleLines()
            val allocations = dao.getSaleAllocations()
            val returns = dao.getSaleReturns()
            val returnAllocations = dao.getSaleReturnAllocations()
            val expenses = dao.getExpenses()
            val inv = buildInventory(purchases, allocations, returnAllocations)
            val root = "/" + business.dropboxRoot.trim().trim('/').ifBlank { "Purchase-Sales-Software" }

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
                    expenses
                )

                val xlsx = File(exportDir, "Business_Records_${safePeriod(period)}.xlsx")
                XlsxExport.create(
                    xlsx,
                    periodPurchases.filter { !isCancelledAndFullyRefundedPurchase(it) },
                    periodSales,
                    saleLines,
                    periodReturns,
                    customers,
                    periodExpenses,
                    summary
                )
                DropboxApi.upload(token, "$periodRoot/Excel/${xlsx.name}", xlsx.readBytes())

                val purchaseOrderGroups = periodPurchases.groupBy { p -> p.purchaseOrderId.takeIf { it > 0 } ?: -p.id }
                purchaseOrderGroups.values.forEach { lines ->
                    val voidedOrder = isFullyRefundedOrderForDropbox(lines)
                    lines.forEach { p ->
                        p.invoicePath?.let { path ->
                            val f = File(path)
                            val remotePath = "$periodRoot/Purchases/Invoices/PUR_${p.id}_${safe(p.item)}.${f.extension.ifBlank { "bin" }}"
                            if (voidedOrder) DropboxApi.deleteIfExists(token, remotePath)
                            else if (f.exists()) DropboxApi.upload(token, remotePath, f.readBytes())
                        }
                    }
                }

                periodSales.forEach { s ->
                    s.pdfPath?.let { path ->
                        val f = File(path)
                        if (f.exists()) DropboxApi.upload(token, "$periodRoot/Sales/Invoices/${s.invoiceNo}.pdf", f.readBytes())
                    }
                }

                periodExpenses.forEach { e ->
                    e.attachmentPath?.let { path ->
                        val f = File(path)
                        if (f.exists()) DropboxApi.upload(token, "$periodRoot/Expenses/Receipts/EXP_${e.id}_${safe(e.details)}.${f.extension.ifBlank { "bin" }}", f.readBytes())
                    }
                }
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
    private fun json(v: String) = v.replace("\\", "\\\\").replace("\"", "\\\"")
}

private fun safePeriod(period: AccountingPeriodEntity): String {
    val name = safe(period.name).ifBlank { "Accounting_Period" }
    return name
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
private fun pendingDump(items: List<PurchaseEntity>) = purchasesDump(items.filter { it.status in setOf("RECEIPT_PENDING", "PARTIALLY_RECEIVED", "REFUND_PENDING", "RETURNED") })
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
