import QuickLook
import SwiftUI
import UIKit

struct AttachmentCardView: View {
    let sessionID: String
    let attachment: ChatAttachment
    let messageCreatedAt: Date?
    let sourceLabel: String?
    let sourceMessageSeq: Int64?
    let sourceAgentID: Int64?
    let sourceSessionTitle: String?
    let isGeneratedImage: Bool
    @ObservedObject var store: AttachmentDownloadStore
    let onUnauthorized: @MainActor () -> Void
    let onSubmitSnapshot: @MainActor (String) -> Void
    let onRegenerateImage: @MainActor () -> Void

    @State private var showPreview = false
    @State private var shareItem: ShareItem?
    @State private var thumbnail: AttachmentDecodedImage?

    init(
        sessionID: String,
        attachment: ChatAttachment,
        messageCreatedAt: Date? = nil,
        sourceLabel: String? = nil,
        sourceMessageSeq: Int64? = nil,
        sourceAgentID: Int64? = nil,
        sourceSessionTitle: String? = nil,
        isGeneratedImage: Bool = false,
        store: AttachmentDownloadStore,
        onUnauthorized: @escaping @MainActor () -> Void,
        onSubmitSnapshot: @escaping @MainActor (String) -> Void,
        onRegenerateImage: @escaping @MainActor () -> Void = {}
    ) {
        self.sessionID = sessionID
        self.attachment = attachment
        self.messageCreatedAt = messageCreatedAt
        self.sourceLabel = sourceLabel
        self.sourceMessageSeq = sourceMessageSeq
        self.sourceAgentID = sourceAgentID
        self.sourceSessionTitle = sourceSessionTitle
        self.isGeneratedImage = isGeneratedImage
        self.store = store
        self.onUnauthorized = onUnauthorized
        self.onSubmitSnapshot = onSubmitSnapshot
        self.onRegenerateImage = onRegenerateImage
    }

    @ViewBuilder
    var body: some View {
        if attachment.kind == .interactive {
            PersonalAppCardView(
                sessionID: sessionID,
                attachment: attachment,
                messageCreatedAt: messageCreatedAt,
                sourceLabel: sourceLabel,
                sourceMessageSeq: sourceMessageSeq,
                sourceAgentID: sourceAgentID,
                sourceSessionTitle: sourceSessionTitle,
                store: store,
                onUnauthorized: onUnauthorized,
                onSubmitSnapshot: onSubmitSnapshot
            )
        } else {
            standardAttachmentCard
        }
    }

    private var standardAttachmentCard: some View {
        Group {
            if attachment.kind == .image {
                if isGeneratedImage { generatedImageCard } else { imageCard }
            } else {
                documentCard
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(uiColor: .secondarySystemBackground))
        .overlay {
            RoundedRectangle(cornerRadius: 8)
                .stroke(Color(uiColor: .separator).opacity(0.35), lineWidth: 1)
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

    private var generatedImageCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                Image(systemName: "photo.on.rectangle.angled")
                    .font(.headline)
                    .foregroundStyle(.blue)
                    .frame(width: 36, height: 36)
                    .background(Color.blue.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 3) {
                    Text("图片已生成")
                        .font(.subheadline.weight(.semibold))
                    Text(sourceLabel.map { "\($0) · 刚刚" } ?? "AI 生成 · 刚刚")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 8)
                Text(generatedStatusText)
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(generatedStatusColor)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 5)
                    .background(generatedStatusColor.opacity(0.12), in: Capsule())
                    .accessibilityIdentifier("attachment.generated.status.\(attachment.id)")
            }
            .padding(12)

            ZStack {
                Color(uiColor: .tertiarySystemFill)
                if let thumbnail {
                    Image(decorative: thumbnail.cgImage, scale: 1)
                        .resizable()
                        .scaledToFit()
                } else {
                    imagePlaceholder
                }
            }
            .aspectRatio(16 / 9, contentMode: .fit)
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
            .onTapGesture { if availableURL != nil { showPreview = true } }

            HStack {
                Text(attachment.detailText)
                Spacer(minLength: 8)
                Text("已保存到 Session")
            }
            .font(.caption2)
            .foregroundStyle(.secondary)
            .padding(.horizontal, 12)
            .padding(.vertical, 9)

            HStack(spacing: 0) {
                generatedAction("预览", systemImage: "eye", identifier: "attachment.open.\(attachment.id)") {
                    if availableURL != nil { showPreview = true }
                }
                Divider()
                generatedAction("分享", systemImage: "square.and.arrow.up", identifier: "attachment.share.\(attachment.id)") {
                    if let availableURL { shareItem = ShareItem(url: availableURL) }
                }
                Divider()
                generatedAction("再次生成", systemImage: "arrow.clockwise", identifier: "attachment.regenerate.\(attachment.id)", tint: .orange) {
                    onRegenerateImage()
                }
            }
            .frame(height: 48)
            .overlay(alignment: .top) { Divider() }
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

    private func generatedAction(
        _ title: String,
        systemImage: String,
        identifier: String,
        tint: Color = .primary,
        action: @escaping @MainActor () -> Void
    ) -> some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .font(.caption.weight(.semibold))
                .foregroundStyle(tint)
                .frame(maxWidth: .infinity, minHeight: 44)
        }
        .buttonStyle(.plain)
        .disabled(title != "再次生成" && availableURL == nil)
        .accessibilityIdentifier(identifier)
    }

    private var generatedStatusText: String {
        switch store.state(for: attachment) {
        case .available: "成功"
        case .idle, .downloading: "加载中"
        case .unavailable, .failed: "不可用"
        }
    }

    private var generatedStatusColor: Color {
        switch store.state(for: attachment) {
        case .available: .green
        case .idle, .downloading: .blue
        case .unavailable, .failed: .red
        }
    }

    private var imageCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            ZStack {
                Color(uiColor: .tertiarySystemFill)
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
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                    .foregroundStyle(Color(uiColor: .systemOrange))
                    .frame(width: 44, height: 44)
                    .background(Color(uiColor: .systemOrange).opacity(0.14))
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
    @State private var zoomScale: CGFloat = 1

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                if let decodedImage {
                    ZoomableImageView(image: UIImage(cgImage: decodedImage.cgImage), zoomScale: $zoomScale)
                        .accessibilityLabel(filename)
                } else {
                    ProgressView()
                        .tint(.white)
                }
            }
            .accessibilityIdentifier("attachment.preview.\(attachmentID)")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Text("\(Int((zoomScale * 100).rounded()))%")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                        .accessibilityLabel("Image zoom")
                        .accessibilityValue("\(Int((zoomScale * 100).rounded()))%")
                        .accessibilityIdentifier("attachment.preview.zoom.\(attachmentID)")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                        .frame(minWidth: 44, minHeight: 44)
                        .accessibilityIdentifier("attachment.preview.done.\(attachmentID)")
                }
            }
        }
        .task(id: url) {
            // Keep enough source pixels for inspecting a 4K result while zoomed. The
            // loader still downsamples provider images above this bound and NSCache
            // accounts for decoded byte cost.
            decodedImage = await AttachmentImageLoader.shared.image(at: url, maxPixelSize: 5_504)
        }
    }
}

private struct ZoomableImageView: UIViewRepresentable {
    let image: UIImage
    @Binding var zoomScale: CGFloat

    func makeCoordinator() -> Coordinator { Coordinator(zoomScale: $zoomScale) }

    func makeUIView(context: Context) -> UIScrollView {
        let scrollView = UIScrollView()
        scrollView.backgroundColor = .black
        scrollView.minimumZoomScale = 1
        scrollView.maximumZoomScale = 5
        scrollView.bouncesZoom = true
        scrollView.delegate = context.coordinator

        let imageView = UIImageView(image: image)
        imageView.contentMode = .scaleAspectFit
        imageView.translatesAutoresizingMaskIntoConstraints = false
        imageView.isUserInteractionEnabled = true
        scrollView.addSubview(imageView)
        NSLayoutConstraint.activate([
            imageView.leadingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.leadingAnchor),
            imageView.trailingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.trailingAnchor),
            imageView.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor),
            imageView.bottomAnchor.constraint(equalTo: scrollView.contentLayoutGuide.bottomAnchor),
            imageView.widthAnchor.constraint(equalTo: scrollView.frameLayoutGuide.widthAnchor),
            imageView.heightAnchor.constraint(equalTo: scrollView.frameLayoutGuide.heightAnchor)
        ])
        context.coordinator.imageView = imageView
        context.coordinator.scrollView = scrollView

        let doubleTap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.toggleZoom(_:)))
        doubleTap.numberOfTapsRequired = 2
        scrollView.addGestureRecognizer(doubleTap)
        return scrollView
    }

    func updateUIView(_ scrollView: UIScrollView, context: Context) {
        context.coordinator.imageView?.image = image
    }

    final class Coordinator: NSObject, UIScrollViewDelegate {
        weak var imageView: UIImageView?
        weak var scrollView: UIScrollView?
        private let zoomScale: Binding<CGFloat>

        init(zoomScale: Binding<CGFloat>) { self.zoomScale = zoomScale }

        func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }

        func scrollViewDidZoom(_ scrollView: UIScrollView) {
            zoomScale.wrappedValue = scrollView.zoomScale
        }

        @objc func toggleZoom(_ recognizer: UITapGestureRecognizer) {
            guard let scrollView else { return }
            if scrollView.zoomScale > scrollView.minimumZoomScale {
                scrollView.setZoomScale(scrollView.minimumZoomScale, animated: true)
                return
            }
            let point = recognizer.location(in: imageView)
            let targetScale: CGFloat = 2.5
            let size = CGSize(width: scrollView.bounds.width / targetScale, height: scrollView.bounds.height / targetScale)
            scrollView.zoom(to: CGRect(x: point.x - size.width / 2, y: point.y - size.height / 2,
                                       width: size.width, height: size.height), animated: true)
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
