package com.apingu.purchasesales.util

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.apingu.purchasesales.data.*
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DocumentStore {
    fun copyIntoApp(context: Context, uri: Uri, prefix: String): String {
        val resolver = context.contentResolver
        val type = resolver.getType(uri).orEmpty()
        val ext = when {
            type.contains("pdf") -> ".pdf"
            type.contains("png") -> ".png"
            type.contains("jpeg") || type.contains("jpg") -> ".jpg"
            else -> ".bin"
        }
        val dir = File(context.filesDir, "documents").apply { mkdirs() }
        val out = File(dir, "${prefix}_${System.currentTimeMillis()}$ext")
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to read selected document" }
            out.outputStream().use { input.copyTo(it) }
        }
        return out.absolutePath
    }

    fun exportAllDocuments(context: Context, treeUri: Uri, purchases: List<PurchaseEntity>, sales: List<SaleEntity>, expenses: List<ExpenseEntity>): Int {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return 0
        val pDir = root.findFile("Purchases") ?: root.createDirectory("Purchases")
        val sDir = root.findFile("Sales") ?: root.createDirectory("Sales")
        val eDir = root.findFile("Expenses") ?: root.createDirectory("Expenses")
        var count = 0
        purchases.forEach { p -> p.invoicePath?.let { if (copyFile(context, File(it), pDir, "PUR_${p.id}_${safe(p.item)}")) count++ } }
        sales.forEach { s -> s.pdfPath?.let { if (copyFile(context, File(it), sDir, "${s.invoiceNo}.pdf")) count++ } }
        expenses.forEach { e -> e.attachmentPath?.let { if (copyFile(context, File(it), eDir, "EXP_${e.id}_${safe(e.details)}")) count++ } }
        return count
    }

    private fun copyFile(context: Context, source: File, dir: DocumentFile?, preferredName: String): Boolean {
        if (!source.exists() || dir == null) return false
        val ext = source.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
        val name = if (preferredName.lowercase().endsWith(ext.lowercase())) preferredName else preferredName + ext
        dir.findFile(name)?.delete()
        val mime = when (source.extension.lowercase()) { "pdf" -> "application/pdf"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; else -> "application/octet-stream" }
        val target = dir.createFile(mime, name) ?: return false
        context.contentResolver.openOutputStream(target.uri).use { out -> source.inputStream().use { it.copyTo(requireNotNull(out)) } }
        return true
    }

    private fun safe(value: String) = value.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(48)
}

object InvoicePdf {
    fun create(context: Context, business: BusinessEntity, customer: CustomerEntity, sale: SaleEntity, lines: List<SaleLineEntity>): String {
        val dir = File(context.filesDir, "sales_invoices").apply { mkdirs() }
        val file = File(dir, "${sale.invoiceNo}.pdf")
        val doc = PdfDocument()
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNo).create())
        var canvas = page.canvas
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun text(t: String, x: Float, y: Float, size: Float = 10f, bold: Boolean = false) {
            paint.textSize = size
            paint.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            canvas.drawText(t, x, y, paint)
        }
        fun newPage(): Float {
            doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNo).create())
            canvas = page.canvas
            return 55f
        }
        text(business.businessName.ifBlank { "Sales Invoice" }, 40f, 52f, 19f, true)
        text("INVOICE", 455f, 52f, 18f, true)
        text(business.address.replace("\n", ", "), 40f, 72f, 9f)
        if (business.vatNumber.isNotBlank()) text("VAT No: ${business.vatNumber}", 40f, 87f, 9f)
        if (business.companyNumber.isNotBlank()) text("Company No: ${business.companyNumber}", 40f, 102f, 9f)
        text("Invoice: ${sale.invoiceNo}", 395f, 78f, 10f, true)
        text("Date: ${displayDate(sale.saleDateEpochDay)}", 395f, 94f, 10f)
        if (sale.vatType == VatTypes.REVERSE) {
            paint.color = android.graphics.Color.RED
            text("THIS INVOICE IS SUBJECT TO REVERSE CHARGE VAT", 40f, 120f, 11f, true)
            paint.color = android.graphics.Color.BLACK
        }

        text("Bill to", 40f, 145f, 10f, true)
        text(customer.companyName, 40f, 161f, 11f, true)
        var cy = 176f
        customer.address.lines().filter { it.isNotBlank() }.take(4).forEach { text(it, 40f, cy, 9f); cy += 13f }
        if (customer.vatNumber.isNotBlank()) { text("VAT No: ${customer.vatNumber}", 40f, cy, 9f); cy += 13f }

        var y = 235f
        paint.strokeWidth = 1f
        canvas.drawLine(40f, y, 555f, y, paint)
        y += 18f
        text("Item", 40f, y, 9f, true); text("Qty", 330f, y, 9f, true); text("Unit Gross", 385f, y, 9f, true); text("Gross", 490f, y, 9f, true)
        y += 9f
        canvas.drawLine(40f, y, 555f, y, paint)
        y += 20f
        lines.forEach { line ->
            if (y > 710f) y = newPage()
            text(line.item.take(48), 40f, y, 9f)
            text(line.quantity.toString(), 338f, y, 9f)
            text(formatMoney(line.unitGrossPence), 385f, y, 9f)
            text(formatMoney(line.lineGrossPence), 490f, y, 9f)
            y += 20f
        }
        if (y > 650f) y = newPage()
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
        if (business.bankDetails.isNotBlank()) { text("Payment details", 40f, y, 9f, true); y += 14f; business.bankDetails.lines().take(4).forEach { text(it, 40f, y, 8f); y += 12f } }
        if (business.invoiceTerms.isNotBlank()) { y += 8f; text("Terms: ${business.invoiceTerms}", 40f, y, 8f) }
        if (business.invoiceFooter.isNotBlank()) text(business.invoiceFooter.take(95), 40f, 810f, 7f)
        doc.finishPage(page)
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file.absolutePath
    }
}

object XlsxExport {
    fun create(
        target: File,
        purchases: List<PurchaseEntity>,
        sales: List<SaleEntity>,
        saleLines: List<SaleLineEntity>,
        saleReturns: List<SaleReturnEntity>,
        customers: List<CustomerEntity>,
        expenses: List<ExpenseEntity>,
        summary: FinanceSummary
    ) {
        val customerMap = customers.associateBy { it.id }
        val purHeaders = listOf("NO", "STORE", "INVOICE DATE", "MODEL", "NET", "VAT", "GROSS", "Unit Price", "QTY", "TOTAL", "Notes (please comment about reverse charge etc)")
        val purRows = mutableListOf<List<Any?>>()
        purchases.forEach { p ->
            purRows += listOf(purRows.size + 1, p.supplier, editDate(p.purchaseDateEpochDay), p.item, p.netPence / 100.0, p.vatPence / 100.0, p.grossPence / 100.0, if (p.quantity > 0) p.grossPence / 100.0 / p.quantity else 0.0, p.quantity, p.grossPence / 100.0, purchaseNotes(p))
            if (p.refundExpectedPence > 0) {
                val creditGross = p.refundExpectedPence.coerceAtMost(p.grossPence)
                val credit = breakdownFromGross(creditGross, p.vatType)
                purRows += listOf(purRows.size + 1, p.supplier, editDate(p.purchaseDateEpochDay), "REFUND / RETURN - ${p.item}", -credit.netPence / 100.0, -credit.vatPence / 100.0, -credit.grossPence / 100.0, null, -p.returnedQty, -credit.grossPence / 100.0, "${p.status.replace('_', ' ')} | Expected ${formatMoney(p.refundExpectedPence)} | Received ${formatMoney(p.refundReceivedPence)}${if (credit.reverseVatPence > 0) " | Reverse VAT reversal ${formatMoney(credit.reverseVatPence)}" else ""}")
            }
        }
        val saleHeaders = listOf("No", "INV.NO", "DATE", "COMPANY", "NET", "VAT", "GROSS", "REVERSE CHARGES", "Notes")
        val lineById = saleLines.associateBy { it.id }
        val saleById = sales.associateBy { it.id }
        val saleRows = mutableListOf<List<Any?>>()
        sales.forEach { s -> saleRows += listOf(saleRows.size + 1, s.invoiceNo, editDate(s.saleDateEpochDay), customerMap[s.customerId]?.companyName.orEmpty(), s.netPence / 100.0, s.vatPence / 100.0, s.grossPence / 100.0, if (s.vatType == VatTypes.REVERSE) s.reverseVatPence / 100.0 else null, s.notes) }
        saleReturns.forEach { r ->
            val line = lineById[r.saleLineId] ?: return@forEach
            val sale = saleById[line.saleId] ?: return@forEach
            val reverse = if (sale.vatType == VatTypes.REVERSE) breakdownFromGross(r.refundGrossPence, sale.vatType).reverseVatPence else 0
            saleRows += listOf(saleRows.size + 1, "${sale.invoiceNo}-RET${r.id}", editDate(r.returnDateEpochDay), customerMap[sale.customerId]?.companyName.orEmpty(), -r.refundNetPence / 100.0, -r.refundVatPence / 100.0, -r.refundGrossPence / 100.0, if (reverse > 0) -reverse / 100.0 else null, "CUSTOMER RETURN: ${line.item} x${r.quantity} | Restocked: ${if (r.restock) "Yes" else "No"}${if (r.notes.isNotBlank()) " | ${r.notes}" else ""}")
        }
        val expHeaders = listOf("NO", "STORE", "DATE", "DETAILS", "Account", "vat", "TOTAL", "Vatable?", "Comments")
        val expRows = expenses.mapIndexed { i, e -> listOf<Any?>(i + 1, e.supplier, editDate(e.expenseDateEpochDay), e.details, e.account, e.vatPence / 100.0, e.grossPence / 100.0, when(e.vatType){ VatTypes.STANDARD -> "Yes"; VatTypes.REVERSE -> "Reverse"; else -> "No" }, e.comments) }
        val profitHeaders = listOf("Metric", "Amount")
        val profitRows = listOf(
            listOf("Sales (net)", summary.salesNet / 100.0),
            listOf("Cost of goods sold", summary.cogsNet / 100.0),
            listOf("Gross profit", summary.grossProfit / 100.0),
            listOf("Expenses (net)", summary.expensesNet / 100.0),
            listOf("Net trading profit", summary.netProfit / 100.0),
            listOf("Output VAT", summary.outputVat / 100.0),
            listOf("Input VAT", summary.inputVat / 100.0),
            listOf("Reverse VAT output (notional)", summary.reverseOutputVat / 100.0),
            listOf("Reverse VAT input (notional)", summary.reverseInputVat / 100.0),
            listOf(if (summary.vatPosition >= 0) "VAT due to HMRC" else "VAT refund expected", kotlin.math.abs(summary.vatPosition) / 100.0),
            listOf("Inventory net cost", summary.inventoryValue / 100.0),
            listOf("Supplier refunds pending", summary.refundsPending / 100.0)
        )
        target.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { zip ->
            entry(zip, "[Content_Types].xml", contentTypes())
            entry(zip, "_rels/.rels", rels())
            entry(zip, "xl/workbook.xml", workbookXml())
            entry(zip, "xl/_rels/workbook.xml.rels", workbookRels())
            entry(zip, "xl/styles.xml", styles())
            entry(zip, "xl/worksheets/sheet1.xml", sheetXml(purHeaders, purRows, setOf(4,5,6,7,9)))
            entry(zip, "xl/worksheets/sheet2.xml", sheetXml(saleHeaders, saleRows, setOf(4,5,6,7)))
            entry(zip, "xl/worksheets/sheet3.xml", sheetXml(expHeaders, expRows, setOf(5,6)))
            entry(zip, "xl/worksheets/sheet4.xml", sheetXml(profitHeaders, profitRows, setOf(1)))
        }
    }

    private fun purchaseNotes(p: PurchaseEntity): String = buildList {
        if (p.vatType == VatTypes.REVERSE) add("Reverse VAT notional ${formatMoney(p.reverseVatPence)}")
        if (p.orderNumber.isNotBlank()) add("Order ${p.orderNumber}")
        if (p.status.isNotBlank()) add(p.status.replace('_', ' '))
        if (p.notes.isNotBlank()) add(p.notes)
    }.joinToString(" | ")

    private fun entry(zip: ZipOutputStream, name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun colName(index: Int): String { var n = index + 1; var out = ""; while (n > 0) { val r = (n - 1) % 26; out = ('A'.code + r).toChar() + out; n = (n - 1) / 26 }; return out }
    private fun sheetXml(headers: List<String>, rows: List<List<Any?>>, moneyCols: Set<Int>): String {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><sheetData>")
        fun rowXml(r: Int, values: List<Any?>, header: Boolean = false) {
            sb.append("<row r=\"$r\">")
            values.forEachIndexed { c, v ->
                val ref = "${colName(c)}$r"
                when (v) {
                    null -> Unit
                    is Number -> sb.append("<c r=\"$ref\"${if (!header && c in moneyCols) " s=\"2\"" else if(header) " s=\"1\"" else ""}><v>${v}</v></c>")
                    else -> sb.append("<c r=\"$ref\" t=\"inlineStr\"${if(header) " s=\"1\"" else ""}><is><t>${esc(v.toString())}</t></is></c>")
                }
            }
            sb.append("</row>")
        }
        rowXml(1, headers, true)
        rows.forEachIndexed { i, row -> rowXml(i + 2, row) }
        sb.append("</sheetData><autoFilter ref=\"A1:${colName(headers.lastIndex)}${rows.size + 1}\"/><sheetFormatPr defaultRowHeight=\"15\"/></worksheet>")
        return sb.toString()
    }
    private fun contentTypes() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/worksheets/sheet3.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/worksheets/sheet4.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>"""
    private fun rels() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
    private fun workbookXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="PUR" sheetId="1" r:id="rId1"/><sheet name="SALES" sheetId="2" r:id="rId2"/><sheet name="EXPENSES" sheetId="3" r:id="rId3"/><sheet name="PROFIT &amp; VAT" sheetId="4" r:id="rId4"/></sheets></workbook>"""
    private fun workbookRels() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet3.xml"/><Relationship Id="rId4" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet4.xml"/><Relationship Id="rId5" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""
    private fun styles() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF1F4E78"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFill="1" applyFont="1"/><xf numFmtId="4" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs></styleSheet>"""
}
