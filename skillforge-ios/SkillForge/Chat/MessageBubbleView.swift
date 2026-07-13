import SwiftUI

struct MessageBubbleView: View {
    let message: ChatMessage
    let sessionID: String
    @ObservedObject var attachmentStore: AttachmentDownloadStore
    @Binding var expandedToolCallIDs: Set<String>
    let onUnauthorized: @MainActor () -> Void

    var body: some View {
        HStack(alignment: .bottom) {
            if message.role == .user { Spacer(minLength: 44) }
            VStack(alignment: message.role == .user ? .trailing : .leading, spacing: 8) {
                if !message.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    MarkdownText(message.text, isStreaming: message.isStreaming)
                        .font(.body)
                        .lineSpacing(3)
                        .textSelection(.enabled)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 11)
                        .foregroundStyle(message.role == .user ? .white : .primary)
                        .background(message.role == .user ? Color(red: 0.29, green: 0.55, blue: 1.0) : .white)
                        .overlay {
                            if message.role == .assistant {
                                RoundedRectangle(cornerRadius: 17, style: .continuous)
                                    .stroke(Color.black.opacity(0.06), lineWidth: 1)
                            }
                        }
                        .clipShape(
                            UnevenRoundedRectangle(
                                topLeadingRadius: 17,
                                bottomLeadingRadius: message.role == .assistant ? 5 : 17,
                                bottomTrailingRadius: message.role == .user ? 5 : 17,
                                topTrailingRadius: 17,
                                style: .continuous
                            )
                        )
                        .shadow(color: Color.black.opacity(0.07), radius: 12, x: 0, y: 4)
                }

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
                }

                if !message.attachments.isEmpty {
                    VStack(spacing: 8) {
                        ForEach(message.attachments) { attachment in
                            AttachmentCardView(
                                sessionID: sessionID,
                                attachment: attachment,
                                store: attachmentStore,
                                onUnauthorized: onUnauthorized
                            )
                        }
                    }
                }
            }
            .frame(maxWidth: 340, alignment: message.role == .user ? .trailing : .leading)
            if message.role == .assistant { Spacer(minLength: 44) }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("chat.message.\(message.id)")
    }
}

private struct ToolCallCardView: View {
    let toolCall: ChatMessage.ToolCall
    @Binding var expanded: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack(spacing: 8) {
                statusIcon
                VStack(alignment: .leading, spacing: 2) {
                    Text(toolCall.name)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    Text(statusText)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 8)
            }

            if !toolCall.inputPreview.isEmpty {
                Text(toolCall.inputPreview)
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(.secondary)
                    .lineLimit(expanded ? nil : 2)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if let output = toolCall.output, !output.isEmpty {
                DisclosureGroup(isExpanded: $expanded) {
                    Text(output)
                        .font(.footnote)
                        .foregroundStyle(.primary)
                        .textSelection(.enabled)
                        .padding(.top, 4)
                } label: {
                    Text(expanded ? "隐藏结果" : "查看结果")
                        .font(.caption.weight(.medium))
                }
            }
        }
        .padding(12)
        .background(Color(red: 0.96, green: 0.97, blue: 0.98))
        .overlay {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .stroke(borderColor, lineWidth: 1)
        }
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    @ViewBuilder
    private var statusIcon: some View {
        switch toolCall.status {
        case .pending:
            ProgressView()
                .controlSize(.small)
                .frame(width: 24, height: 24)
        case .success:
            Image(systemName: "checkmark.circle.fill")
                .font(.title3)
                .foregroundStyle(.green)
                .frame(width: 24, height: 24)
        case .error:
            Image(systemName: "exclamationmark.circle.fill")
                .font(.title3)
                .foregroundStyle(.red)
                .frame(width: 24, height: 24)
        }
    }

    private var statusText: String {
        switch toolCall.status {
        case .pending:
            return "工具调用中"
        case .success:
            return "工具调用完成"
        case .error:
            return "工具调用失败"
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
