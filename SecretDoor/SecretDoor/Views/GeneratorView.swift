import SwiftUI

/// Generator options, remembered between uses.
struct GeneratorSettings: Codable, Equatable {
    enum Mode: String, Codable, CaseIterable { case password, passphrase }

    var mode = Mode.password
    var password = PasswordOptions()
    var passphrase = PassphraseOptions()

    static func load() -> GeneratorSettings {
        UserDefaults.standard.data(forKey: "generatorSettings")
            .flatMap { try? JSONDecoder().decode(GeneratorSettings.self, from: $0) } ?? GeneratorSettings()
    }

    func save() {
        if let data = try? JSONEncoder().encode(self) {
            UserDefaults.standard.set(data, forKey: "generatorSettings")
        }
    }

    func generate() -> String {
        switch mode {
        case .password: return PasswordGenerator.password(password)
        case .passphrase: return PasswordGenerator.passphrase(passphrase)
        }
    }
}

struct GeneratorView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    /// When set, a "Use" button returns the generated value (from the item editor).
    var onUse: ((String) -> Void)?

    @State private var settings = GeneratorSettings.load()
    @State private var value = ""
    @State private var didCopy = false

    var body: some View {
        Form {
            Section {
                Text(colorized(value))
                    .font(.title3.monospaced())
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 6)
                HStack {
                    Button {
                        regenerate()
                    } label: {
                        Label("Regenerate", systemImage: "arrow.clockwise")
                    }
                    Spacer()
                    Button {
                        model.copy(value)
                        didCopy = true
                    } label: {
                        Label(didCopy ? "Copied" : "Copy", systemImage: didCopy ? "checkmark" : "doc.on.doc")
                    }
                }
                .buttonStyle(.borderless)
            }

            Section {
                Picker("Type", selection: $settings.mode) {
                    Text("Password").tag(GeneratorSettings.Mode.password)
                    Text("Passphrase").tag(GeneratorSettings.Mode.passphrase)
                }
                .pickerStyle(.segmented)
            }

            switch settings.mode {
            case .password: passwordOptions
            case .passphrase: passphraseOptions
            }
        }
        .navigationTitle("Generator")
        .toolbar {
            if let onUse {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Use") { onUse(value) }
                }
            }
        }
        .onAppear { if value.isEmpty { regenerate() } }
        .onChange(of: settings) { _, new in
            new.save()
            regenerate()
        }
        .sensoryFeedback(.success, trigger: didCopy) { _, new in new }
    }

    private var passwordOptions: some View {
        Section("Options") {
            VStack(alignment: .leading) {
                Text("Length: \(settings.password.length)")
                Slider(
                    value: Binding(get: { Double(settings.password.length) }, set: { settings.password.length = Int($0) }),
                    in: 5...128,
                    step: 1
                )
            }
            Toggle("A–Z", isOn: $settings.password.uppercase)
            Toggle("a–z", isOn: $settings.password.lowercase)
            Toggle("0–9", isOn: $settings.password.numbers)
            Toggle("!@#$%^&*", isOn: $settings.password.special)
            if settings.password.numbers {
                Stepper("Minimum numbers: \(settings.password.minNumbers)", value: $settings.password.minNumbers, in: 1...9)
            }
            if settings.password.special {
                Stepper("Minimum special: \(settings.password.minSpecial)", value: $settings.password.minSpecial, in: 1...9)
            }
            Toggle("Avoid ambiguous characters", isOn: $settings.password.avoidAmbiguous)
        }
    }

    private var passphraseOptions: some View {
        Section("Options") {
            Stepper("Number of words: \(settings.passphrase.words)", value: $settings.passphrase.words, in: 3...20)
            LabeledContent("Word separator") {
                TextField("Separator", text: Binding(
                    get: { settings.passphrase.separator },
                    set: { settings.passphrase.separator = String($0.prefix(1)) }
                ))
                .multilineTextAlignment(.trailing)
                .frame(width: 60)
            }
            Toggle("Capitalize", isOn: $settings.passphrase.capitalize)
            Toggle("Include number", isOn: $settings.passphrase.includeNumber)
        }
    }

    private func regenerate() {
        value = settings.generate()
        didCopy = false
    }

    /// Digits and symbols in colour, as in the Android app.
    private func colorized(_ text: String) -> AttributedString {
        var result = AttributedString()
        for character in text {
            var part = AttributedString(String(character))
            if character.isNumber {
                part.foregroundColor = .blue
            } else if !character.isLetter {
                part.foregroundColor = .red
            }
            result += part
        }
        return result
    }
}
