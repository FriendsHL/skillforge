import XCTest
@testable import SkillForge

final class MobileRealtimeStateTests: XCTestCase {
    func testSessionStatusImmediatelyAppliesFailureFactBeforeMetadataCatchUp() throws {
        let event = try decodeEvent("""
        {
          "type": "session_status",
          "sessionId": "session-1",
          "status": "error",
          "error": "Agent loop 状态异常",
          "failureSource": "harness",
          "failureCode": "LOOP_STATE",
          "retryable": false,
          "sideEffects": "possible"
        }
        """)

        let updated = MobileRuntimeSessionReducer.applying(event: event, to: session())

        XCTAssertEqual(updated.runtimeStatus, "error")
        XCTAssertEqual(updated.runtimeError, "Agent loop 状态异常")
        XCTAssertEqual(updated.failureSource, .harness)
        XCTAssertEqual(updated.failureCode, "LOOP_STATE")
        XCTAssertEqual(updated.retryable, false)
        XCTAssertEqual(updated.sideEffects, .possible)
    }

    func testSessionUpdatedAppliesTheSameStructuredFailureFactShape() throws {
        let event = try decodeEvent("""
        {
          "type": "session_updated",
          "sessionId": "session-1",
          "runtimeStatus": "error",
          "runtimeError": "用户确认超时",
          "failureSource": "user_action",
          "failureCode": "CONFIRMATION_TIMEOUT",
          "retryable": false,
          "sideEffects": "none"
        }
        """)

        let updated = MobileRuntimeSessionReducer.applying(event: event, to: session())

        XCTAssertEqual(updated.runtimeStatus, "error")
        XCTAssertEqual(updated.runtimeError, "用户确认超时")
        XCTAssertEqual(updated.failureSource, .userAction)
        XCTAssertEqual(updated.failureCode, "CONFIRMATION_TIMEOUT")
        XCTAssertEqual(updated.retryable, false)
        XCTAssertEqual(updated.sideEffects, .noEffects)
    }

    func testNonErrorRealtimeStatusClearsPreviousFailureFact() throws {
        let failed = session(
            runtimeStatus: "error",
            runtimeError: "old",
            failureSource: .tool,
            failureCode: "OLD_TOOL_ERROR",
            retryable: false,
            sideEffects: .observed
        )
        let running = try decodeEvent("""
        { "type": "session_status", "sessionId": "session-1", "status": "running" }
        """)

        let updated = MobileRuntimeSessionReducer.applying(event: running, to: failed)

        XCTAssertEqual(updated.runtimeStatus, "running")
        XCTAssertNil(updated.runtimeError)
        XCTAssertNil(updated.failureSource)
        XCTAssertNil(updated.failureCode)
        XCTAssertNil(updated.retryable)
        XCTAssertNil(updated.sideEffects)
    }

    func testBackendIdleCancelledStepIsPreservedAndClearedByNextNormalStatus() throws {
        let cancelledEvent = try decodeEvent("""
        {
          "type": "session_status",
          "sessionId": "session-1",
          "status": "idle",
          "step": "cancelled"
        }
        """)
        let cancelled = MobileRuntimeSessionReducer.applying(
            event: cancelledEvent,
            to: session(runtimeStatus: "running")
        )

        XCTAssertEqual(cancelled.runtimeStatus, "idle")
        XCTAssertEqual(cancelled.runtimeStep, "cancelled")
        XCTAssertNil(cancelled.runtimeError)
        XCTAssertNil(cancelled.failureSource)
        XCTAssertNil(cancelled.retryable)

        for nextEventJSON in [
            #"{ "type": "session_status", "sessionId": "session-1", "status": "running" }"#,
            #"{ "type": "session_status", "sessionId": "session-1", "status": "idle" }"#
        ] {
            let next = MobileRuntimeSessionReducer.applying(
                event: try decodeEvent(nextEventJSON),
                to: cancelled
            )
            XCTAssertNil(next.runtimeStep)
        }
    }

    func testTerminalCatchUpPreservesNewerWebSocketFactWhenRESTIsStale() throws {
        let event = try decodeEvent("""
        {
          "type": "session_status",
          "sessionId": "session-1",
          "status": "error",
          "error": "模型连接超时",
          "failureSource": "network",
          "failureCode": "FIRST_TOKEN_TIMEOUT",
          "retryable": true,
          "sideEffects": "none"
        }
        """)
        let websocket = MobileRuntimeSessionReducer.applying(event: event, to: session())
        let staleREST = session(runtimeStatus: "running")

        let reconciled = MobileRuntimeSessionReducer.reconcilingTerminalMetadata(
            current: websocket,
            incoming: staleREST,
            expectedRuntimeStatus: "error"
        )

        XCTAssertEqual(reconciled.runtimeStatus, "error")
        XCTAssertEqual(reconciled.runtimeError, "模型连接超时")
        XCTAssertEqual(reconciled.failureSource, .network)
        XCTAssertEqual(reconciled.retryable, true)
        XCTAssertEqual(reconciled.sideEffects, .noEffects)
    }

    func testTerminalCatchUpFillsMissingWebSocketFactFromRESTMetadata() throws {
        let legacyEvent = try decodeEvent("""
        {
          "type": "session_status",
          "sessionId": "session-1",
          "status": "error",
          "error": "运行异常"
        }
        """)
        let websocket = MobileRuntimeSessionReducer.applying(event: legacyEvent, to: session())
        let structuredREST = session(
            runtimeStatus: "error",
            runtimeError: "工具参数校验失败",
            failureSource: .tool,
            failureCode: "TOOL_VALIDATION",
            retryable: false,
            sideEffects: .noEffects
        )

        let reconciled = MobileRuntimeSessionReducer.reconcilingTerminalMetadata(
            current: websocket,
            incoming: structuredREST,
            expectedRuntimeStatus: "error"
        )

        XCTAssertEqual(reconciled.runtimeError, "工具参数校验失败")
        XCTAssertEqual(reconciled.failureSource, .tool)
        XCTAssertEqual(reconciled.failureCode, "TOOL_VALIDATION")
        XCTAssertEqual(reconciled.retryable, false)
        XCTAssertEqual(reconciled.sideEffects, .noEffects)
        XCTAssertTrue(RuntimeMetadataCatchUpPolicy.isReconciled(
            reconciled,
            expectedRuntimeStatus: "error"
        ))
    }

    func testTerminalCatchUpPolicyIsBoundedWithDeterministicDelaySchedule() {
        XCTAssertEqual(RuntimeMetadataCatchUpPolicy.maximumAttempts, 3)
        XCTAssertEqual(RuntimeMetadataCatchUpPolicy.delayNanoseconds(beforeAttempt: 0), 0)
        XCTAssertEqual(RuntimeMetadataCatchUpPolicy.delayNanoseconds(beforeAttempt: 1), 250_000_000)
        XCTAssertEqual(RuntimeMetadataCatchUpPolicy.delayNanoseconds(beforeAttempt: 2), 750_000_000)
        XCTAssertNil(RuntimeMetadataCatchUpPolicy.delayNanoseconds(beforeAttempt: 3))
        XCTAssertFalse(RuntimeMetadataCatchUpPolicy.isReconciled(
            session(runtimeStatus: "error", runtimeError: "generic"),
            expectedRuntimeStatus: "error"
        ))
    }

    func testTerminalCatchUpRequiresNonBlankFailureCodeAndReason() {
        let complete = session(
            runtimeStatus: "error",
            runtimeError: "模型连接超时",
            failureSource: .network,
            failureCode: "FIRST_TOKEN_TIMEOUT",
            retryable: true,
            sideEffects: .noEffects
        )
        XCTAssertTrue(RuntimeMetadataCatchUpPolicy.isReconciled(
            complete,
            expectedRuntimeStatus: "error"
        ))

        for incomplete in [
            session(
                runtimeStatus: "error",
                runtimeError: nil,
                failureSource: .network,
                failureCode: "FIRST_TOKEN_TIMEOUT",
                retryable: true,
                sideEffects: .noEffects
            ),
            session(
                runtimeStatus: "error",
                runtimeError: " \n ",
                failureSource: .network,
                failureCode: "FIRST_TOKEN_TIMEOUT",
                retryable: true,
                sideEffects: .noEffects
            ),
            session(
                runtimeStatus: "error",
                runtimeError: "模型连接超时",
                failureSource: .network,
                failureCode: nil,
                retryable: true,
                sideEffects: .noEffects
            ),
            session(
                runtimeStatus: "error",
                runtimeError: "模型连接超时",
                failureSource: .network,
                failureCode: "  ",
                retryable: true,
                sideEffects: .noEffects
            )
        ] {
            XCTAssertFalse(RuntimeMetadataCatchUpPolicy.isReconciled(
                incomplete,
                expectedRuntimeStatus: "error"
            ))
        }
    }

    func testCompleteWebSocketFailureFactNeedsZeroCatchUpAttempt() {
        let complete = session(
            runtimeStatus: "error",
            runtimeError: "模型连接超时",
            failureSource: .network,
            failureCode: "FIRST_TOKEN_TIMEOUT",
            retryable: true,
            sideEffects: .noEffects
        )
        let missingCode = session(
            runtimeStatus: "error",
            runtimeError: "模型连接超时",
            failureSource: .network,
            failureCode: nil,
            retryable: true,
            sideEffects: .noEffects
        )

        XCTAssertFalse(RuntimeMetadataCatchUpPolicy.shouldAttemptFetch(
            complete,
            expectedRuntimeStatus: "error"
        ))
        XCTAssertTrue(RuntimeMetadataCatchUpPolicy.shouldAttemptFetch(
            missingCode,
            expectedRuntimeStatus: "error"
        ))
    }

    func testTerminalCatchUpDoesNotDowngradeKnownFactWithUnknownOrBlankRESTFields() {
        let current = session(
            runtimeStatus: "error",
            runtimeError: "模型连接超时",
            failureSource: .network,
            failureCode: "FIRST_TOKEN_TIMEOUT",
            retryable: true,
            sideEffects: .noEffects
        )
        let malformedREST = session(
            runtimeStatus: "error",
            runtimeError: " \n ",
            failureSource: .unknown,
            failureCode: "  ",
            retryable: true,
            sideEffects: .unknown
        )

        let reconciled = MobileRuntimeSessionReducer.reconcilingTerminalMetadata(
            current: current,
            incoming: malformedREST,
            expectedRuntimeStatus: "error"
        )

        XCTAssertEqual(reconciled.runtimeError, "模型连接超时")
        XCTAssertEqual(reconciled.failureSource, .network)
        XCTAssertEqual(reconciled.failureCode, "FIRST_TOKEN_TIMEOUT")
        XCTAssertEqual(reconciled.sideEffects, .noEffects)
    }

    func testTerminalCatchUpStopsWhenNewerRealtimeStateLeavesExpectedTerminal() {
        XCTAssertTrue(RuntimeMetadataCatchUpPolicy.shouldContinue(
            session(runtimeStatus: "error"),
            expectedRuntimeStatus: "error"
        ))
        for newerStatus in ["running", "queued", "waiting_user", "idle"] {
            XCTAssertFalse(RuntimeMetadataCatchUpPolicy.shouldContinue(
                session(runtimeStatus: newerStatus),
                expectedRuntimeStatus: "error"
            ), "newerStatus=\(newerStatus)")
        }
    }

    func testLateTerminalRESTCannotRollbackNewerRunningWebSocketState() {
        let current = session(runtimeStatus: "running")
        let staleREST = session(
            runtimeStatus: "error",
            runtimeError: "stale failure",
            failureSource: .network,
            failureCode: "STALE_TIMEOUT",
            retryable: true,
            sideEffects: .noEffects
        )

        let reconciled = MobileRuntimeSessionReducer.reconcilingTerminalMetadata(
            current: current,
            incoming: staleREST,
            expectedRuntimeStatus: "error"
        )

        XCTAssertEqual(reconciled.runtimeStatus, "running")
        XCTAssertNil(reconciled.runtimeError)
        XCTAssertNil(reconciled.failureSource)
        XCTAssertNil(reconciled.retryable)
    }

    func testOrdinaryMetadataPolicyPreservesNewerRealtimeTerminalFactWhenLeaseIsStale() {
        let realtimeFailure = session(
            runtimeStatus: "error",
            runtimeError: "new websocket failure",
            failureSource: .network,
            failureCode: "FIRST_TOKEN_TIMEOUT",
            retryable: true,
            sideEffects: .noEffects
        )
        let staleREST = session(runtimeStatus: "running")

        let reconciled = MobileRuntimeSessionReducer.reconcilingOrdinaryMetadata(
            current: realtimeFailure,
            incoming: staleREST,
            allowsRuntimeReplacement: false
        )

        XCTAssertEqual(reconciled.runtimeStatus, "error")
        XCTAssertEqual(reconciled.runtimeError, "new websocket failure")
        XCTAssertEqual(reconciled.failureSource, .network)
        XCTAssertEqual(reconciled.failureCode, "FIRST_TOKEN_TIMEOUT")
        XCTAssertEqual(reconciled.retryable, true)
        XCTAssertEqual(reconciled.sideEffects, .noEffects)
    }

    func testOrdinaryRESTLeaseCannotOverwriteNewerRealtimeTerminalFact() throws {
        var authority = RuntimeMetadataAuthorityGate(sessionId: "session-1")
        let staleREST = try XCTUnwrap(authority.begin(
            .ordinaryREST,
            sessionId: "session-1"
        ))

        XCTAssertTrue(authority.recordRealtimeFact(sessionId: "session-1"))
        XCTAssertFalse(authority.consume(staleREST))
    }

    func testRetryAcceptanceLeaseCannotWriteRunningAfterNewerRealtimeTerminalFact() throws {
        var authority = RuntimeMetadataAuthorityGate(sessionId: "session-1")
        let retryAcceptance = try XCTUnwrap(authority.begin(
            .retryAcceptance,
            sessionId: "session-1"
        ))

        XCTAssertTrue(authority.recordRealtimeFact(sessionId: "session-1"))
        XCTAssertFalse(authority.consume(retryAcceptance))
    }

    func testLatestRuntimeMetadataLeaseWinsAndSessionSwitchInvalidatesPreviousSession() throws {
        var authority = RuntimeMetadataAuthorityGate(sessionId: "session-1")
        let older = try XCTUnwrap(authority.begin(.ordinaryREST, sessionId: "session-1"))
        let newer = try XCTUnwrap(authority.begin(.sessionList, sessionId: "session-1"))

        XCTAssertFalse(authority.consume(older))
        XCTAssertTrue(authority.consume(newer))

        let previousSession = try XCTUnwrap(authority.begin(
            .ordinaryREST,
            sessionId: "session-1"
        ))
        authority.reset(sessionId: "session-2")

        XCTAssertFalse(authority.consume(previousSession))
        XCTAssertNil(authority.begin(.ordinaryREST, sessionId: "session-1"))
        XCTAssertNotNil(authority.begin(.ordinaryREST, sessionId: "session-2"))
    }

    func testLocalRuntimeTransitionInvalidatesOutstandingMetadataLease() throws {
        var authority = RuntimeMetadataAuthorityGate(sessionId: "session-1")
        let staleREST = try XCTUnwrap(authority.begin(
            .ordinaryREST,
            sessionId: "session-1"
        ))

        XCTAssertTrue(authority.recordLocalTransition(sessionId: "session-1"))
        XCTAssertFalse(authority.consume(staleREST))
    }

    func testRealtimeFactForAnotherSessionCannotInvalidateCurrentSessionLease() throws {
        var authority = RuntimeMetadataAuthorityGate(sessionId: "session-1")
        let currentREST = try XCTUnwrap(authority.begin(
            .ordinaryREST,
            sessionId: "session-1"
        ))

        XCTAssertFalse(authority.recordRealtimeFact(sessionId: "session-2"))
        XCTAssertTrue(authority.consume(currentREST))
    }

    func testTerminalCatchUpCanReacquireAuthorityAfterNewerRealtimeFact() throws {
        var authority = RuntimeMetadataAuthorityGate(sessionId: "session-1")
        let staleCatchUp = try XCTUnwrap(authority.begin(
            .terminalCatchUp,
            sessionId: "session-1"
        ))
        XCTAssertTrue(authority.recordRealtimeFact(sessionId: "session-1"))
        XCTAssertFalse(authority.consume(staleCatchUp))

        let replacementCatchUp = try XCTUnwrap(authority.begin(
            .terminalCatchUp,
            sessionId: "session-1"
        ))
        XCTAssertTrue(authority.consume(replacementCatchUp))
    }

    func testIgnoresLegacyAssistantDeltaWhenTextDeltaCarriesSameChunk() {
        var state = MobileRealtimeState()

        state.applyTextDelta(type: "assistant_delta", chunk: "## 标题\n")
        state.applyTextDelta(type: "text_delta", chunk: "## 标题\n")

        XCTAssertEqual(state.streamingText, "## 标题\n")
    }

    func testKeepsConsecutiveIdenticalChunksFromCanonicalDeltaSource() {
        var state = MobileRealtimeState()

        state.applyTextDelta(type: "assistant_delta", chunk: "\n\n")
        state.applyTextDelta(type: "text_delta", chunk: "\n\n")
        state.applyTextDelta(type: "assistant_delta", chunk: "\n\n")
        state.applyTextDelta(type: "text_delta", chunk: "\n\n")

        XCTAssertEqual(state.streamingText, "\n\n\n\n")
    }

    func testKeepsTransientToolCallAcrossRemoteRefreshWithoutToolCall() {
        var state = MobileRealtimeState()
        state.upsertTool(id: "toolu_1", name: "ReadFile", inputPreview: "{\"path\":\"README.md\"}", output: nil, status: .pending)

        state.applyRemoteRefresh(
            remoteMessages: [
                ChatMessage(role: .assistant, text: "我先读取文件。")
            ],
            clearStreamingText: true,
            clearToolCalls: true
        )

        XCTAssertEqual(state.streamingToolCalls.count, 1)
        XCTAssertEqual(state.streamingToolCalls[0].id, "toolu_1")
    }

    func testKeepsStreamingTextAcrossRemoteRefreshWithoutAssistantText() {
        var state = MobileRealtimeState()
        state.applyTextDelta(type: "text_delta", chunk: "## 实时标题\n")

        state.applyRemoteRefresh(
            remoteMessages: [
                ChatMessage(role: .user, text: "整理一个方案")
            ],
            clearStreamingText: true,
            clearToolCalls: false
        )

        XCTAssertEqual(state.streamingText, "## 实时标题\n")
    }

    func testClearsStreamingTextWhenRemoteRefreshContainsAssistantText() {
        var state = MobileRealtimeState()
        state.beginStreaming(after: 3)
        state.applyTextDelta(type: "text_delta", chunk: "## 实时标题\n")

        state.applyRemoteRefresh(
            remoteMessages: [
                ChatMessage(role: .assistant, text: "## 实时标题\n完整内容", remoteSeqNo: 4)
            ],
            clearStreamingText: true,
            clearToolCalls: false
        )

        XCTAssertTrue(state.streamingText.isEmpty)
    }

    func testCommittedRemoteMessageConsumesPendingThrottledTextBeforeHandoff() {
        var state = MobileRealtimeState()
        state.beginStreaming(after: 10)
        state.applyTextDelta(type: "text_delta", chunk: "最终")

        state.applyRemoteRefresh(
            remoteMessages: [
                ChatMessage(role: .assistant, text: "最终回复", remoteSeqNo: 11)
            ],
            pendingText: "回复",
            clearStreamingText: true,
            clearToolCalls: false
        )

        XCTAssertTrue(state.streamingText.isEmpty)
    }

    func testOldIdenticalAssistantTextCannotConsumeCurrentTurn() {
        var state = MobileRealtimeState()
        state.beginStreaming(after: 20)
        state.applyTextDelta(type: "assistant_delta", chunk: "相同回复")

        state.applyRemoteRefresh(
            remoteMessages: [
                ChatMessage(role: .assistant, text: "相同回复", remoteSeqNo: 18)
            ],
            clearStreamingText: true,
            clearToolCalls: false
        )

        XCTAssertEqual(state.streamingText, "相同回复")
    }

    func testConsumesPersistedAssistantIterationsIncrementally() {
        var state = MobileRealtimeState()
        state.beginStreaming(after: 30)
        state.applyTextDelta(type: "assistant_delta", chunk: "正在检查")
        state.finishStreamSegment()
        state.applyTextDelta(type: "assistant_delta", chunk: "检查完成")

        state.applyRemoteRefresh(
            remoteMessages: [
                ChatMessage(role: .assistant, text: "正在检查", remoteSeqNo: 31)
            ],
            clearStreamingText: true,
            clearToolCalls: false
        )
        XCTAssertEqual(state.streamingText, "检查完成")

        state.applyRemoteRefresh(
            remoteMessages: [
                ChatMessage(role: .assistant, text: "正在检查", remoteSeqNo: 31),
                ChatMessage(role: .assistant, text: "检查完成", remoteSeqNo: 34)
            ],
            clearStreamingText: true,
            clearToolCalls: false
        )
        XCTAssertTrue(state.streamingText.isEmpty)
    }

    func testKeepsStreamingTextWhenRemoteRefreshOnlyContainsOlderAssistantText() {
        var state = MobileRealtimeState()
        state.applyTextDelta(type: "text_delta", chunk: "## 新任务\n")

        state.applyRemoteRefresh(
            remoteMessages: [
                ChatMessage(role: .user, text: "上一个任务"),
                ChatMessage(role: .assistant, text: "这是历史回复")
            ],
            clearStreamingText: true,
            clearToolCalls: false
        )

        XCTAssertEqual(state.streamingText, "## 新任务\n")
    }

    func testClearsTransientToolCallWhenRemoteRefreshContainsSameToolCall() {
        var state = MobileRealtimeState()
        state.upsertTool(id: "toolu_1", name: "ReadFile", inputPreview: "{\"path\":\"README.md\"}", output: nil, status: .pending)

        state.applyRemoteRefresh(
            remoteMessages: [
                ChatMessage(
                    role: .assistant,
                    text: "我先读取文件。",
                    toolCalls: [
                        ChatMessage.ToolCall(
                            id: "toolu_1",
                            name: "ReadFile",
                            inputPreview: "{\"path\":\"README.md\"}",
                            output: nil,
                            status: .pending
                        )
                    ]
                )
            ],
            clearStreamingText: true,
            clearToolCalls: true
        )

        XCTAssertTrue(state.streamingToolCalls.isEmpty)
    }

    private func decodeEvent(_ json: String) throws -> MobileChatEvent {
        try JSONDecoder().decode(MobileChatEvent.self, from: Data(json.utf8))
    }

    private func session(
        runtimeStatus: String = "idle",
        runtimeError: String? = nil,
        failureSource: MobileRuntimeFailureSource? = nil,
        failureCode: String? = nil,
        retryable: Bool? = nil,
        sideEffects: MobileRuntimeSideEffects? = nil
    ) -> MobileSession {
        MobileSession(
            id: "session-1",
            userId: 1,
            agentId: 2,
            title: "Runtime test",
            status: "active",
            runtimeStatus: runtimeStatus,
            runtimeError: runtimeError,
            failureSource: failureSource,
            failureCode: failureCode,
            retryable: retryable,
            sideEffects: sideEffects,
            messageCount: 1,
            updatedAt: "2026-07-17T00:00:00Z"
        )
    }
}
