import AuthenticationServices
import SwiftUI

/// Entry point of the AutoFill extension. iOS calls it when the user picks SecretDoor in a
/// password field (list of logins) or taps a SecretDoor login above the keyboard (one login).
final class CredentialProviderViewController: ASCredentialProviderViewController {
    private let model = AutoFillModel()

    override func viewDidLoad() {
        super.viewDidLoad()
        model.complete = { [weak self] credential in
            self?.extensionContext.completeRequest(withSelectedCredential: credential, completionHandler: nil)
        }
        model.cancel = { [weak self] in
            self?.extensionContext.cancelRequest(withError: Self.error(.userCanceled))
        }
        let host = UIHostingController(rootView: AutoFillRootView().environment(model))
        addChild(host)
        host.view.frame = view.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(host.view)
        host.didMove(toParent: self)
    }

    /// The user chose SecretDoor for a password field: show matching logins.
    override func prepareCredentialList(for serviceIdentifiers: [ASCredentialServiceIdentifier]) {
        model.start(serviceIdentifiers: serviceIdentifiers, recordIdentifier: nil)
    }

    /// iOS asks for a login without showing anything. The vault key is never stored unprotected,
    /// so the vault has to be unlocked first.
    override func provideCredentialWithoutUserInteraction(for credentialRequest: any ASCredentialRequest) {
        extensionContext.cancelRequest(withError: Self.error(.userInteractionRequired))
    }

    /// The user tapped a SecretDoor login above the keyboard: unlock, then fill it.
    override func prepareInterfaceToProvideCredential(for credentialRequest: any ASCredentialRequest) {
        let identity = credentialRequest.credentialIdentity as? ASPasswordCredentialIdentity
        model.start(
            serviceIdentifiers: identity.map { [$0.serviceIdentifier] } ?? [],
            recordIdentifier: identity?.recordIdentifier
        )
    }

    private static func error(_ code: ASExtensionError.Code) -> NSError {
        NSError(domain: ASExtensionErrorDomain, code: code.rawValue)
    }
}
