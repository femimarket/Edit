// swift-tools-version:6.2

import PackageDescription

let package = Package(
    name: "ImageEdit",
    defaultLocalization: "en",
    platforms: [
        .iOS(.v26)
    ],
    products: [
        .library(
            name: "ImageEdit",
            targets: ["ImageEdit"]
        ),
    ],
    dependencies: [
        .package(url: "https://github.com/femimarket/swift-project-service", branch: "main"),
    ],
    targets: [
        .target(
            name: "ImageEdit",
            dependencies: [
                .product(name: "ProjectService", package: "swift-project-service"),
            ],
            path: "ImageEdit",
            exclude: [
                "ImageEditApp.swift",
                "Assets.xcassets",
            ],
            resources: [
                .copy("PrivacyInfo.xcprivacy"),
            ]
        ),
        .testTarget(
            name: "ImageEditTests",
            dependencies: ["ImageEdit"],
            path: "Tests/ImageEditTests"
        ),
    ],
    swiftLanguageModes: [.v6]
)
