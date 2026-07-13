import Combine
import Foundation

enum ConnectionHealthPhase: Equatable {
    case notChecked
    case checking
    case healthy
    case offline
    case serviceIssue
}

struct ConnectionHealthState: Equatable {
    var phase: ConnectionHealthPhase = .notChecked
    var lastCheckedAt: Date?

    static func finished(_ phase: ConnectionHealthPhase, at date: Date = .now) -> Self {
        Self(phase: phase, lastCheckedAt: date)
    }
}

enum ConnectionHealthResult: Equatable {
    case healthy
    case unauthorized
    case offline
    case serviceIssue
}

enum ConnectionHealthPolicy {
    static func classify(error: Error?) -> ConnectionHealthResult {
        guard let error else { return .healthy }

        if case let MobileApiError.httpStatus(status, _) = error, status == 401 {
            return .unauthorized
        }

        if error is URLError {
            return .offline
        }

        let nsError = error as NSError
        if nsError.domain == NSURLErrorDomain {
            return .offline
        }

        return .serviceIssue
    }

}

@MainActor
final class ConnectionHealthMonitor: ObservableObject {
    typealias Probe = @MainActor () async throws -> Void

    @Published private(set) var state = ConnectionHealthState()

    private let pairingIdentity: String
    private let probe: Probe
    private let onUnauthorized: () -> Void
    private var checkTask: Task<Void, Never>?
    private var generation = 0
    private var stateBeforeCheck = ConnectionHealthState()

    init(
        pairingIdentity: String,
        probe: @escaping Probe,
        onUnauthorized: @escaping () -> Void
    ) {
        self.pairingIdentity = pairingIdentity
        self.probe = probe
        self.onUnauthorized = onUnauthorized
    }

    func check(pairingIdentity: String) {
        guard pairingIdentity == self.pairingIdentity else { return }
        guard state.phase != .checking else { return }

        generation &+= 1
        let operationGeneration = generation
        checkTask?.cancel()
        stateBeforeCheck = state
        state.phase = .checking

        checkTask = Task { @MainActor [weak self] in
            guard let self else { return }

            let result: ConnectionHealthResult
            do {
                try await probe()
                result = .healthy
            } catch {
                result = ConnectionHealthPolicy.classify(error: error)
            }

            guard isCurrent(operationGeneration, pairingIdentity: pairingIdentity) else { return }
            checkTask = nil

            switch result {
            case .healthy:
                state = .finished(.healthy)
            case .unauthorized:
                onUnauthorized()
            case .offline:
                state = .finished(.offline)
            case .serviceIssue:
                state = .finished(.serviceIssue)
            }
        }
    }

    func cancel() {
        generation &+= 1
        checkTask?.cancel()
        checkTask = nil
        if state.phase == .checking {
            state = stateBeforeCheck
        }
    }

    private func isCurrent(_ operationGeneration: Int, pairingIdentity: String) -> Bool {
        !Task.isCancelled
            && operationGeneration == generation
            && pairingIdentity == self.pairingIdentity
    }
}

extension ConnectionHealthPhase {
    var summaryTitle: String {
        switch self {
        case .notChecked: "Not checked"
        case .checking: "Checking connection"
        case .healthy: "Running normally"
        case .offline: "Cannot reach server"
        case .serviceIssue: "Service needs attention"
        }
    }

    var summaryDetail: String {
        switch self {
        case .notChecked: "Open diagnostics to check this connection."
        case .checking: "Checking endpoint and device authorization."
        case .healthy: "Endpoint, service, and device authorization are available."
        case .offline: "Check the iPhone network and the server address."
        case .serviceIssue: "SkillForge responded unexpectedly. Pairing was preserved."
        }
    }

    var symbol: String {
        switch self {
        case .notChecked: "minus.circle"
        case .checking: "arrow.trianglehead.2.clockwise.rotate.90"
        case .healthy: "checkmark.circle.fill"
        case .offline: "wifi.slash"
        case .serviceIssue: "exclamationmark.triangle.fill"
        }
    }

    var endpointStatus: String {
        switch self {
        case .notChecked: "Not checked"
        case .checking: "Checking"
        case .healthy, .serviceIssue: "Reachable"
        case .offline: "Unreachable"
        }
    }

    var serviceStatus: String {
        switch self {
        case .notChecked: "Not checked"
        case .checking: "Checking"
        case .healthy: "Available"
        case .offline: "Not verified"
        case .serviceIssue: "Unexpected response"
        }
    }

    var authorizationStatus: String {
        switch self {
        case .notChecked: "Not checked"
        case .checking: "Checking"
        case .healthy: "Authorized"
        case .offline, .serviceIssue: "Not verified"
        }
    }
}
