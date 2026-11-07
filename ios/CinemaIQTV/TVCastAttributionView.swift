import SwiftUI
import CoreImage.CIFilterBuiltins
import UIKit

/// tvOS has no general-purpose browser. Public source and license URLs remain visible and scannable.
struct TVCastAttributionView: View {
    let sourceURL: String
    @Environment(\.dismiss) private var dismiss
    private let licenseURL = "https://creativecommons.org/licenses/by-sa/4.0/"

    var body: some View {
        VStack(spacing: 30) {
            Text("مصدر بيانات وصور الممثلين").font(.title2.bold())
            Text("TVmaze · CC BY-SA 4.0")
                .font(.headline)
                .environment(\.layoutDirection, .leftToRight)
            Text("بيانات طاقم العمل من TVmaze بترخيص المشاع الإبداعي: نَسب المُصنَّف – الترخيص بالمثل 4.0. دُمجت مع أسماء المصادر الأخرى، مع الاحتفاظ بالأسماء التي لا تتوفر لها صورة. البيانات المعدّلة المأخوذة من TVmaze متاحة بالشروط نفسها.")
                .font(.callout)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 1250)
            HStack(alignment: .top, spacing: 100) {
                reference(title: "صفحة المصدر", url: sourceURL)
                reference(title: "شروط الترخيص", url: licenseURL)
            }
            Text("امسح الرمز بكاميرا هاتفك لفتح الرابط.")
                .font(.caption).foregroundStyle(TVTheme.muted)
            Button("رجوع") { dismiss() }
                .accessibilityIdentifier("tv.cast.attribution.close")
        }
        .padding(60)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(TVTheme.ink.ignoresSafeArea())
        .onExitCommand { dismiss() }
    }

    private func reference(title: String, url: String) -> some View {
        VStack(spacing: 16) {
            Text(title).font(.headline)
            if let code = qrCode(url) {
                Image(uiImage: code)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 200, height: 200)
                    .padding(12).background(.white)
                    .accessibilityHidden(true)
            }
            Text(url).font(.caption2).multilineTextAlignment(.center)
                .environment(\.layoutDirection, .leftToRight)
                .frame(width: 580)
        }
    }

    private func qrCode(_ value: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(value.utf8)
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)),
              let image = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: image)
    }
}
