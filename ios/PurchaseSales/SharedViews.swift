import SwiftUI

struct MetricCard: View {
    let title: String
    let value: String
    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            Text(title).font(.subheadline).foregroundStyle(.secondary)
            Text(value).font(.title2.bold()).minimumScaleFactor(0.7)
        }.frame(maxWidth: .infinity, alignment: .leading).padding().background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
    }
}

struct VATPicker: View {
    @Binding var selection: VATType
    var body: some View {
        Picker("VAT treatment", selection: $selection) {
            ForEach(VATType.allCases) { Text($0.label).tag($0) }
        }
    }
}

struct PeriodPicker: View {
    @EnvironmentObject private var store: AppStore
    var body: some View {
        Picker("Accounting period", selection: Binding(
            get: { store.selectedPeriod?.id },
            set: { id in if let period = store.accountingPeriods.first(where: { $0.id == id }) { store.selectPeriod(period) } }
        )) {
            ForEach(store.accountingPeriods) { Text($0.name).tag(Optional($0.id)) }
        }.pickerStyle(.menu)
    }
}

struct EmptyState: View {
    let title: String
    var body: some View {
        VStack(spacing: 10) { Image(systemName: "tray").font(.system(size: 38)).foregroundStyle(.secondary); Text(title).foregroundStyle(.secondary).multilineTextAlignment(.center) }
            .frame(maxWidth: .infinity, minHeight: 220)
    }
}

struct ActivityShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: items, applicationActivities: nil) }
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

