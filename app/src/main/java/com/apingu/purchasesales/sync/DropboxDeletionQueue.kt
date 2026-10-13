package com.apingu.purchasesales.sync

import android.content.Context

data class PendingSaleInvoiceDeletion(
    val saleDateEpochDay: Long,
    val invoiceNo: String
)

data class PendingPurchaseInvoiceDeletion(
    val purchaseDateEpochDay: Long,
    val fileName: String
)

data class PendingCreditNoteDeletion(
    val creditDateEpochDay: Long,
    val creditNoteNo: String
)

/**
 * Persists Dropbox sales-invoice deletions until the next successful sync. This means a sales
 * invoice can be deleted while Dropbox is disabled/offline and the stale cloud copy will still be
 * removed if sync is enabled again later.
 */
object DropboxDeletionQueue {
    private const val PREFS = "dropbox_deletion_queue"
    private const val KEY_SALES = "sales_invoice_deletions"
    private const val KEY_PURCHASES = "purchase_invoice_deletions"
    private const val KEY_CREDIT_NOTES = "credit_note_deletions"

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

    @Synchronized
    fun enqueuePurchase(context: Context, purchaseDateEpochDay: Long, fileName: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val values = prefs.getStringSet(KEY_PURCHASES, emptySet()).orEmpty().toMutableSet()
        values += "${purchaseDateEpochDay}\t${fileName}"
        prefs.edit().putStringSet(KEY_PURCHASES, values).apply()
    }

    fun pendingPurchases(context: Context): List<PendingPurchaseInvoiceDeletion> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_PURCHASES, emptySet()).orEmpty().mapNotNull { raw ->
            val split = raw.indexOf('\t')
            if (split <= 0 || split >= raw.lastIndex) null
            else raw.substring(0, split).toLongOrNull()?.let { PendingPurchaseInvoiceDeletion(it, raw.substring(split + 1)) }
        }
    }

    @Synchronized
    fun removePurchases(context: Context, completed: Collection<PendingPurchaseInvoiceDeletion>) {
        if (completed.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val values = prefs.getStringSet(KEY_PURCHASES, emptySet()).orEmpty().toMutableSet()
        completed.forEach { values.remove("${it.purchaseDateEpochDay}\t${it.fileName}") }
        prefs.edit().putStringSet(KEY_PURCHASES, values).apply()
    }

    @Synchronized
    fun enqueueCreditNote(context: Context, creditDateEpochDay: Long, creditNoteNo: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val values = prefs.getStringSet(KEY_CREDIT_NOTES, emptySet()).orEmpty().toMutableSet()
        values += "${creditDateEpochDay}\t${creditNoteNo}"
        prefs.edit().putStringSet(KEY_CREDIT_NOTES, values).apply()
    }

    fun pendingCreditNotes(context: Context): List<PendingCreditNoteDeletion> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_CREDIT_NOTES, emptySet()).orEmpty().mapNotNull { raw ->
            val split = raw.indexOf('\t')
            if (split <= 0 || split >= raw.lastIndex) null
            else raw.substring(0, split).toLongOrNull()?.let {
                PendingCreditNoteDeletion(it, raw.substring(split + 1))
            }
        }
    }

    @Synchronized
    fun removeCreditNotes(context: Context, completed: Collection<PendingCreditNoteDeletion>) {
        if (completed.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val values = prefs.getStringSet(KEY_CREDIT_NOTES, emptySet()).orEmpty().toMutableSet()
        completed.forEach { values.remove("${it.creditDateEpochDay}\t${it.creditNoteNo}") }
        prefs.edit().putStringSet(KEY_CREDIT_NOTES, values).apply()
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
