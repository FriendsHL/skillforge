import Foundation

struct ChatStatusText {
    static func resolve(
        isAgentRunning: Bool,
        isRefreshing: Bool,
        runtimeStatus: String?,
        hasError: Bool
    ) -> String {
        if hasError {
            return "连接异常"
        }
        if isAgentRunning {
            return "Agent 运行中"
        }
        if isRefreshing {
            return "同步中"
        }
        switch runtimeStatus?.lowercased() {
        case "waiting_user":
            return "等待确认"
        case "error":
            return "运行异常"
        case let status? where status != "idle" && !status.isEmpty:
            return status
        default:
            return "已连接"
        }
    }
}
