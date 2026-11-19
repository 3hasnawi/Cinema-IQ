import SwiftUI
import Security
import CinemaCore

/// Rules and the device PIN are committed together to a device-only Keychain item.
@MainActor final class TVIPTVCategoryAccess: ObservableObject {
    private struct Record: Codable {
        var pin: String?
        var sources: [String: IPTVGroupRules] = [:]
    }
    @Published private var record = Record()
    @Published private(set) var loaded = false
    @Published private(set) var unavailable = false
    @Published private(set) var revision = UUID()
    @Published var guide: IPTVTelevisionGuide?
    private let service: String
    private static var didResetTestKey = false
    init() {
        var name = "com.cinemaplus.rebuilt.tv.iptv.categories"
#if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--cinema-tv-ui-testing-iptv") { name += ".uitests" }
#endif
        service = name
    }
    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
         kSecAttrAccount as String: "rules.v1", kSecAttrSynchronizable as String: false]
    }
    var hasPIN: Bool { record.pin != nil }
    func matches(_ pin: String) -> Bool { hasPIN && pin == record.pin }
    func rules(_ source: String) -> IPTVGroupRules { record.sources[source] ?? .init() }
    func load() {
        guard !loaded || unavailable else { return }
#if DEBUG
        if service.hasSuffix(".uitests"), !Self.didResetTestKey, ProcessInfo.processInfo.arguments.contains("--cinema-tv-ui-testing-iptv-reset") {
            SecItemDelete(query as CFDictionary); Self.didResetTestKey = true
        }
#endif
        var request = query; request[kSecReturnData as String] = true
        var result: CFTypeRef?
        let status = SecItemCopyMatching(request as CFDictionary, &result)
        if status == errSecItemNotFound { record = Record(); unavailable = false }
        else if status == errSecSuccess, let data = result as? Data, let restored = try? JSONDecoder().decode(Record.self, from: data) {
            record = restored; unavailable = false
        } else { unavailable = true }
        loaded = true
    }
    enum Change { case lock, unlock, hide, restore }
    func apply(_ change: Change, source: String, group: String, pin: String?) -> Bool {
        guard loaded, !unavailable else { return false }
        var next = record, rules = rules(source)
        // A hidden, locked category still requires the original PIN when restored or unlocked.
        if change == .lock || rules.locked.contains(group) {
            guard let pin, pin.count == 4, pin.allSatisfy({ $0.isASCII && $0.isNumber }),
                  record.pin == nil || matches(pin) else { return false }
            next.pin = pin
        }
        switch change {
        case .lock: rules.locked.insert(group)
        case .unlock: rules.locked.remove(group)
        case .hide: rules.hidden.insert(group)
        case .restore: rules.hidden.remove(group)
        }
        next.sources[source] = rules
        guard let data = try? JSONEncoder().encode(next) else { return false }
        let attributes: [String: Any] = [kSecValueData as String: data, kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
        var status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound { status = SecItemAdd(query.merging(attributes) { _, new in new } as CFDictionary, nil) }
        guard status == errSecSuccess else { return false }
        record = next; revision = UUID()
        return true
    }
}

/// A remote keypad keeps PIN entry in the same focus system and never opens a second keyboard.
struct TVIPTVPinPad: View {
    let creating: Bool
    let submit: (String) -> Bool
    let cancel: () -> Void
    @State private var digits = ""
    @State private var first: String?
    @State private var message: String?
    @FocusState private var focus: String?
    var body: some View {
        VStack(spacing: 18) {
            Text(creating ? (first == nil ? "أنشئ رمزًا من أربعة أرقام" : "أكّد رمز القفل") : "أدخل رمز القفل").font(.title3.bold())
            Text(String(repeating: "●", count: digits.count) + String(repeating: "○", count: 4 - digits.count))
                .monospaced().accessibilityLabel("تم إدخال \(digits.count) أرقام")
            if let message { Text(message).font(.caption).foregroundStyle(.orange) }
            Grid(horizontalSpacing: 14, verticalSpacing: 12) {
                ForEach(0..<4, id: \.self) { row in
                    GridRow {
                        ForEach(Array(["1","2","3","4","5","6","7","8","9","حذف","0","إلغاء"][(row*3)..<(row*3+3)]), id: \.self) { key in
                            Button(key) { press(key) }.frame(minWidth: 84).focused($focus, equals: key)
                                .accessibilityIdentifier("tv.iptv.pin." + key)
                        }
                    }
                }
            }.environment(\.layoutDirection, .leftToRight)
        }.padding(46).background(TVIPTVStyle.panel, in: RoundedRectangle(cornerRadius: 28))
            .defaultFocus($focus, "1").onAppear { focus = "1" }.onExitCommand(perform: cancel)
    }
    private func press(_ key: String) {
        if key == "إلغاء" { cancel(); return }
        if key == "حذف" { if !digits.isEmpty { digits.removeLast() }; return }
        message = nil; digits += key
        guard digits.count == 4 else { return }
        let value = digits; digits = ""
        if creating && first == nil { first = value; return }
        if creating && first != value { first = nil; message = "الرمزان غير متطابقين"; return }
        if !submit(value) { message = "تعذّر التحقق من الرمز أو حفظ التغيير" }
    }
}

struct TVIPTVCategoryManager: View {
    @EnvironmentObject private var access: TVIPTVCategoryAccess
    let source: String
    let categories: [IPTVGuideCategory]
    let done: () -> Void
    @State private var selected: String?
    @State private var pending: TVIPTVCategoryAccess.Change?
    @State private var confirmHide = false
    @State private var error = false
    @FocusState private var focus: String?
    var body: some View {
        ZStack {
            NavigationStack {
                List {
                    if let group = selected {
                        let rules = access.rules(source)
                        Button(rules.locked.contains(group) ? "إزالة القفل" : "قفل برمز PIN") {
                            pending = rules.locked.contains(group) ? .unlock : .lock
                        }.focused($focus, equals: "lock").accessibilityIdentifier("tv.iptv.manage.lock")
                        Button(rules.hidden.contains(group) ? "استعادة التصنيف" : "حذف من القائمة") {
                            if rules.hidden.contains(group) { request(.restore) } else { confirmHide = true }
                        }.focused($focus, equals: "hide").accessibilityIdentifier("tv.iptv.manage.hide")
                    } else {
                        ForEach(categories) { category in
                            Button {
                                selected = category.name; focus = "lock"
                            } label: {
                                HStack {
                                    Text(category.name); Spacer()
                                    if access.rules(source).hidden.contains(category.name) { Image(systemName: "eye.slash") }
                                    if access.rules(source).locked.contains(category.name) { Image(systemName: "lock.fill") }
                                }
                            }.focused($focus, equals: "group." + category.name)
                                .accessibilityIdentifier("tv.iptv.manage.group." + category.name)
                        }
                    }
                    Button("رجوع", action: back)
                }.navigationTitle(selected ?? "إدارة التصنيفات")
            }.disabled(pending != nil)
            if let pending {
                TVIPTVPinPad(creating: !access.hasPIN, submit: { apply(pending, pin: $0) }, cancel: { self.pending = nil; focus = "lock" })
            }
        }.environment(\.layoutDirection, .rightToLeft)
            .onExitCommand(perform: pending == nil ? back : nil)
            .confirmationDialog("حذف التصنيف من قائمة القنوات؟", isPresented: $confirmHide) {
                Button("حذف من القائمة", role: .destructive) { request(.hide) }; Button("إلغاء", role: .cancel) {}
            }
            .alert("تعذّر حفظ التغيير", isPresented: $error) { Button("موافق") {} }
    }
    private func back() {
        if let selected { self.selected = nil; focus = "group." + selected } else { done() }
    }
    private func request(_ change: TVIPTVCategoryAccess.Change) {
        guard let selected else { return }
        if access.rules(source).locked.contains(selected) { pending = change }
        else if !apply(change, pin: nil) { error = true }
    }
    private func apply(_ change: TVIPTVCategoryAccess.Change, pin: String?) -> Bool {
        guard let selected, access.apply(change, source: source, group: selected, pin: pin) else { return false }
        pending = nil; focus = "group." + selected; self.selected = nil
        return true
    }
}
