import QuickLook
import SwiftUI
import UIKit

struct AttachmentCardView: View {
    let sessionID: String
    let attachment: ChatAttachment
    @ObservedObject var store: AttachmentDownloadStore
    let onUnauthorized: @MainActor () -> Void

    @State private var showPreview = false
    @State private var shareItem: ShareItem?
    @State private var thumbnail: AttachmentDecodedImage?

    var body: some View {
        Group {
            if attachment.kind == .image {
                imageCard
            } else {
                documentCard
            }
        }
        .frame(maxWidth: 340, alignment: .leading)
        .background(Color.white)
        .overlay {
            RoundedRectangle(cornerRadius: 8)
                .stroke(Color.black.opacity(0.08), lineWidth: 1)
        }
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityIdentifier("attachment.card.\(attachment.id)")
        .fullScreenCover(isPresented: $showPreview) {
            if let url = availableURL {
                if attachment.kind == .image {
                    ImagePreviewView(url: url, filename: attachment.filename, attachmentID: attachment.id)
                } else {
                    DocumentPreviewView(url: url, attachmentID: attachment.id)
                }
            }
        }
        .sheet(item: $shareItem) { item in
            ActivityView(items: [item.url])
        }
        .onAppear { store.retain(attachment) }
        .onDisappear { store.release(attachment) }
    }

    private var imageCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            ZStack {
                Color(red: 0.94, green: 0.95, blue: 0.96)
                if let thumbnail {
                    Image(decorative: thumbnail.cgImage, scale: 1)
                        .resizable()
                        .scaledToFit()
                } else {
                    imagePlaceholder
                }
            }
            .aspectRatio(4 / 3, contentMode: .fit)
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
            .onTapGesture {
                if availableURL != nil { showPreview = true }
            }

            attachmentFooter
        }
        .task(id: attachment.id) {
            guard case .idle = store.state(for: attachment) else { return }
            store.load(sessionID: sessionID, attachment: attachment, onUnauthorized: onUnauthorized)
        }
        .task(id: availableURL) {
            thumbnail = nil
            guard let availableURL else { return }
            thumbnail = await AttachmentImageLoader.shared.image(at: availableURL, maxPixelSize: 900)
        }
    }

    @ViewBuilder
    private var imagePlaceholder: some View {
        switch store.state(for: attachment) {
        case .idle, .downloading:
            VStack(spacing: 8) {
                ProgressView()
                Text("Loading image")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        case .unavailable:
            Image(systemName: "exclamationmark.triangle")
                .font(.title2)
                .foregroundStyle(.secondary)
        case .failed:
            Image(systemName: "arrow.clockwise")
                .font(.title2)
                .foregroundStyle(.secondary)
        case .available:
            EmptyView()
        }
    }

    private var documentCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: attachment.kind.systemImage)
                    .font(.title2)
                    .foregroundStyle(Color(red: 0.90, green: 0.35, blue: 0.18))
                    .frame(width: 44, height: 44)
                    .background(Color(red: 1.0, green: 0.94, blue: 0.91))
                    .clipShape(RoundedRectangle(cornerRadius: 7))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 4) {
                    Text(attachment.filename)
                        .font(.subheadline.weight(.semibold))
                        .fixedSize(horizontal: false, vertical: true)
                    Text(attachment.detailText)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            }
            documentActions
            if let caption = attachment.caption { captionText(caption) }
        }
        .padding(12)
    }

    @ViewBuilder
    private var documentActions: some View {
        switch store.state(for: attachment) {
        case .idle:
            Button {
                store.load(sessionID: sessionID, attachment: attachment, onUnauthorized: onUnauthorized)
            } label: {
                Label("Download", systemImage: "arrow.down.circle")
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.borderedProminent)
        case .downloading:
            HStack(spacing: 10) {
                ProgressView()
                Text("Downloading").font(.subheadline)
                Spacer()
                Button("Cancel") { store.cancel(attachment) }
                    .frame(minWidth: 44, minHeight: 44)
            }
        case .available:
            HStack(spacing: 8) {
                Button {
                    showPreview = true
                } label: {
                    Label("Open", systemImage: "eye")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("attachment.open.\(attachment.id)")
                shareButton
            }
        case .unavailable:
            unavailableContent
        case let .failed(reason):
            retryContent(reason: reason)
        }
    }

    private var attachmentFooter: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 8) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(attachment.filename)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(2)
                    Text(attachment.detailText)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 8)
                if availableURL != nil { shareButton }
            }
            if let caption = attachment.caption { captionText(caption) }
            if availableURL != nil {
                Button {
                    showPreview = true
                } label: {
                    Label("Full Screen", systemImage: "arrow.up.left.and.arrow.down.right")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("attachment.open.\(attachment.id)")
            } else if case let .failed(reason) = store.state(for: attachment) {
                retryContent(reason: reason)
            } else if case .unavailable = store.state(for: attachment) {
                unavailableContent
            }
        }
        .padding(12)
    }

    private var shareButton: some View {
        Button {
            if let availableURL { shareItem = ShareItem(url: availableURL) }
        } label: {
            Image(systemName: "square.and.arrow.up")
                .frame(width: 44, height: 44)
        }
        .accessibilityLabel("Share \(attachment.filename)")
        .accessibilityIdentifier("attachment.share.\(attachment.id)")
    }

    private func retryContent(reason: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(reason)
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Button {
                store.retry(sessionID: sessionID, attachment: attachment, onUnauthorized: onUnauthorized)
            } label: {
                Label("Retry download", systemImage: "arrow.clockwise")
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.bordered)
            .accessibilityIdentifier("attachment.retry.\(attachment.id)")
        }
    }

    private var unavailableContent: some View {
        Label("Attachment unavailable", systemImage: "exclamationmark.triangle")
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
    }

    private func captionText(_ caption: String) -> some View {
        Text(caption)
            .font(.footnote)
            .fixedSize(horizontal: false, vertical: true)
    }

    private var availableURL: URL? {
        guard case let .available(url) = store.state(for: attachment) else { return nil }
        return url
    }

    private var accessibilityLabel: String {
        let state: String
        switch store.state(for: attachment) {
        case .idle: state = "not downloaded"
        case .downloading: state = "downloading"
        case .available: state = "ready"
        case .unavailable: state = "unavailable"
        case let .failed(reason): state = "download failed, \(reason)"
        }
        return "\(attachment.kind.label), \(attachment.filename), \(attachment.detailText), \(state)"
    }
}

private struct ShareItem: Identifiable {
    let id = UUID()
    let url: URL
}

private struct ImagePreviewView: View {
    @Environment(\.dismiss) private var dismiss
    let url: URL
    let filename: String
    let attachmentID: String
    @State private var decodedImage: AttachmentDecodedImage?

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                if let decodedImage {
                    Image(decorative: decodedImage.cgImage, scale: 1)
                        .resizable()
                        .scaledToFit()
                        .accessibilityLabel(filename)
                } else {
                    ProgressView()
                        .tint(.white)
                }
            }
            .accessibilityIdentifier("attachment.preview.\(attachmentID)")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                        .frame(minWidth: 44, minHeight: 44)
                        .accessibilityIdentifier("attachment.preview.done.\(attachmentID)")
                }
            }
        }
        .task(id: url) {
            decodedImage = await AttachmentImageLoader.shared.image(at: url, maxPixelSize: 2_048)
        }
    }
}

private struct QuickLookPreview: UIViewControllerRepresentable {
    let url: URL
    func makeCoordinator() -> Coordinator { Coordinator(url: url) }
    func makeUIViewController(context: Context) -> QLPreviewController {
        let controller = QLPreviewController()
        controller.dataSource = context.coordinator
        return controller
    }
    func updateUIViewController(_ uiViewController: QLPreviewController, context: Context) {}

    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        let url: URL
        init(url: URL) { self.url = url }
        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }
        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem {
            url as NSURL
        }
    }
}

private struct DocumentPreviewView: View {
    @Environment(\.dismiss) private var dismiss
    let url: URL
    let attachmentID: String

    var body: some View {
        NavigationStack {
            QuickLookPreview(url: url)
                .ignoresSafeArea(edges: .bottom)
                .accessibilityIdentifier("attachment.preview.\(attachmentID)")
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Done") { dismiss() }
                            .frame(minWidth: 44, minHeight: 44)
                            .accessibilityIdentifier("attachment.preview.done.\(attachmentID)")
                    }
                }
        }
    }
}

private struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
