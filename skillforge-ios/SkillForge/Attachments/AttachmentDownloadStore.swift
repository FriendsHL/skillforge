import Foundation

@MainActor
final class AttachmentDownloadStore: ObservableObject {
    private struct StateOperationToken: Equatable {
        let namespace: UInt64
        let artifact: UInt64
    }

    enum State: Equatable {
        case idle
        case downloading
        case available(URL)
        case unavailable
        case failed(String)
    }

    @Published private var states: [String: State] = [:]
    private var tasks: [String: (id: UUID, task: Task<Void, Never>)] = [:]
    private var ownerCounts: [String: Int] = [:]
    private var stateNamespaceGeneration: UInt64 = 0
    private var artifactStateGenerations: [String: UInt64] = [:]
    private var repository: (any AttachmentDownloading)?
    #if DEBUG
    private let fixtureRetryURLs: [String: URL]
    private let fixtureManifests: [String: InteractiveArtifactManifest]
    private var fixtureLocalRecords: [PersonalAppLocalRecord]
    #endif

    init(repository: any AttachmentDownloading) {
        self.repository = repository
        #if DEBUG
        fixtureRetryURLs = [:]
        fixtureManifests = [:]
        fixtureLocalRecords = []
        #endif
    }

    #if DEBUG
    init(
        fixtureStates: [String: State],
        retryURLs: [String: URL] = [:],
        manifests: [String: InteractiveArtifactManifest] = [:],
        localRecords: [PersonalAppLocalRecord] = []
    ) {
        repository = nil
        states = fixtureStates
        fixtureRetryURLs = retryURLs
        fixtureManifests = manifests
        fixtureLocalRecords = localRecords
    }
    #endif

    func state(for attachment: ChatAttachment) -> State {
        states[attachment.id] ?? .idle
    }

    func retain(_ attachment: ChatAttachment) {
        ownerCounts[attachment.id, default: 0] += 1
    }

    func release(_ attachment: ChatAttachment) {
        let remaining = max(0, ownerCounts[attachment.id, default: 0] - 1)
        if remaining == 0 {
            ownerCounts[attachment.id] = nil
            cancel(attachment)
        } else {
            ownerCounts[attachment.id] = remaining
        }
    }

    func load(
        sessionID: String,
        attachment: ChatAttachment,
        localMetadata: PersonalAppLocalRecord? = nil,
        onUnauthorized: @escaping @MainActor () -> Void
    ) {
        guard tasks[attachment.id] == nil else { return }
        if let localMetadata,
           attachment.kind == .interactive,
           (localMetadata.artifactId != attachment.id || localMetadata.sessionId != sessionID) {
            states[attachment.id] = .failed(AttachmentDownloadError.invalidIdentifier.localizedDescription)
            return
        }
        let operationToken = beginStateOperation(attachmentID: attachment.id)
        states[attachment.id] = .downloading
        let taskID = UUID()
        let task = Task { [weak self] in
            guard let self else { return }
            defer {
                if tasks[attachment.id]?.id == taskID {
                    tasks[attachment.id] = nil
                }
            }
            do {
                #if DEBUG
                if let fixtureURL = fixtureRetryURLs[attachment.id] {
                    try await Task.sleep(for: .milliseconds(150))
                    try Task.checkCancellation()
                    guard acceptsStateOperation(operationToken, attachmentID: attachment.id) else { return }
                    states[attachment.id] = .available(fixtureURL)
                    return
                }
                #endif
                guard let repository else {
                    states[attachment.id] = .failed("Fixture download is unavailable")
                    return
                }
                let url = try await repository.download(sessionID: sessionID, attachment: attachment)
                try Task.checkCancellation()
                guard acceptsStateOperation(operationToken, attachmentID: attachment.id) else { return }
                if let localMetadata, attachment.kind == .interactive {
                    try await repository.recordPersonalAppLocalMetadata(localMetadata)
                }
                try Task.checkCancellation()
                guard acceptsStateOperation(operationToken, attachmentID: attachment.id) else { return }
                states[attachment.id] = .available(url)
            } catch is CancellationError {
                guard acceptsStateOperation(operationToken, attachmentID: attachment.id) else { return }
                states[attachment.id] = .idle
            } catch AttachmentDownloadError.unauthorized {
                guard acceptsStateOperation(operationToken, attachmentID: attachment.id) else { return }
                states[attachment.id] = .failed("Device authorization expired")
                onUnauthorized()
            } catch AttachmentDownloadError.unavailable {
                guard acceptsStateOperation(operationToken, attachmentID: attachment.id) else { return }
                states[attachment.id] = .unavailable
            } catch AttachmentDownloadError.forbidden {
                guard acceptsStateOperation(operationToken, attachmentID: attachment.id) else { return }
                states[attachment.id] = .unavailable
            } catch {
                guard acceptsStateOperation(operationToken, attachmentID: attachment.id) else { return }
                states[attachment.id] = .failed(error.localizedDescription)
            }
        }
        tasks[attachment.id] = (taskID, task)
    }

    func retry(
        sessionID: String,
        attachment: ChatAttachment,
        localMetadata: PersonalAppLocalRecord? = nil,
        onUnauthorized: @escaping @MainActor () -> Void
    ) {
        tasks[attachment.id]?.task.cancel()
        tasks[attachment.id] = nil
        load(
            sessionID: sessionID,
            attachment: attachment,
            localMetadata: localMetadata,
            onUnauthorized: onUnauthorized
        )
    }

    func replaceRepository(_ repository: any AttachmentDownloading) {
        tasks.values.forEach { $0.task.cancel() }
        tasks.removeAll()
        invalidateAllStateOperations()
        states.removeAll()
        self.repository = repository
    }

    func cancel(_ attachment: ChatAttachment) {
        tasks[attachment.id]?.task.cancel()
        tasks[attachment.id] = nil
        invalidateStateOperations(attachmentID: attachment.id)
        if case .downloading = states[attachment.id] {
            states[attachment.id] = .idle
        }
    }

    func clearAll() {
        let repositoryToClear = repository
        tasks.values.forEach { $0.task.cancel() }
        tasks.removeAll()
        invalidateAllStateOperations()
        ownerCounts.removeAll()
        states.removeAll()
        Task { try? await repositoryToClear?.clearCache() }
    }

    func clearAllAndWait() async {
        let repositoryToClear = repository
        tasks.values.forEach { $0.task.cancel() }
        tasks.removeAll()
        invalidateAllStateOperations()
        ownerCounts.removeAll()
        states.removeAll()
        try? await repositoryToClear?.clearCache()
    }

    func interactiveManifest(
        sessionID: String,
        attachmentID: String
    ) async throws -> InteractiveArtifactManifest {
        #if DEBUG
        if let manifest = fixtureManifests[attachmentID] { return manifest }
        #endif
        guard let repository else { throw AttachmentDownloadError.unavailable }
        return try await repository.interactiveManifest(
            sessionID: sessionID,
            attachmentID: attachmentID
        )
    }

    func preparePersonalApp(
        sessionID: String,
        attachment: ChatAttachment,
        metadata: MobilePersonalApp? = nil,
        onUnauthorized: @escaping @MainActor () -> Void
    ) async throws -> URL {
        if let metadata,
           (metadata.artifactId != attachment.id || metadata.sessionId != sessionID) {
            throw AttachmentDownloadError.invalidIdentifier
        }
        #if DEBUG
        if repository == nil {
            if case let .available(url) = state(for: attachment) {
                if let metadata { upsertFixtureMetadata(metadata) }
                return url
            }
            if let url = fixtureRetryURLs[attachment.id] {
                states[attachment.id] = .available(url)
                if let metadata { upsertFixtureMetadata(metadata) }
                return url
            }
        }
        #endif
        guard let repository else { throw AttachmentDownloadError.unavailable }
        tasks[attachment.id]?.task.cancel()
        tasks[attachment.id] = nil
        let operationToken = beginStateOperation(attachmentID: attachment.id)
        states[attachment.id] = .downloading
        do {
            let url = try await repository.prepareInteractiveArtifact(
                sessionID: sessionID,
                attachment: attachment
            )
            guard acceptsStateOperation(operationToken, attachmentID: attachment.id) else {
                throw CancellationError()
            }
            if let metadata {
                try await repository.recordPersonalAppMetadata(metadata)
            }
            guard acceptsStateOperation(operationToken, attachmentID: attachment.id) else {
                throw CancellationError()
            }
            states[attachment.id] = .available(url)
            return url
        } catch is CancellationError {
            guard acceptsStateOperation(operationToken, attachmentID: attachment.id) else {
                throw CancellationError()
            }
            states[attachment.id] = .idle
            throw CancellationError()
        } catch AttachmentDownloadError.unauthorized {
            guard acceptsStateOperation(operationToken, attachmentID: attachment.id) else {
                throw CancellationError()
            }
            states[attachment.id] = .failed("Device authorization expired")
            onUnauthorized()
            throw AttachmentDownloadError.unauthorized
        } catch AttachmentDownloadError.forbidden {
            guard acceptsStateOperation(operationToken, attachmentID: attachment.id) else {
                throw CancellationError()
            }
            states[attachment.id] = .unavailable
            throw AttachmentDownloadError.forbidden
        } catch AttachmentDownloadError.unavailable {
            guard acceptsStateOperation(operationToken, attachmentID: attachment.id) else {
                throw CancellationError()
            }
            states[attachment.id] = .unavailable
            throw AttachmentDownloadError.unavailable
        } catch {
            guard acceptsStateOperation(operationToken, attachmentID: attachment.id) else {
                throw CancellationError()
            }
            states[attachment.id] = .failed(error.localizedDescription)
            throw error
        }
    }

    func downloadedPersonalAppIDs() async -> Set<String> {
        #if DEBUG
        if repository == nil { return Set(fixtureLocalRecords.map(\.artifactId)) }
        #endif
        return (try? await repository?.downloadedPersonalAppIDs()) ?? []
    }

    func localPersonalApps() async -> [PersonalAppLocalRecord] {
        #if DEBUG
        if repository == nil { return fixtureLocalRecords }
        #endif
        return (try? await repository?.localPersonalApps()) ?? []
    }

    func recordPersonalAppMetadata(_ app: MobilePersonalApp) async {
        #if DEBUG
        if repository == nil {
            if case .available = states[app.artifactId] { upsertFixtureMetadata(app) }
            return
        }
        #endif
        try? await repository?.recordPersonalAppMetadata(app)
    }

    func clearPersonalApp(
        sessionID: String,
        attachment: ChatAttachment
    ) async {
        tasks[attachment.id]?.task.cancel()
        tasks[attachment.id] = nil
        invalidateStateOperations(attachmentID: attachment.id)
        states[attachment.id] = .idle
        #if DEBUG
        if repository == nil {
            fixtureLocalRecords.removeAll { $0.artifactId == attachment.id }
            return
        }
        #endif
        try? await repository?.clearInteractiveArtifact(
            sessionID: sessionID,
            attachment: attachment
        )
    }

    #if DEBUG
    private func upsertFixtureMetadata(_ app: MobilePersonalApp) {
        fixtureLocalRecords.removeAll { $0.artifactId == app.artifactId }
        fixtureLocalRecords.append(PersonalAppLocalRecord(app: app))
    }
    #endif

    private func beginStateOperation(attachmentID: String) -> StateOperationToken {
        artifactStateGenerations[attachmentID, default: 0] &+= 1
        return StateOperationToken(
            namespace: stateNamespaceGeneration,
            artifact: artifactStateGenerations[attachmentID, default: 0]
        )
    }

    private func acceptsStateOperation(
        _ token: StateOperationToken,
        attachmentID: String
    ) -> Bool {
        token.namespace == stateNamespaceGeneration
            && token.artifact == artifactStateGenerations[attachmentID, default: 0]
    }

    private func invalidateStateOperations(attachmentID: String) {
        artifactStateGenerations[attachmentID, default: 0] &+= 1
    }

    private func invalidateAllStateOperations() {
        stateNamespaceGeneration &+= 1
        artifactStateGenerations.removeAll()
    }
}
