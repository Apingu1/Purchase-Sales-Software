import Foundation
import UIKit

enum InvoicePDF {
    static func create(business: Business, customer: Customer, sale: Sale, destination: URL) -> String? {
        let file = destination.appendingPathComponent("Sales_Invoice_\(safe(sale.invoiceNo)).pdf")
        let page = CGRect(x: 0, y: 0, width: 595, height: 842)
        let renderer = UIGraphicsPDFRenderer(bounds: page)
        do {
            try renderer.writePDF(to: file) { context in
                context.beginPage()
                var y: CGFloat = 42
                draw(business.businessName.ifBlank("Purchase & Sales"), x: 42, y: y, size: 20, bold: true); y += 30
                draw(business.address, x: 42, y: y, width: 255, size: 10); y += height(business.address, width: 255, size: 10) + 5
                if !business.companyNumber.isEmpty { draw("Company No: \(business.companyNumber)", x: 42, y: y, size: 10); y += 15 }
                if !business.vatNumber.isEmpty { draw("VAT No: \(business.vatNumber)", x: 42, y: y, size: 10); y += 15 }

                draw("SALES INVOICE", x: 365, y: 42, size: 18, bold: true)
                draw("Invoice: \(sale.invoiceNo)", x: 365, y: 72, size: 10, bold: true)
                draw("Date: \(sale.saleDate.displayUK)", x: 365, y: 88, size: 10)
                y = max(y + 18, 130)
                draw("Bill to", x: 42, y: y, size: 11, bold: true); y += 17
                draw(customer.companyName, x: 42, y: y, size: 11, bold: true); y += 17
                draw(customer.address, x: 42, y: y, width: 260, size: 10); y += height(customer.address, width: 260, size: 10) + 4
                if !customer.companyNumber.isEmpty { draw("Company No: \(customer.companyNumber)", x: 42, y: y, size: 10); y += 15 }
                if !customer.vatNumber.isEmpty { draw("VAT No: \(customer.vatNumber)", x: 42, y: y, size: 10); y += 15 }
                y += 18

                UIColor.systemGray5.setFill(); context.cgContext.fill(CGRect(x: 42, y: y, width: 511, height: 26))
                draw("Item", x: 48, y: y + 7, size: 9, bold: true)
                draw("Qty", x: 336, y: y + 7, width: 38, size: 9, bold: true)
                draw("Unit net", x: 382, y: y + 7, width: 76, size: 9, bold: true)
                draw("Line gross", x: 466, y: y + 7, width: 80, size: 9, bold: true)
                y += 32
                for line in sale.lines {
                    let detail = line.assignedIdentifiers.isEmpty ? "" : "\nIMEI / serial: \(line.assignedIdentifiers.joined(separator: ", "))"
                    let text = line.item + detail
                    let rowHeight = max(24, height(text, width: 278, size: 9) + 8)
                    if y + rowHeight > 745 { context.beginPage(); y = 42 }
                    draw(line.item, x: 48, y: y + 4, width: 278, size: 10, bold: true)
                    if !line.assignedIdentifiers.isEmpty { draw("IMEI / serial: \(line.assignedIdentifiers.joined(separator: ", "))", x: 48, y: y + 18, width: 278, size: 8) }
                    draw("\(line.quantity)", x: 336, y: y + 4, width: 38, size: 9)
                    draw(Finance.money(line.unitNetPence), x: 382, y: y + 4, width: 76, size: 9)
                    draw(Finance.money(line.lineGrossPence), x: 466, y: y + 4, width: 80, size: 9)
                    UIColor.systemGray4.setStroke(); context.cgContext.move(to: CGPoint(x: 42, y: y + rowHeight)); context.cgContext.addLine(to: CGPoint(x: 553, y: y + rowHeight)); context.cgContext.strokePath()
                    y += rowHeight
                }
                y += 14
                draw("Net", x: 390, y: y, width: 75, size: 10); draw(Finance.money(sale.netPence), x: 470, y: y, width: 76, size: 10); y += 17
                draw("VAT", x: 390, y: y, width: 75, size: 10); draw(Finance.money(sale.vatPence), x: 470, y: y, width: 76, size: 10); y += 18
                draw("TOTAL", x: 390, y: y, width: 75, size: 11, bold: true); draw(Finance.money(sale.grossPence), x: 470, y: y, width: 76, size: 11, bold: true); y += 30
                if sale.vatType == .reverse {
                    draw("Reverse charge: customer to account for VAT to HMRC.", x: 42, y: y, width: 511, size: 9, bold: true); y += 22
                }
                if !sale.notes.isEmpty { draw("Notes: \(sale.notes)", x: 42, y: y, width: 511, size: 9); y += height(sale.notes, width: 511, size: 9) + 10 }
                if !business.bankDetails.isEmpty { draw("Payment details", x: 42, y: y, size: 10, bold: true); y += 15; draw(business.bankDetails, x: 42, y: y, width: 511, size: 9); y += height(business.bankDetails, width: 511, size: 9) + 8 }
                if !business.invoiceTerms.isEmpty { draw(business.invoiceTerms, x: 42, y: y, width: 511, size: 8); y += 18 }
                if !business.invoiceFooter.isEmpty { draw(business.invoiceFooter, x: 42, y: min(y, 800), width: 511, size: 8) }
            }
            return file.path
        } catch { return nil }
    }

    private static func draw(_ text: String, x: CGFloat, y: CGFloat, width: CGFloat = 300, size: CGFloat, bold: Bool = false) {
        let font = bold ? UIFont.boldSystemFont(ofSize: size) : UIFont.systemFont(ofSize: size)
        NSString(string: text).draw(in: CGRect(x: x, y: y, width: width, height: 500), withAttributes: [.font: font, .foregroundColor: UIColor.label])
    }

    private static func height(_ text: String, width: CGFloat, size: CGFloat) -> CGFloat {
        NSString(string: text).boundingRect(with: CGSize(width: width, height: 500), options: [.usesLineFragmentOrigin], attributes: [.font: UIFont.systemFont(ofSize: size)], context: nil).height
    }

    private static func safe(_ value: String) -> String { value.replacingOccurrences(of: "[^A-Za-z0-9._-]", with: "_", options: .regularExpression) }
}

enum Exports {
    static func accountingWorkbook(data: AppData, period: AccountingPeriod?) -> URL? {
        guard let period else { return nil }
        let orders = data.purchaseOrders.filter { Finance.contains($0.purchaseDate, in: period) && !$0.isVoidedForAccounting }
        let sales = data.sales.filter { Finance.contains($0.saleDate, in: period) }
        let expenses = data.expenses.filter { Finance.contains($0.expenseDate, in: period) }
        var purchaseRows = [["DATE", "SUPPLIER", "ORDER", "ITEM", "QTY", "VAT TYPE", "NET", "VAT", "GROSS", "STATUS"]]
        for order in orders {
            for line in order.lines {
                purchaseRows.append([order.purchaseDate.shortUK, order.supplier, order.orderNumber, line.item, String(line.quantity), order.vatType.label, Finance.plain(line.netPence), Finance.plain(line.vatPence), Finance.plain(line.grossPence), order.status.label])
                let creditGross = line.partialRefund ? line.refundNetPence + line.refundVatPence : line.refundExpectedPence
                if creditGross > 0 {
                    let credit = line.partialRefund ? VATBreakdown(netPence: line.refundNetPence, vatPence: line.refundVatPence, grossPence: creditGross, reverseVatPence: 0) : Finance.fromGross(creditGross, order.vatType)
                    purchaseRows.append([order.purchaseDate.shortUK, order.supplier, order.orderNumber, line.item + " — supplier refund", "", order.vatType.label, Finance.plain(-credit.netPence), Finance.plain(-credit.vatPence), Finance.plain(-credit.grossPence), "CREDIT"])
                }
            }
        }
        var salesRows = [["DATE", "INVOICE", "CUSTOMER", "ITEM", "QTY", "VAT TYPE", "NET", "VAT", "GROSS"]]
        for sale in sales {
            let customer = data.customers.first(where: { $0.id == sale.customerID })?.companyName ?? ""
            for line in sale.lines {
                salesRows.append([sale.saleDate.shortUK, sale.invoiceNo, customer, line.item, String(line.quantity), sale.vatType.label, Finance.plain(line.lineNetPence), Finance.plain(line.lineVatPence), Finance.plain(line.lineGrossPence)])
                for itemReturn in line.returns where Finance.contains(itemReturn.returnDate, in: period) {
                    salesRows.append([itemReturn.returnDate.shortUK, sale.invoiceNo, customer, line.item + " — customer return", String(-itemReturn.quantity), sale.vatType.label, Finance.plain(-itemReturn.refundNetPence), Finance.plain(-itemReturn.refundVatPence), Finance.plain(-itemReturn.refundGrossPence)])
                }
            }
        }
        var expenseRows = [["DATE", "SUPPLIER", "DETAILS", "ACCOUNT", "VAT TYPE", "NET", "VAT", "GROSS"]]
        for expense in expenses { expenseRows.append([expense.expenseDate.shortUK, expense.supplier, expense.details, expense.account, expense.vatType.label, Finance.plain(expense.netPence), Finance.plain(expense.vatPence), Finance.plain(expense.grossPence)]) }
        let outputVAT = sales.reduce(Int64(0)) { $0 + $1.vatPence } - sales.flatMap(\.lines).flatMap(\.returns).filter { Finance.contains($0.returnDate, in: period) }.reduce(Int64(0)) { $0 + $1.refundVatPence }
        let inputVAT = orders.flatMap(\.lines).reduce(Int64(0)) { $0 + $1.vatPence - min($1.vatPence, $1.refundVatPence) } + expenses.reduce(Int64(0)) { $0 + $1.vatPence }
        let salesNet = sales.reduce(Int64(0)) { $0 + $1.netPence } - sales.flatMap(\.lines).flatMap(\.returns).filter { Finance.contains($0.returnDate, in: period) }.reduce(Int64(0)) { $0 + $1.refundNetPence }
        let cogs = sales.flatMap(\.lines).flatMap(\.allocations).reduce(Int64(0)) { $0 + Int64($1.quantity) * $1.unitNetCostPence }
        let expensesNet = expenses.reduce(Int64(0)) { $0 + $1.netPence }
        let profitRows = [["METRIC", "AMOUNT"], ["Net sales", Finance.plain(salesNet)], ["Cost of goods sold", Finance.plain(cogs)], ["Gross profit", Finance.plain(salesNet - cogs)], ["Expenses", Finance.plain(expensesNet)], ["Net trading profit", Finance.plain(salesNet - cogs - expensesNet)], ["Output VAT", Finance.plain(outputVAT)], ["Recoverable input VAT", Finance.plain(inputVAT)], [outputVAT >= inputVAT ? "VAT due to HMRC" : "VAT refund expected", Finance.plain(abs(outputVAT - inputVAT))]]
        let output = """
        <?xml version="1.0"?>
        <?mso-application progid="Excel.Sheet"?>
        <Workbook xmlns="urn:schemas-microsoft-com:office:spreadsheet" xmlns:ss="urn:schemas-microsoft-com:office:spreadsheet">
          \(worksheet("PUR", purchaseRows))
          \(worksheet("SALES", salesRows))
          \(worksheet("EXPENSES", expenseRows))
          \(worksheet("PROFIT &amp; VAT", profitRows))
        </Workbook>
        """
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("Business_Records_\(safe(period.name)).xls")
        do { try output.data(using: .utf8)?.write(to: file, options: .atomic); return file } catch { return nil }
    }

    static func documentManifest(data: AppData, period: AccountingPeriod?) -> URL? {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("Purchase_Sales_Documents.txt")
        let text = """
        Purchase & Sales document register
        Accounting period: \(period?.name ?? "All")
        Purchase invoices:
        \(data.purchaseOrders.compactMap(\.invoicePath).joined(separator: "\n"))

        Sales invoices:
        \(data.sales.compactMap(\.pdfPath).joined(separator: "\n"))

        Expense receipts:
        \(data.expenses.compactMap(\.attachmentPath).joined(separator: "\n"))
        """
        do { try text.data(using: .utf8)?.write(to: file, options: .atomic); return file } catch { return nil }
    }

    private static func xml(_ value: String) -> String { value.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;") }
    private static func worksheet(_ name: String, _ rows: [[String]]) -> String {
        let content = rows.map { cells in "<Row>" + cells.map { "<Cell><Data ss:Type=\"String\">\(xml($0))</Data></Cell>" }.joined() + "</Row>" }.joined(separator: "\n")
        return "<Worksheet ss:Name=\"\(name)\"><Table>\(content)</Table></Worksheet>"
    }
    private static func safe(_ value: String) -> String { value.replacingOccurrences(of: "[^A-Za-z0-9._-]", with: "_", options: .regularExpression) }
}

private extension String {
    func ifBlank(_ fallback: String) -> String { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? fallback : self }
}
