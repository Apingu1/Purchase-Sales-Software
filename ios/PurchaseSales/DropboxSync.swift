import Foundation

enum DropboxSync {
    @MainActor
    static func sync(store: AppStore) async {
        let business = store.business
        guard business.dropboxAutoSync else { store.message = "Dropbox automatic sync is disabled"; return }
        do {
            let token: String
            if !business.dropboxAppKey.isEmpty && !business.dropboxRefreshToken.isEmpty {
                token = try await refreshToken(appKey: business.dropboxAppKey, refreshToken: business.dropboxRefreshToken)
            } else { token = business.dropboxAccessToken }
            guard !token.isEmpty else { throw SyncError.message("Enter a Dropbox access token or App Key + refresh token") }
            let root = business.dropboxRoot.isEmpty ? "/Purchase-Sales-Software" : business.dropboxRoot
            let periodName = store.selectedPeriod?.name.replacingOccurrences(of: "[^A-Za-z0-9._-]", with: "_", options: .regularExpression) ?? "Unassigned"
            var completedDeletions: [String] = []
            for path in store.pendingDropboxDeletions {
                try await deleteIfExists(path: path, token: token)
                completedDeletions.append(path)
            }
            store.completeDropboxDeletions(completedDeletions)
            if let workbook = Exports.accountingWorkbook(data: store.data, period: store.selectedPeriod), let bytes = try? Data(contentsOf: workbook) {
                try await upload(bytes, to: "\(root)/Accounting Periods/\(periodName)/\(workbook.lastPathComponent)", token: token)
            }
            let purchaseDump = store.purchaseOrders.map { order in
                "\(order.purchaseDate.shortUK)\t\(order.supplier)\t\(order.orderNumber)\t\(order.status.label)\t\(order.lines.map { $0.item }.joined(separator: ", "))"
            }.joined(separator: "\n")
            try await upload(Data(purchaseDump.utf8), to: "\(root)/PURCHASES.txt", token: token)
            for order in store.purchaseOrders where !order.isVoidedForAccounting {
                guard let path = order.invoicePath, let bytes = try? Data(contentsOf: URL(fileURLWithPath: path)) else { continue }
                try await upload(bytes, to: "\(root)/Accounting Periods/\(periodName(for: order.purchaseDate, periods: store.accountingPeriods))/Purchases/\(URL(fileURLWithPath: path).lastPathComponent)", token: token)
            }
            for sale in store.sales {
                guard let path = sale.pdfPath, let bytes = try? Data(contentsOf: URL(fileURLWithPath: path)) else { continue }
                try await upload(bytes, to: "\(root)/Accounting Periods/\(periodName(for: sale.saleDate, periods: store.accountingPeriods))/Sales/\(URL(fileURLWithPath: path).lastPathComponent)", token: token)
            }
            for expense in store.expenses {
                guard let path = expense.attachmentPath, let bytes = try? Data(contentsOf: URL(fileURLWithPath: path)) else { continue }
                try await upload(bytes, to: "\(root)/Accounting Periods/\(periodName(for: expense.expenseDate, periods: store.accountingPeriods))/Expenses/\(URL(fileURLWithPath: path).lastPathComponent)", token: token)
            }
            store.message = "Dropbox sync complete"
        } catch { store.message = "Dropbox sync failed: \(error.localizedDescription)" }
    }

    private static func refreshToken(appKey: String, refreshToken: String) async throws -> String {
        var request = URLRequest(url: URL(string: "https://api.dropboxapi.com/oauth2/token")!)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = "grant_type=refresh_token&refresh_token=\(form(refreshToken))&client_id=\(form(appKey))".data(using: .utf8)
        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response, data: data)
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        guard let token = json?["access_token"] as? String else { throw SyncError.message("Dropbox did not return an access token") }
        return token
    }

    private static func upload(_ data: Data, to path: String, token: String) async throws {
        var request = URLRequest(url: URL(string: "https://content.dropboxapi.com/2/files/upload")!)
        request.httpMethod = "POST"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        let argument: [String: Any] = ["path": path, "mode": "overwrite", "autorename": false, "mute": true]
        request.setValue(String(data: try JSONSerialization.data(withJSONObject: argument), encoding: .utf8), forHTTPHeaderField: "Dropbox-API-Arg")
        request.httpBody = data
        let (body, response) = try await URLSession.shared.data(for: request)
        try validate(response, data: body)
    }

    private static func deleteIfExists(path: String, token: String) async throws {
        var request = URLRequest(url: URL(string: "https://api.dropboxapi.com/2/files/delete_v2")!)
        request.httpMethod = "POST"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["path": path])
        let (body, response) = try await URLSession.shared.data(for: request)
        if let http = response as? HTTPURLResponse, http.statusCode == 409,
           String(data: body, encoding: .utf8)?.contains("not_found") == true { return }
        try validate(response, data: body)
    }

    private static func periodName(for date: Date, periods: [AccountingPeriod]) -> String {
        let value = periods.first(where: { Finance.contains(date, in: $0) })?.name ?? "Unassigned"
        return value.replacingOccurrences(of: "[^A-Za-z0-9._-]", with: "_", options: .regularExpression)
    }

    private static func validate(_ response: URLResponse, data: Data) throws {
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw SyncError.message(String(data: data, encoding: .utf8) ?? "Unknown Dropbox error")
        }
    }

    private static func form(_ value: String) -> String { value.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? value }

    private enum SyncError: LocalizedError {
        case message(String)
        var errorDescription: String? { if case let .message(value) = self { return value }; return nil }
    }
}
