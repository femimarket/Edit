# ImageEdit

**ImageEdit** is a SwiftUI-based iOS application designed to browse, select, and trigger image editing workflows. It acts as a file manager and selection interface, integrating with the `ProjectService` library to persist user selections and manage file imports.

## Overview

The app provides a grid-based gallery of images stored locally. Images are grouped by recency (Today, Yesterday, Last 7 Days, etc.). Users can import images from the Files app, select a specific image for editing, and trigger an external edit action via a callback.

### Key Features
- **Image Browsing**: Adaptive grid layout displaying image thumbnails grouped by modification date.
- **File Import**: Securely import images from iCloud Drive, Files, or other providers using the system file picker.
- **Selection Management**: Select a single image to prepare it for editing; the selection persists across reloads if the file remains.
- **Thumbnail Caching**: Efficiently loads and caches high-resolution thumbnails using `NSCache` and `CGImageSource` for performance.
- **Project Service Integration**: Uses `ProjectService` to store the currently selected image filename and save imported files to a persistent directory.

## Architecture

The project is structured as a Swift Package containing the core UI logic, with a separate app target for the executable entry point.

### Directory Structure
- `ImageEdit/`: Contains the core library targets.
  - `ContentView.swift`: The main view controller handling the grid, selection logic, file importing, and date bucketing.
  - `Tile.swift` (embedded in `ContentView.swift`): A private view component for displaying individual image thumbnails with selection overlays and context menus.
  - `DateBucket.swift` (embedded in `ContentView.swift`): Logic for categorizing images by date.
- `ImageEditApp.swift`: The `@main` entry point for the iOS application.
- `Package.swift`: Defines the Swift Package structure, dependencies, and targets.

### Key Components

#### `ContentView`
The central view of the application. It manages the state of the image grid, handles user interactions (tap, delete, import), and coordinates with `ProjectService`.
- **State Management**: Uses `@State` properties for `groups`, `selectedFilename`, and UI flags (`isPickingFile`, `isImporting`).
- **Data Loading**: The `reload()` method fetches all generated files via `ProjectService.getAllGenerations()`, filters for image extensions, sorts by modification date, and buckets them into `DateGroup` objects.
- **File Import**: The `handleFileImport()` method processes security-scoped resources, saves them to the project service directory, and updates the UI.

#### `Tile`
A private `View` struct responsible for rendering individual image tiles.
- **Thumbnails**: Asynchronously loads thumbnails using `CGImageSource` with specific options for scaling and caching.
- **Caching**: Uses a static `NSCache<NSString, UIImage>` to store up to 500 thumbnails, invalidating entries when files are deleted.
- **Interactions**: Supports selection highlighting, context menus for deletion, and accessibility traits.

#### `ProjectService`
An external dependency (`swift-project-service`) that handles:
- `setImageEdit(_:)`: Stores the filename of the currently selected image.
- `getImageEdit()`: Retrieves the stored filename.
- `clearImageEdit()`: Clears the stored selection.
- `saveFile(_:named:)`: Saves raw data to the persistent storage.
- `getAllGenerations()`: Returns a list of all files in the storage directory.

## Installation & Setup

### Prerequisites
- **iOS 26.0+**: The project targets iOS 26.0 and above.
- **Swift 6.0+**: The project uses Swift 6 language mode.
- **Xcode**: Required for building and running the iOS application.

### Building the Package

To build the Swift Package:

```bash
swift build
```

### Running the Application

To run the iOS application, open the project in Xcode or use the command line if configured with an `.xcodeproj` or `.xcworkspace`.

1. Ensure the `ImageEditApp.swift` file is included in your Xcode project (it is excluded from the library target in `Package.swift` to avoid duplicate symbols).
2. Build and run the `ImageEditApp` target on a simulator or physical device.

## Usage

### Importing Images
1. Tap the **"+"** button in the top-right corner.
2. The system file picker will appear. Select one or more images from Files, iCloud Drive, or other providers.
3. The app will import the images, assign them unique UUID-based filenames, and display them in the grid.
4. The last imported image will be automatically selected.

### Selecting an Image
1. Tap on any image tile to select it.
2. A checkmark overlay will appear on the selected tile.
3. Tap the same tile again to deselect it.

### Triggering Edit
1. Select an image.
2. The **"Edit image"** button will appear in the bottom bar.
3. Tap the button to trigger the edit action.
   - This calls `ProjectService.setImageEdit(selectedFilename)` to persist the selection.
   - It also invokes the `onTrigger` closure (if provided by the host application) with the selected filename.

### Deleting Images
1. Long-press on an image tile to open the context menu.
2. Tap **Delete**.
3. The image will be removed from storage, its thumbnail cache cleared, and the grid updated.
4. If the deleted image was selected, the selection will be cleared.

## Configuration

### Image Extensions
The app filters files by the following extensions to identify images:
- `jpg`, `jpeg`, `png`, `heic`, `heif`, `gif`, `tiff`, `webp`, `bmp`

These are defined in `ContentView.imageExtensions`.

### Date Bucketing
Images are grouped into the following categories based on their modification date:
- **Today**
- **Yesterday**
- **Last 7 Days**
- **Last 30 Days**
- **Older**

This logic is implemented in `DateBucket.from(_:now:)`.

## Dependencies

- **swift-project-service**: A package from `femimarket/swift-project-service` (branch `main`) that provides file storage and selection management services.

## Accessibility

The app includes accessibility features:
- **Headers**: Image titles and section headers are marked with `.accessibilityAddTraits(.isHeader)`.
- **Labels**: Buttons and tiles have descriptive accessibility labels and hints.
- **Feedback**: Sensory feedback (success and selection) is triggered on import and selection actions.
- **Traits**: Tiles are marked as buttons with `isSelected` traits when selected.

## License

This project is part of the ImageEdit repository. Please refer to the root `LICENSE` file for licensing details.