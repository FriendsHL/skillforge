import Foundation

struct MobilePairingClaimResponse: Decodable {
    let deviceId: String
    let deviceToken: String
    let serverName: String
    let user: MobileUserSummary
    let defaultAgent: MobileAgentSummary?
    let features: MobileFeatureFlags
}

struct MobileUserSummary: Decodable {
    let id: Int64
}

struct MobileAgentSummary: Decodable, Equatable {
    let id: Int64?
    let name: String
}

enum MobileToolAccess: String, Decodable, Equatable, ExpressibleByStringLiteral {
    case all
    case allowlist
    case unknown

    init(stringLiteral value: String) {
        self = Self(rawValue: value) ?? .unknown
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let rawValue = (try? container.decode(String.self)) ?? ""
        self = Self(rawValue: rawValue) ?? .unknown
    }
}

struct MobileAgentCatalogItem: Decodable, Identifiable, Equatable {
    let id: Int64
    let name: String
    let description: String?
    let role: String?
    let modelId: String?
    let status: String
    let source: String
    let visibility: String
    let isDefault: Bool
    let executionMode: String?
    let skillCount: Int
    let toolCount: Int
    let toolAccess: MobileToolAccess
    let configurationAccess: String

    init(
        id: Int64,
        name: String,
        description: String? = nil,
        role: String? = nil,
        modelId: String? = nil,
        status: String = "active",
        source: String = "owned",
        visibility: String = "private",
        isDefault: Bool = false,
        executionMode: String? = nil,
        skillCount: Int = 0,
        toolCount: Int = 0,
        toolAccess: MobileToolAccess = .unknown,
        configurationAccess: String = "detail"
    ) {
        self.id = id
        self.name = name
        self.description = description
        self.role = role
        self.modelId = modelId
        self.status = status
        self.source = source
        self.visibility = visibility
        self.isDefault = isDefault
        self.executionMode = executionMode
        self.skillCount = skillCount
        self.toolCount = toolCount
        self.toolAccess = toolAccess
        self.configurationAccess = configurationAccess
    }

    enum CodingKeys: String, CodingKey {
        case id, name, description, role, modelId, status, source, visibility
        case isDefault, executionMode, skillCount, toolCount, toolAccess, configurationAccess
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(Int64.self, forKey: .id)
        name = try container.decode(String.self, forKey: .name)
        description = try container.decodeIfPresent(String.self, forKey: .description)
        role = try container.decodeIfPresent(String.self, forKey: .role)
        modelId = try container.decodeIfPresent(String.self, forKey: .modelId)
        status = try container.decodeIfPresent(String.self, forKey: .status) ?? "active"
        source = try container.decodeIfPresent(String.self, forKey: .source) ?? "owned"
        visibility = try container.decodeIfPresent(String.self, forKey: .visibility) ?? "private"
        isDefault = try container.decodeIfPresent(Bool.self, forKey: .isDefault) ?? false
        executionMode = try container.decodeIfPresent(String.self, forKey: .executionMode)
        skillCount = try container.decodeIfPresent(Int.self, forKey: .skillCount) ?? 0
        toolCount = try container.decodeIfPresent(Int.self, forKey: .toolCount) ?? 0
        toolAccess = try container.decodeIfPresent(
            MobileToolAccess.self,
            forKey: .toolAccess
        ) ?? .unknown
        configurationAccess = try container.decodeIfPresent(
            String.self,
            forKey: .configurationAccess
        ) ?? "detail"
    }

    var summary: MobileAgentSummary {
        MobileAgentSummary(id: id, name: name)
    }
}

struct MobilePromptFieldMetadata: Decodable, Equatable {
    let configured: Bool
    let characterCount: Int
}

struct MobileAgentPromptMetadata: Decodable, Equatable {
    let agent: MobilePromptFieldMetadata
    let soul: MobilePromptFieldMetadata
    let tools: MobilePromptFieldMetadata
}

struct MobileAgentDetail: Decodable, Identifiable, Equatable {
    let id: Int64
    let name: String
    let description: String?
    let role: String?
    let modelId: String?
    let status: String
    let source: String
    let visibility: String
    let isDefault: Bool
    let executionMode: String?
    let skillCount: Int
    let toolCount: Int
    let toolAccess: MobileToolAccess
    let configurationAccess: String
    let maxLoops: Int?
    let thinkingMode: String?
    let reasoningEffort: String?
    let skillNames: [String]?
    let toolNames: [String]?
    let enabledSystemSkillCount: Int
    let promptMetadata: MobileAgentPromptMetadata?

    enum CodingKeys: String, CodingKey {
        case id, name, description, role, modelId, status, source, visibility
        case isDefault, executionMode, skillCount, toolCount, toolAccess, configurationAccess
        case maxLoops, thinkingMode, reasoningEffort, skillNames, toolNames
        case enabledSystemSkillCount, promptMetadata, agentPrompt, soulPrompt, toolsPrompt
    }

    init(
        id: Int64,
        name: String,
        description: String? = nil,
        role: String? = nil,
        modelId: String? = nil,
        status: String = "active",
        source: String = "owned",
        visibility: String = "private",
        isDefault: Bool = false,
        executionMode: String? = nil,
        skillCount: Int = 0,
        toolCount: Int = 0,
        toolAccess: MobileToolAccess = .unknown,
        configurationAccess: String = "detail",
        maxLoops: Int? = nil,
        thinkingMode: String? = nil,
        reasoningEffort: String? = nil,
        skillNames: [String]? = nil,
        toolNames: [String]? = nil,
        enabledSystemSkillCount: Int = 0,
        promptMetadata: MobileAgentPromptMetadata? = nil
    ) {
        self.id = id
        self.name = name
        self.description = description
        self.role = role
        self.modelId = modelId
        self.status = status
        self.source = source
        self.visibility = visibility
        self.isDefault = isDefault
        self.executionMode = executionMode
        self.skillCount = skillCount
        self.toolCount = toolCount
        self.toolAccess = toolAccess
        self.configurationAccess = configurationAccess
        self.maxLoops = maxLoops
        self.thinkingMode = thinkingMode
        self.reasoningEffort = reasoningEffort
        self.skillNames = skillNames
        self.toolNames = toolNames
        self.enabledSystemSkillCount = enabledSystemSkillCount
        self.promptMetadata = promptMetadata
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(Int64.self, forKey: .id)
        name = try container.decode(String.self, forKey: .name)
        description = try container.decodeIfPresent(String.self, forKey: .description)
        role = try container.decodeIfPresent(String.self, forKey: .role)
        modelId = try container.decodeIfPresent(String.self, forKey: .modelId)
        status = try container.decodeIfPresent(String.self, forKey: .status) ?? "active"
        source = try container.decodeIfPresent(String.self, forKey: .source) ?? "owned"
        visibility = try container.decodeIfPresent(String.self, forKey: .visibility) ?? "private"
        isDefault = try container.decodeIfPresent(Bool.self, forKey: .isDefault) ?? false
        executionMode = try container.decodeIfPresent(String.self, forKey: .executionMode)
        skillCount = try container.decodeIfPresent(Int.self, forKey: .skillCount) ?? 0
        toolCount = try container.decodeIfPresent(Int.self, forKey: .toolCount) ?? 0
        toolAccess = try container.decodeIfPresent(
            MobileToolAccess.self,
            forKey: .toolAccess
        ) ?? .unknown
        configurationAccess = try container.decodeIfPresent(
            String.self,
            forKey: .configurationAccess
        ) ?? "detail"
        maxLoops = try container.decodeIfPresent(Int.self, forKey: .maxLoops)
        thinkingMode = try container.decodeIfPresent(String.self, forKey: .thinkingMode)
        reasoningEffort = try container.decodeIfPresent(String.self, forKey: .reasoningEffort)
        skillNames = try container.decodeIfPresent([String].self, forKey: .skillNames)
        toolNames = try container.decodeIfPresent([String].self, forKey: .toolNames)
        enabledSystemSkillCount = try container.decodeIfPresent(
            Int.self,
            forKey: .enabledSystemSkillCount
        ) ?? 0

        if let grouped = try container.decodeIfPresent(
            MobileAgentPromptMetadata.self,
            forKey: .promptMetadata
        ) {
            promptMetadata = grouped
        } else if let agentPrompt = try container.decodeIfPresent(
            MobilePromptFieldMetadata.self,
            forKey: .agentPrompt
        ), let soulPrompt = try container.decodeIfPresent(
            MobilePromptFieldMetadata.self,
            forKey: .soulPrompt
        ), let toolsPrompt = try container.decodeIfPresent(
            MobilePromptFieldMetadata.self,
            forKey: .toolsPrompt
        ) {
            promptMetadata = MobileAgentPromptMetadata(
                agent: agentPrompt,
                soul: soulPrompt,
                tools: toolsPrompt
            )
        } else {
            promptMetadata = nil
        }
    }
}

struct MobileFeatureFlags: Decodable {
    let chat: Bool
    let attachments: Bool
    let push: Bool
}

struct MobileMeResponse: Decodable {
    let user: MobileUserSummary
    let device: MobileDeviceSummary
    let defaultAgent: MobileAgentSummary?
    let features: MobileFeatureFlags
}

struct MobileSession: Decodable, Identifiable, Equatable {
    let id: String
    let userId: Int64?
    let agentId: Int64
    let title: String?
    let status: String?
    let runtimeStatus: String?
    let messageCount: Int?
    let updatedAt: String?
}

struct MobileSessionMessage: Decodable, Identifiable, Equatable {
    let seqNo: Int64
    let role: String
    let msgType: String?
    let messageType: String?
    let controlId: String?
    let answeredAt: String?
    let metadata: [String: MobileJSONValue]
    let traceId: String?
    let createdAt: String?
    let reasoningContent: String?
    let displayText: String
    let contentBlocks: [MobileContentBlock]

    var id: Int64 { seqNo }

    enum CodingKeys: String, CodingKey {
        case seqNo
        case role
        case content
        case msgType
        case messageType
        case controlId
        case answeredAt
        case metadata
        case traceId
        case createdAt
        case reasoningContent
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        seqNo = try container.decode(Int64.self, forKey: .seqNo)
        role = try container.decode(String.self, forKey: .role)
        msgType = try container.decodeIfPresent(String.self, forKey: .msgType)
        messageType = try container.decodeIfPresent(String.self, forKey: .messageType)
        controlId = try container.decodeIfPresent(String.self, forKey: .controlId)
        answeredAt = try container.decodeIfPresent(String.self, forKey: .answeredAt)
        metadata = try container.decodeIfPresent(
            [String: MobileJSONValue].self,
            forKey: .metadata
        ) ?? [:]
        traceId = try container.decodeIfPresent(String.self, forKey: .traceId)
        createdAt = try container.decodeIfPresent(String.self, forKey: .createdAt)
        reasoningContent = try container.decodeIfPresent(String.self, forKey: .reasoningContent)
        if container.contains(.content) {
            let decodedContent = try Self.decodeContent(from: container.superDecoder(forKey: .content))
            displayText = decodedContent.displayText
            contentBlocks = decodedContent.blocks
        } else {
            displayText = ""
            contentBlocks = []
        }
    }

    private static func decodeContent(from decoder: Decoder) throws -> (displayText: String, blocks: [MobileContentBlock]) {
        if let text = try? String(from: decoder) {
            return (text, [.text(text)])
        }
        if let blocks = try? [MobileContentBlock](from: decoder) {
            return (blocks.compactMap(\.text).filter { !$0.isEmpty }.joined(separator: "\n"), blocks)
        }
        if let value = try? MobileJSONValue(from: decoder) {
            return (value.displayText, [])
        }
        return ("", [])
    }
}

struct MobileSendMessageResponse: Decodable, Equatable {
    let sessionId: String
    let status: String
}

struct MobileActionResponse: Decodable, Equatable {
    let status: String
}

enum MobileConfirmationDecision: String, Encodable, Equatable {
    case approved
    case denied
}

struct MobileUploadedAttachment: Decodable, Identifiable, Equatable {
    let id: String
    let sessionId: String
    let kind: String
    let mimeType: String
    let filename: String
    let sizeBytes: Int64
    let pageCount: Int?
    let status: String
}

struct MobilePendingConfirmation: Decodable, Identifiable, Equatable {
    let confirmationId: String
    let title: String
    let description: String?
    let installTool: String?
    let installTarget: String?
    let commandPreview: String?

    var id: String { confirmationId }
}

struct MobileDeviceSummary: Decodable {
    let id: String
    let deviceName: String
    let scopes: [String]
}

struct MobileScheduledTask: Decodable, Identifiable, Equatable {
    let id: Int64
    let name: String
    let agentId: Int64
    let cronExpr: String?
    let oneShotAt: String?
    let timezone: String
    let promptPreview: String?
    let sessionMode: String
    let enabled: Bool
    let nextFireAt: String?
    let lastFireAt: String?
    let status: String
    let system: Bool

    func withEnabled(_ enabled: Bool) -> MobileScheduledTask {
        MobileScheduledTask(
            id: id,
            name: name,
            agentId: agentId,
            cronExpr: cronExpr,
            oneShotAt: oneShotAt,
            timezone: timezone,
            promptPreview: promptPreview,
            sessionMode: sessionMode,
            enabled: enabled,
            nextFireAt: nextFireAt,
            lastFireAt: lastFireAt,
            status: status,
            system: system
        )
    }
}

struct MobileScheduledTaskRun: Decodable, Identifiable, Equatable {
    let id: Int64
    let taskId: Int64
    let triggeredAt: String
    let finishedAt: String?
    let status: String
    let errorMessage: String?
    let sessionId: String?
    let manual: Bool
}

struct MobileScheduleActionResponse: Decodable, Equatable {
    let taskId: Int64
    let status: String
}

enum MobileApiError: Error, LocalizedError {
    case invalidResponse
    case missingDeviceToken
    case httpStatus(Int, String)

    var errorDescription: String? {
        switch self {
        case .invalidResponse:
            return "Invalid SkillForge server response"
        case .missingDeviceToken:
            return "Missing SkillForge mobile device token"
        case let .httpStatus(status, body):
            return body.isEmpty ? "SkillForge request failed with HTTP \(status)" : body
        }
    }
}

struct MobileApiClient {
    let baseURL: URL
    let deviceToken: String?
    let session: URLSession

    init(baseURL: URL, deviceToken: String? = nil, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.deviceToken = deviceToken
        self.session = session
    }

    func claimPairing(
        payload: PairingPayload,
        deviceName: String,
        appVersion: String
    ) async throws -> MobilePairingClaimResponse {
        struct ClaimRequest: Encodable {
            let pairingSecret: String
            let deviceName: String
            let platform: String
            let appVersion: String
        }

        let body = ClaimRequest(
            pairingSecret: payload.pairingSecret,
            deviceName: deviceName,
            platform: "ios",
            appVersion: appVersion
        )
        return try await send(
            path: "/api/mobile/pairings/\(payload.pairingId)/claim",
            method: "POST",
            body: body,
            authorized: false
        )
    }

    func me() async throws -> MobileMeResponse {
        try await send(path: "/api/mobile/client/me", method: "GET", body: Optional<String>.none, authorized: true)
    }

    func listAgents() async throws -> [MobileAgentCatalogItem] {
        try await send(
            path: "/api/mobile/client/agents",
            method: "GET",
            body: Optional<String>.none,
            authorized: true
        )
    }

    func getAgent(id: Int64) async throws -> MobileAgentDetail {
        try await send(
            path: "/api/mobile/client/agents/\(id)",
            method: "GET",
            body: Optional<String>.none,
            authorized: true
        )
    }

    func listSessions() async throws -> [MobileSession] {
        try await send(path: "/api/mobile/client/sessions", method: "GET", body: Optional<String>.none, authorized: true)
    }

    func listSchedules() async throws -> [MobileScheduledTask] {
        try await send(
            path: "/api/mobile/client/schedules",
            method: "GET",
            body: Optional<String>.none,
            authorized: true
        )
    }

    func listScheduleRuns(taskId: Int64, limit: Int = 20) async throws -> [MobileScheduledTaskRun] {
        try await send(
            path: "/api/mobile/client/schedules/\(taskId)/runs",
            method: "GET",
            body: Optional<String>.none,
            authorized: true,
            queryItems: [URLQueryItem(name: "limit", value: String(limit))]
        )
    }

    func triggerSchedule(taskId: Int64) async throws -> MobileScheduleActionResponse {
        try await send(
            path: "/api/mobile/client/schedules/\(taskId)/trigger",
            method: "POST",
            body: Optional<String>.none,
            authorized: true
        )
    }

    func setScheduleEnabled(taskId: Int64, enabled: Bool) async throws -> MobileScheduledTask {
        struct EnabledRequest: Encodable {
            let enabled: Bool
        }

        return try await send(
            path: "/api/mobile/client/schedules/\(taskId)/enabled",
            method: "PUT",
            body: EnabledRequest(enabled: enabled),
            authorized: true
        )
    }

    func createSession(agentId: Int64? = nil) async throws -> MobileSession {
        struct CreateSessionRequest: Encodable {
            let agentId: Int64?
        }

        return try await send(
            path: "/api/mobile/client/sessions",
            method: "POST",
            body: CreateSessionRequest(agentId: agentId),
            authorized: true
        )
    }

    func getSession(sessionId: String) async throws -> MobileSession {
        try await send(
            path: "/api/mobile/client/sessions/\(sessionId)",
            method: "GET",
            body: Optional<String>.none,
            authorized: true
        )
    }

    func getMessages(sessionId: String) async throws -> [MobileSessionMessage] {
        try await send(
            path: "/api/mobile/client/sessions/\(sessionId)/messages",
            method: "GET",
            body: Optional<String>.none,
            authorized: true
        )
    }

    func getPendingConfirmations(sessionId: String) async throws -> [MobilePendingConfirmation] {
        try await send(
            path: "/api/mobile/client/sessions/\(sessionId)/pending-confirmations",
            method: "GET",
            body: Optional<String>.none,
            authorized: true
        )
    }

    func sendMessage(
        sessionId: String,
        text: String,
        attachmentIds: [String] = []
    ) async throws -> MobileSendMessageResponse {
        struct SendMessageRequest: Encodable {
            let message: String
            let attachmentIds: [String]
        }

        return try await send(
            path: "/api/mobile/client/sessions/\(sessionId)/messages",
            method: "POST",
            body: SendMessageRequest(message: text, attachmentIds: attachmentIds),
            authorized: true
        )
    }

    func answerAsk(sessionId: String, askId: String, answer: String) async throws -> MobileActionResponse {
        struct AnswerRequest: Encodable {
            let askId: String
            let answer: String
        }

        return try await send(
            path: "/api/mobile/client/sessions/\(sessionId)/answer",
            method: "POST",
            body: AnswerRequest(askId: askId, answer: answer),
            authorized: true
        )
    }

    func answerConfirmation(
        sessionId: String,
        confirmationId: String,
        decision: MobileConfirmationDecision
    ) async throws -> MobileActionResponse {
        struct ConfirmationRequest: Encodable {
            let confirmationId: String
            let decision: MobileConfirmationDecision
        }

        return try await send(
            path: "/api/mobile/client/sessions/\(sessionId)/confirmation",
            method: "POST",
            body: ConfirmationRequest(confirmationId: confirmationId, decision: decision),
            authorized: true
        )
    }

    func uploadAttachment(
        sessionId: String,
        fileURL: URL,
        mimeType: String
    ) async throws -> MobileUploadedAttachment {
        guard let deviceToken, !deviceToken.isBlank else {
            throw MobileApiError.missingDeviceToken
        }
        let didAccess = fileURL.startAccessingSecurityScopedResource()
        defer {
            if didAccess {
                fileURL.stopAccessingSecurityScopedResource()
            }
        }

        let fileData = try Data(contentsOf: fileURL, options: .mappedIfSafe)
        let boundary = "SkillForge-\(UUID().uuidString)"
        let filename = fileURL.lastPathComponent
            .replacingOccurrences(of: "\"", with: "_")
            .replacingOccurrences(of: "\r", with: "_")
            .replacingOccurrences(of: "\n", with: "_")
        var body = Data()
        body.appendUTF8("--\(boundary)\r\n")
        body.appendUTF8("Content-Disposition: form-data; name=\"file\"; filename=\"\(filename)\"\r\n")
        body.appendUTF8("Content-Type: \(mimeType)\r\n\r\n")
        body.append(fileData)
        body.appendUTF8("\r\n--\(boundary)--\r\n")

        var request = URLRequest(
            url: baseURL.skillForgeAppendingPath(
                "/api/mobile/client/sessions/\(sessionId)/attachments"
            )
        )
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("Bearer \(deviceToken)", forHTTPHeaderField: "Authorization")
        request.setValue(
            "multipart/form-data; boundary=\(boundary)",
            forHTTPHeaderField: "Content-Type"
        )
        request.httpBody = body

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw MobileApiError.invalidResponse
        }
        guard (200..<300).contains(http.statusCode) else {
            throw MobileApiError.httpStatus(
                http.statusCode,
                String(data: data, encoding: .utf8) ?? ""
            )
        }
        return try JSONDecoder().decode(MobileUploadedAttachment.self, from: data)
    }

    func chatWebSocketRequest(sessionId: String) throws -> URLRequest {
        guard let deviceToken, !deviceToken.isBlank else {
            throw MobileApiError.missingDeviceToken
        }
        var url = baseURL.skillForgeAppendingPath("/ws/mobile/chat/\(sessionId)")
        var components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        if components?.scheme == "https" {
            components?.scheme = "wss"
        } else {
            components?.scheme = "ws"
        }
        if let built = components?.url {
            url = built
        }
        var request = URLRequest(url: url)
        request.setValue("Bearer \(deviceToken)", forHTTPHeaderField: "Authorization")
        return request
    }

    private func send<RequestBody: Encodable, ResponseBody: Decodable>(
        path: String,
        method: String,
        body: RequestBody?,
        authorized: Bool,
        queryItems: [URLQueryItem] = []
    ) async throws -> ResponseBody {
        let pathURL = baseURL.skillForgeAppendingPath(path)
        var components = URLComponents(url: pathURL, resolvingAgainstBaseURL: false)
        if !queryItems.isEmpty {
            components?.queryItems = queryItems
        }
        guard let requestURL = components?.url else {
            throw MobileApiError.invalidResponse
        }
        var request = URLRequest(url: requestURL)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        if let body {
            request.httpBody = try JSONEncoder().encode(body)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }

        if authorized, let deviceToken {
            request.setValue("Bearer \(deviceToken)", forHTTPHeaderField: "Authorization")
        }

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw MobileApiError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else {
            throw MobileApiError.httpStatus(http.statusCode, String(data: data, encoding: .utf8) ?? "")
        }
        return try JSONDecoder().decode(ResponseBody.self, from: data)
    }
}

private extension String {
    var isBlank: Bool {
        trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}

extension URL {
    func skillForgeAppendingPath(_ path: String) -> URL {
        var url = self
        for component in path.split(separator: "/") {
            url.append(path: String(component))
        }
        return url
    }
}

private extension Data {
    mutating func appendUTF8(_ value: String) {
        append(Data(value.utf8))
    }
}

struct MobileContentBlock: Decodable, Equatable {
    let type: String
    let text: String?
    let id: String?
    let name: String?
    let input: MobileJSONValue?
    let toolUseId: String?
    let content: MobileJSONValue?
    let isError: Bool
    let attachmentId: String?
    let mimeType: String?
    let filename: String?
    let pageCount: Int?
    let sheetCount: Int?
    let byteSize: Int64?
    let caption: String?

    enum CodingKeys: String, CodingKey {
        case type
        case text
        case id
        case name
        case input
        case toolUseId
        case toolUseIdSnake = "tool_use_id"
        case content
        case isError
        case isErrorSnake = "is_error"
        case attachmentId
        case attachmentIdSnake = "attachment_id"
        case mimeType
        case mimeTypeSnake = "mime_type"
        case filename
        case fileName
        case fileNameSnake = "file_name"
        case pageCount
        case pageCountSnake = "page_count"
        case sheetCount
        case sheetCountSnake = "sheet_count"
        case byteSize
        case byteSizeSnake = "byte_size"
        case fileSize
        case fileSizeSnake = "file_size"
        case size
        case caption
    }

    init(
        type: String,
        text: String? = nil,
        id: String? = nil,
        name: String? = nil,
        input: MobileJSONValue? = nil,
        toolUseId: String? = nil,
        content: MobileJSONValue? = nil,
        isError: Bool = false,
        attachmentId: String? = nil,
        mimeType: String? = nil,
        filename: String? = nil,
        pageCount: Int? = nil,
        sheetCount: Int? = nil,
        byteSize: Int64? = nil,
        caption: String? = nil
    ) {
        self.type = type
        self.text = text
        self.id = id
        self.name = name
        self.input = input
        self.toolUseId = toolUseId
        self.content = content
        self.isError = isError
        self.attachmentId = attachmentId
        self.mimeType = mimeType
        self.filename = filename
        self.pageCount = pageCount
        self.sheetCount = sheetCount
        self.byteSize = byteSize
        self.caption = caption
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        type = try container.decodeIfPresent(String.self, forKey: .type) ?? "text"
        text = try container.decodeIfPresent(String.self, forKey: .text)
        id = try container.decodeIfPresent(String.self, forKey: .id)
        name = try container.decodeIfPresent(String.self, forKey: .name)
        input = try container.decodeIfPresent(MobileJSONValue.self, forKey: .input)
        toolUseId = try container.decodeIfPresent(String.self, forKey: .toolUseIdSnake)
            ?? container.decodeIfPresent(String.self, forKey: .toolUseId)
            ?? id
        content = try container.decodeIfPresent(MobileJSONValue.self, forKey: .content)
        isError = try container.decodeIfPresent(Bool.self, forKey: .isErrorSnake)
            ?? container.decodeIfPresent(Bool.self, forKey: .isError)
            ?? false
        attachmentId = try container.decodeIfPresent(String.self, forKey: .attachmentIdSnake)
            ?? container.decodeIfPresent(String.self, forKey: .attachmentId)
        mimeType = try container.decodeIfPresent(String.self, forKey: .mimeTypeSnake)
            ?? container.decodeIfPresent(String.self, forKey: .mimeType)
        filename = try container.decodeIfPresent(String.self, forKey: .filename)
            ?? container.decodeIfPresent(String.self, forKey: .fileNameSnake)
            ?? container.decodeIfPresent(String.self, forKey: .fileName)
        pageCount = try container.decodeIfPresent(Int.self, forKey: .pageCountSnake)
            ?? container.decodeIfPresent(Int.self, forKey: .pageCount)
        sheetCount = try container.decodeIfPresent(Int.self, forKey: .sheetCountSnake)
            ?? container.decodeIfPresent(Int.self, forKey: .sheetCount)
        let snakeByteSize = try container.decodeIfPresent(Int64.self, forKey: .byteSizeSnake)
        let camelByteSize = try container.decodeIfPresent(Int64.self, forKey: .byteSize)
        let snakeFileSize = try container.decodeIfPresent(Int64.self, forKey: .fileSizeSnake)
        let camelFileSize = try container.decodeIfPresent(Int64.self, forKey: .fileSize)
        let genericSize = try container.decodeIfPresent(Int64.self, forKey: .size)
        byteSize = snakeByteSize ?? camelByteSize ?? snakeFileSize ?? camelFileSize ?? genericSize
        caption = try container.decodeIfPresent(String.self, forKey: .caption)
    }

    static func text(_ text: String) -> MobileContentBlock {
        MobileContentBlock(type: "text", text: text)
    }

    var attachment: ChatAttachment? {
        guard type.lowercased().hasSuffix("_ref"), let attachmentId, !attachmentId.isBlank else {
            return nil
        }
        let displayFilename = filename.flatMap { $0.isBlank ? nil : $0 } ?? "Attachment"
        return ChatAttachment(
            id: attachmentId,
            kind: .init(blockType: type),
            mimeType: mimeType,
            filename: displayFilename,
            pageCount: pageCount,
            sheetCount: sheetCount,
            byteSize: byteSize,
            caption: caption?.isBlank == false ? caption : nil
        )
    }
}

enum MobileJSONValue: Decodable, Equatable {
    case string(String)
    case number(String)
    case bool(Bool)
    case object([String: MobileJSONValue])
    case array([MobileJSONValue])
    case null

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode(Int64.self) {
            self = .number(String(value))
        } else if let value = try? container.decode(Double.self) {
            self = .number(String(value))
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode([MobileJSONValue].self) {
            self = .array(value)
        } else if let value = try? container.decode([String: MobileJSONValue].self) {
            self = .object(value)
        } else {
            self = .null
        }
    }

    var displayText: String {
        switch self {
        case let .string(value), let .number(value):
            return value
        case let .bool(value):
            return value ? "true" : "false"
        case let .object(value):
            if case let .string(text)? = value["text"] {
                return text
            }
            if case let .string(text)? = value["content"] {
                return text
            }
            return ""
        case let .array(values):
            return values.map(\.displayText).filter { !$0.isEmpty }.joined(separator: "\n")
        case .null:
            return ""
        }
    }

    var previewText: String {
        switch self {
        case let .string(value):
            return value
        case let .number(value):
            return value
        case let .bool(value):
            return value ? "true" : "false"
        case let .object(value):
            let parts = value.keys.sorted().map { key in
                "\"\(key)\": \(value[key]?.previewText ?? "")"
            }
            return "{ \(parts.joined(separator: ", ")) }"
        case let .array(values):
            return values.map(\.previewText).joined(separator: "\n")
        case .null:
            return ""
        }
    }
}
