//
//  ContentView.swift
//  ImageEdit
//
//  Created by u on 22/06/2026.
//

import SwiftUI
import UniformTypeIdentifiers
import ProjectService

struct ContentView: View {
    /// Hook the host wires up to actually perform the edit. The screen's
    /// only job is to set the arg via `ProjectService.setImageEdit` and
    /// then call this — the work itself happens elsewhere.
    var onTrigger: ((String) -> Void)? = nil

    @State private var groups: [DateGroup] = []
    @State private var selectedFilename: String? = nil
    @State private var isPickingFile: Bool = false
    @State private var isImporting: Bool = false
    @State private var triggerPulse: Int = 0
    @State private var selectionPulse: Int = 0

    private let columns = [
        GridItem(.adaptive(minimum: 108, maximum: 180), spacing: 10)
    ]

    var body: some View {
        ScrollView(showsIndicators: false) {
            VStack(spacing: 0) {
                header
                    .padding(.horizontal, 24)
                    .padding(.top, 12)
                    .padding(.bottom, 24)

                if groups.isEmpty {
                    emptyState
                        .padding(.top, 80)
                } else {
                    LazyVGrid(columns: columns, spacing: 10) {
                        ForEach(groups) { group in
                            Section {
                                ForEach(group.urls, id: \.self) { url in
                                    Tile(
                                        url: url,
                                        isSelected: selectedFilename == url.lastPathComponent,
                                        onTap: { select(url.lastPathComponent) },
                                        onDelete: { delete(url) }
                                    )
                                }
                            } header: {
                                sectionHeader(group)
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                }
            }
        }
        .background(Color(.systemBackground))
        .safeAreaInset(edge: .bottom, spacing: 0) {
            bottomBar
        }
        .task { reload() }
        .fileImporter(
            isPresented: $isPickingFile,
            allowedContentTypes: [.image],
            allowsMultipleSelection: true
        ) { result in
            handleFileImport(result)
        }
        .sensoryFeedback(.success, trigger: triggerPulse)
        .sensoryFeedback(.selection, trigger: selectionPulse)
    }

    // MARK: - Header

    private var header: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 6) {
                Text("Image Edit")
                    .font(.system(.largeTitle, weight: .semibold))
                    .foregroundStyle(.primary)
                    .tracking(-0.4)
                    .accessibilityAddTraits(.isHeader)

                Text("Select an image to edit")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            Button {
                isPickingFile = true
            } label: {
                Group {
                    if isImporting {
                        ProgressView()
                            .controlSize(.small)
                    } else {
                        Image(systemName: "plus")
                            .font(.body.weight(.semibold))
                    }
                }
                .frame(width: 44, height: 44)
            }
            .buttonStyle(.glass)
            .disabled(isImporting)
            .accessibilityLabel(isImporting ? "Importing images" : "Add image from Files")
        }
    }

    // MARK: - Section header

    private func sectionHeader(_ group: DateGroup) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text(group.id.title)
                .font(.title2.weight(.semibold))
                .foregroundStyle(.primary)
                .accessibilityAddTraits(.isHeader)
            Text("\(group.urls.count)")
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.secondary)
                .monospacedDigit()
                .accessibilityLabel("\(group.urls.count) images")
            Spacer()
        }
        .padding(.horizontal, 4)
        .padding(.top, 22)
        .padding(.bottom, 12)
    }

    // MARK: - Empty state

    private var emptyState: some View {
        VStack(spacing: 18) {
            ZStack {
                Circle()
                    .fill(Color(.secondarySystemFill))
                    .frame(width: 72, height: 72)
                Image(systemName: "photo.on.rectangle.angled")
                    .font(.title)
                    .foregroundStyle(.secondary)
            }
            .accessibilityHidden(true)

            VStack(spacing: 6) {
                Text("No images yet")
                    .font(.headline)
                    .foregroundStyle(.primary)
                Text("Pick from Files, iCloud Drive, or any provider.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(.horizontal, 40)

            Button {
                isPickingFile = true
            } label: {
                Label("Add image", systemImage: "plus")
                    .font(.subheadline.weight(.semibold))
                    .padding(.horizontal, 6)
                    .padding(.vertical, 4)
            }
            .buttonStyle(.glassProminent)
            .accessibilityLabel("Add image from Files")
        }
    }

    // MARK: - Bottom bar

    @ViewBuilder
    private var bottomBar: some View {
        if let selectedFilename {
            HStack {
                Button {
                    ProjectService.setImageEdit(selectedFilename)
                    triggerPulse &+= 1
                    onTrigger?(selectedFilename)
                } label: {
                    HStack(spacing: 10) {
                        Text("Edit image")
                            .font(.headline)
                        Image(systemName: "arrow.right")
                            .font(.subheadline.weight(.bold))
                    }
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: 28)
                    .padding(.vertical, 12)
                }
                .buttonStyle(.glassProminent)
                .accessibilityLabel("Edit image")
                .accessibilityHint("Triggers the edit action with the selected image")
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 12)
            .transition(
                .move(edge: .bottom)
                .combined(with: .opacity)
            )
            .animation(.spring(response: 0.45, dampingFraction: 0.85), value: selectedFilename)
        }
    }

    // MARK: - Logic

    private func reload() {
        let urls = ProjectService.getAllGenerations()
            .filter { Self.imageExtensions.contains($0.pathExtension.lowercased()) }

        let dated: [(URL, Date)] = urls.map { url in
            let d = (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            return (url, d)
        }.sorted { $0.1 > $1.1 }

        var bucketMap: [DateBucket: [URL]] = [:]
        let now = Date()
        for (url, date) in dated {
            bucketMap[DateBucket.from(date, now: now), default: []].append(url)
        }
        groups = DateBucket.allCases.compactMap { bucket in
            guard let urls = bucketMap[bucket], !urls.isEmpty else { return nil }
            return DateGroup(id: bucket, urls: urls)
        }

        // Restore selection if the stored arg still exists; clear if the
        // currently selected file vanished (e.g. just deleted).
        let allNames = Set(dated.map { $0.0.lastPathComponent })
        if selectedFilename == nil,
           let stored = ProjectService.getImageEdit(),
           allNames.contains(stored) {
            selectedFilename = stored
        } else if let cur = selectedFilename, !allNames.contains(cur) {
            selectedFilename = nil
        }
    }

    private func select(_ name: String) {
        selectionPulse &+= 1
        withAnimation(.spring(response: 0.32, dampingFraction: 0.85)) {
            selectedFilename = (selectedFilename == name) ? nil : name
        }
    }

    private func delete(_ url: URL) {
        try? FileManager.default.removeItem(at: url)
        Tile.invalidateThumb(for: url)
        if selectedFilename == url.lastPathComponent {
            ProjectService.clearImageEdit()
        }
        withAnimation(.spring(response: 0.4, dampingFraction: 0.85)) {
            reload()
        }
    }

    private func handleFileImport(_ result: Result<[URL], Error>) {
        guard case .success(let urls) = result, !urls.isEmpty else { return }
        isImporting = true
        Task.detached(priority: .userInitiated) {
            var lastImported: String?
            for url in urls {
                let needsScope = url.startAccessingSecurityScopedResource()
                defer { if needsScope { url.stopAccessingSecurityScopedResource() } }
                guard let data = try? Data(contentsOf: url) else { continue }
                let ext = url.pathExtension.isEmpty ? "jpg" : url.pathExtension
                let name = "img-\(UUID().uuidString).\(ext)"
                ProjectService.saveFile(data, named: name)
                lastImported = name
            }
            let final = lastImported
            await MainActor.run {
                reload()
                if let name = final {
                    withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) {
                        selectedFilename = name
                    }
                }
                isImporting = false
            }
        }
    }

    private static let imageExtensions: Set<String> = [
        "jpg", "jpeg", "png", "heic", "heif", "gif", "tiff", "webp", "bmp"
    ]
}

// MARK: - Date bucketing

enum DateBucket: Int, CaseIterable, Identifiable {
    case today, yesterday, lastWeek, lastMonth, older
    var id: Int { rawValue }
    var title: String {
        switch self {
        case .today:     return "Today"
        case .yesterday: return "Yesterday"
        case .lastWeek:  return "Last 7 Days"
        case .lastMonth: return "Last 30 Days"
        case .older:     return "Older"
        }
    }
    static func from(_ date: Date, now: Date) -> DateBucket {
        let cal = Calendar.current
        if cal.isDateInToday(date) { return .today }
        if cal.isDateInYesterday(date) { return .yesterday }
        let days = cal.dateComponents([.day], from: date, to: now).day ?? 0
        if days <= 7  { return .lastWeek }
        if days <= 30 { return .lastMonth }
        return .older
    }
}

struct DateGroup: Identifiable {
    let id: DateBucket
    let urls: [URL]
}

// MARK: - Tile

private struct Tile: View {
    let url: URL
    let isSelected: Bool
    let onTap: () -> Void
    let onDelete: () -> Void

    @Environment(\.displayScale) private var displayScale
    @State private var image: UIImage?

    private static let thumbCache: NSCache<NSString, UIImage> = {
        let c = NSCache<NSString, UIImage>()
        c.countLimit = 500
        return c
    }()

    static func invalidateThumb(for url: URL) {
        thumbCache.removeObject(forKey: url.path as NSString)
    }

    var body: some View {
        Button(action: onTap) {
            ZStack {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(Color(.secondarySystemFill))

                if let image {
                    Color.clear
                        .overlay {
                            Image(uiImage: image)
                                .resizable()
                                .scaledToFill()
                        }
                        .clipped()
                }

                if isSelected {
                    selectionOverlay
                }
            }
            .aspectRatio(1, contentMode: .fit)
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .strokeBorder(
                        isSelected ? Color.primary : Color(.separator),
                        lineWidth: isSelected ? 2 : 0.5
                    )
            )
            .scaleEffect(isSelected ? 0.97 : 1.0)
            .animation(.spring(response: 0.32, dampingFraction: 0.85), value: isSelected)
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button(role: .destructive, action: onDelete) {
                Label("Delete", systemImage: "trash")
            }
        }
        .accessibilityLabel(isSelected ? "Image, selected" : "Image")
        .accessibilityHint("Double tap to select. Long press for options.")
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
        .task(id: url) { await loadThumb() }
    }

    private var selectionOverlay: some View {
        VStack {
            HStack {
                Spacer()
                ZStack {
                    Circle().fill(Color.primary)
                    Image(systemName: "checkmark")
                        .font(.caption.weight(.heavy))
                        .foregroundStyle(Color(.systemBackground))
                }
                .frame(width: 24, height: 24)
                .padding(10)
            }
            Spacer()
        }
        .accessibilityHidden(true)
    }

    private func loadThumb() async {
        let key = url.path as NSString
        if let cached = Self.thumbCache.object(forKey: key) {
            self.image = cached
            return
        }
        let url = self.url
        let pixelSize = max(320, Int(180 * displayScale))
        let img = await Task.detached(priority: .userInitiated) { () -> UIImage? in
            let opts: [CFString: Any] = [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceShouldCacheImmediately: true,
                kCGImageSourceThumbnailMaxPixelSize: pixelSize
            ]
            guard let src = CGImageSourceCreateWithURL(url as CFURL, nil),
                  let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else {
                return nil
            }
            return UIImage(cgImage: cg)
        }.value
        if let img {
            Self.thumbCache.setObject(img, forKey: key)
        }
        await MainActor.run { self.image = img }
    }
}

#Preview {
    ContentView()
}
