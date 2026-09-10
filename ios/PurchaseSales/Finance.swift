import Foundation

struct VATBreakdown {
    let netPence: Int64
    let vatPence: Int64
    let grossPence: Int64
    let reverseVatPence: Int64
}

enum Finance {
    static func fromGross(_ gross: Int64, _ type: VATType) -> VATBreakdown {
        switch type {
        case .standard:
            let net = roundedDivide(gross * 5, by: 6)
            return VATBreakdown(netPence: net, vatPence: gross - net, grossPence: gross, reverseVatPence: 0)
        case .reverse:
            return VATBreakdown(netPence: gross, vatPence: 0, grossPence: gross, reverseVatPence: roundedDivide(gross, by: 5))
        case .none:
            return VATBreakdown(netPence: gross, vatPence: 0, grossPence: gross, reverseVatPence: 0)
        }
    }

    static func fromNet(_ net: Int64, _ type: VATType) -> VATBreakdown {
        switch type {
        case .standard:
            let vat = roundedDivide(net, by: 5)
            return VATBreakdown(netPence: net, vatPence: vat, grossPence: net + vat, reverseVatPence: 0)
        case .reverse:
            return VATBreakdown(netPence: net, vatPence: 0, grossPence: net, reverseVatPence: roundedDivide(net, by: 5))
        case .none:
            return VATBreakdown(netPence: net, vatPence: 0, grossPence: net, reverseVatPence: 0)
        }
    }

    static func pence(_ text: String) -> Int64 {
        let cleaned = text.replacingOccurrences(of: "£", with: "").replacingOccurrences(of: ",", with: "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard let decimal = Decimal(string: cleaned, locale: Locale(identifier: "en_GB")) else { return 0 }
        var value = decimal * 100
        var result = Decimal()
        NSDecimalRound(&result, &value, 0, .plain)
        return NSDecimalNumber(decimal: result).int64Value
    }

    static func money(_ pence: Int64) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .currency
        formatter.locale = Locale(identifier: "en_GB")
        return formatter.string(from: NSDecimalNumber(value: Double(pence) / 100.0)) ?? "£0.00"
    }

    static func plain(_ pence: Int64) -> String { String(format: "%.2f", Double(pence) / 100.0) }

    static func contains(_ date: Date, in period: AccountingPeriod) -> Bool {
        let calendar = Calendar.current
        let start = calendar.startOfDay(for: period.start)
        let end = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: period.end))!
        return date >= start && date < end
    }

    private static func roundedDivide(_ value: Int64, by divisor: Int64) -> Int64 {
        let sign: Int64 = value < 0 ? -1 : 1
        return sign * ((abs(value) + divisor / 2) / divisor)
    }
}

enum PurchaseStatus: String {
    case pending = "RECEIPT_PENDING"
    case partial = "PARTIALLY_RECEIVED"
    case received = "RECEIVED"
    case cancelled = "CANCELLED"
    case returned = "RETURNED"
    case refundPending = "REFUND_PENDING"
    case refundReceived = "REFUND_RECEIVED"

    var label: String { rawValue.replacingOccurrences(of: "_", with: " ").capitalized }
}

extension PurchaseOrder {
    var status: PurchaseStatus {
        guard !lines.isEmpty else { return .pending }
        let quantity = lines.reduce(0) { $0 + $1.quantity }
        let received = lines.reduce(0) { $0 + $1.receivedQty }
        let cancelled = lines.reduce(0) { $0 + $1.cancelledQty }
        let returned = lines.reduce(0) { $0 + $1.returnedQty }
        let expected = lines.reduce(Int64(0)) { $0 + ($1.partialRefund ? $1.refundNetPence + $1.refundVatPence : $1.refundExpectedPence) }
        let paid = lines.reduce(Int64(0)) { $0 + $1.refundReceivedPence }
        if expected > paid && expected > 0 { return .refundPending }
        if expected > 0 && paid >= expected { return .refundReceived }
        if cancelled >= quantity { return .cancelled }
        if returned >= received && received > 0 { return .returned }
        if received >= quantity - cancelled { return .received }
        if received > 0 { return .partial }
        return .pending
    }

    var isCancelledOrRefunded: Bool {
        [.cancelled, .returned, .refundPending, .refundReceived].contains(status)
    }

    var isVoidedForAccounting: Bool {
        guard !lines.isEmpty else { return false }
        return lines.allSatisfy { line in
            let fullyCancelled = line.cancelledQty >= line.quantity && line.receivedQty == 0
            let credited = line.partialRefund ? line.refundNetPence + line.refundVatPence : line.refundExpectedPence
            return fullyCancelled || credited >= line.grossPence
        }
    }
}

extension Date {
    var shortUK: String { formatted(.dateTime.day(.twoDigits).month(.twoDigits).year()) }
    var displayUK: String { formatted(.dateTime.day().month(.abbreviated).year()) }
    var compactInvoice: String {
        let formatter = DateFormatter(); formatter.dateFormat = "ddMMyy"; return formatter.string(from: self)
    }
}
