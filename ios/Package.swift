// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "PianoCore", platforms: [.iOS(.v16)],
    products: [.library(name: "PianoCore", targets: ["PianoCore"])],
    targets: [.target(name: "PianoCore"), .testTarget(name: "PianoCoreTests", dependencies: ["PianoCore"])])
