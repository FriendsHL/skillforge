import Foundation

enum ChatScrollFollowMode: Equatable {
    case following
    case pausedByUser
}

struct ChatScrollFollowState: Equatable {
    let mode: ChatScrollFollowMode
    let unreadTurnCount: Int
    let countedTurnID: String?

    static let initial = ChatScrollFollowState(
        mode: .following,
        unreadTurnCount: 0,
        countedTurnID: nil
    )
}

enum ChatScrollFollowEvent: Equatable {
    case userDragged(verticalTranslation: CGFloat)
    case geometryChanged(bottomDistance: CGFloat)
    case assistantContent(turnID: String)
    case bottomButtonTapped
    case sessionChanged
}

enum ChatScrollFollowPolicy {
    static let nearBottomThreshold: CGFloat = 28
    // Dragging the finger downward (positive translation) reveals older
    // transcript content above the current viewport.
    static let historyReadingDragThreshold: CGFloat = 8

    static func reduce(
        _ state: ChatScrollFollowState,
        event: ChatScrollFollowEvent
    ) -> ChatScrollFollowState {
        switch event {
        case let .userDragged(verticalTranslation):
            guard verticalTranslation >= historyReadingDragThreshold else { return state }
            return ChatScrollFollowState(
                mode: .pausedByUser,
                unreadTurnCount: state.unreadTurnCount,
                countedTurnID: state.countedTurnID
            )
        case let .geometryChanged(bottomDistance):
            return bottomDistance <= nearBottomThreshold ? .initial : state
        case let .assistantContent(turnID):
            guard state.mode == .pausedByUser, state.countedTurnID != turnID else { return state }
            return ChatScrollFollowState(
                mode: .pausedByUser,
                unreadTurnCount: state.unreadTurnCount + 1,
                countedTurnID: turnID
            )
        case .bottomButtonTapped, .sessionChanged:
            return .initial
        }
    }

    static func bottomButtonLabel(for state: ChatScrollFollowState) -> String {
        guard state.unreadTurnCount > 0 else { return "滚动到最新消息" }
        return "有 \(state.unreadTurnCount) 条新消息，滚动到最新消息"
    }
}

enum AssistantActivityPresentation: Equatable {
    case hidden
    case running
    case usingTool

    var accessibilityLabel: String {
        switch self {
        case .hidden: ""
        case .running: "正在运行"
        case .usingTool: "正在使用工具"
        }
    }
}

enum AssistantActivityPresentationPolicy {
    static func resolve(
        sendAccepted: Bool,
        isRuntimeRunning: Bool,
        hasText: Bool,
        hasPendingTool: Bool,
        isWaitingForUser: Bool
    ) -> AssistantActivityPresentation {
        guard sendAccepted, isRuntimeRunning, !hasText, !isWaitingForUser else { return .hidden }
        return hasPendingTool ? .usingTool : .running
    }
}

enum ChatBottomScrollAction: Equatable {
    case dismissKeyboardThenScroll
    case scrollAuthoritatively

    var shouldDismissKeyboard: Bool {
        self == .dismissKeyboardThenScroll
    }

    var shouldScrollAuthoritatively: Bool {
        self == .scrollAuthoritatively
    }
}

struct ChatScrollPolicy {
    static let keyboardDismissFallbackNanoseconds: UInt64 = 750_000_000

    static func bottomButtonAction(
        isComposerFocused: Bool,
        isKeyboardVisible: Bool,
        isKeyboardSettling: Bool
    ) -> ChatBottomScrollAction {
        isComposerFocused || isKeyboardVisible || isKeyboardSettling
            ? .dismissKeyboardThenScroll
            : .scrollAuthoritatively
    }

    static func shouldAutoScroll(
        isComposerFocused: Bool,
        isKeyboardVisible: Bool,
        isKeyboardSettling: Bool
    ) -> Bool {
        !isComposerFocused && !isKeyboardVisible && !isKeyboardSettling
    }

    static func shouldAnimate(requested: Bool, reduceMotionEnabled: Bool) -> Bool {
        requested && !reduceMotionEnabled
    }

    static func shouldCancelPendingBottomRequest(isSceneActive: Bool) -> Bool {
        !isSceneActive
    }

    static func shouldConsumeDeferredBottomScroll(
        requestSessionID: String,
        activeSessionID: String?,
        isChatActive: Bool,
        isSceneActive: Bool
    ) -> Bool {
        isChatActive
            && isSceneActive
            && requestSessionID == activeSessionID
    }
}

struct ChatComposerPolicy {
    static func canSend(
        text: String,
        attachmentCount: Int = 0,
        isSending: Bool,
        isUploading: Bool = false
    ) -> Bool {
        guard !isSending, !isUploading else { return false }
        return !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            || attachmentCount > 0
    }

    static func draftAfterSendAccepted() -> String {
        ""
    }

    static func draftAfterSendFailed(currentDraft: String, submittedDraft: String) -> String {
        currentDraft.isEmpty ? submittedDraft : currentDraft
    }
}

struct ChatMessageLayoutPolicy {
    static func widthFraction(
        isUser: Bool,
        text: String = "",
        isAccessibilitySize: Bool
    ) -> CGFloat {
        guard isUser else { return 1 }
        guard !isAccessibilitySize else { return 1 }
        let visibleLength = text
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .count
        if visibleLength <= 40 { return 0.76 }
        if visibleLength <= 160 { return 0.86 }
        return 0.92
    }
}

struct ChatHeaderPresentation: Equatable {
    let title: String
    let agentName: String
    let statusTitle: String
    let semantic: ChatStatusSemantic
    let symbolName: String

    var accessibilityValue: String {
        "\(title)，\(agentName)，\(statusTitle)"
    }
}

enum ChatHeaderPresentationPolicy {
    static func resolve(
        sessionTitle: String?,
        fallbackTitle: String,
        agentName: String,
        status: ChatStatusPresentation
    ) -> ChatHeaderPresentation {
        let normalizedTitle = sessionTitle?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return ChatHeaderPresentation(
            title: normalizedTitle.flatMap { $0.isEmpty ? nil : $0 } ?? fallbackTitle,
            agentName: agentName,
            statusTitle: compactStatusTitle(status),
            semantic: status.semantic,
            symbolName: status.symbolName
        )
    }

    private static func compactStatusTitle(_ status: ChatStatusPresentation) -> String {
        switch status.semantic {
        case .running: "运行中"
        case .waiting: "等待确认"
        case .error: "运行失败"
        case .cancelled: "已取消"
        case .connected: "已连接"
        case .neutral: status.title
        }
    }
}

struct ToolActivityPresentation: Equatable {
    let title: String
    let summary: String?
    let rawName: String
    let rawInput: String?
    let rawOutput: String?
    let status: ChatMessage.ToolCall.Status
    let disclosureLabel: String
    let offersRetry: Bool
}

enum ToolActivityPresentationPolicy {
    static func resolve(_ toolCall: ChatMessage.ToolCall) -> ToolActivityPresentation {
        let rawInput = nonBlank(toolCall.inputPreview)
        let rawOutput = nonBlank(toolCall.output)
        let summary = switch toolCall.status {
        case .pending: rawInput ?? rawOutput
        case .success, .error: rawOutput ?? rawInput
        }
        return ToolActivityPresentation(
            title: title(name: toolCall.name, status: toolCall.status),
            summary: summary,
            rawName: toolCall.name,
            rawInput: rawInput,
            rawOutput: rawOutput,
            status: toolCall.status,
            disclosureLabel: toolCall.status == .success && rawOutput != nil ? "查看结果" : "查看详情",
            offersRetry: false
        )
    }

    private static func title(name: String, status: ChatMessage.ToolCall.Status) -> String {
        let action: (pending: String, success: String, error: String)
        switch name.lowercased() {
        case "publishinteractiveartifact", "publish_interactive_artifact":
            action = ("正在发布 Personal App", "已发布 Personal App", "Personal App 发布失败")
        case "publishchatartifact", "publish_chat_artifact":
            action = ("正在发布文件", "已发布文件", "文件发布失败")
        case "websearch", "web_search":
            action = ("正在检索资料", "已完成资料检索", "资料检索失败")
        default:
            action = ("正在执行工具", "工具执行完成", "工具执行失败")
        }
        return switch status {
        case .pending: action.pending
        case .success: action.success
        case .error: action.error
        }
    }

    private static func nonBlank(_ value: String?) -> String? {
        let normalized = value?.trimmingCharacters(in: .whitespacesAndNewlines)
        return normalized?.isEmpty == false ? normalized : nil
    }
}

struct ChatTranscriptPolicy {
    static let pendingInteractionTargetPrefix = "skillforge.pending."

    static func bottomScrollTargetID(
        messageRowIDs: [String],
        pendingInteractionIDs: [String]
    ) -> String? {
        if let pendingInteractionID = pendingInteractionIDs.last {
            return pendingInteractionTargetPrefix + pendingInteractionID
        }
        return messageRowIDs.last
    }

    static func containerIdentity(sessionID: String?) -> String {
        "skillforge.transcript.\(sessionID ?? "unselected")"
    }

    static func sessionIDAfterRefresh(
        currentSessionID: String?,
        loadedSessionIDs: [String]
    ) -> String? {
        currentSessionID ?? loadedSessionIDs.first
    }

    static func shouldApplyRemoteSnapshot(
        highestAppliedSequence: Int64?,
        incomingHighestSequence: Int64?
    ) -> Bool {
        guard let highestAppliedSequence else { return true }
        guard let incomingHighestSequence else { return false }
        return incomingHighestSequence >= highestAppliedSequence
    }

    static func reconcileRemoteSnapshot(
        current: [ChatMessage],
        remote: [ChatMessage],
        preserveVisibleTranscript: Bool,
        minimumExpectedRemoteCount: Int? = nil
    ) -> [ChatMessage] {
        if let minimumExpectedRemoteCount,
           remote.count < minimumExpectedRemoteCount,
           !current.isEmpty {
            return current
        }
        if preserveVisibleTranscript, remote.isEmpty, !current.isEmpty {
            return current
        }
        return remote
    }
}

enum ChatSessionVisibilityPolicy {
    static func visibleSessions(_ loaded: [MobileSession]) -> [MobileSession] {
        loaded
    }
}

struct ChatTranscriptRow: Identifiable, Equatable {
    static let streamingTailID = "skillforge.transcript.streaming-tail"

    let id: String
    let message: ChatMessage

    static func rows(for messages: [ChatMessage], stabilizeStreamingTail: Bool) -> [ChatTranscriptRow] {
        messages.enumerated().map { index, message in
            let isStabilizedTail = stabilizeStreamingTail
                && index == messages.index(before: messages.endIndex)
            return ChatTranscriptRow(
                id: isStabilizedTail ? streamingTailID : message.id,
                message: message
            )
        }
    }
}
