import Combine
import Foundation

struct PairingPayload: Codable, Equatable {
    let type: String
    let version: Int
    let serverName: String
    let pairingId: String
    let pairingSecret: String
    let endpoints: [String]
    let expiresAt: Date

    static let expectedType = "skillforge.mobile_pairing"

    static func decode(from qrText: String) throws -> PairingPayload {
        let data = Data(qrText.utf8)
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let payload = try decoder.decode(PairingPayload.self, from: data)
        guard payload.type == expectedType else { throw PairingPayloadError.unsupportedType(payload.type) }
        guard payload.version == 1 else { throw PairingPayloadError.unsupportedVersion(payload.version) }
        guard !payload.pairingId.isEmpty, !payload.pairingSecret.isEmpty, !payload.endpoints.isEmpty else {
            throw PairingPayloadError.missingRequiredFields
        }
        return payload
    }
}

enum PairingPayloadError: Error, LocalizedError, Equatable {
    case unsupportedType(String)
    case unsupportedVersion(Int)
    case missingRequiredFields

    var errorDescription: String? {
        switch self {
        case let .unsupportedType(type):
            return "Unsupported QR payload: \(type)"
        case let .unsupportedVersion(version):
            return "Unsupported QR payload version: \(version)"
        case .missingRequiredFields:
            return "QR payload is missing required pairing fields"
        }
    }
}

struct PairingReview: Equatable {
    enum Expiry: Equatable {
        case valid(until: Date)
        case expired
    }

    let serverName: String
    let endpointDisplays: [String]
    let expiry: Expiry

    func isExpired(at date: Date) -> Bool {
        switch expiry {
        case .expired:
            return true
        case let .valid(until):
            return until <= date
        }
    }
}

struct PairingClaimContext {
    let payload: PairingPayload
    let endpoint: URL
    let endpoints: [URL]
}

struct PairingErrorPresentation: Equatable {
    enum Kind: Equatable {
        case cameraUnavailable
        case invalidPayload
        case expiredPayload
        case unreachableEndpoint
        case pairingRejected
        case alreadyUsed
        case serverUnavailable
        case credentialSaveFailed
    }

    let kind: Kind
    let message: String
    let recoveryTitle: String

    static let cameraUnavailable = PairingErrorPresentation(
        kind: .cameraUnavailable,
        message: "Camera scanning is unavailable. Paste the complete pairing payload instead.",
        recoveryTitle: "Paste Payload"
    )

    static let invalidPayload = PairingErrorPresentation(
        kind: .invalidPayload,
        message: "This is not a valid SkillForge pairing payload. Copy a new payload from Dashboard.",
        recoveryTitle: "Try Another Payload"
    )

    static let expiredPayload = PairingErrorPresentation(
        kind: .expiredPayload,
        message: "This pairing payload has expired. Generate a new pairing QR in Dashboard.",
        recoveryTitle: "Scan New QR"
    )

    static let unreachableEndpoint = PairingErrorPresentation(
        kind: .unreachableEndpoint,
        message: "This iPhone cannot reach any listed SkillForge endpoint. Check the network and try again.",
        recoveryTitle: "Try Again"
    )

    static let credentialSaveFailed = PairingErrorPresentation(
        kind: .credentialSaveFailed,
        message: "Pairing succeeded, but this iPhone could not securely save the connection. Pair again to continue.",
        recoveryTitle: "Pair Again"
    )

    static func claimFailure(_ error: Error) -> PairingErrorPresentation {
        if case let MobileApiError.httpStatus(status, body) = error {
            switch status {
            case 400:
                return badRequestFailure(body)
            case 409:
                return PairingErrorPresentation(
                    kind: .alreadyUsed,
                    message: "This pairing request was already used. Generate a new pairing QR in Dashboard.",
                    recoveryTitle: "Scan New QR"
                )
            case 410:
                return .expiredPayload
            case 401, 403:
                return PairingErrorPresentation(
                    kind: .pairingRejected,
                    message: "SkillForge could not verify this pairing request. Generate a new pairing QR and try again.",
                    recoveryTitle: "Scan New QR"
                )
            case 500...599:
                return PairingErrorPresentation(
                    kind: .serverUnavailable,
                    message: "The SkillForge server could not complete pairing right now.",
                    recoveryTitle: "Try Again"
                )
            default:
                break
            }
        }
        if error is URLError {
            return .unreachableEndpoint
        }
        return PairingErrorPresentation(
            kind: .pairingRejected,
            message: "Pairing could not be completed. Try again or generate a new pairing QR.",
            recoveryTitle: "Try Again"
        )
    }

    private static func badRequestFailure(_ body: String) -> PairingErrorPresentation {
        struct ErrorEnvelope: Decodable {
            let error: String
        }

        let message = body.data(using: .utf8)
            .flatMap { try? JSONDecoder().decode(ErrorEnvelope.self, from: $0) }
            .map { $0.error.lowercased() }
        switch message {
        case "pairing is expired":
            return .expiredPayload
        case "pairing is not pending":
            return PairingErrorPresentation(
                kind: .alreadyUsed,
                message: "This pairing request was already used. Generate a new pairing QR in Dashboard.",
                recoveryTitle: "Scan New QR"
            )
        default:
            return PairingErrorPresentation(
                kind: .pairingRejected,
                message: "SkillForge could not verify this pairing request. Generate a new pairing QR and try again.",
                recoveryTitle: "Scan New QR"
            )
        }
    }
}

@MainActor
final class PairingFlowModel: ObservableObject {
    enum Stage: Equatable {
        case ready
        case review(PairingReview)
        case checking
        case claiming
        case saving
        case paired
    }

    typealias Probe = ([String]) async -> URL?

    @Published var payloadText = ""
    @Published var isPasteExpanded = false
    @Published private(set) var stage: Stage
    @Published private(set) var error: PairingErrorPresentation?

    private let now: () -> Date
    private let probe: Probe
    private var payload: PairingPayload?
    private var normalizedEndpoints: [URL] = []

    init(
        stage: Stage = .ready,
        now: @escaping () -> Date = Date.init,
        probe: @escaping Probe = { await EndpointProbe().firstReachableEndpoint(from: $0) }
    ) {
        self.stage = stage
        self.now = now
        self.probe = probe
    }

    func decode(_ text: String) throws {
        payloadText = ""
        error = nil

        do {
            let decoded = try PairingPayload.decode(from: text)
            let normalized = decoded.endpoints.compactMap(Self.normalizedHTTPSEndpoint)
            var seenEndpoints = Set<String>()
            let endpoints = normalized.filter { seenEndpoints.insert($0.absoluteString).inserted }
            guard !decoded.serverName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  !endpoints.isEmpty else {
                throw PairingPayloadError.missingRequiredFields
            }

            payload = decoded
            normalizedEndpoints = endpoints
            isPasteExpanded = false
            stage = .review(PairingReview(
                serverName: decoded.serverName,
                endpointDisplays: endpoints.map(Self.endpointDisplay),
                expiry: decoded.expiresAt > now() ? .valid(until: decoded.expiresAt) : .expired
            ))
        } catch {
            payload = nil
            normalizedEndpoints = []
            stage = .ready
            self.error = .invalidPayload
            throw error
        }
    }

    func prepareClaim() async -> PairingClaimContext? {
        guard case .review = stage else { return nil }
        guard let payload else { return nil }
        guard payload.expiresAt > now() else {
            stage = reviewStage(for: payload)
            error = .expiredPayload
            return nil
        }

        error = nil
        stage = .checking
        let candidates = normalizedEndpoints.map(\.absoluteString)
        guard let endpoint = await probe(candidates) else {
            stage = reviewStage(for: payload)
            error = .unreachableEndpoint
            return nil
        }
        stage = .claiming
        return PairingClaimContext(
            payload: payload,
            endpoint: endpoint,
            endpoints: normalizedEndpoints
        )
    }

    func markSaving() {
        stage = .saving
    }

    func markPaired() {
        clearSensitiveState()
        stage = .paired
    }

    func recordClaimFailure(_ claimError: Error) {
        let presentation = PairingErrorPresentation.claimFailure(claimError)
        error = presentation
        switch presentation.kind {
        case .alreadyUsed, .expiredPayload, .pairingRejected:
            clearSensitiveState()
            stage = .ready
        default:
            if let payload {
                stage = reviewStage(for: payload)
            } else {
                stage = .ready
            }
        }
    }

    func recordCredentialSaveFailure() {
        clearSensitiveState()
        stage = .ready
        error = .credentialSaveFailed
    }

    func recordCameraUnavailable() {
        error = .cameraUnavailable
        isPasteExpanded = true
    }

    func startOver(openPaste: Bool = false) {
        clearSensitiveState()
        stage = .ready
        error = nil
        isPasteExpanded = openPaste
    }

    private func clearSensitiveState() {
        payload = nil
        normalizedEndpoints = []
        payloadText = ""
    }

    private func reviewStage(for payload: PairingPayload) -> Stage {
        .review(PairingReview(
            serverName: payload.serverName,
            endpointDisplays: normalizedEndpoints.map(Self.endpointDisplay),
            expiry: payload.expiresAt > now() ? .valid(until: payload.expiresAt) : .expired
        ))
    }

    private static func normalizedHTTPSEndpoint(_ rawValue: String) -> URL? {
        let trimmed = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let candidate = trimmed.contains("://") ? trimmed : "http://\(trimmed)"
        guard let components = URLComponents(string: candidate),
              let scheme = components.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              components.host != nil,
              components.user == nil,
              components.password == nil,
              let url = components.url else {
            return nil
        }
        return url
    }

    private static func endpointDisplay(_ endpoint: URL) -> String {
        guard let components = URLComponents(url: endpoint, resolvingAgainstBaseURL: false),
              let scheme = components.scheme,
              let host = components.host else {
            return "Unknown endpoint"
        }
        let port = components.port.map { ":\($0)" } ?? ""
        return "\(scheme.lowercased())://\(host)\(port)"
    }
}

#if DEBUG
extension PairingFlowModel {
    static func reviewUITestFixture() -> PairingFlowModel {
        PairingFlowModel(stage: .review(PairingReview(
            serverName: "Pairing Review Server",
            endpointDisplays: ["https://review.example.com:8443"],
            expiry: .valid(until: Date().addingTimeInterval(300))
        )))
    }
}
#endif
