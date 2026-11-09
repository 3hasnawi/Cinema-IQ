import SwiftUI
import CinemaCore

/// Artwork and title stay still while the viewer moves across the featured cards with the remote.
struct TVFeaturedHero: View {
    let media: Media?
    let info: OMDbInfo?
    let featured: [Media]
    var focused: FocusState<String?>.Binding
    let loading: Bool
    let error: String?
    let retry: () -> Void
    let open: (Media) -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            Spacer(minLength: 0)
            if let media {
                Text(media.title)
                    .font(.system(size: 72, weight: .bold))
                    .lineLimit(2).minimumScaleFactor(0.7)
                    .frame(maxWidth: 960, alignment: .leading)
                    .accessibilityIdentifier("tv.home.featuredTitle")
                    .multilineTextAlignment(.leading)
                FactsLine(facts: DisplayFormat.facts(media, imdb: info?.imdbRating))
                if !media.overview.isEmpty {
                    Text(media.overview).font(.callout).foregroundStyle(.white.opacity(0.8))
                        .lineLimit(2).frame(maxWidth: 900, alignment: .leading)
                }
                Text("أعمال مختارة").font(.title3.weight(.semibold)).padding(.top, 14)
                ScrollView(.horizontal) {
                    LazyHStack(spacing: 22) {
                        ForEach(featured) { item in
                            Button { open(item) } label: {
                                Artwork(url: item.backdrop.isEmpty ? item.poster : item.backdrop)
                                    .frame(width: 300, height: 169)
                                    .overlay(alignment: .bottomLeading) {
                                        Text(item.title).font(.caption.weight(.semibold)).lineLimit(1)
                                            .padding(14).frame(maxWidth: .infinity, alignment: .leading)
                                            .background(.black.opacity(0.65))
                                    }
                                    .clipShape(RoundedRectangle(cornerRadius: 14))
                                    .overlay {
                                        RoundedRectangle(cornerRadius: 14)
                                            .strokeBorder(.white.opacity(focused.wrappedValue == item.id ? 1 : 0), lineWidth: 5)
                                    }
                            }
                            .buttonStyle(TVArtworkButtonStyle())
                            .focused(focused, equals: item.id)
                            .scaleEffect(focused.wrappedValue == item.id && !reduceMotion ? 1.04 : 1)
                            .animation(reduceMotion ? nil : .easeOut(duration: 0.18), value: focused.wrappedValue)
                            .accessibilityLabel(item.title)
                            .accessibilityIdentifier("tv.home.feature.\(item.id)")
                        }
                    }.padding(.vertical, 12)
                }.scrollClipDisabled().scrollIndicators(.hidden).focusSection()
            } else if loading {
                ProgressView().frame(maxWidth: .infinity)
            } else if let error {
                ErrorState(message: error, retry: retry)
            }
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}
