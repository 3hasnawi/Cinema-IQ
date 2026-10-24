import SwiftUI
import CinemaCore

/// Settings: content source, Cinema iQ account and profile, subtitles, external providers, about.
struct TVSettingsView: View {
    @EnvironmentObject private var providers: ProviderStore
    @EnvironmentObject private var router: TVRouter
    @AppStorage(SourcePreference.key) private var sourceName = SourcePreference.fallback.rawValue
    @AppStorage(SubtitlePreference.sizeKey) private var subtitleSize = 20.0
    @AppStorage(SubtitlePreference.languageKey) private var subtitleLanguage = "ar"
    @AppStorage(SubtitlePreference.backgroundKey) private var subtitleBackground = 0.5
    /// The row whose popup is open, to take focus back when it closes.
    @FocusState private var row: SettingsRow?
    @State private var lastRow: SettingsRow?
    private var version: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "" }
    private var sources: [ContentSource] { ContentSource.builtIn + ([ExternalProvider.demo] + providers.providers).map { .external($0.id) } }

    private enum SettingsRow: Hashable { case source, language, size, background }

    var body: some View {
        HStack(alignment: .top, spacing: 80) {
            VStack(spacing: 18) {
                Image("Logo").resizable().frame(width: 220, height: 220)
                    .clipShape(RoundedRectangle(cornerRadius: 48, style: .continuous))
                HStack(spacing: 0) {
                    Text("Cinema").foregroundStyle(TVTheme.accent)
                    Text(" iQ")
                }
                .font(.system(size: 52, weight: .heavy))
                .environment(\.layoutDirection, .leftToRight)
                Text("الإصدار \(version)").foregroundStyle(TVTheme.muted)
                Text("تطوير Emad Hasnawi").font(.callout).foregroundStyle(TVTheme.muted)
            }
            .frame(width: 520)
            .padding(.top, 60)

            List {
                Section("مصدر المحتوى") {
                    optionRow(.source, "المصدر", value: SourcePreference.source(sourceName).title,
                              options: sources.map { ($0.rawValue, $0.title) }, selected: sourceName) { sourceName = $0 }
                    NavigationLink("المصادر الخارجية") { TVProvidersView() }
                }
                Section("حساب Cinema iQ والمزامنة") { TVAccountRows() }
                Section("الترجمة") {
                    optionRow(.language, "الترجمة المفضلة", value: title(SubtitlePreference.languages, subtitleLanguage),
                              options: SubtitlePreference.languages.map { ($0.1, $0.0) }, selected: subtitleLanguage) { subtitleLanguage = $0 }
                    optionRow(.size, "حجم الخط", value: title(SubtitlePreference.sizes, subtitleSize),
                              options: SubtitlePreference.sizes.map { (String($0.1), $0.0) }, selected: String(subtitleSize)) {
                        subtitleSize = Double($0) ?? subtitleSize
                    }
                    optionRow(.background, "خلفية الترجمة", value: title(SubtitlePreference.backgrounds, subtitleBackground),
                              options: SubtitlePreference.backgrounds.map { (String($0.1), $0.0) }, selected: String(subtitleBackground)) {
                        subtitleBackground = Double($0) ?? subtitleBackground
                    }
                    SubtitleText(text: "هكذا ستظهر الترجمة", size: subtitleSize, background: subtitleBackground)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 20)
                        .focusable(false)
                }
            }
            .frame(maxWidth: 1100)
        }
        .padding(.horizontal, 90)
        .background(TVTheme.ink.ignoresSafeArea())
        // Back on the row whose popup just closed.
        .onChange(of: router.popup == nil) { _, closed in
            guard closed, let lastRow else { return }
            Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(80))
                row = lastRow
            }
        }
    }

    /// A setting with several options: its value on the right; pressing opens the glass popup.
    private func optionRow(_ id: SettingsRow, _ title: String, value: String, options: [(String, String)], selected: String,
                           choose: @escaping (String) -> Void) -> some View {
        Button {
            lastRow = id
            router.popup = TVPopup(title: title, options: options.map { (id: $0.0, title: $0.1) }, selected: selected, choose: choose)
        } label: {
            HStack {
                Text(title)
                Spacer()
                Text(value).foregroundStyle(.secondary)
                Image(systemName: "chevron.up.chevron.down").font(.caption).foregroundStyle(.secondary)
            }
        }
        .focused($row, equals: id)
    }

    private func title<Value: Equatable>(_ options: [(String, Value)], _ value: Value) -> String {
        options.first { $0.1 == value }?.0 ?? ""
    }
}

/// Sign-in, email confirmation and profile, with the same account as the phones and Android TV.
private struct TVAccountRows: View {
    @EnvironmentObject private var cloud: CloudSync
    @State private var email = ""
    @State private var password = ""
    @State private var profileName = ""
    var body: some View {
        if !cloud.configured {
            Text("إعداد المزامنة غير متوفر في هذه النسخة.").foregroundStyle(TVTheme.muted)
        } else if let session = cloud.session {
            LabeledContent("البريد", value: session.email)
            if !session.verified {
                Button("تحققت من البريد") { Task { await cloud.checkVerification() } }
                Button("إعادة إرسال رابط التأكيد") { Task { await cloud.resendVerification() } }
            } else if cloud.profiles.isEmpty {
                TextField("اسم الملف الشخصي", text: $profileName)
                Button("إنشاء ملف شخصي") { Task { await cloud.createFirstProfile(name: profileName) } }
            } else {
                ForEach(cloud.profiles) { profile in
                    Button { Task { await cloud.choose(profile: profile.id) } } label: {
                        HStack {
                            Text(profile.name)
                            Spacer()
                            if profile.id == cloud.profileID { Image(systemName: "checkmark").foregroundStyle(TVTheme.accent) }
                        }
                    }
                }
                Button { Task { await cloud.pull() } } label: { Label("مزامنة الآن", systemImage: "arrow.triangle.2.circlepath") }
                if !cloud.status.isEmpty { Text(cloud.status).font(.footnote).foregroundStyle(TVTheme.muted) }
            }
            Button("تسجيل الخروج", role: .destructive) { Task { await cloud.signOut() } }
        } else {
            TextField("البريد الإلكتروني", text: $email)
                .textContentType(.emailAddress)
            SecureField("كلمة المرور", text: $password)
            Button(cloud.busy ? "جارٍ تسجيل الدخول…" : "تسجيل الدخول") {
                Task { await cloud.signIn(email: email, password: password); password = "" }
            }
            .disabled(cloud.busy || email.isEmpty || password.isEmpty)
        }
        if let message = cloud.message { Text(message).font(.footnote).foregroundStyle(TVTheme.muted) }
    }
}

/// External providers: the demo and the added ones; add with name, address and token; remove with a long press.
private struct TVProvidersView: View {
    @EnvironmentObject private var store: ProviderStore
    @AppStorage(SourcePreference.key) private var sourceName = SourcePreference.fallback.rawValue
    @State private var name = ""
    @State private var address = "https://"
    @State private var token = ""
    @State private var busy = false
    @State private var error: String?
    var body: some View {
        List {
            Section {
                ForEach([ExternalProvider.demo] + store.providers) { provider in
                    Button { sourceName = ContentSource.external(provider.id).rawValue } label: {
                        HStack {
                            VStack(alignment: .leading) {
                                Text(provider.name)
                                Text(provider.id == "demo" ? "أفلام مرخّصة للتجربة" : (provider.baseURL.host ?? ""))
                                    .font(.caption).foregroundStyle(TVTheme.muted)
                            }
                            Spacer()
                            if sourceName == ContentSource.external(provider.id).rawValue {
                                Image(systemName: "checkmark").foregroundStyle(TVTheme.accent)
                            }
                        }
                    }
                    .contextMenu {
                        if provider.id != "demo" {
                            Button("حذف", role: .destructive) {
                                if sourceName == ContentSource.external(provider.id).rawValue { sourceName = SourcePreference.fallback.rawValue }
                                store.remove(provider)
                            }
                        }
                    }
                }
            } header: {
                Text("المصادر الخارجية")
            } footer: {
                Text("اضغط مصدراً لاختياره، واضغط مطوّلاً لحذفه.")
            }
            Section("إضافة مصدر") {
                TextField("الاسم", text: $name)
                TextField("عنوان الواجهة (HTTPS)", text: $address)
                SecureField("رمز الوصول (اختياري)", text: $token)
                Button(busy ? "جارٍ الفحص…" : "فحص وإضافة") {
                    busy = true; error = nil
                    Task {
                        do {
                            let provider = try await store.add(name: name, address: address, token: token)
                            sourceName = ContentSource.external(provider.id).rawValue
                            name = ""; address = "https://"; token = ""
                        } catch is CancellationError {
                        } catch { self.error = error.localizedDescription }
                        busy = false
                    }
                }
                .disabled(busy || address.count < 12)
                if let error { Text(error).foregroundStyle(.red) }
            }
        }
        .navigationTitle("المصادر الخارجية")
    }
}
