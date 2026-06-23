# ImageEdit

**ImageEdit** is a SwiftUI-based iOS application and library that provides a reusable image selection interface. It allows users to browse, select, import, and delete images stored in the app's documents directory, organizing them by recency.

The project is structured as a Swift Package that includes both the core UI components (`ContentView`, `Tile`, `DateBucket`) and a minimal iOS app target (`ImageEditApp`) for demonstration and testing purposes.

## Features

- **Image Browsing**: Displays images in a responsive grid, bucketed by date (Today, Yesterday, Last 7 Days, Last 30 Days, Older).
- **File Import**: Integrates with the system Files picker to import images from iCloud Drive, On My iPhone, or other file providers. Imported images are saved to the app's local `Documents/` directory.
- **Lazy Thumbnail Loading**: Thumbnails are decoded off the main thread using `CGImageSource` and cached in memory (`NSCache`) for performance.
- **State Management**: Decoupled from downstream logic. Selection state is managed via `ProjectService`, allowing the view to act as a pure input component.
- **Haptic Feedback**: Provides sensory feedback on selection and action triggers.
- **Long-Press Actions**: Supports long-press context menus to delete individual images.

## Architecture

The project is built using **SwiftUI** and relies on the `ProjectService` library for file system operations and state management.

### Key Components

- **`ContentView`** (`ImageEdit/ContentView.swift`): The primary view controller. It manages the grid layout, handles file imports, and exposes an `onTrigger` callback.
- **`Tile`** (`ImageEdit/ContentView.swift`): A private view representing a single image. Handles thumbnail loading, caching, and selection state.
- **`DateBucket`** (`ImageEdit/ContentView.swift`): An enum that categorizes images based on their modification date relative to the current time.
- **`ProjectService`** (External Dependency): A service layer (from `swift-project-service`) that handles:
    - `getAllGenerations()`: Retrieves all files in the documents directory.
    - `saveFile(_:named:)`: Saves imported data to disk.
    - `setImageEdit(_:)` / `getImageEdit()`: Manages the currently selected image filename.

### Data Flow

1. **Initialization**: `ContentView` loads all images from `ProjectService` and groups them by date.
2. **Selection**: When a user taps a tile, `selectedFilename` is updated locally.
3. **Trigger**: When the "Edit image" button is tapped:
    - The selected filename is persisted via `ProjectService.setImageEdit(_:)`.
    - The `onTrigger` closure is called.
    - Downstream logic (not part of this package) should read the filename via `ProjectService.getImageEdit()` to proceed with the edit action.

## Installation

### Swift Package Manager

Add the package to your `Package.swift` or Xcode project:

```swift
dependencies: [
    .package(url: "https://github.com/femimarket/swift-project-service", branch: "main")
]
```

Then, include the `ImageEdit` target in your application's target dependencies.

### Requirements

- **iOS 26.0+** (as defined in `Package.swift`)
- **Swift 6.0+**

## Usage

### Basic Integration

To use the image selection view in your own app, instantiate `ContentView` and provide an `onTrigger` closure.

```swift
import SwiftUI
import ImageEdit

struct MyEditScreen: View {
    var body: some View {
        ContentView {
            // This block is called after the user selects an image and taps "Edit image".
            // The selected filename is now available via ProjectService.getImageEdit().
            runEditAction()
        }
    }

    func runEditAction() {
        if let filename = ProjectService.getImageEdit() {
            print("Editing image: \(filename)")
            // Proceed with image processing...
        }
    }
}
```

### Importing Images

The view automatically handles file imports when the user taps the "+" button. Images are:
1. Selected via the system `UIActivityViewController` or `PHPickerViewController` (via `fileImporter`).
2. Copied to the app's `Documents/` directory with a unique name (`img-<UUID>.<ext>`).
3. Added to the grid view.

### Customization

The `ContentView` is designed to be self-contained. While you cannot easily modify its internal styling without forking, you can control its behavior via the `onTrigger` callback.

## Project Structure

```
.
├── ImageEdit/
│   ├── ContentView.swift       # Main UI logic, grid, tile, and date bucketing
│   ├── ImageEditApp.swift      # Minimal iOS app entry point for testing
│   └── PrivacyInfo.xcprivacy   # Privacy manifest declaring file timestamp access
├── Tests/
│   └── ImageEditTests/
│       └── DateBucketTests.swift # Unit tests for date bucketing logic
└── Package.swift               # Swift Package definition
```

## Testing

The project includes unit tests for the `DateBucket` logic to ensure deterministic date categorization.

### Running Tests

```bash
swift test
```

### Test Coverage

- **`DateBucketTests`**: Verifies that dates are correctly categorized into `today`, `yesterday`, `lastWeek`, `lastMonth`, and `older` buckets. Uses a fixed reference date (`2026-06-22 12:00:00 UTC`) to ensure test stability.

## Dependencies

- **ProjectService**: `https://github.com/femimarket/swift-project-service` (branch: `main`)
  - Provides file system abstraction and state management for the selected image.

## License

This project is licensed under the terms specified in the `LICENSE` file included in the repository.

## Privacy

The app declares access to file timestamps (`NSPrivacyAccessedAPICategoryFileTimestamp`) for the purpose of categorizing images by date in the UI. No user data is collected or transmitted. See `ImageEdit/PrivacyInfo.xcprivacy` for details.