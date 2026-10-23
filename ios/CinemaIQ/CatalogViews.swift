import SwiftUI
import CinemaCore

/// Paged poster grid of one source, movies or series, optionally filtered by a search query.
struct CatalogGrid: View {
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
    private let columns = [GridItem(.adaptive(minimum: 104, maximum: 160), spacing: 12)]

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Picker("نوع العمل", selection: $series) {
                    Text("أفلام").tag(false)
                    Text("مسلسلات").tag(true)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 18)
                LazyVGrid(columns: columns, spacing: 18) {
                    ForEach(items) { media in
                        PosterCard(media: media, routeKey: "grid-\(media.id)", width: nil)
                            .onAppear { if media.id == items.last?.id { Task { await load() } } }
                    }
                }
                .padding(.horizontal, 18)
                if loading {
                    ProgressView().padding()
                } else if let message {
                    ErrorState(message: message) { Task { await load() } }
                } else if items.isEmpty && !more {
                    ContentUnavailableView("لا توجد نتائج", systemImage: "magnifyingglass",
                                           description: Text("جرّب كلمة أخرى أو مصدراً آخر."))
                }
            }
            .padding(.vertical, 8)
        }
        .scrollDismissesKeyboard(.immediately)
        .task(id: series) {
            // Returning from a title page re-runs this task: keep the loaded grid and its scroll position.
            if loadedSeries == series && !items.isEmpty { return }
            items = []; page = 0; more = true; message = nil; loading = false
            // Typing restarts this task, so a search runs once the viewer pauses.
            if !query.isEmpty {
                try? await Task.sleep(for: .milliseconds(350))
                if Task.isCancelled { return }
            }
            await load()
        }
    }

    private func load() async {
        guard !loading, more else { return }
        let kind = series
        loading = true; message = nil
        defer { loading = false }
        do {
            let result = try await SourceClient().catalog(source, series: kind, query: query, page: page + 1)
            // A late answer for the other tab must not be mixed into this one.
            guard kind == series, !Task.isCancelled else { return }
            let existing = Set(items.map(\.id))
            let additions = result.filter { !existing.contains($0.id) }
            items.append(contentsOf: additions)
            loadedSeries = kind
            page += 1
            more = !additions.isEmpty && source != .dawwirli
        } catch is CancellationError {
        } catch {
            if kind == series && !Task.isCancelled { message = error.localizedDescription }
        }
    }
}

struct BrowseView: View {
    @AppStorage(SourcePreference.key) private var sourceName = SourcePreference.fallback.rawValue
    var body: some View {
        CatalogGrid(source: SourcePreference.source(sourceName), query: "")
            .id(sourceName)
            .background(Theme.ink)
            .navigationTitle("تصفّح")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { SourceMenu() } }
    }
}

struct SearchView: View {
    @EnvironmentObject private var library: LibraryStore
    @AppStorage(SourcePreference.key) private var sourceName = SourcePreference.fallback.rawValue
    @State private var query = ""
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
                                Button { query = text } label: {
                                    Label(text, systemImage: "clock.arrow.circlepath")
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                        .padding(.vertical, 8)
                                }
                                .buttonStyle(.plain)
                                Divider()
                            }
                        }
                    }
                    .padding(.horizontal, 18)
                }
            }
        }
        .background(Theme.ink)
        .navigationTitle("البحث")
        .searchable(text: $query, prompt: "اسم الفيلم أو المسلسل")
        .onSubmit(of: .search) { library.remember(search: trimmed) }
        .toolbar { ToolbarItem(placement: .topBarTrailing) { SourceMenu() } }
    }
}

struct LibraryView: View {
    enum Part: String, CaseIterable, Identifiable {
        case list = "قائمتي", recent = "تابع المشاهدة", watched = "شوهد"
        var id: String { rawValue }
    }
    @EnvironmentObject private var library: LibraryStore
    @State private var part: Part = .list
    private let columns = [GridItem(.adaptive(minimum: 104, maximum: 160), spacing: 12)]
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
                .padding(.horizontal, 18)
                if items.isEmpty {
                    ContentUnavailableView(emptyTitle, systemImage: part == .list ? "bookmark" : "play.rectangle",
                                           description: Text(emptyText))
                        .padding(.top, 60)
                } else {
                    LazyVGrid(columns: columns, spacing: 18) {
                        ForEach(items) { media in
                            PosterCard(media: media, routeKey: "library-\(part.id)-\(media.id)", width: nil)
                        }
                    }
                    .padding(.horizontal, 18)
                }
            }
            .padding(.vertical, 8)
        }
        .background(Theme.ink)
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

struct SettingsView: View {
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var providers: ProviderStore
    @AppStorage(SourcePreference.key) private var sourceName = SourcePreference.fallback.rawValue
    @AppStorage(SubtitlePreference.sizeKey) private var subtitleSize = 20.0
    @AppStorage(SubtitlePreference.languageKey) private var subtitleLanguage = "ar"
    @AppStorage(SubtitlePreference.backgroundKey) private var subtitleBackground = 0.5
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
                AccountRows()
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
        .background(Theme.ink)
        .navigationTitle("الإعدادات")
        .navigationBarTitleDisplayMode(.inline)
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
    private var profileBinding: Binding<String> {
        Binding(get: { cloud.profileID ?? "" }, set: { id in
            guard !id.isEmpty else { return }
            Task { await cloud.choose(profile: id) }
        })
    }
    var body: some View {
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
                Button("إنشاء ملف شخصي") { Task { await cloud.createFirstProfile(name: profileName) } }
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
            Button("تسجيل الخروج", role: .destructive) { Task { await cloud.signOut() } }
        } else {
            TextField("البريد الإلكتروني", text: $email)
                .textContentType(.emailAddress)
                .keyboardType(.emailAddress)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            SecureField("كلمة المرور", text: $password)
                .textContentType(.password)
            Button("تسجيل الدخول") {
                Task { await cloud.signIn(email: email, password: password); password = "" }
            }
            .disabled(email.isEmpty || password.isEmpty || cloud.busy)
            Button("إنشاء حساب جديد") {
                Task { await cloud.signUp(email: email, password: password); password = "" }
            }
            .disabled(email.isEmpty || password.count < 6 || cloud.busy)
            Button("نسيت كلمة المرور") { Task { await cloud.resetPassword(email: email) } }
                .disabled(email.isEmpty || cloud.busy)
        }
        if cloud.busy { ProgressView().frame(maxWidth: .infinity) }
        if let message = cloud.message {
            Text(message).font(.footnote).foregroundStyle(Theme.accent)
        }
    }
}
