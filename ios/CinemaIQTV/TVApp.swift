import SwiftUI
import CinemaCore

@main struct CinemaIQTVApp: App {
    @StateObject private var library = LibraryStore()
    @StateObject private var cloud = CloudSync()
    // Created first, so Core knows the external providers before any page asks for one.
    @StateObject private var providers = ProviderStore()
    @StateObject private var player = PlayerPresenter()
    @Environment(\.scenePhase) private var phase
    var body: some Scene {
        WindowGroup {
            TVRootView()
                .environmentObject(library)
                .environmentObject(cloud)
                .environmentObject(providers)
                .environmentObject(player)
                .environment(\.layoutDirection, .rightToLeft)
                .preferredColorScheme(.dark)
                .tint(TVTheme.accent)
                .task { cloud.start(library: library) }
                .onChange(of: phase) { _, value in
                    if value == .active { cloud.scene(active: true) } else if value == .background { cloud.scene(active: false) }
                }
        }
    }
}

enum TVTab: Hashable { case home, movies, series, library, search, settings }

/// The tvOS top tab bar: Home, Movies, Series, My List, Search, Settings. Every tab has its own navigation, and
/// the player opens full screen over all of them.
struct TVRootView: View {
    @EnvironmentObject private var player: PlayerPresenter
    @State private var tab = TVTab.home
    var body: some View {
        TabView(selection: $tab) {
            NavigationStack { TVHomeView().withDetailRoutes() }
                .tabItem { Label("الرئيسية", systemImage: "house.fill") }.tag(TVTab.home)
            NavigationStack { TVCatalogView(series: false).withDetailRoutes() }
                .tabItem { Label("أفلام", systemImage: "film") }.tag(TVTab.movies)
            NavigationStack { TVCatalogView(series: true).withDetailRoutes() }
                .tabItem { Label("مسلسلات", systemImage: "tv") }.tag(TVTab.series)
            NavigationStack { TVLibraryView().withDetailRoutes() }
                .tabItem { Label("مكتبتي", systemImage: "rectangle.stack.fill") }.tag(TVTab.library)
            NavigationStack { TVSearchView().withDetailRoutes() }
                .tabItem { Label("البحث", systemImage: "magnifyingglass") }.tag(TVTab.search)
            NavigationStack { TVSettingsView() }
                .tabItem { Label("الإعدادات", systemImage: "gearshape.fill") }.tag(TVTab.settings)
        }
        .background(TVTheme.ink.ignoresSafeArea())
        .fullScreenCover(item: $player.selection) { selection in
            TVPlayerView(selection: selection)
        }
    }
}

extension View {
    /// Opens a title's page from any card in this navigation stack.
    func withDetailRoutes() -> some View {
        navigationDestination(for: Media.self) { media in TVDetailView(initial: media) }
    }
}
