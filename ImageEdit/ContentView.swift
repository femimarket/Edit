//
//  ContentView.swift
//  ImageEdit
//
//  Created by u on 22/06/2026.
//

import SwiftUI
import PhotosUI
import ProjectService

struct ContentView: View {
    /// Hook the host wires up to actually perform the edit. The screen's
    /// only job is to set the arg via `ProjectService.setImageEdit` and
    /// then call this — the work itself happens elsewhere.
    var onTrigger: ((String) -> Void)? = nil

    @State private var files: [URL] = []
    @State private var selectedFilename: String? = nil
    @State private var photoPickerItem: PhotosPickerItem? = nil
    @State private var triggerPulse: Int = 0

    private let columns = [
        GridItem(.adaptive(minimum: 108, maximum: 180), spacing: 10)
    ]

    var body: some View {
        ZStack(alignment: .bottom) {
            background

            ScrollView(showsIndicators: false) {
                VStack(spacing: 0) {
                    header
                        .padding(.horizontal, 24)
                        .padding(.top, 12)
                        .padding(.bottom, 24)

                    if files.isEmpty {
                        emptyState
                            .padding(.top, 80)
                    } else {
                        LazyVGrid(columns: columns, spacing: 10) {
                            ForEach(files, id: \.self) { url in
                                Tile(
                                    url: url,
                                    isSelected: selectedFilename == url.lastPathComponent,
                                    onTap: { select(url.lastPathComponent) }
                                )
                            }
                        }
                        .padding(.horizontal, 16)
                    }

                    Color.clear.frame(height: 140)
                }
            }

            bottomBar
        }
        .preferredColorScheme(.dark)
        .task { reload() }
        .onChange(of: photoPickerItem) { _, item in
            guard let item else { return }
            Task { await handlePicked(item) }
        }
        .sensoryFeedback(.success, trigger: triggerPulse)
    }

    // MARK: - Background

    private var background: some View {
        ZStack {
            Color.black
            RadialGradient(
                colors: [Color(red: 0.18, green: 0.06, blue: 0.30).opacity(0.55), .clear],
                center: .init(x: 0.15, y: 0.05),
                startRadius: 4, endRadius: 520
            )
            RadialGradient(
                colors: [Color(red: 0.02, green: 0.10, blue: 0.28).opacity(0.55), .clear],
                center: .init(x: 0.95, y: 0.95),
                startRadius: 4, endRadius: 560
            )
            LinearGradient(
                colors: [.white.opacity(0.04), .clear],
                startPoint: .top, endPoint: .center
            )
        }
        .ignoresSafeArea()
    }

    // MARK: - Header

    private var header: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 6) {
                Text("Image Edit")
                    .font(.system(size: 34, weight: .semibold))
                    .foregroundStyle(
                        LinearGradient(
                            colors: [.white, .white.opacity(0.6)],
                            startPoint: .top, endPoint: .bottom
                        )
                    )
                    .tracking(-0.4)

                Text("Select an image to edit")
                    .font(.system(size: 14, weight: .regular))
                    .foregroundStyle(.white.opacity(0.45))
            }

            Spacer()

            PhotosPicker(
                selection: $photoPickerItem,
                matching: .images,
                photoLibrary: .shared()
            ) {
                Image(systemName: "plus")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.glass)
        }
    }

    // MARK: - Empty state

    private var emptyState: some View {
        VStack(spacing: 14) {
            ZStack {
                Circle()
                    .fill(.white.opacity(0.04))
                    .frame(width: 72, height: 72)
                    .overlay(Circle().stroke(.white.opacity(0.08), lineWidth: 0.5))
                Image(systemName: "photo.on.rectangle.angled")
                    .font(.system(size: 28, weight: .light))
                    .foregroundStyle(.white.opacity(0.55))
            }

            Text("No images yet")
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(.white.opacity(0.85))

            Text("Add an image from your library to get started")
                .font(.system(size: 13))
                .foregroundStyle(.white.opacity(0.4))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
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
                            .font(.system(size: 17, weight: .semibold))
                        Image(systemName: "arrow.right")
                            .font(.system(size: 14, weight: .bold))
                    }
                    .foregroundStyle(.black)
                    .frame(maxWidth: .infinity)
                    .frame(height: 56)
                }
                .buttonStyle(.glassProminent)
                .tint(.white)
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 28)
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
            .sorted { a, b in
                let da = (try? a.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                let db = (try? b.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                return da > db
            }
        files = urls
    }

    private func select(_ name: String) {
        withAnimation(.spring(response: 0.32, dampingFraction: 0.85)) {
            selectedFilename = (selectedFilename == name) ? nil : name
        }
    }

    private func handlePicked(_ item: PhotosPickerItem) async {
        guard let data = try? await item.loadTransferable(type: Data.self) else {
            photoPickerItem = nil
            return
        }
        let ext = item.supportedContentTypes
            .compactMap { $0.preferredFilenameExtension }
            .first ?? "jpg"
        let name = "img-\(UUID().uuidString).\(ext)"
        ProjectService.saveFile(data, named: name)
        await MainActor.run {
            reload()
            withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) {
                selectedFilename = name
            }
            photoPickerItem = nil
        }
    }

    private static let imageExtensions: Set<String> = [
        "jpg", "jpeg", "png", "heic", "heif", "gif", "tiff", "webp", "bmp"
    ]
}

// MARK: - Tile

private struct Tile: View {
    let url: URL
    let isSelected: Bool
    let onTap: () -> Void

    @Environment(\.displayScale) private var displayScale
    @State private var image: UIImage?

    var body: some View {
        Button(action: onTap) {
            ZStack {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(.white.opacity(0.05))

                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                } else {
                    LinearGradient(
                        colors: [.white.opacity(0.06), .white.opacity(0.02)],
                        startPoint: .top, endPoint: .bottom
                    )
                }

                // Music-video gloss: top highlight + bottom shade
                LinearGradient(
                    colors: [
                        .white.opacity(0.22),
                        .clear,
                        .clear,
                        .black.opacity(0.30)
                    ],
                    startPoint: .top, endPoint: .bottom
                )
                .blendMode(.overlay)
                .allowsHitTesting(false)

                if isSelected {
                    selectionOverlay
                }
            }
            .aspectRatio(1, contentMode: .fit)
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .strokeBorder(
                        LinearGradient(
                            colors: isSelected
                                ? [.white, .white.opacity(0.6)]
                                : [.white.opacity(0.10), .white.opacity(0.02)],
                            startPoint: .top, endPoint: .bottom
                        ),
                        lineWidth: isSelected ? 1.5 : 0.5
                    )
            )
            .shadow(
                color: isSelected ? .white.opacity(0.18) : .black.opacity(0.35),
                radius: isSelected ? 16 : 8,
                y: isSelected ? 6 : 4
            )
            .scaleEffect(isSelected ? 0.97 : 1.0)
            .animation(.spring(response: 0.32, dampingFraction: 0.85), value: isSelected)
        }
        .buttonStyle(.plain)
        .task(id: url) { await loadThumb() }
    }

    private var selectionOverlay: some View {
        VStack {
            HStack {
                Spacer()
                ZStack {
                    Circle().fill(.white)
                    Image(systemName: "checkmark")
                        .font(.system(size: 11, weight: .heavy))
                        .foregroundStyle(.black)
                }
                .frame(width: 24, height: 24)
                .shadow(color: .black.opacity(0.4), radius: 4, y: 2)
                .padding(10)
            }
            Spacer()
        }
    }

    private func loadThumb() async {
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
        await MainActor.run { self.image = img }
    }
}

#Preview {
    ContentView()
}
