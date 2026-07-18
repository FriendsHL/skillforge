import XCTest
@testable import SkillForge

final class ChatRuntimePresentationPolicyTests: XCTestCase {
    func testStructuredFailureSourcesMapToExplicitChineseResponsibilityCopy() {
        let expectations: [(MobileRuntimeFailureSource, String)] = [
            (.modelProvider, "模型服务异常"),
            (.network, "模型连接异常"),
            (.tool, "工具执行失败"),
            (.harness, "Agent Runtime 异常"),
            (.userAction, "用户操作未完成"),
            (.unknown, "未知运行异常")
        ]

        for (source, expectedTitle) in expectations {
            let presentation = ChatRuntimePresentationPolicy.resolve(
                isAgentRunning: false,
                isRetrying: false,
                isRefreshing: false,
                runtimeStatus: "error",
                runtimeError: "服务端安全原因",
                failureSource: source,
                failureCode: "SAFE_CODE",
                retryable: false,
                sideEffects: .noEffects,
                hasConnectionError: false
            )

            XCTAssertEqual(presentation.title, expectedTitle, "source=\(source)")
            XCTAssertEqual(presentation.semantic, .error)
        }
    }

    func testFailureDetailPreservesSanitizedReasonAndExplainsSideEffects() {
        let expectations: [(MobileRuntimeSideEffects?, String)] = [
            (.noEffects, "未检测到副作用"),
            (.possible, "可能已产生副作用"),
            (.observed, "已确认产生副作用"),
            (.unknown, "副作用状态未知"),
            (nil, "副作用状态未知")
        ]

        for (sideEffects, expectedSuffix) in expectations {
            let presentation = ChatRuntimePresentationPolicy.resolve(
                isAgentRunning: false,
                isRetrying: false,
                isRefreshing: false,
                runtimeStatus: "error",
                runtimeError: "  服务端安全原因  ",
                failureSource: .harness,
                failureCode: nil,
                retryable: false,
                sideEffects: sideEffects,
                hasConnectionError: false
            )

            XCTAssertEqual(presentation.detail, "服务端安全原因\n\(expectedSuffix)")
        }
    }

    func testPresentationNeverInfersResponsibilityOrRetryFromErrorText() {
        let misleading = ChatRuntimePresentationPolicy.resolve(
            isAgentRunning: false,
            isRetrying: false,
            isRefreshing: false,
            runtimeStatus: "error",
            runtimeError: "模型服务 429，但结构化来源是工具",
            failureSource: .tool,
            failureCode: nil,
            retryable: false,
            sideEffects: .possible,
            hasConnectionError: false
        )
        let legacy = ChatRuntimePresentationPolicy.resolve(
            isAgentRunning: false,
            isRetrying: false,
            isRefreshing: false,
            runtimeStatus: "error",
            runtimeError: "网络超时，可重试",
            failureSource: nil,
            failureCode: nil,
            retryable: nil,
            sideEffects: nil,
            hasConnectionError: false
        )

        XCTAssertEqual(misleading.title, "工具执行失败")
        XCTAssertFalse(misleading.canRetry)
        XCTAssertEqual(legacy.title, "未知运行异常")
        XCTAssertFalse(legacy.canRetry)
    }

    func testRetryRequiresExactErrorStatusAndExplicitServerTrue() {
        func canRetry(
            status: String?,
            retryable: Bool?,
            source: MobileRuntimeFailureSource? = .network,
            sideEffects: MobileRuntimeSideEffects? = .noEffects
        ) -> Bool {
            ChatRuntimePresentationPolicy.resolve(
                isAgentRunning: false,
                isRetrying: false,
                isRefreshing: false,
                runtimeStatus: status,
                runtimeError: "safe",
                failureSource: source,
                failureCode: nil,
                retryable: retryable,
                sideEffects: sideEffects,
                hasConnectionError: false
            ).canRetry
        }

        XCTAssertTrue(canRetry(status: "error", retryable: true))
        XCTAssertFalse(canRetry(status: "error", retryable: false))
        XCTAssertFalse(canRetry(status: "error", retryable: nil))
        XCTAssertFalse(canRetry(status: "failed", retryable: true))
        XCTAssertFalse(canRetry(status: "idle", retryable: true))
        XCTAssertFalse(canRetry(status: "error", retryable: true, source: nil, sideEffects: nil))
        XCTAssertFalse(canRetry(status: "error", retryable: true, source: .unknown))
        XCTAssertFalse(canRetry(status: "error", retryable: true, sideEffects: .possible))
        XCTAssertFalse(canRetry(status: "error", retryable: true, sideEffects: .observed))
    }

    func testRetrySourceAllowlistFailsClosedForToolUserActionAndUnknown() {
        func canRetry(source: MobileRuntimeFailureSource) -> Bool {
            ChatRuntimePresentationPolicy.resolve(
                isAgentRunning: false,
                isRetrying: false,
                isRefreshing: false,
                runtimeStatus: "error",
                runtimeError: "服务端安全原因",
                failureSource: source,
                failureCode: "SAFE_CODE",
                retryable: true,
                sideEffects: .noEffects,
                hasConnectionError: false
            ).canRetry
        }

        XCTAssertTrue(canRetry(source: .modelProvider))
        XCTAssertTrue(canRetry(source: .network))
        XCTAssertTrue(canRetry(source: .harness))
        XCTAssertFalse(canRetry(source: .tool))
        XCTAssertFalse(canRetry(source: .userAction))
        XCTAssertFalse(canRetry(source: .unknown))
    }

    func testRuntimeErrorUsesFailureSemanticsAndServerRetryDecision() {
        let presentation = ChatRuntimePresentationPolicy.resolve(
            isAgentRunning: true,
            isRetrying: false,
            isRefreshing: false,
            runtimeStatus: "error",
            runtimeError: "模型服务在响应前超时",
            failureSource: .network,
            failureCode: "FIRST_TOKEN_TIMEOUT",
            retryable: true,
            sideEffects: .noEffects,
            hasConnectionError: false
        )

        XCTAssertEqual(presentation.title, "模型连接异常")
        XCTAssertEqual(presentation.detail, "模型服务在响应前超时\n未检测到副作用")
        XCTAssertEqual(presentation.semantic, .error)
        XCTAssertEqual(presentation.symbolName, "exclamationmark.triangle.fill")
        XCTAssertTrue(presentation.canRetry)
    }

    func testRuntimeErrorDoesNotOfferRetryWhenServerRejectsIt() {
        let presentation = ChatRuntimePresentationPolicy.resolve(
            isAgentRunning: false,
            isRetrying: false,
            isRefreshing: false,
            runtimeStatus: "error",
            runtimeError: "工具执行失败",
            retryable: false,
            hasConnectionError: false
        )

        XCTAssertEqual(presentation.semantic, .error)
        XCTAssertFalse(presentation.canRetry)
    }

    func testWaitingCancelledAndConnectionFailureHaveDistinctSemantics() {
        let waiting = ChatRuntimePresentationPolicy.resolve(
            isAgentRunning: false,
            isRetrying: false,
            isRefreshing: false,
            runtimeStatus: "waiting_user",
            runtimeError: nil,
            retryable: false,
            hasConnectionError: false
        )
        let cancelled = ChatRuntimePresentationPolicy.resolve(
            isAgentRunning: false,
            isRetrying: false,
            isRefreshing: false,
            runtimeStatus: "cancelled",
            runtimeError: nil,
            retryable: false,
            hasConnectionError: false
        )
        let connectionFailure = ChatRuntimePresentationPolicy.resolve(
            isAgentRunning: false,
            isRetrying: false,
            isRefreshing: false,
            runtimeStatus: "idle",
            runtimeError: nil,
            retryable: true,
            hasConnectionError: true
        )

        XCTAssertEqual(waiting.semantic, .waiting)
        XCTAssertEqual(waiting.title, "等待确认")
        XCTAssertEqual(cancelled.semantic, .cancelled)
        XCTAssertEqual(cancelled.title, "已取消")
        XCTAssertEqual(connectionFailure.semantic, .error)
        XCTAssertEqual(connectionFailure.title, "连接异常")
        XCTAssertFalse(connectionFailure.canRetry, "Connection errors must not infer turn retry safety")
    }

    func testBackendIdleCancelledStepUsesCancelledSemanticsWithoutRetry() {
        let cancelled = ChatRuntimePresentationPolicy.resolve(
            isAgentRunning: false,
            isRetrying: false,
            isRefreshing: false,
            runtimeStatus: "idle",
            runtimeStep: "cancelled",
            runtimeError: nil,
            retryable: true,
            hasConnectionError: false
        )

        XCTAssertEqual(cancelled.semantic, .cancelled)
        XCTAssertEqual(cancelled.title, "已取消")
        XCTAssertFalse(cancelled.canRetry)
    }

    func testRetryingSuppressesDoubleSubmitAndUsesActiveSemantics() {
        let presentation = ChatRuntimePresentationPolicy.resolve(
            isAgentRunning: false,
            isRetrying: true,
            isRefreshing: false,
            runtimeStatus: "error",
            runtimeError: "模型连接异常",
            retryable: true,
            hasConnectionError: false
        )

        XCTAssertEqual(presentation.title, "正在重试")
        XCTAssertEqual(presentation.semantic, .running)
        XCTAssertFalse(presentation.canRetry)
    }
}
