import SwiftUI
import CinemaCore

enum AppTab: Hashable { case home, browse, iptv, library, search }

struct RootView: View {
    @EnvironmentObject private var library: LibraryStore
    @StateObject private var player = PlayerPresenter()
    @StateObject private var iptv = IPTVStore()
    @State private var showingSettings = false
    @State private var tab: AppTab = {
#if DEBUG
        if IPTVFixtureSupport.snapshot { return .iptv }
#endif
        return .home
    }()

    var body: some View {
        GeometryReader { window in
            // A portrait or narrow iPad presents its sidebar over the hero. Start those windows
            // in the native tab bar and reserve the expanded sidebar for a wide column layout.
            tabs.defaultAdaptableTabBarPlacement(window.size.width >= 1000 && window.size.width > window.size.height ? .sidebar : .tabBar)
        }
        .environmentObject(player)
        .environmentObject(iptv)
        .sheet(isPresented: $showingSettings) { settings }
        .fullScreenCover(item: $player.selection) { selection in
            ResponsiveContainer { PlaybackView(selection: selection).environmentObject(library) }
        }
    }

    private var tabs: some View {
        TabView(selection: $tab) {
            Tab("الرئيسية", systemImage: "house.fill", value: AppTab.home) {
                RoutedStack { HomeView(isActive: tab == .home && !showingSettings, openSettings: { showingSettings = true }, openLibrary: { tab = .library }) }
            }
            Tab("تصفّح", systemImage: "square.grid.2x2.fill", value: AppTab.browse) {
                RoutedStack { BrowseView() }
            }
            Tab("التلفزيون", systemImage: PhoneTelevisionSymbol.name, value: AppTab.iptv) {
                RoutedStack { IPTVView() }
            }
            Tab("مكتبتي", systemImage: "bookmark.fill", value: AppTab.library) {
                RoutedStack { LibraryView() }
            }
            Tab("البحث", systemImage: "magnifyingglass", value: AppTab.search, role: .search) {
                RoutedStack { SearchView() }
            }
        }
        .tabViewStyle(.sidebarAdaptable)
        .tabViewSidebarHeader { CinemaWordmark().padding(.vertical, 8) }
        .tabViewSidebarFooter {
            Button { showingSettings = true } label: {
                Label("الإعدادات", systemImage: "gearshape").frame(maxWidth: .infinity, alignment: .leading)
                    .frame(minHeight: 44)
            }
            .accessibilityIdentifier("ios.sidebar.settings")
        }
        .minimizingTabBar()
    }

    private var settings: some View {
        ResponsiveContainer {
            NavigationStack {
                SettingsView()
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("إغلاق") { showingSettings = false }
                                .accessibilityIdentifier("ios.settings.close")
                        }
                    }
            }
        }
    }
}

struct CinemaWordmark: View {
    var body: some View {
        HStack(spacing: 5) {
            Image(systemName: "camera.aperture").foregroundStyle(Theme.accent)
            Text("Cinema") + Text(" iQ").foregroundColor(Theme.accent)
        }
        .font(.headline.weight(.bold))
        .environment(\.layoutDirection, .leftToRight)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Cinema iQ")
    }
}
