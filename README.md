# ImageEdit

**ImageEdit** is a lightweight, SwiftUI-based iOS application designed for selecting, managing, and triggering image editing workflows. It serves as a gallery interface that aggregates images by recency and provides a clean mechanism to pass a selected image to an external editing service.

The project is structured as a Swift Package, making it reusable as a library or embeddable within larger host applications. It relies on `ProjectService` for persistent storage and state management.

## Features

- **Smart Gallery View**: Displays images in a responsive grid, grouped by date (Today, Yesterday, Last 7 Days, Last 30 Days, Older).
- **File Import**: Seamlessly import images from the Files app, iCloud Drive, or other providers using the native `fileImporter` API.
- **Thumbnail Caching**: Efficiently loads and caches high-resolution thumbnails using `NSCache` and `CGImageSource` to maintain performance with large libraries.
- **Selection State**: Maintains selection state across reloads and clears it if the underlying file is deleted.
- **Host Integration**: Designed to be triggered by a host application via a closure (`onTrigger`), allowing the host to handle the actual image processing/editing logic.

## Architecture

The project follows a clean separation between UI and service logic:

1.  **`ContentView.swift`**: The primary UI component. It handles:
    -   Fetching image URLs from `ProjectService`.
    -   Grouping images by modification date.
    -   Managing user interactions (selecting, deleting, importing).
    -   Triggering the edit action via the `onTrigger` callback.
2.  **`ImageEditApp.swift`**: The entry point for the standalone app, which simply instantiates `ContentView`.
3.  **`ProjectService`**: An external dependency (`swift-project-service`) that handles:
    -   Saving image data to the file system.
    -   Retrieving all generated files.
    -   Managing the "currently selected image for editing" state.

### Key Files

-   `ImageEdit/ContentView.swift`: Contains all UI logic, date bucketing enums, and the `Tile` view for individual image display.
-   `ImageEdit/ImageEditApp.swift`: The `@main` entry point.
-   `Package.swift`: Defines the package structure, dependencies, and platform constraints.

## Installation & Setup

### Prerequisites

-   **iOS 26.0+**: The project targets iOS 26.0 and above.
-   **Swift 6**: The package uses Swift 6 language mode.

### Adding as a Dependency

To use `ImageEdit` in your own Swift Package Manager project, add it to your `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/your-username/ImageEdit.git", from: "1.0.0"),
]
```

Then, add the target dependency to your app's target:

```swift
.target(
    name: "YourApp",
    dependencies: [
        .product(name: "ImageEdit", package: "ImageEdit"),
    ]
)
```

### Standalone Usage

If you are building the app directly:

1.  Clone the repository.
2.  Open the project in Xcode (ensure you have the latest toolchain supporting Swift 6 and iOS 26).
3.  Build and run on a simulator or device.

## Usage

### As a Library (Host Integration)

The primary use case for `ImageEdit` is as a sub-view within a larger application. You can instantiate `ContentView` and provide a closure to handle the edit trigger.

```swift
import SwiftUI
import ImageEdit

struct HostView: View {
    var body: some View {
        ContentView { filename in
            // This closure is called when the user taps "Edit image"
            print("User selected image: \(filename)")
            performEdit(with: filename)
        }
    }
    
    func performEdit(with filename: String) {
        // Load the image from ProjectService and process it
    }
}
```

### Importing Images

1.  Tap the **"+"** button in the header or the **"Add image"** button in the empty state.
2.  Select one or more images from the system file picker.
3.  The images are copied to the app's storage and appear in the gallery, grouped by date.
4.  The most recently imported image is automatically selected.

### Selecting and Editing

1.  Tap an image tile to select it. A checkmark overlay indicates selection.
2.  Tap the **"Edit image"** button in the bottom bar.
3.  This triggers the `onTrigger` closure with the filename of the selected image.

### Deleting Images

1.  Long-press (or right-click) on an image tile.
2.  Select **"Delete"** from the context menu.
3.  The image is removed from storage and the gallery updates immediately.

## Technical Details

### Date Bucketing

Images are categorized into five buckets based on their content modification date:
-   **Today**
-   **Yesterday**
-   **Last 7 Days**
-   **Last 30 Days**
-   **Older**

This logic is implemented in the `DateBucket` enum within `ContentView.swift`.

### Thumbnail Generation

Thumbnails are generated asynchronously in the background to prevent UI jank. The `Tile` view uses:
-   `CGImageSourceCreateThumbnailAtIndex` for efficient thumbnail extraction.
-   `NSCache` to store up to 500 thumbnails, automatically evicting older ones when memory pressure occurs.
-   Cache invalidation when an image is deleted.

### Security

The app handles security-scoped resources correctly when importing files from external providers (e.g., iCloud Drive). It calls `startAccessingSecurityScopedResource()` and ensures `stopAccessingSecurityScopedResource()` is called in a `defer` block.

## License

This project is licensed under the terms specified in the `LICENSE` file included in the repository.