import XCTest
@testable import SkillForge

final class ChatInteractionPolicyTests: XCTestCase {
    func testOnlyActiveStreamingTailReceivesStableIdentity() {
        let history = ChatMessage(id: "remote-1", role: .user, text: "Build a report")
        let streaming = ChatMessage(
            id: "streaming-session",
            role: .assistant,
            text: "A long transient answer",
            isStreaming: true
        )
        let persisted = ChatMessage(id: "remote-2", role: .assistant, text: "Persisted answer")

        let regularRows = ChatTranscriptRow.rows(
            for: [history, persisted],
            stabilizeStreamingTail: false
        )
        let streamingRows = ChatTranscriptRow.rows(
            for: [history, streaming],
            stabilizeStreamingTail: true
        )
        let handoffRows = ChatTranscriptRow.rows(
            for: [history, persisted],
            stabilizeStreamingTail: true
        )

        XCTAssertEqual(regularRows.map(\.id), [history.id, persisted.id])
        XCTAssertEqual(streamingRows.map(\.id), [history.id, ChatTranscriptRow.streamingTailID])
        XCTAssertEqual(handoffRows.map(\.id), [history.id, ChatTranscriptRow.streamingTailID])
    }

    func testBottomButtonDismissesKeyboardBeforeScrollingWhenComposerFocused() {
        let action = ChatScrollPolicy.bottomButtonAction(
            isComposerFocused: true,
            isKeyboardVisible: false,
            isKeyboardSettling: false
        )

        XCTAssertEqual(action, .dismissKeyboardThenScroll)
        XCTAssertTrue(action.shouldDismissKeyboard)
        XCTAssertFalse(action.shouldScrollImmediately)
    }

    func testBottomButtonScrollsImmediatelyWhenComposerIsNotFocused() {
        let action = ChatScrollPolicy.bottomButtonAction(
            isComposerFocused: false,
            isKeyboardVisible: false,
            isKeyboardSettling: false
        )

        XCTAssertEqual(action, .scrollImmediately)
        XCTAssertFalse(action.shouldDismissKeyboard)
        XCTAssertTrue(action.shouldScrollImmediately)
    }

    func testBottomButtonWaitsForKeyboardWhenFocusWasAlreadyCleared() {
        let action = ChatScrollPolicy.bottomButtonAction(
            isComposerFocused: false,
            isKeyboardVisible: true,
            isKeyboardSettling: true
        )

        XCTAssertEqual(action, .dismissKeyboardThenScroll)
    }

    func testComposerSendIsDisabledForBlankOrSendingState() {
        XCTAssertFalse(ChatComposerPolicy.canSend(text: "   ", isSending: false))
        XCTAssertFalse(ChatComposerPolicy.canSend(text: "hello", isSending: true))
        XCTAssertTrue(ChatComposerPolicy.canSend(text: "hello", isSending: false))
        XCTAssertTrue(ChatComposerPolicy.canSend(
            text: "",
            attachmentCount: 1,
            isSending: false,
            isUploading: false
        ))
        XCTAssertFalse(ChatComposerPolicy.canSend(
            text: "hello",
            attachmentCount: 0,
            isSending: false,
            isUploading: true
        ))
    }

    func testAcceptedSendClearsDraftAndFailureDoesNotOverwriteNewTyping() {
        XCTAssertEqual(ChatComposerPolicy.draftAfterSendAccepted(), "")
        XCTAssertEqual(
            ChatComposerPolicy.draftAfterSendFailed(
                currentDraft: "",
                submittedDraft: "  keep the spacing  "
            ),
            "  keep the spacing  "
        )
        XCTAssertEqual(
            ChatComposerPolicy.draftAfterSendFailed(
                currentDraft: "new follow-up",
                submittedDraft: "old request"
            ),
            "new follow-up"
        )
    }

    func testTranscriptContainerIdentityOnlyChangesWithSession() {
        let original = ChatTranscriptPolicy.containerIdentity(sessionID: "session-1")

        for _ in 0..<20 {
            XCTAssertEqual(
                ChatTranscriptPolicy.containerIdentity(sessionID: "session-1"),
                original
            )
        }
        XCTAssertNotEqual(
            ChatTranscriptPolicy.containerIdentity(sessionID: "session-2"),
            original
        )
    }

    func testSessionRefreshKeepsCurrentSelectionWhenListTemporarilyOmitsIt() {
        XCTAssertEqual(
            ChatTranscriptPolicy.sessionIDAfterRefresh(
                currentSessionID: "running-session",
                loadedSessionIDs: []
            ),
            "running-session"
        )
        XCTAssertEqual(
            ChatTranscriptPolicy.sessionIDAfterRefresh(
                currentSessionID: "running-session",
                loadedSessionIDs: ["other-session"]
            ),
            "running-session"
        )
        XCTAssertEqual(
            ChatTranscriptPolicy.sessionIDAfterRefresh(
                currentSessionID: nil,
                loadedSessionIDs: ["first-session", "second-session"]
            ),
            "first-session"
        )
    }

    func testAutoScrollWaitsWhileComposerIsFocusedOrKeyboardIsSettling() {
        XCTAssertFalse(ChatScrollPolicy.shouldAutoScroll(
            isComposerFocused: true,
            isKeyboardVisible: false,
            isKeyboardSettling: false
        ))
        XCTAssertFalse(ChatScrollPolicy.shouldAutoScroll(
            isComposerFocused: false,
            isKeyboardVisible: true,
            isKeyboardSettling: false
        ))
        XCTAssertFalse(ChatScrollPolicy.shouldAutoScroll(
            isComposerFocused: false,
            isKeyboardVisible: false,
            isKeyboardSettling: true
        ))
        XCTAssertTrue(ChatScrollPolicy.shouldAutoScroll(
            isComposerFocused: false,
            isKeyboardVisible: false,
            isKeyboardSettling: false
        ))
    }

    func testRemoteEmptySnapshotDoesNotBlankRunningTranscript() {
        let current = [ChatMessage(id: "optimistic", role: .user, text: "继续任务")]

        let reconciled = ChatTranscriptPolicy.reconcileRemoteSnapshot(
            current: current,
            remote: [],
            preserveVisibleTranscript: true
        )

        XCTAssertEqual(reconciled, current)
    }

    func testRemoteEmptySnapshotCanReplaceTranscriptOutsideRunningCatchUp() {
        let current = [ChatMessage(id: "old", role: .assistant, text: "旧消息")]

        let reconciled = ChatTranscriptPolicy.reconcileRemoteSnapshot(
            current: current,
            remote: [],
            preserveVisibleTranscript: false
        )

        XCTAssertTrue(reconciled.isEmpty)
    }

    func testNonemptyRemoteSnapshotAlwaysBecomesAuthoritative() {
        let current = [ChatMessage(id: "optimistic", role: .user, text: "继续任务")]
        let remote = [
            ChatMessage(id: "remote-1", role: .user, text: "继续任务"),
            ChatMessage(id: "remote-2", role: .assistant, text: "正在处理")
        ]

        let reconciled = ChatTranscriptPolicy.reconcileRemoteSnapshot(
            current: current,
            remote: remote,
            preserveVisibleTranscript: true
        )

        XCTAssertEqual(reconciled, remote)
    }

    func testLaggingNonemptySnapshotDoesNotReplaceOptimisticMessage() {
        let current = [
            ChatMessage(id: "remote-old", role: .assistant, text: "旧回复"),
            ChatMessage(id: "optimistic", role: .user, text: "新任务")
        ]
        let laggingRemote = [
            ChatMessage(id: "remote-old", role: .assistant, text: "旧回复")
        ]

        let reconciled = ChatTranscriptPolicy.reconcileRemoteSnapshot(
            current: current,
            remote: laggingRemote,
            preserveVisibleTranscript: false,
            minimumExpectedRemoteCount: 2
        )

        XCTAssertEqual(reconciled, current)
    }

    func testCaughtUpSnapshotReplacesOptimisticMessage() {
        let current = [ChatMessage(id: "optimistic", role: .user, text: "新任务")]
        let caughtUpRemote = [ChatMessage(id: "remote-user", role: .user, text: "新任务")]

        let reconciled = ChatTranscriptPolicy.reconcileRemoteSnapshot(
            current: current,
            remote: caughtUpRemote,
            preserveVisibleTranscript: false,
            minimumExpectedRemoteCount: 1
        )

        XCTAssertEqual(reconciled, caughtUpRemote)
    }

    func testRejectsSnapshotOlderThanHighestAppliedSequence() {
        XCTAssertFalse(ChatTranscriptPolicy.shouldApplyRemoteSnapshot(
            highestAppliedSequence: 42,
            incomingHighestSequence: 41
        ))
        XCTAssertTrue(ChatTranscriptPolicy.shouldApplyRemoteSnapshot(
            highestAppliedSequence: 42,
            incomingHighestSequence: 42
        ))
        XCTAssertTrue(ChatTranscriptPolicy.shouldApplyRemoteSnapshot(
            highestAppliedSequence: 42,
            incomingHighestSequence: 43
        ))
        XCTAssertFalse(ChatTranscriptPolicy.shouldApplyRemoteSnapshot(
            highestAppliedSequence: 42,
            incomingHighestSequence: nil
        ))
    }
}
