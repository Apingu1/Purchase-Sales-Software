import SwiftUI

struct RootView: View {
    @EnvironmentObject private var store: AppStore
    @State private var selected = 0

    var body: some View {
        TabView(selection: $selected) {
            NavigationStack { DashboardView(selectedTab: $selected) }.tabItem { Label("Home", systemImage: "house") }.tag(0)
            NavigationStack { PurchasesView() }.tabItem { Label("Purchases", systemImage: "cart") }.tag(1)
            NavigationStack { InventoryView() }.tabItem { Label("Inventory", systemImage: "shippingbox") }.tag(2)
            NavigationStack { SalesView() }.tabItem { Label("Sales", systemImage: "doc.text") }.tag(3)
            NavigationStack { MoreView() }.tabItem { Label("More", systemImage: "ellipsis") }.tag(4)
        }
        .alert("Purchase & Sales", isPresented: Binding(get: { store.message != nil }, set: { if !$0 { store.message = nil } })) {
            Button("OK") { store.message = nil }
        } message: { Text(store.message ?? "") }
    }
}

struct DashboardView: View {
    @EnvironmentObject private var store: AppStore
    @Binding var selectedTab: Int
    @State private var showPurchase = false
    @State private var showSale = false

    var body: some View {
        let summary = store.summary
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text(store.business.businessName.isEmpty ? "Business dashboard" : store.business.businessName).font(.title2.bold())
                PeriodPicker()
                HStack { MetricCard(title: "Net sales", value: Finance.money(summary.salesNet)); MetricCard(title: "Net profit", value: Finance.money(summary.netProfit)) }
                HStack { MetricCard(title: "Inventory", value: Finance.money(summary.inventoryValue)); MetricCard(title: "Refunds pending", value: Finance.money(summary.refundsPending)) }
                VStack(alignment: .leading, spacing: 6) {
                    Text(summary.vatPosition >= 0 ? "VAT due to HMRC" : "VAT refund expected").font(.subheadline)
                    Text(Finance.money(abs(summary.vatPosition))).font(.title.bold())
                    Text("Output \(Finance.money(summary.outputVAT)) • Input \(Finance.money(summary.inputVAT)) • Reverse VAT nets to £0").font(.caption).foregroundStyle(.secondary)
                }.frame(maxWidth: .infinity, alignment: .leading).padding().background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
                Text("Quick actions").font(.headline)
                HStack {
                    Button { showPurchase = true } label: { Label("Purchase", systemImage: "cart.badge.plus").frame(maxWidth: .infinity) }.buttonStyle(.borderedProminent)
                    Button { showSale = true } label: { Label("Sale", systemImage: "sterlingsign.circle").frame(maxWidth: .infinity) }.buttonStyle(.borderedProminent)
                }
                Button { selectedTab = 1 } label: {
                    HStack { Image(systemName: "truck.box"); VStack(alignment: .leading) { Text("Pending receipts").fontWeight(.semibold); Text("\(pendingCount) purchase order\(pendingCount == 1 ? "" : "s") need attention").font(.caption).foregroundStyle(.secondary) }; Spacer(); Image(systemName: "chevron.right") }
                        .padding().background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
                }.buttonStyle(.plain)
            }.padding()
        }.navigationTitle("Purchase & Sales").sheet(isPresented: $showPurchase) { NavigationStack { PurchaseEditorView() } }.sheet(isPresented: $showSale) { NavigationStack { SaleEditorView() } }
    }

    private var pendingCount: Int {
        store.purchaseOrders.filter { order in
            guard let period = store.selectedPeriod, Finance.contains(order.purchaseDate, in: period) else { return false }
            return [.pending, .partial].contains(order.status)
        }.count
    }
}

struct InventoryView: View {
    @EnvironmentObject private var store: AppStore
    var body: some View {
        List {
            if store.inventory.isEmpty { EmptyState(title: "No received inventory") }
            ForEach(store.inventory) { row in
                VStack(alignment: .leading, spacing: 5) {
                    HStack { Text(row.item).fontWeight(.semibold); Spacer(); Text("\(row.available) available").fontWeight(.bold) }
                    Text("Received \(row.received) • Sold \(row.sold) • Supplier returned \(row.supplierReturned) • Customer restocked \(row.customerRestocked)").font(.caption).foregroundStyle(.secondary)
                    Text("Inventory net cost \(Finance.money(row.inventoryNetCostPence))").font(.caption)
                }.padding(.vertical, 4)
            }
        }.navigationTitle("Inventory")
    }
}

