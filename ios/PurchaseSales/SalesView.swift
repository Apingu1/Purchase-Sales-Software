import SwiftUI

struct SalesView: View {
    @EnvironmentObject private var store: AppStore
    @State private var editor: SaleEditorContext?
    @State private var shareURL: URL?
    @State private var deleteTarget: Sale?
    @State private var returnTarget: ReturnContext?

    var body: some View {
        List {
            Section { PeriodPicker() }
            if periodSales.isEmpty { EmptyState(title: "No sales invoices in this accounting period") }
            ForEach(periodSales) { sale in
                VStack(alignment: .leading, spacing: 7) {
                    HStack { Text(sale.invoiceNo).fontWeight(.bold); Spacer(); Text(Finance.money(sale.grossPence)).fontWeight(.bold) }
                    Text("\(customerName(sale)) • \(sale.saleDate.displayUK) • \(sale.vatType.label)").font(.caption).foregroundStyle(.secondary)
                    Text(sale.lines.map { "\($0.item) × \($0.quantity)" }.joined(separator: " • ")).font(.subheadline)
                    Menu("Invoice actions") {
                        Button { editor = SaleEditorContext(sale: sale) } label: { Label("Edit", systemImage: "pencil") }
                        if let path = sale.pdfPath { Button { shareURL = URL(fileURLWithPath: path) } label: { Label("Download / share PDF", systemImage: "square.and.arrow.up") } }
                        ForEach(sale.lines) { line in Button { returnTarget = ReturnContext(saleID: sale.id, line: line) } label: { Label("Return \(line.item)", systemImage: "arrow.uturn.backward") } }
                        Button(role: .destructive) { deleteTarget = sale } label: { Label("Delete sales invoice", systemImage: "trash") }
                    }.font(.caption)
                }.padding(.vertical, 4)
            }
        }.navigationTitle("Sales")
            .toolbar { ToolbarItem(placement: .primaryAction) { Button { editor = SaleEditorContext(sale: nil) } label: { Image(systemName: "plus") } } }
            .sheet(item: $editor) { context in NavigationStack { SaleEditorView(existing: context.sale) } }
            .sheet(item: $returnTarget) { context in NavigationStack { CustomerReturnView(context: context) } }
            .sheet(isPresented: Binding(get: { shareURL != nil }, set: { if !$0 { shareURL = nil } })) { if let shareURL { ActivityShareSheet(items: [shareURL]) } }
            .confirmationDialog("Delete \(deleteTarget?.invoiceNo ?? "invoice")?", isPresented: Binding(get: { deleteTarget != nil }, set: { if !$0 { deleteTarget = nil } }), titleVisibility: .visible) {
                Button("Delete sales invoice", role: .destructive) { if let sale = deleteTarget { store.deleteSale(sale) }; deleteTarget = nil }
            } message: { Text("This removes the sale, restores its allocated stock and deletes the locally generated PDF.") }
    }

    private var periodSales: [Sale] { store.sales.filter { sale in guard let period = store.selectedPeriod else { return false }; return Finance.contains(sale.saleDate, in: period) } }
    private func customerName(_ sale: Sale) -> String { store.customers.first(where: { $0.id == sale.customerID })?.companyName ?? "Unknown customer" }
}

private struct SaleEditorContext: Identifiable { let id = UUID(); let sale: Sale? }

struct SaleEditorView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    private let existingID: UUID?
    @State private var date: Date
    @State private var customerID: UUID?
    @State private var vatType: VATType
    @State private var notes: String
    @State private var invoiceNumber: String
    @State private var lines: [SaleLineDraft]

    init(existing: Sale? = nil) {
        existingID = existing?.id
        _date = State(initialValue: existing?.saleDate ?? Date()); _customerID = State(initialValue: existing?.customerID)
        _vatType = State(initialValue: existing?.vatType ?? .standard); _notes = State(initialValue: existing?.notes ?? "")
        _invoiceNumber = State(initialValue: existing?.invoiceNo ?? "")
        _lines = State(initialValue: existing?.lines.map { SaleLineDraft(item: $0.item, quantity: $0.quantity, unitNetPence: $0.unitNetPence, selectedIdentifiers: $0.assignedIdentifiers) } ?? [SaleLineDraft()])
    }

    var body: some View {
        Form {
            if store.customers.isEmpty { Section { Text("Create a customer in More → Customers before recording a sale.").foregroundStyle(.secondary) } }
            Section("Invoice details") {
                DatePicker("Sale date", selection: $date, displayedComponents: .date)
                Picker("Customer", selection: $customerID) { Text("Select customer").tag(Optional<UUID>.none); ForEach(store.customers) { Text($0.companyName).tag(Optional($0.id)) } }
                VATPicker(selection: $vatType)
                if !store.business.autoInvoiceNumber { TextField("Invoice number", text: $invoiceNumber).textInputAutocapitalization(.characters) }
                TextField("Notes", text: $notes, axis: .vertical)
            }
            ForEach($lines) { $line in
                Section("Sales item \((lines.firstIndex(where: { $0.id == line.id }) ?? 0) + 1)") {
                    Picker("Inventory item", selection: $line.item) { Text("Select item").tag(""); ForEach(store.inventory.filter { $0.available > 0 }) { Text("\($0.item) (\($0.available) available)").tag($0.item) } }
                    Stepper("Quantity: \(line.quantity)", value: $line.quantity, in: 1...max(1, available(for: line.item)))
                    TextField("Unit net selling price (£)", text: Binding(get: { Finance.plain(line.unitNetPence) }, set: { line.unitNetPence = Finance.pence($0) })).keyboardType(.decimalPad)
                    let split = Finance.fromNet(line.unitNetPence, vatType)
                    Text("Line net \(Finance.money(split.netPence * Int64(line.quantity))) • VAT \(Finance.money(split.vatPence * Int64(line.quantity))) • Gross \(Finance.money(split.grossPence * Int64(line.quantity)))").font(.caption)
                    let identifiers = store.identifierOptions(for: line.item, excludingSaleID: existingID)
                    if identifiers.count > line.quantity {
                        Text("Choose exactly \(line.quantity) IMEI/serial number(s)").font(.caption).fontWeight(.semibold)
                        ForEach(identifiers, id: \.self) { identifier in
                            Button {
                                if line.selectedIdentifiers.contains(identifier) { line.selectedIdentifiers.removeAll { $0 == identifier } }
                                else if line.selectedIdentifiers.count < line.quantity { line.selectedIdentifiers.append(identifier) }
                            } label: {
                                HStack { Text(identifier); Spacer(); Image(systemName: line.selectedIdentifiers.contains(identifier) ? "checkmark.circle.fill" : "circle") }
                            }.buttonStyle(.plain)
                        }
                        Text("\(line.selectedIdentifiers.count) of \(line.quantity) selected").font(.caption).foregroundStyle(.secondary)
                    }
                    if lines.count > 1 { Button("Remove item", role: .destructive) { lines.removeAll { $0.id == line.id } } }
                }
            }
            Section { Button { lines.append(SaleLineDraft()) } label: { Label("Add another item", systemImage: "plus") } }
            Section("Invoice total") { Text(Finance.money(totalGross)).font(.title2.bold()); Text("Net \(Finance.money(totalNet)) • VAT \(Finance.money(totalVAT))") }
        }.navigationTitle(existingID == nil ? "New sale" : "Edit sale").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }; ToolbarItem(placement: .confirmationAction) { Button("Save") { save() }.disabled(customerID == nil || lines.contains { $0.item.isEmpty }) } }
    }

    private func available(for item: String) -> Int { store.inventory.first(where: { $0.item.caseInsensitiveCompare(item) == .orderedSame })?.available ?? 1 }
    private var totalNet: Int64 { lines.reduce(0) { $0 + $1.unitNetPence * Int64($1.quantity) } }
    private var totalVAT: Int64 { lines.reduce(0) { $0 + Finance.fromNet($1.unitNetPence, vatType).vatPence * Int64($1.quantity) } }
    private var totalGross: Int64 { totalNet + totalVAT }
    private func save() {
        guard let customer = store.customers.first(where: { $0.id == customerID }) else { return }
        store.saveSale(id: existingID, date: date, customer: customer, vatType: vatType, notes: notes, manualInvoice: invoiceNumber, drafts: lines)
        dismiss()
    }
}

struct ReturnContext: Identifiable {
    let id = UUID(); let saleID: UUID; let line: SaleLine
}

struct CustomerReturnView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    let context: ReturnContext
    @State private var date = Date()
    @State private var quantity = 1
    @State private var restock = true
    @State private var notes = ""

    var body: some View {
        Form {
            Section { Text(context.line.item).fontWeight(.semibold); DatePicker("Return date", selection: $date, displayedComponents: .date); Stepper("Quantity: \(quantity)", value: $quantity, in: 1...maxReturn); Toggle("Return item to inventory", isOn: $restock); TextField("Return notes", text: $notes, axis: .vertical) }
        }.navigationTitle("Customer return").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }; ToolbarItem(placement: .confirmationAction) { Button("Save") { store.recordReturn(saleID: context.saleID, lineID: context.line.id, quantity: quantity, date: date, restock: restock, notes: notes); dismiss() } } }
    }
    private var maxReturn: Int { max(1, context.line.quantity - context.line.returns.reduce(0) { $0 + $1.quantity }) }
}
