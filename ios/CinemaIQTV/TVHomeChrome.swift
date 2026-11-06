import SwiftUI
import CinemaCore

/// A compact translucent capsule, rather than the system sidebar. Arabic keeps its natural reading order.
struct TVFloatingNavigation: View {
    let selection: TVTab
    var focus: FocusState<TVTab?>.Binding
    let select: (TVTab) -> Void
    private let items: [(tab: TVTab, title: String, icon: String, id: String)] = [
        (.home, "الرئيسية", "house", "home"),
        (.library, "مكتبتي", "books.vertical", "library"),
        (.search, "البحث", "magnifyingglass", "search"),
        (.settings, "الإعدادات", "gearshape", "settings")
    ]

    var body: some View {
        HStack(spacing: 6) {
            ForEach(items, id: \.tab) { item in
                Button { select(item.tab) } label: {
                    Label(item.title, systemImage: item.icon)
                        .font(.system(size: 23, weight: .semibold))
                        .padding(.horizontal, 23).padding(.vertical, 13)
                        .foregroundStyle(selection == item.tab || focus.wrappedValue == item.tab ? Color.black : .white)
                        .background {
                            if selection == item.tab || focus.wrappedValue == item.tab {
                                Capsule().fill(.white.opacity(focus.wrappedValue == item.tab ? 1 : 0.9))
                            }
                        }
                        .contentShape(Capsule())
                }
                .buttonStyle(TVQuietButtonStyle())
                .focused(focus, equals: item.tab)
                .scaleEffect(focus.wrappedValue == item.tab ? 1.04 : 1)
                .accessibilityIdentifier("tv.navigation.\(item.id)")
                .accessibilityAddTraits(selection == item.tab ? .isSelected : [])
            }
        }
        .padding(6)
        .background(.ultraThinMaterial, in: Capsule())
        .overlay { Capsule().strokeBorder(.white.opacity(0.16), lineWidth: 1) }
        .shadow(color: .black.opacity(0.25), radius: 18, y: 6)
        .fixedSize()
        .focusSection()
        .animation(.easeOut(duration: 0.18), value: focus.wrappedValue)
    }
}

/// Keeps the backdrop crisp above the fold. Only the reading edge and shelf area are shaded, leaving the
/// subject and sky visible as in the supplied reference. Leading/trailing mirror for the Arabic interface.
struct TVHomeBackdrop: View {
    let url: String
    let belowFold: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.layoutDirection) private var direction

    var body: some View {
        ZStack {
            TVTheme.ink
            Artwork(url: url)
                .id(url)
                .transition(.opacity)
            LinearGradient(stops: [
                .init(color: .black.opacity(0.66), location: 0),
                .init(color: .black.opacity(0.28), location: 0.38),
                .init(color: .clear, location: 0.72)
            ], startPoint: direction == .rightToLeft ? .trailing : .leading,
               endPoint: direction == .rightToLeft ? .leading : .trailing)
            LinearGradient(stops: [
                .init(color: TVTheme.ink, location: 0),
                .init(color: TVTheme.ink.opacity(0.7), location: 0.12),
                .init(color: .clear, location: 0.48)
            ], startPoint: .bottom, endPoint: .top)
            if belowFold { Rectangle().fill(.regularMaterial).transition(.opacity) }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.6), value: url)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.35), value: belowFold)
    }
}

struct TVHeroCast: View {
    let people: [TMDBPerson]
    var body: some View {
        HStack(spacing: 14) {
            let portraits = Array(people.filter { !$0.photo.isEmpty }.prefix(3))
            if !portraits.isEmpty {
                HStack(spacing: -12) {
                    ForEach(portraits) { person in
                        Artwork(url: person.photo)
                            .frame(width: 44, height: 44)
                            .clipShape(Circle())
                            .overlay { Circle().strokeBorder(.white.opacity(0.65), lineWidth: 1.5) }
                    }
                }
                .accessibilityHidden(true)
            }
            Text(people.prefix(3).map(\.name).joined(separator: "، "))
                .font(.system(size: 20, weight: .medium))
                .foregroundStyle(.white.opacity(0.9))
                .lineLimit(1)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("بطولة \(people.prefix(3).map(\.name).joined(separator: "، "))")
    }
}

/// The selected capsule stays visible without focus; the remote can focus any dot to select that feature.
struct TVHeroPagination: View {
    let count: Int
    let index: Int
    let select: (Int) -> Void
    @FocusState private var focused: Int?
    let focusChanged: (Bool) -> Void

    var body: some View {
        HStack(spacing: 2) {
            ForEach(0..<count, id: \.self) { dot in
                Button { select(dot) } label: {
                    Capsule()
                        .fill(dot == index || dot == focused ? .white : .white.opacity(0.45))
                        .frame(width: dot == index ? 28 : 8, height: 8)
                        .padding(.horizontal, 6).frame(height: 40)
                        .contentShape(Rectangle())
                }
                .buttonStyle(TVQuietButtonStyle())
                .focused($focused, equals: dot)
                .scaleEffect(focused == dot ? 1.25 : 1)
                .accessibilityLabel("العمل \(dot + 1) من \(count)")
                .accessibilityAddTraits(dot == index ? .isSelected : [])
                .accessibilityIdentifier("tv.hero.page.\(dot)")
            }
        }
        .onChange(of: focused) { _, value in focusChanged(value != nil) }
        .onDisappear { focusChanged(false) }
        .animation(.easeInOut(duration: 0.2), value: index)
    }
}

/// A restrained glass action, with an explicit high-contrast Siri Remote focus state.
struct TVHeroActionStyle: ButtonStyle {
    @Environment(\.isFocused) private var focused
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 24, weight: .semibold))
            .padding(.horizontal, 26).padding(.vertical, 15)
            .foregroundStyle(focused ? Color.black : .white)
            .background {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(focused ? AnyShapeStyle(.white) : AnyShapeStyle(.thinMaterial))
            }
            .overlay {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(.white.opacity(focused ? 1 : 0.2), lineWidth: 1)
            }
            .scaleEffect(configuration.isPressed ? 0.98 : (focused ? 1.04 : 1))
            .shadow(color: .black.opacity(0.18), radius: 10, y: 4)
            .animation(.easeOut(duration: 0.15), value: focused)
    }
}

/// The shelf title is a small translucent label; existing lockups retain resume, focus and long-press actions.
struct TVHomeShelf<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(title)
                .font(.system(size: 24, weight: .semibold))
                .padding(.horizontal, 22).padding(.vertical, 10)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .overlay { RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(.white.opacity(0.12), lineWidth: 1) }
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: 40) { content }
                    .padding(.vertical, 12)
            }
            .scrollClipDisabled()
            .scrollIndicators(.hidden)
        }
        .focusSection()
    }
}

/// The focus appearance is supplied by each compact control, without tvOS adding a second oversized plate.
private struct TVQuietButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.opacity(configuration.isPressed ? 0.8 : 1)
    }
}
