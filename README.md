# ImageEdit

A SwiftUI-based iOS component that provides a polished, date-bucketed gallery interface for browsing, selecting, and importing images. It integrates with `ProjectService` to manage file persistence, selection state, and trigger downstream edit workflows without tightly coupling the UI to the action handler.

## Features

- **System Files Picker Integration**: Import images from iCloud Drive, On My iPhone, or any installed file provider. Imported files are saved to the app's `Documents/` directory.
- **Date-Bucketed Gallery**: Images are automatically grouped into `Today`, `Yesterday`, `Last 7 Days`, `Last 30 Days`, and `Older` sections based on file modification time.
- **Lazy Thumbnail Decoding**: Thumbnails are generated off the main thread using `CGImageSource` and cached in memory for smooth scrolling.
- **Persistent Selection State**: The previously selected image is restored on view appearance, keeping the CTA pre-armed for quick actions.
- **Haptic Feedback**: Subtle selection and success haptics on tile tap and trigger.
- **Context Menu Deletion**: Long-press any tile to delete it, with automatic cache invalidation and state cleanup.
- **Decoupled Trigger Pattern**: The view never passes payloads to its callback. It writes the selected filename to `ProjectService` and emits a pure `onTrigger` signal.

## Architecture & Key Files

| Path | Purpose |
|------|---------|
| `ImageEdit/ContentView.swift` | Core UI, business logic, date bucketing, tile rendering, and thumbnail loading. Contains the `DateBucket` enum and `Tile` view. |
| `ImageEdit/ImageEditApp.swift` | SwiftUI `@main` entry point. Provided for standalone preview/demo purposes. |
| `Package.swift` | Swift Package Manager manifest. Configures the project as a reusable library targeting iOS 26+ with Swift 6. |
| `Tests/ImageEditTests/DateBucketTests.swift` | Swift Testing suite validating the date bucketing algorithm and edge cases. |

### Module Structure
The SPM target is configured as a library. `ImageEditApp.swift` and `Assets.xcassets` are explicitly excluded from the library target to prevent duplication when the component is integrated into a host app. The `PrivacyInfo.xcprivacy` resource is bundled automatically.

## Installation & Setup

### Swift Package Manager
Add the package to your Xcode project or `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/femimarket/swift-project-service", branch: "main")
]
```

### Requirements
- **iOS**: 26+
- **Swift**: 6
- **Dependencies**: `swift-project-service` (automatically resolved)

Build via command line:
```bash
swift build
swift test
```

## Usage & Integration

Embed `ContentView` in your app's view hierarchy and provide an `onTrigger` closure to handle the downstream edit flow.

```swift
struct HostView: View {
    var body: some View {
        ContentView {
            // Trigger signal — read the arg from ProjectService and run the
            // downstream edit flow.
            runEditAction()
        }
    }

    private func runEditAction() {
        guard let filename = ProjectService.getImageEdit() else { return }
        // Proceed with edit workflow using `filename`
    }
}
```

### The Trigger Contract
`ContentView` follows a strict decoupling pattern:
1. When the user taps **Edit image**, the view writes the selected filename via `ProjectService.setImageEdit(_:)`.
2. It then calls `onTrigger`.
3. `onTrigger` is a pure signal with no payload. The host reads the filename downstream using `ProjectService.getImageEdit()`.

This keeps `ContentView` purely presentational, highly testable, and agnostic to the actual edit implementation.

## Non-Obvious Conventions & Design Decisions

- **Thumbnail Caching Strategy**: Uses `NSCache<NSString, UIImage>` with a hard limit of 500 images. Thumbnails are generated at `max(320, 180 * displayScale)` pixels to balance quality and memory. The cache is automatically invalidated when a file is deleted via `Tile.invalidateThumb(for:)`.
- **Date Bucketing Logic**: `DateBucket.from(_:)` uses `Calendar.current` to compute strict boundaries. The enum conforms to `CaseIterable` and drives the UI section order (newest to oldest).
- **Asynchronous File Import**: Imported files are processed in a `Task.detached(priority: .userInitiated)` to prevent UI blocking. Security-scoped resources are properly opened and closed. The last imported file is auto-selected upon completion.
- **State Restoration**: On `.task { reload() }`, the view checks `ProjectService.getImageEdit()` against the current file list. If the stored filename still exists, it's restored; otherwise, selection is cleared.
- **Swift 6 Strict Concurrency**: The codebase is structured for Swift 6 mode. Async thumbnail loading and detached tasks are used to maintain main-thread responsiveness without explicit `@MainActor` annotations on the view itself.

## Testing

Unit tests are located in `Tests/ImageEditTests/DateBucketTests.swift` and cover:
- Boundary conditions for each date bucket
- Correct ordering of `DateBucket.allCases`
- Edge cases like `.distantPast` and exact day thresholds

Run tests with:
```bash
swift test
```

## License

This project is provided as-is. Ensure compliance with the `swift-project-service` dependency license when integrating.