import CryptoKit
import SwiftUI
import UIKit
import WebKit

struct PersonalAppViewer: View {
    let sessionID: String
    let attachment: ChatAttachment
    let htmlURL: URL
    @ObservedObject var store: AttachmentDownloadStore
    let onUnauthorized: @MainActor () -> Void
    let onSubmitSnapshot: @MainActor (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.personalAppExternalURLOpener) private var externalURLOpener
    @State private var manifest: InteractiveArtifactManifest?
    @State private var errorText: String?
    @State private var savedState: Data?
    @State private var diagnosticText: String?
    @State private var confirmationState = PersonalAppConfirmationState()
    @State private var shareItem: PersonalAppShareItem?
    @State private var webViewResetGeneration = 0
    #if DEBUG
    @State private var debugExternalOpenCount = 0
    @State private var debugLastOpenedURL = ""
    @State private var debugDeniedPermissions: Set<PersonalAppDeniedPermissionKind> = []
    #endif

    var body: some View {
        NavigationStack {
            Group {
                if let manifest {
                    PersonalAppWebView(
                        artifactID: attachment.id,
                        htmlURL: htmlURL,
                        manifest: manifest,
                        savedState: savedState,
                        onSnapshot: receiveSnapshot,
                        onExternalURL: receiveExternalURL,
                        onPermissionDenied: receivePermissionDenied
                    )
                    .id(webViewResetGeneration)
                    .ignoresSafeArea(edges: .bottom)
                    .safeAreaInset(edge: .top, spacing: 0) {
                        if let diagnosticText {
                            HStack(alignment: .firstTextBaseline, spacing: 8) {
                                Image(systemName: "exclamationmark.triangle.fill")
                                    .foregroundStyle(.orange)
                                    .accessibilityHidden(true)
                                Text(diagnosticText)
                                    .font(.footnote)
                                    .foregroundStyle(.primary)
                                    .accessibilityIdentifier("personalApp.diagnostic")
                                Spacer(minLength: 0)
                            }
                            .padding(.horizontal, 16)
                            .padding(.vertical, 10)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(.orange.opacity(0.14))
                        }
                    }
                } else if let errorText {
                    ContentUnavailableView(
                        "Personal App unavailable",
                        systemImage: "exclamationmark.triangle",
                        description: Text(errorText)
                    )
                } else {
                    ProgressView("Preparing Personal App")
                }
            }
            .navigationTitle(attachment.title ?? "Personal App")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Done") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button("Reset saved state", systemImage: "arrow.counterclockwise") {
                            PersonalAppStateStore.reset(artifactID: attachment.id)
                            savedState = nil
                            diagnosticText = nil
                            webViewResetGeneration &+= 1
                        }
                        Button("Share saved state", systemImage: "square.and.arrow.up") {
                            if let manifest,
                               let data = PersonalAppValidatedStateAccess.live.load(
                                   artifactID: attachment.id,
                                   schema: manifest.stateSchema
                               ) {
                                shareItem = PersonalAppShareItem(data: data)
                            }
                        }
                    } label: {
                        Label("Personal App actions", systemImage: "ellipsis.circle")
                    }
                }
            }
        }
        .task(id: attachment.id) { await loadManifest() }
        .alert(confirmationTitle, isPresented: Binding(
            get: { confirmationState.pending != nil },
            set: { if !$0 { confirmationState.cancel() } }
        )) {
            switch confirmationState.pending {
            case .snapshot:
                Button("Cancel", role: .cancel) { confirmationState.cancel() }
                Button("Send") { submitPendingSnapshot() }
            case .externalURL:
                Button("Cancel", role: .cancel) { confirmationState.cancel() }
                Button("Open") { openPendingExternalURL() }
            case nil:
                EmptyView()
            }
        } message: {
            Text(confirmationMessage)
        }
        .sheet(item: $shareItem) { item in
            PersonalAppActivityView(items: [item.url])
        }
        .accessibilityIdentifier("personalApp.viewer.\(attachment.id)")
        .overlay(alignment: .topLeading) { debugOpenObservation }
    }

    private func loadManifest() async {
        do {
            let loadedManifest = try await store.interactiveManifest(
                sessionID: sessionID,
                attachmentID: attachment.id
            )
            switch PersonalAppValidatedStateAccess.live.loadResult(
                artifactID: attachment.id,
                schema: loadedManifest.stateSchema
            ) {
            case .missing:
                savedState = nil
                diagnosticText = nil
            case .valid(let data):
                savedState = data
                diagnosticText = nil
            case .invalid:
                savedState = nil
                diagnosticText = PersonalAppDiagnosticText.invalidSavedState
            }
            errorText = nil
            manifest = loadedManifest
        } catch AttachmentDownloadError.unauthorized {
            errorText = "This device is no longer authorized."
            onUnauthorized()
        } catch {
            errorText = error.localizedDescription
        }
    }

    private func receiveSnapshot(_ data: Data) {
        guard let manifest,
              PersonalAppSnapshotValidator.validate(data: data, schema: manifest.stateSchema) else {
            diagnosticText = PersonalAppDiagnosticText.invalidSubmittedState
            return
        }
        confirmationState.propose(.snapshot(data))
    }

    private func receiveExternalURL(_ url: URL) {
        confirmationState.propose(.externalURL(url))
    }

    private func receivePermissionDenied(_ permission: PersonalAppDeniedPermissionKind) {
        #if DEBUG
        if DebugLaunchConfiguration.isInteractiveArtifactUITest {
            debugDeniedPermissions.insert(permission)
        }
        #endif
    }

    private var snapshotPreview: String {
        guard case .snapshot(let data) = confirmationState.pending,
              let value = String(data: data, encoding: .utf8) else { return "" }
        return value.count > 800 ? String(value.prefix(800)) + "…" : value
    }

    private var confirmationTitle: String {
        switch confirmationState.pending {
        case .snapshot: "Send this state to Agent?"
        case .externalURL: "Open external link?"
        case nil: "Personal App confirmation"
        }
    }

    private var confirmationMessage: String {
        switch confirmationState.pending {
        case .snapshot:
            return snapshotPreview
        case .externalURL(let url):
            let host = URLComponents(url: url, resolvingAgainstBaseURL: false)?.host ?? ""
            return "\(host)\n\(url.absoluteString)"
        case nil:
            return ""
        }
    }

    private func submitPendingSnapshot() {
        guard let data = confirmationState.takeSnapshot(),
              let json = String(data: data, encoding: .utf8) else { return }
        onSubmitSnapshot("Personal App \(attachment.title ?? attachment.filename) state:\n```json\n\(json)\n```")
        dismiss()
    }

    private func openPendingExternalURL() {
        guard let url = confirmationState.takeExternalURL() else { return }
        externalURLOpener.open(url)
        #if DEBUG
        if DebugLaunchConfiguration.isInteractiveArtifactUITest {
            debugExternalOpenCount += 1
            debugLastOpenedURL = url.absoluteString
        }
        #endif
    }

    @ViewBuilder
    private var debugOpenObservation: some View {
        #if DEBUG
        if DebugLaunchConfiguration.isInteractiveArtifactUITest {
            VStack(spacing: 0) {
                Text(String(debugExternalOpenCount))
                    .accessibilityLabel(String(debugExternalOpenCount))
                    .accessibilityIdentifier("personalApp.debug.openCount")
                Text(debugLastOpenedURL)
                    .accessibilityLabel(debugLastOpenedURL)
                    .accessibilityIdentifier("personalApp.debug.lastOpenedURL")
                    .lineLimit(1)
                Text(debugDeniedPermissions.map(\.rawValue).sorted().joined(separator: ","))
                    .accessibilityLabel(
                        debugDeniedPermissions.map(\.rawValue).sorted().joined(separator: ",")
                    )
                    .accessibilityIdentifier("personalApp.debug.deniedPermissions")
                    .lineLimit(1)
            }
            .font(.caption2)
            .foregroundStyle(Color.primary.opacity(0.02))
            .padding(2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .allowsHitTesting(false)
        }
        #endif
    }
}

private enum PersonalAppDiagnosticText {
    static let invalidSavedState =
        "Saved state did not match this Personal App and was ignored. Initial data is being used."
    static let invalidSubmittedState =
        "The Personal App submitted state that does not match its schema."
}

enum PersonalAppBootstrapScriptBuilder {
    static func build(
        artifactID: String,
        initialData: [String: MobileJSONValue],
        savedState: Data?
    ) -> String {
        let artifactIDFragment = jsonStringFragment(artifactID)
        let initial = jsonObjectString(initialData.mapValues(\.foundationValue))
        let saved = savedState.flatMap { String(data: $0, encoding: .utf8) } ?? "null"
        return """
        \(PersonalAppFileInputGuard.script)
        \(PersonalAppClipboardGuard.script)
        const skillForgeArtifactID = \(artifactIDFragment);
        window.SkillForgeArtifact = Object.freeze({
          initialData: \(initial),
          savedState: \(saved),
          saveState: function(payload) {
            window.webkit.messageHandlers.skillforge.postMessage({method:'saveState', artifactId:skillForgeArtifactID, payload:payload});
          },
          submitSnapshot: function(payload) {
            window.webkit.messageHandlers.skillforge.postMessage({method:'submitSnapshot', artifactId:skillForgeArtifactID, payload:payload});
          },
          requestOpenURL: function(url) {
            window.webkit.messageHandlers.skillforge.postMessage({method:'requestOpenURL', artifactId:skillForgeArtifactID, url:url});
          }
        });
        """
    }

    private static func jsonStringFragment(_ value: String) -> String {
        guard let data = try? JSONEncoder().encode(value),
              let string = String(data: data, encoding: .utf8) else { return "\"\"" }
        return string
    }

    private static func jsonObjectString(_ value: Any) -> String {
        guard JSONSerialization.isValidJSONObject(value),
              let data = try? JSONSerialization.data(withJSONObject: value),
              let string = String(data: data, encoding: .utf8) else { return "{}" }
        return string
    }
}

private struct PersonalAppWebView: UIViewRepresentable {
    let artifactID: String
    let htmlURL: URL
    let manifest: InteractiveArtifactManifest
    let savedState: Data?
    let onSnapshot: @MainActor (Data) -> Void
    let onExternalURL: @MainActor (URL) -> Void
    let onPermissionDenied: @MainActor (PersonalAppDeniedPermissionKind) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(
            artifactID: artifactID,
            securedHTML: securedHTML(),
            stateSchema: manifest.stateSchema,
            onSnapshot: onSnapshot,
            onExternalURL: onExternalURL,
            onPermissionDenied: onPermissionDenied
        )
    }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        configuration.userContentController.add(context.coordinator, name: "skillforge")
        configuration.userContentController.addUserScript(WKUserScript(
            source: bootstrapScript(),
            injectionTime: .atDocumentStart,
            forMainFrameOnly: true
        ))
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.uiDelegate = context.coordinator
        webView.isInspectable = false
        context.coordinator.loadSecuredDocument(into: webView)
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {}

    static func dismantleUIView(_ webView: WKWebView, coordinator: Coordinator) {
        webView.configuration.userContentController.removeScriptMessageHandler(forName: "skillforge")
        webView.stopLoading()
    }

    private func securedHTML() -> String? {
        guard let html = try? String(contentsOf: htmlURL, encoding: .utf8) else { return nil }
        return PersonalAppHTMLCSPInserter.secure(html)
    }

    private func bootstrapScript() -> String {
        return PersonalAppBootstrapScriptBuilder.build(
            artifactID: artifactID,
            initialData: manifest.initialData,
            savedState: savedState
        )
    }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {
        private let artifactID: String
        private let securedHTML: String?
        private let stateSchema: [String: MobileJSONValue]
        private let onSnapshot: @MainActor (Data) -> Void
        private let onExternalURL: @MainActor (URL) -> Void
        private let onPermissionDenied: @MainActor (PersonalAppDeniedPermissionKind) -> Void
        private let now: () -> TimeInterval
        private var rateLimiter = PersonalAppBridgeRateLimiter()
        private var initialNavigationGate = PersonalAppInitialNavigationGate()

        init(
            artifactID: String,
            securedHTML: String?,
            stateSchema: [String: MobileJSONValue],
            onSnapshot: @escaping @MainActor (Data) -> Void,
            onExternalURL: @escaping @MainActor (URL) -> Void,
            onPermissionDenied: @escaping @MainActor (PersonalAppDeniedPermissionKind) -> Void,
            now: @escaping () -> TimeInterval = { ProcessInfo.processInfo.systemUptime }
        ) {
            self.artifactID = artifactID
            self.securedHTML = securedHTML
            self.stateSchema = stateSchema
            self.onSnapshot = onSnapshot
            self.onExternalURL = onExternalURL
            self.onPermissionDenied = onPermissionDenied
            self.now = now
        }

        func loadSecuredDocument(into webView: WKWebView) {
            guard let securedHTML else { return }
            initialNavigationGate.rearmForSecuredDocument()
            webView.loadHTMLString(securedHTML, baseURL: nil)
        }

        func userContentController(_ userContentController: WKUserContentController,
                                   didReceive message: WKScriptMessage) {
            guard let method = PersonalAppBridgeMessageParser.preflightMethod(
                handlerName: message.name,
                isMainFrame: message.frameInfo.isMainFrame,
                body: message.body,
                expectedArtifactID: artifactID
            ),
            rateLimiter.shouldAccept(method, now: now()),
            let command = PersonalAppBridgeMessageParser.parse(
                handlerName: message.name,
                isMainFrame: message.frameInfo.isMainFrame,
                body: message.body,
                expectedArtifactID: artifactID
            ) else { return }

            switch command {
            case .saveState(let data):
                PersonalAppValidatedStateAccess.live.save(
                    data,
                    artifactID: artifactID,
                    schema: stateSchema
                )
            case .submitSnapshot(let data):
                onSnapshot(data)
            case .requestOpenURL(let url):
                onExternalURL(url)
            }
        }

        func webView(_ webView: WKWebView,
                     decidePolicyFor navigationAction: WKNavigationAction) async -> WKNavigationActionPolicy {
            if initialNavigationGate.shouldAllow(
                isOtherNavigation: navigationAction.navigationType == .other,
                isMainFrame: navigationAction.targetFrame?.isMainFrame == true,
                scheme: navigationAction.request.url?.scheme
            ) {
                return .allow
            }
            return .cancel
        }

        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                     for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
            nil
        }

        @available(iOS 18.4, *)
        func webView(
            _ webView: WKWebView,
            runOpenPanelWith parameters: WKOpenPanelParameters,
            initiatedByFrame frame: WKFrameInfo,
            completionHandler: @escaping @MainActor @Sendable ([URL]?) -> Void
        ) {
            onPermissionDenied(.fileSelection)
            completionHandler(PersonalAppWebPermissionPolicy.openPanelResult)
        }

        func webView(
            _ webView: WKWebView,
            requestMediaCapturePermissionFor origin: WKSecurityOrigin,
            initiatedByFrame frame: WKFrameInfo,
            type: WKMediaCaptureType,
            decisionHandler: @escaping @MainActor @Sendable (WKPermissionDecision) -> Void
        ) {
            onPermissionDenied(.mediaCapture)
            decisionHandler(PersonalAppWebPermissionPolicy.permissionDecision)
        }

        func webView(
            _ webView: WKWebView,
            requestDeviceOrientationAndMotionPermissionFor origin: WKSecurityOrigin,
            initiatedByFrame frame: WKFrameInfo,
            decisionHandler: @escaping @MainActor @Sendable (WKPermissionDecision) -> Void
        ) {
            onPermissionDenied(.deviceOrientationAndMotion)
            decisionHandler(PersonalAppWebPermissionPolicy.permissionDecision)
        }

        func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
            loadSecuredDocument(into: webView)
        }
    }
}

enum PersonalAppStateStore {
    static func load(artifactID: String) -> Data? {
        try? Data(contentsOf: url(for: artifactID))
    }

    static func save(_ data: Data, artifactID: String) throws {
        let destination = url(for: artifactID)
        try FileManager.default.createDirectory(
            at: destination.deletingLastPathComponent(),
            withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        )
        try data.write(to: destination, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    static func reset(artifactID: String) {
        try? FileManager.default.removeItem(at: url(for: artifactID))
    }

    private static func url(for artifactID: String) -> URL {
        let digest = SHA256.hash(data: Data(artifactID.utf8))
            .map { String(format: "%02x", $0) }.joined()
        return FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appending(path: "SkillForgePersonalApps", directoryHint: .isDirectory)
            .appending(path: digest + ".json")
    }
}

struct PersonalAppValidatedStateAccess {
    let loadRaw: (String) -> Data?
    let saveRaw: (Data, String) throws -> Void

    static var live: Self {
        Self(
            loadRaw: { PersonalAppStateStore.load(artifactID: $0) },
            saveRaw: { data, artifactID in
                try PersonalAppStateStore.save(data, artifactID: artifactID)
            }
        )
    }

    func loadResult(
        artifactID: String,
        schema: [String: MobileJSONValue]
    ) -> PersonalAppValidatedStateLoadResult {
        guard let data = loadRaw(artifactID) else { return .missing }
        guard PersonalAppSnapshotValidator.validate(data: data, schema: schema) else {
            return .invalid
        }
        return .valid(data)
    }

    func load(artifactID: String, schema: [String: MobileJSONValue]) -> Data? {
        guard case .valid(let data) = loadResult(artifactID: artifactID, schema: schema) else {
            return nil
        }
        return data
    }

    @discardableResult
    func save(
        _ data: Data,
        artifactID: String,
        schema: [String: MobileJSONValue]
    ) -> Bool {
        guard PersonalAppSnapshotValidator.validate(data: data, schema: schema) else {
            return false
        }
        do {
            try saveRaw(data, artifactID)
            return true
        } catch {
            return false
        }
    }
}

enum PersonalAppValidatedStateLoadResult: Equatable {
    case missing
    case valid(Data)
    case invalid
}

enum PersonalAppSnapshotValidator {
    static let maximumSnapshotBytes = 64 * 1024
    static let maximumDepth = 8
    static let maximumSchemaNodeCount = 1_024
    static let maximumDataNodeCount = 8_192

    private enum SchemaType: String {
        case string
        case number
        case integer
        case boolean
        case object
        case array
    }

    private indirect enum SnapshotValue: Decodable {
        case string(String)
        case number(Decimal)
        case boolean(Bool)
        case object([String: SnapshotValue])
        case array([SnapshotValue])
        case null

        init(from decoder: Decoder) throws {
            let container = try decoder.singleValueContainer()
            if container.decodeNil() {
                self = .null
            } else if let value = try? container.decode(Bool.self) {
                self = .boolean(value)
            } else if let value = try? container.decode(String.self) {
                self = .string(value)
            } else if let value = try? container.decode(Decimal.self) {
                self = .number(value)
            } else if let value = try? container.decode([String: SnapshotValue].self) {
                self = .object(value)
            } else if let value = try? container.decode([SnapshotValue].self) {
                self = .array(value)
            } else {
                throw DecodingError.typeMismatch(
                    SnapshotValue.self,
                    .init(
                        codingPath: decoder.codingPath,
                        debugDescription: "Snapshot value is not valid JSON with a representable Decimal number."
                    )
                )
            }
        }
    }

    private struct TraversalBudget {
        let maximumDepth: Int
        private(set) var remainingNodes: Int

        init(maximumDepth: Int, maximumNodes: Int) {
            self.maximumDepth = maximumDepth
            remainingNodes = maximumNodes
        }

        mutating func consume(depth: Int) -> Bool {
            guard depth <= maximumDepth, remainingNodes > 0 else { return false }
            remainingNodes -= 1
            return true
        }
    }

    static func validate(data: Data, schema: [String: MobileJSONValue]) -> Bool {
        guard data.count <= maximumSnapshotBytes,
              isSchemaWithinBudget(schema),
              isValidSchema(schema),
              schemaType(in: schema) == .object,
              let value = try? JSONDecoder().decode(SnapshotValue.self, from: data),
              case .object = value,
              isDataWithinBudget(value) else { return false }
        return matches(value, schema: schema)
    }

    private static func isSchemaWithinBudget(_ schema: [String: MobileJSONValue]) -> Bool {
        var budget = TraversalBudget(
            maximumDepth: maximumDepth,
            maximumNodes: maximumSchemaNodeCount
        )
        return visitSchemaObject(schema, depth: 1, budget: &budget)
    }

    private static func visitSchemaObject(
        _ object: [String: MobileJSONValue],
        depth: Int,
        budget: inout TraversalBudget
    ) -> Bool {
        guard budget.consume(depth: depth) else { return false }
        return object.values.allSatisfy { value in
            visitSchemaValue(value, depth: depth + 1, budget: &budget)
        }
    }

    private static func visitSchemaValue(
        _ value: MobileJSONValue,
        depth: Int,
        budget: inout TraversalBudget
    ) -> Bool {
        guard budget.consume(depth: depth) else { return false }
        switch value {
        case let .object(children):
            return children.values.allSatisfy { child in
                visitSchemaValue(child, depth: depth + 1, budget: &budget)
            }
        case let .array(children):
            return children.allSatisfy { child in
                visitSchemaValue(child, depth: depth + 1, budget: &budget)
            }
        case .string, .number, .bool, .null:
            return true
        }
    }

    private static func isDataWithinBudget(_ value: SnapshotValue) -> Bool {
        var budget = TraversalBudget(
            maximumDepth: maximumDepth,
            maximumNodes: maximumDataNodeCount
        )
        return visitData(value, depth: 1, budget: &budget)
    }

    private static func visitData(
        _ value: SnapshotValue,
        depth: Int,
        budget: inout TraversalBudget
    ) -> Bool {
        guard budget.consume(depth: depth) else { return false }
        switch value {
        case let .object(object):
            return object.values.allSatisfy { child in
                visitData(child, depth: depth + 1, budget: &budget)
            }
        case let .array(array):
            return array.allSatisfy { child in
                visitData(child, depth: depth + 1, budget: &budget)
            }
        case .string, .number, .boolean, .null:
            return true
        }
    }

    private static func isValidSchema(_ schema: [String: MobileJSONValue]) -> Bool {
        guard let type = schemaType(in: schema), hasOnlySupportedKeywords(schema, type: type) else {
            return false
        }
        switch type {
        case .object:
            return isValidObjectSchema(schema)
        case .array:
            return isValidArraySchema(schema)
        case .string, .number, .integer, .boolean:
            return true
        }
    }

    private static func hasOnlySupportedKeywords(
        _ schema: [String: MobileJSONValue],
        type: SchemaType
    ) -> Bool {
        let allowed: Set<String>
        switch type {
        case .object:
            allowed = ["type", "properties", "required", "additionalProperties"]
        case .array:
            allowed = ["type", "items"]
        case .string, .number, .integer, .boolean:
            allowed = ["type"]
        }
        return schema.keys.allSatisfy(allowed.contains)
    }

    private static func isValidObjectSchema(_ schema: [String: MobileJSONValue]) -> Bool {
        if let propertiesValue = schema["properties"] {
            guard case let .object(properties) = propertiesValue,
                  properties.values.allSatisfy({ property in
                      guard case let .object(childSchema) = property else { return false }
                      return isValidSchema(childSchema)
                  }) else { return false }
        }
        if let requiredValue = schema["required"] {
            guard case let .array(required) = requiredValue,
                  required.allSatisfy({ if case .string = $0 { return true }; return false }) else {
                return false
            }
        }
        if let additionalValue = schema["additionalProperties"] {
            switch additionalValue {
            case .bool:
                break
            case let .object(childSchema):
                guard isValidSchema(childSchema) else { return false }
            default:
                return false
            }
        }
        return true
    }

    private static func isValidArraySchema(_ schema: [String: MobileJSONValue]) -> Bool {
        guard let itemsValue = schema["items"] else { return true }
        guard case let .object(itemSchema) = itemsValue else { return false }
        return isValidSchema(itemSchema)
    }

    private static func schemaType(in schema: [String: MobileJSONValue]) -> SchemaType? {
        guard case let .string(rawType)? = schema["type"] else { return nil }
        return SchemaType(rawValue: rawType)
    }

    private static func matches(_ value: SnapshotValue, schema: [String: MobileJSONValue]) -> Bool {
        guard let type = schemaType(in: schema) else { return false }
        switch type {
        case .string:
            guard case .string = value else { return false }
            return true
        case .number:
            guard case .number = value else { return false }
            return true
        case .integer:
            guard case let .number(number) = value else { return false }
            return isInteger(number)
        case .boolean:
            guard case .boolean = value else { return false }
            return true
        case .object:
            guard case let .object(object) = value else { return false }
            return matchesObject(object, schema: schema)
        case .array:
            guard case let .array(array) = value else { return false }
            return matchesArray(array, schema: schema)
        }
    }

    private static func matchesObject(
        _ object: [String: SnapshotValue],
        schema: [String: MobileJSONValue]
    ) -> Bool {
        let properties: [String: MobileJSONValue]
        if case let .object(value)? = schema["properties"] { properties = value } else { properties = [:] }

        if case let .array(required)? = schema["required"] {
            let requiredKeys = required.compactMap { value -> String? in
                guard case let .string(key) = value else { return nil }
                return key
            }
            guard requiredKeys.allSatisfy(object.keys.contains) else { return false }
        }

        return object.allSatisfy { key, value in
            if case let .object(propertySchema)? = properties[key] {
                return matches(value, schema: propertySchema)
            }
            switch schema["additionalProperties"] {
            case .bool(false)?:
                return false
            case let .object(additionalSchema)?:
                return matches(value, schema: additionalSchema)
            default:
                return true
            }
        }
    }

    private static func matchesArray(
        _ array: [SnapshotValue],
        schema: [String: MobileJSONValue]
    ) -> Bool {
        guard let itemsValue = schema["items"] else { return true }
        guard case let .object(itemSchema) = itemsValue else { return false }
        return array.allSatisfy { matches($0, schema: itemSchema) }
    }

    private static func isInteger(_ number: Decimal) -> Bool {
        var input = number
        var rounded = Decimal()
        NSDecimalRound(&rounded, &input, 0, .plain)
        return rounded == number
    }
}

private struct PersonalAppShareItem: Identifiable {
    let id = UUID()
    let url: URL

    init(data: Data) {
        let url = FileManager.default.temporaryDirectory
            .appending(path: "personal-app-state-\(id.uuidString).json")
        try? data.write(to: url, options: .atomic)
        self.url = url
    }
}

private struct PersonalAppActivityView: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
