import SwiftUI
import UniformTypeIdentifiers

extension UTType {
    /// `.secretdoor` backup files (declared in Info.plist).
    static let secretDoorBackup = UTType(exportedAs: "app.secretdoor.backup", conformingTo: .data)
}

/// A backup file for `fileExporter`.
struct BackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.secretDoorBackup, .data] }
    static var writableContentTypes: [UTType] { [.secretDoorBackup] }

    let data: Data

    init(data: Data) { self.data = data }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}

/// Reads a file picked with `fileImporter` (a security-scoped URL).
func readPickedFile(_ url: URL) throws -> Data {
    let accessing = url.startAccessingSecurityScopedResource()
    defer { if accessing { url.stopAccessingSecurityScopedResource() } }
    var coordinationError: NSError?
    var result: Result<Data, Error> = .failure(CocoaError(.fileReadUnknown))
    NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &coordinationError) { readURL in
        result = Result { try Data(contentsOf: readURL) }
    }
    if let coordinationError { throw coordinationError }
    return try result.get()
}

/// A password field with a show/hide button.
struct PasswordField: View {
    let title: LocalizedStringKey
    @Binding var text: String
    var isNewPassword = false
    @State private var isRevealed = false
    @FocusState private var isFocused: Bool

    init(_ title: LocalizedStringKey, text: Binding<String>, isNewPassword: Bool = false) {
        self.title = title
        _text = text
        self.isNewPassword = isNewPassword
    }

    var body: some View {
        HStack {
            Group {
                if isRevealed {
                    TextField(title, text: $text)
                        .font(.body.monospaced())
                } else {
                    SecureField(title, text: $text)
                }
            }
            .textContentType(isNewPassword ? .newPassword : .password)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .focused($isFocused)
            Button {
                isRevealed.toggle()
                isFocused = true
            } label: {
                Image(systemName: isRevealed ? "eye.slash" : "eye")
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(isRevealed ? Text("Hide") : Text("Show"))
        }
    }
}

/// A read-only field with copy (and optionally reveal) buttons.
struct FieldRow: View {
    @Environment(AppModel.self) private var model
    let label: String
    let value: String
    var isSecret = false
    var isMonospaced = false
    var isMultiline = false
    @State private var isRevealed = false
    @State private var didCopy = false

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(label)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Group {
                    if isSecret && !isRevealed {
                        Text(String(repeating: "•", count: min(max(value.count, 8), 16)))
                    } else {
                        Text(value)
                            .font(isMonospaced || isSecret ? .body.monospaced() : .body)
                            .textSelection(.enabled)
                            .lineLimit(isMultiline ? nil : 3)
                    }
                }
            }
            Spacer(minLength: 0)
            if isSecret {
                Button {
                    isRevealed.toggle()
                } label: {
                    Image(systemName: isRevealed ? "eye.slash" : "eye")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(isRevealed ? Text("Hide") : Text("Show"))
            }
            Button {
                model.copy(value)
                didCopy = true
                Task {
                    try? await Task.sleep(for: .seconds(1.5))
                    didCopy = false
                }
            } label: {
                Image(systemName: didCopy ? "checkmark" : "doc.on.doc")
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(Text("Copy \(label)"))
        }
        .sensoryFeedback(.success, trigger: didCopy) { _, new in new }
    }
}

/// Coloured strength bar for new master passwords.
struct StrengthMeter: View {
    let password: String

    private var score: Int { PasswordGenerator.strength(of: password) }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            GeometryReader { geometry in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color(.systemFill))
                    Capsule()
                        .fill(color)
                        .frame(width: geometry.size.width * CGFloat(score + 1) / 5)
                }
            }
            .frame(height: 6)
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .opacity(password.isEmpty ? 0 : 1)
        .animation(.default, value: score)
    }

    private var color: Color {
        switch score {
        case 0: return .red
        case 1: return .orange
        case 2: return .yellow
        case 3: return .green
        default: return .blue
        }
    }

    private var label: LocalizedStringKey {
        switch score {
        case 0: return "Weak"
        case 1: return "Weak"
        case 2: return "Fair"
        case 3: return "Good"
        default: return "Strong"
        }
    }
}

/// Shows an error message in an alert.
struct ErrorAlert: ViewModifier {
    @Binding var error: String?

    func body(content: Content) -> some View {
        content.alert(
            "Something went wrong",
            isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } }),
            actions: { Button("OK", role: .cancel) {} },
            message: { Text(error ?? "") }
        )
    }
}

extension View {
    func errorAlert(_ error: Binding<String?>) -> some View {
        modifier(ErrorAlert(error: error))
    }
}

extension Error {
    var displayMessage: String {
        (self as? LocalizedError)?.errorDescription ?? localizedDescription
    }
}

extension CipherType {
    /// Artwork from the Android app's icon sheet (`design/icon-source`), where there is some.
    var artName: String? {
        switch self {
        case .login: return "ArtLogin"
        case .card: return "ArtCard"
        case .identity: return "ArtIdentity"
        case .secureNote: return "ArtNote"
        case .sshKey: return "ArtSshKey"
        case .bankAccount, .driversLicense, .passport: return nil
        }
    }
}

/// The icon of an item type: SecretDoor artwork, or a system symbol for types without any.
struct TypeIcon: View {
    let type: CipherType
    var size: CGFloat = 32

    var body: some View {
        Group {
            if let artName = type.artName {
                Image(artName)
                    .resizable()
                    .interpolation(.high)
                    .scaledToFit()
            } else {
                Image(systemName: type.systemImage)
                    .font(.system(size: size * 0.55))
                    .foregroundStyle(.white)
                    .frame(width: size, height: size)
                    .background(
                        LinearGradient(colors: [Color(white: 0.32), Color(white: 0.16)], startPoint: .top, endPoint: .bottom),
                        in: RoundedRectangle(cornerRadius: size * 0.2)
                    )
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}
