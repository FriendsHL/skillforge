import SwiftUI
import UIKit

struct SessionListView: View {
    let defaultAgent: MobileAgentSummary?
    let sessions: [MobileSession]
    let selectedSessionId: String?
    let onSelect: (MobileSession) -> Void
    let onCreate: () -> Void
    let onRefresh: () async -> Void

    @State private var searchText = ""
    @State private var statusFilter: SessionStatusFilter = .all

    var body: some View {
        NavigationStack {
            List {
                Section {
                    SessionSearchField(text: $searchText)
                        .frame(height: 38)
                        .listRowInsets(EdgeInsets())
                }

                Section {
                    Picker("Status", selection: $statusFilter) {
                        ForEach(SessionStatusFilter.allCases) { filter in
                            Text(filter.title).tag(filter)
                        }
                    }
                    .pickerStyle(.segmented)
                    .accessibilityLabel("Session status")
                    .accessibilityIdentifier("sessions.filter")
                }

                Section("Sessions") {
                    if filteredSessions.isEmpty {
                        ContentUnavailableView(
                            emptyTitle,
                            systemImage: "bubble.left.and.bubble.right",
                            description: Text(emptyDescription)
                        )
                    } else {
                        ForEach(filteredSessions) { session in
                            Button {
                                onSelect(session)
                            } label: {
                                sessionRow(session)
                            }
                            .accessibilityIdentifier("sessions.session.\(session.id)")
                            .accessibilityValue(
                                selectedSessionId == session.id ? "Selected" : statusText(for: session)
                            )
                            .accessibilityAddTraits(selectedSessionId == session.id ? .isSelected : [])
                        }
                    }
                }
            }
            .refreshable { await onRefresh() }
            .navigationTitle("Sessions")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(action: onCreate) {
                        Image(systemName: "plus")
                    }
                    .accessibilityLabel("New conversation")
                    .accessibilityIdentifier("sessions.openNewConversation")
                }
            }
        }
    }

    private var filteredSessions: [MobileSession] {
        SessionListPolicy.filteredSessions(sessions, query: searchText, filter: statusFilter)
    }

    private var emptyTitle: String {
        sessions.isEmpty ? "No Sessions" : "No matching Sessions"
    }

    private var emptyDescription: String {
        sessions.isEmpty
            ? "Create a conversation for this Agent to get started."
            : "Try another search or status filter."
    }

    private func sessionRow(_ session: MobileSession) -> some View {
        HStack(spacing: 12) {
            Image(systemName: selectedSessionId == session.id ? "checkmark.circle.fill" : statusFilter(for: session).symbol)
                .foregroundStyle(selectedSessionId == session.id ? CompanionStyle.orange : statusFilter(for: session).color)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 5) {
                Text(displayTitle(for: session))
                    .font(.body.weight(.medium))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                HStack(spacing: 6) {
                    Text(statusText(for: session))
                    if let updated = formattedUpdatedAt(session.updatedAt) {
                        Text("·")
                        Text(updated)
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
            Spacer(minLength: 8)
            if let count = session.messageCount {
                Text("\(count)")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
        }
        .contentShape(Rectangle())
        .padding(.vertical, 3)
    }

    private func displayTitle(for session: MobileSession) -> String {
        guard let title = session.title?.trimmingCharacters(in: .whitespacesAndNewlines), !title.isEmpty else {
            return "New Conversation"
        }
        return title
    }

    private func statusText(for session: MobileSession) -> String {
        let raw = normalizedStatus(for: session)
        return switch raw {
        case "running": "Running"
        case "waiting_user", "waiting_input": "Waiting for input"
        case "waiting_confirmation", "confirmation_required": "Waiting for approval"
        case "error", "failed": "Error"
        case "idle", "active": "Ready"
        case "completed": "Completed"
        default: raw.replacingOccurrences(of: "_", with: " ").capitalized
        }
    }

    private func searchableText(for session: MobileSession) -> String {
        [displayTitle(for: session), statusText(for: session), session.status, session.runtimeStatus]
            .compactMap { $0 }
            .joined(separator: " ")
    }

    private func normalizedStatus(for session: MobileSession) -> String {
        let runtime = session.runtimeStatus?.trimmingCharacters(in: .whitespacesAndNewlines)
        let persisted = session.status?.trimmingCharacters(in: .whitespacesAndNewlines)
        return [runtime, persisted]
            .compactMap { $0?.isEmpty == false ? $0?.lowercased() : nil }
            .first ?? "other"
    }

    private func statusFilter(for session: MobileSession) -> SessionStatusFilter {
        SessionStatusFilter.category(for: session)
    }

    private func formattedUpdatedAt(_ rawValue: String?) -> String? {
        guard let rawValue else { return nil }
        let withFractionalSeconds = ISO8601DateFormatter()
        withFractionalSeconds.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let withoutFractionalSeconds = ISO8601DateFormatter()
        withoutFractionalSeconds.formatOptions = [.withInternetDateTime]
        guard let date = withFractionalSeconds.date(from: rawValue)
            ?? withoutFractionalSeconds.date(from: rawValue)
        else { return nil }
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: date, relativeTo: Date())
    }
}

private struct SessionSearchField: UIViewRepresentable {
    @Binding var text: String

    func makeCoordinator() -> Coordinator {
        Coordinator(text: $text)
    }

    func makeUIView(context: Context) -> UISearchBar {
        let searchBar = UISearchBar(frame: .zero)
        searchBar.searchBarStyle = .minimal
        searchBar.placeholder = "Search Sessions"
        searchBar.autocapitalizationType = .none
        searchBar.autocorrectionType = .no
        searchBar.delegate = context.coordinator
        searchBar.searchTextField.accessibilityIdentifier = "sessions.search"
        return searchBar
    }

    func updateUIView(_ searchBar: UISearchBar, context: Context) {
        if searchBar.text != text {
            searchBar.text = text
        }
    }

    final class Coordinator: NSObject, UISearchBarDelegate {
        @Binding var text: String

        init(text: Binding<String>) {
            _text = text
        }

        func searchBar(_ searchBar: UISearchBar, textDidChange searchText: String) {
            text = searchText
        }

        func searchBarSearchButtonClicked(_ searchBar: UISearchBar) {
            searchBar.resignFirstResponder()
        }
    }
}

enum SessionStatusFilter: String, CaseIterable, Identifiable {
    case all
    case active
    case waiting
    case error
    case other

    var id: String { rawValue }

    var title: String {
        switch self {
        case .all: "All"
        case .active: "Active"
        case .waiting: "Waiting"
        case .error: "Error"
        case .other: "Other"
        }
    }

    var symbol: String {
        switch self {
        case .active: "bolt.circle.fill"
        case .waiting: "person.crop.circle.badge.clock"
        case .error: "exclamationmark.circle.fill"
        case .all, .other: "bubble.left"
        }
    }

    var color: Color {
        switch self {
        case .active: .green
        case .waiting: .orange
        case .error: .red
        case .all, .other: .secondary
        }
    }

    func matches(_ session: MobileSession) -> Bool {
        guard self != .all else { return true }
        return Self.category(for: session) == self
    }

    static func category(for session: MobileSession) -> SessionStatusFilter {
        switch SessionListPolicy.group(for: session) {
        case .running: .active
        case .waiting: .waiting
        case .error: .error
        case .other: .other
        }
    }
}
