import SwiftUI
import Security
import CinemaCore

/// Rules and the device PIN are committed together to a device-only Keychain item.
@MainActor final class TVIPTVCategoryAccess: ObservableObject {
    private struct Record: Codable {
        var pin: String?
        var sources: [String: IPTVGroupRules] = [:]
        var failures: Int?
        var lastAttempt: Date?
        var blockedUntil: Date?
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
    var retryDelay: Int {
        guard let end = record.blockedUntil else { return 0 }
        return max(0, Int(ceil(end.timeIntervalSince(max(Date(), record.lastAttempt ?? .distantPast)))))
    }
    func matches(_ pin: String) -> Bool {
        guard loaded, !unavailable, let saved = record.pin, retryDelay == 0 else { return false }
        var next = record
        let now = max(Date(), next.lastAttempt ?? .distantPast)
        next.lastAttempt = now
        let accepted = pin == saved
        let failures = accepted ? 0 : min(100, (next.failures ?? 0) + 1)
        next.failures = failures
        next.blockedUntil = failures < 5 ? nil : now.addingTimeInterval(Double(min(900, 30 << min(failures - 5, 5))))
        return save(next) && accepted
    }
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
        var rules = rules(source)
        var firstPIN: String?
        // A hidden, locked category still requires the original PIN when restored or unlocked.
        if change == .lock || rules.locked.contains(group) {
            guard let pin, pin.count == 4, pin.allSatisfy({ $0.isASCII && $0.isNumber }),
                  record.pin == nil || matches(pin) else { return false }
            if !hasPIN { firstPIN = pin }
        }
        switch change {
        case .lock: rules.locked.insert(group)
        case .unlock: rules.locked.remove(group)
        case .hide: rules.hidden.insert(group)
        case .restore: rules.hidden.remove(group)
        }
        var next = record
        if let firstPIN { next.pin = firstPIN }
        next.sources[source] = rules
        guard save(next) else { return false }
        revision = UUID()
        return true
    }
    private func save(_ next: Record) -> Bool {
        guard let data = try? JSONEncoder().encode(next) else { return false }
        let attributes: [String: Any] = [kSecValueData as String: data, kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
        var status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound { status = SecItemAdd(query.merging(attributes) { _, new in new } as CFDictionary, nil) }
        guard status == errSecSuccess else { return false }
        record = next
        return true
    }
}

/// A remote keypad keeps PIN entry in the same focus system and never opens a second keyboard.
struct TVIPTVPinPad: View {
    @EnvironmentObject private var access: TVIPTVCategoryAccess
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
        if !submit(value) {
            message = access.retryDelay > 0 ? "أعد المحاولة بعد \(access.retryDelay) ثانية" : "تعذّر التحقق من الرمز أو حفظ التغيير"
        }
    }
}

struct TVIPTVCategoryManager: View {
    @EnvironmentObject private var access: TVIPTVCategoryAccess
    let source: String
    let categories: [IPTVGuideCategory]
    let done: () -> Void
    @State private var selected: String?
    @State private var anchorGroup: String?
    @State private var pending: TVIPTVCategoryAccess.Change?
    @State private var confirmHide = false
    @State private var error = false
    @FocusState private var focus: String?
    var body: some View {
        ZStack {
            VStack(alignment: .leading, spacing: 20) {
                Text(selected ?? "إدارة التصنيفات").font(.title2.bold())
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 12) {
                            if let group = selected {
                                let rules = access.rules(source)
                                action(rules.locked.contains(group) ? "إزالة القفل" : "قفل برمز PIN", id: "lock") {
                                    pending = rules.locked.contains(group) ? .unlock : .lock
                                }
                                action(rules.hidden.contains(group) ? "استعادة التصنيف" : "حذف من القائمة", id: "hide") {
                                    if rules.hidden.contains(group) { request(.restore) } else { confirmHide = true }
                                }
                            } else {
                                ForEach(categories) { category in
                                    action(category.name, id: "group." + category.name,
                                           symbol: access.rules(source).hidden.contains(category.name) ? "eye.slash" :
                                               access.rules(source).locked.contains(category.name) ? "lock.fill" : nil) {
                                        anchorGroup = category.name; selected = category.name
                                    }
                                }
                            }
                            action("رجوع", id: "back", run: back)
                        }
                    }.task(id: selected) {
                        let target = selected == nil ? (anchorGroup ?? categories.first?.name).map { "group." + $0 } ?? "back" : "lock"
                        proxy.scrollTo(target, anchor: .center)
                        do { try await Task.sleep(for: .milliseconds(180)) } catch { return }
                        if pending == nil { focus = target }
                    }
                }
            }.padding(60).frame(maxWidth: 900, maxHeight: .infinity).disabled(pending != nil)
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
    private func action(_ title: String, id: String, symbol: String? = nil, run: @escaping () -> Void) -> some View {
        Button(action: run) {
            HStack { Text(title); Spacer(); if let symbol { Image(systemName: symbol) } }
                .padding(18).frame(maxWidth: .infinity, minHeight: 82)
        }.buttonStyle(TVIPTVGuideButtonStyle(category: true)).focusEffectDisabled()
            .focused($focus, equals: id).id(id).accessibilityIdentifier("tv.iptv.manage." + id)
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
