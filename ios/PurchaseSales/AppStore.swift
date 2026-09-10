import Foundation
import SwiftUI

@MainActor
final class AppStore: ObservableObject {
    @Published private(set) var data = AppData()
    @Published var message: String?

    private let dataURL: URL
    private let attachmentsURL: URL

    init(fileManager: FileManager = .default) {
        let support = try! fileManager.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        let root = support.appendingPathComponent("PurchaseSales", isDirectory: true)
        try? fileManager.createDirectory(at: root, withIntermediateDirectories: true)
        attachmentsURL = root.appendingPathComponent("Attachments", isDirectory: true)
        try? fileManager.createDirectory(at: attachmentsURL, withIntermediateDirectories: true)
        dataURL = root.appendingPathComponent("PurchaseSalesData.json")
        load()
        if data.accountingPeriods.isEmpty {
            let calendar = Calendar.current
            let year = calendar.component(.year, from: Date())
            let start = calendar.date(from: DateComponents(year: year, month: 1, day: 1))!
            let end = calendar.date(from: DateComponents(year: year, month: 12, day: 31))!
            let period = AccountingPeriod(name: String(year), start: start, end: end)
            data.accountingPeriods = [period]
            data.business.selectedAccountingPeriodID = period.id
            persist()
        }
    }

    var business: Business { data.business }
    var accountingPeriods: [AccountingPeriod] { data.accountingPeriods.sorted { $0.start > $1.start } }
    var customers: [Customer] { data.customers.sorted { $0.companyName.localizedCaseInsensitiveCompare($1.companyName) == .orderedAscending } }
    var purchaseOrders: [PurchaseOrder] { data.purchaseOrders.sorted { $0.purchaseDate > $1.purchaseDate } }
    var sales: [Sale] { data.sales.sorted { $0.saleDate > $1.saleDate } }
    var expenses: [Expense] { data.expenses.sorted { $0.expenseDate > $1.expenseDate } }
    var selectedPeriod: AccountingPeriod? {
        data.accountingPeriods.first { $0.id == data.business.selectedAccountingPeriodID } ?? data.accountingPeriods.max { $0.start < $1.start }
    }

    func saveBusiness(_ business: Business) {
        data.business = business
        persist("Business details saved")
    }

    func savePeriod(_ period: AccountingPeriod) {
        if let index = data.accountingPeriods.firstIndex(where: { $0.id == period.id }) { data.accountingPeriods[index] = period }
        else { data.accountingPeriods.append(period) }
        if data.business.selectedAccountingPeriodID == nil { data.business.selectedAccountingPeriodID = period.id }
        persist("Accounting period saved")
    }

    func selectPeriod(_ period: AccountingPeriod) {
        data.business.selectedAccountingPeriodID = period.id
        persist()
    }

    func deletePeriod(_ period: AccountingPeriod) {
        guard data.accountingPeriods.count > 1 else { message = "At least one accounting period is required"; return }
        data.accountingPeriods.removeAll { $0.id == period.id }
        if data.business.selectedAccountingPeriodID == period.id { data.business.selectedAccountingPeriodID = accountingPeriods.first?.id }
        persist("Accounting period deleted")
    }

    func saveCustomer(_ customer: Customer) {
        let code = customer.invoiceCode.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard (2...5).contains(code.count) else { message = "Invoice code must contain 2–5 characters"; return }
        guard !data.customers.contains(where: { $0.id != customer.id && $0.invoiceCode.caseInsensitiveCompare(code) == .orderedSame }) else {
            message = "That invoice code is already in use"; return
        }
        var value = customer; value.invoiceCode = code
        if let index = data.customers.firstIndex(where: { $0.id == value.id }) { data.customers[index] = value }
        else { data.customers.append(value) }
        persist("Customer saved")
    }

    func deleteCustomer(_ customer: Customer) {
        guard !data.sales.contains(where: { $0.customerID == customer.id }) else { message = "This customer is used on a sales invoice"; return }
        data.customers.removeAll { $0.id == customer.id }
        persist("Customer deleted")
    }

    func savePurchase(_ order: PurchaseOrder, attachment: URL?) {
        guard !order.supplier.trimmingCharacters(in: .whitespaces).isEmpty, !order.lines.isEmpty else { message = "Add a supplier and at least one item"; return }
        guard order.lines.allSatisfy({ !$0.item.trimmingCharacters(in: .whitespaces).isEmpty && $0.quantity > 0 }) else { message = "Every item needs a name and quantity"; return }
        let previous = data.purchaseOrders.first { $0.id == order.id }
        var value = order
        if let attachment { value.invoicePath = importAttachment(attachment, prefix: "PURCHASE") ?? value.invoicePath }
        value.updatedAt = Date()
        if value.isVoidedForAccounting, let old = previous, let localPath = old.invoicePath {
            queueDropboxDeletion(for: old.purchaseDate, category: "Purchases", filename: URL(fileURLWithPath: localPath).lastPathComponent)
        }
        if let index = data.purchaseOrders.firstIndex(where: { $0.id == value.id }) { data.purchaseOrders[index] = value }
        else { data.purchaseOrders.append(value) }
        persist("Purchase saved")
        autoSync()
    }

    func markReceivedAll(_ order: PurchaseOrder) {
        guard let index = data.purchaseOrders.firstIndex(where: { $0.id == order.id }) else { return }
        for lineIndex in data.purchaseOrders[index].lines.indices {
            let line = data.purchaseOrders[index].lines[lineIndex]
            data.purchaseOrders[index].lines[lineIndex].receivedQty = max(line.receivedQty, line.quantity - line.cancelledQty)
        }
        data.purchaseOrders[index].updatedAt = Date()
        persist("Purchase received")
        autoSync()
    }

    func duplicate(_ order: PurchaseOrder) -> PurchaseOrder {
        PurchaseOrder(
            purchaseDate: Date(), supplier: order.supplier, orderNumber: "", accountUsername: order.accountUsername,
            vatType: order.vatType, paymentMethod: order.paymentMethod, invoicePath: nil, notes: "", lines: order.lines.map {
                PurchaseLine(item: $0.item, quantity: $0.quantity, grossPence: $0.grossPence,
                             netPence: $0.netPence, vatPence: $0.vatPence, reverseVatPence: $0.reverseVatPence)
            }
        )
    }

    func invoiceNumber(customer: Customer, date: Date) -> String {
        let count = data.sales.filter { $0.customerID == customer.id && Calendar.current.isDate($0.saleDate, inSameDayAs: date) }.count + 1
        return customer.invoiceCode.uppercased() + "-" + date.compactInvoice + "-" + String(format: "%02d", count)
    }

    func saveSale(id: UUID? = nil, date: Date, customer: Customer, vatType: VATType, notes: String, manualInvoice: String?, drafts: [SaleLineDraft]) {
        guard !drafts.isEmpty, drafts.allSatisfy({ !$0.item.trimmingCharacters(in: .whitespaces).isEmpty && $0.quantity > 0 }) else { message = "Add at least one valid sales item"; return }
        let invoice = data.business.autoInvoiceNumber ? invoiceNumber(customer: customer, date: date) : (manualInvoice ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !invoice.isEmpty else { message = "Enter an invoice number"; return }
        guard !data.sales.contains(where: { $0.id != id && $0.invoiceNo.caseInsensitiveCompare(invoice) == .orderedSame }) else { message = "Invoice number already exists"; return }

        var lines: [SaleLine] = []
        do {
            for draft in drafts {
                let allocations = try allocate(item: draft.item, quantity: draft.quantity, excludingSaleID: id)
                let result = Finance.fromNet(draft.unitNetPence, vatType)
                let count = Int64(draft.quantity)
                let candidates = availableIdentifiers(for: draft.item, excludingSaleID: id).map { $0.identifier }
                let preferred = draft.selectedIdentifiers.filter { candidates.contains($0) }
                if candidates.count > draft.quantity && preferred.count != draft.quantity {
                    throw NSError(domain: "PurchaseSales", code: 2, userInfo: [NSLocalizedDescriptionKey: "Select exactly \(draft.quantity) IMEI/serial number(s) for \(draft.item)"])
                }
                let identifiers = preferred.count == draft.quantity ? preferred : Array(candidates.prefix(draft.quantity))
                lines.append(SaleLine(item: draft.item, quantity: draft.quantity, unitNetPence: draft.unitNetPence,
                                      lineGrossPence: result.grossPence * count, lineNetPence: result.netPence * count,
                                      lineVatPence: result.vatPence * count, allocations: allocations,
                                      assignedIdentifiers: identifiers))
            }
        } catch { message = error.localizedDescription; return }

        let net = lines.reduce(Int64(0)) { $0 + $1.lineNetPence }
        let vat = lines.reduce(Int64(0)) { $0 + $1.lineVatPence }
        let reverse = vatType == .reverse ? Finance.fromNet(net, .reverse).reverseVatPence : 0
        var sale = Sale(id: id ?? UUID(), invoiceNo: invoice, saleDate: date, customerID: customer.id, vatType: vatType,
                        netPence: net, vatPence: vat, grossPence: net + vat, reverseVatPence: reverse, notes: notes, lines: lines)
        if let existing = id.flatMap({ saleID in data.sales.first(where: { $0.id == saleID }) }) {
            for index in sale.lines.indices {
                if let old = existing.lines.first(where: { $0.item.caseInsensitiveCompare(sale.lines[index].item) == .orderedSame }) {
                    sale.lines[index].returns = old.returns
                }
            }
        }
        sale.pdfPath = InvoicePDF.create(business: data.business, customer: customer, sale: sale, destination: attachmentsURL)
        if let index = data.sales.firstIndex(where: { $0.id == sale.id }) { data.sales[index] = sale } else { data.sales.append(sale) }
        if !data.business.autoInvoiceNumber { data.usedManualInvoiceNumbers.insert(invoice.lowercased()) }
        persist("Invoice generated")
        autoSync()
    }

    func deleteSale(_ sale: Sale) {
        if let path = sale.pdfPath {
            queueDropboxDeletion(for: sale.saleDate, category: "Sales", filename: URL(fileURLWithPath: path).lastPathComponent)
        }
        if let path = sale.pdfPath { try? FileManager.default.removeItem(atPath: path) }
        data.sales.removeAll { $0.id == sale.id }
        persist("Sales invoice deleted")
        autoSync()
    }

    func recordReturn(saleID: UUID, lineID: UUID, quantity: Int, date: Date, restock: Bool, notes: String) {
        guard let saleIndex = data.sales.firstIndex(where: { $0.id == saleID }),
              let lineIndex = data.sales[saleIndex].lines.firstIndex(where: { $0.id == lineID }) else { return }
        let line = data.sales[saleIndex].lines[lineIndex]
        let already = line.returns.reduce(0) { $0 + $1.quantity }
        guard quantity > 0 && already + quantity <= line.quantity else { message = "Return quantity exceeds the quantity sold"; return }
        let gross = (line.lineGrossPence / Int64(line.quantity)) * Int64(quantity)
        let split = Finance.fromGross(gross, data.sales[saleIndex].vatType)
        data.sales[saleIndex].lines[lineIndex].returns.append(CustomerReturn(returnDate: date, quantity: quantity,
                                                                             refundGrossPence: gross, refundNetPence: split.netPence,
                                                                             refundVatPence: split.vatPence, restock: restock, notes: notes))
        persist("Customer return recorded")
        autoSync()
    }

    func saveExpense(_ expense: Expense, attachment: URL?) {
        var value = expense
        if let attachment { value.attachmentPath = importAttachment(attachment, prefix: "EXPENSE") ?? value.attachmentPath }
        value.updatedAt = Date()
        if let index = data.expenses.firstIndex(where: { $0.id == value.id }) { data.expenses[index] = value }
        else { data.expenses.append(value) }
        persist("Expense saved")
        autoSync()
    }

    var inventory: [InventoryRow] {
        let allAllocations = data.sales.flatMap(\.lines).flatMap(\.allocations)
        return Dictionary(grouping: data.purchaseOrders.flatMap { order in order.lines.map { (order.id, $0) } }, by: { $0.1.item.lowercased().trimmingCharacters(in: .whitespaces) })
            .map { _, entries in
                let item = entries.first!.1.item
                let purchased = entries.reduce(0) { $0 + $1.1.quantity }
                let received = entries.reduce(0) { $0 + $1.1.receivedQty }
                let returned = entries.reduce(0) { $0 + $1.1.returnedQty }
                let ids = Set(entries.map { $0.1.id })
                let sold = allAllocations.filter { ids.contains($0.purchaseLineID) }.reduce(0) { $0 + $1.quantity }
                let customerRestocked = data.sales.flatMap(\.lines).filter { $0.item.caseInsensitiveCompare(item) == .orderedSame }
                    .flatMap(\.returns).filter(\.restock).reduce(0) { $0 + $1.quantity }
                let value = entries.reduce(Int64(0)) { total, entry in
                    let line = entry.1
                    let lineSold = allAllocations.filter { $0.purchaseLineID == line.id }.reduce(0) { $0 + $1.quantity }
                    let available = max(0, line.receivedQty - line.returnedQty - lineSold)
                    let effectiveNet = max(0, line.netPence - (line.partialRefund ? line.refundNetPence : 0))
                    return total + Int64(available) * (line.quantity > 0 ? effectiveNet / Int64(line.quantity) : 0)
                }
                return InventoryRow(item: item, purchased: purchased, received: received, supplierReturned: returned,
                                    sold: sold, customerRestocked: customerRestocked, available: received - returned - sold + customerRestocked,
                                    inventoryNetCostPence: value)
            }.sorted { $0.item.localizedCaseInsensitiveCompare($1.item) == .orderedAscending }
    }

    var summary: FinanceSummary {
        guard let period = selectedPeriod else { return FinanceSummary() }
        let periodOrders = data.purchaseOrders.filter { Finance.contains($0.purchaseDate, in: period) }
        let accountingOrders = periodOrders.filter { !$0.isVoidedForAccounting }
        let periodSales = data.sales.filter { Finance.contains($0.saleDate, in: period) }
        let periodExpenses = data.expenses.filter { Finance.contains($0.expenseDate, in: period) }
        let periodReturns = data.sales.flatMap(\.lines).flatMap(\.returns).filter { Finance.contains($0.returnDate, in: period) }
        let salesNet = periodSales.reduce(Int64(0)) { $0 + $1.netPence } - periodReturns.reduce(Int64(0)) { $0 + $1.refundNetPence }
        let outputVAT = periodSales.reduce(Int64(0)) { $0 + $1.vatPence } - periodReturns.reduce(Int64(0)) { $0 + $1.refundVatPence }
        let inputVAT = accountingOrders.flatMap(\.lines).reduce(Int64(0)) { $0 + $1.vatPence - min($1.vatPence, $1.refundVatPence) }
            + periodExpenses.reduce(Int64(0)) { $0 + $1.vatPence }
        let reverseInput = accountingOrders.flatMap(\.lines).reduce(Int64(0)) { $0 + $1.reverseVatPence }
            + periodExpenses.reduce(Int64(0)) { $0 + $1.reverseVatPence }
        let saleIDs = Set(periodSales.map(\.id))
        let cogs = data.sales.filter { saleIDs.contains($0.id) }.flatMap(\.lines).flatMap(\.allocations)
            .reduce(Int64(0)) { $0 + Int64($1.quantity) * $1.unitNetCostPence }
        let expenseNet = periodExpenses.reduce(Int64(0)) { $0 + $1.netPence }
        let refundsPending = periodOrders.flatMap(\.lines).filter { !$0.partialRefund }
            .reduce(Int64(0)) { $0 + max(0, $1.refundExpectedPence - $1.refundReceivedPence) }
        return FinanceSummary(salesNet: salesNet, cogsNet: cogs, expensesNet: expenseNet,
                              grossProfit: salesNet - cogs, netProfit: salesNet - cogs - expenseNet,
                              outputVAT: outputVAT, inputVAT: inputVAT, reverseOutputVAT: reverseInput,
                              reverseInputVAT: reverseInput, vatPosition: outputVAT - inputVAT,
                              inventoryValue: inventory.reduce(Int64(0)) { $0 + $1.inventoryNetCostPence }, refundsPending: refundsPending)
    }

    func exportAccountingWorkbook() -> URL? { Exports.accountingWorkbook(data: data, period: selectedPeriod) }
    func exportDocuments() -> URL? { Exports.documentManifest(data: data, period: selectedPeriod) }
    func syncNow() { Task { await DropboxSync.sync(store: self) } }
    func identifierOptions(for item: String, excludingSaleID: UUID?) -> [String] {
        availableIdentifiers(for: item, excludingSaleID: excludingSaleID).map { $0.identifier }
    }
    var pendingDropboxDeletions: [String] { data.pendingDropboxDeletions }
    func completeDropboxDeletions(_ completed: [String]) {
        data.pendingDropboxDeletions.removeAll { completed.contains($0) }
        persist()
    }

    private func allocate(item: String, quantity: Int, excludingSaleID: UUID?) throws -> [SaleAllocation] {
        var remaining = quantity
        var result: [SaleAllocation] = []
        let sold = data.sales.filter { $0.id != excludingSaleID }.flatMap(\.lines).flatMap(\.allocations)
        for order in data.purchaseOrders.sorted(by: { $0.purchaseDate < $1.purchaseDate }) {
            for line in order.lines where line.item.caseInsensitiveCompare(item) == .orderedSame {
                let used = sold.filter { $0.purchaseLineID == line.id }.reduce(0) { $0 + $1.quantity }
                let available = max(0, line.receivedQty - line.returnedQty - used)
                let take = min(remaining, available)
                if take > 0 {
                    let effective = max(0, line.netPence - (line.partialRefund ? line.refundNetPence : 0))
                    result.append(SaleAllocation(purchaseOrderID: order.id, purchaseLineID: line.id, quantity: take,
                                                 unitNetCostPence: line.quantity > 0 ? effective / Int64(line.quantity) : 0))
                    remaining -= take
                }
                if remaining == 0 { return result }
            }
        }
        throw NSError(domain: "PurchaseSales", code: 1, userInfo: [NSLocalizedDescriptionKey: "Not enough received inventory for \(item)"])
    }

    private func availableIdentifiers(for item: String, excludingSaleID: UUID?) -> [(identifier: String, lineID: UUID)] {
        let used = Set(data.sales.filter { $0.id != excludingSaleID }.flatMap(\.lines).flatMap(\.assignedIdentifiers))
        return data.purchaseOrders.sorted { $0.purchaseDate < $1.purchaseDate }.flatMap { order in
            order.lines.filter { $0.item.caseInsensitiveCompare(item) == .orderedSame }.flatMap { line in
                line.notes.split(whereSeparator: { $0 == "\n" || $0 == "," }).map { String($0).trimmingCharacters(in: .whitespaces) }
                    .filter { !$0.isEmpty && !used.contains($0) }.map { ($0, line.id) }
            }
        }
    }

    private func importAttachment(_ source: URL, prefix: String) -> String? {
        let access = source.startAccessingSecurityScopedResource()
        defer { if access { source.stopAccessingSecurityScopedResource() } }
        let ext = source.pathExtension.isEmpty ? "dat" : source.pathExtension
        let target = attachmentsURL.appendingPathComponent("\(prefix)_\(UUID().uuidString).\(ext)")
        do { try FileManager.default.copyItem(at: source, to: target); return target.path }
        catch { message = "Attachment could not be copied: \(error.localizedDescription)"; return nil }
    }

    private func autoSync() { if data.business.dropboxAutoSync { syncNow() } }

    private func queueDropboxDeletion(for date: Date, category: String, filename: String) {
        let root = data.business.dropboxRoot.isEmpty ? "/Purchase-Sales-Software" : data.business.dropboxRoot
        let name = periodName(for: date)
        let path = "\(root)/Accounting Periods/\(name)/\(category)/\(filename)"
        if !data.pendingDropboxDeletions.contains(path) { data.pendingDropboxDeletions.append(path) }
    }

    private func periodName(for date: Date) -> String {
        let name = data.accountingPeriods.first(where: { Finance.contains(date, in: $0) })?.name ?? "Unassigned"
        return name.replacingOccurrences(of: "[^A-Za-z0-9._-]", with: "_", options: .regularExpression)
    }

    private func load() {
        guard let content = try? Data(contentsOf: dataURL) else { return }
        do { data = try JSONDecoder.purchaseSales.decode(AppData.self, from: content) }
        catch { message = "Saved data could not be opened: \(error.localizedDescription)" }
    }

    private func persist(_ success: String? = nil) {
        do {
            try JSONEncoder.purchaseSales.encode(data).write(to: dataURL, options: .atomic)
            objectWillChange.send()
            if let success { message = success }
        } catch { message = "Data could not be saved: \(error.localizedDescription)" }
    }
}

struct SaleLineDraft: Identifiable, Hashable {
    var id = UUID()
    var item = ""
    var quantity = 1
    var unitNetPence: Int64 = 0
    var selectedIdentifiers: [String] = []
}

private extension JSONEncoder {
    static var purchaseSales: JSONEncoder { let value = JSONEncoder(); value.dateEncodingStrategy = .iso8601; value.outputFormatting = [.prettyPrinted, .sortedKeys]; return value }
}

private extension JSONDecoder {
    static var purchaseSales: JSONDecoder { let value = JSONDecoder(); value.dateDecodingStrategy = .iso8601; return value }
}
