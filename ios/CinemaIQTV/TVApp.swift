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
                // System white buttons and lockups, as on the Apple TV app; orange stays for progress only.
                .tint(.white)
                .task { cloud.start(library: library) }
                .onChange(of: phase) { _, value in
                    if value == .active { cloud.scene(active: true) } else if value == .background { cloud.scene(active: false) }
                }
        }
    }
}

enum TVTab: Hashable { case home, movies, series, library, search, settings }

/// The Apple TV app's layout: a sidebar (Menu or the leading edge opens it) over full-screen pages. Every tab has
/// its own navigation, and the player opens full screen over all of them.
struct TVRootView: View {
    @EnvironmentObject private var player: PlayerPresenter
    @EnvironmentObject private var router: TVRouter
    var body: some View {
        TabView(selection: $router.tab) {
            Tab("البحث", systemImage: "magnifyingglass", value: TVTab.search, role: .search) {
                stack(.search) { TVSearchView() }
            }
            Tab("الرئيسية", systemImage: "house", value: TVTab.home) {
                stack(.home) { TVHomeView() }
            }
            Tab("أفلام", systemImage: "film", value: TVTab.movies) {
                stack(.movies) { TVCatalogView(series: false) }
            }
            Tab("مسلسلات", systemImage: "tv", value: TVTab.series) {
                stack(.series) { TVCatalogView(series: true) }
            }
            Tab("مكتبتي", systemImage: "books.vertical", value: TVTab.library) {
                stack(.library) { TVLibraryView() }
            }
            Tab("الإعدادات", systemImage: "gearshape", value: TVTab.settings) {
                NavigationStack { TVSettingsView() }
            }
        }
        .tabViewStyle(.sidebarAdaptable)
        .background(TVTheme.ink.ignoresSafeArea())
        .fullScreenCover(item: $player.selection) { selection in
            TVPlayerView(selection: selection)
        }
    }

    private func stack<Content: View>(_ tab: TVTab, @ViewBuilder _ content: () -> Content) -> some View {
        NavigationStack(path: router.path(tab)) {
            content().navigationDestination(for: Media.self) { media in TVDetailView(initial: media) }
        }
    }
}
