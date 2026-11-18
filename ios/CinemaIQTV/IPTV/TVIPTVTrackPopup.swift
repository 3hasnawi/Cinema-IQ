import SwiftUI
import UIKit
import CinemaCore

/// A same-window, icon-anchored drop popover; no large modal sheet or nested focus window.
struct TVIPTVTrackPopup<Content: View>: View {
    let title: String
    let labels: [String]
    let rows: Int
    var footnoteLines: Int = 0
    let anchor: CGRect
    let window: CGSize
    let ready: () -> Void
    @ViewBuilder let content: () -> Content
    private var panelSize: CGSize {
        let font = UIFont.systemFont(ofSize: 26)
        let attributes: [NSAttributedString.Key: Any] = [.font: font]
        var ideal: CGFloat = 260
        for label in labels { ideal = max(ideal, (label as NSString).size(withAttributes: attributes).width) }
        let width: CGFloat = min(600, max(310, ideal + 120))
        let rowHeight = CGFloat(rows) * 76
        let spacing = CGFloat(max(0, rows - 1)) * 8
        let footnoteHeight = CGFloat(footnoteLines) * 32
        let height: CGFloat = min(window.height * 0.55, rowHeight + spacing + footnoteHeight + 100)
        return CGSize(width: width, height: height)
    }
    var body: some View {
        let size = panelSize
        let placed = IPTVPopupPlacement.above(window: window, anchor: anchor, popup: size)
        panel(size: size, pivot: placed.pivot)
            .position(x: placed.origin.x + size.width / 2, y: placed.origin.y + size.height / 2)
            .transition(.scale(scale: 0.72, anchor: UnitPoint(x: placed.pivot, y: 1)).combined(with: .opacity))
            .task { await Task.yield(); ready() }
    }
    private func panel(size: CGSize, pivot: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.system(size: 26, weight: .bold)).foregroundStyle(.white.opacity(0.65)).padding(.horizontal, 16)
                .accessibilityIdentifier("tv.iptv.track.popup")
            ScrollView {
                VStack(spacing: 8, content: content).padding(.vertical, 2)
            }.scrollIndicators(.hidden)
        }
        .padding(.horizontal, 20).padding(.top, 20).padding(.bottom, 40)
        .frame(width: size.width, height: size.height)
        .background(TVIPTVStyle.panel.opacity(0.78), in: TVIPTVDropShape(pivot: pivot))
        .overlay(TVIPTVDropShape(pivot: pivot).stroke(.white.opacity(0.15), lineWidth: 1))
        .environment(\.layoutDirection, .rightToLeft)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("tv.iptv.track.panel")
    }
}
private struct TVIPTVDropShape: Shape {
    let pivot: CGFloat
    func path(in rect: CGRect) -> Path {
        let w = rect.width, bottom = rect.height - 18, r: CGFloat = 36
        let tip = max(r + 18, min(w - r - 18, w * pivot))
        return Path { p in
            p.move(to: CGPoint(x: r, y: 0)); p.addLine(to: CGPoint(x: w-r, y: 0))
            p.addQuadCurve(to: CGPoint(x: w, y: r), control: CGPoint(x: w, y: 0))
            p.addLine(to: CGPoint(x: w, y: bottom-r)); p.addQuadCurve(to: CGPoint(x: w-r, y: bottom), control: CGPoint(x: w, y: bottom))
            p.addLine(to: CGPoint(x: tip+18, y: bottom)); p.addQuadCurve(to: CGPoint(x: tip, y: rect.height), control: CGPoint(x: tip+9, y: bottom))
            p.addQuadCurve(to: CGPoint(x: tip-18, y: bottom), control: CGPoint(x: tip-9, y: bottom))
            p.addLine(to: CGPoint(x: r, y: bottom)); p.addQuadCurve(to: CGPoint(x: 0, y: bottom-r), control: CGPoint(x: 0, y: bottom))
            p.addLine(to: CGPoint(x: 0, y: r)); p.addQuadCurve(to: CGPoint(x: r, y: 0), control: .zero); p.closeSubpath()
        }
    }
}
