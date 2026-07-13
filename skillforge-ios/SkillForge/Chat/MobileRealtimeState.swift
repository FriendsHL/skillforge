import Foundation

struct MobileRealtimeState: Equatable {
    var streamingText = ""
    var streamingToolCalls: [ChatMessage.ToolCall] = []

    private var acceptedDeltaType: String?
    private var handoffSeqNo: Int64?

    mutating func applyTextDelta(type: String, chunk: String?) {
        guard let accepted = acceptedTextDelta(type: type, chunk: chunk) else { return }
        streamingText += accepted
    }

    mutating func acceptedTextDelta(type: String, chunk: String?) -> String? {
        guard let chunk, !chunk.isEmpty else { return nil }
        if let acceptedDeltaType {
            guard type == acceptedDeltaType else { return nil }
        } else {
            acceptedDeltaType = type
        }
        return chunk
    }

    mutating func beginStreaming(after sequence: Int64?) {
        guard handoffSeqNo == nil else { return }
        handoffSeqNo = sequence ?? -1
    }

    mutating func finishStreamSegment() {
        acceptedDeltaType = nil
    }

    mutating func appendBufferedText(_ text: String) {
        guard !text.isEmpty else { return }
        streamingText += text
    }

    mutating func upsertTool(
        id: String?,
        name: String?,
        inputPreview: String?,
        output: String?,
        status: ChatMessage.ToolCall.Status
    ) {
        let resolvedId = id ?? "tool-\(streamingToolCalls.count + 1)"
        if let index = streamingToolCalls.firstIndex(where: { $0.id == resolvedId }) {
            if let name, !name.isEmpty {
                streamingToolCalls[index].name = name
            }
            if let inputPreview, !inputPreview.isEmpty {
                streamingToolCalls[index].inputPreview = inputPreview
            }
            if let output {
                streamingToolCalls[index].output = output
            }
            streamingToolCalls[index].status = status
            return
        }

        streamingToolCalls.append(ChatMessage.ToolCall(
            id: resolvedId,
            name: name ?? "tool",
            inputPreview: inputPreview ?? "",
            output: output,
            status: status
        ))
    }

    mutating func applyRemoteRefresh(
        remoteMessages: [ChatMessage],
        pendingText: String = "",
        clearStreamingText: Bool,
        clearToolCalls: Bool
    ) {
        appendBufferedText(pendingText)
        if clearStreamingText {
            consumePersistedStreamingText(from: remoteMessages)
        }
        if clearToolCalls {
            let remoteToolIds = Set(remoteMessages.flatMap { $0.toolCalls.map(\.id) })
            if !remoteToolIds.isEmpty {
                streamingToolCalls.removeAll { remoteToolIds.contains($0.id) }
            }
        }
    }

    mutating func reset() {
        streamingText = ""
        streamingToolCalls = []
        acceptedDeltaType = nil
        handoffSeqNo = nil
    }

    private mutating func consumePersistedStreamingText(from remoteMessages: [ChatMessage]) {
        guard var consumedThrough = handoffSeqNo else { return }
        let candidates = remoteMessages
            .filter { ($0.remoteSeqNo ?? Int64.min) > consumedThrough && $0.role == .assistant }
            .sorted { ($0.remoteSeqNo ?? Int64.min) < ($1.remoteSeqNo ?? Int64.min) }

        for message in candidates {
            guard let sequence = message.remoteSeqNo else { continue }
            guard let remoteText = message.visibleAssistantText else {
                consumedThrough = sequence
                continue
            }

            let currentText = streamingText.trimmingCharacters(in: .whitespacesAndNewlines)
            if currentText.isEmpty {
                consumedThrough = sequence
                continue
            }
            if remoteText == currentText || remoteText.hasPrefix(currentText) {
                streamingText = ""
                consumedThrough = sequence
                continue
            }
            guard currentText.hasPrefix(remoteText) else { break }
            streamingText = String(currentText.dropFirst(remoteText.count))
            consumedThrough = sequence
        }
        handoffSeqNo = consumedThrough
    }
}

private extension ChatMessage {
    var visibleAssistantText: String? {
        guard role == .assistant else { return nil }
        let visibleText = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return visibleText.isEmpty ? nil : visibleText
    }
}
