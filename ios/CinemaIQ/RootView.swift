import SwiftUI
import CinemaCore

enum AppTab: Hashable { case home, browse, library, iptv, settings, search }

/// Floating tab bar with the search tab apart from the others, the iOS 26 layout. The player covers every tab.
struct RootView: View {
    @EnvironmentObject private var library: LibraryStore
    @StateObject private var player = PlayerPresenter()
    @StateObject private var iptv = IPTVStore()
    @State private var tab: AppTab = {
#if DEBUG
        if IPTVFixtureSupport.snapshot { return .browse }
#endif
        return .home
    }()
    var body: some View {
        TabView(selection: $tab) {
            Tab("الرئيسية", systemImage: "house.fill", value: AppTab.home) {
                RoutedStack { HomeView() }
            }
            Tab("تصفّح", systemImage: "square.grid.2x2.fill", value: AppTab.browse) {
                RoutedStack { BrowseView() }
            }
            Tab("مكتبتي", systemImage: "rectangle.stack.fill", value: AppTab.library) {
                RoutedStack { LibraryView() }
            }
            // The phone retains its five tabs and reaches IPTV from Browse; iPad also has a sidebar entry.
            if UIDevice.current.userInterfaceIdiom == .pad {
                Tab("IPTV", systemImage: "dot.radiowaves.left.and.right", value: AppTab.iptv) {
                    RoutedStack { IPTVView() }
                }
            }
            Tab("الإعدادات", systemImage: "gearshape.fill", value: AppTab.settings) {
                ResponsiveContainer { NavigationStack { SettingsView() } }
            }
            Tab("البحث", systemImage: "magnifyingglass", value: AppTab.search, role: .search) {
                RoutedStack { SearchView() }
            }
        }
        .tabViewStyle(.sidebarAdaptable)
        .minimizingTabBar()
        .environmentObject(player)
        .environmentObject(iptv)
        .fullScreenCover(item: $player.selection) { selection in
            ResponsiveContainer { PlaybackView(selection: selection).environmentObject(library) }
        }
    }
}
