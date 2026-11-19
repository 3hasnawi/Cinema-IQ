import SwiftUI
import UIKit
import CinemaCore

struct TVIPTVSourceForm: View {
    let existing: IPTVSource?
    @EnvironmentObject private var store: IPTVStore
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var kind = IPTVSource.Kind.playlist
    @State private var address = ""
    @State private var username = ""
    @State private var password = ""
    @State private var output = IPTVSource.Output.hls
    @State private var allowHTTP = false
    @State private var message: String?
    @State private var submitting = false
    private enum Field: String { case name, address, username, password }
    @State private var editing: Field?
    @State private var pendingFocus: String?
    @Namespace private var formScope
    @Environment(\.resetFocus) private var resetFocus
    @State private var preferred = "m3u"
    @State private var submission: Task<Void, Never>?
    @FocusState private var focus: String?
    var body: some View {
        NavigationStack {
            ZStack {
                ScrollViewReader { proxy in
                    ScrollView { form }
                        .focusScope(formScope)
                        .task(id: pendingFocus) {
                            guard let target = pendingFocus, editing == nil else { return }
                            proxy.scrollTo(target, anchor: .center)
                            do { try await Task.sleep(for: .milliseconds(300)) } catch { return }
                            guard pendingFocus == target, editing == nil else { return }
                            focus = target; resetFocus(in: formScope); pendingFocus = nil
                        }
                }.disabled(editing != nil)
                if let field = editing { editor(field) }
            }
            .navigationTitle(existing == nil ? "إضافة مصدر IPTV" : "تعديل مصدر IPTV")
        }
        .defaultFocus($focus, preferred)
        .onAppear {
            if let existing {
                name = existing.name; kind = existing.kind; address = existing.address.absoluteString
                username = existing.username; password = existing.password; output = existing.output; allowHTTP = existing.allowHTTP
                preferred = kind == .xtream ? "xtream" : "m3u"; focus = preferred
            }
        }
        .onDisappear { submission?.cancel() }
        .onExitCommand(perform: editing == nil ? { submission?.cancel(); dismiss() } : nil)
    }
    private var form: some View {
        VStack(alignment: .leading, spacing: 22) {
            Section("طريقة الإضافة") {
                HStack(spacing: 28) {
                    Button { kind = .playlist } label: { Label("M3U", systemImage: kind == .playlist ? "checkmark.circle.fill" : "link") }
                        .focused($focus, equals: "m3u")
                        .prefersDefaultFocus(preferred == "m3u", in: formScope).accessibilityIdentifier("tv.iptv.form.m3u")
                    Button { kind = .xtream } label: { Label("Xtream", systemImage: kind == .xtream ? "checkmark.circle.fill" : "person.crop.rectangle") }
                        .focused($focus, equals: "xtream")
                        .prefersDefaultFocus(preferred == "xtream", in: formScope).accessibilityIdentifier("tv.iptv.form.xtream")
                }.buttonStyle(.bordered)
            }
            Section {
                input(.name)
                input(.address)
                if kind == .xtream {
                    input(.username)
                    input(.password)
                    Picker("صيغة البث", selection: $output) {
                        Text("HLS (m3u8) — مفضّل").tag(IPTVSource.Output.hls)
                        Text("MPEG-TS (ts)").tag(IPTVSource.Output.transportStream)
                    }.focused($focus, equals: "output")
                    .prefersDefaultFocus(preferred == "output", in: formScope)
                    .id("output")
                }
            } header: { Text("بيانات المصدر") } footer: {
                Text(kind == .playlist ? "أدخل رابط القائمة من مزوّدك. يمكنك استخدام لوحة مفاتيح Apple TV أو الكتابة من iPhone عند ظهور إشعار لوحة المفاتيح."
                     : "أدخل عنوان الخادم فقط، ثم اسم المستخدم وكلمة المرور. يُفضّل HLS لتوافق أفضل مع Apple TV؛ بعض صيغ TS غير مدعومة.")
            }
            Section {
                Toggle("السماح باتصال HTTP لهذا المصدر", isOn: $allowHTTP)
                    .focused($focus, equals: "allowHTTP")
                    .prefersDefaultFocus(preferred == "allowHTTP", in: formScope)
                    .id("allowHTTP").accessibilityIdentifier("tv.iptv.form.allowHTTP")
            } footer: {
                Text("فعّل الخيار إذا كان رابط القائمة أو بعض قنواتها يستخدم HTTP. الاتصال غير مشفّر وقد تمر بيانات الاشتراك دون تشفير؛ يُفضّل HTTPS.")
            }
            if let message { Section { Text(message).foregroundStyle(.red).accessibilityIdentifier("tv.iptv.form.error") } }
            Section {
                Button(submitting ? "جارٍ الاتصال…" : "اتصال وحفظ", action: connect)
                    .disabled(submitting || address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                              (kind == .xtream && (username.isEmpty || password.isEmpty)))
                    .focused($focus, equals: "save").accessibilityIdentifier("tv.iptv.form.save")
                Button("إلغاء") { submission?.cancel(); dismiss() }
                    .focused($focus, equals: "cancel").accessibilityIdentifier("tv.iptv.form.cancel")
            } footer: { Text("تحفظ بيانات الاشتراك على Apple TV في Keychain، ولا تُرسل إلى حساب المزامنة.") }
        }
        .padding(40).frame(maxWidth: 1380, alignment: .leading)
        .buttonStyle(.bordered)
    }
    private func editor(_ field: Field) -> some View {
        TVIPTVFieldEditor(title: title(field), value: value(field), contentType: contentType(field),
                          returnKey: field == .password || (field == .address && kind == .playlist) ? .done : .next,
                          secure: field == .password) { text, submitted in
            guard editing == field else { return }
            switch field {
            case .name: name = text
            case .address: address = text
            case .username: username = text
            case .password: password = text
            }
            let target = submitted ? next(field) : field.rawValue
            preferred = target
            editing = nil
            pendingFocus = target
        }
        .id(field).frame(width: 1100, height: 82)
    }
    private func contentType(_ field: Field) -> UITextContentType? {
        switch field {
        case .name: return nil
        case .address: return .URL
        case .username: return .username
        case .password: return .password
        }
    }
    private func title(_ field: Field) -> String {
        switch field {
        case .name: return "اسم المصدر"
        case .address: return kind == .playlist ? "رابط قائمة M3U" : "عنوان خادم Xtream"
        case .username: return "اسم المستخدم"
        case .password: return "كلمة المرور"
        }
    }
    private func value(_ field: Field) -> String {
        switch field { case .name: return name; case .address: return address; case .username: return username; case .password: return password }
    }
    private func next(_ field: Field) -> String {
        switch field { case .name: return "address"; case .address: return kind == .xtream ? "username" : "allowHTTP"
        case .username: return "password"; case .password: return "output" }
    }
    private func input(_ field: Field) -> some View {
        let title = self.title(field), value = self.value(field)
        let display = field == .password && !value.isEmpty ? "••••" : value
        return Button { pendingFocus = nil; editing = field } label: {
            HStack { Text(title); Spacer(); Text(display).foregroundStyle(.secondary).lineLimit(1).truncationMode(.middle) }
                .frame(maxWidth: .infinity, minHeight: 62, alignment: .leading)
        }
        .id(field.rawValue).focused($focus, equals: field.rawValue)
        .prefersDefaultFocus(preferred == field.rawValue, in: formScope)
        .accessibilityLabel(title).accessibilityValue(field == .password ? (value.isEmpty ? "" : "محفوظة") : display)
        .privacySensitive(field != .name).accessibilityIdentifier("tv.iptv.form." + field.rawValue)
    }
    private func connect() {
        guard !submitting else { return }
        message = nil
        let text = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard text.count <= 8192, username.count <= 1024, password.count <= 4096 else {
            message = "بيانات المصدر أطول من الحد المدعوم."; return
        }
        guard let url = URL(string: text.contains("://") ? text : "https://" + text) else {
            message = "أدخل رابط HTTP أو HTTPS صالحًا لهذا المصدر."; return
        }
        do {
            let source = try IPTVSource(id: existing?.id ?? UUID().uuidString, name: name, kind: kind, address: url,
                username: kind == .xtream ? username.trimmingCharacters(in: .whitespacesAndNewlines) : "",
                password: kind == .xtream ? password : "", output: output, allowHTTP: allowHTTP)
            submitting = true
            submission = Task { @MainActor in
                defer { submitting = false }
                do { try await store.connectAndSave(source); try Task.checkCancellation(); dismiss() }
                catch { if !Task.isCancelled { message = store.safeMessage(error) } }
            }
        } catch { message = store.safeMessage(error) }
    }
}
