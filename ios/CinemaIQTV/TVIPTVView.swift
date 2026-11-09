import SwiftUI

/// Entry point for a future IPTV library; no provider account or playlist is stored yet.
struct TVIPTVView: View {
    var body: some View {
        GeometryReader { size in
            ZStack(alignment: .topLeading) {
                Color(red: 0.07, green: 0.08, blue: 0.10).ignoresSafeArea()
                VStack(alignment: .leading, spacing: 30) {
                    Label("IPTV", systemImage: "dot.radiowaves.left.and.right")
                        .font(.system(size: 60, weight: .bold))
                        .foregroundStyle(.white)
                    Text("قنواتك في مكان واحد")
                        .font(.system(size: 34, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.9))
                    Text("قريبًا ستتمكن من إضافة قائمة قنوات أو تسجيل الدخول باشتراك Xtream.")
                        .font(.system(size: 27))
                        .foregroundStyle(.white.opacity(0.68))
                        .fixedSize(horizontal: false, vertical: true)
                    HStack(spacing: 20) {
                        option("رابط قائمة القنوات", icon: "link")
                        option("اشتراك Xtream", icon: "person.crop.rectangle")
                    }
                    Text("قريبًا")
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.7))
                }
                .frame(maxWidth: min(size.size.width * 0.72, 1100), alignment: .leading)
                .padding(.horizontal, 76)
                .padding(.top, 82)
            }
        }
        .environment(\.layoutDirection, .rightToLeft)
    }

    private func option(_ title: String, icon: String) -> some View {
        Label(title, systemImage: icon)
            .font(.system(size: 25, weight: .medium))
            .foregroundStyle(.white.opacity(0.82))
            .padding(.horizontal, 26)
            .padding(.vertical, 22)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 18))
            .overlay { RoundedRectangle(cornerRadius: 18).strokeBorder(.white.opacity(0.14)) }
    }
}
