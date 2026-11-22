import SwiftUI
import CinemaCore

/// Paged poster grid of one source, movies or series, optionally filtered by a search query.
struct CatalogGrid: View {
    @Environment(\.contentLayout) private var layout
    let source: ContentSource
    let query: String
    @State private var series = false
    @State private var items: [Media] = []
    @State private var page = 0
    @State private var more = true
    @State private var loading = false
    @State private var message: String?
    /// The tab (movies or series) whose items are loaded.
    @State private var loadedSeries: Bool?
    @State private var generation = UUID()
    @State private var paginationTask: Task<Void, Never>?

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Picker("نوع العمل", selection: $series) {
                    Text("أفلام").tag(false)
                    Text("مسلسلات").tag(true)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, layout.sideInset)
                LazyVGrid(columns: layout.gridColumns, spacing: 18) {
                    ForEach(items) { media in
                        PosterCard(media: media, routeKey: "grid-\(media.id)", width: nil)
                            .onAppear { if media.id == items.last?.id { requestPage() } }
                    }
                }
                .padding(.horizontal, layout.sideInset)
                if loading {
                    ProgressView().padding()
                } else if let message {
                    ErrorState(message: message, retry: requestPage)
                } else if items.isEmpty && !more {
                    ContentUnavailableView("لا توجد نتائج", systemImage: "magnifyingglass",
                                           description: Text("جرّب كلمة أخرى أو مصدراً آخر."))
                }
            }
            .padding(.vertical, 8)
        }
        .scrollDismissesKeyboard(.immediately)
        .accessibilityIdentifier("ios.catalog")
        .task(id: series) {
            // Returning from a title page re-runs this task: keep the loaded grid and its scroll position.
            if loadedSeries == series && !items.isEmpty { return }
            paginationTask?.cancel(); paginationTask = nil
            let request = UUID(); generation = request
            items = []; page = 0; more = true; message = nil; loading = false
            // Typing restarts this task, so a search runs once the viewer pauses.
            if !query.isEmpty {
                try? await Task.sleep(for: .milliseconds(350))
                if Task.isCancelled { return }
            }
            await load(request)
        }
        .onDisappear {
            generation = UUID(); paginationTask?.cancel(); paginationTask = nil; loading = false
        }
    }

    private func requestPage() {
        guard !loading, more, paginationTask == nil else { return }
        let request = generation
        paginationTask = Task {
            await load(request)
            if generation == request { paginationTask = nil }
        }
    }

    private func load(_ request: UUID) async {
        guard generation == request, !Task.isCancelled, !loading, more else { return }
        let kind = series
        let requestedPage = page + 1
        loading = true; message = nil
        defer { if generation == request { loading = false } }
        do {
            let result = try await SourceClient().catalog(source, series: kind, query: query, page: requestedPage)
            // A late answer for the other tab must not be mixed into this one.
            guard generation == request, kind == series, !Task.isCancelled else { return }
            let existing = Set(items.map(\.id))
            let additions = result.filter { !existing.contains($0.id) }
            items.append(contentsOf: additions)
            loadedSeries = kind
            page = requestedPage
            more = !additions.isEmpty && source != .dawwirli
        } catch is CancellationError {
        } catch {
            if generation == request && kind == series && !Task.isCancelled { message = error.localizedDescription }
        }
    }
}

struct BrowseView: View {
    @AppStorage(SourcePreference.key) private var sourceName = SourcePreference.fallback.rawValue
    var body: some View {
        CatalogGrid(source: SourcePreference.source(sourceName), query: "")
            .id(sourceName)
            .background(Theme.canvas)
            .navigationTitle("تصفّح")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { SourceMenu() } }
    }
}

struct SearchView: View {
    @Environment(\.contentLayout) private var layout
    @EnvironmentObject private var library: LibraryStore
    @AppStorage(SourcePreference.key) private var sourceName = SourcePreference.fallback.rawValue
    @State private var query = ""
    @FocusState private var searchFocused: Bool
    private var trimmed: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }
    var body: some View {
        Group {
            if trimmed.count >= 2 {
                CatalogGrid(source: SourcePreference.source(sourceName), query: trimmed)
                    .id(sourceName + "/" + trimmed)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        if library.searches.isEmpty {
                            ContentUnavailableView("ابحث في \(SourcePreference.source(sourceName).title)",
                                                   systemImage: "magnifyingglass",
                                                   description: Text("اكتب حرفين على الأقل من اسم الفيلم أو المسلسل."))
                                .padding(.top, 80)
                        } else {
                            HStack {
                                Text("عمليات البحث الأخيرة").font(.headline)
                                Spacer()
                                Button("مسح") { library.clearSearches() }.font(.subheadline)
                            }
                            ForEach(library.searches, id: \.self) { text in
                                Button { query = text; searchFocused = false } label: {
                                    Label(text, systemImage: "clock.arrow.circlepath")
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                        .padding(.vertical, 8)
                                }
                                .buttonStyle(.plain)
                                Divider()
                            }
                        }
                    }
                    .padding(.horizontal, layout.sideInset)
                }
            }
        }
        .background(Theme.canvas)
        .navigationTitle("البحث")
        .searchable(text: $query, prompt: "اسم الفيلم أو المسلسل")
        .searchFocused($searchFocused)
        .onSubmit(of: .search) { library.remember(search: trimmed); searchFocused = false }
        .scrollDismissesKeyboard(.interactively)
        .onDisappear { searchFocused = false }
        .toolbar { ToolbarItem(placement: .topBarTrailing) { SourceMenu() } }
    }
}

struct LibraryView: View {
    @Environment(\.contentLayout) private var layout
    enum Part: String, CaseIterable, Identifiable {
        case list = "قائمتي", recent = "تابع المشاهدة", watched = "شوهد"
        var id: String { rawValue }
    }
    @EnvironmentObject private var library: LibraryStore
    @State private var part: Part = .list
    private var items: [Media] {
        switch part {
        case .list: return library.favorites
        case .recent: return library.recent
        case .watched: return library.watched
        }
    }
    private var emptyTitle: String { part == .list ? "قائمتك فارغة" : "لا يوجد شيء هنا بعد" }
    private var emptyText: String { part == .list ? "أضف أعمالاً بزر + من صفحة العمل." : "ابدأ المشاهدة وستظهر الأعمال هنا." }
    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Picker("القسم", selection: $part) {
                    ForEach(Part.allCases) { part in Text(part.rawValue).tag(part) }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, layout.sideInset)
                if items.isEmpty {
                    ContentUnavailableView(emptyTitle, systemImage: part == .list ? "bookmark" : "play.rectangle",
                                           description: Text(emptyText))
                        .padding(.top, 60)
                } else {
                    LazyVGrid(columns: layout.gridColumns, spacing: 18) {
                        ForEach(items) { media in
                            PosterCard(media: media, routeKey: "library-\(part.id)-\(media.id)", width: nil)
                        }
                    }
                    .padding(.horizontal, layout.sideInset)
                }
            }
            .padding(.vertical, 8)
        }
        .background(Theme.canvas)
        .navigationTitle("مكتبتي")
    }
}

/// Subtitle appearance and language, shared by the settings and the player.
enum SubtitlePreference {
    static let sizeKey = "ios.subtitle.size.v1"
    static let languageKey = "ios.subtitle.language.v1"
    static let backgroundKey = "ios.subtitle.background.v1"
    static let sizes: [(String, Double)] = [("صغير", 16), ("متوسط", 20), ("كبير", 24), ("كبير جداً", 28)]
    static let languages: [(String, String)] = [("العربية", "ar"), ("الإنجليزية", "en"), ("بدون ترجمة", "off")]
    static let backgrounds: [(String, Double)] = [("بدون", 0), ("خفيفة", 0.25), ("متوسطة", 0.5), ("داكنة", 0.75)]
}

private enum AccountInput: Hashable { case email, password, profile }

struct SettingsView: View {
    @Environment(\.contentLayout) private var layout
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var providers: ProviderStore
    @AppStorage(SourcePreference.key) private var sourceName = SourcePreference.fallback.rawValue
    @AppStorage(SubtitlePreference.sizeKey) private var subtitleSize = 20.0
    @AppStorage(SubtitlePreference.languageKey) private var subtitleLanguage = "ar"
    @AppStorage(SubtitlePreference.backgroundKey) private var subtitleBackground = 0.5
    @AppStorage(PhonePlaybackPreference.qualityKey) private var preferredQuality = VideoQuality.automatic.rawValue
    @FocusState private var accountInput: AccountInput?
    private var version: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "" }
    var body: some View {
        Form {
            Section {
                VStack(spacing: 10) {
                    Image("Logo")
                        .resizable()
                        .frame(width: 84, height: 84)
                        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                    HStack(spacing: 0) {
                        Text("Cinema").foregroundStyle(Theme.accent)
                        Text(" iQ")
                    }
                    .font(.title.weight(.heavy))
                    .environment(\.layoutDirection, .leftToRight)
                    Text("سينما آي كيو · الإصدار \(version)").font(.subheadline).foregroundStyle(Theme.muted)
                }
                .frame(maxWidth: .infinity)
                .listRowBackground(Color.clear)
            }
            Section {
                AccountRows(focusedInput: $accountInput)
            } header: {
                Text("حساب Cinema iQ والمزامنة")
            } footer: {
                Text("نفس حساب التلفاز والجوال. تتزامن «قائمتي» و«تابع المشاهدة» وموضع المشاهدة مع الملف الشخصي المختار.")
            }
            Section {
                Picker("المصدر", selection: $sourceName) {
                    ForEach(ContentSource.builtIn + ([ExternalProvider.demo] + providers.providers).map { .external($0.id) }) { source in
                        Text(source.title).tag(source.rawValue)
                    }
                }
                .pickerStyle(.menu)
            } header: {
                Text("مصدر المحتوى")
            } footer: {
                Text("اختر المصدر الذي تدعمه شبكة الإنترنت لديك. الأعمال المحفوظة تبقى مرتبطة بمصدرها الأصلي.")
            }
            ExternalSourcesSection()
            Section {
                Picker("الجودة المفضلة", selection: $preferredQuality) {
                    ForEach(VideoQuality.allCases, id: \.rawValue) { quality in
                        Text(quality.title).tag(quality.rawValue)
                    }
                }
                .pickerStyle(.menu)
                .accessibilityIdentifier("ios.settings.quality")
                .accessibilityValue((VideoQuality(rawValue: preferredQuality) ?? .automatic).title)
            } header: {
                Text("التشغيل")
            } footer: {
                Text("يختار «تلقائي» البث المتكيف عند توفره. الجودة المتاحة تعتمد على المصدر، ويمكن تغييرها أثناء المشاهدة.")
            }
            Section {
                Picker("الترجمة المفضلة", selection: $subtitleLanguage) {
                    ForEach(SubtitlePreference.languages.indices, id: \.self) { index in Text(SubtitlePreference.languages[index].0).tag(SubtitlePreference.languages[index].1) }
                }
                .pickerStyle(.menu)
                Picker("حجم الخط", selection: $subtitleSize) {
                    ForEach(SubtitlePreference.sizes.indices, id: \.self) { index in Text(SubtitlePreference.sizes[index].0).tag(SubtitlePreference.sizes[index].1) }
                }
                .pickerStyle(.menu)
                Picker("خلفية الترجمة", selection: $subtitleBackground) {
                    ForEach(SubtitlePreference.backgrounds.indices, id: \.self) { index in Text(SubtitlePreference.backgrounds[index].0).tag(SubtitlePreference.backgrounds[index].1) }
                }
                .pickerStyle(.menu)
                SubtitlePreview(size: subtitleSize, background: subtitleBackground)
                    .listRowBackground(Color.black)
            } header: {
                Text("الترجمة")
            } footer: {
                Text("تُختار الترجمة المفضلة تلقائياً عند توفرها، ويمكن تغييرها من زر الخيارات أثناء المشاهدة.")
            }
            Section("البحث") {
                Button("مسح سجل البحث", role: .destructive) { library.clearSearches() }
                    .disabled(library.searches.isEmpty)
            }
            Section("حول التطبيق") {
                LabeledContent("الحفظ", value: "على هذا الجهاز فقط")
                Text("تطوير Emad Hasnawi")
            }
        }
        .scrollContentBackground(.hidden)
        .scrollDismissesKeyboard(.interactively)
        .frame(maxWidth: layout.formWidth)
        .frame(maxWidth: .infinity)
        .background(Theme.canvas)
        .accessibilityIdentifier("ios.settings")
        .navigationTitle("الإعدادات")
        .navigationBarTitleDisplayMode(.inline)
        // One form owns the keyboard toolbar and lifecycle; a Group of rows would
        // propagate those modifiers into individual virtualized Form cells.
        .toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                if accountInput != nil {
                    Spacer()
                    Button("تم") { accountInput = nil }
                        .accessibilityIdentifier("ios.account.keyboard.done")
                }
            }
        }
        .onDisappear { accountInput = nil }
    }
}

/// How subtitles will look in the player.
struct SubtitleText: View {
    let text: String
    let size: Double
    let background: Double
    var body: some View {
        Text(text)
            .font(.system(size: size, weight: .semibold))
            .foregroundStyle(.white)
            .multilineTextAlignment(.center)
            .shadow(color: .black.opacity(background < 0.3 ? 1 : 0.6), radius: 2)
            .padding(.horizontal, 12).padding(.vertical, 6)
            .background(.black.opacity(background), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

private struct SubtitlePreview: View {
    let size: Double
    let background: Double
    var body: some View {
        SubtitleText(text: "هكذا ستظهر الترجمة", size: size, background: background)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 18)
            .accessibilityHidden(true)
    }
}

/// Sign-in, email confirmation, profile choice and sync actions, as rows of the settings form.
private struct AccountRows: View {
    @EnvironmentObject private var cloud: CloudSync
    @State private var email = ""
    @State private var password = ""
    @State private var profileName = ""
    @State private var confirmImport = false
    @FocusState.Binding var focusedInput: AccountInput?
    private var profileBinding: Binding<String> {
        Binding(get: { cloud.profileID ?? "" }, set: { id in
            guard !id.isEmpty else { return }
            Task { await cloud.choose(profile: id) }
        })
    }
    var body: some View {
        Group {
        if !cloud.configured {
            Text("إعداد المزامنة غير متوفر في هذه النسخة.").foregroundStyle(.secondary)
        } else if let session = cloud.session {
            LabeledContent("البريد", value: session.email)
            if !session.verified {
                Text("أكّد بريدك من الرابط المرسل إليه لتفعيل المزامنة.").font(.footnote).foregroundStyle(Theme.muted)
                Button("تحققت من البريد") { Task { await cloud.checkVerification() } }
                Button("إعادة إرسال رابط التأكيد") { Task { await cloud.resendVerification() } }
            } else if cloud.profiles.isEmpty {
                TextField("اسم الملف الشخصي", text: $profileName)
                    .focused($focusedInput, equals: .profile)
                    .submitLabel(.done).onSubmit { focusedInput = nil }
                    .disabled(cloud.busy)
                    .accessibilityIdentifier("ios.account.profileName")
                Button("إنشاء ملف شخصي") {
                    focusedInput = nil
                    let name = profileName.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !name.isEmpty, !cloud.busy else { return }
                    Task { await cloud.createFirstProfile(name: name) }
                }
                .disabled(profileName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || cloud.busy)
            } else {
                Picker("الملف الشخصي", selection: profileBinding) {
                    if cloud.profileID == nil { Text("اختر ملفاً").tag("") }
                    ForEach(cloud.profiles) { profile in Text(profile.name).tag(profile.id) }
                }
                .pickerStyle(.menu)
                if cloud.profileID != nil {
                    Button { Task { await cloud.pull() } } label: {
                        Label("مزامنة الآن", systemImage: "arrow.triangle.2.circlepath")
                    }
                    Button("نقل مكتبة هذا الجهاز إلى الملف") { confirmImport = true }
                        .confirmationDialog("نقل مكتبة هذا الجهاز", isPresented: $confirmImport, titleVisibility: .visible) {
                            Button("نقل") { Task { await cloud.importDeviceLibrary() } }
                        } message: {
                            Text("تُضاف قائمتك وتقدّم المشاهدة المحفوظة على الآيفون إلى الملف. الأعمال الموجودة في الاثنين تأخذ قيم هذا الجهاز.")
                        }
                    if !cloud.status.isEmpty { Text(cloud.status).font(.footnote).foregroundStyle(Theme.muted) }
                }
            }
            Button("تسجيل الخروج", role: .destructive) { focusedInput = nil; Task { await cloud.signOut() } }
        } else {
            TextField("البريد الإلكتروني", text: $email)
                .textContentType(.emailAddress)
                .keyboardType(.emailAddress)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .focused($focusedInput, equals: .email)
                .submitLabel(.next).onSubmit { focusedInput = .password }
                .disabled(cloud.busy)
                .accessibilityIdentifier("ios.account.email")
            SecureField("كلمة المرور", text: $password)
                .textContentType(.password)
                .focused($focusedInput, equals: .password)
                .submitLabel(.done).onSubmit { focusedInput = nil }
                .disabled(cloud.busy)
                .accessibilityIdentifier("ios.account.password")
            Button("تسجيل الدخول") { submit(signUp: false) }
            .disabled(email.isEmpty || password.isEmpty || cloud.busy)
            .accessibilityIdentifier("ios.account.login")
            Button("إنشاء حساب جديد") { submit(signUp: true) }
            .disabled(email.isEmpty || password.count < 6 || cloud.busy)
            .accessibilityIdentifier("ios.account.create")
            Button("نسيت كلمة المرور") {
                focusedInput = nil
                let address = email.trimmingCharacters(in: .whitespacesAndNewlines)
                Task { await cloud.resetPassword(email: address) }
            }
                .disabled(email.isEmpty || cloud.busy)
        }
        if cloud.busy { ProgressView().frame(maxWidth: .infinity) }
        if let message = cloud.message {
            Text(message).font(.footnote).foregroundStyle(Theme.accent)
        }
        }
    }

    private func submit(signUp: Bool) {
        let address = email.trimmingCharacters(in: .whitespacesAndNewlines), secret = password
        guard !cloud.busy, !address.isEmpty, !secret.isEmpty, !signUp || secret.count >= 6 else { return }
        focusedInput = nil; password = ""
        Task {
            if signUp { await cloud.signUp(email: address, password: secret) }
            else { await cloud.signIn(email: address, password: secret) }
        }
    }
}
