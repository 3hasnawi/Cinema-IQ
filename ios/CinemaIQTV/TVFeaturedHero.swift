import SwiftUI
import CinemaCore

/// One focus target over the full-screen artwork. There are no featured thumbnails or card containers.
struct TVFeaturedHero: View {
    let media: Media?
    let info: OMDbInfo?
    let count: Int
    let index: Int
    @Binding var focused: Bool
    let loading: Bool
    let error: String?
    let retry: () -> Void
    let open: (Media) -> Void
    let move: (Int) -> Void

    var body: some View {
        Group {
            if let media {
                ZStack(alignment: .leading) {
                    TVCarouselFocusSurface(focused: $focused, title: media.title, position: "\(index + 1) من \(count)",
                                           activate: { open(media) }, move: move)
                    VStack(alignment: .leading, spacing: 22) {
                        Spacer(minLength: 0)
                        Text(media.title)
                            .font(.system(size: 72, weight: .bold))
                            .lineLimit(2).minimumScaleFactor(0.7)
                            .multilineTextAlignment(.leading)
                            .frame(maxWidth: 960, alignment: .leading)
                        FactsLine(facts: DisplayFormat.facts(media, imdb: info?.imdbRating))
                        if !media.overview.isEmpty {
                            Text(media.overview).font(.callout).foregroundStyle(.white.opacity(0.8))
                                .lineLimit(2).multilineTextAlignment(.leading)
                                .frame(maxWidth: 900, alignment: .leading)
                        }
                        if count > 1 {
                            HStack(spacing: 10) {
                                ForEach(0..<count, id: \.self) { position in
                                    Capsule()
                                        .fill(.white.opacity(position == index ? 0.95 : 0.35))
                                        .frame(width: position == index ? 28 : 8, height: 8)
                                }
                            }
                            .padding(.top, 18)
                            .accessibilityHidden(true)
                        }
                    }
                    .padding(.bottom, 64)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
                }
            } else if loading {
                ProgressView("جارٍ تحميل الأعمال…")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .accessibilityIdentifier("tv.home.loading")
            } else if let error {
                ErrorState(message: error, retry: retry)
                    .frame(maxHeight: .infinity)
            } else {
                ContentUnavailableView {
                    Label("لا توجد أعمال متاحة", systemImage: "film")
                } description: {
                    Text("أعد المحاولة أو اختر مصدرًا آخر من الإعدادات.")
                } actions: {
                    Button("إعادة المحاولة", action: retry)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }
}
