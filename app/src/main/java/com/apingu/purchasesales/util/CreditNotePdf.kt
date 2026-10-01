package com.apingu.purchasesales.util

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.apingu.purchasesales.data.BusinessEntity
import com.apingu.purchasesales.data.CreditNoteEntity
import com.apingu.purchasesales.data.CustomerEntity
import com.apingu.purchasesales.data.SaleEntity
import java.io.File

data class CreditNotePdfLine(
    val item: String,
    val quantity: Int,
    val netPence: Long,
    val vatPence: Long,
    val grossPence: Long,
    val restock: Boolean,
    val identifiers: List<String> = emptyList()
)

object CreditNotePdf {
    fun create(
        context: Context,
        business: BusinessEntity,
        customer: CustomerEntity,
        sale: SaleEntity,
        credit: CreditNoteEntity,
        lines: List<CreditNotePdfLine>
    ): String {
        val dir = File(context.filesDir, "credit_notes").apply { mkdirs() }
        val file = File(dir, "${credit.creditNoteNo}.pdf")
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

        fun newPage(): Float {
            doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNo).create())
            canvas = page.canvas
            return 55f
        }

        text(business.businessName.ifBlank { "Credit Note" }, 40f, 48f, 19f, true)
        text("CREDIT NOTE", 410f, 48f, 17f, true)
        text("Seller", 40f, 70f, 9f, true)
        var sellerY = 84f
        business.address.lines().map { it.trim() }.filter { it.isNotBlank() }.take(4).forEach {
            text(it.take(65), 40f, sellerY, 9f); sellerY += 13f
        }
        if (business.vatNumber.isNotBlank()) { text("VAT No: ${business.vatNumber}", 40f, sellerY, 9f); sellerY += 13f }
        if (business.companyNumber.isNotBlank()) { text("Company No: ${business.companyNumber}", 40f, sellerY, 9f); sellerY += 13f }

        text("Credit note: ${credit.creditNoteNo}", 365f, 76f, 10f, true)
        text("Date: ${displayDate(credit.creditDateEpochDay)}", 365f, 93f, 10f)
        text("Original invoice: ${sale.invoiceNo}", 365f, 110f, 10f)

        var billY = maxOf(155f, sellerY + 10f)
        text("Credit to", 40f, billY, 10f, true); billY += 17f
        text(customer.companyName, 40f, billY, 11f, true); billY += 15f
        customer.address.lines().map { it.trim() }.filter { it.isNotBlank() }.take(4).forEach {
            text(it.take(65), 40f, billY, 9f); billY += 13f
        }
        if (customer.vatNumber.isNotBlank()) { text("VAT No: ${customer.vatNumber}", 40f, billY, 9f); billY += 13f }

        var y = maxOf(270f, billY + 24f)
        canvas.drawLine(40f, y, 555f, y, paint); y += 18f
        text("Item", 40f, y, 9f, true)
        text("Qty", 330f, y, 9f, true)
        text("Net", 405f, y, 9f, true)
        text("Gross", 495f, y, 9f, true)
        y += 9f
        canvas.drawLine(40f, y, 555f, y, paint); y += 20f

        lines.forEachIndexed { index, line ->
            if (y + 30f + line.identifiers.size * 12f > 700f) y = newPage()
            text(line.item.take(48), 40f, y, 9f, true)
            text(line.quantity.toString(), 338f, y, 9f)
            text(formatMoney(line.netPence), 405f, y, 9f)
            text(formatMoney(line.grossPence), 495f, y, 9f)
            y += 16f
            if (line.identifiers.isNotEmpty()) {
                text("IMEI / serial:", 48f, y, 8f, true); y += 11f
                line.identifiers.forEach { id -> text(id.take(72), 58f, y, 8f); y += 11f }
            }
            text("Return to inventory: ${if (line.restock) "Yes" else "No"}", 48f, y, 8f)
            y += 12f
            if (index < lines.lastIndex) { canvas.drawLine(40f, y, 555f, y, paint); y += 12f }
        }

        if (y > 650f) y = newPage()
        y += 14f
        canvas.drawLine(330f, y, 555f, y, paint); y += 20f
        text("Net credit", 375f, y, 10f); text(formatMoney(credit.netPence), 490f, y, 10f, true); y += 18f
        text("VAT credit", 375f, y, 10f); text(formatMoney(credit.vatPence), 490f, y, 10f, true); y += 18f
        text("TOTAL CREDIT", 375f, y, 11f, true); text(formatMoney(credit.grossPence), 490f, y, 11f, true); y += 24f

        if (sale.vatType == VatTypes.REVERSE) {
            text("Reverse-charge sale: customer VAT charged was £0.00.", 40f, y, 9f); y += 16f
        } else if (sale.vatType == VatTypes.NO_VAT) {
            text("No VAT was charged on the original invoice.", 40f, y, 9f); y += 16f
        }
        if (credit.notes.isNotBlank()) text("Notes: ${credit.notes}".take(95), 40f, y + 8f, 8f)

        doc.finishPage(page)
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file.absolutePath
    }
}
