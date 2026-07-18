import Foundation

struct MobileChatEvent: Decodable, Equatable {
    let type: String
    let sessionId: String
    let status: String?
    let runtimeStatus: String?
    let step: String?
    let runtimeStep: String?
    let error: String?
    let runtimeError: String?
    let failureSource: MobileRuntimeFailureSource?
    let failureCode: String?
    let retryable: Bool?
    let sideEffects: MobileRuntimeSideEffects?
    let delta: String?
    let text: String?
    let toolUseId: String?
    let toolName: String?
    let name: String?
    let input: MobileJSONValue?
    let jsonFragment: String?
    let message: MobileRealtimeMessage?
    let askId: String?
    let question: String?
    let context: String?
    let options: [MobileInteractionOption]?
    let allowOther: Bool?
    let payload: MobileConfirmationEventPayload?
    let title: String?
    let messageCount: Int?
    let updatedAt: String?

    enum CodingKeys: String, CodingKey {
        case type, sessionId, status, runtimeStatus, step, runtimeStep, error, runtimeError
        case failureSource, failureCode, retryable, sideEffects
        case delta, text, toolUseId, toolName, name, input, jsonFragment, message
        case askId, question, context, options, allowOther, payload
        case title, messageCount, updatedAt
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        type = try container.decode(String.self, forKey: .type)
        sessionId = try container.decode(String.self, forKey: .sessionId)
        status = try container.decodeIfPresent(String.self, forKey: .status)
        runtimeStatus = try? container.decode(String.self, forKey: .runtimeStatus)
        step = try container.decodeIfPresent(String.self, forKey: .step)
        runtimeStep = try? container.decode(String.self, forKey: .runtimeStep)
        error = try container.decodeIfPresent(String.self, forKey: .error)
        runtimeError = try? container.decode(String.self, forKey: .runtimeError)
        failureSource = try? container.decode(MobileRuntimeFailureSource.self, forKey: .failureSource)
        failureCode = try? container.decode(String.self, forKey: .failureCode)
        retryable = try? container.decode(Bool.self, forKey: .retryable)
        sideEffects = try? container.decode(MobileRuntimeSideEffects.self, forKey: .sideEffects)
        delta = try container.decodeIfPresent(String.self, forKey: .delta)
        text = try container.decodeIfPresent(String.self, forKey: .text)
        toolUseId = try container.decodeIfPresent(String.self, forKey: .toolUseId)
        toolName = try container.decodeIfPresent(String.self, forKey: .toolName)
        name = try container.decodeIfPresent(String.self, forKey: .name)
        input = try container.decodeIfPresent(MobileJSONValue.self, forKey: .input)
        jsonFragment = try container.decodeIfPresent(String.self, forKey: .jsonFragment)
        message = try container.decodeIfPresent(MobileRealtimeMessage.self, forKey: .message)
        askId = try container.decodeIfPresent(String.self, forKey: .askId)
        question = try container.decodeIfPresent(String.self, forKey: .question)
        context = try container.decodeIfPresent(String.self, forKey: .context)
        options = try container.decodeIfPresent([MobileInteractionOption].self, forKey: .options)
        allowOther = try container.decodeIfPresent(Bool.self, forKey: .allowOther)
        payload = try container.decodeIfPresent(MobileConfirmationEventPayload.self, forKey: .payload)
        title = try? container.decode(String.self, forKey: .title)
        messageCount = try? container.decode(Int.self, forKey: .messageCount)
        updatedAt = try? container.decode(String.self, forKey: .updatedAt)
    }

    var assistantTextDelta: String? {
        delta ?? text
    }

    var resolvedToolName: String? {
        toolName ?? name
    }
}

enum MobileRuntimeSessionReducer {
    static func applying(event: MobileChatEvent, to session: MobileSession) -> MobileSession {
        guard event.sessionId == session.id,
              event.type == "session_status" || event.type == "session_updated"
        else { return session }

        guard let runtimeStatus = resolvedRuntimeStatus(for: event) else {
            return replacingMetadata(of: session, with: event)
        }
        let normalizedStatus = normalize(runtimeStatus)
        let isFailure = normalizedStatus == "error" || normalizedStatus == "failed"
        let eventStep = event.runtimeStep ?? event.step
        let isCancellation = normalizedStatus == "cancelled"
            || normalizedStatus == "canceled"
            || (normalizedStatus == "idle" && isCancellationStep(eventStep))
        return MobileSession(
            id: session.id,
            userId: session.userId,
            agentId: session.agentId,
            title: event.title ?? session.title,
            status: session.status,
            runtimeStatus: runtimeStatus,
            runtimeStep: isFailure ? eventStep : (isCancellation ? "cancelled" : nil),
            runtimeError: isFailure ? resolvedRuntimeError(for: event) : nil,
            failureSource: isFailure ? event.failureSource : nil,
            failureCode: isFailure ? event.failureCode : nil,
            retryable: isFailure ? event.retryable : nil,
            sideEffects: isFailure ? event.sideEffects : nil,
            messageCount: event.messageCount ?? session.messageCount,
            updatedAt: event.updatedAt ?? session.updatedAt
        )
    }

    static func reconcilingTerminalMetadata(
        current: MobileSession,
        incoming: MobileSession,
        expectedRuntimeStatus: String
    ) -> MobileSession {
        guard current.id == incoming.id else { return current }
        guard RuntimeMetadataCatchUpPolicy.shouldContinue(
            current,
            expectedRuntimeStatus: expectedRuntimeStatus
        ) else { return current }
        guard normalize(incoming.runtimeStatus) == normalize(expectedRuntimeStatus) else {
            return MobileSession(
                id: incoming.id,
                userId: incoming.userId,
                agentId: incoming.agentId,
                title: incoming.title,
                status: incoming.status,
                runtimeStatus: current.runtimeStatus,
                runtimeStep: current.runtimeStep,
                runtimeError: current.runtimeError,
                failureSource: current.failureSource,
                failureCode: current.failureCode,
                retryable: current.retryable,
                sideEffects: current.sideEffects,
                messageCount: incoming.messageCount,
                updatedAt: incoming.updatedAt
            )
        }

        return MobileSession(
            id: incoming.id,
            userId: incoming.userId,
            agentId: incoming.agentId,
            title: incoming.title,
            status: incoming.status,
            runtimeStatus: incoming.runtimeStatus,
            runtimeStep: incoming.runtimeStep ?? current.runtimeStep,
            runtimeError: preferredNonBlank(incoming.runtimeError, over: current.runtimeError),
            failureSource: preferredFailureSource(incoming.failureSource, over: current.failureSource),
            failureCode: preferredNonBlank(incoming.failureCode, over: current.failureCode),
            retryable: incoming.retryable ?? current.retryable,
            sideEffects: preferredSideEffects(incoming.sideEffects, over: current.sideEffects),
            messageCount: incoming.messageCount,
            updatedAt: incoming.updatedAt
        )
    }

    static func reconcilingOrdinaryMetadata(
        current: MobileSession,
        incoming: MobileSession,
        allowsRuntimeReplacement: Bool
    ) -> MobileSession {
        guard current.id == incoming.id else { return current }
        guard allowsRuntimeReplacement else {
            return preservingRuntimeMetadata(current: current, merging: incoming)
        }
        return incoming
    }

    static func resolvedRuntimeStatus(for event: MobileChatEvent) -> String? {
        event.type == "session_updated"
            ? event.runtimeStatus ?? event.status
            : event.status ?? event.runtimeStatus
    }

    private static func resolvedRuntimeError(for event: MobileChatEvent) -> String? {
        event.type == "session_updated"
            ? event.runtimeError ?? event.error
            : event.error ?? event.runtimeError
    }

    private static func replacingMetadata(
        of session: MobileSession,
        with event: MobileChatEvent
    ) -> MobileSession {
        MobileSession(
            id: session.id,
            userId: session.userId,
            agentId: session.agentId,
            title: event.title ?? session.title,
            status: session.status,
            runtimeStatus: session.runtimeStatus,
            runtimeStep: session.runtimeStep,
            runtimeError: session.runtimeError,
            failureSource: session.failureSource,
            failureCode: session.failureCode,
            retryable: session.retryable,
            sideEffects: session.sideEffects,
            messageCount: event.messageCount ?? session.messageCount,
            updatedAt: event.updatedAt ?? session.updatedAt
        )
    }

    private static func preservingRuntimeMetadata(
        current: MobileSession,
        merging incoming: MobileSession
    ) -> MobileSession {
        MobileSession(
            id: current.id,
            userId: current.userId ?? incoming.userId,
            agentId: current.agentId,
            title: incoming.title ?? current.title,
            status: incoming.status ?? current.status,
            runtimeStatus: current.runtimeStatus,
            runtimeStep: current.runtimeStep,
            runtimeError: current.runtimeError,
            failureSource: current.failureSource,
            failureCode: current.failureCode,
            retryable: current.retryable,
            sideEffects: current.sideEffects,
            messageCount: maxOptional(current.messageCount, incoming.messageCount),
            updatedAt: current.updatedAt ?? incoming.updatedAt
        )
    }

    private static func maxOptional(_ lhs: Int?, _ rhs: Int?) -> Int? {
        switch (lhs, rhs) {
        case let (lhs?, rhs?): max(lhs, rhs)
        case let (lhs?, nil): lhs
        case let (nil, rhs?): rhs
        case (nil, nil): nil
        }
    }

    private static func preferredNonBlank(_ incoming: String?, over current: String?) -> String? {
        guard let incoming,
              !incoming.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else { return current }
        return incoming
    }

    private static func preferredFailureSource(
        _ incoming: MobileRuntimeFailureSource?,
        over current: MobileRuntimeFailureSource?
    ) -> MobileRuntimeFailureSource? {
        if incoming == .unknown, let current, current != .unknown {
            return current
        }
        return incoming ?? current
    }

    private static func preferredSideEffects(
        _ incoming: MobileRuntimeSideEffects?,
        over current: MobileRuntimeSideEffects?
    ) -> MobileRuntimeSideEffects? {
        if incoming == .unknown, let current, current != .unknown {
            return current
        }
        return incoming ?? current
    }

    private static func normalize(_ value: String?) -> String? {
        value?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    private static func isCancellationStep(_ value: String?) -> Bool {
        let normalized = normalize(value)
        return normalized == "cancelled" || normalized == "canceled"
    }
}

enum RuntimeMetadataAsyncOperation: Equatable, Sendable {
    case ordinaryREST
    case sessionList
    case retryAcceptance
    case interactionAcceptance
    case terminalCatchUp
}

struct RuntimeMetadataAuthorityToken: Equatable, Sendable {
    let sessionId: String
    let generation: UInt64
    let operation: RuntimeMetadataAsyncOperation
}

struct RuntimeMetadataAuthorityGate: Equatable, Sendable {
    private(set) var sessionId: String?
    private(set) var generation: UInt64 = 0

    init(sessionId: String? = nil) {
        self.sessionId = sessionId
    }

    mutating func reset(sessionId: String?) {
        generation &+= 1
        self.sessionId = sessionId
    }

    mutating func begin(
        _ operation: RuntimeMetadataAsyncOperation,
        sessionId: String
    ) -> RuntimeMetadataAuthorityToken? {
        guard self.sessionId == sessionId else { return nil }
        generation &+= 1
        return RuntimeMetadataAuthorityToken(
            sessionId: sessionId,
            generation: generation,
            operation: operation
        )
    }

    mutating func recordRealtimeFact(sessionId: String) -> Bool {
        advanceIfCurrent(sessionId: sessionId)
    }

    mutating func recordLocalTransition(sessionId: String) -> Bool {
        advanceIfCurrent(sessionId: sessionId)
    }

    func accepts(_ token: RuntimeMetadataAuthorityToken) -> Bool {
        sessionId == token.sessionId && generation == token.generation
    }

    mutating func consume(_ token: RuntimeMetadataAuthorityToken) -> Bool {
        guard accepts(token) else { return false }
        generation &+= 1
        return true
    }

    private mutating func advanceIfCurrent(sessionId: String) -> Bool {
        guard self.sessionId == sessionId else { return false }
        generation &+= 1
        return true
    }
}

enum RuntimeMetadataCatchUpPolicy {
    static let maximumAttempts = 3

    static func delayNanoseconds(beforeAttempt attempt: Int) -> UInt64? {
        switch attempt {
        case 0: 0
        case 1: 250_000_000
        case 2: 750_000_000
        default: nil
        }
    }

    static func isTerminalStatus(_ status: String?) -> Bool {
        switch status?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "idle", "error", "failed", "cancelled", "canceled": true
        default: false
        }
    }

    static func shouldContinue(
        _ current: MobileSession,
        expectedRuntimeStatus: String
    ) -> Bool {
        let actual = current.runtimeStatus?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        let expected = expectedRuntimeStatus
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        return actual == expected
    }

    static func shouldAttemptFetch(
        _ current: MobileSession,
        expectedRuntimeStatus: String
    ) -> Bool {
        shouldContinue(current, expectedRuntimeStatus: expectedRuntimeStatus)
            && !isReconciled(current, expectedRuntimeStatus: expectedRuntimeStatus)
    }

    static func isReconciled(
        _ session: MobileSession,
        expectedRuntimeStatus: String
    ) -> Bool {
        let actual = session.runtimeStatus?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        let expected = expectedRuntimeStatus
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        guard actual == expected else { return false }
        guard expected == "error" || expected == "failed" else { return true }
        let hasReason = session.runtimeError?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .isEmpty == false
        let hasFailureCode = session.failureCode?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .isEmpty == false
        return hasReason
            && hasFailureCode
            && session.failureSource != nil
            && session.retryable != nil
            && session.sideEffects != nil
    }
}

struct MobileInteractionOption: Decodable, Equatable {
    let label: String
    let description: String?
}

struct MobileConfirmationEventPayload: Decodable, Equatable {
    let confirmationId: String
    let sessionId: String?
    let installTool: String?
    let installTarget: String?
    let commandPreview: String?
    let title: String?
    let description: String?
    let choices: [MobileConfirmationChoice]?
    let expiresAt: String?
}

struct MobileConfirmationChoice: Decodable, Equatable {
    let value: String
    let label: String
    let style: String?
}

struct MobileRealtimeMessage: Decodable, Equatable {
    let role: String?
}
