//
//  SecretDoorApp.swift
//  SecretDoor
//
//  An offline password manager. The vault lives only on this iPhone, encrypted with the master
//  password, and uses the same formats as SecretDoor for Android, so backups move between them.
//

import SwiftUI

@main
struct SecretDoorApp: App {
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // Decrypted attachments are only ever written to temporary folders; remove leftovers.
        let temporary = FileManager.default.temporaryDirectory
        for name in (try? FileManager.default.contentsOfDirectory(atPath: temporary.path)) ?? [] where name.hasPrefix("attachments-") {
            try? FileManager.default.removeItem(at: temporary.appendingPathComponent(name))
        }
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
        }
        .onChange(of: scenePhase) { _, phase in
            model.scenePhaseChanged(to: phase)
        }
    }
}
