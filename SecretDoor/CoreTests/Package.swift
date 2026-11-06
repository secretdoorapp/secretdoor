// swift-tools-version:6.0
// Runs the unit tests of the app's core code (SecretDoor/Core) on the Mac:
//   cd CoreTests && swift test
import PackageDescription

let package = Package(
    name: "SecretDoorCore",
    platforms: [.macOS(.v14), .iOS(.v17)],
    targets: [
        .target(name: "SecretDoorCore", swiftSettings: [.swiftLanguageMode(.v5)]),
        .testTarget(name: "SecretDoorCoreTests", dependencies: ["SecretDoorCore"]),
    ]
)
