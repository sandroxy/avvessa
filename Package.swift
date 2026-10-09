// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "Avvessa",
    platforms: [.iOS(.v13)],
    products: [.library(name: "Avvessa", targets: ["Avvessa"])],
    dependencies: [
        .package(url: "https://github.com/jpush/jpush-sdk.git", exact: "6.2.7"),
        .package(url: "https://github.com/jpush/jcore-sdk.git", exact: "5.5.3")
    ],
    targets: [
        .target(
            name: "Avvessa",
            dependencies: [
                .product(name: "JPush", package: "jpush-sdk"),
                .product(name: "JCore", package: "jcore-sdk")
            ],
            path: "native/ios/Sources/Avvessa",
            resources: [.process("Resources/PrivacyInfo.xcprivacy")],
            publicHeadersPath: "include",
            linkerSettings: [
                .linkedFramework("UIKit"), .linkedFramework("UserNotifications"),
                .linkedFramework("Network"), .linkedLibrary("z"), .linkedLibrary("resolv")
            ]
        )
    ]
)
