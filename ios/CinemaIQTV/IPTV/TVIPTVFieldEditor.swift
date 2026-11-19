import SwiftUI
import UIKit

/// Only the editor owns a text responder. All persistent form rows participate in tvOS button focus.
struct TVIPTVFieldEditor: UIViewRepresentable {
    let title: String
    let value: String
    let contentType: UITextContentType?
    let returnKey: UIReturnKeyType
    let secure: Bool
    let finish: (String, Bool) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(value: value, finish: finish) }

    func makeUIView(context: Context) -> Input {
        let input = Input()
        input.placeholder = title
        input.text = value
        input.isSecureTextEntry = secure
        input.textContentType = contentType
        input.autocorrectionType = .no
        input.autocapitalizationType = .none
        input.returnKeyType = returnKey
        input.textAlignment = .natural
        input.delegate = context.coordinator
        input.addTarget(context.coordinator, action: #selector(Coordinator.changed(_:)), for: .editingChanged)
        input.accessibilityIdentifier = "tv.iptv.form.editor"
        return input
    }

    func updateUIView(_ input: Input, context: Context) {}

    final class Input: UITextField {
        private var requested = false
        override func didMoveToWindow() {
            super.didMoveToWindow()
            guard window != nil, !requested else { return }
            requested = true
            DispatchQueue.main.async { [weak self] in
                guard let self, self.window != nil else { return }
                self.becomeFirstResponder()
            }
        }
    }

    final class Coordinator: NSObject, UITextFieldDelegate {
        let finish: (String, Bool) -> Void
        private var draft: String
        private var submitted = false
        private var finished = false
        init(value: String, finish: @escaping (String, Bool) -> Void) {
            self.draft = value; self.finish = finish
        }

        @objc func changed(_ field: UITextField) {
            if !finished, field.isEditing { draft = field.text ?? "" }
        }

        func textField(_ textField: UITextField, shouldChangeCharactersIn range: NSRange, replacementString string: String) -> Bool {
            let previous = textField.text ?? ""
            if let edit = Range(range, in: previous) { draft = previous.replacingCharacters(in: edit, with: string) }
            return true
        }

        func textFieldShouldReturn(_ textField: UITextField) -> Bool {
            draft = textField.text ?? draft
            submitted = true
            textField.resignFirstResponder()
            complete(textField)
            return false
        }

        func textFieldDidEndEditing(_ textField: UITextField) { complete(textField) }

        private func complete(_ textField: UITextField) {
            guard !finished else { return }
            finished = true
            // The tvOS Menu action restores the launch value in UITextField before ending editing.
            let value = draft, next = submitted
            DispatchQueue.main.async { [finish] in finish(value, next) }
        }
    }
}
