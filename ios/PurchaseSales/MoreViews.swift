import SwiftUI
import UniformTypeIdentifiers

struct MoreView: View {
    var body: some View {
        List {
            NavigationLink { CustomersView() } label: { MoreRow(icon: "person.2", title: "Customers", subtitle: "Saved customer cards and invoice codes") }
            NavigationLink { ExpensesView() } label: { MoreRow(icon: "creditcard", title: "Expenses", subtitle: "Expenses and receipt attachments") }
            NavigationLink { ReportsView() } label: { MoreRow(icon: "chart.bar", title: "Profit & VAT", subtitle: "Profit, VAT due/refund and period export") }
            NavigationLink { DocumentsView() } label: { MoreRow(icon: "folder", title: "Documents & Dropbox", subtitle: "Invoices, spreadsheets and cloud sync") }
            NavigationLink { BusinessView() } label: { MoreRow(icon: "building.2", title: "Business Details", subtitle: "Company, invoice numbering, periods and Dropbox") }
        }.navigationTitle("More")
    }
}

private struct MoreRow: View {
    let icon: String; let title: String; let subtitle: String
    var body: some View { Label { VStack(alignment: .leading) { Text(title).fontWeight(.semibold); Text(subtitle).font(.caption).foregroundStyle(.secondary) } } icon: { Image(systemName: icon) }.padding(.vertical, 5) }
}

struct CustomersView: View {
    @EnvironmentObject private var store: AppStore
    @State private var editor: CustomerEditorContext?
    var body: some View {
        List {
            if store.customers.isEmpty { EmptyState(title: "Create customers for sales invoices") }
            ForEach(store.customers) { customer in
                Button { editor = CustomerEditorContext(customer: customer) } label: { HStack { VStack(alignment: .leading) { Text(customer.companyName).fontWeight(.semibold); Text("Invoice code \(customer.invoiceCode)").font(.caption).foregroundStyle(.secondary) }; Spacer(); Image(systemName: "chevron.right") } }.buttonStyle(.plain)
                    .swipeActions { Button("Delete", role: .destructive) { store.deleteCustomer(customer) } }
            }
        }.navigationTitle("Customers").toolbar { Button { editor = CustomerEditorContext(customer: nil) } label: { Image(systemName: "plus") } }
            .sheet(item: $editor) { context in NavigationStack { CustomerEditorView(existing: context.customer) } }
    }
}

private struct CustomerEditorContext: Identifiable { let id = UUID(); let customer: Customer? }

struct CustomerEditorView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var customer: Customer
    init(existing: Customer?) { _customer = State(initialValue: existing ?? Customer(companyName: "", invoiceCode: "")) }
    var body: some View {
        Form {
            TextField("Company / customer name", text: $customer.companyName); TextField("Invoice code (2–5 chars)", text: $customer.invoiceCode).textInputAutocapitalization(.characters)
            TextField("Billing address", text: $customer.address, axis: .vertical); TextField("Email", text: $customer.email).keyboardType(.emailAddress); TextField("Phone", text: $customer.phone).keyboardType(.phonePad)
            TextField("VAT number", text: $customer.vatNumber); TextField("Company number", text: $customer.companyNumber); TextField("Notes", text: $customer.notes, axis: .vertical)
        }.navigationTitle(customer.companyName.isEmpty ? "New customer" : "Edit customer").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }; ToolbarItem(placement: .confirmationAction) { Button("Save") { customer.invoiceCode = String(customer.invoiceCode.uppercased().prefix(5)); store.saveCustomer(customer); dismiss() } } }
    }
}

struct ExpensesView: View {
    @EnvironmentObject private var store: AppStore
    @State private var editor: ExpenseEditorContext?
    var body: some View {
        List {
            if store.expenses.isEmpty { EmptyState(title: "No expenses recorded") }
            ForEach(store.expenses) { expense in
                Button { editor = ExpenseEditorContext(expense: expense) } label: { VStack(alignment: .leading) { HStack { Text(expense.details).fontWeight(.semibold); Spacer(); Text(Finance.money(expense.grossPence)).fontWeight(.bold) }; Text("\(expense.supplier) • \(expense.expenseDate.displayUK) • \(expense.vatType.label)").font(.caption).foregroundStyle(.secondary) } }.buttonStyle(.plain)
            }
        }.navigationTitle("Expenses").toolbar { Button { editor = ExpenseEditorContext(expense: nil) } label: { Image(systemName: "plus") } }
            .sheet(item: $editor) { context in NavigationStack { ExpenseEditorView(existing: context.expense) } }
    }
}

private struct ExpenseEditorContext: Identifiable { let id = UUID(); let expense: Expense? }

struct ExpenseEditorView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var expense: Expense
    @State private var gross: String
    @State private var attachment: URL?
    @State private var chooseAttachment = false
    init(existing: Expense?) {
        let initial = existing ?? Expense(expenseDate: Date(), supplier: "", details: "", grossPence: 0, netPence: 0, vatPence: 0, reverseVatPence: 0, vatType: .standard)
        _expense = State(initialValue: initial); _gross = State(initialValue: Finance.plain(initial.grossPence))
    }
    var body: some View {
        let split = Finance.fromGross(Finance.pence(gross), expense.vatType)
        Form {
            DatePicker("Date", selection: $expense.expenseDate, displayedComponents: .date); TextField("Store / supplier", text: $expense.supplier); TextField("Details", text: $expense.details); TextField("Account / category", text: $expense.account)
            TextField("Gross total (£)", text: $gross).keyboardType(.decimalPad); VATPicker(selection: $expense.vatType); Text("Net \(Finance.money(split.netPence)) • VAT \(Finance.money(split.vatPence))").font(.caption)
            TextField("Payment method", text: $expense.paymentMethod); TextField("Comments", text: $expense.comments, axis: .vertical)
            Button { chooseAttachment = true } label: { Label(attachment == nil ? "Attach receipt/invoice" : "New receipt selected", systemImage: "paperclip") }
        }.navigationTitle(expense.details.isEmpty ? "New expense" : "Edit expense").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }; ToolbarItem(placement: .confirmationAction) { Button("Save") { expense.grossPence = split.grossPence; expense.netPence = split.netPence; expense.vatPence = split.vatPence; expense.reverseVatPence = split.reverseVatPence; store.saveExpense(expense, attachment: attachment); dismiss() } } }
            .fileImporter(isPresented: $chooseAttachment, allowedContentTypes: [.item]) { if case let .success(url) = $0 { attachment = url } }
    }
}

struct ReportsView: View {
    @EnvironmentObject private var store: AppStore
    @State private var shareURL: URL?
    var body: some View {
        let summary = store.summary
        List {
            Section { PeriodPicker() }
            Section("Trading") { ReportRow("Net sales", summary.salesNet); ReportRow("Cost of goods sold", summary.cogsNet); ReportRow("Gross profit", summary.grossProfit, bold: true); ReportRow("Expenses", summary.expensesNet); ReportRow("Net trading profit", summary.netProfit, bold: true) }
            Section("VAT analysis") { ReportRow("Output VAT on sales", summary.outputVAT); ReportRow("Recoverable input VAT", summary.inputVAT); ReportRow("Reverse VAT output (notional)", summary.reverseOutputVAT); ReportRow("Reverse VAT input (notional)", -summary.reverseInputVAT); ReportRow(summary.vatPosition >= 0 ? "VAT due to HMRC" : "VAT refund expected", abs(summary.vatPosition), bold: true) }
            Section { Button { shareURL = store.exportAccountingWorkbook() } label: { Label("Export selected-period Excel", systemImage: "tablecells") } }
        }.navigationTitle("Profit & VAT").sheet(isPresented: Binding(get: { shareURL != nil }, set: { if !$0 { shareURL = nil } })) { if let shareURL { ActivityShareSheet(items: [shareURL]) } }
    }
}

private struct ReportRow: View {
    let label: String; let amount: Int64; let bold: Bool
    init(_ label: String, _ amount: Int64, bold: Bool = false) { self.label = label; self.amount = amount; self.bold = bold }
    var body: some View { HStack { Text(label).fontWeight(bold ? .bold : .regular); Spacer(); Text(Finance.money(amount)).fontWeight(bold ? .bold : .regular) } }
}

struct DocumentsView: View {
    @EnvironmentObject private var store: AppStore
    @State private var shareURL: URL?
    var body: some View {
        List {
            Section { PeriodPicker() }
            Section("VAT & accounting spreadsheet") { Text("Exports an up-to-date Excel-readable workbook for the selected accounting period. Fully cancelled and fully refunded purchases are excluded.").font(.caption); Button { shareURL = store.exportAccountingWorkbook() } label: { Label("Download VAT spreadsheet", systemImage: "arrow.down.doc") } }
            Section("Document register") { Text("Creates a register of attached purchase invoices, sales PDFs and expense receipts.").font(.caption); Button { shareURL = store.exportDocuments() } label: { Label("Export document register", systemImage: "folder") } }
            Section("Dropbox auto-sync") { Text(store.business.dropboxAutoSync ? "Enabled • \(store.business.dropboxRoot)" : "Disabled — configure in Business Details").font(.caption); Button { store.syncNow() } label: { Label("Sync now", systemImage: "arrow.triangle.2.circlepath") }.disabled(!store.business.dropboxAutoSync) }
        }.navigationTitle("Documents & Dropbox").sheet(isPresented: Binding(get: { shareURL != nil }, set: { if !$0 { shareURL = nil } })) { if let shareURL { ActivityShareSheet(items: [shareURL]) } }
    }
}

struct BusinessView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.openURL) private var openURL
    @State private var business = Business()
    @State private var showHelp = false
    @State private var showPeriod = false
    @State private var editPeriod: AccountingPeriod?
    var body: some View {
        Form {
            Section("Business details") { TextField("Business name", text: $business.businessName); TextField("Business address", text: $business.address, axis: .vertical); TextField("VAT number", text: $business.vatNumber); TextField("Company number", text: $business.companyNumber); TextField("Email", text: $business.email); TextField("Phone", text: $business.phone) }
            Section("Sales invoices") { TextField("Bank/payment details", text: $business.bankDetails, axis: .vertical); TextField("Invoice terms", text: $business.invoiceTerms); TextField("Invoice footer", text: $business.invoiceFooter, axis: .vertical); Toggle("Automatic sales invoice number", isOn: $business.autoInvoiceNumber); Text(business.autoInvoiceNumber ? "Numbers use the customer code and sale date." : "The Sales screen will require your own unique invoice number.").font(.caption) }
            Section("Accounting periods") {
                ForEach(store.accountingPeriods) { period in Button { editPeriod = period } label: { HStack { VStack(alignment: .leading) { Text(period.name); Text("\(period.start.displayUK) – \(period.end.displayUK)").font(.caption).foregroundStyle(.secondary) }; Spacer(); if period.id == store.selectedPeriod?.id { Image(systemName: "checkmark.circle.fill") } } } }
                Button { showPeriod = true } label: { Label("Add accounting period", systemImage: "plus") }
            }
            Section("Dropbox") {
                Text("Documents and workbooks are stored beneath the matching accounting-period folder.").font(.caption)
                Button("How do I get an access token?") { showHelp = true }
                SecureField("Dropbox access token", text: $business.dropboxAccessToken); TextField("Dropbox App Key (optional)", text: $business.dropboxAppKey); SecureField("Dropbox refresh token (optional)", text: $business.dropboxRefreshToken); TextField("Dropbox root folder", text: $business.dropboxRoot); Toggle("Automatic Dropbox sync", isOn: $business.dropboxAutoSync)
            }
            Section { Button("Save business details") { store.saveBusiness(business) }.frame(maxWidth: .infinity) }
        }.navigationTitle("Business Details").onAppear { business = store.business }
            .sheet(isPresented: $showPeriod) { NavigationStack { PeriodEditorView(existing: nil) } }
            .sheet(item: $editPeriod) { period in NavigationStack { PeriodEditorView(existing: period) } }
            .alert("Get a Dropbox access token", isPresented: $showHelp) {
                Button("Open Dropbox App Console") { openURL(URL(string: "https://www.dropbox.com/developers/apps")!) }; Button("Done", role: .cancel) {}
            } message: { Text("Open your Dropbox app, enable files.content.write under Permissions, then generate a token under Settings → OAuth 2. Paste it here, enable automatic sync and save.") }
    }
}

struct PeriodEditorView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var period: AccountingPeriod
    init(existing: AccountingPeriod?) { _period = State(initialValue: existing ?? AccountingPeriod(name: "", start: Date(), end: Date())) }
    var body: some View {
        Form { TextField("Period name", text: $period.name); DatePicker("Start", selection: $period.start, displayedComponents: .date); DatePicker("End", selection: $period.end, in: period.start..., displayedComponents: .date); Section { Button("Use as selected period") { store.savePeriod(period); store.selectPeriod(period); dismiss() }; if store.accountingPeriods.contains(where: { $0.id == period.id }) { Button("Delete period", role: .destructive) { store.deletePeriod(period); dismiss() } } } }
            .navigationTitle("Accounting period").navigationBarTitleDisplayMode(.inline).toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }; ToolbarItem(placement: .confirmationAction) { Button("Save") { store.savePeriod(period); dismiss() } } }
    }
}
