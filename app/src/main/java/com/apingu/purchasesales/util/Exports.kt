package com.apingu.purchasesales.util

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.apingu.purchasesales.data.*
import java.io.*
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

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
        val mime = when (source.extension.lowercase()) {
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "application/octet-stream"
        }
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
            paint.color = android.graphics.Color.BLACK
            return 55f
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

        var y = maxOf(285f, billY + 22f)
        paint.strokeWidth = 1f
        canvas.drawLine(40f, y, 555f, y, paint); y += 18f
        text("Item", 40f, y, 9f, true); text("Qty", 330f, y, 9f, true); text("Unit Gross", 385f, y, 9f, true); text("Gross", 490f, y, 9f, true)
        y += 9f
        canvas.drawLine(40f, y, 555f, y, paint); y += 20f

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
        if (business.bankDetails.isNotBlank()) {
            text("Payment details", 40f, y, 9f, true); y += 14f
            business.bankDetails.lines().filter { it.isNotBlank() }.take(4).forEach { text(it.take(80), 40f, y, 8f); y += 12f }
        }
        if (business.invoiceTerms.isNotBlank()) { y += 8f; text("Terms: ${business.invoiceTerms}".take(95), 40f, y, 8f) }
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
            val creditGross = supplierRefundGrossPence(p)
            if (creditGross > 0) {
                val creditNet = if (p.refundNetPence > 0 || p.refundVatPence > 0) p.refundNetPence else breakdownFromGross(creditGross.coerceAtMost(p.grossPence), p.vatType).netPence
                val creditVat = if (p.refundNetPence > 0 || p.refundVatPence > 0) p.refundVatPence else breakdownFromGross(creditGross.coerceAtMost(p.grossPence), p.vatType).vatPence
                val reverseCredit = if (p.vatType == VatTypes.REVERSE) breakdownFromGross(creditNet, VatTypes.REVERSE).reverseVatPence else 0
                purRows += listOf(
                    purRows.size + 1,
                    p.supplier,
                    editDate(p.purchaseDateEpochDay),
                    if (p.partialRefund) "PARTIAL REFUND - ${p.item}" else "REFUND / RETURN - ${p.item}",
                    -creditNet / 100.0,
                    -creditVat / 100.0,
                    -(creditNet + creditVat) / 100.0,
                    null,
                    if (p.partialRefund) 0 else -p.returnedQty,
                    -(creditNet + creditVat) / 100.0,
                    buildString {
                        append(if (p.partialRefund) "PARTIAL MONETARY REFUND / PRICE ADJUSTMENT" else p.status.replace('_', ' '))
                        append(" | Net refunded ${formatMoney(creditNet)} | VAT refunded ${formatMoney(creditVat)}")
                        append(" | Expected ${formatMoney(p.refundExpectedPence)} | Received ${formatMoney(p.refundReceivedPence)}")
                        if (reverseCredit > 0) append(" | Reverse VAT reversal ${formatMoney(reverseCredit)}")
                    }
                )
            }
        }

        val saleHeaders = listOf("No", "INV.NO", "DATE", "COMPANY", "NET", "VAT", "GROSS", "REVERSE CHARGES", "Notes")
        val lineById = saleLines.associateBy { it.id }
        val saleById = sales.associateBy { it.id }
        val saleRows = mutableListOf<List<Any?>>()
        sales.forEach { s ->
            val itemsNote = saleLines.filter { it.saleId == s.id }.joinToString(", ") { "${it.item} x${it.quantity}" }
            val notes = listOf(itemsNote, s.notes).filter { it.isNotBlank() }.joinToString(" | ")
            saleRows += listOf(saleRows.size + 1, s.invoiceNo, editDate(s.saleDateEpochDay), customerMap[s.customerId]?.companyName.orEmpty(), s.netPence / 100.0, s.vatPence / 100.0, s.grossPence / 100.0, if (s.vatType == VatTypes.REVERSE) s.reverseVatPence / 100.0 else null, notes)
        }
        saleReturns.forEach { r ->
            val line = lineById[r.saleLineId] ?: return@forEach
            val sale = saleById[line.saleId] ?: return@forEach
            val reverse = if (sale.vatType == VatTypes.REVERSE) breakdownFromGross(r.refundGrossPence, sale.vatType).reverseVatPence else 0
            saleRows += listOf(saleRows.size + 1, "${sale.invoiceNo}-RET${r.id}", editDate(r.returnDateEpochDay), customerMap[sale.customerId]?.companyName.orEmpty(), -r.refundNetPence / 100.0, -r.refundVatPence / 100.0, -r.refundGrossPence / 100.0, if (reverse > 0) -reverse / 100.0 else null, "CUSTOMER RETURN: ${line.item} x${r.quantity} | Restocked: ${if (r.restock) "Yes" else "No"}${if (r.notes.isNotBlank()) " | ${r.notes}" else ""}")
        }

        val expHeaders = listOf("NO", "STORE", "DATE", "DETAILS", "Account", "vat", "TOTAL", "Vatable?", "Comments")
        val expRows = expenses.mapIndexed { i, e -> listOf<Any?>(i + 1, e.supplier, editDate(e.expenseDateEpochDay), e.details, e.account, e.vatPence / 100.0, e.grossPence / 100.0, when (e.vatType) { VatTypes.STANDARD -> "Yes"; VatTypes.REVERSE -> "Reverse"; else -> "No" }, e.comments) }

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
            entry(zip, "_rels/.rels", rootRels())
            entry(zip, "docProps/app.xml", appProperties())
            entry(zip, "docProps/core.xml", coreProperties())
            entry(zip, "xl/workbook.xml", workbookXml())
            entry(zip, "xl/_rels/workbook.xml.rels", workbookRels())
            entry(zip, "xl/styles.xml", styles())
            entry(zip, "xl/worksheets/sheet1.xml", sheetXml(purHeaders, purRows, setOf(4, 5, 6, 7, 9)))
            entry(zip, "xl/worksheets/sheet2.xml", sheetXml(saleHeaders, saleRows, setOf(4, 5, 6, 7)))
            entry(zip, "xl/worksheets/sheet3.xml", sheetXml(expHeaders, expRows, setOf(5, 6)))
            entry(zip, "xl/worksheets/sheet4.xml", sheetXml(profitHeaders, profitRows, setOf(1)))
        }
        validatePackage(target)
    }

    private fun purchaseNotes(p: PurchaseEntity): String = buildList {
        if (p.vatType == VatTypes.REVERSE) add("Reverse VAT notional ${formatMoney(p.reverseVatPence)}")
        if (p.orderNumber.isNotBlank()) add("Order ${p.orderNumber}")
        if (p.status.isNotBlank()) add(p.status.replace('_', ' '))
        if (p.partialRefund) add("Partial refund: net ${formatMoney(p.refundNetPence)}, VAT ${formatMoney(p.refundVatPence)}")
        if (p.notes.isNotBlank()) add(p.notes)
    }.joinToString(" | ")

    private fun entry(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun colName(index: Int): String {
        var n = index + 1; var out = ""
        while (n > 0) { val r = (n - 1) % 26; out = ('A'.code + r).toChar() + out; n = (n - 1) / 26 }
        return out
    }

    private fun sheetXml(headers: List<String>, rows: List<List<Any?>>, moneyCols: Set<Int>): String {
        val lastColumn = colName(headers.lastIndex)
        val lastRow = rows.size + 1
        val dimension = "A1:$lastColumn$lastRow"
        val sb = StringBuilder(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                "<dimension ref=\"$dimension\"/>" +
                "<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>" +
                "<sheetFormatPr defaultRowHeight=\"15\"/>" +
                "<cols><col min=\"1\" max=\"${headers.size}\" width=\"16\" customWidth=\"1\"/></cols>" +
                "<sheetData>"
        )

        fun rowXml(r: Int, values: List<Any?>, header: Boolean = false) {
            sb.append("<row r=\"$r\">")
            values.forEachIndexed { c, v ->
                val ref = "${colName(c)}$r"
                when (v) {
                    null -> Unit
                    is Number -> sb.append("<c r=\"$ref\"${if (!header && c in moneyCols) " s=\"2\"" else if (header) " s=\"1\"" else ""}><v>${v}</v></c>")
                    else -> sb.append("<c r=\"$ref\" t=\"inlineStr\"${if (header) " s=\"1\"" else ""}><is><t xml:space=\"preserve\">${esc(v.toString())}</t></is></c>")
                }
            }
            sb.append("</row>")
        }
        rowXml(1, headers, true)
        rows.forEachIndexed { i, row -> rowXml(i + 2, row) }
        sb.append("</sheetData><autoFilter ref=\"$dimension\"/></worksheet>")
        return sb.toString()
    }

    private fun contentTypes() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/worksheets/sheet3.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/worksheets/sheet4.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>"""

    private fun rootRels() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
</Relationships>"""

    private fun workbookXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<fileVersion appName="xl" lastEdited="7" lowestEdited="7" rupBuild="0"/>
<workbookPr defaultThemeVersion="164011"/>
<bookViews><workbookView xWindow="0" yWindow="0" windowWidth="28800" windowHeight="15000"/></bookViews>
<sheets><sheet name="PUR" sheetId="1" r:id="rId1"/><sheet name="SALES" sheetId="2" r:id="rId2"/><sheet name="EXPENSES" sheetId="3" r:id="rId3"/><sheet name="PROFIT &amp; VAT" sheetId="4" r:id="rId4"/></sheets>
<calcPr calcId="191029" fullCalcOnLoad="1"/>
</workbook>"""

    private fun workbookRels() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet3.xml"/>
<Relationship Id="rId4" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet4.xml"/>
<Relationship Id="rId5" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    private fun styles() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="2"><font><sz val="11"/><name val="Calibri"/><family val="2"/><scheme val="minor"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="11"/><name val="Calibri"/><family val="2"/><scheme val="minor"/></font></fonts>
<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF1F4E78"/><bgColor indexed="64"/></patternFill></fill></fills>
<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFill="1" applyFont="1"/><xf numFmtId="4" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles><dxfs count="0"/><tableStyles count="0" defaultTableStyle="TableStyleMedium2" defaultPivotStyle="PivotStyleLight16"/>
</styleSheet>"""

    private fun appProperties() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes"><Application>Purchase &amp; Sales Software</Application><AppVersion>1.0</AppVersion></Properties>"""

    private fun coreProperties(): String {
        val now = Instant.now().toString()
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:dcmitype="http://purl.org/dc/dcmitype/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><dc:creator>Purchase &amp; Sales Software</dc:creator><cp:lastModifiedBy>Purchase &amp; Sales Software</cp:lastModifiedBy><dcterms:created xsi:type="dcterms:W3CDTF">$now</dcterms:created><dcterms:modified xsi:type="dcterms:W3CDTF">$now</dcterms:modified></cp:coreProperties>"""
    }

    private fun validatePackage(target: File) {
        val required = setOf("[Content_Types].xml", "_rels/.rels", "docProps/app.xml", "docProps/core.xml", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml", "xl/worksheets/sheet3.xml", "xl/worksheets/sheet4.xml")
        ZipFile(target).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            require(required.all { it in names }) { "Generated Excel workbook is incomplete" }
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            required.filter { it.endsWith(".xml") || it.endsWith(".rels") }.forEach { name ->
                val doc = zip.getInputStream(requireNotNull(zip.getEntry(name))).use { factory.newDocumentBuilder().parse(it) }
                if (name.startsWith("xl/worksheets/")) {
                    val cols = doc.getElementsByTagNameNS("http://schemas.openxmlformats.org/spreadsheetml/2006/main", "col")
                    for (i in 0 until cols.length) {
                        val max = cols.item(i).attributes.getNamedItem("max")?.nodeValue
                        require(max?.toIntOrNull() != null) { "Generated Excel workbook has invalid column metadata" }
                    }
                }
            }
        }
    }
}
