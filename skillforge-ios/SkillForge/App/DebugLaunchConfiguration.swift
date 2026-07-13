#if DEBUG
import Foundation
import UIKit

enum DebugLaunchConfiguration {
    enum Mode: Equatable {
        case chat
        case slice3
        case tabs
        case outboundAttachments
        case streamingHandoff
        case pairingReady
        case pairingReview
    }

    static func mode(arguments: [String]) -> Mode? {
        if arguments.contains("--ui-testing-streaming-handoff") { return .streamingHandoff }
        if arguments.contains("--ui-testing-outbound-attachments") { return .outboundAttachments }
        if arguments.contains("--ui-testing-pairing-review") { return .pairingReview }
        if arguments.contains("--ui-testing-pairing-ready") { return .pairingReady }
        if arguments.contains("--ui-testing-tabs") { return .tabs }
        if arguments.contains("--ui-testing-slice3") { return .slice3 }
        if arguments.contains("--ui-testing-chat") { return .chat }
        return nil
    }

    static var mode: Mode? {
        mode(arguments: ProcessInfo.processInfo.arguments)
    }

    static var isChatUITest: Bool {
        mode == .chat
    }

    static var isSlice3UITest: Bool {
        mode == .slice3
    }

    static var isTabsUITest: Bool {
        mode == .tabs
    }

    static var isOutboundAttachmentsUITest: Bool {
        mode == .outboundAttachments
    }

    static var isStreamingHandoffUITest: Bool {
        mode == .streamingHandoff
    }

    static var isPairingReviewUITest: Bool {
        mode == .pairingReview
    }

    static var isPairingUITest: Bool {
        mode == .pairingReady || mode == .pairingReview
    }

    static func isUITest(arguments: [String]) -> Bool {
        mode(arguments: arguments) != nil
    }

    static var isUITest: Bool {
        isUITest(arguments: ProcessInfo.processInfo.arguments)
    }
}

extension ChatView {
    static func streamingHandoffUITestFixture() -> ChatView {
        let session = MobileSession(
            id: "ui-test-streaming-handoff-session",
            userId: 1,
            agentId: 1,
            title: "Streaming Handoff",
            status: "active",
            runtimeStatus: "running",
            messageCount: fixtureMessages.count,
            updatedAt: nil
        )
        return ChatView(
            endpoint: URL(string: "http://127.0.0.1")!,
            deviceToken: "",
            device: MobileDeviceSummary(id: "streaming-fixture-device", deviceName: "Simulator", scopes: []),
            defaultAgent: MobileAgentSummary(id: 1, name: "Main Assistant"),
            initialSession: session,
            initialMessages: fixtureMessages,
            usesDeterministicFixture: true
        )
    }

    static func outboundAttachmentsUITestFixture() -> ChatView {
        let files = DebugOutboundArtifactFiles.create()
        let image = ChatAttachment(
            id: "outbound-image",
            kind: .image,
            mimeType: "image/png",
            filename: "release-chart.png",
            byteSize: files.imageSize,
            caption: "Release readiness chart"
        )
        let document = ChatAttachment(
            id: "outbound-document",
            kind: .pdf,
            mimeType: "application/pdf",
            filename: "release-brief.pdf",
            pageCount: 1,
            byteSize: files.documentSize,
            caption: "Release brief"
        )
        let retry = ChatAttachment(
            id: "outbound-retry",
            kind: .pdf,
            mimeType: "application/pdf",
            filename: "retry-brief.pdf",
            pageCount: 1,
            byteSize: files.documentSize,
            caption: "Retry fixture"
        )
        let store = AttachmentDownloadStore(
            fixtureStates: [
                image.id: .available(files.imageURL),
                document.id: .available(files.documentURL),
                retry.id: .failed("Deterministic fixture failure")
            ],
            retryURLs: [retry.id: files.documentURL]
        )
        let session = MobileSession(
            id: "outbound-attachments-session",
            userId: 1,
            agentId: 1,
            title: "Outbound Attachments",
            status: "active",
            runtimeStatus: "idle",
            messageCount: 1,
            updatedAt: nil
        )
        return ChatView(
            endpoint: URL(string: "http://127.0.0.1")!,
            deviceToken: "",
            device: MobileDeviceSummary(id: "outbound-fixture-device", deviceName: "Simulator", scopes: []),
            defaultAgent: MobileAgentSummary(id: 1, name: "Main Assistant"),
            initialSession: session,
            initialMessages: [
                ChatMessage(
                    id: "outbound-attachments-message",
                    role: .assistant,
                    text: "",
                    attachments: [image, document, retry]
                )
            ],
            usesDeterministicFixture: true,
            attachmentStore: store
        )
    }

    static func uiTestFixture() -> ChatView {
        let session = MobileSession(
            id: "ui-test-session",
            userId: 1,
            agentId: 1,
            title: "UI Test Chat",
            status: "active",
            runtimeStatus: "idle",
            messageCount: fixtureMessages.count,
            updatedAt: nil
        )
        return ChatView(
            endpoint: URL(string: "http://127.0.0.1:3000")!,
            deviceToken: "",
            device: MobileDeviceSummary(id: "ui-test-device", deviceName: "Simulator", scopes: []),
            defaultAgent: MobileAgentSummary(id: 1, name: "Main Assistant"),
            initialSession: session,
            initialMessages: fixtureMessages,
            usesDeterministicFixture: true
        )
    }

    static func slice3UITestFixture() -> ChatView {
        let session = MobileSession(
            id: "ui-test-slice3-session",
            userId: 1,
            agentId: 1,
            title: "Slice 3 UI Test",
            status: "active",
            runtimeStatus: "waiting_user",
            messageCount: 1,
            updatedAt: nil
        )
        let pending = [
            PendingInteraction(
                id: "ask-fixture",
                kind: .ask,
                question: "选择发布环境",
                context: "Agent 需要你的输入后才能继续。",
                options: [
                    PendingInteraction.Option(
                        label: "测试环境",
                        description: "部署到 staging"
                    )
                ],
                allowOther: true,
                source: .persisted
            ),
            PendingInteraction(
                id: "confirmation-fixture",
                kind: .confirmation,
                question: "批准执行部署？",
                context: "将运行已准备好的部署命令。",
                options: [],
                allowOther: false,
                source: .realtime
            )
        ]
        let attachment = MobileUploadedAttachment(
            id: "attachment-fixture",
            sessionId: session.id,
            kind: "image",
            mimeType: "image/png",
            filename: "release-check.png",
            sizeBytes: 1024,
            pageCount: nil,
            status: "uploaded"
        )
        return ChatView(
            endpoint: URL(string: "http://127.0.0.1:3000")!,
            deviceToken: "",
            device: MobileDeviceSummary(id: "ui-test-device", deviceName: "Simulator", scopes: []),
            defaultAgent: MobileAgentSummary(id: 1, name: "Main Assistant"),
            initialSession: session,
            initialMessages: [
                ChatMessage(
                    id: "slice3-intro",
                    role: .assistant,
                    text: "## 待处理事项\n\n请完成下面的回答和确认。"
                )
            ],
            initialPendingInteractions: pending,
            initialAttachments: [attachment],
            usesDeterministicFixture: true
        )
    }

    private static var fixtureMessages: [ChatMessage] {
        let history = (1...32).map { index in
            let detailedAssistantText = """
            ## Step \(index)

            Processed the fixture message with variable-height Markdown to exercise realistic chat layout.

            - Preserve the visible transcript
            - Keep the keyboard transition stable
            - Avoid jumping into blank content

            ```swift
            let step = \(index)
            let summary = "A deliberately long fixture row"
            ```
            """
            let assistantText = index.isMultiple(of: 8)
                ? detailedAssistantText
                : "## Step \(index)\n\nProcessed a shorter fixture response with a different measured height."
            let toolCalls = index.isMultiple(of: 8)
                ? [ChatMessage.ToolCall(
                    id: "fixture-tool-\(index)",
                    name: "ReadFile",
                    inputPreview: "{ \"path\": \"docs/fixture-\(index).md\" }",
                    output: "Loaded the deterministic fixture output for step \(index).",
                    status: .success
                )]
                : []
            return ChatMessage(
                id: "fixture-\(index)",
                role: index.isMultiple(of: 2) ? .assistant : .user,
                text: index.isMultiple(of: 2)
                    ? assistantText
                    : "Fixture request \(index): keep this transcript long enough for keyboard testing.\n\nThe row has a second paragraph so estimated heights vary.",
                toolCalls: index.isMultiple(of: 2) ? toolCalls : []
            )
        }
        return history + [
            ChatMessage(
                id: "fixture-latest",
                role: .assistant,
                text: "## Latest fixture message\n\nThe chat transcript is still visible after keyboard dismissal."
            )
        ]
    }
}

private enum DebugOutboundArtifactFiles {
    struct Files {
        let imageURL: URL
        let documentURL: URL
        let imageSize: Int64
        let documentSize: Int64
    }

    static func create() -> Files {
        let fileManager = FileManager.default
        let directory = fileManager.temporaryDirectory
            .appending(path: "SkillForgeOutboundAttachmentFixture", directoryHint: .isDirectory)
        let imageURL = directory.appending(path: "release-chart.png")
        let documentURL = directory.appending(path: "release-brief.pdf")
        do {
            try? fileManager.removeItem(at: directory)
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
            try makeImageData().write(to: imageURL, options: .atomic)
            try makePDFData().write(to: documentURL, options: .atomic)
            return Files(
                imageURL: imageURL,
                documentURL: documentURL,
                imageSize: fileSize(at: imageURL),
                documentSize: fileSize(at: documentURL)
            )
        } catch {
            preconditionFailure("Unable to create deterministic outbound attachment fixture")
        }
    }

    private static func makeImageData() -> Data {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 640, height: 360))
        return renderer.pngData { context in
            UIColor(red: 0.96, green: 0.97, blue: 0.98, alpha: 1).setFill()
            context.fill(CGRect(x: 0, y: 0, width: 640, height: 360))
            UIColor(red: 0.16, green: 0.55, blue: 0.42, alpha: 1).setFill()
            context.fill(CGRect(x: 72, y: 165, width: 110, height: 125))
            UIColor(red: 0.28, green: 0.48, blue: 0.88, alpha: 1).setFill()
            context.fill(CGRect(x: 220, y: 105, width: 110, height: 185))
            UIColor(red: 0.93, green: 0.38, blue: 0.20, alpha: 1).setFill()
            context.fill(CGRect(x: 368, y: 55, width: 110, height: 235))
            draw("Release readiness", at: CGPoint(x: 72, y: 34), size: 30, color: .label)
        }
    }

    private static func makePDFData() -> Data {
        let bounds = CGRect(x: 0, y: 0, width: 612, height: 792)
        return UIGraphicsPDFRenderer(bounds: bounds).pdfData { context in
            context.beginPage()
            draw("SkillForge Release Brief", at: CGPoint(x: 54, y: 64), size: 28, color: .black)
            draw("Deterministic outbound attachment fixture", at: CGPoint(x: 54, y: 118), size: 17, color: .darkGray)
            draw("Status: Ready for preview and sharing", at: CGPoint(x: 54, y: 170), size: 17, color: .black)
        }
    }

    private static func draw(_ text: String, at point: CGPoint, size: CGFloat, color: UIColor) {
        (text as NSString).draw(
            at: point,
            withAttributes: [
                .font: UIFont.systemFont(ofSize: size, weight: .semibold),
                .foregroundColor: color
            ]
        )
    }

    private static func fileSize(at url: URL) -> Int64 {
        let values = try? url.resourceValues(forKeys: [.fileSizeKey])
        return Int64(values?.fileSize ?? 0)
    }
}

extension CompanionTabView {
    static func tabsUITestFixture() -> CompanionTabView {
        let fixtureSuiteName = "skillforge.tabs-fixture"
        let fixtureDefaults = UserDefaults(suiteName: fixtureSuiteName) ?? .standard
        fixtureDefaults.removePersistentDomain(forName: fixtureSuiteName)
        let agents = [
            MobileAgentCatalogItem(
                id: 1,
                name: "Main Assistant",
                description: "Coordinates daily work and routes specialized tasks.",
                role: "main_assistant",
                modelId: "claude-sonnet",
                source: "default",
                visibility: "private",
                isDefault: true,
                executionMode: "agent_loop",
                skillCount: 4,
                toolCount: 8,
                toolAccess: "all"
            ),
            MobileAgentCatalogItem(
                id: 2,
                name: "Release Agent",
                description: "Reviews release readiness and deployment health.",
                role: "code",
                modelId: "gpt-5-codex",
                source: "owned",
                visibility: "private",
                executionMode: "agent_loop",
                skillCount: 3,
                toolCount: 6,
                toolAccess: "allowlist"
            ),
            MobileAgentCatalogItem(
                id: 3,
                name: "Research Commons",
                description: "A shared research configuration with protected internals.",
                role: "research",
                modelId: "claude-sonnet",
                source: "shared",
                visibility: "public",
                executionMode: "agent_loop",
                skillCount: 5,
                toolCount: 4,
                toolAccess: "all",
                configurationAccess: "summary"
            )
        ]
        let promptMetadata = MobileAgentPromptMetadata(
            agent: MobilePromptFieldMetadata(configured: true, characterCount: 1_240),
            soul: MobilePromptFieldMetadata(configured: true, characterCount: 420),
            tools: MobilePromptFieldMetadata(configured: true, characterCount: 680)
        )
        let agentDetails: [Int64: MobileAgentDetail] = [
            1: MobileAgentDetail(
                id: 1,
                name: "Main Assistant",
                description: agents[0].description,
                role: agents[0].role,
                modelId: agents[0].modelId,
                source: "default",
                visibility: "private",
                isDefault: true,
                executionMode: "agent_loop",
                skillCount: 4,
                toolCount: 8,
                toolAccess: "all",
                maxLoops: 40,
                thinkingMode: "enabled",
                reasoningEffort: "medium",
                skillNames: ["planning", "code-review", "web-research", "memory"],
                toolNames: ["Read", "Search", "Edit", "Shell", "Browser", "AskUser", "Task", "Memory"],
                enabledSystemSkillCount: 2,
                promptMetadata: promptMetadata
            ),
            2: MobileAgentDetail(
                id: 2,
                name: "Release Agent",
                description: agents[1].description,
                role: agents[1].role,
                modelId: agents[1].modelId,
                source: "owned",
                visibility: "private",
                executionMode: "agent_loop",
                skillCount: 3,
                toolCount: 6,
                toolAccess: "allowlist",
                maxLoops: 24,
                thinkingMode: "enabled",
                reasoningEffort: "high",
                skillNames: ["release-check", "code-review", "incident-triage"],
                toolNames: ["Read", "Search", "Shell", "GitHub", "Task", "AskUser"],
                enabledSystemSkillCount: 1,
                promptMetadata: promptMetadata
            ),
            3: MobileAgentDetail(
                id: 3,
                name: "Research Commons",
                description: agents[2].description,
                role: agents[2].role,
                modelId: agents[2].modelId,
                source: "shared",
                visibility: "public",
                executionMode: "agent_loop",
                skillCount: 5,
                toolCount: 4,
                toolAccess: "all",
                configurationAccess: "summary",
                enabledSystemSkillCount: 1
            )
        ]
        let sessions = [
            MobileSession(
                id: "tabs-running",
                userId: 1,
                agentId: 1,
                title: "Prepare release",
                status: "active",
                runtimeStatus: "running",
                messageCount: 3,
                updatedAt: "2026-07-10T08:30:00Z"
            ),
            MobileSession(
                id: "tabs-waiting",
                userId: 1,
                agentId: 1,
                title: "Review deployment",
                status: "active",
                runtimeStatus: "waiting_user",
                messageCount: 5,
                updatedAt: "2026-07-10T08:40:00Z"
            ),
            MobileSession(
                id: "tabs-release-default",
                userId: 1,
                agentId: 2,
                title: "Release overview",
                status: "active",
                runtimeStatus: "idle",
                messageCount: 1,
                updatedAt: "2026-07-10T08:25:00Z"
            ),
            MobileSession(
                id: "tabs-release-agent",
                userId: 1,
                agentId: 2,
                title: "Release checklist",
                status: "active",
                runtimeStatus: "idle",
                messageCount: 2,
                updatedAt: "2026-07-10T08:20:00Z"
            )
        ]
        let schedules = [
            MobileScheduledTask(
                id: 7,
                name: "Morning brief",
                agentId: 1,
                cronExpr: "0 0 7 * * *",
                oneShotAt: nil,
                timezone: "Asia/Shanghai",
                promptPreview: "Summarize overnight updates and prepare the morning brief.",
                sessionMode: "new",
                enabled: true,
                nextFireAt: "2026-07-12T23:00:00Z",
                lastFireAt: "2026-07-11T23:00:00Z",
                status: "idle",
                system: false
            ),
            MobileScheduledTask(
                id: 8,
                name: "Release monitor",
                agentId: 2,
                cronExpr: "0 */30 * * * *",
                oneShotAt: nil,
                timezone: "Asia/Shanghai",
                promptPreview: "Check the release pipeline and report failures.",
                sessionMode: "reuse",
                enabled: false,
                nextFireAt: nil,
                lastFireAt: "2026-07-10T08:00:00Z",
                status: "idle",
                system: false
            )
        ]
        let runs = [
            MobileScheduledTaskRun(
                id: 71,
                taskId: 7,
                triggeredAt: "2026-07-11T23:00:00Z",
                finishedAt: "2026-07-11T23:01:00Z",
                status: "success",
                errorMessage: nil,
                sessionId: "tabs-running",
                manual: false
            )
        ]
        return CompanionTabView(
            endpoint: URL(string: "http://127.0.0.1:3000")!,
            deviceToken: "",
            device: MobileDeviceSummary(
                id: "ui-test-device",
                deviceName: "Simulator",
                scopes: [
                    "chat:read", "chat:write", "confirmation:answer",
                    "schedule:read", "schedule:write", "agent:read"
                ]
            ),
            defaultAgent: agents[0].summary,
            initialAgents: agents,
            initialSessions: sessions,
            initialSchedules: schedules,
            initialRuns: runs,
            initialAgentDetails: agentDetails,
            initialMessages: [
                ChatMessage(
                    id: "tabs-fixture-message",
                    role: .assistant,
                    text: "The deterministic tab fixture keeps this transcript in memory."
                )
            ],
            usesDeterministicFixture: true,
            userDefaults: fixtureDefaults
        )
    }
}
#endif
