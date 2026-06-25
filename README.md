# ImageEdit

**ImageEdit** is a SwiftUI-based iOS application and library that provides a reusable image selection interface. It allows users to browse, select, import, and delete images stored in the app's document directory, organized by date.

The project is structured as a Swift Package, making the `ContentView` and its supporting logic available as a library for integration into larger host applications, while also serving as a standalone app.

## Features

- **Date-Bucketed Gallery**: Images are automatically grouped into logical sections: *Today*, *Yesterday*, *Last 7 Days*, *Last 30 Days*, and *Older*.
- **System File Import**: Users can import images from iCloud Drive, On My iPhone, or any installed file provider via the standard iOS `Files` picker.
- **Performance Optimized**:
  - Thumbnails are decoded off the main thread using `CGImageSource`.
  - Thumbnails are cached in memory (`NSCache`) to ensure smooth scrolling.
  - Lazy loading ensures only visible tiles decode images.
- **Selection State Management**:
  - Maintains selection state across view appearances.
  - Decouples the selection UI from the downstream edit action using a callback signal (`onTrigger`) and a shared service (`ProjectService`).
- **Haptic Feedback**: Provides subtle haptic feedback on selection and action triggers.
- **Delete Support**: Long-press any image tile to delete it from storage.

## Architecture

The project follows a clean separation between UI and state management:

1.  **`ContentView` (`ImageEdit/ContentView.swift`)**: The primary UI component. It handles user interactions (picking, selecting, deleting) and updates the shared state.
2.  **`ProjectService`**: A dependency provided by the `swift-project-service` package. It acts as the single source of truth for file operations (saving, retrieving, clearing) and the currently selected image filename.
3.  **`DateBucket` & `DateGroup`**: Helper types that handle the logic for categorizing files based on their modification time.
4.  **`Tile`**: A private view component responsible for rendering individual image thumbnails with selection overlays and context menus.

### Data Flow

1.  **Import**: User picks a file → `ContentView` saves it to `Documents/` via `ProjectService` → Gallery reloads.
2.  **Select**: User taps a tile → `ContentView` updates local `@State` → Haptic feedback triggers.
3.  **Edit Trigger**: User taps "Edit image" → `ContentView` writes filename to `ProjectService` → Calls `onTrigger` callback.
4.  **Downstream**: The host app (or the app itself) reads the filename from `ProjectService` to perform the actual edit.

## Installation

### Prerequisites

-   **iOS 26.0+** (Note: The `Package.swift` specifies `.iOS(.v26)`. Ensure your development environment supports this target version).
-   **Swift 6.0+** (Language mode set to v6).

### Adding as a Dependency

To use `ImageEdit` in your own Swift Package Manager project, add it to your `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/your-username/ImageEdit.git", from: "1.0.0")
]
```

Then, add `ImageEdit` to your target's dependencies:

```swift
targets: [
    .target(
        name: "YourApp",
        dependencies: [
            .product(name: "ImageEdit", package: "ImageEdit")
        ]
    )
]
```

## Usage

### As a Library

Import the module and instantiate `ContentView`. You must provide an `onTrigger` closure to handle the action when the user selects an image and taps "Edit image".

```swift
import SwiftUI
import ImageEdit
import ProjectService // Required to read the selected file

struct HostView: View {
    var body: some View {
        ContentView {
            // This block is called after the user selects an image and taps "Edit image"
            handleImageEdit()
        }
    }

    private func handleImageEdit() {
        // Retrieve the filename set by ContentView
        if let filename = ProjectService.getImageEdit() {
            let fileURL = ProjectService.documentDirectory.appendingPathComponent(filename)
            // Perform your edit logic here
            print("Editing file: \(filename)")
        }
    }
}
```

### Standalone App

To run the project as a standalone application:

1.  Open the project in Xcode.
2.  Select the `ImageEdit` scheme.
3.  Choose a simulator or physical device.
4.  Run (`Cmd + R`).

## Key Files

| File | Description |
| :--- | :--- |
| `ImageEdit/ContentView.swift` | Main UI view. Handles grid layout, file importing, selection logic, and date bucketing. |
| `ImageEdit/ImageEditApp.swift` | App entry point (`@main`). Initializes the `ContentView`. |
| `Package.swift` | Swift Package manifest. Defines dependencies (`swift-project-service`) and targets. |
| `Tests/ImageEditTests/DateBucketTests.swift` | Unit tests for the `DateBucket` categorization logic. |

## Configuration

### Supported Image Formats

The app filters files to display only images with the following extensions:
`jpg`, `jpeg`, `png`, `heic`, `heif`, `gif`, `tiff`, `webp`, `bmp`.

### Privacy Manifest

The package includes a `PrivacyInfo.xcprivacy` resource file. Ensure this is included in your final build if you integrate the package as a library, as it is marked as a resource in `Package.swift`.

## Testing

Run the unit tests using Swift Package Manager:

```bash
swift test
```

Or via Xcode:
1.  Open the package.
2.  Select the `ImageEditTests` target.
3.  Run tests (`Cmd + U`).

## License

This project is available for use under the terms defined in the repository's license file (if applicable, otherwise assume standard open-source terms or proprietary use as defined by the author).