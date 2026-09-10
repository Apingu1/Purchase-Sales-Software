import Foundation

enum VATType: String, Codable, CaseIterable, Identifiable {
    case standard = "STANDARD"
    case reverse = "REVERSE"
    case none = "NONE"
    var id: String { rawValue }
    var label: String {
        switch self {
        case .standard: return "Standard VAT 20%"
        case .reverse: return "Reverse VAT"
        case .none: return "No VAT"
        }
    }
}

struct Business: Codable, Equatable {
    var businessName = ""
    var address = ""
    var vatNumber = ""
    var companyNumber = ""
    var email = ""
    var phone = ""
    var bankDetails = ""
    var invoiceTerms = ""
    var invoiceFooter = ""
    var dropboxAccessToken = ""
    var dropboxAppKey = ""
    var dropboxRefreshToken = ""
    var dropboxRoot = "/Purchase-Sales-Software"
    var dropboxAutoSync = false
    var autoInvoiceNumber = true
    var selectedAccountingPeriodID: UUID?
}

struct AccountingPeriod: Identifiable, Codable, Hashable {
    var id = UUID()
    var name: String
    var start: Date
    var end: Date
    var createdAt = Date()
}

struct Customer: Identifiable, Codable, Hashable {
    var id = UUID()
    var companyName: String
    var invoiceCode: String
    var address = ""
    var email = ""
    var phone = ""
    var vatNumber = ""
    var companyNumber = ""
    var notes = ""
}

struct PurchaseOrder: Identifiable, Codable, Hashable {
    var id = UUID()
    var purchaseDate: Date
    var supplier: String
    var orderNumber = ""
    var accountUsername = ""
    var vatType: VATType
    var paymentMethod = ""
    var invoicePath: String?
    var notes = ""
    var updatedAt = Date()
    var lines: [PurchaseLine]
}

struct PurchaseLine: Identifiable, Codable, Hashable {
    var id = UUID()
    var item: String
    var quantity: Int
    var grossPence: Int64
    var netPence: Int64
    var vatPence: Int64
    var reverseVatPence: Int64
    var receivedQty = 0
    var cancelledQty = 0
    var returnedQty = 0
    var refundExpectedPence: Int64 = 0
    var refundReceivedPence: Int64 = 0
    var partialRefund = false
    var refundNetPence: Int64 = 0
    var refundVatPence: Int64 = 0
    var notes = ""
}

struct Sale: Identifiable, Codable, Hashable {
    var id = UUID()
    var invoiceNo: String
    var saleDate: Date
    var customerID: UUID
    var vatType: VATType
    var netPence: Int64
    var vatPence: Int64
    var grossPence: Int64
    var reverseVatPence: Int64
    var notes = ""
    var pdfPath: String?
    var updatedAt = Date()
    var lines: [SaleLine]
}

struct SaleLine: Identifiable, Codable, Hashable {
    var id = UUID()
    var item: String
    var quantity: Int
    var unitNetPence: Int64
    var lineGrossPence: Int64
    var lineNetPence: Int64
    var lineVatPence: Int64
    var allocations: [SaleAllocation]
    var assignedIdentifiers: [String] = []
    var returns: [CustomerReturn] = []
}

struct SaleAllocation: Identifiable, Codable, Hashable {
    var id = UUID()
    var purchaseOrderID: UUID
    var purchaseLineID: UUID
    var quantity: Int
    var unitNetCostPence: Int64
}

struct CustomerReturn: Identifiable, Codable, Hashable {
    var id = UUID()
    var returnDate: Date
    var quantity: Int
    var refundGrossPence: Int64
    var refundNetPence: Int64
    var refundVatPence: Int64
    var restock: Bool
    var notes = ""
}

struct Expense: Identifiable, Codable, Hashable {
    var id = UUID()
    var expenseDate: Date
    var supplier: String
    var details: String
    var account = ""
    var grossPence: Int64
    var netPence: Int64
    var vatPence: Int64
    var reverseVatPence: Int64
    var vatType: VATType
    var paymentMethod = ""
    var attachmentPath: String?
    var comments = ""
    var updatedAt = Date()
}

struct AppData: Codable {
    var schemaVersion = 1
    var business = Business()
    var accountingPeriods: [AccountingPeriod] = []
    var customers: [Customer] = []
    var purchaseOrders: [PurchaseOrder] = []
    var sales: [Sale] = []
    var expenses: [Expense] = []
    var usedManualInvoiceNumbers: Set<String> = []
    var pendingDropboxDeletions: [String] = []
}

struct InventoryRow: Identifiable {
    var id: String { item.lowercased() }
    let item: String
    let purchased: Int
    let received: Int
    let supplierReturned: Int
    let sold: Int
    let customerRestocked: Int
    let available: Int
    let inventoryNetCostPence: Int64
}

struct FinanceSummary {
    var salesNet: Int64 = 0
    var cogsNet: Int64 = 0
    var expensesNet: Int64 = 0
    var grossProfit: Int64 = 0
    var netProfit: Int64 = 0
    var outputVAT: Int64 = 0
    var inputVAT: Int64 = 0
    var reverseOutputVAT: Int64 = 0
    var reverseInputVAT: Int64 = 0
    var vatPosition: Int64 = 0
    var inventoryValue: Int64 = 0
    var refundsPending: Int64 = 0
}
