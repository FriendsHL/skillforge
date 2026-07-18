import SwiftUI
import UIKit

enum PersonalAppCardActionTone: String, Equatable {
    case neutralDark
}

enum PersonalAppSourceLabelPolicy {
    static func resolve(
        sessionAgentID: Int64?,
        availableAgents: [MobileAgentCatalogItem],
        defaultAgent: MobileAgentSummary?
    ) -> String? {
        guard let sessionAgentID else { return nil }

        if let matchedName = availableAgents.lazy
            .filter({ $0.id == sessionAgentID })
            .compactMap({ normalizedName($0.name) })
            .first {
            return matchedName
        }

        guard let defaultAgent, defaultAgent.id == sessionAgentID else { return nil }
        return normalizedName(defaultAgent.name)
    }

    private static func normalizedName(_ value: String) -> String? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}

struct PersonalAppCardPresentation {
    static let badgeText = "PERSONAL APP"
    static let capabilityText = "Offline · No permissions"
    static let primaryActionTone: PersonalAppCardActionTone = .neutralDark

    static func provenanceText(
        sourceLabel: String?,
        createdAt: Date?,
        locale: Locale = .autoupdatingCurrent,
        timeZone: TimeZone = .autoupdatingCurrent
    ) -> String? {
        var facts: [String] = []
        if let sourceLabel, let normalizedSource = normalizedFact(sourceLabel) {
            facts.append(normalizedSource)
        }
        if let createdAt {
            let formatter = DateFormatter()
            formatter.locale = locale
            formatter.timeZone = timeZone
            formatter.dateStyle = .medium
            formatter.timeStyle = .short
            facts.append(formatter.string(from: createdAt))
        }
        return facts.isEmpty ? nil : facts.joined(separator: " · ")
    }

    static func accessibilitySummary(
        title: String,
        caption: String?
    ) -> String {
        [
            title,
            caption,
            "Personal App"
        ]
        .compactMap { value in
            guard let value, let normalized = normalizedFact(value) else { return nil }
            return normalized.trimmingCharacters(in: CharacterSet(charactersIn: ".。"))
        }
        .joined(separator: ". ")
    }

    private static func normalizedFact(_ value: String) -> String? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}

struct PersonalAppCardView: View {
    let sessionID: String
    let attachment: ChatAttachment
    let messageCreatedAt: Date?
    let sourceLabel: String?
    let sourceMessageSeq: Int64?
    let sourceAgentID: Int64?
    let sourceSessionTitle: String?
    @ObservedObject var store: AttachmentDownloadStore
    let onUnauthorized: @MainActor () -> Void
    let onSubmitSnapshot: @MainActor (String) -> Void

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var showViewer = false
    @State private var shareItem: PersonalAppCardShareItem?

    init(
        sessionID: String,
        attachment: ChatAttachment,
        messageCreatedAt: Date? = nil,
        sourceLabel: String? = nil,
        sourceMessageSeq: Int64? = nil,
        sourceAgentID: Int64? = nil,
        sourceSessionTitle: String? = nil,
        store: AttachmentDownloadStore,
        onUnauthorized: @escaping @MainActor () -> Void,
        onSubmitSnapshot: @escaping @MainActor (String) -> Void
    ) {
        self.sessionID = sessionID
        self.attachment = attachment
        self.messageCreatedAt = messageCreatedAt
        self.sourceLabel = sourceLabel
        self.sourceMessageSeq = sourceMessageSeq
        self.sourceAgentID = sourceAgentID
        self.sourceSessionTitle = sourceSessionTitle
        self.store = store
        self.onUnauthorized = onUnauthorized
        self.onSubmitSnapshot = onSubmitSnapshot
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            preview
            cardSummary
            metadata
            actions
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(Color(uiColor: .secondarySystemGroupedBackground))
        }
        .overlay {
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .stroke(Color(uiColor: .separator).opacity(colorScheme == .dark ? 0.7 : 0.35), lineWidth: 1)
        }
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .shadow(
            color: Color.black.opacity(colorScheme == .dark ? 0.24 : 0.10),
            radius: colorScheme == .dark ? 10 : 18,
            x: 0,
            y: 8
        )
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("attachment.card.\(attachment.id)")
        .fullScreenCover(isPresented: $showViewer) {
            if let availableURL {
                PersonalAppViewer(
                    sessionID: sessionID,
                    attachment: attachment,
                    htmlURL: availableURL,
                    store: store,
                    onUnauthorized: onUnauthorized,
                    onSubmitSnapshot: onSubmitSnapshot
                )
            }
        }
        .sheet(item: $shareItem) { item in
            PersonalAppCardActivityView(items: [item.url])
        }
        .onAppear { store.retain(attachment) }
        .onDisappear { store.release(attachment) }
        .task(id: attachment.id) {
            guard case .idle = store.state(for: attachment) else { return }
            store.load(
                sessionID: sessionID,
                attachment: attachment,
                localMetadata: localMetadata,
                onUnauthorized: onUnauthorized
            )
        }
    }

    private var cardSummary: some View {
        titleSection
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(PersonalAppCardPresentation.accessibilitySummary(
            title: displayTitle,
            caption: attachment.caption
        ))
        .accessibilityIdentifier("personalApp.summary.\(attachment.id)")
    }

    private var preview: some View {
        PersonalAppVisualPreview()
            .frame(maxWidth: .infinity)
            .frame(height: dynamicTypeSize.isAccessibilitySize ? 118 : 142)
            .background(
                LinearGradient(
                    colors: [
                        Color(red: 0.08, green: 0.09, blue: 0.14),
                        Color(red: 0.13, green: 0.14, blue: 0.22)
                    ],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
            )
            .overlay {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(Color.white.opacity(0.10), lineWidth: 1)
            }
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .accessibilityHidden(true)
    }

    @ViewBuilder
    private var titleSection: some View {
        if dynamicTypeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 10) {
                titleAndCaption
                badge
            }
        } else {
            HStack(alignment: .top, spacing: 10) {
                titleAndCaption
                Spacer(minLength: 4)
                badge
            }
        }
    }

    private var titleAndCaption: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(displayTitle)
                .font(.headline)
                .foregroundStyle(.primary)
                .fixedSize(horizontal: false, vertical: true)
            if let caption = attachment.caption {
                Text(caption)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var badge: some View {
        Text(PersonalAppCardPresentation.badgeText)
            .font(.caption2.weight(.heavy))
            .tracking(0.5)
            .foregroundStyle(Color(uiColor: .systemIndigo))
            .padding(.horizontal, 9)
            .padding(.vertical, 6)
            .background(Color(uiColor: .systemIndigo).opacity(colorScheme == .dark ? 0.24 : 0.12))
            .clipShape(Capsule())
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityIdentifier("personalApp.badge.\(attachment.id)")
    }

    @ViewBuilder
    private var metadata: some View {
        if dynamicTypeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 6) {
                capabilityMetadata
                provenanceMetadata
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            HStack(alignment: .top, spacing: 8) {
                capabilityMetadata
                    .layoutPriority(1)
                Spacer(minLength: 4)
                provenanceMetadata
            }
            .frame(maxWidth: .infinity)
        }
    }

    private var capabilityMetadata: some View {
        Text(PersonalAppCardPresentation.capabilityText)
            .font(.caption)
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityIdentifier("personalApp.capabilities.\(attachment.id)")
    }

    @ViewBuilder
    private var provenanceMetadata: some View {
        if let provenance = PersonalAppCardPresentation.provenanceText(
            sourceLabel: sourceLabel,
            createdAt: messageCreatedAt
        ) {
            Text(provenance)
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(dynamicTypeSize.isAccessibilitySize ? .leading : .trailing)
                .fixedSize(horizontal: false, vertical: true)
                .frame(
                    maxWidth: .infinity,
                    alignment: dynamicTypeSize.isAccessibilitySize ? .leading : .trailing
                )
                .accessibilityIdentifier("personalApp.provenance.\(attachment.id)")
        }
    }

    @ViewBuilder
    private var actions: some View {
        switch store.state(for: attachment) {
        case .idle:
            Button {
                store.load(
                    sessionID: sessionID,
                    attachment: attachment,
                    localMetadata: localMetadata,
                    onUnauthorized: onUnauthorized
                )
            } label: {
                Label("Download Personal App", systemImage: "arrow.down.circle")
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(PersonalAppPrimaryButtonStyle())
            .accessibilityIdentifier("attachment.download.\(attachment.id)")

        case .downloading:
            HStack(spacing: 10) {
                ProgressView()
                Text("Downloading Personal App")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Spacer(minLength: 0)
                Button("Cancel") { store.cancel(attachment) }
                    .frame(minWidth: 44, minHeight: 44)
                    .accessibilityIdentifier("attachment.cancel.\(attachment.id)")
            }

        case .available:
            HStack(spacing: 10) {
                Button {
                    showViewer = true
                } label: {
                    Label("Open", systemImage: "arrow.up.right.square")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(PersonalAppPrimaryButtonStyle())
                .accessibilityLabel("Open \(displayTitle)")
                .accessibilityIdentifier("attachment.open.\(attachment.id)")

                Button {
                    if let availableURL {
                        shareItem = PersonalAppCardShareItem(url: availableURL)
                    }
                } label: {
                    Image(systemName: "square.and.arrow.up")
                        .font(.system(size: 17, weight: .semibold))
                        .frame(width: 44, height: 44)
                        .background(Color(uiColor: .tertiarySystemFill))
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .buttonStyle(.plain)
                .foregroundStyle(.primary)
                .accessibilityLabel("Share \(displayTitle)")
                .accessibilityIdentifier("attachment.share.\(attachment.id)")
            }

        case .unavailable:
            Label("Personal App unavailable", systemImage: "exclamationmark.triangle")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)

        case let .failed(reason):
            VStack(alignment: .leading, spacing: 8) {
                Text(reason)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                Button {
                    store.retry(
                        sessionID: sessionID,
                        attachment: attachment,
                        localMetadata: localMetadata,
                        onUnauthorized: onUnauthorized
                    )
                } label: {
                    Label("Retry download", systemImage: "arrow.clockwise")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(PersonalAppSecondaryButtonStyle())
                .accessibilityIdentifier("attachment.retry.\(attachment.id)")
            }
        }
    }

    private var displayTitle: String {
        attachment.title ?? attachment.filename
    }

    private var availableURL: URL? {
        guard case let .available(url) = store.state(for: attachment) else { return nil }
        return url
    }

    private var localMetadata: PersonalAppLocalRecord? {
        guard let sourceMessageSeq,
              let sourceAgentID,
              let messageCreatedAt else { return nil }
        return PersonalAppLocalRecord(
            artifactId: attachment.id,
            sessionId: sessionID,
            sourceMessageSeq: sourceMessageSeq,
            title: displayTitle,
            caption: attachment.caption,
            schemaVersion: attachment.artifactSchemaVersion ?? 1,
            permissions: [],
            network: [],
            agentId: sourceAgentID,
            agentName: sourceLabel ?? "Agent #\(sourceAgentID)",
            sessionTitle: sourceSessionTitle,
            createdAt: ISO8601DateFormatter().string(from: messageCreatedAt),
            lastOpenedAt: nil,
            favorite: false
        )
    }
}

private struct PersonalAppVisualPreview: View {
    var body: some View {
        HStack(alignment: .center, spacing: 24) {
            ZStack {
                Circle()
                    .stroke(Color.white.opacity(0.12), lineWidth: 13)
                Circle()
                    .trim(from: 0.06, to: 0.58)
                    .stroke(
                        Color(uiColor: .systemIndigo),
                        style: StrokeStyle(lineWidth: 13, lineCap: .round)
                    )
                    .rotationEffect(.degrees(-90))
                Circle()
                    .trim(from: 0.64, to: 0.82)
                    .stroke(
                        Color(uiColor: .systemTeal),
                        style: StrokeStyle(lineWidth: 13, lineCap: .round)
                    )
                    .rotationEffect(.degrees(-90))
                Image(systemName: "app.badge")
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(.white)
            }
            .frame(width: 78, height: 78)

            HStack(alignment: .bottom, spacing: 8) {
                previewBar(height: 42, color: Color(uiColor: .systemIndigo))
                previewBar(height: 78, color: Color(uiColor: .systemOrange))
                previewBar(height: 58, color: Color(uiColor: .systemTeal))
            }
            .frame(maxWidth: .infinity, maxHeight: 86, alignment: .bottom)
        }
        .padding(.horizontal, 22)
        .padding(.vertical, 16)
    }

    private func previewBar(height: CGFloat, color: Color) -> some View {
        RoundedRectangle(cornerRadius: 5, style: .continuous)
            .fill(color.gradient)
            .frame(maxWidth: 26, maxHeight: height)
    }
}

private struct PersonalAppPrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.white)
            .padding(.horizontal, 14)
            .background(
                configuration.isPressed
                    ? Color(red: 0.17, green: 0.18, blue: 0.23)
                    : Color(red: 0.07, green: 0.08, blue: 0.11)
            )
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .contentShape(Rectangle())
    }
}

private struct PersonalAppSecondaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.primary)
            .padding(.horizontal, 14)
            .background(Color(uiColor: .tertiarySystemFill).opacity(configuration.isPressed ? 0.65 : 1))
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .contentShape(Rectangle())
    }
}

private struct PersonalAppCardShareItem: Identifiable {
    let id = UUID()
    let url: URL
}

private struct PersonalAppCardActivityView: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
