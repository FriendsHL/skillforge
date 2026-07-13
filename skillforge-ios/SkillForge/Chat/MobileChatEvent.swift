import Foundation

struct MobileChatEvent: Decodable, Equatable {
    let type: String
    let sessionId: String
    let status: String?
    let step: String?
    let error: String?
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

    var assistantTextDelta: String? {
        delta ?? text
    }

    var resolvedToolName: String? {
        toolName ?? name
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
