import SwiftUI
import CinemaCore

/// Native tvOS controls in a category/detail layout; all existing account and provider flows stay available.
struct TVSettingsView: View {
    @EnvironmentObject private var providers: ProviderStore
    @EnvironmentObject private var router: TVRouter
    @Environment(\.resetFocus) private var resetFocus
    @Namespace private var contentScope
    @AppStorage(SourcePreference.key) private var sourceName = SourcePreference.fallback.rawValue
    @AppStorage(SubtitlePreference.sizeKey) private var subtitleSize = 20.0
    @AppStorage(SubtitlePreference.languageKey) private var subtitleLanguage = "ar"
    @AppStorage(SubtitlePreference.backgroundKey) private var subtitleBackground = 0.5
    @AppStorage(TVPlaybackPreference.qualityKey) private var preferredQuality = VideoQuality.automatic.rawValue
    @State private var selected = SettingsSection.source
    @State private var lastRow: SettingsRow?
    @State private var focusTask: Task<Void, Never>?
    @FocusState private var category: SettingsSection?
    @FocusState private var row: SettingsRow?

    private enum SettingsSection: String, CaseIterable, Identifiable {
        case source, account, playback, subtitles, about
        var id: String { rawValue }
        var title: String {
            switch self {
            case .source: return "المحتوى والمصادر"
            case .account: return "الحساب والملفات الشخصية"
            case .playback: return "التشغيل والجودة"
            case .subtitles: return "الترجمة والمظهر"
            case .about: return "حول التطبيق"
            }
        }
        var menuTitle: String {
            switch self {
            case .source: return "المصادر"
            case .account: return "الحساب"
            case .playback: return "التشغيل"
            case .subtitles: return "الترجمة"
            case .about: return "حول التطبيق"
            }
        }
        var icon: String {
            switch self {
            case .source: return "play.rectangle.on.rectangle"
            case .account: return "person.crop.circle"
            case .playback: return "slider.horizontal.3"
            case .subtitles: return "captions.bubble"
            case .about: return "info.circle"
            }
        }
        var summary: String {
            switch self {
            case .source: return "اختر المصدر الذي تدعمه شبكتك وأدر مصادر المحتوى الإضافية."
            case .account: return "حساب Cinema iQ والملفات الشخصية ومزامنة مكتبتك وتقدم المشاهدة."
            case .playback: return "اختر الجودة المفضلة عند بدء تشغيل الأفلام والحلقات."
            case .subtitles: return "اللغة المفضلة وحجم الخط والخلفية، مع معاينة مباشرة."
            case .about: return "Cinema iQ على Apple TV."
            }
        }
    }
    private enum SettingsRow: String { case source, quality, language, size, background }
    private var version: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "" }
    private var sources: [ContentSource] { ContentSource.builtIn + ([ExternalProvider.demo] + providers.providers).map { .external($0.id) } }
    private var quality: VideoQuality { VideoQuality(rawValue: preferredQuality) ?? .automatic }

    var body: some View {
        VStack(alignment: .leading, spacing: 28) {
            HStack(spacing: 22) {
                Image("Logo").resizable().frame(width: 80, height: 80)
                    .clipShape(RoundedRectangle(cornerRadius: 20))
                Text("الإعدادات").font(.system(size: 52, weight: .bold))
                    .accessibilityIdentifier("tv.settings.heading")
                Spacer()
                Text("Cinema iQ").font(.title3.weight(.semibold)).foregroundStyle(TVTheme.muted)
                    .environment(\.layoutDirection, .leftToRight)
            }
            HStack(alignment: .top, spacing: 64) {
                categories.frame(width: 340)
                VStack(alignment: .leading, spacing: 16) {
                    Text(selected.title).font(.title2.weight(.semibold))
                    Text(selected.summary).font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    List { settingsRows }
                        .id(selected)
                        .focusScope(contentScope)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            }
        }
        .padding(.horizontal, 24).padding(.top, 30).padding(.bottom, 24)
        .background(TVTheme.ink.ignoresSafeArea())
        .defaultFocus($category, selected)
        .onChange(of: category) { _, value in
            if let value { selected = value }
        }
        .onChange(of: router.popup == nil) { _, closed in
            guard closed, let lastRow else { return }
            focusTask?.cancel()
            focusTask = Task { @MainActor in
                do { try await Task.sleep(for: .milliseconds(80)) } catch { return }
                guard !Task.isCancelled else { return }
                row = lastRow
            }
        }
        .onDisappear { focusTask?.cancel(); focusTask = nil }
    }

    private var categories: some View {
        VStack(alignment: .leading, spacing: 16) {
            ForEach(SettingsSection.allCases) { section in
                Button { enter(section) } label: {
                    Label(section.menuTitle, systemImage: section.icon)
                        .font(.callout.weight(.medium)).frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.vertical, 8)
                }
                .buttonBorderShape(.capsule)
                .overlay { Capsule().strokeBorder(.white.opacity(selected == section ? 0.28 : 0), lineWidth: 1) }
                .focused($category, equals: section)
                .accessibilityIdentifier("tv.settings.category.\(section.rawValue)")
                .onMoveCommand { if $0 == .left { enter(section) } }
            }
            Spacer(minLength: 24)
            Text("الإصدار \(version)").font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxHeight: .infinity, alignment: .topLeading)
        .focusSection()
    }

    @ViewBuilder private var settingsRows: some View {
        switch selected {
        case .source:
            Section("مصدر المحتوى") {
                optionRow(.source, "المصدر", value: SourcePreference.source(sourceName).title,
                          options: sources.map { ($0.rawValue, $0.title) }, selected: sourceName) { sourceName = $0 }
                NavigationLink("المصادر الخارجية") { TVProvidersView() }
            }
        case .account:
            Section("حساب Cinema iQ والمزامنة") { TVAccountRows() }
        case .playback:
            Section {
                optionRow(.quality, "الجودة المفضلة", value: quality.title,
                          options: VideoQuality.allCases.map { ($0.rawValue, $0.title) }, selected: quality.rawValue) { preferredQuality = $0 }
            } header: { Text("جودة الفيديو") } footer: {
                Text("يفضل التطبيق الجودة المختارة ويستخدم المتاح من المصدر عند غيابها. «تلقائي» يكيّف الجودة مع الاتصال عندما يدعم المصدر البث المتكيف؛ بعض المصادر توفر روابط بجودات ثابتة فقط.")
            }
        case .subtitles:
            Section("خيارات الترجمة") {
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
            }
            Section("المعاينة") {
                VStack(spacing: 16) {
                    SubtitleText(text: "هكذا ستظهر الترجمة على الشاشة", size: subtitleSize, background: subtitleBackground)
                    if subtitleLanguage == "off" {
                        Text("الترجمة متوقفة").font(.caption).foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 20).frame(maxWidth: .infinity)
                .focusable(false)
                .accessibilityIdentifier("tv.settings.subtitle.preview")
            }
        case .about:
            Section("Cinema iQ") {
                LabeledContent("الإصدار", value: version)
                LabeledContent("المنصة", value: "Apple TV")
                LabeledContent("التطوير", value: "Emad Hasnawi")
                Text("يدعم التطبيق مصادر متعددة للمحتوى، مع مكتبة شخصية واستكمال المشاهدة والمزامنة عند تسجيل الدخول.")
                    .font(.callout).foregroundStyle(.secondary)
            }
        }
    }

    private func enter(_ section: SettingsSection) {
        selected = section
        focusTask?.cancel()
        focusTask = Task { @MainActor in
            await Task.yield()
            guard !Task.isCancelled, selected == section else { return }
            switch section {
            case .source: row = .source
            case .playback: row = .quality
            case .subtitles: row = .language
            case .account: break
            case .about: return
            }
            resetFocus(in: contentScope)
        }
    }

    private func optionRow(_ id: SettingsRow, _ title: String, value: String, options: [(String, String)], selected: String,
                           choose: @escaping (String) -> Void) -> some View {
        Button {
            lastRow = id
            router.popup = TVPopup(title: title, options: options.map { (id: $0.0, title: $0.1) }, selected: selected, choose: choose)
        } label: {
            HStack(spacing: 18) {
                Text(title)
                Spacer()
                Text(value).foregroundStyle(.secondary)
                Image(systemName: "chevron.up.chevron.down").font(.caption).foregroundStyle(.secondary)
            }
        }
        .focused($row, equals: id)
        .accessibilityLabel(title)
        .accessibilityValue(value)
        .accessibilityIdentifier("tv.settings.option.\(id.rawValue)")
    }

    private func title<Value: Equatable>(_ options: [(String, Value)], _ value: Value) -> String {
        options.first { $0.1 == value }?.0 ?? "مخصص"
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
