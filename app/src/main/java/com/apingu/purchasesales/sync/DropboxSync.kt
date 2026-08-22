package com.apingu.purchasesales.sync

import android.content.Context
import androidx.work.*
import com.apingu.purchasesales.data.AppDatabase
import com.apingu.purchasesales.data.BusinessEntity
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

            val purchases = dao.getPurchases()
            val customers = dao.getCustomers()
            val sales = dao.getSales()
            val saleLines = dao.getSaleLines()
            val allocations = dao.getSaleAllocations()
            val returns = dao.getSaleReturns()
            val returnAllocations = dao.getSaleReturnAllocations()
            val expenses = dao.getExpenses()
            val start = business.accountingStartEpochDay.takeIf { it > 0 } ?: LocalDate.now().withDayOfYear(1).toEpochDay()
            val end = business.accountingEndEpochDay.takeIf { it >= start } ?: LocalDate.now().withMonth(12).withDayOfMonth(31).toEpochDay()
            val summary = buildFinanceSummary(start, end, purchases, sales, saleLines, allocations, returns, returnAllocations, expenses)
            val inv = buildInventory(purchases, allocations, returnAllocations)
            val label = accountingLabel(business)
            val root = "/" + business.dropboxRoot.trim().trim('/').ifBlank { "Purchase-Sales-Software" }

            val recoveryDir = File(applicationContext.filesDir, "recovery").apply { mkdirs() }
            val allPurchases = File(recoveryDir, "PURCHASES.txt").apply { writeText(purchasesDump(purchases)) }
            val pending = File(recoveryDir, "PENDING_PURCHASES.txt").apply { writeText(pendingDump(purchases)) }
            val inventory = File(recoveryDir, "INVENTORY.txt").apply { writeText(inventoryDump(inv)) }
            DropboxApi.upload(token, "$root/Recovery/PURCHASES.txt", allPurchases.readBytes())
            DropboxApi.upload(token, "$root/Recovery/PENDING_PURCHASES.txt", pending.readBytes())
            DropboxApi.upload(token, "$root/Recovery/INVENTORY.txt", inventory.readBytes())

            val exportDir = File(applicationContext.filesDir, "exports").apply { mkdirs() }
            val xlsx = File(exportDir, "Business_Records_${label}.xlsx")
            XlsxExport.create(
                xlsx,
                purchases.filter { it.purchaseDateEpochDay in start..end },
                sales.filter { it.saleDateEpochDay in start..end },
                saleLines,
                returns.filter { it.returnDateEpochDay in start..end },
                customers,
                expenses.filter { it.expenseDateEpochDay in start..end },
                summary
            )
            DropboxApi.upload(token, "$root/Excel/$label/${xlsx.name}", xlsx.readBytes())

            purchases.forEach { p -> p.invoicePath?.let { path ->
                val f = File(path); if (f.exists()) DropboxApi.upload(token, "$root/Purchases/$label/Invoices/PUR_${p.id}_${safe(p.item)}.${f.extension.ifBlank { "bin" }}", f.readBytes())
            } }
            sales.forEach { s -> s.pdfPath?.let { path ->
                val f = File(path); if (f.exists()) DropboxApi.upload(token, "$root/Sales/$label/Invoices/${s.invoiceNo}.pdf", f.readBytes())
            } }
            expenses.forEach { e -> e.attachmentPath?.let { path ->
                val f = File(path); if (f.exists()) DropboxApi.upload(token, "$root/Expenses/$label/Receipts/EXP_${e.id}_${safe(e.details)}.${f.extension.ifBlank { "bin" }}", f.readBytes())
            } }
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

    private fun readResponse(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }
    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
    private fun json(v: String) = v.replace("\\", "\\\\").replace("\"", "\\\"")
}

private fun accountingLabel(b: BusinessEntity): String {
    val start = runCatching { LocalDate.ofEpochDay(b.accountingStartEpochDay) }.getOrElse { LocalDate.now() }
    val end = runCatching { LocalDate.ofEpochDay(b.accountingEndEpochDay) }.getOrElse { start }
    return if (start.year == end.year) start.year.toString() else "${start.year}-${end.year}"
}
private fun safe(value: String) = value.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(48)
private fun purchasesDump(items: List<com.apingu.purchasesales.data.PurchaseEntity>) = buildString {
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
private fun pendingDump(items: List<com.apingu.purchasesales.data.PurchaseEntity>) = purchasesDump(items.filter { it.status in setOf("RECEIPT_PENDING", "PARTIALLY_RECEIVED", "REFUND_PENDING", "RETURNED") })
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
