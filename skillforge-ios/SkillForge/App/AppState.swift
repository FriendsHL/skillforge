import Foundation

@MainActor
final class AppState: ObservableObject {
    enum Phase {
        case loading
        case needsPairing
        case paired(
            endpoint: URL,
            deviceToken: String,
            device: MobileDeviceSummary?,
            defaultAgent: MobileAgentSummary?
        )
    }

    @Published private(set) var phase: Phase = .loading

    private let keychain: KeychainStoring
    private let session: URLSession
    private var endpointCandidates: [URL] = []
    private var isRefreshingEndpoint = false

    init(
        keychain: KeychainStoring = KeychainStore(),
        session: URLSession = .shared,
        loadOnInit: Bool = true
    ) {
        self.keychain = keychain
        self.session = session
        if loadOnInit {
            Task { await loadStoredSession() }
        }
    }

    func loadStoredSession() async {
        do {
            guard
                let endpointString = try keychain.readString(for: .endpoint),
                let storedEndpoint = URL(string: endpointString),
                let token = try keychain.readString(for: .deviceToken)
            else {
                phase = .needsPairing
                return
            }

            endpointCandidates = try storedEndpoints(fallback: storedEndpoint)
            switch await validateEndpointCandidates(token: token) {
            case let .authenticated(endpoint, me):
                try saveActiveEndpoint(endpoint)
                phase = .paired(endpoint: endpoint, deviceToken: token, device: me.device, defaultAgent: me.defaultAgent)
            case .unauthorized:
                resetPairing()
            case .unavailable:
                phase = .paired(endpoint: storedEndpoint, deviceToken: token, device: nil, defaultAgent: nil)
            }
        } catch {
            phase = .needsPairing
        }
    }

    func completePairing(
        endpoint: URL,
        endpoints: [URL],
        deviceToken: String,
        device: MobileDeviceSummary?,
        defaultAgent: MobileAgentSummary? = nil
    ) async {
        do {
            endpointCandidates = normalizedEndpoints(endpoints, fallback: endpoint)
            try saveActiveEndpoint(endpoint)
            try saveEndpoints(endpointCandidates)
            try keychain.saveString(deviceToken, for: .deviceToken)
            phase = .paired(endpoint: endpoint, deviceToken: deviceToken, device: device, defaultAgent: defaultAgent)
        } catch {
            phase = .needsPairing
        }
    }

    func refreshEndpointSelection() async {
        guard !isRefreshingEndpoint else { return }
        guard case let .paired(currentEndpoint, token, device, defaultAgent) = phase else { return }
        guard endpointCandidates.count > 1 else { return }

        isRefreshingEndpoint = true
        defer { isRefreshingEndpoint = false }
        guard case let .authenticated(selected, me) = await validateEndpointCandidates(token: token),
              selected != currentEndpoint else { return }

        do {
            try saveActiveEndpoint(selected)
            phase = .paired(
                endpoint: selected,
                deviceToken: token,
                device: me.device,
                defaultAgent: me.defaultAgent ?? defaultAgent
            )
        } catch {
            phase = .paired(
                endpoint: currentEndpoint,
                deviceToken: token,
                device: device,
                defaultAgent: defaultAgent
            )
        }
    }

    func resetPairing() {
        try? keychain.deleteString(for: .endpoint)
        try? keychain.deleteString(for: .endpoints)
        try? keychain.deleteString(for: .deviceToken)
        endpointCandidates = []
        phase = .needsPairing
    }


    private func storedEndpoints(fallback: URL) throws -> [URL] {
        guard let raw = try keychain.readString(for: .endpoints),
              let data = raw.data(using: .utf8),
              let strings = try? JSONDecoder().decode([String].self, from: data) else {
            return [fallback]
        }
        return normalizedEndpoints(strings.compactMap(URL.init(string:)), fallback: fallback)
    }

    private func normalizedEndpoints(_ endpoints: [URL], fallback: URL) -> [URL] {
        let ordered = EndpointProbe.orderedEndpoints(
            from: (endpoints + [fallback]).map(\.absoluteString)
        )
        return ordered.isEmpty ? [fallback] : ordered
    }

    private func saveEndpoints(_ endpoints: [URL]) throws {
        let data = try JSONEncoder().encode(endpoints.map(\.absoluteString))
        guard let value = String(data: data, encoding: .utf8) else {
            throw KeychainError.unexpectedData
        }
        try keychain.saveString(value, for: .endpoints)
    }

    private func saveActiveEndpoint(_ endpoint: URL) throws {
        try keychain.saveString(endpoint.absoluteString, for: .endpoint)
    }

    private enum EndpointValidation {
        case authenticated(URL, MobileMeResponse)
        case unauthorized
        case unavailable
    }

    private func validateEndpointCandidates(token: String) async -> EndpointValidation {
        let probe = EndpointProbe(session: session, timeout: 2)
        var unauthorizedCount = 0
        for endpoint in endpointCandidates {
            guard await probe.isReachable(endpoint) else { continue }
            do {
                let me = try await MobileApiClient(
                    baseURL: endpoint,
                    deviceToken: token,
                    session: session
                ).me()
                return .authenticated(endpoint, me)
            } catch {
                if case let MobileApiError.httpStatus(status, _) = error, status == 401 {
                    unauthorizedCount += 1
                }
            }
        }
        return unauthorizedCount == endpointCandidates.count ? .unauthorized : .unavailable
    }
}
