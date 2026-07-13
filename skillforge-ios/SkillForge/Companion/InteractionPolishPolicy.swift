import Foundation
@preconcurrency import UserNotifications

enum SessionStatusGroup: Equatable {
    case running
    case waiting
    case error
    case other
}

enum SessionListPolicy {
    static func filteredSessions(
        _ sessions: [MobileSession],
        query: String,
        filter: SessionStatusFilter
    ) -> [MobileSession] {
        let normalizedQuery = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return sessions.filter { session in
            filter.matches(session)
                && (normalizedQuery.isEmpty || searchableText(for: session).localizedCaseInsensitiveContains(normalizedQuery))
        }
    }

    static func group(for session: MobileSession) -> SessionStatusGroup {
        let statuses = [session.runtimeStatus, session.status]
            .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }
        if statuses.contains(where: { ["running", "queued"].contains($0) }) { return .running }
        if statuses.contains(where: {
            ["waiting_user", "waiting_input", "waiting_confirmation", "confirmation_required"].contains($0)
        }) { return .waiting }
        if statuses.contains(where: { ["error", "failed"].contains($0) }) { return .error }
        return .other
    }

    private static func searchableText(for session: MobileSession) -> String {
        [session.title, session.runtimeStatus, session.status]
            .compactMap { $0 }
            .joined(separator: " ")
    }
}

struct NotificationPresentation: Equatable {
    let statusText: String
    let canRequestPermission: Bool
    let shouldOpenSystemSettings: Bool
    let isAuthorized: Bool
    let isRegisteredWithServer: Bool
}

enum NotificationPresentationPolicy {
    static func presentation(for status: UNAuthorizationStatus) -> NotificationPresentation {
        switch status {
        case .notDetermined:
            NotificationPresentation(
                statusText: "Not enabled",
                canRequestPermission: true,
                shouldOpenSystemSettings: false,
                isAuthorized: false,
                isRegisteredWithServer: false
            )
        case .denied:
            NotificationPresentation(
                statusText: "Disabled in Settings",
                canRequestPermission: false,
                shouldOpenSystemSettings: true,
                isAuthorized: false,
                isRegisteredWithServer: false
            )
        case .authorized, .provisional, .ephemeral:
            NotificationPresentation(
                statusText: "Allowed on this iPhone",
                canRequestPermission: false,
                shouldOpenSystemSettings: false,
                isAuthorized: true,
                isRegisteredWithServer: false
            )
        @unknown default:
            NotificationPresentation(
                statusText: "Unavailable",
                canRequestPermission: false,
                shouldOpenSystemSettings: true,
                isAuthorized: false,
                isRegisteredWithServer: false
            )
        }
    }
}
