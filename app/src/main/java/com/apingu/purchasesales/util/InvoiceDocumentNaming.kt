package com.apingu.purchasesales.util

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Stable human-readable invoice filenames; never use the filename as the database identity. */
object InvoiceDocumentNaming {
    private val dateFormat = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.UK)

    fun supplierKey(supplier: String): String =
        supplier.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    fun baseName(dateEpochDay: Long, supplier: String, dailySequence: Int): String {
        require(dailySequence > 0) { "Purchase sequence must be positive" }
        val safeSupplier = supplier.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim().trimEnd('.')
            .take(90).ifBlank { "Supplier" }
        return "${LocalDate.ofEpochDay(dateEpochDay).format(dateFormat)} - $safeSupplier #$dailySequence"
    }

    fun invoiceFileName(dateEpochDay: Long, supplier: String, dailySequence: Int, invoiceOrdinal: Int, extension: String = "pdf"): String {
        require(invoiceOrdinal > 0) { "Invoice ordinal must be positive" }
        val safeExtension = extension.trim().lowercase(Locale.ROOT).takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "pdf"
        val suffix = if (invoiceOrdinal == 1) "" else " - Invoice $invoiceOrdinal"
        return baseName(dateEpochDay, supplier, dailySequence) + suffix + ".$safeExtension"
    }
}
