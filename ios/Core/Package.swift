// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "CinemaCore", platforms: [.iOS(.v17)], products: [.library(name: "CinemaCore", targets: ["CinemaCore"])], dependencies: [.package(url: "https://github.com/scinfu/SwiftSoup.git", exact: "2.7.0")], targets: [
    .target(name: "CinemaCore", dependencies: [.product(name: "SwiftSoup", package: "SwiftSoup")]),
    .testTarget(name: "CinemaCoreTests", dependencies: ["CinemaCore"], resources: [.process("Fixtures")])
])
