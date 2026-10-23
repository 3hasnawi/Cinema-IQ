import SwiftUI
import CinemaCore

/// Settings section: the demo and the added providers, with adding and removing.
struct ExternalSourcesSection: View {
    @EnvironmentObject private var store: ProviderStore
    @AppStorage(SourcePreference.key) private var sourceName = SourcePreference.fallback.rawValue
    @State private var adding = false
    var body: some View {
        Section {
            ForEach([ExternalProvider.demo] + store.providers) { provider in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(provider.name)
                        Text(provider.id == "demo" ? "أفلام مرخّصة للتجربة" : (provider.baseURL.host ?? ""))
                            .font(.caption).foregroundStyle(Theme.muted)
                    }
                    Spacer()
                    if sourceName == ContentSource.external(provider.id).rawValue {
                        Image(systemName: "checkmark").foregroundStyle(Theme.accent)
                    }
                }
                .contentShape(Rectangle())
                .onTapGesture { sourceName = ContentSource.external(provider.id).rawValue }
                .swipeActions {
                    if provider.id != "demo" {
                        Button("حذف", role: .destructive) {
                            if sourceName == ContentSource.external(provider.id).rawValue { sourceName = SourcePreference.fallback.rawValue }
                            store.remove(provider)
                        }
                    }
                }
            }
            Button { adding = true } label: { Label("إضافة مصدر", systemImage: "plus") }
        } header: {
            Text("المصادر الخارجية")
        } footer: {
            Text("أضف واجهة REST تعطي المحتوى بصيغة Cinema iQ (روابط MP4 أو HLS عبر HTTPS). اضغط مصدراً لاختياره، واسحب لحذفه.")
        }
        .sheet(isPresented: $adding) { AddProviderSheet() }
    }
}

private struct AddProviderSheet: View {
    @EnvironmentObject private var store: ProviderStore
    @AppStorage(SourcePreference.key) private var sourceName = SourcePreference.fallback.rawValue
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var address = "https://"
    @State private var token = ""
    @State private var busy = false
    @State private var error: String?
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("الاسم", text: $name)
                    TextField("عنوان الواجهة", text: $address)
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                        .environment(\.layoutDirection, .leftToRight)
                    SecureField("رمز الوصول (اختياري)", text: $token)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                } footer: {
                    Text("يُفحص العنوان بطلب صفحته الرئيسية قبل الحفظ. رمز الوصول يُحفظ في سلسلة المفاتيح ويُرسل كـ Authorization: Bearer.")
                }
                if let error { Section { Text(error).foregroundStyle(.red) } }
            }
            .navigationTitle("إضافة مصدر")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("إلغاء") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if busy { ProgressView() } else {
                        Button("فحص وإضافة") {
                            busy = true; error = nil
                            Task {
                                do {
                                    let provider = try await store.add(name: name, address: address, token: token)
                                    sourceName = ContentSource.external(provider.id).rawValue
                                    dismiss()
                                } catch is CancellationError {
                                } catch { self.error = error.localizedDescription }
                                busy = false
                            }
                        }
                        .disabled(address.count < 12)
                    }
                }
            }
        }
    }
}
