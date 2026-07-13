import Combine
import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct ChatView: View {
    @EnvironmentObject private var appState: AppState
    @Environment(\.scenePhase) private var scenePhase

    let endpoint: URL
    let deviceToken: String
    let device: MobileDeviceSummary?
    let defaultAgent: MobileAgentSummary?
    let availableAgents: [MobileAgentCatalogItem]
    private let usesDeterministicFixture: Bool
    private let isActive: Bool
    private let route: ChatRoute?
    private let rootCleanupToken: Int
    private let onRouteHandled: (UUID) -> Void
    private let onDisconnectRequested: (() -> Void)?
    private let onChatAgentSelected: (MobileAgentCatalogItem) -> Void
    private let fixtureSessions: [MobileSession]
    private let bottomAnchorId = "chat-bottom-anchor"

    @State private var sessionSheetOpen = false
    @State private var newConversationOpen = false
    @State private var didLoad = false
    @State private var sessions: [MobileSession] = []
    @State private var selectedSession: MobileSession?
    @State private var messages: [ChatMessage] = []
    @State private var pendingInteractions: [PendingInteraction] = []
    @State private var submittingInteractionId: String?
    @State private var pendingInteractionErrorId: String?
    @State private var pendingInteractionError: String?
    @State private var uploadedAttachments: [MobileUploadedAttachment] = []
    @State private var isUploadingAttachment = false
    @State private var attachmentUploadTask: Task<Void, Never>?
    @State private var isLoading = false
    @State private var isSending = false
    @State private var isRefreshing = false
    @State private var isAgentRunning = false
    @State private var errorText: String?
    @State private var runtimeStatusOverride: String?
    @State private var composerText = ""
    @State private var operationTask: Task<Void, Never>?
    @State private var activationTask: Task<Void, Never>?
    @State private var sendTask: Task<Void, Never>?
    @State private var sendOperationId: UUID?
    @State private var optimisticMessageId: String?
    @State private var minimumExpectedRemoteMessageCount: Int?
    @State private var highestAppliedRemoteSeqNo: Int64?
    @State private var realtimeTask: Task<Void, Never>?
    @State private var realtimeSocket: URLSessionWebSocketTask?
    @State private var realtimeState = MobileRealtimeState()
    @State private var streamingBuffer = ""
    @State private var streamingFlushTask: Task<Void, Never>?
    @State private var deferredBottomScrollRequest = 0
    @State private var pendingBottomScrollAfterKeyboard = false
    @State private var pendingAutoScrollAfterKeyboard = false
    @State private var pendingHandoffLayoutRecovery = false
    @State private var stabilizeStreamingTailIdentity = false
    @State private var isKeyboardVisible = false
    @State private var isKeyboardSettling = false
    @State private var deferredBottomScrollTask: Task<Void, Never>?
    @State private var pendingRoute: ChatRoute?
    @State private var expandedToolCallIDs: Set<String> = []
    @State private var isTranscriptPinnedToBottom = true
    @State private var acceptedAgentSelectionID: Int64?
    @State private var fixtureSessionSequence = 0
    @State private var sessionStateGeneration = 0
    #if DEBUG
    @State private var deterministicHandoffCompleted = false
    @State private var deterministicHandoffCheckpoint: String?
    @State private var deterministicCheckpointAcknowledgement: String?
    #endif
    @StateObject private var attachmentStore: AttachmentDownloadStore
    @FocusState private var composerFocused: Bool

    init(
        endpoint: URL,
        deviceToken: String,
        device: MobileDeviceSummary?,
        defaultAgent: MobileAgentSummary?,
        availableAgents: [MobileAgentCatalogItem] = [],
        initialSession: MobileSession? = nil,
        initialSessions: [MobileSession] = [],
        initialMessages: [ChatMessage] = [],
        initialPendingInteractions: [PendingInteraction] = [],
        initialAttachments: [MobileUploadedAttachment] = [],
        usesDeterministicFixture: Bool = false,
        isActive: Bool = true,
        route: ChatRoute? = nil,
        rootCleanupToken: Int = 0,
        onRouteHandled: @escaping (UUID) -> Void = { _ in },
        onDisconnectRequested: (() -> Void)? = nil,
        onChatAgentSelected: @escaping (MobileAgentCatalogItem) -> Void = { _ in },
        attachmentStore: AttachmentDownloadStore? = nil
    ) {
        self.endpoint = endpoint
        self.deviceToken = deviceToken
        self.device = device
        self.defaultAgent = defaultAgent
        self.availableAgents = availableAgents
        self.usesDeterministicFixture = usesDeterministicFixture
        self.isActive = isActive
        self.route = route
        self.rootCleanupToken = rootCleanupToken
        self.onRouteHandled = onRouteHandled
        self.onDisconnectRequested = onDisconnectRequested
        self.onChatAgentSelected = onChatAgentSelected
        let seededSessions = initialSessions.isEmpty
            ? initialSession.map { [$0] } ?? []
            : initialSessions
        let seededSelection = initialSession ?? seededSessions.first
        fixtureSessions = seededSessions
        _didLoad = State(initialValue: seededSelection != nil)
        _sessions = State(initialValue: seededSessions)
        _selectedSession = State(initialValue: seededSelection)
        _messages = State(initialValue: initialMessages)
        _highestAppliedRemoteSeqNo = State(initialValue: initialMessages.compactMap(\.remoteSeqNo).max())
        _pendingInteractions = State(initialValue: initialPendingInteractions)
        _uploadedAttachments = State(initialValue: initialAttachments)
        let deviceIdentity = device?.id ?? "token:\(deviceToken)"
        let resolvedAttachmentStore = attachmentStore ?? AttachmentDownloadStore(
            repository: AttachmentDownloadRepository(
                endpoint: endpoint,
                deviceID: deviceIdentity,
                deviceToken: deviceToken
            )
        )
        _attachmentStore = StateObject(wrappedValue: resolvedAttachmentStore)
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                chatHeader
                if let errorText {
                    Text(errorText)
                        .font(.footnote)
                        .foregroundStyle(.red)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal)
                        .padding(.vertical, 8)
                }
                chatScroller
                ComposerView(
                    text: $composerText,
                    isSending: isSending || selectedSession == nil || !pendingInteractions.isEmpty,
                    isUploading: isUploadingAttachment,
                    attachments: uploadedAttachments,
                    focus: $composerFocused,
                    onSelectAttachment: handleAttachmentSelection,
                    onRemoveAttachment: removeAttachment
                ) { text, submittedDraft in
                    let attachments = uploadedAttachments
                    startSend(
                        text,
                        submittedDraft: submittedDraft,
                        attachments: attachments
                    )
                }
            }
            .toolbar(.hidden, for: .navigationBar)
            #if DEBUG
            .overlay(alignment: .topLeading) {
                VStack(spacing: 0) {
                    if let deterministicHandoffCheckpoint {
                        handoffCheckpointMarker(deterministicHandoffCheckpoint)
                        Button {
                            deterministicCheckpointAcknowledgement = deterministicHandoffCheckpoint
                        } label: {
                            Color.clear.frame(width: 20, height: 20)
                        }
                        .accessibilityIdentifier(
                            "chat.streamingHandoff.continue.\(deterministicHandoffCheckpoint)"
                        )
                    }
                    if deterministicHandoffCompleted {
                        handoffCheckpointMarker("complete")
                        if !stabilizeStreamingTailIdentity {
                            handoffCheckpointMarker("identity-released")
                        }
                    }
                }
            }
            #endif
            .sheet(isPresented: $sessionSheetOpen) {
                SessionListView(
                    defaultAgent: defaultAgent,
                    sessions: sessions,
                    selectedSessionId: selectedSession?.id,
                    onSelect: { session in
                        sessionSheetOpen = false
                        startOperation { await select(session) }
                    },
                    onCreate: {
                        sessionSheetOpen = false
                        Task { @MainActor in
                            await Task.yield()
                            newConversationOpen = true
                        }
                    },
                    onRefresh: refreshSessionList
                )
            }
            .sheet(isPresented: $newConversationOpen) {
                NewConversationView(
                    agents: availableAgents,
                    currentAgentID: defaultAgent?.id,
                    onCreate: createConversation
                )
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
            }
            .task {
                startOperation { await loadInitialSession() }
                #if DEBUG
                await runDeterministicStreamingHandoffIfNeeded()
                #endif
            }
            .onChange(of: isActive) { _, active in
                if active {
                    resumeAfterInactivity()
                } else {
                    pauseForInactivity()
                }
            }
            .onChange(of: defaultAgent?.id) { oldAgentId, newAgentId in
                guard oldAgentId != newAgentId else { return }
                if acceptedAgentSelectionID == newAgentId {
                    acceptedAgentSelectionID = nil
                    return
                }
                startOperation { await switchSelectedAgent() }
            }
            .onChange(of: endpoint) { oldEndpoint, newEndpoint in
                guard oldEndpoint != newEndpoint, !usesDeterministicFixture else { return }
                resumeAfterEndpointChange()
            }
            .onChange(of: route) { _, newRoute in
                guard let newRoute else { return }
                receive(newRoute)
            }
            .onChange(of: rootCleanupToken) { _, _ in
                performFullCleanup()
            }
            .onChange(of: scenePhase) { _, phase in
                guard !usesDeterministicFixture else { return }
                if phase == .active, isActive {
                    resumeAfterInactivity()
                } else {
                    pauseRealtime()
                }
            }
            .onDisappear {
                pauseForInactivity()
            }
            .onReceive(NotificationCenter.default.publisher(for: .skillForgeChatRootDisconnect)) { _ in
                performFullCleanup()
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { _ in
                isKeyboardVisible = true
                isKeyboardSettling = true
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardDidShowNotification)) { _ in
                isKeyboardVisible = true
                isKeyboardSettling = false
                if composerFocused {
                    if isTranscriptPinnedToBottom {
                        deferredBottomScrollRequest += 1
                    }
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
                isKeyboardSettling = true
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardDidHideNotification)) { _ in
                isKeyboardVisible = false
                isKeyboardSettling = false
                completePendingBottomScroll()
            }
        }
    }

    private var client: MobileApiClient {
        MobileApiClient(baseURL: endpoint, deviceToken: deviceToken)
    }

    private var assistantName: String {
        defaultAgent?.name ?? "Main Assistant"
    }

    private var selectedSessionTitle: String {
        if let title = selectedSession?.title, !title.isEmpty {
            return title
        }
        return assistantName
    }

    private var renderedMessages: [ChatMessage] {
        var list = messages
        if hasStreamingMessage {
            list.append(ChatMessage(
                id: "streaming-\(selectedSession?.id ?? "active")",
                role: .assistant,
                text: realtimeState.streamingText,
                toolCalls: realtimeState.streamingToolCalls,
                isStreaming: true
            ))
        }
        return list
    }

    private var hasStreamingMessage: Bool {
        !realtimeState.streamingText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            || !realtimeState.streamingToolCalls.isEmpty
    }

    private var renderedRows: [ChatTranscriptRow] {
        ChatTranscriptRow.rows(
            for: renderedMessages,
            stabilizeStreamingTail: stabilizeStreamingTailIdentity
        )
    }

    private var chatScroller: some View {
        ScrollViewReader { proxy in
            ZStack(alignment: .bottomTrailing) {
                ScrollView {
                    LazyVStack(spacing: 12) {
                        if isLoading && renderedMessages.isEmpty {
                            ProgressView("Loading chat")
                                .padding(.top, 80)
                        } else if renderedMessages.isEmpty {
                            ContentUnavailableView("No messages", systemImage: "bubble.left.and.bubble.right")
                                .padding(.top, 80)
                        } else {
                            ForEach(renderedRows) { row in
                                MessageBubbleView(
                                    message: row.message,
                                    sessionID: selectedSession?.id ?? "",
                                    attachmentStore: attachmentStore,
                                    expandedToolCallIDs: $expandedToolCallIDs,
                                    onUnauthorized: disconnectForUnauthorizedAttachment
                                )
                            }
                        }
                        ForEach(pendingInteractions) { interaction in
                            PendingCardView(
                                interaction: interaction,
                                isSubmitting: submittingInteractionId != nil,
                                errorText: pendingInteractionErrorId == interaction.id
                                    ? pendingInteractionError
                                    : nil,
                                onAnswer: { answer in
                                    startOperation {
                                        await submit(interaction: interaction, answer: answer)
                                    }
                                },
                                onDecision: { decision in
                                    startOperation {
                                        await submit(interaction: interaction, decision: decision)
                                    }
                                }
                            )
                        }
                        Color.clear
                            .frame(height: 1)
                            .id(bottomAnchorId)
                    }
                    .id(ChatTranscriptPolicy.containerIdentity(sessionID: selectedSession?.id))
                    .padding()
                    .frame(maxWidth: .infinity)
                }
                .accessibilityIdentifier("chat.transcript")
                .accessibilityValue(selectedSession?.id ?? "")
                .contentShape(Rectangle())
                .scrollDismissesKeyboard(.interactively)
                .simultaneousGesture(TapGesture().onEnded {
                    composerFocused = false
                })
                .simultaneousGesture(DragGesture(minimumDistance: 8).onChanged { _ in
                    isTranscriptPinnedToBottom = false
                })

                if !renderedRows.isEmpty {
                    Button {
                        handleBottomButtonTap(proxy: proxy)
                    } label: {
                        Image(systemName: "arrow.down")
                            .font(.callout.weight(.bold))
                            .foregroundStyle(.white)
                            .frame(width: 42, height: 42)
                            .background(Color(red: 0.10, green: 0.12, blue: 0.16))
                            .clipShape(Circle())
                            .shadow(color: .black.opacity(0.18), radius: 12, x: 0, y: 5)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Scroll to latest message")
                    .accessibilityIdentifier("chat.scrollToBottom")
                    .padding(.trailing, 16)
                    .padding(.bottom, 14)
                }
            }
            .onChange(of: messages.count) { _, _ in
                requestAutoScroll(proxy: proxy, animated: true)
            }
            .onChange(of: realtimeState.streamingText) { _, _ in
                guard isAgentRunning else { return }
                if isTranscriptPinnedToBottom, isKeyboardVisible, !isKeyboardSettling {
                    scrollToBottom(proxy: proxy, animated: false)
                } else {
                    requestAutoScroll(proxy: proxy, animated: false)
                }
            }
            .onChange(of: realtimeState.streamingToolCalls.count) { _, _ in
                requestAutoScroll(proxy: proxy, animated: true)
            }
            .onChange(of: realtimeState.streamingToolCalls) { _, _ in
                guard isTranscriptPinnedToBottom, isKeyboardVisible, !isKeyboardSettling else { return }
                deferredBottomScrollRequest += 1
            }
            .onChange(of: hasStreamingMessage) { wasStreaming, isStreaming in
                if !wasStreaming, isStreaming {
                    stabilizeStreamingTailIdentity = true
                    return
                }
                guard wasStreaming, !isStreaming else { return }
                let keyboardBlocksScroll = !ChatScrollPolicy.shouldAutoScroll(
                    isComposerFocused: composerFocused,
                    isKeyboardVisible: isKeyboardVisible,
                    isKeyboardSettling: isKeyboardSettling
                )
                if keyboardBlocksScroll, isTranscriptPinnedToBottom {
                    pendingHandoffLayoutRecovery = true
                    deferredBottomScrollRequest += 1
                } else {
                    pendingHandoffLayoutRecovery = false
                    stabilizeStreamingTailIdentity = false
                }
            }
            .onChange(of: pendingInteractions.count) { _, _ in
                requestAutoScroll(proxy: proxy, animated: true)
            }
            .onChange(of: deferredBottomScrollRequest) { _, _ in
                scrollToBottom(proxy: proxy, animated: false)
            }
        }
    }

    private var chatHeader: some View {
        VStack(spacing: 12) {
            HStack(spacing: 12) {
                Button {
                    sessionSheetOpen = true
                } label: {
                    Image(systemName: "sidebar.left")
                        .font(.title3.weight(.semibold))
                        .frame(width: 44, height: 44)
                        .background(.white.opacity(0.9))
                        .clipShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Sessions")

                HStack(spacing: 10) {
                    Text("SF")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.white)
                        .frame(width: 34, height: 34)
                        .background(Color(red: 0.10, green: 0.12, blue: 0.16))
                        .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
                    VStack(alignment: .leading, spacing: 3) {
                        Text(assistantName)
                            .font(.headline.weight(.bold))
                        Text("SkillForge Mac 已连接")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                Button {
                    newConversationOpen = true
                } label: {
                    Image(systemName: "plus")
                        .font(.title3.weight(.semibold))
                        .frame(width: 44, height: 44)
                        .background(.white.opacity(0.9))
                        .clipShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("New conversation")
                .accessibilityIdentifier("chat.newConversation")
            }

            HStack(spacing: 9) {
                Circle()
                    .fill(errorText == nil ? .green : .red)
                    .frame(width: 8, height: 8)
                    .shadow(color: .green.opacity(0.35), radius: 5)
                Text(statusText)
                    .font(.footnote.weight(.medium))
                Spacer()
                Text(endpoint.host() ?? endpoint.absoluteString)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 11)
            .padding(.vertical, 9)
            .background(Color.green.opacity(0.14))
            .overlay {
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .stroke(Color.green.opacity(0.22), lineWidth: 1)
            }
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
        .padding(.horizontal, 18)
        .padding(.top, 14)
        .padding(.bottom, 12)
        .background(Color(uiColor: .secondarySystemBackground).opacity(0.96))
    }

    private var statusText: String {
        ChatStatusText.resolve(
            isAgentRunning: isAgentRunning || isSending || runtimeStatusOverride == "running" || selectedSession?.runtimeStatus == "running",
            isRefreshing: isRefreshing,
            runtimeStatus: runtimeStatusOverride ?? selectedSession?.runtimeStatus,
            hasError: errorText != nil
        )
    }

    private var shouldPreserveVisibleTranscriptDuringRefresh: Bool {
        isAgentRunning
            || isSending
            || runtimeStatusOverride == "running"
            || selectedSession?.runtimeStatus == "running"
    }

    @MainActor
    private func pauseForInactivity() {
        composerFocused = false
        activationTask?.cancel()
        activationTask = nil
        pauseRealtime()
    }

    @MainActor
    private func resumeAfterInactivity() {
        guard isActive, scenePhase == .active else { return }
        guard !usesDeterministicFixture else { return }
        guard didLoad, !isLoading, let session = selectedSession else { return }

        activationTask?.cancel()
        activationTask = Task { @MainActor in
            await refreshSelectedSessionMetadata()
            guard !Task.isCancelled, selectedSession?.id == session.id, isActive else { return }
            await loadMessages(for: session, preserveVisibleTranscript: true)
            guard !Task.isCancelled, selectedSession?.id == session.id, isActive else { return }
            if selectedSession?.runtimeStatus != "running" {
                resetStreamingState()
            }
            startRealtime(for: session)
            activationTask = nil
        }
    }

    @MainActor
    private func resumeAfterEndpointChange() {
        attachmentStore.replaceRepository(
            AttachmentDownloadRepository(
                endpoint: endpoint,
                deviceID: device?.id ?? "token:\(deviceToken)",
                deviceToken: deviceToken
            )
        )
        guard isActive, scenePhase == .active, let session = selectedSession else { return }
        activationTask?.cancel()
        pauseRealtime()
        activationTask = Task { @MainActor in
            await loadMessages(for: session, preserveVisibleTranscript: true)
            guard !Task.isCancelled, selectedSession?.id == session.id, isActive else { return }
            startRealtime(for: session)
            activationTask = nil
        }
    }

    @MainActor
    private func performFullCleanup() {
        composerFocused = false
        operationTask?.cancel()
        operationTask = nil
        activationTask?.cancel()
        activationTask = nil
        cancelSend()
        attachmentUploadTask?.cancel()
        attachmentUploadTask = nil
        isUploadingAttachment = false
        stopRealtime()
        attachmentStore.clearAll()
    }

    @MainActor
    private func disconnectForUnauthorizedAttachment() {
        if let onDisconnectRequested {
            onDisconnectRequested()
        } else {
            performFullCleanup()
            appState.resetPairing()
        }
    }

    @MainActor
    private func receive(_ newRoute: ChatRoute) {
        pendingRoute = newRoute
        guard didLoad, !isLoading else { return }
        if usesDeterministicFixture {
            applyFixtureRoute(newRoute)
            return
        }
        startOperation { await applyPendingRouteIfNeeded() }
    }

    @MainActor
    private func applyFixtureRoute(_ newRoute: ChatRoute) {
        sessions = visibleSessions(from: sessions + [newRoute.session])
        if selectedSession?.id != newRoute.session.id {
            resetTranscriptPresentationForSessionChange()
        }
        selectedSession = newRoute.session
        pendingRoute = nil
        onRouteHandled(newRoute.id)
    }

    @MainActor
    private func applyPendingRouteIfNeeded() async {
        guard let pendingRoute else { return }
        self.pendingRoute = nil
        sessions = visibleSessions(from: sessions + [pendingRoute.session])
        await select(pendingRoute.session)
        guard !Task.isCancelled, selectedSession?.id == pendingRoute.session.id else { return }
        onRouteHandled(pendingRoute.id)
    }

    @MainActor
    private func startOperation(_ operation: @escaping @MainActor () async -> Void) {
        operationTask?.cancel()
        isRefreshing = false
        operationTask = Task { @MainActor in
            await operation()
        }
    }

    @MainActor
    private func startSend(
        _ text: String,
        submittedDraft: String,
        attachments: [MobileUploadedAttachment]
    ) {
        guard sendTask == nil else { return }
        guard selectedSession != nil else { return }
        guard ChatComposerPolicy.canSend(
            text: text,
            attachmentCount: attachments.count,
            isSending: isSending,
            isUploading: isUploadingAttachment
        ) else { return }
        isTranscriptPinnedToBottom = true
        let operationId = UUID()
        sendOperationId = operationId
        sendTask = Task { @MainActor in
            await send(text, attachments: attachments, submittedDraft: submittedDraft)
            if sendOperationId == operationId {
                sendTask = nil
                sendOperationId = nil
            }
        }
    }

    @MainActor
    private func cancelSend() {
        sendOperationId = nil
        sendTask?.cancel()
        sendTask = nil
    }

    @MainActor
    private func loadInitialSession() async {
        guard !usesDeterministicFixture else { return }
        guard !didLoad else { return }
        didLoad = true
        pendingRoute = route
        await reloadSessions()
    }

    @MainActor
    private func switchSelectedAgent() async {
        activationTask?.cancel()
        activationTask = nil
        stopRealtime()
        cancelSend()
        isSending = false
        resetTranscriptPresentationForSessionChange()
        selectedSession = nil
        messages = []
        pendingInteractions = []
        pendingInteractionErrorId = nil
        pendingInteractionError = nil
        clearAttachmentDrafts()
        composerText = ""
        clearOptimisticProtection()
        resetStreamingState()
        runtimeStatusOverride = nil
        isAgentRunning = false
        errorText = nil

        if usesDeterministicFixture {
            sessions = visibleSessions(from: fixtureSessions)
            selectedSession = sessions.first
            return
        }
        await reloadSessions()
    }

    @MainActor
    private func reloadSessions() async {
        let generation = sessionStateGeneration
        isLoading = true
        errorText = nil
        defer { isLoading = false }

        do {
            let loaded = visibleSessions(from: try await client.listSessions())
            guard !Task.isCancelled, generation == sessionStateGeneration else { return }
            sessions = loaded
            let previousSessionId = selectedSession?.id
            let nextSessionID = ChatTranscriptPolicy.sessionIDAfterRefresh(
                currentSessionID: previousSessionId,
                loadedSessionIDs: loaded.map(\.id)
            )
            let nextSelection = selectedSession.flatMap { current in
                current.id == nextSessionID ? current : nil
            } ?? loaded.first { $0.id == nextSessionID }
            let preserveVisibleTranscript = nextSelection.map { next in
                previousSessionId == next.id
            } ?? false
            if previousSessionId != nextSelection?.id {
                cancelSend()
                isSending = false
                resetTranscriptPresentationForSessionChange()
                messages = []
                pendingInteractions = []
                pendingInteractionErrorId = nil
                pendingInteractionError = nil
                clearAttachmentDrafts()
                composerText = ""
                clearOptimisticProtection()
            }
            selectedSession = nextSelection
            if let nextSelection {
                isAgentRunning = nextSelection.runtimeStatus == "running"
                runtimeStatusOverride = nextSelection.runtimeStatus
                await loadMessages(
                    for: nextSelection,
                    preserveVisibleTranscript: preserveVisibleTranscript
                )
                guard !Task.isCancelled, selectedSession?.id == nextSelection.id else { return }
                startRealtime(for: nextSelection)
            }
            await applyPendingRouteIfNeeded()
        } catch {
            guard !Task.isCancelled else { return }
            handle(error)
        }
    }

    @MainActor
    private func select(_ session: MobileSession) async {
        isLoading = true
        defer { isLoading = false }
        let isCurrentSession = selectedSession?.id == session.id
        let preserveVisibleTranscript = isCurrentSession
            && (shouldPreserveVisibleTranscriptDuringRefresh || session.runtimeStatus == "running")
        selectedSession = session
        isAgentRunning = session.runtimeStatus == "running"
        runtimeStatusOverride = session.runtimeStatus
        if !isCurrentSession {
            cancelSend()
            isSending = false
            resetTranscriptPresentationForSessionChange()
            messages = []
            pendingInteractions = []
            pendingInteractionErrorId = nil
            pendingInteractionError = nil
            clearAttachmentDrafts()
            composerText = ""
            clearOptimisticProtection()
        }
        if !isCurrentSession {
            resetStreamingState()
        }
        await loadMessages(
            for: session,
            preserveVisibleTranscript: preserveVisibleTranscript
        )
        guard !Task.isCancelled, selectedSession?.id == session.id else { return }
        startRealtime(for: session)
    }

    @MainActor
    private func createConversation(with agent: MobileAgentCatalogItem) async throws {
        let created: MobileSession
        if usesDeterministicFixture {
            fixtureSessionSequence += 1
            created = MobileSession(
                id: "fixture-created-\(fixtureSessionSequence)",
                userId: 1,
                agentId: agent.id,
                title: "New Conversation",
                status: "active",
                runtimeStatus: "idle",
                messageCount: 0,
                updatedAt: ISO8601DateFormatter().string(from: Date())
            )
        } else {
            created = try await client.createSession(agentId: agent.id)
        }
        guard !Task.isCancelled else { throw CancellationError() }

        // Invalidate older list/load tasks before committing the new selection.
        sessionStateGeneration &+= 1
        operationTask?.cancel()
        operationTask = nil
        activationTask?.cancel()
        activationTask = nil
        stopRealtime()

        if agent.id != defaultAgent?.id {
            acceptedAgentSelectionID = agent.id
        }
        onChatAgentSelected(agent)
        if agent.id == defaultAgent?.id {
            sessions.removeAll { $0.id == created.id }
            sessions.insert(created, at: 0)
        } else {
            sessions = [created]
        }
        selectedSession = created
        cancelSend()
        isSending = false
        resetTranscriptPresentationForSessionChange()
        isAgentRunning = created.runtimeStatus == "running"
        runtimeStatusOverride = created.runtimeStatus
        messages = []
        pendingInteractions = []
        pendingInteractionErrorId = nil
        pendingInteractionError = nil
        clearAttachmentDrafts()
        composerText = ""
        clearOptimisticProtection()
        resetStreamingState()
        errorText = nil

        guard !usesDeterministicFixture else { return }
        await loadMessages(for: created)
        guard !Task.isCancelled, selectedSession?.id == created.id else { return }
        startRealtime(for: created)
    }

    @MainActor
    private func refreshSessionList() async {
        guard !usesDeterministicFixture else { return }
        let generation = sessionStateGeneration
        do {
            let loaded = visibleSessions(from: try await client.listSessions())
            guard !Task.isCancelled, generation == sessionStateGeneration else { return }
            sessions = loaded
        } catch {
            guard !Task.isCancelled else { return }
            if case let MobileApiError.httpStatus(status, _) = error, status == 401 {
                handle(error)
            } else {
                errorText = error.localizedDescription
            }
        }
    }

    @MainActor
    private func loadMessages(
        for session: MobileSession,
        preserveVisibleTranscript: Bool = false
    ) async {
        do {
            let rawMessages = try await client.getMessages(sessionId: session.id)
            guard !Task.isCancelled, selectedSession?.id == session.id else { return }
            applyRemoteSnapshot(
                rawMessages,
                preserveVisibleTranscript: preserveVisibleTranscript,
                clearStreamingText: true,
                clearToolCalls: true
            )

            do {
                let remoteConfirmations = try await client.getPendingConfirmations(sessionId: session.id)
                guard !Task.isCancelled, selectedSession?.id == session.id else { return }
                mergePendingInteractions(
                    from: rawMessages,
                    remoteConfirmations: remoteConfirmations
                )
            } catch {
                guard !Task.isCancelled, selectedSession?.id == session.id else { return }
                handle(error)
            }
        } catch {
            guard !Task.isCancelled, selectedSession?.id == session.id else { return }
            handle(error)
        }
    }

    @MainActor
    private func send(
        _ text: String,
        attachments: [MobileUploadedAttachment],
        submittedDraft: String
    ) async {
        guard let session = selectedSession else { return }
        guard ChatComposerPolicy.canSend(
            text: text,
            attachmentCount: attachments.count,
            isSending: isSending,
            isUploading: isUploadingAttachment
        ) else { return }
        composerFocused = false
        let previousCount = messages.count
        isSending = true
        isAgentRunning = true
        runtimeStatusOverride = "running"
        errorText = nil
        resetStreamingState()
        let optimisticText = text.isEmpty
            ? "已发送附件：\n" + attachments.map { "- \($0.filename)" }.joined(separator: "\n")
            : text
        let optimisticMessage = ChatMessage(role: .user, text: optimisticText)
        messages.append(optimisticMessage)
        optimisticMessageId = optimisticMessage.id
        minimumExpectedRemoteMessageCount = previousCount + 1

        if usesDeterministicFixture {
            // Exercise the production race: status/refresh may arrive before the
            // persisted user row. A lagging snapshot must not clear history or the
            // optimistic message while the minimum expected count is outstanding.
            reconcileRemoteMessages(
                Array(messages.dropLast()),
                preserveVisibleTranscript: false
            )
            let sentIds = Set(attachments.map(\.id))
            uploadedAttachments.removeAll { sentIds.contains($0.id) }
            clearOptimisticProtection()
            isSending = false
            isAgentRunning = false
            runtimeStatusOverride = "idle"
            return
        }

        do {
            _ = try await client.sendMessage(
                sessionId: session.id,
                text: text,
                attachmentIds: attachments.map(\.id)
            )
            guard !Task.isCancelled, selectedSession?.id == session.id else { return }
            let sentIds = Set(attachments.map(\.id))
            uploadedAttachments.removeAll { sentIds.contains($0.id) }
            isSending = false
            await refreshAfterSend(sessionId: session.id, previousCount: previousCount)
        } catch {
            guard !Task.isCancelled, selectedSession?.id == session.id else { return }
            if let optimisticMessageId {
                messages.removeAll { $0.id == optimisticMessageId }
            }
            clearOptimisticProtection()
            isSending = false
            isAgentRunning = false
            runtimeStatusOverride = selectedSession?.runtimeStatus
            composerText = ChatComposerPolicy.draftAfterSendFailed(
                currentDraft: composerText,
                submittedDraft: submittedDraft
            )
            handle(error)
        }
    }

    @MainActor
    private func refreshAfterSend(sessionId: String, previousCount: Int) async {
        isRefreshing = true
        defer { isRefreshing = false }

        for attempt in 0..<8 {
            guard !Task.isCancelled, selectedSession?.id == sessionId else { return }
            if attempt > 0 {
                do {
                    try await Task.sleep(nanoseconds: 2_000_000_000)
                } catch {
                    return
                }
            }
            do {
                let rawMessages = try await client.getMessages(sessionId: sessionId)
                guard !Task.isCancelled, selectedSession?.id == sessionId else { return }
                applyRemoteSnapshot(
                    rawMessages,
                    preserveVisibleTranscript: shouldPreserveVisibleTranscriptDuringRefresh,
                    clearStreamingText: true,
                    clearToolCalls: true
                )
                if rawMessages.count > previousCount + 1 {
                    break
                }
            } catch {
                guard !Task.isCancelled, selectedSession?.id == sessionId else { return }
                handle(error)
                break
            }
        }
        await refreshSelectedSessionMetadata()
        if selectedSession?.runtimeStatus != "running" && runtimeStatusOverride != "running" {
            isAgentRunning = false
        }
    }

    @MainActor
    private func refreshForeground() async {
        guard !usesDeterministicFixture else { return }
        guard didLoad else { return }
        await reloadSessions()
    }

    @MainActor
    private func refreshSelectedSessionMetadata() async {
        guard let selectedSession else { return }
        do {
            let refreshed = try await client.getSession(sessionId: selectedSession.id)
            guard !Task.isCancelled, self.selectedSession?.id == selectedSession.id else { return }
            self.selectedSession = refreshed
            sessions = sessions.map { $0.id == refreshed.id ? refreshed : $0 }
            runtimeStatusOverride = refreshed.runtimeStatus
            if refreshed.runtimeStatus != "running" {
                isAgentRunning = false
            }
        } catch {
            guard !Task.isCancelled, self.selectedSession?.id == selectedSession.id else { return }
            handle(error)
        }
    }

    @MainActor
    private func handle(_ error: Error) {
        if case let MobileApiError.httpStatus(status, _) = error, status == 401 {
            performFullCleanup()
            appState.resetPairing()
            return
        }
        errorText = error.localizedDescription
    }

    @MainActor
    private func submit(
        interaction: PendingInteraction,
        answer: String? = nil,
        decision: MobileConfirmationDecision? = nil
    ) async {
        guard let session = selectedSession else { return }
        submittingInteractionId = interaction.id
        pendingInteractionErrorId = nil
        pendingInteractionError = nil
        defer {
            if submittingInteractionId == interaction.id {
                submittingInteractionId = nil
            }
        }

        if usesDeterministicFixture {
            pendingInteractions.removeAll { $0.id == interaction.id }
            return
        }

        do {
            switch interaction.kind {
            case .ask:
                guard let answer, !answer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                    return
                }
                _ = try await client.answerAsk(
                    sessionId: session.id,
                    askId: interaction.id,
                    answer: answer
                )
            case .confirmation:
                guard let decision else { return }
                _ = try await client.answerConfirmation(
                    sessionId: session.id,
                    confirmationId: interaction.id,
                    decision: decision
                )
            }
            guard !Task.isCancelled, selectedSession?.id == session.id else { return }
            pendingInteractions.removeAll { $0.id == interaction.id }
            pendingInteractionErrorId = nil
            isAgentRunning = true
            runtimeStatusOverride = "running"
            await loadMessages(for: session, preserveVisibleTranscript: true)
            await refreshSelectedSessionMetadata()
        } catch {
            guard !Task.isCancelled, selectedSession?.id == session.id else { return }
            if case let MobileApiError.httpStatus(status, _) = error, status == 410 {
                pendingInteractions.removeAll { $0.id == interaction.id }
            }
            if case let MobileApiError.httpStatus(status, _) = error, status == 401 {
                handle(error)
            } else {
                pendingInteractionErrorId = interaction.id
                pendingInteractionError = error.localizedDescription
            }
        }
    }

    @MainActor
    private func handleAttachmentSelection(_ result: Result<URL, Error>) {
        switch result {
        case let .success(url):
            startAttachmentUpload(url)
        case let .failure(error):
            handle(error)
        }
    }

    @MainActor
    private func startAttachmentUpload(_ url: URL) {
        guard let session = selectedSession else { return }
        guard uploadedAttachments.count < 5 else {
            errorText = "一次最多发送 5 个附件"
            return
        }
        attachmentUploadTask?.cancel()
        isUploadingAttachment = true
        errorText = nil
        let sessionId = session.id
        let mimeType = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType
            ?? "application/octet-stream"
        attachmentUploadTask = Task { @MainActor in
            defer {
                if selectedSession?.id == sessionId {
                    isUploadingAttachment = false
                    attachmentUploadTask = nil
                }
            }
            do {
                let attachment = try await client.uploadAttachment(
                    sessionId: sessionId,
                    fileURL: url,
                    mimeType: mimeType
                )
                guard !Task.isCancelled, selectedSession?.id == sessionId else { return }
                uploadedAttachments.removeAll { $0.id == attachment.id }
                uploadedAttachments.append(attachment)
            } catch {
                guard !Task.isCancelled, selectedSession?.id == sessionId else { return }
                handle(error)
            }
        }
    }

    @MainActor
    private func removeAttachment(_ id: String) {
        uploadedAttachments.removeAll { $0.id == id }
    }

    @MainActor
    private func clearAttachmentDrafts() {
        attachmentUploadTask?.cancel()
        attachmentUploadTask = nil
        isUploadingAttachment = false
        uploadedAttachments = []
    }

    @MainActor
    private func mergePendingInteractions(
        from messages: [MobileSessionMessage],
        remoteConfirmations: [MobilePendingConfirmation] = []
    ) {
        let persisted = PendingInteraction.persisted(from: messages)
        let recovered = remoteConfirmations.map(PendingInteraction.init(remoteConfirmation:))
        let persistedIds = Set((persisted + recovered).map(\.id))
        let shouldKeepRealtime = runtimeStatusOverride == "waiting_user"
            || selectedSession?.runtimeStatus == "waiting_user"
        let realtimeOnly = shouldKeepRealtime
            ? pendingInteractions.filter {
                $0.source == .realtime && !persistedIds.contains($0.id)
            }
            : []
        pendingInteractions = persisted + recovered + realtimeOnly
    }

    @MainActor
    private func reconcileRemoteMessages(
        _ remoteMessages: [ChatMessage],
        preserveVisibleTranscript: Bool
    ) {
        messages = ChatTranscriptPolicy.reconcileRemoteSnapshot(
            current: messages,
            remote: remoteMessages,
            preserveVisibleTranscript: preserveVisibleTranscript,
            minimumExpectedRemoteCount: minimumExpectedRemoteMessageCount
        )
        if let minimumExpectedRemoteMessageCount,
           remoteMessages.count >= minimumExpectedRemoteMessageCount {
            clearOptimisticProtection()
        }
    }

    @MainActor
    private func applyRemoteSnapshot(
        _ rawMessages: [MobileSessionMessage],
        preserveVisibleTranscript: Bool,
        clearStreamingText: Bool,
        clearToolCalls: Bool
    ) {
        let incomingHighestSequence = rawMessages.map(\.seqNo).max()
        guard ChatTranscriptPolicy.shouldApplyRemoteSnapshot(
            highestAppliedSequence: highestAppliedRemoteSeqNo,
            incomingHighestSequence: incomingHighestSequence
        ) else { return }
        let remoteMessages = ChatMessage.normalize(rawMessages)
        let pendingText = takeStreamingBuffer()
        reconcileRemoteMessages(
            remoteMessages,
            preserveVisibleTranscript: preserveVisibleTranscript
        )
        realtimeState.applyRemoteRefresh(
            remoteMessages: remoteMessages,
            pendingText: pendingText,
            clearStreamingText: clearStreamingText,
            clearToolCalls: clearToolCalls
        )
        mergePendingInteractions(from: rawMessages)
        if let incomingHighestSequence {
            highestAppliedRemoteSeqNo = max(highestAppliedRemoteSeqNo ?? incomingHighestSequence, incomingHighestSequence)
        }
    }

    @MainActor
    private func clearOptimisticProtection() {
        optimisticMessageId = nil
        minimumExpectedRemoteMessageCount = nil
    }

    @MainActor
    private func resetTranscriptPresentationForSessionChange() {
        expandedToolCallIDs.removeAll()
        highestAppliedRemoteSeqNo = nil
        isTranscriptPinnedToBottom = true
        stabilizeStreamingTailIdentity = false
        pendingHandoffLayoutRecovery = false
        pendingAutoScrollAfterKeyboard = false
        pendingBottomScrollAfterKeyboard = false
    }

    @MainActor
    private func upsertPendingInteraction(_ interaction: PendingInteraction) {
        pendingInteractions.removeAll { $0.id == interaction.id }
        pendingInteractions.append(interaction)
        pendingInteractionErrorId = nil
        pendingInteractionError = nil
    }

    private func scrollToBottom(proxy: ScrollViewProxy, animated: Bool) {
        if animated {
            withAnimation(.snappy(duration: 0.22)) {
                proxy.scrollTo(bottomAnchorId, anchor: .bottom)
            }
        } else {
            proxy.scrollTo(bottomAnchorId, anchor: .bottom)
        }
    }

    private func requestAutoScroll(proxy: ScrollViewProxy, animated: Bool) {
        guard isTranscriptPinnedToBottom else { return }
        guard ChatScrollPolicy.shouldAutoScroll(
            isComposerFocused: composerFocused,
            isKeyboardVisible: isKeyboardVisible,
            isKeyboardSettling: isKeyboardSettling
        ) else {
            pendingAutoScrollAfterKeyboard = true
            return
        }
        pendingAutoScrollAfterKeyboard = false
        scrollToBottom(proxy: proxy, animated: animated)
    }

    @MainActor
    private func handleBottomButtonTap(proxy: ScrollViewProxy) {
        isTranscriptPinnedToBottom = true
        switch ChatScrollPolicy.bottomButtonAction(
            isComposerFocused: composerFocused,
            isKeyboardVisible: isKeyboardVisible,
            isKeyboardSettling: isKeyboardSettling
        ) {
        case .dismissKeyboardThenScroll:
            pendingBottomScrollAfterKeyboard = true
            composerFocused = false
            scheduleBottomScrollFallback()
        case .scrollImmediately:
            scrollToBottom(proxy: proxy, animated: true)
        }
    }

    @MainActor
    private func scheduleBottomScrollFallback() {
        deferredBottomScrollTask?.cancel()
        deferredBottomScrollTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: ChatScrollPolicy.keyboardDismissFallbackNanoseconds)
            guard !Task.isCancelled else { return }
            guard !isKeyboardVisible else { return }
            completePendingBottomScroll()
        }
    }

    @MainActor
    private func completePendingBottomScroll() {
        let shouldCompleteButtonScroll = pendingBottomScrollAfterKeyboard
        let shouldCompleteAutoScroll = pendingAutoScrollAfterKeyboard
        guard shouldCompleteButtonScroll || shouldCompleteAutoScroll else { return }
        pendingBottomScrollAfterKeyboard = false
        pendingAutoScrollAfterKeyboard = false
        if shouldCompleteAutoScroll, pendingHandoffLayoutRecovery {
            stabilizeStreamingTailIdentity = false
            pendingHandoffLayoutRecovery = false
        }
        deferredBottomScrollTask?.cancel()
        deferredBottomScrollTask = Task { @MainActor in
            await Task.yield()
            await Task.yield()
            guard !Task.isCancelled else { return }
            guard !isKeyboardVisible, !isKeyboardSettling else {
                pendingBottomScrollAfterKeyboard = shouldCompleteButtonScroll
                pendingAutoScrollAfterKeyboard = shouldCompleteAutoScroll
                return
            }
            deferredBottomScrollRequest += 1
            deferredBottomScrollTask = nil
        }
    }

    @MainActor
    private func startRealtime(for session: MobileSession) {
        stopRealtime()
        guard isActive, scenePhase == .active else { return }
        guard deviceToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false else { return }
        let apiClient = client
        realtimeTask = Task { @MainActor in
            await listenRealtime(sessionId: session.id, client: apiClient)
        }
    }

    @MainActor
    private func pauseRealtime() {
        flushStreamingBuffer()
        stopRealtime()
    }

    @MainActor
    private func stopRealtime() {
        realtimeTask?.cancel()
        realtimeTask = nil
        realtimeSocket?.cancel(with: .goingAway, reason: nil)
        realtimeSocket = nil
        streamingFlushTask?.cancel()
        streamingFlushTask = nil
        deferredBottomScrollTask?.cancel()
        deferredBottomScrollTask = nil
        pendingBottomScrollAfterKeyboard = false
        pendingAutoScrollAfterKeyboard = false
        pendingHandoffLayoutRecovery = false
        stabilizeStreamingTailIdentity = false
    }

    @MainActor
    private func listenRealtime(sessionId: String, client: MobileApiClient) async {
        do {
            let request = try client.chatWebSocketRequest(sessionId: sessionId)
            let socket = URLSession.shared.webSocketTask(with: request)
            realtimeSocket = socket
            socket.resume()
            defer {
                socket.cancel(with: .goingAway, reason: nil)
                if realtimeSocket === socket {
                    realtimeSocket = nil
                }
            }

            while !Task.isCancelled {
                let message = try await socket.receive()
                guard let event = try decodeRealtimeEvent(message) else { continue }
                await applyRealtimeEvent(event)
            }
        } catch {
            guard !Task.isCancelled else { return }
            runtimeStatusOverride = selectedSession?.runtimeStatus
        }
    }

    private func decodeRealtimeEvent(_ message: URLSessionWebSocketTask.Message) throws -> MobileChatEvent? {
        let data: Data
        switch message {
        case let .string(text):
            data = Data(text.utf8)
        case let .data(rawData):
            data = rawData
        @unknown default:
            return nil
        }
        return try JSONDecoder().decode(MobileChatEvent.self, from: data)
    }

    @MainActor
    private func applyRealtimeEvent(_ event: MobileChatEvent) async {
        guard event.sessionId == selectedSession?.id else { return }
        switch event.type {
        case "session_status":
            runtimeStatusOverride = event.status
            if event.status == "running" {
                isAgentRunning = true
            } else if event.status == "idle" || event.status == "error" {
                flushStreamingBuffer()
                isAgentRunning = false
                pendingInteractions.removeAll { $0.source == .realtime }
                if event.status == "error", let error = event.error, !error.isEmpty {
                    errorText = error
                }
                await reloadRemoteMessagesForRealtime(
                    sessionId: event.sessionId,
                    clearStreamingText: true,
                    clearToolCalls: true
                )
                await refreshSelectedSessionMetadata()
            }
        case "ask_user", "confirmation_required":
            if let interaction = PendingInteraction(realtimeEvent: event) {
                upsertPendingInteraction(interaction)
                isAgentRunning = false
                runtimeStatusOverride = "waiting_user"
            }
        case "text_delta", "assistant_delta":
            isAgentRunning = true
            realtimeState.beginStreaming(after: highestAppliedRemoteSeqNo)
            if let acceptedDelta = realtimeState.acceptedTextDelta(type: event.type, chunk: event.assistantTextDelta) {
                queueStreamingDelta(acceptedDelta)
            }
        case "tool_started":
            isAgentRunning = true
            upsertStreamingTool(
                id: event.toolUseId,
                name: event.resolvedToolName,
                inputPreview: event.input?.previewText,
                output: nil,
                status: .pending
            )
        case "tool_use_delta":
            isAgentRunning = true
            upsertStreamingTool(
                id: event.toolUseId,
                name: event.resolvedToolName,
                inputPreview: event.jsonFragment,
                output: nil,
                status: .pending
            )
        case "tool_use_complete":
            upsertStreamingTool(
                id: event.toolUseId,
                name: event.resolvedToolName,
                inputPreview: event.input?.previewText,
                output: nil,
                status: .pending
            )
        case "tool_finished":
            upsertStreamingTool(
                id: event.toolUseId,
                name: event.resolvedToolName,
                inputPreview: nil,
                output: event.error,
                status: ChatMessage.ToolCall.Status(raw: event.status)
            )
        case "assistant_stream_end":
            flushStreamingBuffer()
            realtimeState.finishStreamSegment()
        case "message_appended":
            flushStreamingBuffer()
            let appendedRole = event.message?.role?.lowercased()
            await reloadRemoteMessagesForRealtime(
                sessionId: event.sessionId,
                clearStreamingText: appendedRole != "user",
                clearToolCalls: true
            )
        case "messages_snapshot":
            flushStreamingBuffer()
            await reloadRemoteMessagesForRealtime(
                sessionId: event.sessionId,
                clearStreamingText: true,
                clearToolCalls: true
            )
        default:
            break
        }
    }

    @MainActor
    private func queueStreamingDelta(_ delta: String?) {
        guard let delta, !delta.isEmpty else { return }
        streamingBuffer += delta
        if streamingFlushTask != nil { return }
        streamingFlushTask = Task { @MainActor in
            do {
                try await Task.sleep(nanoseconds: 120_000_000)
            } catch {
                return
            }
            guard !Task.isCancelled else { return }
            flushStreamingBuffer()
        }
    }

    @MainActor
    private func flushStreamingBuffer() {
        realtimeState.appendBufferedText(takeStreamingBuffer())
    }

    @MainActor
    private func takeStreamingBuffer() -> String {
        let pendingText = streamingBuffer
        streamingBuffer = ""
        streamingFlushTask?.cancel()
        streamingFlushTask = nil
        return pendingText
    }

    @MainActor
    private func upsertStreamingTool(
        id: String?,
        name: String?,
        inputPreview: String?,
        output: String?,
        status: ChatMessage.ToolCall.Status
    ) {
        realtimeState.upsertTool(
            id: id,
            name: name,
            inputPreview: inputPreview,
            output: output,
            status: status
        )
    }

    @MainActor
    private func reloadRemoteMessagesForRealtime(
        sessionId: String,
        clearStreamingText: Bool,
        clearToolCalls: Bool
    ) async {
        do {
            let rawMessages = try await client.getMessages(sessionId: sessionId)
            guard !Task.isCancelled, selectedSession?.id == sessionId else { return }
            applyRemoteSnapshot(
                rawMessages,
                preserveVisibleTranscript: shouldPreserveVisibleTranscriptDuringRefresh,
                clearStreamingText: clearStreamingText,
                clearToolCalls: clearToolCalls
            )
        } catch {
            guard !Task.isCancelled, selectedSession?.id == sessionId else { return }
            handle(error)
        }
    }

    @MainActor
    private func resetStreamingState() {
        realtimeState.reset()
        streamingBuffer = ""
        streamingFlushTask?.cancel()
        streamingFlushTask = nil
    }

    private func visibleSessions(from loaded: [MobileSession]) -> [MobileSession] {
        guard let defaultAgentId = defaultAgent?.id else {
            return loaded
        }
        return loaded.filter { $0.agentId == defaultAgentId }
    }

    #if DEBUG
    private func handoffCheckpointMarker(_ checkpoint: String) -> some View {
        Color.clear
            .frame(width: 1, height: 1)
            .accessibilityElement()
            .accessibilityIdentifier("chat.streamingHandoff.\(checkpoint)")
    }

    @MainActor
    private func waitForDeterministicCheckpointAcknowledgement(_ checkpoint: String) async {
        deterministicHandoffCheckpoint = checkpoint
        while deterministicCheckpointAcknowledgement != checkpoint, !Task.isCancelled {
            try? await Task.sleep(for: .milliseconds(50))
        }
        deterministicCheckpointAcknowledgement = nil
        deterministicHandoffCheckpoint = nil
    }

    @MainActor
    private func runDeterministicStreamingHandoffIfNeeded() async {
        guard usesDeterministicFixture,
              selectedSession?.id == "ui-test-streaming-handoff-session"
        else { return }

        try? await Task.sleep(for: .seconds(3))
        for cycle in 1...5 {
            guard !Task.isCancelled else { return }
            isAgentRunning = true
            runtimeStatusOverride = "running"
            let heading = "## Streaming handoff cycle \(cycle)\n\n"
            let paragraph = "Rendered streaming Markdown changes height while the final row remains pinned. "
            let remoteSequence = Int64(100 + cycle)
            realtimeState.beginStreaming(after: remoteSequence - 1)
            var streamedText = heading
            realtimeState.appendBufferedText(heading)
            for chunk in 1...18 {
                let delta = "\n\n\(paragraph)Chunk \(chunk)."
                streamedText += delta
                realtimeState.appendBufferedText(delta)
                if chunk == 7 {
                    realtimeState.upsertTool(
                        id: "fixture-tool-\(cycle)",
                        name: "PublishChatArtifact",
                        inputPreview: "{ \"cycle\": \(cycle) }",
                        output: nil,
                        status: .pending
                    )
                } else if chunk == 13 {
                    realtimeState.upsertTool(
                        id: "fixture-tool-\(cycle)",
                        name: "PublishChatArtifact",
                        inputPreview: nil,
                        output: "Fixture tool completed",
                        status: .success
                    )
                }
                if cycle == 1, chunk == 5 {
                    await waitForDeterministicCheckpointAcknowledgement("streaming")
                } else if cycle == 1, chunk == 14 {
                    await waitForDeterministicCheckpointAcknowledgement("tool-complete")
                }
                try? await Task.sleep(for: .milliseconds(35))
            }

            let persistedMessage = ChatMessage(
                id: "remote-streaming-final-\(cycle)",
                role: .assistant,
                text: streamedText,
                toolCalls: [ChatMessage.ToolCall(
                    id: "fixture-tool-\(cycle)",
                    name: "PublishChatArtifact",
                    inputPreview: "{ \"cycle\": \(cycle) }",
                    output: "Fixture tool completed",
                    status: .success
                )],
                remoteSeqNo: remoteSequence
            )
            messages.append(persistedMessage)
            realtimeState.applyRemoteRefresh(
                remoteMessages: [persistedMessage],
                clearStreamingText: true,
                clearToolCalls: true
            )
            if cycle == 1 {
                await waitForDeterministicCheckpointAcknowledgement("persisted")
            }
            try? await Task.sleep(for: .milliseconds(180))
        }
        deterministicHandoffCompleted = true
    }
    #endif
}

extension Notification.Name {
    static let skillForgeChatRootDisconnect = Notification.Name("SkillForge.ChatRootDisconnect")
}

struct ChatMessage: Identifiable, Equatable {
    enum Role {
        case user
        case assistant
    }

    struct ToolCall: Identifiable, Equatable {
        enum Status: Equatable {
            case pending
            case success
            case error

            init(raw: String?) {
                switch raw?.lowercased() {
                case "success", "succeeded", "ok", "completed":
                    self = .success
                case "error", "failed", "failure":
                    self = .error
                default:
                    self = .pending
                }
            }
        }

        let id: String
        var name: String
        var inputPreview: String
        var output: String?
        var status: Status
    }

    let id: String
    let role: Role
    let text: String
    var toolCalls: [ToolCall]
    let attachments: [ChatAttachment]
    let reasoningContent: String?
    let isStreaming: Bool
    let remoteSeqNo: Int64?

    init(
        id: String = UUID().uuidString,
        role: Role,
        text: String,
        toolCalls: [ToolCall] = [],
        attachments: [ChatAttachment] = [],
        reasoningContent: String? = nil,
        isStreaming: Bool = false,
        remoteSeqNo: Int64? = nil
    ) {
        self.id = id
        self.role = role
        self.text = text
        self.toolCalls = toolCalls
        self.attachments = attachments
        self.reasoningContent = reasoningContent
        self.isStreaming = isStreaming
        self.remoteSeqNo = remoteSeqNo
    }

    init?(message: MobileSessionMessage) {
        guard let normalized = Self.normalize([message]).first else { return nil }
        self = normalized
    }

    static func normalize(_ messages: [MobileSessionMessage]) -> [ChatMessage] {
        var result: [ChatMessage] = []

        for message in messages {
            let role = message.role.lowercased()
            guard role != "system" else { continue }

            let msgType = message.msgType?.uppercased() ?? ""
            if ["COMPACT_BOUNDARY", "RECOVERY_PAYLOAD", "SUMMARY"].contains(msgType) {
                continue
            }
            let messageType = message.messageType?.lowercased() ?? ""
            if ["ask_user", "confirmation"].contains(messageType) {
                continue
            }

            let blocks = message.contentBlocks.isEmpty ? [.text(message.displayText)] : message.contentBlocks
            let textBlocks = blocks.filter { $0.type == "text" }
            let toolUseBlocks = blocks.filter { $0.type == "tool_use" }
            let toolResultBlocks = blocks.filter { $0.type == "tool_result" }
            let attachments = blocks.compactMap(\.attachment)
            var text = Self.visibleText(from: textBlocks.compactMap(\.text).joined(separator: "\n"))
            if text == "[NORMAL]" {
                text = ""
            }

            if role == "user" {
                if !toolResultBlocks.isEmpty, let lastIndex = result.indices.last {
                    var previous = result[lastIndex]
                    if previous.role == .assistant {
                        for block in toolResultBlocks {
                            let toolId = block.toolUseId ?? block.id ?? "tool-\(message.seqNo)"
                            let output = block.content?.displayText ?? block.text ?? ""
                            let status: ToolCall.Status = block.isError ? .error : .success
                            if let index = previous.toolCalls.firstIndex(where: { $0.id == toolId }) {
                                previous.toolCalls[index].output = output
                                previous.toolCalls[index].status = status
                            } else {
                                previous.toolCalls.append(ToolCall(
                                    id: toolId,
                                    name: "tool",
                                    inputPreview: "",
                                    output: output,
                                    status: status
                                ))
                            }
                        }
                        result[lastIndex] = previous
                    }
                }
                guard !text.isEmpty || !attachments.isEmpty else { continue }
                result.append(ChatMessage(
                    id: "remote-\(message.seqNo)",
                    role: .user,
                    text: text,
                    attachments: attachments,
                    remoteSeqNo: message.seqNo
                ))
                continue
            }

            let toolCalls = toolUseBlocks.enumerated().map { index, block in
                ToolCall(
                    id: block.id ?? block.toolUseId ?? "tool-\(message.seqNo)-\(index)",
                    name: block.name ?? "tool",
                    inputPreview: block.input?.previewText ?? "",
                    output: nil,
                    status: .pending
                )
            }
            let reasoning = Self.visibleText(from: message.reasoningContent ?? "")
            guard !text.isEmpty || !toolCalls.isEmpty || !reasoning.isEmpty || !attachments.isEmpty else { continue }
            result.append(ChatMessage(
                id: "remote-\(message.seqNo)",
                role: .assistant,
                text: text,
                toolCalls: toolCalls,
                attachments: attachments,
                reasoningContent: reasoning.isEmpty ? nil : reasoning,
                remoteSeqNo: message.seqNo
            ))
        }

        return result
    }

    private static func visibleText(from rawText: String) -> String {
        rawText
            .replacingOccurrences(
                of: "(?is)<system-reminder>.*?</system-reminder>",
                with: "",
                options: .regularExpression
            )
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
