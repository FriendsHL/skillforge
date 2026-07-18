import Foundation

enum ChatStatusSemantic: String, Equatable, Sendable {
    case connected
    case running
    case waiting
    case error
    case cancelled
    case neutral
}

struct ChatStatusPresentation: Equatable, Sendable {
    let title: String
    let detail: String?
    let failureCode: String?
    let semantic: ChatStatusSemantic
    let symbolName: String
    let canRetry: Bool
}

enum ChatRuntimePresentationPolicy {
    static func resolve(
        isAgentRunning: Bool,
        isRetrying: Bool,
        isRefreshing: Bool,
        runtimeStatus: String?,
        runtimeStep: String? = nil,
        runtimeError: String?,
        failureSource: MobileRuntimeFailureSource? = nil,
        failureCode: String? = nil,
        retryable: Bool?,
        sideEffects: MobileRuntimeSideEffects? = nil,
        hasConnectionError: Bool
    ) -> ChatStatusPresentation {
        let normalizedStatus = runtimeStatus?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        let normalizedStep = runtimeStep?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        let sanitizedReason = runtimeError?
            .trimmingCharacters(in: .whitespacesAndNewlines)

        if isRetrying {
            return presentation(
                title: "正在重试",
                semantic: .running,
                symbolName: "arrow.clockwise",
                canRetry: false
            )
        }

        switch normalizedStatus {
        case "error", "failed":
            return presentation(
                title: (failureSource ?? .unknown).userFacingTitle,
                detail: failureDetail(
                    sanitizedReason: sanitizedReason,
                    sideEffects: sideEffects
                ),
                failureCode: failureCode,
                semantic: .error,
                symbolName: "exclamationmark.triangle.fill",
                canRetry: normalizedStatus == "error"
                    && retryable == true
                    && failureSource?.isAllowedForRetry == true
                    && sideEffects == .noEffects
            )
        case "waiting_user":
            return presentation(
                title: "等待确认",
                semantic: .waiting,
                symbolName: "person.crop.circle.badge.questionmark",
                canRetry: false
            )
        case "cancelled", "canceled":
            return presentation(
                title: "已取消",
                semantic: .cancelled,
                symbolName: "xmark.circle.fill",
                canRetry: false
            )
        case "idle" where normalizedStep == "cancelled" || normalizedStep == "canceled":
            return presentation(
                title: "已取消",
                semantic: .cancelled,
                symbolName: "xmark.circle.fill",
                canRetry: false
            )
        default:
            break
        }

        if isAgentRunning || normalizedStatus == "running" || normalizedStatus == "queued" {
            return presentation(
                title: "Agent 运行中",
                semantic: .running,
                symbolName: "bolt.fill",
                canRetry: false
            )
        }
        if hasConnectionError {
            return presentation(
                title: "连接异常",
                semantic: .error,
                symbolName: "wifi.exclamationmark",
                canRetry: false
            )
        }
        if isRefreshing {
            return presentation(
                title: "同步中",
                semantic: .running,
                symbolName: "arrow.triangle.2.circlepath",
                canRetry: false
            )
        }
        if let normalizedStatus, !normalizedStatus.isEmpty, normalizedStatus != "idle" {
            return presentation(
                title: normalizedStatus,
                semantic: .neutral,
                symbolName: "circle.fill",
                canRetry: false
            )
        }
        return presentation(
            title: "已连接",
            semantic: .connected,
            symbolName: "checkmark.circle.fill",
            canRetry: false
        )
    }

    private static func presentation(
        title: String,
        detail: String? = nil,
        failureCode: String? = nil,
        semantic: ChatStatusSemantic,
        symbolName: String,
        canRetry: Bool
    ) -> ChatStatusPresentation {
        ChatStatusPresentation(
            title: title,
            detail: detail?.isEmpty == false ? detail : nil,
            failureCode: failureCode?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
                ? failureCode?.trimmingCharacters(in: .whitespacesAndNewlines)
                : nil,
            semantic: semantic,
            symbolName: symbolName,
            canRetry: canRetry
        )
    }

    private static func failureDetail(
        sanitizedReason: String?,
        sideEffects: MobileRuntimeSideEffects?
    ) -> String {
        let reason = sanitizedReason?.isEmpty == false ? sanitizedReason : nil
        return [reason, (sideEffects ?? .unknown).userFacingText]
            .compactMap { $0 }
            .joined(separator: "\n")
    }
}

private extension MobileRuntimeFailureSource {
    var isAllowedForRetry: Bool {
        switch self {
        case .modelProvider, .network, .harness:
            true
        case .tool, .userAction, .unknown:
            false
        }
    }

    var userFacingTitle: String {
        switch self {
        case .modelProvider: "模型服务异常"
        case .network: "模型连接异常"
        case .tool: "工具执行失败"
        case .harness: "Agent Runtime 异常"
        case .userAction: "用户操作未完成"
        case .unknown: "未知运行异常"
        }
    }
}

private extension MobileRuntimeSideEffects {
    var userFacingText: String {
        switch self {
        case .noEffects: "未检测到副作用"
        case .possible: "可能已产生副作用"
        case .observed: "已确认产生副作用"
        case .unknown: "副作用状态未知"
        }
    }
}

struct ChatStatusText {
    static func resolve(
        isAgentRunning: Bool,
        isRefreshing: Bool,
        runtimeStatus: String?,
        hasError: Bool
    ) -> String {
        ChatRuntimePresentationPolicy.resolve(
            isAgentRunning: isAgentRunning,
            isRetrying: false,
            isRefreshing: isRefreshing,
            runtimeStatus: runtimeStatus,
            runtimeError: nil,
            retryable: false,
            hasConnectionError: hasError
        ).title
    }
}
