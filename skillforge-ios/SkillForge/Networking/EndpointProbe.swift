import Foundation

struct EndpointProbe {
    let session: URLSession
    let timeout: TimeInterval

    init(session: URLSession = .shared, timeout: TimeInterval = 3) {
        self.session = session
        self.timeout = timeout
    }

    func firstReachableEndpoint(from endpoints: [String]) async -> URL? {
        for url in Self.orderedEndpoints(from: endpoints) {
            if await isReachable(url) {
                return url
            }
        }
        return nil
    }

    func isReachable(_ baseURL: URL) async -> Bool {
        var request = URLRequest(url: baseURL.skillForgeAppendingPath("/api/mobile/client/me"))
        request.httpMethod = "GET"
        request.timeoutInterval = timeout

        do {
            let (_, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else { return false }
            return http.statusCode < 500
        } catch {
            return false
        }
    }

    static func normalizedEndpoint(_ value: String) -> URL? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, var components = URLComponents(string: trimmed) else { return nil }
        if components.scheme == nil {
            components.scheme = "http"
        }
        guard let scheme = components.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              components.host != nil,
              components.user == nil,
              components.password == nil else { return nil }
        while components.path.hasSuffix("/") {
            components.path.removeLast()
        }
        guard let url = components.url else { return nil }
        return url
    }

    static func orderedEndpoints(from endpoints: [String]) -> [URL] {
        var seen = Set<String>()
        let normalized = endpoints.compactMap(normalizedEndpoint).filter {
            seen.insert($0.absoluteString).inserted
        }
        return normalized.enumerated().sorted { lhs, rhs in
            let lhsPriority = priority(for: lhs.element)
            let rhsPriority = priority(for: rhs.element)
            return lhsPriority == rhsPriority ? lhs.offset < rhs.offset : lhsPriority < rhsPriority
        }.map(\.element)
    }

    private static func priority(for endpoint: URL) -> Int {
        isPrivateLANHost(endpoint.host()) ? 0 : 1
    }

    private static func isPrivateLANHost(_ host: String?) -> Bool {
        guard let host = host?.lowercased() else { return false }
        if host == "localhost" || host.hasSuffix(".local") { return true }
        let octets = host.split(separator: ".").compactMap { Int($0) }
        guard octets.count == 4 else { return false }
        if octets[0] == 10 || (octets[0] == 192 && octets[1] == 168) { return true }
        return octets[0] == 172 && (16...31).contains(octets[1])
    }
}
