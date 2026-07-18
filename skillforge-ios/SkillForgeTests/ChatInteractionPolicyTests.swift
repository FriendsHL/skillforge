import XCTest
@testable import SkillForge

final class ChatInteractionPolicyTests: XCTestCase {
    func testScrollFollowPausesOnlyForUpwardReadingAndResumesAtRealBottom() {
        var state = ChatScrollFollowState.initial

        state = ChatScrollFollowPolicy.reduce(state, event: .userDragged(verticalTranslation: -18))
        XCTAssertEqual(state.mode, .following, "Dragging toward newer content must not pause follow")

        state = ChatScrollFollowPolicy.reduce(state, event: .userDragged(verticalTranslation: 24))
        XCTAssertEqual(state.mode, .pausedByUser)

        state = ChatScrollFollowPolicy.reduce(state, event: .geometryChanged(bottomDistance: 80))
        XCTAssertEqual(state.mode, .pausedByUser)
        state = ChatScrollFollowPolicy.reduce(state, event: .geometryChanged(bottomDistance: 12))
        XCTAssertEqual(state, .initial, "Returning to the real bottom resumes and clears unread")
    }

    func testPausedFollowCountsAnAssistantTurnOnceUntilNextTurn() {
        var state = ChatScrollFollowState(mode: .pausedByUser, unreadTurnCount: 0, countedTurnID: nil)

        state = ChatScrollFollowPolicy.reduce(state, event: .assistantContent(turnID: "turn-1"))
        state = ChatScrollFollowPolicy.reduce(state, event: .assistantContent(turnID: "turn-1"))
        XCTAssertEqual(state.unreadTurnCount, 1)

        state = ChatScrollFollowPolicy.reduce(state, event: .assistantContent(turnID: "turn-2"))
        XCTAssertEqual(state.unreadTurnCount, 2)
        XCTAssertEqual(ChatScrollFollowPolicy.bottomButtonLabel(for: state), "有 2 条新消息，滚动到最新消息")

        state = ChatScrollFollowPolicy.reduce(state, event: .bottomButtonTapped)
        XCTAssertEqual(state, .initial)
    }

    func testAssistantActivityUsesOneStableRowAndClearsAtTerminalBoundaries() {
        XCTAssertEqual(
            AssistantActivityPresentationPolicy.resolve(
                sendAccepted: true,
                isRuntimeRunning: true,
                hasText: false,
                hasPendingTool: false,
                isWaitingForUser: false
            ),
            .running
        )
        XCTAssertEqual(
            AssistantActivityPresentationPolicy.resolve(
                sendAccepted: true,
                isRuntimeRunning: true,
                hasText: false,
                hasPendingTool: true,
                isWaitingForUser: false
            ),
            .usingTool
        )
        XCTAssertEqual(
            AssistantActivityPresentationPolicy.resolve(
                sendAccepted: true,
                isRuntimeRunning: true,
                hasText: true,
                hasPendingTool: true,
                isWaitingForUser: false
            ),
            .hidden
        )
        XCTAssertEqual(
            AssistantActivityPresentationPolicy.resolve(
                sendAccepted: true,
                isRuntimeRunning: true,
                hasText: false,
                hasPendingTool: false,
                isWaitingForUser: true
            ),
            .hidden
        )
    }
    func testAgentFirstMessageWidthPolicyKeepsUserCompactAndAssistantFullWidth() {
        XCTAssertEqual(
            ChatMessageLayoutPolicy.widthFraction(isUser: false, isAccessibilitySize: false),
            1
        )
        XCTAssertEqual(
            ChatMessageLayoutPolicy.widthFraction(
                isUser: true,
                text: "Short question",
                isAccessibilitySize: false
            ),
            0.76,
            accuracy: 0.001
        )
        XCTAssertEqual(
            ChatMessageLayoutPolicy.widthFraction(isUser: true, isAccessibilitySize: true),
            1,
            "Accessibility sizes may use the full row to avoid unreadably narrow user text"
        )
        XCTAssertEqual(
            ChatMessageLayoutPolicy.widthFraction(
                isUser: true,
                text: String(repeating: "中", count: 80),
                isAccessibilitySize: false
            ),
            0.86,
            accuracy: 0.001
        )
        XCTAssertEqual(
            ChatMessageLayoutPolicy.widthFraction(
                isUser: true,
                text: String(repeating: "长", count: 200),
                isAccessibilitySize: false
            ),
            0.92,
            accuracy: 0.001
        )
    }

    func testChatHeaderUsesSessionIdentityAndAgentRuntimeSubtitle() {
        let header = ChatHeaderPresentationPolicy.resolve(
            sessionTitle: "  iOS 移动体验优化  ",
            fallbackTitle: "Main Assistant",
            agentName: "Research Agent",
            status: ChatStatusPresentation(
                title: "Agent 运行中",
                detail: nil,
                failureCode: nil,
                semantic: .running,
                symbolName: "bolt.fill",
                canRetry: false
            )
        )

        XCTAssertEqual(header.title, "iOS 移动体验优化")
        XCTAssertEqual(header.agentName, "Research Agent")
        XCTAssertEqual(header.statusTitle, "运行中")
        XCTAssertEqual(header.accessibilityValue, "iOS 移动体验优化，Research Agent，运行中")
    }

    func testChatHeaderFallsBackWithoutInventingSessionTitle() {
        let header = ChatHeaderPresentationPolicy.resolve(
            sessionTitle: " \n ",
            fallbackTitle: "Main Assistant",
            agentName: "Main Assistant",
            status: ChatStatusPresentation(
                title: "已连接",
                detail: nil,
                failureCode: nil,
                semantic: .connected,
                symbolName: "checkmark.circle.fill",
                canRetry: false
            )
        )

        XCTAssertEqual(header.title, "Main Assistant")
        XCTAssertEqual(header.statusTitle, "已连接")
    }

    func testToolActivityPresentationHumanizesKnownToolsAndPreservesRawFacts() {
        let presentation = ToolActivityPresentationPolicy.resolve(ChatMessage.ToolCall(
            id: "tool-1",
            name: "PublishInteractiveArtifact",
            inputPreview: "title: AI 早报阅读器",
            output: "published artifact-1",
            status: .success
        ))

        XCTAssertEqual(presentation.title, "已发布 Personal App")
        XCTAssertEqual(presentation.summary, "published artifact-1")
        XCTAssertEqual(presentation.rawName, "PublishInteractiveArtifact")
        XCTAssertEqual(presentation.rawOutput, "published artifact-1")
        XCTAssertEqual(presentation.disclosureLabel, "查看结果")
        XCTAssertFalse(presentation.offersRetry)
    }

    func testToolActivityPresentationRecognizesWireFormatToolNames() {
        let presentation = ToolActivityPresentationPolicy.resolve(ChatMessage.ToolCall(
            id: "tool-wire",
            name: "publish_interactive_artifact",
            inputPreview: "title: Planner",
            output: nil,
            status: .pending
        ))

        XCTAssertEqual(presentation.title, "正在发布 Personal App")
        XCTAssertEqual(presentation.summary, "title: Planner")
    }

    func testToolActivityPresentationUsesSafeFallbackAndErrorSummary() {
        let presentation = ToolActivityPresentationPolicy.resolve(ChatMessage.ToolCall(
            id: "tool-2",
            name: "UnknownDangerousMutation",
            inputPreview: "{\"path\":\"workspace\"}",
            output: "workspace boundary denied",
            status: .error
        ))

        XCTAssertEqual(presentation.title, "工具执行失败")
        XCTAssertEqual(presentation.summary, "workspace boundary denied")
        XCTAssertEqual(presentation.disclosureLabel, "查看详情")
        XCTAssertFalse(presentation.offersRetry)
    }

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
        XCTAssertFalse(action.shouldScrollAuthoritatively)
    }

    func testBottomButtonUsesAuthoritativeScrollWhenKeyboardIsNotBlocking() {
        let action = ChatScrollPolicy.bottomButtonAction(
            isComposerFocused: false,
            isKeyboardVisible: false,
            isKeyboardSettling: false
        )

        XCTAssertEqual(action, .scrollAuthoritatively)
        XCTAssertFalse(action.shouldDismissKeyboard)
        XCTAssertTrue(action.shouldScrollAuthoritatively)
    }

    func testBottomButtonWaitsForKeyboardWhenFocusWasAlreadyCleared() {
        let action = ChatScrollPolicy.bottomButtonAction(
            isComposerFocused: false,
            isKeyboardVisible: true,
            isKeyboardSettling: true
        )

        XCTAssertEqual(action, .dismissKeyboardThenScroll)
    }

    func testReduceMotionDisablesRequestedScrollAnimation() {
        XCTAssertTrue(ChatScrollPolicy.shouldAnimate(
            requested: true,
            reduceMotionEnabled: false
        ))
        XCTAssertFalse(ChatScrollPolicy.shouldAnimate(
            requested: true,
            reduceMotionEnabled: true
        ))
        XCTAssertFalse(ChatScrollPolicy.shouldAnimate(
            requested: false,
            reduceMotionEnabled: false
        ))
    }

    func testBackgroundSceneCancelsPendingBottomRequest() {
        XCTAssertFalse(ChatScrollPolicy.shouldCancelPendingBottomRequest(isSceneActive: true))
        XCTAssertTrue(ChatScrollPolicy.shouldCancelPendingBottomRequest(isSceneActive: false))
    }

    func testDeferredBottomScrollOnlyRunsForCurrentActiveSession() {
        XCTAssertTrue(ChatScrollPolicy.shouldConsumeDeferredBottomScroll(
            requestSessionID: "session-1",
            activeSessionID: "session-1",
            isChatActive: true,
            isSceneActive: true
        ))
        XCTAssertFalse(ChatScrollPolicy.shouldConsumeDeferredBottomScroll(
            requestSessionID: "session-1",
            activeSessionID: "session-2",
            isChatActive: true,
            isSceneActive: true
        ))
        XCTAssertFalse(ChatScrollPolicy.shouldConsumeDeferredBottomScroll(
            requestSessionID: "session-1",
            activeSessionID: "session-1",
            isChatActive: false,
            isSceneActive: true
        ))
        XCTAssertFalse(ChatScrollPolicy.shouldConsumeDeferredBottomScroll(
            requestSessionID: "session-1",
            activeSessionID: "session-1",
            isChatActive: true,
            isSceneActive: false
        ))
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

    func testSessionAllViewDoesNotDropSessionsFromOtherAgents() {
        let sessions = [
            MobileSession(
                id: "main", userId: 1, agentId: 1, title: "Main",
                status: "active", runtimeStatus: "idle", messageCount: 1, updatedAt: nil
            ),
            MobileSession(
                id: "research", userId: 1, agentId: 2, title: "Research",
                status: "active", runtimeStatus: "idle", messageCount: 1, updatedAt: nil
            )
        ]

        XCTAssertEqual(
            ChatSessionVisibilityPolicy.visibleSessions(sessions).map(\.id),
            ["main", "research"]
        )
    }

    func testBottomScrollTargetsLastRealTranscriptContentInsteadOfTrailingSpacer() {
        XCTAssertEqual(
            ChatTranscriptPolicy.bottomScrollTargetID(
                messageRowIDs: ["message-1", "message-2"],
                pendingInteractionIDs: []
            ),
            "message-2"
        )
        XCTAssertEqual(
            ChatTranscriptPolicy.bottomScrollTargetID(
                messageRowIDs: ["message-1", "message-2"],
                pendingInteractionIDs: ["approval-1"]
            ),
            "skillforge.pending.approval-1"
        )
        XCTAssertNil(
            ChatTranscriptPolicy.bottomScrollTargetID(
                messageRowIDs: [],
                pendingInteractionIDs: []
            )
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

@MainActor
final class ChatBottomScrollCoordinatorTests: XCTestCase {
    func testRequestStopsActiveScrollingBeforeSwiftUIPositionsAtBottom() {
        let coordinator = ChatBottomScrollCoordinator(sessionID: "session-1")
        let recorder = BottomScrollEventRecorder()
        let driver = BottomScrollDriverSpy(recorder: recorder)
        coordinator.attach(driver)

        let request = coordinator.request(sessionID: "session-1") {
            recorder.events.append(.position("request"))
        }

        XCTAssertEqual(recorder.events, [.stopActiveScrolling, .position("request")])
        XCTAssertEqual(coordinator.completedRequestID, request?.id)
        XCTAssertFalse(coordinator.hasPendingRequest)
    }

    func testLatestRequestCoalescesWhileDriverIsUnavailable() {
        let coordinator = ChatBottomScrollCoordinator(sessionID: "session-1")
        let recorder = BottomScrollEventRecorder()
        let first = coordinator.request(sessionID: "session-1") {
            recorder.events.append(.position("stale"))
        }
        let latest = coordinator.request(sessionID: "session-1") {
            recorder.events.append(.position("latest"))
        }
        let driver = BottomScrollDriverSpy(recorder: recorder)

        coordinator.attach(driver)

        XCTAssertNotEqual(first?.id, latest?.id)
        XCTAssertEqual(recorder.events, [.stopActiveScrolling, .position("latest")])
        XCTAssertEqual(coordinator.completedRequestID, latest?.id)
    }

    func testSessionActivationInvalidatesPendingRequestAndRejectsStaleSession() {
        let coordinator = ChatBottomScrollCoordinator(sessionID: "session-1")
        let recorder = BottomScrollEventRecorder()
        _ = coordinator.request(sessionID: "session-1") {
            recorder.events.append(.position("stale"))
        }

        coordinator.activate(sessionID: "session-2")
        let driver = BottomScrollDriverSpy(recorder: recorder)
        coordinator.attach(driver)

        XCTAssertTrue(recorder.events.isEmpty)
        XCTAssertNil(coordinator.request(sessionID: "session-1") {})
        XCTAssertNotNil(coordinator.request(sessionID: "session-2") {
            recorder.events.append(.position("session-2"))
        })
        XCTAssertEqual(recorder.events, [.stopActiveScrolling, .position("session-2")])
    }

    func testCancellationPreventsLateDriverAttachmentFromExecutingRequest() {
        let coordinator = ChatBottomScrollCoordinator(sessionID: "session-1")
        let recorder = BottomScrollEventRecorder()
        _ = coordinator.request(sessionID: "session-1") {
            recorder.events.append(.position("cancelled"))
        }

        coordinator.cancelPendingRequest()
        let driver = BottomScrollDriverSpy(recorder: recorder)
        coordinator.attach(driver)

        XCTAssertTrue(recorder.events.isEmpty)
        XCTAssertFalse(coordinator.hasPendingRequest)
    }

    func testSessionChangeDuringPositionDoesNotCompleteStaleRequest() {
        let coordinator = ChatBottomScrollCoordinator(sessionID: "session-1")
        let recorder = BottomScrollEventRecorder()
        let driver = BottomScrollDriverSpy(recorder: recorder)
        coordinator.attach(driver)

        let request = coordinator.request(sessionID: "session-1") {
            recorder.events.append(.position("session-1"))
            coordinator.activate(sessionID: "session-2")
        }

        XCTAssertEqual(recorder.events, [.stopActiveScrolling, .position("session-1")])
        XCTAssertNotEqual(coordinator.completedRequestID, request?.id)
        XCTAssertEqual(coordinator.activeSessionID, "session-2")
        XCTAssertFalse(coordinator.hasPendingRequest)
    }

    func testRequestRemainsPendingUntilDriverCanStopActiveScrolling() {
        let coordinator = ChatBottomScrollCoordinator(sessionID: "session-1")
        let recorder = BottomScrollEventRecorder()
        let request = coordinator.request(sessionID: "session-1") {
            recorder.events.append(.position("delayed"))
        }

        XCTAssertTrue(coordinator.hasPendingRequest)
        XCTAssertNotEqual(coordinator.completedRequestID, request?.id)
        XCTAssertTrue(recorder.events.isEmpty)

        let driver = BottomScrollDriverSpy(recorder: recorder)
        coordinator.attach(driver)

        XCTAssertEqual(
            recorder.events,
            [.stopActiveScrolling, .position("delayed")]
        )
        XCTAssertEqual(coordinator.completedRequestID, request?.id)
        XCTAssertFalse(coordinator.hasPendingRequest)
    }

    func testUIKitDriverStoppingIdleScrollDoesNotJumpContentOffset() {
        let scrollView = UIScrollView(frame: CGRect(x: 0, y: 0, width: 320, height: 400))
        scrollView.contentSize = CGSize(width: 320, height: 1_000)
        scrollView.contentInset = UIEdgeInsets(top: 10, left: 0, bottom: 24, right: 0)
        scrollView.contentOffset = CGPoint(x: 0, y: 100)
        let driver = UIKitChatBottomScrollDriver(scrollView: scrollView)

        driver.stopActiveScrolling()

        XCTAssertEqual(scrollView.contentOffset.y, 100, accuracy: 0.5)
    }
}

@MainActor
private final class BottomScrollEventRecorder {
    enum Event: Equatable { case stopActiveScrolling, position(String) }
    var events: [Event] = []
}

@MainActor
private final class BottomScrollDriverSpy: ChatBottomScrollDriving {
    private let recorder: BottomScrollEventRecorder

    init(recorder: BottomScrollEventRecorder) {
        self.recorder = recorder
    }

    func stopActiveScrolling() {
        recorder.events.append(.stopActiveScrolling)
    }
}
