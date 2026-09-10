import SwiftUI

@main
struct PurchaseSalesApp: App {
    @StateObject private var store = AppStore()

    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(store)
        }
    }
}
