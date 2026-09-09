package com.apingu.purchasesales.sync

import android.content.Context

data class PendingSaleInvoiceDeletion(
    val saleDateEpochDay: Long,
    val invoiceNo: String
)

/**
 * Persists Dropbox sales-invoice deletions until the next successful sync. This means a sales
 * invoice can be deleted while Dropbox is disabled/offline and the stale cloud copy will still be
 * removed if sync is enabled again later.
 */
object DropboxDeletionQueue {
    private const val PREFS = "dropbox_deletion_queue"
    private const val KEY_SALES = "sales_invoice_deletions"

    @Synchronized
    fun enqueueSale(context: Context, saleDateEpochDay: Long, invoiceNo: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val values = prefs.getStringSet(KEY_SALES, emptySet()).orEmpty().toMutableSet()
        values += encode(PendingSaleInvoiceDeletion(saleDateEpochDay, invoiceNo))
        prefs.edit().putStringSet(KEY_SALES, values).apply()
    }

    fun pendingSales(context: Context): List<PendingSaleInvoiceDeletion> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_SALES, emptySet()).orEmpty().mapNotNull(::decode)
    }

    @Synchronized
    fun removeSales(context: Context, completed: Collection<PendingSaleInvoiceDeletion>) {
        if (completed.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val values = prefs.getStringSet(KEY_SALES, emptySet()).orEmpty().toMutableSet()
        completed.forEach { values.remove(encode(it)) }
        prefs.edit().putStringSet(KEY_SALES, values).apply()
    }

    private fun encode(value: PendingSaleInvoiceDeletion): String =
        "${value.saleDateEpochDay}\t${value.invoiceNo}"

    private fun decode(value: String): PendingSaleInvoiceDeletion? {
        val split = value.indexOf('\t')
        if (split <= 0 || split >= value.lastIndex) return null
        val day = value.substring(0, split).toLongOrNull() ?: return null
        val invoiceNo = value.substring(split + 1)
        if (invoiceNo.isBlank()) return null
        return PendingSaleInvoiceDeletion(day, invoiceNo)
    }
}
