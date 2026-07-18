#if DEBUG
import Foundation
import SwiftUI
import UIKit

enum DebugLaunchConfiguration {
    enum Mode: Equatable {
        case launchLoading
        case chat
        case slice3
        case tabs
        case outboundAttachments
        case streamingHandoff
        case pairingReady
        case pairingReview
        case interactiveArtifact
        case runtimeError
        case personalAppLibrary
    }

    static func mode(arguments: [String]) -> Mode? {
        if arguments.contains("--ui-testing-launch-loading") { return .launchLoading }
        if arguments.contains("--ui-testing-personal-app-library") { return .personalAppLibrary }
        if arguments.contains("--ui-testing-runtime-error") { return .runtimeError }
        if arguments.contains("--ui-testing-interactive-artifact") { return .interactiveArtifact }
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

    static var isInteractiveArtifactUITest: Bool { mode == .interactiveArtifact }

    static var isRuntimeErrorUITest: Bool { mode == .runtimeError }

    static var isPersonalAppLibraryUITest: Bool { mode == .personalAppLibrary }

    static var usesAccessibilityXXXL: Bool {
        ProcessInfo.processInfo.arguments.contains("--ui-testing-accessibility-xxxl")
    }

    static var usesChatDarkMode: Bool {
        ProcessInfo.processInfo.arguments.contains("--ui-testing-dark-mode")
    }

    static var usesLongChatFixture: Bool {
        ProcessInfo.processInfo.arguments.contains("--ui-testing-chat-long")
    }

    static var usesCompanionShell: Bool {
        ProcessInfo.processInfo.arguments.contains("--ui-testing-companion-shell")
    }

    static var usesUploadedAttachmentFixture: Bool {
        ProcessInfo.processInfo.arguments.contains("--ui-testing-uploaded-attachment")
    }

    static var usesPersonalAppCardDarkMode: Bool {
        ProcessInfo.processInfo.arguments.contains("--personal-app-card-dark")
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
    static func runtimeErrorUITestFixture() -> ChatView {
        let isCancelled = ProcessInfo.processInfo.arguments.contains("--runtime-cancelled")
        let configuration = RuntimeFailureFixtureConfiguration.resolve(
            arguments: ProcessInfo.processInfo.arguments
        )
        let session = MobileSession(
            id: "runtime-error-session",
            userId: 1,
            agentId: 1,
            title: "Runtime Recovery",
            status: "active",
            runtimeStatus: isCancelled ? "idle" : "error",
            runtimeStep: isCancelled ? "cancelled" : (configuration.retryable ? "retryable" : "failed"),
            runtimeError: isCancelled ? nil : configuration.reason,
            failureSource: isCancelled ? nil : configuration.source,
            failureCode: isCancelled ? nil : configuration.code,
            retryable: isCancelled ? false : configuration.retryable,
            sideEffects: isCancelled ? nil : configuration.sideEffects,
            messageCount: 1,
            updatedAt: nil
        )
        return ChatView(
            endpoint: URL(string: "http://127.0.0.1")!,
            deviceToken: "",
            device: MobileDeviceSummary(id: "runtime-error-device", deviceName: "Simulator", scopes: []),
            defaultAgent: MobileAgentSummary(id: 1, name: "Main Assistant"),
            initialSession: session,
            initialMessages: [
                ChatMessage(
                    id: "runtime-error-user",
                    role: .user,
                    text: "Generate the morning AI brief."
                )
            ],
            usesDeterministicFixture: true
        )
    }

    static func interactiveArtifactUITestFixture() -> ChatView {
        let directory = FileManager.default.temporaryDirectory
            .appending(path: "SkillForgeInteractiveArtifactFixture", directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let htmlURL = directory.appending(path: "budget.html")
        let html = """
        <!doctype html>
        <!-- CSP decoy: <head data-decoy="true"> -->
        <html><HeAd data-fixture="personal-app-security">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
        body { font: 17px -apple-system, sans-serif; padding: 20px; }
        button, a { display: block; margin: 12px 0; min-height: 44px; }
        #security-marker { font-size: 14px; }
        </style>
        </hEaD><body>
        <h1>July budget</h1>
        <p id="security-marker">Security checks pending</p>
        <p id="permission-marker">Permission checks pending</p>
        <p id="clipboard-marker">Clipboard probe pending</p>
        <p id="state-marker">State bootstrap pending</p>
        <button id="submit">Submit budget</button>
        <button id="submit-invalid">Submit invalid state</button>
        <button id="open-valid">Open external report</button>
        <button id="open-invalid">Try invalid URL</button>
        <button id="try-file">Try file picker</button>
        <button id="try-media">Try camera and microphone</button>
        <button id="try-clipboard">Try clipboard</button>
        <a id="ordinary-link" href="https://example.com/navigation-escape">Ordinary external link</a>
        <button id="open-popup">Open popup</button>
        <script>
        const pageString = String;
        const pageStopImmediatePropagation = Event.prototype.stopImmediatePropagation;
        Set.prototype.has = function() { return false; };
        String.prototype.toLowerCase = function() { return 'not-copy'; };
        globalThis.String = function() { return 'not-copy'; };
        Event.prototype.preventDefault = function() {};
        Event.prototype.stopImmediatePropagation = function() {};
        ['copy', 'cut', 'paste'].forEach(type => {
          window.addEventListener(type, event => {
            pageStopImmediatePropagation.call(event);
          }, true);
        });
        document.getElementById('submit').onclick = () =>
          window.SkillForgeArtifact.submitSnapshot({food:2800});
        document.getElementById('submit-invalid').onclick = () =>
          window.SkillForgeArtifact.submitSnapshot({food:'invalid'});
        document.getElementById('state-marker').textContent =
          window.SkillForgeArtifact.savedState === null ? 'Using initial data' : 'Loaded saved state';
        document.getElementById('open-valid').onclick = () =>
          window.SkillForgeArtifact.requestOpenURL('HTTPS://Example.COM:8443/report?q=skillforge%20ios#summary');
        document.getElementById('open-invalid').onclick = () =>
          window.SkillForgeArtifact.requestOpenURL('javascript:alert(1)');
        const permissionMarker = document.getElementById('permission-marker');
        let fileDenialCount = 0;
        document.addEventListener('skillforgepermissiondenied', event => {
          if (event.detail && event.detail.kind === 'fileSelection') {
            fileDenialCount += 1;
            permissionMarker.textContent = `File picker denied by SkillForge (${fileDenialCount}/3)`;
          }
        });
        const fileBypassInputs = new Set();
        window.addEventListener('click', event => {
          if (fileBypassInputs.has(event.target)) event.stopImmediatePropagation();
        }, true);
        document.getElementById('try-file').onpointerdown = () => {
          const propertyInput = document.createElement('input');
          document.body.appendChild(propertyInput);
          propertyInput.type = 'file';
          fileBypassInputs.add(propertyInput);
          HTMLElement.prototype.click.call(propertyInput);

          const attributeInput = document.createElement('input');
          document.body.appendChild(attributeInput);
          attributeInput.setAttribute('type', 'file');
          attributeInput.setAttribute('capture', 'environment');
          fileBypassInputs.add(attributeInput);
          HTMLElement.prototype.click.call(attributeInput);

          const pickerInput = document.createElement('input');
          document.body.appendChild(pickerInput);
          pickerInput.type = 'file';
          fileBypassInputs.add(pickerInput);
          if (typeof pickerInput.showPicker === 'function') {
            try { HTMLInputElement.prototype.showPicker.call(pickerInput); } catch (_) {}
          }
        };
        document.getElementById('try-media').onclick = () => {
          const mediaDevices = navigator.mediaDevices;
          if (!mediaDevices || typeof mediaDevices.getUserMedia !== 'function') {
            permissionMarker.textContent = 'Media capture denied by SkillForge';
            return;
          }
          mediaDevices.getUserMedia({audio: true, video: true})
            .then(stream => {
              stream.getTracks().forEach(track => track.stop());
              permissionMarker.textContent = 'Media capture escaped';
            })
            .catch(() => {
              permissionMarker.textContent = 'Media capture denied by SkillForge';
            });
        };
        document.getElementById('try-clipboard').onclick = async () => {
          const clipboard = navigator['clip' + 'board'];
          let read = 'unavailable';
          let write = 'unavailable';
          if (clipboard && typeof clipboard.readText === 'function') {
            try { await clipboard.readText(); read = 'succeeded'; }
            catch (_) { read = 'denied'; }
          }
          if (clipboard && typeof clipboard.writeText === 'function') {
            try { await clipboard.writeText('skillforge-probe'); write = 'succeeded'; }
            catch (_) { write = 'denied'; }
          }
          const copySource = document.createElement('textarea');
          copySource.value = 'SKILLFORGE_CLIPBOARD_AFTER';
          document.body.appendChild(copySource);
          copySource.select();
          let exec = false;
          try { exec = Boolean(document['exec' + 'Command']('copy')); } catch (_) {}
          copySource.remove();
          const escaped = read === 'succeeded' || write === 'succeeded' || exec;
          document.getElementById('clipboard-marker').textContent =
            (escaped ? 'Clipboard escaped' : 'Clipboard denied') +
            ' · href=' + location.href +
            ' · origin=' + location.origin +
            ' · secure=' + pageString(isSecureContext) +
            ' · api=' + typeof clipboard +
            ' · read=' + read +
            ' · write=' + write +
            ' · exec=' + pageString(exec);
        };
        document.getElementById('open-popup').onclick = () =>
          window.open('https://example.com/popup-escape', '_blank');

        const marker = document.getElementById('security-marker');
        const csp = document.head.querySelector('meta[http-equiv="Content-Security-Policy"]');
        const remote = ['https:', '', 'example.com', 'skillforge-network-probe'].join('/');
        const imageTarget = remote + '/image.png';
        const fetchTarget = remote + '/data.json';
        const results = {
          headMeta: Boolean(csp && csp.parentElement === document.head),
          imageViolation: false,
          fetchViolation: false,
          escaped: false
        };
        function renderSecurityResult() {
          if (results.escaped) {
            marker.textContent = 'Network escaped';
          } else if (!results.headMeta) {
            marker.textContent = 'CSP missing from document head';
          } else if (results.imageViolation && results.fetchViolation) {
            marker.textContent = 'CSP enforced · Head meta · img-src blocked · connect-src blocked';
          }
        }
        document.addEventListener('securitypolicyviolation', event => {
          if (event.effectiveDirective === 'img-src' && event.blockedURI === imageTarget) {
            results.imageViolation = true;
          }
          if (event.effectiveDirective === 'connect-src' && event.blockedURI === fetchTarget) {
            results.fetchViolation = true;
          }
          renderSecurityResult();
        });
        const remoteImage = new Image();
        remoteImage.hidden = true;
        remoteImage.onload = () => { results.escaped = true; renderSecurityResult(); };
        remoteImage.onerror = () => { renderSecurityResult(); };
        document.body.appendChild(remoteImage);
        remoteImage['s' + 'rc'] = imageTarget;
        globalThis['fe' + 'tch'](fetchTarget)
          .then(() => { results.escaped = true; renderSecurityResult(); })
          .catch(() => { renderSecurityResult(); });
        </script>
        </body></html>
        """
        try? html.write(to: htmlURL, atomically: true, encoding: .utf8)
        let attachment = ChatAttachment(
            id: "interactive-budget",
            kind: .interactive,
            mimeType: "text/html",
            filename: "budget.html",
            caption: "Adjust the July family budget.",
            title: "July budget",
            artifactSchemaVersion: 1
        )
        let manifest = InteractiveArtifactManifest(
            schemaVersion: 1,
            title: "July budget",
            fallback: "Adjust the July family budget.",
            permissions: [],
            network: [],
            initialData: ["food": .number("2600")],
            stateSchema: [
                "type": .string("object"),
                "additionalProperties": .bool(false),
                "required": .array([.string("food")]),
                "properties": .object(["food": .object(["type": .string("integer")])])
            ]
        )
        let arguments = ProcessInfo.processInfo.arguments
        let initialState: AttachmentDownloadStore.State
        let retryURLs: [String: URL]
        if arguments.contains("--personal-app-card-failed") {
            initialState = .failed("Personal App download failed")
            retryURLs = [attachment.id: htmlURL]
        } else if arguments.contains("--personal-app-card-unavailable") {
            initialState = .unavailable
            retryURLs = [:]
        } else {
            initialState = .available(htmlURL)
            retryURLs = [:]
        }
        let store = AttachmentDownloadStore(
            fixtureStates: [attachment.id: initialState],
            retryURLs: retryURLs,
            manifests: [attachment.id: manifest]
        )
        let session = MobileSession(
            id: "interactive-session", userId: 1, agentId: 1,
            title: "Personal App", status: "active", runtimeStatus: "idle",
            messageCount: 1, updatedAt: nil
        )
        return ChatView(
            endpoint: URL(string: "http://127.0.0.1")!,
            deviceToken: "",
            device: MobileDeviceSummary(id: "fixture-device", deviceName: "Simulator", scopes: []),
            defaultAgent: MobileAgentSummary(id: 1, name: "Research Agent"),
            initialSession: session,
            initialMessages: [ChatMessage(
                id: "interactive-message", role: .assistant,
                text: "I created an offline budget planner.",
                attachments: [attachment],
                createdAt: try? Date.ISO8601FormatStyle().parse("2026-07-16T18:30:00Z")
            )],
            usesDeterministicFixture: true,
            attachmentStore: store
        )
    }

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
        let uploadedAttachments = DebugLaunchConfiguration.usesUploadedAttachmentFixture
            ? [MobileUploadedAttachment(
                id: "attachment-fixture",
                sessionId: session.id,
                kind: "image",
                mimeType: "image/png",
                filename: "release-check.png",
                sizeBytes: 1_024,
                pageCount: nil,
                status: "uploaded"
            )]
            : []
        return ChatView(
            endpoint: URL(string: "http://127.0.0.1:3000")!,
            deviceToken: "",
            device: MobileDeviceSummary(id: "ui-test-device", deviceName: "Simulator", scopes: []),
            defaultAgent: MobileAgentSummary(id: 1, name: "Main Assistant"),
            initialSession: session,
            initialMessages: fixtureMessages,
            initialAttachments: uploadedAttachments,
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
        let usesLargeRows = DebugLaunchConfiguration.usesLongChatFixture
        let historyCount = usesLargeRows ? 46 : 32
        let history = (1...historyCount).map { index in
            let accessibilityAssistantText = """
            ## Agent result

            - Keep the result readable
            - Preserve source context

            1. Review the summary
            2. Open the generated result

            > Provenance and runtime responsibility remain visible.

            ```swift
            let status = "ready"
            ```

            [Open source](https://skillforge.example/result)
            """
            let standardDetailedAssistantText = """
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
            let usesLargeAssistantRow = usesLargeRows && index.isMultiple(of: 6)
            let assistantText = if index == 2 {
                accessibilityAssistantText
            } else if index.isMultiple(of: 8) {
                standardDetailedAssistantText
            } else {
                "## Step \(index)\n\nProcessed a shorter fixture response with a different measured height."
            }
            let largeToolOutput = (1...180).map { item in
                "Tool result line \(item): a deterministic payload that stays collapsed until the user opens the result."
            }.joined(separator: "\n")
            let toolCalls = index == 2 || usesLargeAssistantRow || index.isMultiple(of: 8)
                ? [ChatMessage.ToolCall(
                    id: "fixture-tool-\(index)",
                    name: "ReadFile",
                    inputPreview: "{ \"path\": \"docs/fixture-\(index).md\" }",
                    output: usesLargeAssistantRow
                        ? largeToolOutput
                        : "Loaded the deterministic fixture output for step \(index).",
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

private struct RuntimeFailureFixtureConfiguration {
    let source: MobileRuntimeFailureSource?
    let code: String?
    let reason: String
    let retryable: Bool
    let sideEffects: MobileRuntimeSideEffects?

    static func resolve(arguments: [String]) -> RuntimeFailureFixtureConfiguration {
        let source = resolvedSource(arguments: arguments)
        let sideEffects = resolvedSideEffects(arguments: arguments, source: source)
        let retryable = if arguments.contains("--runtime-failure-legacy") {
            true
        } else {
            !arguments.contains("--runtime-error-not-retryable")
                && source == .network
                && sideEffects == .noEffects
        }
        return RuntimeFailureFixtureConfiguration(
            source: source,
            code: code(for: source),
            reason: reason(for: source),
            retryable: retryable,
            sideEffects: sideEffects
        )
    }

    private static func resolvedSource(arguments: [String]) -> MobileRuntimeFailureSource? {
        if arguments.contains("--runtime-failure-legacy") { return nil }
        if arguments.contains("--runtime-failure-model-provider") { return .modelProvider }
        if arguments.contains("--runtime-failure-tool") { return .tool }
        if arguments.contains("--runtime-failure-harness") { return .harness }
        if arguments.contains("--runtime-failure-user-action") { return .userAction }
        if arguments.contains("--runtime-failure-unknown") { return .unknown }
        return .network
    }

    private static func resolvedSideEffects(
        arguments: [String],
        source: MobileRuntimeFailureSource?
    ) -> MobileRuntimeSideEffects? {
        if arguments.contains("--runtime-failure-legacy") { return nil }
        if arguments.contains("--runtime-side-effects-possible") { return .possible }
        if arguments.contains("--runtime-side-effects-observed") { return .observed }
        if arguments.contains("--runtime-side-effects-unknown") { return .unknown }
        switch source {
        case .tool: return .possible
        case .harness: return .observed
        case .unknown: return .unknown
        default: return .noEffects
        }
    }

    private static func code(for source: MobileRuntimeFailureSource?) -> String? {
        switch source {
        case .modelProvider: "PROVIDER_REJECTED"
        case .network: "FIRST_TOKEN_TIMEOUT"
        case .tool: "TOOL_VALIDATION"
        case .harness: "LOOP_STATE"
        case .userAction: "CONFIRMATION_TIMEOUT"
        case .unknown: "UNCLASSIFIED"
        case nil: nil
        }
    }

    private static func reason(for source: MobileRuntimeFailureSource?) -> String {
        switch source {
        case .modelProvider: "模型服务拒绝了请求"
        case .network: "模型服务在响应前超时"
        case .tool: "工具参数校验失败"
        case .harness: "Agent Runtime 状态机异常"
        case .userAction: "用户确认已超时"
        case .unknown: "运行时返回了未分类错误"
        case nil: "旧版服务返回了通用错误"
        }
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

    static func personalAppLibraryUITestFixture() -> CompanionTabView {
        let directory = FileManager.default.temporaryDirectory
            .appending(path: "SkillForgePersonalAppLibraryFixture", directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let htmlURL = directory.appending(path: "library-app.html")
        let html = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
        body{font:17px -apple-system,sans-serif;background:#0b0b12;color:#f5f5fa;padding:22px}
        .card{background:#181823;border:1px solid #303044;border-radius:18px;padding:18px;margin:14px 0}
        button{min-height:48px;width:100%;border:0;border-radius:14px;background:#7467f0;color:white;font-weight:700}
        </style></head><body><h1>AI Brief</h1><p>Interactive highlights saved from your agent.</p>
        <div class="card"><h2>Today</h2><p>Three product updates need review.</p></div>
        <button onclick="window.SkillForgeArtifact.submitSnapshot({reviewed:true})">Submit selection</button>
        </body></html>
        """
        try? Data(html.utf8).write(to: htmlURL, options: .atomic)

        let agent = MobileAgentCatalogItem(
            id: 7,
            name: "Research Agent",
            description: "Builds the daily AI brief.",
            role: "research",
            modelId: "gpt-5-codex",
            source: "owned",
            visibility: "private",
            isDefault: true,
            executionMode: "agent_loop",
            skillCount: 3,
            toolCount: 5,
            toolAccess: "all"
        )
        let archivedAgent = MobileAgentCatalogItem(
            id: 99,
            name: "Archive Agent",
            description: "Owns Personal Apps older than the first library page."
        )
        let session = MobileSession(
            id: "library-source-session",
            userId: 1,
            agentId: 7,
            title: "Daily AI research",
            status: "active",
            runtimeStatus: "idle",
            messageCount: 43,
            updatedAt: "2026-07-17T09:30:00Z"
        )
        let archivedSession = MobileSession(
            id: "archived-library-session",
            userId: 1,
            agentId: archivedAgent.id,
            title: "Archived launch",
            status: "active",
            runtimeStatus: "idle",
            messageCount: 12,
            updatedAt: "2026-06-01T09:00:00Z"
        )
        let titles = [
            "AI Brief · July 17",
            "Release Readiness Board",
            "Model Cost Explorer",
            "Research Source Triage",
            "Weekly Product Signals",
            "Incident Follow-up",
            "Launch Checklist",
            "Unsupported Camera App",
            "Unavailable Legacy App"
        ]
        let apps = titles.enumerated().map { index, title in
            MobilePersonalApp(
                artifactId: "library-app-\(index + 1)",
                sessionId: session.id,
                sourceMessageSeq: 42,
                title: title,
                caption: index == 0
                    ? "35 AI updates with filters, favorites, and saved review state. This longer description verifies that Personal App Library cards stay concise instead of expanding to fill the screen."
                    : "A reusable interactive result from Research Agent.",
                schemaVersion: 1,
                permissions: index == titles.count - 2 ? ["camera"] : [],
                network: [],
                agentId: 7,
                agentName: agent.name,
                sessionTitle: session.title,
                createdAt: "2026-07-\(String(format: "%02d", 17 - index))T09:00:00Z",
                lastOpenedAt: index == 0 ? "2026-07-17T09:20:00Z" : nil,
                favorite: index == 0,
                availability: index == titles.count - 1 ? .unavailable : .available
            )
        }
        let manifest = InteractiveArtifactManifest(
            schemaVersion: 1,
            title: "AI Brief",
            fallback: "Return to the source conversation.",
            permissions: [],
            network: [],
            initialData: [:],
            stateSchema: [
                "type": .string("object"),
                "additionalProperties": .bool(false),
                "required": .array([.string("reviewed")]),
                "properties": .object([
                    "reviewed": .object(["type": .string("boolean")])
                ])
            ]
        )
        let downloaded = Array(apps.prefix(2)).map { PersonalAppLocalRecord(app: $0) }
        let states = Dictionary(uniqueKeysWithValues: downloaded.map { ($0.artifactId, AttachmentDownloadStore.State.available(htmlURL)) })
        let retryURLs = Dictionary(uniqueKeysWithValues: apps.map { ($0.artifactId, htmlURL) })
        let manifests = Dictionary(uniqueKeysWithValues: apps.map { ($0.artifactId, manifest) })
        let store = AttachmentDownloadStore(
            fixtureStates: states,
            retryURLs: retryURLs,
            manifests: manifests,
            localRecords: downloaded
        )
        let suiteName = "skillforge.personal-app-library-fixture"
        let defaults = UserDefaults(suiteName: suiteName) ?? .standard
        defaults.removePersistentDomain(forName: suiteName)

        return CompanionTabView(
            endpoint: URL(string: "http://127.0.0.1:3000")!,
            deviceToken: "fixture-token",
            device: MobileDeviceSummary(
                id: "personal-app-library-device",
                deviceName: "Simulator",
                scopes: ["chat:read", "chat:write", "agent:read"]
            ),
            defaultAgent: agent.summary,
            initialAgents: [agent, archivedAgent],
            initialSessions: [session, archivedSession],
            initialMessages: [
                ChatMessage(
                    id: "remote-42",
                    role: .assistant,
                    text: "Source message for AI Brief",
                    createdAt: ISO8601DateFormatter().date(from: "2026-07-17T09:00:00Z"),
                    remoteSeqNo: 42
                )
            ],
            initialPersonalApps: apps,
            usesDeterministicFixture: true,
            userDefaults: defaults,
            attachmentStore: store
        )
    }
}
#endif
