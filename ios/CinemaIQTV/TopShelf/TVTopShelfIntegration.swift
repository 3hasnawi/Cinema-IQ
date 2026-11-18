import SwiftUI
import CinemaCore

struct TVTopShelfIntegration: ViewModifier {
    @EnvironmentObject private var appDelegate: TVShelfAppDelegate
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var cloud: CloudSync
    @EnvironmentObject private var router: TVRouter
    @EnvironmentObject private var player: PlayerPresenter
    @Environment(\.scenePhase) private var phase
    @AppStorage(SourcePreference.key) private var sourceName = SourcePreference.fallback.rawValue
    @StateObject private var bridge = TVShelfBridge()
    @State private var refreshTask: Task<Void, Never>?
    func body(content: Content) -> some View {
        content
            .disabled(bridge.opening)
            .task {
                // Activate the real account/profile before publishing anything from its library.
                cloud.start(library: library)
                bridge.attach(library, source: SourcePreference.source(sourceName))
                if let url = appDelegate.consume() { bridge.open(url, router: router, player: player) }
#if DEBUG
                await bridge.seedAudit()
#endif
                await bridge.refresh(SourcePreference.source(sourceName))
            }
            .onReceive(NotificationCenter.default.publisher(for: LibraryStore.topShelfDidChange)) { note in
                if let changed = note.object as? LibraryStore, changed === library { bridge.publish() }
            }
            .onChange(of: sourceName) { _, _ in refresh() }
            .onChange(of: phase) { _, value in
                bridge.publish()
                if value == .active { refresh() }
            }
            .onChange(of: appDelegate.pendingURL) { _, value in
                // onChange runs after @Published has stored the value, including a cold-launch request.
                if value != nil, bridge.attached, let url = appDelegate.consume() {
                    bridge.open(url, router: router, player: player)
                }
            }
            .overlay {
                if bridge.opening {
                    ProgressView("جارٍ فتح العمل…").padding(32).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 24))
                }
            }
            .onExitCommand(perform: bridge.opening ? { bridge.cancelOpen() } : nil)
            .alert("تعذّر فتح العمل", isPresented: Binding(get: { bridge.problem != nil }, set: { if !$0 { bridge.problem = nil } })) {
                Button("حسنًا", role: .cancel) { bridge.problem = nil }
            } message: { Text(bridge.problem ?? "") }
#if DEBUG
            .overlay(alignment: .topLeading) {
                if ProcessInfo.processInfo.arguments.contains("--cinema-top-shelf-testing") ||
                    ProcessInfo.processInfo.arguments.contains("--cinema-top-shelf-link-testing") {
                    Text(ProcessInfo.processInfo.arguments.contains("--cinema-top-shelf-link-testing") ?
                         appDelegate.audit + ":" + bridge.audit : bridge.audit).font(.caption).padding(8).background(.black.opacity(0.8))
                        .accessibilityIdentifier("tv.topshelf.audit")
                }
            }
#endif
            .onDisappear { refreshTask?.cancel(); bridge.cancelOpen() }
    }
    private func refresh() {
        refreshTask?.cancel()
        refreshTask = Task { await bridge.refresh(SourcePreference.source(sourceName)) }
    }
}
