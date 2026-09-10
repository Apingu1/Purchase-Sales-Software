import SwiftUI
import UniformTypeIdentifiers

private enum PurchaseFilter: String, CaseIterable, Identifiable {
    case pending = "Pending receipts", received = "Received", cancelled = "Cancelled / refunded", all = "All"
    var id: String { rawValue }
}

struct PurchasesView: View {
    @EnvironmentObject private var store: AppStore
    @State private var filter: PurchaseFilter = .pending
    @State private var query = ""
    @State private var editor: PurchaseEditorContext?

    var body: some View {
        List {
            Section { PeriodPicker(); Picker("View", selection: $filter) { ForEach(PurchaseFilter.allCases) { Text($0.rawValue).tag($0) } } }
            if filtered.isEmpty { EmptyState(title: "No purchase orders match this view") }
            ForEach(filtered) { order in
                Button { editor = PurchaseEditorContext(order: order, duplicate: false) } label: { PurchaseOrderRow(order: order) }.buttonStyle(.plain)
                    .swipeActions(edge: .leading) {
                        if [.pending, .partial, .received].contains(order.status) { Button { editor = PurchaseEditorContext(order: order, duplicate: true) } label: { Label("Duplicate", systemImage: "doc.on.doc") }.tint(.blue) }
                    }
                    .swipeActions(edge: .trailing) {
                        if [.pending, .partial].contains(order.status) { Button { store.markReceivedAll(order) } label: { Label("Receive all", systemImage: "checkmark.circle") }.tint(.green) }
                    }
            }
        }.navigationTitle("Purchases").searchable(text: $query, prompt: "Supplier, item or order")
            .toolbar { ToolbarItem(placement: .primaryAction) { Button { editor = PurchaseEditorContext(order: nil, duplicate: false) } label: { Image(systemName: "plus") } } }
            .sheet(item: $editor) { context in NavigationStack { PurchaseEditorView(source: context.order, duplicate: context.duplicate) } }
    }

    private var filtered: [PurchaseOrder] {
        store.purchaseOrders.filter { order in
            guard let period = store.selectedPeriod, Finance.contains(order.purchaseDate, in: period) else { return false }
            let statusMatches: Bool
            switch filter {
            case .pending: statusMatches = [.pending, .partial].contains(order.status)
            case .received: statusMatches = order.status == .received
            case .cancelled: statusMatches = order.isCancelledOrRefunded
            case .all: statusMatches = true
            }
            let q = query.trimmingCharacters(in: .whitespaces)
            return statusMatches && (q.isEmpty || order.supplier.localizedCaseInsensitiveContains(q) || order.orderNumber.localizedCaseInsensitiveContains(q) || order.lines.contains { $0.item.localizedCaseInsensitiveContains(q) || $0.notes.localizedCaseInsensitiveContains(q) })
        }
    }
}

private struct PurchaseEditorContext: Identifiable {
    let id = UUID(); let order: PurchaseOrder?; let duplicate: Bool
}

private struct PurchaseOrderRow: View {
    let order: PurchaseOrder
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack { VStack(alignment: .leading) { Text(order.supplier).fontWeight(.semibold); Text(order.purchaseDate.displayUK + (order.orderNumber.isEmpty ? "" : " • \(order.orderNumber)")).font(.caption) }; Spacer(); Text(order.status.label).font(.caption2).padding(6).background(.thinMaterial, in: Capsule()) }
            Text(order.lines.prefix(2).map { "\($0.item) × \($0.quantity)" }.joined(separator: " • ") + (order.lines.count > 2 ? " • +\(order.lines.count - 2) more" : ""))
            Text("Qty \(order.lines.reduce(0) { $0 + $1.quantity }) • \(Finance.money(order.lines.reduce(Int64(0)) { $0 + $1.grossPence })) • \(order.vatType.label)").font(.caption).foregroundStyle(.secondary)
        }.padding(.vertical, 5)
    }
}

private struct PurchaseLineForm: Identifiable {
    var id = UUID(); var item = ""; var quantity = 1; var unitGross = ""; var notes = ""; var received = 0; var cancelled = 0; var returned = 0
    var refundExpected = ""; var refundReceived = ""; var partialRefund = false; var refundNet = ""; var refundVAT = ""
}

struct PurchaseEditorView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    private let existingID: UUID?
    private let isDuplicate: Bool
    @State private var date: Date
    @State private var supplier: String
    @State private var orderNumber: String
    @State private var account: String
    @State private var vatType: VATType
    @State private var payment: String
    @State private var notes: String
    @State private var lines: [PurchaseLineForm]
    @State private var attachment: URL?
    @State private var chooseAttachment = false

    init(source: PurchaseOrder? = nil, duplicate: Bool = false) {
        existingID = duplicate ? nil : source?.id; isDuplicate = duplicate
        _date = State(initialValue: duplicate ? Date() : source?.purchaseDate ?? Date())
        _supplier = State(initialValue: source?.supplier ?? ""); _orderNumber = State(initialValue: duplicate ? "" : source?.orderNumber ?? "")
        _account = State(initialValue: source?.accountUsername ?? ""); _vatType = State(initialValue: source?.vatType ?? .standard)
        _payment = State(initialValue: source?.paymentMethod ?? ""); _notes = State(initialValue: duplicate ? "" : source?.notes ?? "")
        let forms = source?.lines.map { line in PurchaseLineForm(item: line.item, quantity: line.quantity, unitGross: Finance.plain(line.quantity > 0 ? line.grossPence / Int64(line.quantity) : 0), notes: duplicate ? "" : line.notes, received: duplicate ? 0 : line.receivedQty, cancelled: duplicate ? 0 : line.cancelledQty, returned: duplicate ? 0 : line.returnedQty, refundExpected: duplicate ? "" : Finance.plain(line.refundExpectedPence), refundReceived: duplicate ? "" : Finance.plain(line.refundReceivedPence), partialRefund: duplicate ? false : line.partialRefund, refundNet: duplicate ? "" : Finance.plain(line.refundNetPence), refundVAT: duplicate ? "" : Finance.plain(line.refundVatPence)) } ?? [PurchaseLineForm()]
        _lines = State(initialValue: forms)
    }

    var body: some View {
        Form {
            if isDuplicate { Section { Text("Order number, order notes, IMEI/serial notes, receipt/refund fields and invoice attachment were cleared for the new order.").font(.caption) } }
            Section("Purchase details") {
                DatePicker("Purchase date", selection: $date, displayedComponents: .date); TextField("Supplier / Store", text: $supplier); TextField("Order number", text: $orderNumber); TextField("Email / Username", text: $account); VATPicker(selection: $vatType); TextField("Payment method", text: $payment)
            }
            Section { Text("Enter the gross cost for one unit. The app multiplies it by Quantity and calculates net and VAT.").font(.caption).foregroundStyle(.secondary) } header: { Text("Items purchased") }
            ForEach($lines) { $line in
                Section("Item \((lines.firstIndex(where: { $0.id == line.id }) ?? 0) + 1)") {
                    TextField("Item", text: $line.item); Stepper("Quantity: \(line.quantity)", value: $line.quantity, in: 1...9999)
                    TextField("Gross cost for 1 unit (£)", text: $line.unitGross).keyboardType(.decimalPad)
                    Text("Line gross \(Finance.money(Finance.pence(line.unitGross) * Int64(line.quantity)))").font(.caption)
                    TextField("Item notes / IMEI / serial number", text: $line.notes, axis: .vertical)
                    Stepper("Received: \(line.received)", value: $line.received, in: 0...line.quantity)
                    Stepper("Cancelled: \(line.cancelled)", value: $line.cancelled, in: 0...line.quantity)
                    Stepper("Returned: \(line.returned)", value: $line.returned, in: 0...line.received)
                    Toggle("Partial refund", isOn: $line.partialRefund)
                    if line.partialRefund { TextField("Refund net (£)", text: $line.refundNet).keyboardType(.decimalPad); TextField("Refund VAT (£)", text: $line.refundVAT).keyboardType(.decimalPad) }
                    else { TextField("Refund expected (£)", text: $line.refundExpected).keyboardType(.decimalPad); TextField("Refund received (£)", text: $line.refundReceived).keyboardType(.decimalPad) }
                    if lines.count > 1 { Button("Remove item", role: .destructive) { lines.removeAll { $0.id == line.id } } }
                }
            }
            Section { Button { lines.append(PurchaseLineForm()) } label: { Label("Add another item", systemImage: "plus") } }
            Section("Purchase total") { Text(Finance.money(total.grossPence)).font(.title2.bold()); Text("Net \(Finance.money(total.netPence)) • VAT \(Finance.money(total.vatPence))") }
            Section("Additional details") { TextField("Order notes", text: $notes, axis: .vertical); Button { chooseAttachment = true } label: { Label(attachment == nil ? "Attach purchase invoice" : "New invoice selected", systemImage: "paperclip") } }
        }.navigationTitle(isDuplicate ? "Duplicate purchase" : existingID == nil ? "New purchase" : "Edit purchase").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }; ToolbarItem(placement: .confirmationAction) { Button("Save") { save() } } }
            .fileImporter(isPresented: $chooseAttachment, allowedContentTypes: [.item]) { if case let .success(url) = $0 { attachment = url } }
    }

    private var total: VATBreakdown { Finance.fromGross(lines.reduce(Int64(0)) { $0 + Finance.pence($1.unitGross) * Int64($1.quantity) }, vatType) }
    private func save() {
        let values = lines.map { form -> PurchaseLine in
            let gross = Finance.pence(form.unitGross) * Int64(form.quantity); let split = Finance.fromGross(gross, vatType)
            let expected = form.partialRefund ? Finance.pence(form.refundNet) + Finance.pence(form.refundVAT) : Finance.pence(form.refundExpected)
            let refundSplit = Finance.fromGross(expected, vatType)
            return PurchaseLine(item: form.item, quantity: form.quantity, grossPence: gross, netPence: split.netPence, vatPence: split.vatPence, reverseVatPence: split.reverseVatPence, receivedQty: form.received, cancelledQty: form.cancelled, returnedQty: form.returned, refundExpectedPence: form.partialRefund ? 0 : expected, refundReceivedPence: form.partialRefund ? 0 : Finance.pence(form.refundReceived), partialRefund: form.partialRefund, refundNetPence: form.partialRefund ? Finance.pence(form.refundNet) : refundSplit.netPence, refundVatPence: form.partialRefund ? Finance.pence(form.refundVAT) : refundSplit.vatPence, notes: form.notes)
        }
        let oldPath = existingID.flatMap { id in store.purchaseOrders.first(where: { $0.id == id })?.invoicePath }
        store.savePurchase(PurchaseOrder(id: existingID ?? UUID(), purchaseDate: date, supplier: supplier, orderNumber: orderNumber, accountUsername: account, vatType: vatType, paymentMethod: payment, invoicePath: oldPath, notes: notes, lines: values), attachment: attachment)
        dismiss()
    }
}
