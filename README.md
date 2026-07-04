# ImageEdit

A cross-platform image selection and editing entry point.

`ImageEdit` provides a self-contained UI screen for browsing, selecting, and importing images from local storage or file providers. It is designed to be embedded as a library component in iOS (SwiftUI), Android (Jetpack Compose), and Web (Compose Multiplatform) applications.

The component manages the "argument handoff" contract: it persists the user's selection and emits a pure signal to the host app, which is responsible for reading the selected filename and executing the actual image editing logic.

## Features

*   **Cross-Platform UI**: Identical visual design and interaction patterns across iOS, Android, and Web.
*   **Date-Bucketed Grid**: Images are automatically grouped by recency (Today, Yesterday, Last 7 Days, Last 30 Days, Older) based on file modification time.
*   **Lazy Thumbnail Loading**: Thumbnails are decoded off the main thread and cached to ensure smooth scrolling.
*   **File Import**:
    *   **iOS**: Uses the system `UIActivityViewController`/Files picker.
    *   **Android**: Uses the system Document Picker (SAF).
    *   **Web**: Uses the browser `showOpenFilePicker` API, saving files to the Origin Private File System (OPFS).
*   **Persistence**: Restores the previously selected image on app launch.
*   **Haptic Feedback**: Provides tactile feedback on selection and trigger (iOS/Android).
*   **Theming**: Automatically adapts to system light/dark mode using semantic iOS color values.

## Architecture

The project consists of three main parts:

1.  **Swift Library (`ImageEdit/`)**: The source of truth for the UI logic and design.
2.  **Kotlin Multiplatform Library (`Kmp/imageedit/`)**: A port of the SwiftUI view to Jetpack Compose for Android and Web.
3.  **Demo Apps**:
    *   **iOS**: `ImageEditApp.swift` (SwiftUI App entry point).
    *   **Android**: `Kmp/demo/` (Kotlin/Compose demo app).

### The Contract

The `ImageEdit` view is decoupled from the actual editing logic. It communicates with the host via `ProjectService`:

1.  **Selection**: When the user selects an image, the view writes the filename to `ProjectService.setImageEdit(filename)`.
2.  **Trigger**: The view calls the `onTrigger` callback. This callback carries **no payload**.
3.  **Execution**: The host app, inside `onTrigger`, reads the filename via `ProjectService.getImageEdit()` and proceeds with the edit flow.

```swift
// Example Host Integration
ContentView {
    // 1. View has already called ProjectService.setImageEdit(selectedName)
    let name = ProjectService.getImageEdit()
    // 2. Run your edit logic here
    runEditAction(with: name)
}
```

## Installation

### iOS (Swift Package Manager)

Add the dependency to your `Package.swift` or Xcode project:

```swift
dependencies: [
    .package(url: "https://github.com/femimarket/swift-project-service", branch: "main"),
    .package(url: "https://github.com/your-org/ImageEdit", branch: "main"), // Replace with actual repo
]
```

Include `ImageEdit` and `ProjectService` in your target dependencies.

### Android & Web (Kotlin Multiplatform)

The library is published as a Kotlin Multiplatform module. Add the dependency to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("market.femi:imageedit:<version>")
    // Ensure ProjectService API is also available
    implementation("market.femi:api:<version>")
}
```

## Usage

### iOS

Import the module and instantiate `ContentView` in your SwiftUI view hierarchy.

```swift
import SwiftUI
import ImageEdit

struct HostView: View {
    var body: some View {
        NavigationStack {
            ContentView {
                // Trigger signal
                handleEdit()
            }
            .navigationTitle("Edit Image")
        }
    }

    private func handleEdit() {
        if let filename = ProjectService.getImageEdit() {
            // Perform edit
        }
    }
}
```

### Android

Import the module and call the `ImageEdit()` composable.

```kotlin
import market.femi.imageedit.ImageEdit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ImageEditTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ImageEdit(onTrigger = {
                        // Trigger signal
                        handleEdit()
                    })
                }
            }
        }
    }

    private fun handleEdit() {
        val filename = ProjectService.getImageEdit()
        // Perform edit
    }
}
```

### Web

The usage is identical to Android, as it shares the same Compose Multiplatform codebase.

## Key Files & Structure

### iOS (`ImageEdit/`)

*   **`ContentView.swift`**: The main SwiftUI view. Contains the grid logic, date bucketing (`DateBucket`), and tile rendering (`Tile`).
*   **`ImageEditApp.swift`**: The `@main` entry point for the standalone iOS demo.

### Kotlin Multiplatform (`Kmp/`)

*   **`Kmp/imageedit/src/androidMain/kotlin/market/femi/imageedit/ImageEdit.kt`**: The Android-specific Compose implementation. Handles Android-specific APIs like SAF, ExifInterface, and Haptics.
*   **`Kmp/imageedit/src/webMain/kotlin/market/femi/imageedit/ImageEdit.kt`**: The Web-specific Compose implementation. Handles OPFS, browser file pickers, and Skia image decoding.
*   **`Kmp/demo/`**: A complete Android application demonstrating how to embed the library.

### Configuration

*   **`Package.swift`**: Swift Package manifest. Defines dependencies on `ProjectService` and excludes demo-specific files from the library target.
*   **`Tests/ImageEditTests/DateBucketTests.swift`**: Unit tests for the date bucketing logic, ensuring consistent grouping across platforms.

## Technical Details

### Date Bucketing

Files are grouped into five categories based on their modification time relative to the current date:
1.  **Today**
2.  **Yesterday**
3.  **Last 7 Days**
4.  **Last 30 Days**
5.  **Older**

The logic accounts for calendar days (not just 24-hour periods) to ensure stability across DST changes.

### Thumbnail Caching

*   **iOS**: Uses `NSCache` with a count limit of 500. Thumbnails are generated via `CGImageSourceCreateThumbnailAtIndex` with a max pixel size of `max(320, 180 * scale)`.
*   **Android**: Uses `LruCache` with a count limit of 500. Thumbnails are decoded via `BitmapFactory` with `inSampleSize` optimization. EXIF orientation is applied automatically.
*   **Web**: Uses an in-memory `LinkedHashMap` (LRU) with a count limit of 500. Images are decoded via Skia (`decodeImageBitmap`).

### Theming

The UI uses semantic colors that mirror iOS system colors (`systemBackground`, `secondaryLabel`, `accent`, etc.). These are hardcoded in the Kotlin code to ensure visual parity across platforms, adapting automatically to the system's light/dark mode.

### File Import

*   **iOS**: Files are copied to the app's `Documents/` directory via `ProjectService.saveFile(_:named:)`.
*   **Android**: Files are copied to the app's documents root via `ProjectService.saveFile(_:named:)`.
*   **Web**: Files are read from the browser picker, saved to OPFS via `ProjectService.saveFile(_:named:)`, and the temporary staging copy is deleted.

## Testing

Run the Swift tests via Xcode or the command line:

```bash
swift test
```

This primarily validates the `DateBucket` logic, which is shared conceptually across all platforms.