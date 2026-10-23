import SwiftUI

/// Cinema iQ colours, shared with the Android app.
enum Theme {
    static let ink = Color(red: 0x1E / 255, green: 0x17 / 255, blue: 0x22 / 255)
    static let panel = Color(red: 0x2B / 255, green: 0x22 / 255, blue: 0x30 / 255)
    static let accent = Color(red: 1, green: 0x8A / 255, blue: 0x2B / 255)
    static let muted = Color(red: 0xB5 / 255, green: 0xA9 / 255, blue: 0xBC / 255)
    static let positive = Color(red: 0.37, green: 0.83, blue: 0.54)
    static let negative = Color(red: 1, green: 0.42, blue: 0.35)
}

// Liquid Glass (iOS 26 and later) where the SDK and the system have it; the same shapes in a material otherwise.
// The compiler check keeps the project building with older Xcode versions, the availability check keeps it
// running on older iPhones.
#if compiler(>=6.2)
@available(iOS 26.0, *)
extension Theme {
    static func glass(tint: Color?, interactive: Bool) -> Glass {
        var glass = Glass.regular
        if let tint { glass = glass.tint(tint) }
        if interactive { glass = glass.interactive() }
        return glass
    }
}

extension View {
    @ViewBuilder func glass<S: Shape>(in shape: S, tint: Color? = nil, interactive: Bool = false) -> some View {
        if #available(iOS 26.0, *) {
            self.glassEffect(Theme.glass(tint: tint, interactive: interactive), in: shape)
        } else {
            self.materialFallback(in: shape)
        }
    }
    /// The main action of a screen: prominent glass in the app's orange.
    @ViewBuilder func primaryAction() -> some View {
        if #available(iOS 26.0, *) {
            self.buttonStyle(.glassProminent).tint(Theme.accent)
        } else {
            self.buttonStyle(.borderedProminent).buttonBorderShape(.capsule).tint(Theme.accent)
        }
    }
    @ViewBuilder func secondaryAction() -> some View {
        if #available(iOS 26.0, *) {
            self.buttonStyle(.glass)
        } else {
            self.buttonStyle(.bordered).buttonBorderShape(.capsule).tint(.white)
        }
    }
    /// The tab bar shrinks while the viewer scrolls down, as in the system apps.
    @ViewBuilder func minimizingTabBar() -> some View {
        if #available(iOS 26.0, *) { self.tabBarMinimizeBehavior(.onScrollDown) } else { self }
    }
}
#else
extension View {
    func glass<S: Shape>(in shape: S, tint: Color? = nil, interactive: Bool = false) -> some View { materialFallback(in: shape) }
    func primaryAction() -> some View { buttonStyle(.borderedProminent).buttonBorderShape(.capsule).tint(Theme.accent) }
    func secondaryAction() -> some View { buttonStyle(.bordered).buttonBorderShape(.capsule).tint(.white) }
    func minimizingTabBar() -> some View { self }
}
#endif

extension View {
    fileprivate func materialFallback<S: Shape>(in shape: S) -> some View {
        background(.ultraThinMaterial, in: shape).overlay(shape.stroke(.white.opacity(0.18), lineWidth: 0.5))
    }
}
