import SwiftUI
import CinemaCore

/// The reference layout keeps the app icon on the left and the settings on the right, even in Arabic.
struct TVSettingsView: View {
    @EnvironmentObject private var providers: ProviderStore
    @EnvironmentObject private var router: TVRouter
    @EnvironmentObject private var cloud: CloudSync
    @AppStorage(SourcePreference.key) private var sourceName = SourcePreference.fallback.rawValue
    @FocusState private var row: SettingsRow?
    @State private var restoreSourceFocus = false

    private enum SettingsRow: Hashable { case source, providers, account, library, subtitles, about }
    private var sources: [ContentSource] {
        ContentSource.builtIn + ([ExternalProvider.demo] + providers.providers).map { .external($0.id) }
    }

    var body: some View {
        GeometryReader { geometry in
            HStack(spacing: 78) {
                brand(size: min(460, geometry.size.width * 0.29))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)

                ScrollView {
                    VStack(spacing: 18) {
                        Button {
                            restoreSourceFocus = true
                            router.popup = TVPopup(
                                title: "مصدر المحتوى",
                                options: sources.map { (id: $0.rawValue, title: $0.title) },
                                selected: sourceName,
                                choose: { sourceName = $0 }
                            )
                        } label: {
                            TVSettingsRowLabel(title: "مصدر المحتوى", value: SourcePreference.source(sourceName).title)
                        }
                        .focused($row, equals: .source)
                        .accessibilityIdentifier("tv.settings.source")

                        NavigationLink { TVProvidersView() } label: {
                            TVSettingsRowLabel(title: "المصادر الخارجية")
                        }
                        .focused($row, equals: .providers)
                        .accessibilityIdentifier("tv.settings.providers")

                        NavigationLink { TVAccountView() } label: {
                            TVSettingsRowLabel(title: "حساب Cinema iQ", value: cloud.activeProfile?.name ?? "")
                        }
                        .focused($row, equals: .account)
                        .accessibilityIdentifier("tv.settings.account")

                        NavigationLink { TVLibrarySettingsView() } label: {
                            TVSettingsRowLabel(title: "المكتبة والمزامنة")
                        }
                        .focused($row, equals: .library)
                        .accessibilityIdentifier("tv.settings.library")

                        NavigationLink { TVSubtitleSettingsView() } label: {
                            TVSettingsRowLabel(title: "الترجمة")
                        }
                        .focused($row, equals: .subtitles)
                        .accessibilityIdentifier("tv.settings.subtitles")

                        NavigationLink { TVAboutView() } label: {
                            TVSettingsRowLabel(title: "حول التطبيق")
                        }
                        .focused($row, equals: .about)
                        .accessibilityIdentifier("tv.settings.about")
                    }
                    .buttonStyle(TVSettingsRowStyle())
                    .padding(.horizontal, 16)
                    .padding(.vertical, 28)
                    .environment(\.layoutDirection, .rightToLeft)
                }
                .scrollIndicators(.hidden)
                .scrollClipDisabled()
                .focusSection()
                .frame(width: min(930, geometry.size.width * 0.54))
            }
            .environment(\.layoutDirection, .leftToRight)
            .padding(.horizontal, 76)
            .padding(.top, 24)
            .padding(.bottom, 40)
            // Include the logo's empty focus area, so Down from the leftmost navigation pill reaches the rows.
            .focusSection()
        }
        .background { TVSettingsBackground() }
        // A structured task is cancelled if the tab or destination disappears while the popup closes.
        .task(id: router.popup?.id) {
            guard router.popup == nil, restoreSourceFocus, router.tab == .settings else { return }
            do { try await Task.sleep(for: .milliseconds(120)) } catch { return }
            guard router.popup == nil, router.tab == .settings else { return }
            row = .source
            restoreSourceFocus = false
        }
    }

    private func brand(size: CGFloat) -> some View {
        VStack(spacing: 24) {
            Image("Logo")
                .resizable()
                .scaledToFit()
                .frame(width: size, height: size)
                .clipShape(RoundedRectangle(cornerRadius: size * 0.23, style: .continuous))
                .shadow(color: .black.opacity(0.22), radius: 32, y: 18)
                .accessibilityLabel("شعار Cinema iQ")
            Text("Cinema iQ")
                .font(.system(size: 38, weight: .semibold))
                .foregroundStyle(.white.opacity(0.9))
        }
        .padding(.bottom, 36)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("tv.settings.logo")
    }
}

private struct TVSettingsBackground: View {
    var body: some View {
        ZStack {
            LinearGradient(colors: [Color(red: 0.15, green: 0.24, blue: 0.53),
                                    Color(red: 0.045, green: 0.09, blue: 0.24)],
                           startPoint: .topLeading, endPoint: .bottomTrailing)
            RadialGradient(colors: [Color(red: 0.13, green: 0.36, blue: 0.8).opacity(0.8), .clear],
                           center: .topTrailing, startRadius: 30, endRadius: 1100)
            RadialGradient(colors: [Color(red: 0.45, green: 0.43, blue: 0.85).opacity(0.28), .clear],
                           center: .leading, startRadius: 40, endRadius: 700)
        }
        .ignoresSafeArea()
    }
}

private struct TVSettingsRowLabel: View {
    let title: String
    var value = ""
    var body: some View {
        HStack(spacing: 20) {
            Text(title).font(.system(size: 31, weight: .medium)).lineLimit(1)
            Spacer(minLength: 12)
            if !value.isEmpty {
                Text(value).font(.system(size: 25)).opacity(0.72).lineLimit(1)
            }
            Image(systemName: "chevron.forward")
                .font(.system(size: 22, weight: .semibold))
                .opacity(0.8)
        }
        .padding(.horizontal, 30)
        .frame(minHeight: 76)
        .contentShape(Capsule())
    }
}

/// A single bright capsule is the remote focus; the other rows remain translucent blue.
private struct TVSettingsRowStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        FocusedRow(configuration: configuration)
    }
    private struct FocusedRow: View {
        @Environment(\.isFocused) private var focused
        let configuration: ButtonStyleConfiguration
        var body: some View {
            configuration.label
                .foregroundStyle(focused ? Color(red: 0.08, green: 0.12, blue: 0.25) : .white)
                .background(focused ? .white : .white.opacity(0.14), in: Capsule())
                .overlay { Capsule().strokeBorder(.white.opacity(focused ? 0 : 0.08), lineWidth: 1) }
                .shadow(color: .black.opacity(focused ? 0.2 : 0), radius: 18, y: 8)
                .scaleEffect(configuration.isPressed ? 0.98 : (focused ? 1.025 : 1))
                .animation(.easeOut(duration: 0.16), value: focused)
                .animation(.easeOut(duration: 0.1), value: configuration.isPressed)
        }
    }
}

/// Subtitle choices remain in the app's existing full-screen popup, with focus returned to the originating row.
private struct TVSubtitleSettingsView: View {
    @EnvironmentObject private var router: TVRouter
    @AppStorage(SubtitlePreference.sizeKey) private var subtitleSize = 20.0
    @AppStorage(SubtitlePreference.languageKey) private var subtitleLanguage = "ar"
    @AppStorage(SubtitlePreference.backgroundKey) private var subtitleBackground = 0.5
    @FocusState private var row: SettingsRow?
    @State private var lastRow: SettingsRow?
    private enum SettingsRow: Hashable { case language, size, background }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Text("خيارات الترجمة").font(.title3.weight(.semibold)).foregroundStyle(TVTheme.muted)
                VStack(spacing: 18) {
                    optionRow(.language, "الترجمة المفضلة", value: title(SubtitlePreference.languages, subtitleLanguage),
                              options: SubtitlePreference.languages.map { ($0.1, $0.0) }, selected: subtitleLanguage) { subtitleLanguage = $0 }
                        .accessibilityIdentifier("tv.settings.subtitle.language")
                    optionRow(.size, "حجم الخط", value: title(SubtitlePreference.sizes, subtitleSize),
                              options: SubtitlePreference.sizes.map { (String($0.1), $0.0) }, selected: String(subtitleSize)) {
                        subtitleSize = Double($0) ?? subtitleSize
                    }
                    .accessibilityIdentifier("tv.settings.subtitle.size")
                    optionRow(.background, "خلفية الترجمة", value: title(SubtitlePreference.backgrounds, subtitleBackground),
                              options: SubtitlePreference.backgrounds.map { (String($0.1), $0.0) }, selected: String(subtitleBackground)) {
                        subtitleBackground = Double($0) ?? subtitleBackground
                    }
                    .accessibilityIdentifier("tv.settings.subtitle.background")
                }
                .buttonStyle(TVSettingsRowStyle())
                Text("معاينة").font(.title3.weight(.semibold)).foregroundStyle(TVTheme.muted)
                    .padding(.top, 18)
                SubtitleText(text: "هكذا ستظهر الترجمة", size: subtitleSize, background: subtitleBackground)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 28)
                    .focusable(false)
            }
            .padding(.horizontal, 92)
            .padding(.vertical, 40)
        }
        .scrollClipDisabled()
        .focusSection()
        .navigationTitle("الترجمة")
        .background { TVSettingsBackground() }
        .task(id: router.popup?.id) {
            guard router.popup == nil, let lastRow else { return }
            // Wait until the popup's removal transition completes before moving focus back into the page.
            do { try await Task.sleep(for: .milliseconds(300)) } catch { return }
            guard router.popup == nil else { return }
            row = lastRow
        }
    }

    private func optionRow(_ id: SettingsRow, _ title: String, value: String, options: [(String, String)], selected: String,
                           choose: @escaping (String) -> Void) -> some View {
        Button {
            lastRow = id
            row = nil
            router.popup = TVPopup(title: title, options: options.map { (id: $0.0, title: $0.1) }, selected: selected, choose: choose)
        } label: {
            HStack {
                Text(title)
                Spacer()
                Text(value).opacity(0.72)
                Image(systemName: "chevron.up.chevron.down").font(.caption).opacity(0.72)
            }
            .font(.system(size: 31, weight: .medium))
            .padding(.horizontal, 30)
            .frame(minHeight: 76)
        }
        .focused($row, equals: id)
    }

    private func title<Value: Equatable>(_ options: [(String, Value)], _ value: Value) -> String {
        options.first { $0.1 == value }?.0 ?? ""
    }
}

private struct TVAccountView: View {
    var body: some View {
        List { TVAccountRows() }
            .navigationTitle("حساب Cinema iQ")
            .background { TVSettingsBackground() }
    }
}

/// Sign-in, registration, email confirmation and profiles use the same existing backend as the phone apps.
private struct TVAccountRows: View {
    @EnvironmentObject private var cloud: CloudSync
    @State private var email = ""
    @State private var password = ""
    @State private var profileName = ""
    @State private var creatingAccount = false
    @State private var working = false
    private var cleanEmail: String { email.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var busy: Bool { working || cloud.busy }

    var body: some View {
        if !cloud.configured {
            Text("إعداد المزامنة غير متوفر في هذه النسخة.").foregroundStyle(TVTheme.muted)
        } else if let session = cloud.session {
            Section("الحساب") {
                LabeledContent("البريد", value: session.email)
                if !session.verified {
                    Text("افتح رابط التأكيد في بريدك لإتاحة ملفاتك ومزامنة مكتبتك.").foregroundStyle(TVTheme.muted)
                    Button("تحققت من البريد") { perform { await cloud.checkVerification() } }
                        .disabled(busy)
                    Button("إعادة إرسال رابط التأكيد") { perform { await cloud.resendVerification() } }
                        .disabled(busy)
                }
            }
            if session.verified {
                Section("الملفات الشخصية") {
                    if cloud.profiles.isEmpty {
                        TextField("اسم الملف الشخصي", text: $profileName)
                            .disabled(busy)
                        Button("إنشاء ملف شخصي") {
                            perform { await cloud.createFirstProfile(name: profileName.trimmingCharacters(in: .whitespacesAndNewlines)) }
                        }
                        .disabled(busy)
                    } else {
                        ForEach(cloud.profiles) { profile in
                            Button { perform { await cloud.choose(profile: profile.id) } } label: {
                                HStack {
                                    Text(profile.name)
                                    Spacer()
                                    if profile.id == cloud.profileID { Image(systemName: "checkmark").foregroundStyle(TVTheme.accent) }
                                }
                            }
                            .disabled(busy)
                        }
                    }
                }
                if cloud.profileID != nil {
                    Section {
                        Button { perform { await cloud.pull() } } label: {
                            Label("مزامنة الآن", systemImage: "arrow.triangle.2.circlepath")
                        }
                        .disabled(busy)
                        if !cloud.status.isEmpty { Text(cloud.status).font(.footnote).foregroundStyle(TVTheme.muted) }
                    }
                }
            }
            Section {
                Button("تسجيل الخروج", role: .destructive) { perform { await cloud.signOut() } }
                    .disabled(busy)
            }
        } else {
            Section(creatingAccount ? "إنشاء حساب" : "تسجيل الدخول") {
                TextField("البريد الإلكتروني", text: $email)
                    .textContentType(.emailAddress)
                    .autocorrectionDisabled()
                    .environment(\.layoutDirection, .leftToRight)
                    .accessibilityIdentifier("tv.settings.account.email")
                    .disabled(busy)
                SecureField("كلمة المرور", text: $password)
                    .textContentType(creatingAccount ? .newPassword : .password)
                    .accessibilityIdentifier("tv.settings.account.password")
                    .disabled(busy)
                Button(busy ? "جارٍ الإرسال…" : (creatingAccount ? "إنشاء حساب" : "تسجيل الدخول")) {
                    let submittedEmail = cleanEmail, submittedPassword = password
                    let signUp = creatingAccount
                    perform {
                        if signUp { await cloud.signUp(email: submittedEmail, password: submittedPassword) }
                        else { await cloud.signIn(email: submittedEmail, password: submittedPassword) }
                        password = ""
                    }
                }
                .accessibilityIdentifier("tv.settings.account.submit")
                .disabled(busy || cleanEmail.isEmpty || password.isEmpty || (creatingAccount && password.count < 6))
                if creatingAccount {
                    Text("استخدم 6 أحرف على الأقل. سنرسل رابطاً لتأكيد البريد الإلكتروني.")
                        .font(.footnote).foregroundStyle(TVTheme.muted)
                }
            }
            Section {
                Button(creatingAccount ? "لدي حساب، تسجيل الدخول" : "إنشاء حساب جديد") {
                    creatingAccount.toggle()
                    password = ""
                    cloud.message = nil
                }
                .accessibilityIdentifier("tv.settings.account.toggleMode")
                .disabled(busy)
                Button("نسيت كلمة المرور") {
                    let submittedEmail = cleanEmail
                    perform { await cloud.resetPassword(email: submittedEmail) }
                }
                .accessibilityIdentifier("tv.settings.account.resetPassword")
                .disabled(busy || cleanEmail.isEmpty)
                Text("لاستعادة كلمة المرور، أدخل بريدك أولاً ثم اختر «نسيت كلمة المرور».")
                    .font(.footnote).foregroundStyle(TVTheme.muted)
            }
        }
        if let message = cloud.message {
            Section { Text(message).font(.footnote).foregroundStyle(TVTheme.muted) }
        }
    }

    /// Reserve the controls synchronously, before the asynchronous backend marks itself busy.
    private func perform(_ operation: @escaping @MainActor () async -> Void) {
        guard !busy else { return }
        working = true
        Task { @MainActor in
            await operation()
            working = false
        }
    }
}

private struct TVLibrarySettingsView: View {
    @EnvironmentObject private var cloud: CloudSync
    @EnvironmentObject private var library: LibraryStore
    @State private var confirmImport = false
    @State private var working = false
    private var canSync: Bool { cloud.session?.verified == true && cloud.profileID != nil }

    var body: some View {
        List {
            Section("مكتبتك الحالية") {
                LabeledContent("المفضلة", value: String(library.favorites.count))
                LabeledContent("تابع المشاهدة", value: String(library.recent.count))
                LabeledContent("سجل المشاهدة", value: String(library.watched.count))
            }
            Section("المزامنة") {
                if canSync {
                    LabeledContent("الملف الشخصي", value: cloud.activeProfile?.name ?? "")
                    Button(working ? "جارٍ المزامنة…" : "مزامنة الآن") {
                        guard !working else { return }
                        working = true
                        Task { await cloud.pull(); working = false }
                    }
                    .disabled(working || cloud.busy)
                    .accessibilityIdentifier("tv.settings.library.sync")
                    Button("نسخ مكتبة هذا الجهاز إلى الملف الشخصي") { confirmImport = true }
                        .disabled(working || cloud.busy)
                        .accessibilityIdentifier("tv.settings.library.import")
                    Text("تبقى مكتبة الجهاز منفصلة عن الحساب حتى تختار نسخها. محتوى المصادر الخارجية يبقى على الجهاز.")
                        .font(.footnote).foregroundStyle(TVTheme.muted)
                } else {
                    Text("سجّل الدخول وأكّد بريدك واختر ملفاً شخصياً لمزامنة مكتبتك بين أجهزتك.")
                        .foregroundStyle(TVTheme.muted)
                    NavigationLink("فتح الحساب") { TVAccountView() }
                }
                if !cloud.status.isEmpty { Text(cloud.status).font(.footnote).foregroundStyle(TVTheme.muted) }
                if let message = cloud.message { Text(message).font(.footnote).foregroundStyle(TVTheme.muted) }
            }
        }
        .navigationTitle("المكتبة والمزامنة")
        .background { TVSettingsBackground() }
        .confirmationDialog("نسخ مكتبة هذا الجهاز؟", isPresented: $confirmImport, titleVisibility: .visible) {
            Button("نسخ إلى الملف الشخصي") {
                guard canSync, !working, !cloud.busy else { return }
                working = true
                Task { await cloud.importDeviceLibrary(); working = false }
            }
            Button("إلغاء", role: .cancel) {}
        } message: {
            Text("ستُضاف المفضلة وسجل المشاهدة ومواضع التشغيل المحلية إلى الملف «\(cloud.activeProfile?.name ?? "")». عند وجود العنوان نفسه تُستخدم بيانات هذا الجهاز. لن تُحذف المكتبة المحلية.")
        }
    }
}

private struct TVAboutView: View {
    private var version: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "" }
    private var build: String { Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "" }
    var body: some View {
        List {
            Section("Cinema iQ") {
                LabeledContent("الإصدار", value: version)
                LabeledContent("رقم النسخة", value: build)
                LabeledContent("التطوير", value: "Emad Hasnawi")
                Text("نسخة Apple TV · مكتبتك ومتابعة المشاهدة والمصادر في مكان واحد.")
                    .foregroundStyle(TVTheme.muted)
            }
        }
        .navigationTitle("حول التطبيق")
        .background { TVSettingsBackground() }
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
        .background { TVSettingsBackground() }
    }
}
