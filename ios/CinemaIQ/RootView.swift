import SwiftUI
import CinemaCore

enum AppTab: Hashable { case home, browse, library, settings, search }

/// Floating tab bar with the search tab apart from the others, the iOS 26 layout. The player covers every tab.
struct RootView: View {
    @EnvironmentObject private var library: LibraryStore
    @StateObject private var player = PlayerPresenter()
    @State private var tab: AppTab = .home
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
            Tab("الإعدادات", systemImage: "gearshape.fill", value: AppTab.settings) {
                NavigationStack { SettingsView() }
            }
            Tab("البحث", systemImage: "magnifyingglass", value: AppTab.search, role: .search) {
                RoutedStack { SearchView() }
            }
        }
        .minimizingTabBar()
        .environmentObject(player)
        .fullScreenCover(item: $player.selection) { selection in
            PlaybackView(selection: selection).environmentObject(library)
        }
    }
}
