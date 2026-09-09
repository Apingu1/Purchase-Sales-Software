package com.apingu.purchasesales.util

import android.content.Context

/** Device-persistent business preferences and invoice-number tombstones. */
object InvoiceNumberPreferences {
    private const val PREFS = "purchase_sales_business_settings"
    private const val KEY_AUTO_SALES_INVOICE_NUMBER = "auto_sales_invoice_number"
    private const val KEY_USED_SALES_INVOICE_NUMBERS = "used_sales_invoice_numbers"

    fun isAutoEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_SALES_INVOICE_NUMBER, true)

    fun setAutoEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_SALES_INVOICE_NUMBER, enabled)
            .apply()
    }

    fun wasInvoiceNumberUsed(context: Context, invoiceNo: String): Boolean {
        val key = normalize(invoiceNo)
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_USED_SALES_INVOICE_NUMBERS, emptySet())
            .orEmpty()
            .contains(key)
    }

    @Synchronized
    fun markInvoiceNumberUsed(context: Context, invoiceNo: String) {
        val key = normalize(invoiceNo)
        if (key.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val values = prefs.getStringSet(KEY_USED_SALES_INVOICE_NUMBERS, emptySet()).orEmpty().toMutableSet()
        values += key
        prefs.edit().putStringSet(KEY_USED_SALES_INVOICE_NUMBERS, values).apply()
    }

    private fun normalize(invoiceNo: String): String = invoiceNo.trim().uppercase()
}
