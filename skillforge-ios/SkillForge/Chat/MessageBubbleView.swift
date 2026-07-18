import SwiftUI

struct MessageBubbleView: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    let message: ChatMessage
    let sessionID: String
    let sourceLabel: String?
    let sourceAgentID: Int64?
    let sourceSessionTitle: String?
    @ObservedObject var attachmentStore: AttachmentDownloadStore
    @Binding var expandedToolCallIDs: Set<String>
    let onUnauthorized: @MainActor () -> Void
    let onSubmitArtifactSnapshot: @MainActor (String) -> Void

    var body: some View {
        ChatTurnLayout(
            isTrailing: message.role == .user,
            widthFraction: ChatMessageLayoutPolicy.widthFraction(
                isUser: message.role == .user,
                text: message.text,
                isAccessibilitySize: dynamicTypeSize.isAccessibilitySize
            )
        ) {
            if message.role == .user {
                userTurn
                    .background {
                        surfaceAccessibilityAnchor(
                            identifier: "chat.message.\(message.id).userSurface",
                            label: "User message surface"
                        )
                    }
            } else {
                assistantTurn
                    .background {
                        surfaceAccessibilityAnchor(
                            identifier: "chat.message.\(message.id).assistantSurface",
                            label: "Assistant result surface"
                        )
                    }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("chat.message.\(message.id)")
    }

    private var userTurn: some View {
        VStack(alignment: .trailing, spacing: 8) {
            if hasText {
                messageText
                    .padding(.horizontal, 14)
                    .padding(.vertical, 11)
                    .foregroundStyle(CompanionStyle.userQueryForeground)
                    .background(CompanionStyle.userQueryBackground, in: userBubbleShape)
                    .overlay {
                        userBubbleShape
                            .stroke(CompanionStyle.userQueryBorder, lineWidth: 0.75)
                    }
            }
            resultModules
        }
    }

    private var userBubbleShape: UnevenRoundedRectangle {
        UnevenRoundedRectangle(
            topLeadingRadius: 18,
            bottomLeadingRadius: 18,
            bottomTrailingRadius: 5,
            topTrailingRadius: 18,
            style: .continuous
        )
    }

    private var assistantTurn: some View {
        VStack(alignment: .leading, spacing: 10) {
            assistantHeader
            if hasText {
                messageText
                    .foregroundStyle(.primary)
            }
            resultModules
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var assistantHeader: some View {
        HStack(spacing: 8) {
            Text("SF")
                .font(.caption2.weight(.bold))
                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                .foregroundStyle(.white)
                .frame(width: 28, height: 28)
                .background(CompanionStyle.ink)
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                .accessibilityHidden(true)
            Text(sourceLabel ?? "Main Assistant")
                .font(.subheadline.weight(.semibold))
                .lineLimit(1)
            Spacer(minLength: 8)
            if message.isStreaming {
                HStack(spacing: 5) {
                    ProgressView()
                        .controlSize(.mini)
                    Text("正在回复")
                }
                .font(.caption2)
                .foregroundStyle(.secondary)
            } else if let createdAt = message.createdAt {
                Text(createdAt.formatted(date: .omitted, time: .shortened))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .dynamicTypeSize(...DynamicTypeSize.accessibility1)
    }

    private var messageText: some View {
        MarkdownText(
            message.text,
            isStreaming: message.isStreaming,
            presentation: message.role == .user ? .compact : .assistantResult
        )
            .accessibilityPrefix("chat.message.\(message.id).markdown")
            .font(.body)
            .lineSpacing(3)
            .textSelection(.enabled)
            .fixedSize(horizontal: false, vertical: true)
    }

    @ViewBuilder
    private var resultModules: some View {
        if !message.toolCalls.isEmpty {
            VStack(spacing: 8) {
                ForEach(message.toolCalls) { toolCall in
                    ToolCallCardView(
                        toolCall: toolCall,
                        expanded: Binding(
                            get: { expandedToolCallIDs.contains(toolCall.id) },
                            set: { isExpanded in
                                if isExpanded {
                                    expandedToolCallIDs.insert(toolCall.id)
                                } else {
                                    expandedToolCallIDs.remove(toolCall.id)
                                }
                            }
                        )
                    )
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }

        if !message.attachments.isEmpty {
            VStack(spacing: 8) {
                ForEach(message.attachments) { attachment in
                    AttachmentCardView(
                        sessionID: sessionID,
                        attachment: attachment,
                        messageCreatedAt: message.createdAt,
                        sourceLabel: sourceLabel,
                        sourceMessageSeq: message.remoteSeqNo,
                        sourceAgentID: sourceAgentID,
                        sourceSessionTitle: sourceSessionTitle,
                        store: attachmentStore,
                        onUnauthorized: onUnauthorized,
                        onSubmitSnapshot: onSubmitArtifactSnapshot
                    )
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var hasText: Bool {
        !message.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private func surfaceAccessibilityAnchor(identifier: String, label: String) -> some View {
        Color.clear
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(label)
            .accessibilityIdentifier(identifier)
            .allowsHitTesting(false)
    }

}

private struct ChatTurnLayout: Layout {
    let isTrailing: Bool
    let widthFraction: CGFloat

    func sizeThatFits(
        proposal: ProposedViewSize,
        subviews: Subviews,
        cache: inout ()
    ) -> CGSize {
        guard let subview = subviews.first else { return .zero }
        let fallback = subview.sizeThatFits(.unspecified)
        let availableWidth = proposal.width ?? fallback.width
        let contentWidth = max(0, availableWidth * widthFraction)
        let contentSize = subview.sizeThatFits(
            ProposedViewSize(width: contentWidth, height: proposal.height)
        )
        return CGSize(width: availableWidth, height: contentSize.height)
    }

    func placeSubviews(
        in bounds: CGRect,
        proposal: ProposedViewSize,
        subviews: Subviews,
        cache: inout ()
    ) {
        guard let subview = subviews.first else { return }
        let contentWidth = max(0, bounds.width * widthFraction)
        let contentProposal = ProposedViewSize(width: contentWidth, height: bounds.height)
        let contentSize = subview.sizeThatFits(contentProposal)
        let originX = isTrailing ? bounds.maxX - contentSize.width : bounds.minX
        subview.place(
            at: CGPoint(x: originX, y: bounds.minY),
            anchor: .topLeading,
            proposal: ProposedViewSize(width: contentWidth, height: contentSize.height)
        )
    }
}

private struct ToolCallCardView: View {
    let toolCall: ChatMessage.ToolCall
    @Binding var expanded: Bool

    private var presentation: ToolActivityPresentation {
        ToolActivityPresentationPolicy.resolve(toolCall)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack(spacing: 8) {
                statusIcon
                VStack(alignment: .leading, spacing: 2) {
                    Text(presentation.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(presentation.status == .error ? Color.red : Color.primary)
                        .lineLimit(1)
                    if let summary = presentation.summary {
                        Text(summary)
                            .font(.caption)
                            .foregroundStyle(presentation.status == .error ? Color.red : Color.secondary)
                            .lineLimit(2)
                    }
                }
                Spacer(minLength: 8)
                Button {
                    expanded.toggle()
                } label: {
                    Image(systemName: expanded ? "chevron.up" : "chevron.down")
                        .font(.caption.weight(.bold))
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
                .accessibilityLabel(expanded ? "收起详情" : presentation.disclosureLabel)
                .accessibilityIdentifier("chat.toolCall.\(toolCall.id).details")
            }

            if expanded {
                VStack(alignment: .leading, spacing: 9) {
                    rawDetail(label: "工具", value: presentation.rawName)
                    if let input = presentation.rawInput {
                        rawDetail(label: "输入", value: input)
                    }
                    if let output = presentation.rawOutput {
                        rawDetail(label: presentation.status == .error ? "错误" : "输出", value: output)
                    }
                }
                .padding(.top, 2)
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(backgroundColor)
        .overlay {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .stroke(borderColor, lineWidth: 1)
        }
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("chat.toolCall.\(toolCall.id)")
    }

    private func rawDetail(label: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.secondary)
            Text(value)
                .font(.system(.caption, design: .monospaced))
                .foregroundStyle(.primary)
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private var statusIcon: some View {
        switch toolCall.status {
        case .pending:
            ProgressView()
                .controlSize(.small)
                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                .frame(width: 24, height: 24)
        case .success:
            Image(systemName: "checkmark.circle.fill")
                .font(.title3)
                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                .foregroundStyle(.green)
                .frame(width: 24, height: 24)
        case .error:
            Image(systemName: "exclamationmark.circle.fill")
                .font(.title3)
                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                .foregroundStyle(.red)
                .frame(width: 24, height: 24)
        }
    }

    private var backgroundColor: Color {
        switch toolCall.status {
        case .pending: Color.blue.opacity(0.06)
        case .success: Color.green.opacity(0.06)
        case .error: Color.red.opacity(0.07)
        }
    }

    private var borderColor: Color {
        switch toolCall.status {
        case .pending:
            return Color.blue.opacity(0.22)
        case .success:
            return Color.green.opacity(0.25)
        case .error:
            return Color.red.opacity(0.28)
        }
    }
}
