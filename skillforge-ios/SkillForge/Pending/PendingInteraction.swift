import Foundation

struct PendingInteraction: Identifiable, Equatable {
    enum Kind: Equatable {
        case ask
        case confirmation
    }

    enum Source: Equatable {
        case persisted
        case realtime
    }

    struct Option: Identifiable, Equatable {
        let id: String
        let label: String
        let description: String?

        init(id: String? = nil, label: String, description: String?) {
            self.id = id ?? [label, description ?? ""].joined(separator: "|")
            self.label = label
            self.description = description
        }
    }

    let id: String
    let kind: Kind
    let question: String
    let context: String?
    let options: [Option]
    let allowOther: Bool
    let source: Source

    init(
        id: String,
        kind: Kind,
        question: String,
        context: String?,
        options: [Option],
        allowOther: Bool,
        source: Source
    ) {
        self.id = id
        self.kind = kind
        self.question = question
        self.context = context
        self.options = options
        self.allowOther = allowOther
        self.source = source
    }

    static func persisted(from messages: [MobileSessionMessage]) -> [PendingInteraction] {
        messages.compactMap { message in
            guard message.answeredAt == nil, let controlId = message.controlId else {
                return nil
            }
            let kind: Kind
            switch message.messageType?.lowercased() {
            case "ask_user":
                kind = .ask
            case "confirmation":
                kind = .confirmation
            default:
                return nil
            }
            let metadata = message.metadata
            let question = metadata["question"]?.stringValue
                ?? metadata["title"]?.stringValue
                ?? message.displayText
            guard !question.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                return nil
            }
            return PendingInteraction(
                id: controlId,
                kind: kind,
                question: question,
                context: metadata["context"]?.stringValue
                    ?? metadata["description"]?.stringValue,
                options: metadata["options"]?.optionValues ?? [],
                allowOther: kind == .ask ? (metadata["allowOther"]?.boolValue ?? true) : false,
                source: .persisted
            )
        }
    }

    init?(realtimeEvent event: MobileChatEvent) {
        switch event.type {
        case "ask_user":
            guard let id = event.askId, let question = event.question else { return nil }
            self.init(
                id: id,
                kind: .ask,
                question: question,
                context: event.context,
                options: (event.options ?? []).enumerated().map { index, option in
                    Option(
                        id: "\(index)-\(option.label)",
                        label: option.label,
                        description: option.description
                    )
                },
                allowOther: event.allowOther ?? true,
                source: .realtime
            )
        case "confirmation_required":
            guard let payload = event.payload else { return nil }
            let fallback = [payload.installTool, payload.installTarget]
                .compactMap { $0 }
                .filter { !$0.isEmpty }
                .joined(separator: " ")
            self.init(
                id: payload.confirmationId,
                kind: .confirmation,
                question: payload.title?.nonBlank ?? "需要确认",
                context: payload.description?.nonBlank
                    ?? payload.commandPreview?.nonBlank
                    ?? fallback.nonBlank,
                options: [],
                allowOther: false,
                source: .realtime
            )
        default:
            return nil
        }
    }

    init(remoteConfirmation: MobilePendingConfirmation) {
        self.init(
            id: remoteConfirmation.confirmationId,
            kind: .confirmation,
            question: remoteConfirmation.title,
            context: remoteConfirmation.description?.nonBlank
                ?? remoteConfirmation.commandPreview?.nonBlank,
            options: [],
            allowOther: false,
            source: .realtime
        )
    }
}

extension MobileJSONValue {
    fileprivate var stringValue: String? {
        guard case let .string(value) = self else { return nil }
        return value
    }

    fileprivate var boolValue: Bool? {
        guard case let .bool(value) = self else { return nil }
        return value
    }

    fileprivate var optionValues: [PendingInteraction.Option] {
        guard case let .array(values) = self else { return [] }
        return values.enumerated().compactMap { index, value in
            guard case let .object(object) = value,
                  let label = object["label"]?.stringValue?.nonBlank else {
                return nil
            }
            return PendingInteraction.Option(
                id: "\(index)-\(label)",
                label: label,
                description: object["description"]?.stringValue?.nonBlank
            )
        }
    }
}

private extension String {
    var nonBlank: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
