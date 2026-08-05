import SwiftUI

enum MobileSessionTaskStatus: String, Decodable, Equatable, Sendable {
    case pending
    case inProgress = "in_progress"
    case completed
    case deleted
    case unknown

    init(from decoder: Decoder) throws {
        let value = try decoder.singleValueContainer().decode(String.self)
        self = Self(rawValue: value.lowercased()) ?? .unknown
    }
}

struct MobileSessionTask: Decodable, Equatable, Identifiable, Sendable {
    let taskId: String
    let subject: String
    let description: String
    let activeForm: String?
    let status: MobileSessionTaskStatus
    let owner: String?
    let blocked: Bool
    let blockedBy: [String]
    let blocks: [String]
    let createdAt: String
    let updatedAt: String
    let version: Int64

    var id: String { taskId }
}

struct MobileSessionTaskSnapshot: Decodable, Equatable, Sendable {
    let sessionId: String
    let tasks: [MobileSessionTask]
    let generatedAt: String

    init(sessionId: String, tasks: [MobileSessionTask], generatedAt: String) {
        self.sessionId = sessionId
        self.tasks = tasks
        self.generatedAt = generatedAt
    }
}

enum SessionTaskLoadPhase: Equatable {
    case idle
    case loading
    case ready
    case failed(String)
}

struct SessionTaskState: Equatable {
    var sessionID: String?
    var tasksByID: [String: MobileSessionTask]
    var phase: SessionTaskLoadPhase

    static let idle = SessionTaskState(sessionID: nil, tasksByID: [:], phase: .idle)

    static func loading(sessionID: String) -> SessionTaskState {
        SessionTaskState(sessionID: sessionID, tasksByID: [:], phase: .loading)
    }

    static func ready(sessionID: String, tasks: [MobileSessionTask]) -> SessionTaskState {
        var state = SessionTaskState(sessionID: sessionID, tasksByID: [:], phase: .ready)
        state.upsert(tasks)
        return state
    }

    mutating func merge(snapshot: MobileSessionTaskSnapshot) {
        guard sessionID == snapshot.sessionId else { return }
        upsert(snapshot.tasks)
        phase = .ready
    }

    mutating func markFailed(_ message: String, sessionID: String) {
        guard self.sessionID == sessionID else { return }
        phase = .failed(message)
    }

    private mutating func upsert(_ tasks: [MobileSessionTask]) {
        for task in tasks {
            guard let current = tasksByID[task.taskId] else {
                tasksByID[task.taskId] = task
                continue
            }
            if task.version > current.version
                || (task.version == current.version && task.updatedAt > current.updatedAt) {
                tasksByID[task.taskId] = task
            }
        }
    }
}

struct SessionTaskProgressPresentation: Equatable {
    let visibleTasks: [MobileSessionTask]
    let completedCount: Int
    let totalCount: Int
    let currentAction: String?
    let blockedCount: Int

    var accessibilityLabel: String {
        var parts = ["任务进度 \(completedCount) / \(totalCount) 已完成"]
        if let currentAction { parts.append("当前：\(currentAction)") }
        if blockedCount > 0 { parts.append("\(blockedCount) 项受阻") }
        return parts.joined(separator: "，")
    }
}

enum SessionTaskProgressPolicy {
    static func presentation(for tasks: [MobileSessionTask]) -> SessionTaskProgressPresentation {
        let visible = tasks
            .filter { $0.status != .deleted }
            .sorted {
                if $0.createdAt != $1.createdAt { return $0.createdAt < $1.createdAt }
                return $0.taskId < $1.taskId
            }
        let current = visible.first { $0.status == .inProgress }
        return SessionTaskProgressPresentation(
            visibleTasks: visible,
            completedCount: visible.count { $0.status == .completed },
            totalCount: visible.count,
            currentAction: nonBlank(current?.activeForm) ?? nonBlank(current?.subject),
            blockedCount: visible.count { $0.blocked }
        )
    }

    private static func nonBlank(_ value: String?) -> String? {
        let normalized = value?.trimmingCharacters(in: .whitespacesAndNewlines)
        return normalized?.isEmpty == false ? normalized : nil
    }
}

struct SessionTaskProgressView: View {
    let state: SessionTaskState
    let reduceMotion: Bool
    let onRetry: () -> Void
    @State private var expanded = false

    private var presentation: SessionTaskProgressPresentation {
        SessionTaskProgressPolicy.presentation(for: Array(state.tasksByID.values))
    }

    var body: some View {
        Group {
            switch state.phase {
            case .idle:
                EmptyView()
            case .loading where state.tasksByID.isEmpty:
                loadingView
            case .failed where state.tasksByID.isEmpty:
                failureView
            case .ready where presentation.totalCount == 0:
                EmptyView()
            case .loading, .ready, .failed:
                progressCard
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
        .background(CompanionStyle.warmBackground)
    }

    private var loadingView: some View {
        HStack(spacing: 9) {
            ProgressView().controlSize(.small)
            Text("正在加载任务进度")
                .font(.caption)
                .foregroundStyle(.secondary)
            Spacer()
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("chat.taskProgress.loading")
    }

    private var failureView: some View {
        HStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
            Text("任务进度暂时不可用")
                .font(.caption)
            Spacer()
            Button("重试", action: onRetry)
                .font(.caption.weight(.semibold))
                .frame(minWidth: 44, minHeight: 44)
        }
        .accessibilityElement(children: .contain)
        .accessibilityHint("点击重试任务进度，不影响聊天内容")
        .accessibilityIdentifier("chat.taskProgress.error")
    }

    private var progressCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                if reduceMotion {
                    expanded.toggle()
                } else {
                    withAnimation(.easeInOut(duration: 0.18)) { expanded.toggle() }
                }
            } label: {
                HStack(spacing: 9) {
                    Image(systemName: "checklist")
                        .foregroundStyle(CompanionStyle.orange)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("任务进度 \(presentation.completedCount)/\(presentation.totalCount)")
                            .font(.subheadline.weight(.semibold))
                        if let currentAction = presentation.currentAction {
                            Text(currentAction)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        } else if presentation.blockedCount > 0 {
                            Text("\(presentation.blockedCount) 项任务受阻")
                                .font(.caption)
                                .foregroundStyle(.orange)
                        }
                    }
                    Spacer(minLength: 8)
                    Image(systemName: expanded ? "chevron.up" : "chevron.down")
                        .font(.caption.weight(.bold))
                }
                .contentShape(Rectangle())
                .frame(minHeight: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(presentation.accessibilityLabel)
            .accessibilityValue(expanded ? "已展开" : "已折叠")
            .accessibilityIdentifier("chat.taskProgress.toggle")

            if expanded {
                Divider()
                ForEach(presentation.visibleTasks) { task in
                    taskRow(task)
                }
                if case .failed = state.phase {
                    Button("重新同步任务", action: onRetry)
                        .font(.caption.weight(.semibold))
                        .frame(minHeight: 44)
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 7)
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private func taskRow(_ task: MobileSessionTask) -> some View {
        HStack(alignment: .top, spacing: 9) {
            Image(systemName: symbol(for: task))
                .foregroundStyle(color(for: task))
                .frame(width: 20)
            VStack(alignment: .leading, spacing: 2) {
                Text(task.subject)
                    .font(.caption.weight(.semibold))
                    .strikethrough(task.status == .completed)
                if task.blocked {
                    Text("受阻" + (task.blockedBy.isEmpty ? "" : " · 等待 \(task.blockedBy.count) 项"))
                        .font(.caption2)
                        .foregroundStyle(.orange)
                } else if let owner = task.owner, !owner.isEmpty {
                    Text(owner).font(.caption2).foregroundStyle(.secondary)
                }
            }
            Spacer()
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("chat.taskProgress.task.\(task.taskId)")
    }

    private func symbol(for task: MobileSessionTask) -> String {
        if task.blocked { return "exclamationmark.circle.fill" }
        switch task.status {
        case .completed: return "checkmark.circle.fill"
        case .inProgress: return "circle.inset.filled"
        case .pending, .unknown, .deleted: return "circle"
        }
    }

    private func color(for task: MobileSessionTask) -> Color {
        if task.blocked { return .orange }
        switch task.status {
        case .completed: return .green
        case .inProgress: return .blue
        case .pending, .unknown, .deleted: return .secondary
        }
    }
}
