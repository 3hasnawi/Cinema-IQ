import SwiftUI
import CinemaCore

struct IPTVSourceForm: View {
    @EnvironmentObject private var store: IPTVStore
    @Environment(\.dismiss) private var dismiss
    let existing: IPTVSource?
    @State private var name = ""
    @State private var kind = IPTVSource.Kind.playlist
    @State private var address = ""
    @State private var username = ""
    @State private var password = ""
    @State private var output = IPTVSource.Output.hls
    @State private var allowHTTP = false
    @State private var message: String?
    @State private var submitting = false
    @State private var submission: Task<Void, Never>?
    @FocusState private var field: Field?
    private enum Field { case name, address, username, password }
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("اسم المصدر", text: $name)
                        .focused($field, equals: .name).submitLabel(.next)
                        .onSubmit { field = .address }.accessibilityIdentifier("ios.iptv.form.name")
                    Picker("طريقة الإضافة", selection: $kind) {
                        Text("M3U").tag(IPTVSource.Kind.playlist)
                        Text("Xtream").tag(IPTVSource.Kind.xtream)
                    }.pickerStyle(.segmented).accessibilityIdentifier("ios.iptv.form.kind")
                    TextField(kind == .playlist ? "رابط قائمة M3U" : "عنوان خادم Xtream", text: $address)
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                        .focused($field, equals: .address).submitLabel(kind == .xtream ? .next : .done)
                        .onSubmit { if kind == .xtream { field = .username } else { field = nil } }
                        .privacySensitive().accessibilityIdentifier("ios.iptv.form.address")
                    if kind == .xtream {
                        TextField("اسم المستخدم", text: $username).textContentType(.username)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                            .focused($field, equals: .username).submitLabel(.next).onSubmit { field = .password }
                            .privacySensitive().accessibilityIdentifier("ios.iptv.form.username")
                        SecureField("كلمة المرور", text: $password).textContentType(.password)
                            .focused($field, equals: .password).submitLabel(.done).onSubmit { field = nil }
                            .accessibilityIdentifier("ios.iptv.form.password")
                        Picker("صيغة البث", selection: $output) {
                            Text("HLS (m3u8) — مفضّل").tag(IPTVSource.Output.hls)
                            Text("MPEG-TS (ts)").tag(IPTVSource.Output.transportStream)
                        }
                    }
                } header: { Text("المصدر") } footer: {
                    Text(kind == .playlist ? "أدخل رابط قائمة القنوات من مزوّدك. يدعم القسم قوائم M3U وروابط HLS المباشرة."
                         : "أدخل عنوان الخادم فقط، ثم اسم المستخدم وكلمة المرور. يُفضّل HLS لتوافق أفضل مع iPhone وiPad؛ بعض بثوث TS غير مدعومة.")
                }
                Section {
                    Toggle("السماح باتصال HTTP لهذا المصدر", isOn: $allowHTTP)
                        .accessibilityIdentifier("ios.iptv.form.allowHTTP")
                } footer: {
                    Text("فعّل الخيار إذا كان رابط القائمة أو بعض قنواتها يستخدم HTTP. الاتصال غير مشفّر وقد تمر بيانات الاشتراك دون تشفير؛ يُفضّل HTTPS.")
                }
                if let message { Section { Text(message).foregroundStyle(.red).accessibilityIdentifier("ios.iptv.form.error") } }
                Section {
                    Button(action: connect) {
                        HStack { Spacer(); if submitting { ProgressView() }; Text(submitting ? "جارٍ الاتصال…" : "اتصال وإضافة"); Spacer() }
                    }
                    .disabled(submitting || address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                              (kind == .xtream && (username.isEmpty || password.isEmpty)))
                    .accessibilityIdentifier("ios.iptv.form.connect")
                } footer: { Text("تحفظ بيانات المصدر على هذا الجهاز في Keychain، ولا تُرسل إلى حساب المزامنة.") }
            }
            .scrollDismissesKeyboard(.interactively)
            .navigationTitle(existing == nil ? "إضافة مصدر IPTV" : "تعديل مصدر IPTV")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("إلغاء") { submission?.cancel(); dismiss() }.accessibilityIdentifier("ios.iptv.form.cancel")
                }
            }
            .onAppear {
                guard let existing else { return }
                name = existing.name; kind = existing.kind; address = existing.address.absoluteString
                username = existing.username; password = existing.password; output = existing.output; allowHTTP = existing.allowHTTP
            }
            .onDisappear { submission?.cancel() }
            .onChange(of: kind) { _, value in
                if value == .playlist { username = ""; password = "" }
            }
        }
    }
    private func connect() {
        field = nil; message = nil
        let text = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard text.count <= 8192, username.count <= 1024, password.count <= 4096 else {
            message = "بيانات المصدر أطول من الحد المدعوم."; return
        }
        guard let url = URL(string: text.contains("://") ? text : "https://" + text) else { message = IPTVError.invalidURL.localizedDescription; return }
        do {
            let source = try IPTVSource(id: existing?.id ?? UUID().uuidString, name: name, kind: kind, address: url,
                username: kind == .xtream ? username.trimmingCharacters(in: .whitespacesAndNewlines) : "",
                password: kind == .xtream ? password : "",
                output: output, allowHTTP: allowHTTP)
            submitting = true
            submission = Task { @MainActor in
                defer { submitting = false }
                do { try await store.connectAndSave(source); try Task.checkCancellation(); dismiss() }
                catch { if !Task.isCancelled { message = store.safeMessage(error) } }
            }
        } catch { message = store.safeMessage(error) }
    }
}
