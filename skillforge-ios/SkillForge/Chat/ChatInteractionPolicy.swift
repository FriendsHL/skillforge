import Foundation

enum ChatBottomScrollAction: Equatable {
    case dismissKeyboardThenScroll
    case scrollImmediately

    var shouldDismissKeyboard: Bool {
        self == .dismissKeyboardThenScroll
    }

    var shouldScrollImmediately: Bool {
        self == .scrollImmediately
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
            : .scrollImmediately
    }

    static func shouldAutoScroll(
        isComposerFocused: Bool,
        isKeyboardVisible: Bool,
        isKeyboardSettling: Bool
    ) -> Bool {
        !isComposerFocused && !isKeyboardVisible && !isKeyboardSettling
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

struct ChatTranscriptPolicy {
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
