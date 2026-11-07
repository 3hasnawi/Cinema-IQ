import SwiftUI
import CinemaCore

/// Informational credit with circular focus feedback, not a button that promises a person page.
struct TVCastLockup: View {
    let person: CastCredit
    @FocusState private var isFocused: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(spacing: 16) {
            Circle()
                .fill(TVTheme.raised)
                .frame(width: 180, height: 180)
                .overlay {
                    Text(initials)
                        .font(.system(size: 44, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.85))
                }
                .overlay {
                    if let url = URL(string: person.photo), !person.photo.isEmpty {
                        AsyncImage(url: url) { phase in
                            if let image = phase.image { image.resizable().scaledToFill() }
                        }
                    }
                }
                .clipShape(Circle())
                .overlay {
                    Circle().strokeBorder(.white.opacity(isFocused ? 0.95 : 0), lineWidth: 4)
                }
                .scaleEffect(isFocused && !reduceMotion ? 1.08 : 1)
            Text(person.name).font(.caption).lineLimit(1).frame(width: 220)
        }
        // The full credit stays focusable for remote scrolling and VoiceOver, but its default rectangular
        // focus background must not surround the circular portrait and the name. Draw our own ring above.
        .focusable()
        .focused($isFocused)
        .focusEffectDisabled()
        .animation(reduceMotion ? nil : .easeOut(duration: 0.16), value: isFocused)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(person.name)
        .accessibilityIdentifier("tv.cast.person.\(person.id)")
    }

    private var initials: String {
        person.name.split(separator: " ").prefix(2).compactMap(\.first).map(String.init).joined()
    }
}

#if DEBUG
/// Offline UI-test fixture. It cannot be launched in a distributed Release app and never starts cloud sync.
struct TVCastFocusFixture: View {
    @State private var presentationCount = 0
    @State private var showAttribution = false
    @FocusState private var entryFocused: Bool
    @FocusState private var attributionFocused: Bool
    private let people = (1...12).map { index in
        CastCredit(id: String(index), name: index == 1 ? "ممثل بدون صورة" : "ممثل الاختبار \(index)", photo: "")
    }

    var body: some View {
        VStack(spacing: 60) {
            Button("قبل طاقم العمل") { presentationCount += 1 }
                .accessibilityIdentifier("tv.cast.fixture.before")
                .focused($entryFocused)
            TVShelf(title: "طاقم العمل") {
                ForEach(people) { TVCastLockup(person: $0) }
            }
            Button("بعد طاقم العمل") { presentationCount += 1 }
                .accessibilityIdentifier("tv.cast.fixture.after")
            Text("\(presentationCount)")
                .accessibilityIdentifier("tv.cast.fixture.activations")
            Button("TVmaze · CC BY-SA 4.0") { showAttribution = true }
                .accessibilityIdentifier("tv.cast.fixture.attribution")
                .focused($attributionFocused)
        }
        .padding(80)
        .background(TVTheme.ink.ignoresSafeArea())
        .defaultFocus($entryFocused, true)
        .fullScreenCover(isPresented: $showAttribution, onDismiss: { attributionFocused = true }) {
            TVCastAttributionView(sourceURL: "https://www.tvmaze.com/shows/73612")
        }
    }
}
#endif
