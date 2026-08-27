package com.apingu.purchasesales.util

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.apingu.purchasesales.data.BusinessEntity
import com.apingu.purchasesales.data.CustomerEntity
import com.apingu.purchasesales.data.SaleEntity
import com.apingu.purchasesales.data.SaleLineEntity
import java.io.File

/**
 * Sales invoice renderer that preserves the existing PDF format/business details and additionally
 * prints purchase-line traceability notes (for example IMEI or serial numbers) beneath the sale item
 * that consumed that stock.
 */
object TraceableInvoicePdf {
    fun create(
        context: Context,
        business: BusinessEntity,
        customer: CustomerEntity,
        sale: SaleEntity,
        lines: List<SaleLineEntity>,
        sourceNotes: Map<Long, String>
    ): String {
        val dir = File(context.filesDir, "sales_invoices").apply { mkdirs() }
        val file = File(dir, "${sale.invoiceNo}.pdf")
        val doc = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNo).create())
        var canvas = page.canvas

        fun text(value: String, x: Float, y: Float, size: Float = 10f, bold: Boolean = false) {
            paint.textSize = size
            paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            canvas.drawText(value, x, y, paint)
        }

        fun startNewPage(): Float {
            doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNo).create())
            canvas = page.canvas
            paint.color = android.graphics.Color.BLACK
            return 55f
        }

        fun wrap(value: String, maxChars: Int): List<String> {
            val result = mutableListOf<String>()
            value.lines().forEach { rawLine ->
                val words = rawLine.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                if (words.isEmpty()) return@forEach
                var current = ""
                words.forEach { word ->
                    if (current.isBlank()) current = word
                    else if (current.length + 1 + word.length <= maxChars) current += " $word"
                    else {
                        result += current
                        current = word
                    }
                }
                if (current.isNotBlank()) result += current
            }
            return result
        }

        text(business.businessName.ifBlank { "Sales Invoice" }, 40f, 48f, 19f, true)
        text("INVOICE", 455f, 48f, 18f, true)
        text("Seller", 40f, 68f, 9f, true)
        var sellerY = 83f
        business.address.lines().map { it.trim() }.filter { it.isNotBlank() }.take(4).forEach {
            text(it.take(65), 40f, sellerY, 9f); sellerY += 13f
        }
        if (business.email.isNotBlank()) { text("Email: ${business.email}".take(75), 40f, sellerY, 9f); sellerY += 13f }
        if (business.phone.isNotBlank()) { text("Phone: ${business.phone}".take(75), 40f, sellerY, 9f); sellerY += 13f }
        if (business.vatNumber.isNotBlank()) { text("VAT No: ${business.vatNumber}", 40f, sellerY, 9f); sellerY += 13f }
        if (business.companyNumber.isNotBlank()) { text("Company No: ${business.companyNumber}", 40f, sellerY, 9f); sellerY += 13f }

        text("Invoice: ${sale.invoiceNo}", 395f, 76f, 10f, true)
        text("Date: ${displayDate(sale.saleDateEpochDay)}", 395f, 93f, 10f)

        val noticeY = maxOf(150f, sellerY + 5f)
        if (sale.vatType == VatTypes.REVERSE) {
            paint.color = android.graphics.Color.RED
            text("THIS INVOICE IS SUBJECT TO REVERSE CHARGE VAT", 40f, noticeY, 11f, true)
            paint.color = android.graphics.Color.BLACK
        }

        var billY = noticeY + if (sale.vatType == VatTypes.REVERSE) 28f else 12f
        text("Bill to", 40f, billY, 10f, true); billY += 17f
        text(customer.companyName, 40f, billY, 11f, true); billY += 15f
        customer.address.lines().map { it.trim() }.filter { it.isNotBlank() }.take(4).forEach {
            text(it.take(65), 40f, billY, 9f); billY += 13f
        }
        if (customer.email.isNotBlank()) { text("Email: ${customer.email}".take(75), 40f, billY, 9f); billY += 13f }
        if (customer.vatNumber.isNotBlank()) { text("VAT No: ${customer.vatNumber}", 40f, billY, 9f); billY += 13f }
        if (customer.companyNumber.isNotBlank()) { text("Company No: ${customer.companyNumber}", 40f, billY, 9f); billY += 13f }

        var y = maxOf(285f, billY + 22f)
        paint.strokeWidth = 1f
        canvas.drawLine(40f, y, 555f, y, paint); y += 18f
        text("Item", 40f, y, 9f, true)
        text("Qty", 330f, y, 9f, true)
        text("Unit Gross", 385f, y, 9f, true)
        text("Gross", 490f, y, 9f, true)
        y += 9f
        canvas.drawLine(40f, y, 555f, y, paint); y += 20f

        lines.forEach { line ->
            val noteLines = sourceNotes[line.id].orEmpty().let { if (it.isBlank()) emptyList() else wrap(it, 82) }
            val requiredHeight = 22f + if (noteLines.isEmpty()) 0f else 18f + noteLines.size * 12f
            if (y + requiredHeight > 700f) y = startNewPage()

            text(line.item.take(48), 40f, y, 9f, true)
            text(line.quantity.toString(), 338f, y, 9f)
            text(formatMoney(line.unitGrossPence), 385f, y, 9f)
            text(formatMoney(line.lineGrossPence), 490f, y, 9f)
            y += 18f

            if (noteLines.isNotEmpty()) {
                text("Item details / IMEI / serial:", 48f, y, 8f, true)
                y += 12f
                noteLines.forEach { noteLine ->
                    text(noteLine.take(92), 58f, y, 8f)
                    y += 12f
                }
            }
            y += 5f
        }

        if (y > 650f) y = startNewPage()
        y += 14f
        canvas.drawLine(330f, y, 555f, y, paint); y += 20f
        text("Net", 390f, y, 10f); text(formatMoney(sale.netPence), 490f, y, 10f, true); y += 18f
        text("VAT", 390f, y, 10f); text(formatMoney(sale.vatPence), 490f, y, 10f, true); y += 18f
        text("TOTAL", 390f, y, 12f, true); text(formatMoney(sale.grossPence), 490f, y, 12f, true); y += 26f

        when (sale.vatType) {
            VatTypes.REVERSE -> {
                paint.color = android.graphics.Color.RED
                text("Reverse charge applies - customer to account for VAT. VAT charged: £0.00", 40f, y, 9f, true)
                paint.color = android.graphics.Color.BLACK
            }
            VatTypes.NO_VAT -> text("No VAT charged on this invoice.", 40f, y, 9f)
        }

        y += 22f
        if (business.bankDetails.isNotBlank()) {
            text("Payment details", 40f, y, 9f, true); y += 14f
            business.bankDetails.lines().filter { it.isNotBlank() }.take(4).forEach {
                text(it.take(80), 40f, y, 8f); y += 12f
            }
        }
        if (business.invoiceTerms.isNotBlank()) { y += 8f; text("Terms: ${business.invoiceTerms}".take(95), 40f, y, 8f) }
        if (business.invoiceFooter.isNotBlank()) text(business.invoiceFooter.take(95), 40f, 810f, 7f)

        doc.finishPage(page)
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file.absolutePath
    }
}
