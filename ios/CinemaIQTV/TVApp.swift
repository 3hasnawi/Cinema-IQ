import SwiftUI
import CinemaCore

@main struct CinemaIQTVApp: App {
    @StateObject private var library = LibraryStore()
    @StateObject private var cloud = CloudSync()
    // Created first, so Core knows the external providers before any page asks for one.
    @StateObject private var providers = ProviderStore()
    @StateObject private var player = PlayerPresenter()
    @StateObject private var router = TVRouter()
    @Environment(\.scenePhase) private var phase
    init() {
        #if DEBUG
        // Dedicated test flags seed mutable defaults. Using -ios.contentSource.v1 would place the value in
        // NSArgumentDomain, shadowing later picker changes and making a settings regression test misleading.
        let arguments = ProcessInfo.processInfo.arguments
        let preferences = ["-tv.testSource": SourcePreference.key,
                           "-tv.testSubtitleLanguage": SubtitlePreference.languageKey,
                           "-tv.testSubtitleSize": SubtitlePreference.sizeKey]
        for (flag, key) in preferences {
            guard let index = arguments.firstIndex(of: flag), arguments.indices.contains(index + 1) else { continue }
            let value = arguments[index + 1]
            if key == SubtitlePreference.sizeKey, let size = Double(value) {
                UserDefaults.standard.set(size, forKey: key)
            } else {
                UserDefaults.standard.set(value, forKey: key)
            }
        }
        #endif
    }
    var body: some Scene {
        WindowGroup {
            TVRootView()
                .environmentObject(library)
                .environmentObject(cloud)
                .environmentObject(providers)
                .environmentObject(player)
                .environmentObject(router)
                .environment(\.layoutDirection, .rightToLeft)
                .preferredColorScheme(.dark)
                .task { cloud.start(library: library) }
                .onChange(of: phase) { _, value in
                    if value == .active { cloud.scene(active: true) } else if value == .background { cloud.scene(active: false) }
                }
        }
    }
}

enum TVTab: Hashable, CaseIterable { case home, movies, series, library, search, settings }

/// Four floating destinations over the artwork, with catalog shortcuts on Home. Detail navigation and the
/// full-screen player still use the same router and presenters as the previous sidebar layout.
struct TVRootView: View {
    @EnvironmentObject private var player: PlayerPresenter
    @EnvironmentObject private var router: TVRouter
    @FocusState private var navigationFocus: TVTab?
    @State private var visibleRoots: Set<TVTab> = []
    @State private var visitedTabs: Set<TVTab> = [.home]
    @State private var initialTabApplied = false
    private var primaryTab: TVTab { router.tab == .movies || router.tab == .series ? .home : router.tab }
    private var atRoot: Bool { visibleRoots.contains(router.tab) && router.paths[router.tab, default: []].isEmpty }

    var body: some View {
        ZStack {
            ZStack {
                ForEach(TVTab.allCases.filter { visitedTabs.contains($0) }, id: \.self) { tab in
                    tabStack(tab)
                        .opacity(router.tab == tab ? 1 : 0)
                        .allowsHitTesting(router.tab == tab)
                        .disabled(router.tab != tab || router.popup != nil)
                        .accessibilityElement(children: router.tab == tab ? .contain : .ignore)
                        .accessibilityHidden(router.tab != tab)
                        .zIndex(router.tab == tab ? 1 : 0)
                }
            }
            .background(TVTheme.ink.ignoresSafeArea())
            .fullScreenCover(item: $player.selection) { selection in
                TVPlayerView(selection: selection)
            }
            if let popup = router.popup {
                TVOptionsPopup(popup: popup) { withAnimation(.easeOut(duration: 0.2)) { router.popup = nil } }
                    .zIndex(1)
            }
        }
        .animation(.easeOut(duration: 0.25), value: router.popup?.id)
        .onExitCommand(perform: exitAction)
        .onAppear { applyInitialTab() }
        .onChange(of: router.tab) { _, tab in visitedTabs.insert(tab) }
    }

    /// Tabs are mounted lazily and then retained, preserving search text, scroll positions and unfinished
    /// settings forms. Hidden stacks cannot receive input, accessibility focus or navigation-bar focus.
    private func tabStack(_ tab: TVTab) -> some View {
        NavigationStack(path: router.path(tab)) {
            page(tab)
                .onAppear { visibleRoots.insert(tab) }
                .onDisappear { visibleRoots.remove(tab) }
                .safeAreaInset(edge: .top, spacing: 22) {
                    Group {
                        if router.tab == tab {
                            TVFloatingNavigation(selection: primaryTab, focus: $navigationFocus) { destination in
                                visitedTabs.insert(destination)
                                router.tab = destination
                            }
                        } else {
                            Color.clear
                        }
                    }
                    .frame(height: 72)
                    .padding(.top, 6)
                }
                .navigationDestination(for: Media.self) { media in TVDetailView(initial: media) }
        }
    }

    @ViewBuilder private func page(_ tab: TVTab) -> some View {
        switch tab {
        case .home: TVHomeView()
        case .movies: TVCatalogView(series: false)
        case .series: TVCatalogView(series: true)
        case .library: TVLibraryView()
        case .search: TVSearchView()
        case .settings: TVSettingsView()
        }
    }

    /// A test-only starting destination, without writing the viewer's settings or authentication state.
    private func applyInitialTab() {
        guard !initialTabApplied else { return }
        initialTabApplied = true
        #if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        guard let position = arguments.firstIndex(of: "-tv.initialTab"), arguments.indices.contains(position + 1) else { return }
        let tabs: [String: TVTab] = ["home": .home, "movies": .movies, "series": .series,
                                   "library": .library, "search": .search, "settings": .settings]
        if let tab = tabs[arguments[position + 1]] { visitedTabs.insert(tab); router.tab = tab }
        #endif
    }

    /// Menu closes an overlay first, otherwise lets NavigationStack go back. At a page root it returns focus
    /// to the floating navigation; a second Menu is left to the system so the viewer can leave the app.
    private var exitAction: (() -> Void)? {
        if router.popup != nil {
            return { withAnimation(.easeOut(duration: 0.2)) { router.popup = nil } }
        }
        guard atRoot, player.selection == nil else { return nil }
        if router.tab == .movies || router.tab == .series {
            return { router.tab = .home; navigationFocus = .home }
        }
        if navigationFocus == nil { return { navigationFocus = primaryTab } }
        return nil
    }
}
