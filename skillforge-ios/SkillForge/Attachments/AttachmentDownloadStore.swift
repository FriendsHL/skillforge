import Foundation

@MainActor
final class AttachmentDownloadStore: ObservableObject {
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
    private var repository: (any AttachmentDownloading)?
    #if DEBUG
    private let fixtureRetryURLs: [String: URL]
    #endif

    init(repository: any AttachmentDownloading) {
        self.repository = repository
        #if DEBUG
        fixtureRetryURLs = [:]
        #endif
    }

    #if DEBUG
    init(fixtureStates: [String: State], retryURLs: [String: URL] = [:]) {
        repository = nil
        states = fixtureStates
        fixtureRetryURLs = retryURLs
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
        onUnauthorized: @escaping @MainActor () -> Void
    ) {
        guard tasks[attachment.id] == nil else { return }
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
                    guard tasks[attachment.id]?.id == taskID else { return }
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
                guard tasks[attachment.id]?.id == taskID else { return }
                states[attachment.id] = .available(url)
            } catch is CancellationError {
                guard tasks[attachment.id]?.id == taskID else { return }
                states[attachment.id] = .idle
            } catch AttachmentDownloadError.unauthorized {
                guard tasks[attachment.id]?.id == taskID else { return }
                states[attachment.id] = .failed("Device authorization expired")
                onUnauthorized()
            } catch AttachmentDownloadError.unavailable {
                guard tasks[attachment.id]?.id == taskID else { return }
                states[attachment.id] = .unavailable
            } catch {
                guard tasks[attachment.id]?.id == taskID else { return }
                states[attachment.id] = .failed(error.localizedDescription)
            }
        }
        tasks[attachment.id] = (taskID, task)
    }

    func retry(
        sessionID: String,
        attachment: ChatAttachment,
        onUnauthorized: @escaping @MainActor () -> Void
    ) {
        tasks[attachment.id]?.task.cancel()
        tasks[attachment.id] = nil
        load(sessionID: sessionID, attachment: attachment, onUnauthorized: onUnauthorized)
    }

    func replaceRepository(_ repository: any AttachmentDownloading) {
        let activeAttachmentIDs = Array(tasks.keys)
        tasks.values.forEach { $0.task.cancel() }
        tasks.removeAll()
        for attachmentID in activeAttachmentIDs where states[attachmentID] == .downloading {
            states[attachmentID] = .idle
        }
        self.repository = repository
    }

    func cancel(_ attachment: ChatAttachment) {
        tasks[attachment.id]?.task.cancel()
        tasks[attachment.id] = nil
        if case .downloading = states[attachment.id] {
            states[attachment.id] = .idle
        }
    }

    func clearAll() {
        tasks.values.forEach { $0.task.cancel() }
        tasks.removeAll()
        ownerCounts.removeAll()
        states.removeAll()
        Task { try? await repository?.clearCache() }
    }

    func clearAllAndWait() async {
        tasks.values.forEach { $0.task.cancel() }
        tasks.removeAll()
        ownerCounts.removeAll()
        states.removeAll()
        try? await repository?.clearCache()
    }
}
