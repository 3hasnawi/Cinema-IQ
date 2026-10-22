import SwiftUI

@main struct CinemaIQApp: App {
    @StateObject private var library = LibraryStore()
    var body: some Scene {
        WindowGroup {
            MainView()
                .environmentObject(library)
                .environment(\.layoutDirection, .rightToLeft)
                .preferredColorScheme(.dark)
                .tint(.orange)
                .font(.custom("Cairo-Regular", size: 16, relativeTo: .body))
        }
    }
}
