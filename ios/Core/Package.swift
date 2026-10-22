// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "CinemaCore", platforms: [.iOS(.v17)], products: [.library(name: "CinemaCore", targets: ["CinemaCore"])], targets: [
    .target(name: "CinemaCore"),
    .testTarget(name: "CinemaCoreTests", dependencies: ["CinemaCore"], resources: [.process("Fixtures")])
])
