import SwiftUI

struct NewConversationView: View {
    let agents: [MobileAgentCatalogItem]
    let currentAgentID: Int64?
    let onCreate: (MobileAgentCatalogItem) async throws -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var selectedAgentID: Int64?
    @State private var isCreating = false
    @State private var errorText: String?

    init(
        agents: [MobileAgentCatalogItem],
        currentAgentID: Int64?,
        onCreate: @escaping (MobileAgentCatalogItem) async throws -> Void
    ) {
        self.agents = agents
        self.currentAgentID = currentAgentID
        self.onCreate = onCreate
        let selectableAgents = agents.filter { $0.status.lowercased() == "active" }
        let initialID = selectableAgents.first(where: { $0.id == currentAgentID })?.id
            ?? selectableAgents.first(where: \.isDefault)?.id
            ?? selectableAgents.first?.id
        _selectedAgentID = State(initialValue: initialID)
    }

    var body: some View {
        NavigationStack {
            List {
                if selectableAgents.isEmpty {
                    ContentUnavailableView(
                        "No available Agents",
                        systemImage: "person.crop.circle.badge.exclamationmark",
                        description: Text("Enable a conversational Agent in the SkillForge Dashboard first.")
                    )
                } else {
                    Section {
                        ForEach(selectableAgents) { agent in
                            Button {
                                selectedAgentID = agent.id
                                errorText = nil
                            } label: {
                                agentRow(agent)
                            }
                            .buttonStyle(.plain)
                            .disabled(isCreating)
                            .accessibilityIdentifier("newConversation.agent.\(agent.id)")
                            .accessibilityValue(selectedAgentID == agent.id ? "Selected" : "Not selected")
                            .accessibilityAddTraits(selectedAgentID == agent.id ? .isSelected : [])
                        }
                    } header: {
                        Text("Choose Agent")
                    } footer: {
                        Text("A new Session is created only after SkillForge confirms the request.")
                    }
                }

                if let errorText {
                    Section {
                        Label(errorText, systemImage: "exclamationmark.triangle.fill")
                            .font(.callout)
                            .foregroundStyle(.red)
                            .accessibilityIdentifier("newConversation.error")
                    }
                }
            }
            .navigationTitle("New Conversation")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .disabled(isCreating)
                        .accessibilityIdentifier("newConversation.cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(action: createConversation) {
                        if isCreating {
                            ProgressView()
                        } else {
                            Text("Create")
                        }
                    }
                    .disabled(selectedAgent == nil || isCreating)
                    .accessibilityIdentifier("newConversation.create")
                }
            }
            .interactiveDismissDisabled(isCreating)
        }
    }

    private var selectableAgents: [MobileAgentCatalogItem] {
        agents.filter { $0.status.lowercased() == "active" }
    }

    private var selectedAgent: MobileAgentCatalogItem? {
        selectedAgentID.flatMap { id in selectableAgents.first { $0.id == id } }
    }

    private func agentRow(_ agent: MobileAgentCatalogItem) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text(initials(for: agent.name))
                .font(.caption.weight(.bold))
                .foregroundStyle(.white)
                .frame(width: 40, height: 40)
                .background(Color(red: 0.10, green: 0.12, blue: 0.16))
                .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))

            VStack(alignment: .leading, spacing: 5) {
                HStack(spacing: 6) {
                    Text(agent.name)
                        .font(.headline)
                        .foregroundStyle(.primary)
                    if agent.isDefault {
                        Text("Default")
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(.green)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color.green.opacity(0.12))
                            .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
                    }
                }
                Text(summary(for: agent))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
            Spacer(minLength: 8)
            Image(systemName: selectedAgentID == agent.id ? "checkmark.circle.fill" : "circle")
                .foregroundStyle(selectedAgentID == agent.id ? CompanionStyle.orange : Color.secondary)
                .font(.title3)
        }
        .contentShape(Rectangle())
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }

    private func summary(for agent: MobileAgentCatalogItem) -> String {
        let source = agent.source.replacingOccurrences(of: "_", with: " ").capitalized
        let model = agent.modelId?.trimmingCharacters(in: .whitespacesAndNewlines)
        return [source, model].compactMap { value in
            guard let value, !value.isEmpty else { return nil }
            return value
        }.joined(separator: " · ")
    }

    private func initials(for name: String) -> String {
        let words = name.split(separator: " ").prefix(2)
        let value = words.compactMap(\.first).map(String.init).joined()
        return value.isEmpty ? "SF" : value.uppercased()
    }

    private func createConversation() {
        guard let selectedAgent, !isCreating else { return }
        isCreating = true
        errorText = nil
        Task { @MainActor in
            do {
                try await onCreate(selectedAgent)
                guard !Task.isCancelled else { return }
                dismiss()
            } catch {
                guard !Task.isCancelled else { return }
                errorText = error.localizedDescription
                isCreating = false
            }
        }
    }
}
