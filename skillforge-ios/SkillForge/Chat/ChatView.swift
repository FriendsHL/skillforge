import Combine
import SwiftUI
import UIKit
import UniformTypeIdentifiers

private enum RuntimeMetadataCommitAuthority {
    case realtime
    case asyncResponse(RuntimeMetadataAuthorityToken)
    case localTransition
}

private struct DeferredAuthoritativeBottomScroll: Equatable {
    let id: UInt64
    let sessionID: String
}

private struct TranscriptBottomPreferenceKey: PreferenceKey {
    static let defaultValue: CGFloat = .greatestFiniteMagnitude
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
    }
}

private struct TranscriptViewportHeightPreferenceKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
    }
}

private struct AssistantActivityView: View {
    let presentation: AssistantActivityPresentation
    let reduceMotion: Bool
    @State private var isActive = false

    var body: some View {
        HStack(spacing: 10) {
            HStack(spacing: 4) {
                ForEach(0..<3, id: \.self) { index in
                    Circle()
                        .frame(width: 6, height: 6)
                        .scaleEffect(reduceMotion || !isActive ? 0.78 : 1)
                        .opacity(reduceMotion ? 0.72 : (isActive ? 1 : 0.35))
                        .animation(
                            reduceMotion ? nil : .easeInOut(duration: 0.62)
                                .repeatForever(autoreverses: true)
                                .delay(Double(index) * 0.12),
                            value: isActive
                        )
                }
            }
            Text(presentation.accessibilityLabel)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 10)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(presentation.accessibilityLabel)
        .accessibilityIdentifier("chat.assistantActivity")
        .onAppear { isActive = true }
    }
}

struct ChatView: View {
    @EnvironmentObject private var appState: AppState
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.accessibilityReduceMotion) private var accessibilityReduceMotion

    let endpoint: URL
    let deviceToken: String
    let device: MobileDeviceSummary?
    let defaultAgent: MobileAgentSummary?
    let availableAgents: [MobileAgentCatalogItem]
    private let usesDeterministicFixture: Bool
    private let isActive: Bool
    private let route: ChatRoute?
    private let newConversationRoute: NewConversationRoute?
    private let rootCleanupToken: Int
    private let onRouteHandled: (UUID) -> Void
    private let onNewConversationRouteHandled: (UUID) -> Void
    private let onDisconnectRequested: (() -> Void)?
    private let onChatAgentSelected: (MobileAgentCatalogItem) -> Void
    private let onNewConversationAgentSelected: (MobileAgentCatalogItem) -> Void
    private let fixtureSessions: [MobileSession]

    @State private var sessionSheetOpen = false
    @State private var newConversationOpen = false
    @State private var requestedNewConversationAgentID: Int64?
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
    @State private var isRetryingRuntime = false
    @State private var errorText: String?
    @State private var runtimeStatusOverride: String?
    @State private var composerText = ""
    @State private var operationTask: Task<Void, Never>?
    @State private var activationTask: Task<Void, Never>?
    @State private var sendTask: Task<Void, Never>?
    @State private var sendOperationId: UUID?
    @State private var runtimeRetryTask: Task<Void, Never>?
    @State private var runtimeRetryOperationId: UUID?
    @State private var optimisticMessageId: String?
    @State private var minimumExpectedRemoteMessageCount: Int?
    @State private var highestAppliedRemoteSeqNo: Int64?
    @State private var realtimeTask: Task<Void, Never>?
    @State private var realtimeSocket: URLSessionWebSocketTask?
    @State private var terminalMetadataCatchUpTask: Task<Void, Never>?
    @State private var terminalMetadataCatchUpOperationId: UUID?
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
    @State private var deferredAuthoritativeBottomScroll: DeferredAuthoritativeBottomScroll?
    @State private var nextDeferredAuthoritativeBottomScrollID: UInt64 = 0
    @State private var deferredBottomScrollTask: Task<Void, Never>?
    @State private var bottomButtonKeyboardFallbackTask: Task<Void, Never>?
    @State private var pendingRoute: ChatRoute?
    @State private var pendingSourceMessageSeq: Int64?
    @State private var sourceRouteNotice: String?
    @State private var expandedToolCallIDs: Set<String> = []
    @State private var isTranscriptPinnedToBottom = true
    @State private var scrollFollowState = ChatScrollFollowState.initial
    @State private var awaitingAssistantActivity = false
    @State private var assistantTurnSequence = 0
    @State private var transcriptViewportHeight: CGFloat = 0
    @State private var acceptedAgentSelectionID: Int64?
    @State private var fixtureSessionSequence = 0
    @State private var sessionStateGeneration = 0
    @State private var runtimeMetadataAuthority: RuntimeMetadataAuthorityGate
    #if DEBUG
    @State private var deterministicHandoffCompleted = false
    @State private var deterministicHandoffCheckpoint: String?
    @State private var deterministicCheckpointAcknowledgement: String?
    @State private var deterministicBottomButtonTapCount = 0
    #endif
    @StateObject private var attachmentStore: AttachmentDownloadStore
    @StateObject private var bottomScrollCoordinator: ChatBottomScrollCoordinator
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
        newConversationRoute: NewConversationRoute? = nil,
        rootCleanupToken: Int = 0,
        onRouteHandled: @escaping (UUID) -> Void = { _ in },
        onNewConversationRouteHandled: @escaping (UUID) -> Void = { _ in },
        onDisconnectRequested: (() -> Void)? = nil,
        onChatAgentSelected: @escaping (MobileAgentCatalogItem) -> Void = { _ in },
        onNewConversationAgentSelected: @escaping (MobileAgentCatalogItem) -> Void = { _ in },
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
        self.newConversationRoute = newConversationRoute
        self.rootCleanupToken = rootCleanupToken
        self.onRouteHandled = onRouteHandled
        self.onNewConversationRouteHandled = onNewConversationRouteHandled
        self.onDisconnectRequested = onDisconnectRequested
        self.onChatAgentSelected = onChatAgentSelected
        self.onNewConversationAgentSelected = onNewConversationAgentSelected
        let seededSessions = initialSessions.isEmpty
            ? initialSession.map { [$0] } ?? []
            : initialSessions
        let seededSelection = initialSession ?? seededSessions.first
        fixtureSessions = seededSessions
        _didLoad = State(initialValue: seededSelection != nil)
        _sessions = State(initialValue: seededSessions)
        _selectedSession = State(initialValue: seededSelection)
        _runtimeMetadataAuthority = State(
            initialValue: RuntimeMetadataAuthorityGate(sessionId: seededSelection?.id)
        )
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
        _bottomScrollCoordinator = StateObject(
            wrappedValue: ChatBottomScrollCoordinator(sessionID: seededSelection?.id)
        )
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
                if let sourceRouteNotice {
                    Label(sourceRouteNotice, systemImage: "arrow.turn.down.right")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal)
                        .padding(.vertical, 8)
                        .accessibilityIdentifier("chat.sourceRouteNotice")
                }
                chatScroller
                ComposerView(
                    text: $composerText,
                    isSending: isSending || isRetryingRuntime || selectedSession == nil || !pendingInteractions.isEmpty,
                    isUploading: isUploadingAttachment,
                    attachments: uploadedAttachments,
                    assistantName: assistantName,
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
            .background(Color(uiColor: .systemGroupedBackground))
            .toolbar(.hidden, for: .navigationBar)
            #if DEBUG
            .overlay(alignment: .topLeading) {
                VStack(spacing: 0) {
                    if DebugLaunchConfiguration.isChatUITest {
                        Group {
                            Text("Bottom button taps")
                                .accessibilityIdentifier("chat.bottomButtonTapCount")
                                .accessibilityValue(String(deterministicBottomButtonTapCount))
                            Text("Bottom scroll completions")
                                .accessibilityIdentifier("chat.bottomScrollCompletionCount")
                                .accessibilityValue(String(bottomScrollCoordinator.completedRequestID))
                        }
                        .font(.caption2)
                        .frame(width: 1, height: 1)
                        .opacity(0.01)
                        .allowsHitTesting(false)
                    }
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
                        requestedNewConversationAgentID = nil
                        Task { @MainActor in
                            await Task.yield()
                            newConversationOpen = true
                        }
                    },
                    onRefresh: refreshSessionList
                )
            }
            .sheet(isPresented: $newConversationOpen, onDismiss: {
                requestedNewConversationAgentID = nil
            }) {
                NewConversationView(
                    agents: availableAgents,
                    currentAgentID: requestedNewConversationAgentID ?? defaultAgent?.id,
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
            .onChange(of: selectedSession?.id) { _, sessionID in
                bottomScrollCoordinator.activate(sessionID: sessionID)
            }
            .onChange(of: endpoint) { oldEndpoint, newEndpoint in
                guard oldEndpoint != newEndpoint, !usesDeterministicFixture else { return }
                resumeAfterEndpointChange()
            }
            .onChange(of: route) { _, newRoute in
                guard let newRoute else { return }
                receive(newRoute)
            }
            .onChange(of: newConversationRoute) { _, newRoute in
                guard let newRoute else { return }
                requestedNewConversationAgentID = newRoute.agentID
                newConversationOpen = true
                onNewConversationRouteHandled(newRoute.id)
            }
            .onChange(of: rootCleanupToken) { _, _ in
                performFullCleanup()
            }
            .onChange(of: scenePhase) { _, phase in
                guard !usesDeterministicFixture else { return }
                if phase == .active, isActive {
                    resumeAfterInactivity()
                } else {
                    if ChatScrollPolicy.shouldCancelPendingBottomRequest(
                        isSceneActive: phase == .active
                    ) {
                        cancelBottomButtonScroll()
                    }
                    pauseRealtime()
                }
            }
            .onDisappear {
                cancelBottomButtonScroll()
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

    private var selectedSessionSourceLabel: String? {
        PersonalAppSourceLabelPolicy.resolve(
            sessionAgentID: selectedSession?.agentId,
            availableAgents: availableAgents,
            defaultAgent: defaultAgent
        )
    }

    private var selectedSessionTitle: String {
        if let title = selectedSession?.title, !title.isEmpty {
            return title
        }
        return assistantName
    }

    private var headerPresentation: ChatHeaderPresentation {
        ChatHeaderPresentationPolicy.resolve(
            sessionTitle: selectedSession?.title,
            fallbackTitle: assistantName,
            agentName: selectedSessionSourceLabel ?? assistantName,
            status: statusPresentation
        )
    }

    private var renderedMessages: [ChatMessage] {
        var list = messages
        if hasStreamingMessage || assistantActivity != .hidden {
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

    private var assistantActivity: AssistantActivityPresentation {
        AssistantActivityPresentationPolicy.resolve(
            sendAccepted: awaitingAssistantActivity,
            isRuntimeRunning: isAgentRunning || runtimeStatusOverride == "running",
            hasText: !realtimeState.streamingText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
            hasPendingTool: realtimeState.streamingToolCalls.contains { $0.status == .pending },
            isWaitingForUser: runtimeStatusOverride == "waiting_user" || !pendingInteractions.isEmpty
        )
    }

    private var activeAssistantTurnID: String {
        "\(selectedSession?.id ?? "unselected"):\(assistantTurnSequence)"
    }

    private var renderedRows: [ChatTranscriptRow] {
        ChatTranscriptRow.rows(
            for: renderedMessages,
            stabilizeStreamingTail: stabilizeStreamingTailIdentity
        )
    }

    private var bottomScrollTargetID: String? {
        ChatTranscriptPolicy.bottomScrollTargetID(
            messageRowIDs: renderedRows.map(\.id),
            pendingInteractionIDs: pendingInteractions.map(\.id)
        )
    }

    private var chatScroller: some View {
        ScrollViewReader { proxy in
            ZStack(alignment: .bottomTrailing) {
                ScrollView {
                    // Long Markdown and Tool cards have highly variable heights. A lazy
                    // stack estimates off-screen rows and changes the scroll view's
                    // content size as they materialize, which makes the indicator jump
                    // and can position a bottom request inside estimated blank space.
                    VStack(spacing: 20) {
                        if isLoading && renderedMessages.isEmpty {
                            ProgressView("Loading chat")
                                .padding(.top, 80)
                        } else if renderedMessages.isEmpty {
                            ContentUnavailableView("No messages", systemImage: "bubble.left.and.bubble.right")
                                .padding(.top, 80)
                        } else {
                            ForEach(renderedRows) { row in
                                if row.message.isStreaming,
                                   row.message.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                                   assistantActivity != .hidden {
                                    AssistantActivityView(
                                        presentation: assistantActivity,
                                        reduceMotion: accessibilityReduceMotion
                                    )
                                    .id(row.id)
                                } else {
                                    MessageBubbleView(
                                    message: row.message,
                                    sessionID: selectedSession?.id ?? "",
                                    sourceLabel: selectedSessionSourceLabel,
                                    sourceAgentID: selectedSession?.agentId,
                                    sourceSessionTitle: selectedSession?.title,
                                    attachmentStore: attachmentStore,
                                    expandedToolCallIDs: $expandedToolCallIDs,
                                    onUnauthorized: disconnectForUnauthorizedAttachment,
                                    onSubmitArtifactSnapshot: { snapshotMessage in
                                        startOperation {
                                            await send(
                                                snapshotMessage,
                                                attachments: [],
                                                submittedDraft: snapshotMessage
                                            )
                                        }
                                    }
                                    )
                                }
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
                            .id(ChatTranscriptPolicy.pendingInteractionTargetPrefix + interaction.id)
                        }
                        Color.clear
                            .frame(height: 1)
                            .background {
                                GeometryReader { geometry in
                                    Color.clear.preference(
                                        key: TranscriptBottomPreferenceKey.self,
                                        value: geometry.frame(in: .named("chat.transcript.viewport")).maxY
                                    )
                                }
                            }
                    }
                    .id(ChatTranscriptPolicy.containerIdentity(sessionID: selectedSession?.id))
                    .background {
                        ChatBottomScrollResolver(coordinator: bottomScrollCoordinator)
                            .allowsHitTesting(false)
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 12)
                    .padding(.bottom, 16)
                    .frame(maxWidth: .infinity)
                }
                .accessibilityIdentifier("chat.transcript")
                .accessibilityValue(selectedSession?.id ?? "")
                .coordinateSpace(name: "chat.transcript.viewport")
                .background {
                    GeometryReader { geometry in
                        Color.clear.preference(
                            key: TranscriptViewportHeightPreferenceKey.self,
                            value: geometry.size.height
                        )
                    }
                }
                .contentShape(Rectangle())
                .scrollDismissesKeyboard(.interactively)
                .simultaneousGesture(TapGesture().onEnded {
                    composerFocused = false
                })
                .simultaneousGesture(DragGesture(minimumDistance: 8).onChanged { value in
                    updateScrollFollow(.userDragged(verticalTranslation: value.translation.height))
                })
                .onPreferenceChange(TranscriptBottomPreferenceKey.self) { bottomY in
                    guard bottomY.isFinite, transcriptViewportHeight > 0 else { return }
                    updateScrollFollow(
                        .geometryChanged(bottomDistance: max(0, bottomY - transcriptViewportHeight))
                    )
                }
                .onPreferenceChange(TranscriptViewportHeightPreferenceKey.self) { height in
                    transcriptViewportHeight = height
                }

                if !renderedRows.isEmpty {
                    Button {
                        handleBottomButtonTap(proxy: proxy)
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: "arrow.down")
                            if scrollFollowState.unreadTurnCount > 0 {
                                Text("\(scrollFollowState.unreadTurnCount)")
                                    .font(.caption.bold().monospacedDigit())
                            }
                        }
                            .font(.callout.weight(.bold))
                            .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                            .foregroundStyle(.white)
                            .frame(minWidth: 44, minHeight: 44)
                            .padding(.horizontal, scrollFollowState.unreadTurnCount > 0 ? 10 : 0)
                            .background(Color(red: 0.10, green: 0.12, blue: 0.16))
                            .clipShape(Capsule())
                            .shadow(color: .black.opacity(0.18), radius: 12, x: 0, y: 5)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(ChatScrollFollowPolicy.bottomButtonLabel(for: scrollFollowState))
                    .accessibilityIdentifier("chat.scrollToBottom")
                    .padding(.trailing, 16)
                    .padding(.bottom, 14)
                }
            }
            .onChange(of: messages.count) { _, _ in
                if resolvePendingSourceRoute(proxy: proxy) { return }
                requestAutoScroll(proxy: proxy, animated: true)
            }
            .onChange(of: pendingSourceMessageSeq) { _, _ in
                _ = resolvePendingSourceRoute(proxy: proxy)
            }
            .onChange(of: realtimeState.streamingText) { _, _ in
                guard isAgentRunning else { return }
                awaitingAssistantActivity = false
                updateScrollFollow(.assistantContent(turnID: activeAssistantTurnID))
                if isTranscriptPinnedToBottom, isKeyboardVisible, !isKeyboardSettling {
                    scrollToBottom(proxy: proxy, animated: false)
                } else {
                    requestAutoScroll(proxy: proxy, animated: false)
                }
            }
            .onChange(of: realtimeState.streamingToolCalls.count) { _, _ in
                updateScrollFollow(.assistantContent(turnID: activeAssistantTurnID))
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
            .onChange(of: deferredAuthoritativeBottomScroll) { _, request in
                guard let request else { return }
                guard ChatScrollPolicy.shouldConsumeDeferredBottomScroll(
                    requestSessionID: request.sessionID,
                    activeSessionID: selectedSession?.id,
                    isChatActive: isActive,
                    isSceneActive: usesDeterministicFixture || scenePhase == .active
                ) else { return }
                requestAuthoritativeBottomScroll(
                    proxy: proxy,
                    sessionID: request.sessionID
                )
            }
        }
    }

    private var chatHeader: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Button {
                    sessionSheetOpen = true
                } label: {
                    Image(systemName: "sidebar.left")
                        .font(.title3.weight(.semibold))
                        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                        .frame(width: 44, height: 44)
                        .background(Color(uiColor: .secondarySystemBackground))
                        .clipShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Sessions")

                VStack(spacing: 2) {
                    Text(headerPresentation.title)
                        .font(.headline.weight(.bold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.72)
                    HStack(spacing: 5) {
                        Text(headerPresentation.agentName)
                            .lineLimit(1)
                        Text("·")
                            .foregroundStyle(.tertiary)
                        Circle()
                            .fill(headerPresentation.semantic.color)
                            .frame(width: 7, height: 7)
                            .accessibilityHidden(true)
                        Text(headerPresentation.statusTitle)
                            .lineLimit(1)
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("当前对话")
                .accessibilityValue(headerPresentation.accessibilityValue)
                .accessibilityIdentifier("chat.header.agent")

                Button {
                    requestedNewConversationAgentID = nil
                    newConversationOpen = true
                } label: {
                    Image(systemName: "plus")
                        .font(.title3.weight(.semibold))
                        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                        .frame(width: 44, height: 44)
                        .background(Color(uiColor: .secondarySystemBackground))
                        .clipShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("New conversation")
                .accessibilityIdentifier("chat.newConversation")
            }
            .padding(.horizontal, 18)
            .padding(.top, 10)
            .padding(.bottom, 7)

            if usesExpandedRuntimePanel {
                expandedRuntimePanel
            }
        }
        .dynamicTypeSize(...DynamicTypeSize.accessibility1)
        .background(.ultraThinMaterial)
        .overlay(alignment: .bottom) {
            Rectangle()
                .fill(Color.primary.opacity(0.06))
                .frame(height: 1)
        }
    }

    private var expandedRuntimePanel: some View {
        VStack(alignment: .leading, spacing: 7) {
            runtimeStatusHeader

            if let detail = statusPresentation.detail {
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(statusPresentation.semantic.color)
                    .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 2)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityLabel(detail)
                    .accessibilityIdentifier("chat.runtimeError")
            }
        }
        .padding(.horizontal, 11)
        .padding(.vertical, 9)
        .background(statusPresentation.semantic.color.opacity(0.12))
        .overlay {
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .stroke(statusPresentation.semantic.color.opacity(0.25), lineWidth: 1)
        }
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        .padding(.horizontal, 18)
        .padding(.bottom, 10)
    }

    private var usesExpandedRuntimePanel: Bool {
        isRetryingRuntime
            || statusPresentation.semantic == .error
            || statusPresentation.detail != nil
            || statusPresentation.canRetry
    }

    @ViewBuilder
    private var runtimeStatusHeader: some View {
        if dynamicTypeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 8) {
                runtimeStatusIdentity
                    .frame(maxWidth: .infinity, alignment: .leading)
                runtimeRetryControl
                runtimeEndpointLabel
            }
        } else {
            HStack(spacing: 9) {
                runtimeStatusIdentity
                Spacer(minLength: 8)
                runtimeRetryControl
                runtimeEndpointLabel
            }
        }
    }

    private var runtimeStatusIdentity: some View {
        HStack(spacing: 8) {
            Image(systemName: statusPresentation.symbolName)
                .font(.caption.weight(.bold))
                .foregroundStyle(statusPresentation.semantic.color)
            Text(statusPresentation.title)
                .font(.footnote.weight(.semibold))
                .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                .fixedSize(horizontal: false, vertical: dynamicTypeSize.isAccessibilitySize)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(statusPresentation.title)
        .accessibilityValue(statusPresentation.semantic.rawValue)
        .accessibilityIdentifier("chat.runtimeStatus")
    }

    @ViewBuilder
    private var runtimeRetryControl: some View {
        if isRetryingRuntime {
            HStack(spacing: 5) {
                ProgressView()
                    .controlSize(.small)
                Text("重试中")
                    .font(.caption.weight(.semibold))
            }
            .frame(
                maxWidth: dynamicTypeSize.isAccessibilitySize ? CGFloat.infinity : nil,
                minHeight: 44,
                alignment: .leading
            )
            .foregroundStyle(statusPresentation.semantic.color)
            .accessibilityElement(children: .combine)
            .accessibilityLabel("正在重试失败的任务")
            .accessibilityIdentifier("chat.runtimeRetry.progress")
        } else if statusPresentation.canRetry {
            Button(action: startRuntimeRetry) {
                Label("重试", systemImage: "arrow.clockwise")
                    .font(.caption.weight(.bold))
                    .padding(.horizontal, 9)
                    .frame(
                        maxWidth: dynamicTypeSize.isAccessibilitySize ? CGFloat.infinity : nil,
                        minHeight: 44
                    )
            }
            .buttonStyle(.plain)
            .foregroundStyle(statusPresentation.semantic.color)
            .background(statusPresentation.semantic.color.opacity(0.12))
            .clipShape(Capsule())
            .accessibilityLabel("重试失败的任务")
            .accessibilityHint("重试上一次失败的任务，不会重复发送用户消息")
            .accessibilityIdentifier("chat.runtimeRetry")
        }
    }

    private var runtimeEndpointLabel: some View {
        Text(endpoint.host() ?? endpoint.absoluteString)
            .font(.caption)
            .foregroundStyle(.secondary)
            .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
            .minimumScaleFactor(dynamicTypeSize.isAccessibilitySize ? 1 : 0.75)
            .fixedSize(horizontal: false, vertical: dynamicTypeSize.isAccessibilitySize)
            .frame(
                maxWidth: dynamicTypeSize.isAccessibilitySize ? CGFloat.infinity : nil,
                alignment: .leading
            )
            .accessibilityIdentifier("chat.runtimeEndpoint")
    }

    private var statusPresentation: ChatStatusPresentation {
        ChatRuntimePresentationPolicy.resolve(
            isAgentRunning: isAgentRunning || isSending || runtimeStatusOverride == "running" || selectedSession?.runtimeStatus == "running",
            isRetrying: isRetryingRuntime,
            isRefreshing: isRefreshing,
            runtimeStatus: runtimeStatusOverride ?? selectedSession?.runtimeStatus,
            runtimeStep: selectedSession?.runtimeStep,
            runtimeError: selectedSession?.runtimeError,
            failureSource: selectedSession?.failureSource,
            failureCode: selectedSession?.failureCode,
            retryable: selectedSession?.retryable,
            sideEffects: selectedSession?.sideEffects,
            hasConnectionError: errorText != nil
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
        cancelBottomButtonScroll()
        cancelRuntimeRetry()
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
        cancelBottomButtonScroll()
        cancelRuntimeRetry()
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
        if selectedSession?.id == newRoute.session.id {
            _ = commitSelectedSessionMetadata(
                newRoute.session,
                authority: .localTransition
            )
        } else {
            cancelRuntimeRetry()
            resetTranscriptPresentationForSessionChange()
            runtimeMetadataAuthority.reset(sessionId: newRoute.session.id)
            selectedSession = newRoute.session
            runtimeStatusOverride = newRoute.session.runtimeStatus
            isAgentRunning = newRoute.session.runtimeStatus == "running"
        }
        activateSourceRoute(newRoute.sourceMessageSeq)
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
        activateSourceRoute(pendingRoute.sourceMessageSeq)
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
        guard !isRetryingRuntime else { return }
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
    private func startRuntimeRetry() {
        guard runtimeRetryTask == nil,
              statusPresentation.canRetry,
              let session = selectedSession
        else { return }

        let operationId = UUID()
        runtimeRetryOperationId = operationId
        isRetryingRuntime = true
        errorText = nil
        runtimeRetryTask = Task { @MainActor in
            await retryFailedTurn(sessionId: session.id)
            finishRuntimeRetry(operationId: operationId)
        }
    }

    @MainActor
    private func cancelRuntimeRetry() {
        runtimeRetryOperationId = nil
        runtimeRetryTask?.cancel()
        runtimeRetryTask = nil
        isRetryingRuntime = false
    }

    @MainActor
    private func finishRuntimeRetry(operationId: UUID) {
        guard runtimeRetryOperationId == operationId else { return }
        runtimeRetryOperationId = nil
        runtimeRetryTask = nil
        isRetryingRuntime = false
    }

    @MainActor
    private func retryFailedTurn(sessionId: String) async {
        if usesDeterministicFixture {
            await runDeterministicRuntimeRetry(sessionId: sessionId)
            return
        }
        guard let authorityToken = runtimeMetadataAuthority.begin(
            .retryAcceptance,
            sessionId: sessionId
        ) else { return }

        do {
            _ = try await client.retrySession(sessionId: sessionId)
            guard !Task.isCancelled,
                  let current = selectedSession,
                  current.id == sessionId
            else { return }
            let running = replacingRuntimeState(
                of: current,
                runtimeStatus: "running",
                runtimeStep: nil,
                runtimeError: nil,
                retryable: false
            )
            guard commitSelectedSessionMetadata(
                running,
                authority: .asyncResponse(authorityToken)
            ) else { return }
            errorText = nil
            await refreshSelectedSessionMetadata()
        } catch {
            guard !Task.isCancelled, selectedSession?.id == sessionId else { return }
            if isUnauthorized(error) {
                handle(error)
                return
            }
            if case let MobileApiError.retryRejected(_, _, message, retryable) = error,
               let current = selectedSession {
                guard commitSelectedSessionMetadata(
                    replacingRetryDecision(of: current, retryable: retryable),
                    authority: .asyncResponse(authorityToken)
                ) else { return }
                errorText = message
                return
            }
            guard runtimeMetadataAuthority.consume(authorityToken) else { return }
            handle(error)
        }
    }

    @MainActor
    private func runDeterministicRuntimeRetry(sessionId: String) async {
        guard sessionId == "runtime-error-session" else { return }
        do {
            try await Task.sleep(for: .milliseconds(650))
        } catch {
            return
        }
        guard !Task.isCancelled,
              let failedSession = selectedSession,
              failedSession.id == sessionId
        else { return }

        let runningSession = replacingRuntimeState(
            of: failedSession,
            runtimeStatus: "running",
            runtimeStep: nil,
            runtimeError: nil,
            retryable: false
        )
        guard commitSelectedSessionMetadata(
            runningSession,
            authority: .localTransition
        ) else { return }

        do {
            try await Task.sleep(for: .milliseconds(650))
        } catch {
            return
        }
        guard !Task.isCancelled,
              let currentSession = selectedSession,
              currentSession.id == sessionId
        else { return }

        messages.append(ChatMessage(
            id: "runtime-retry-success",
            role: .assistant,
            text: "Retry completed without sending the user message again."
        ))
        let recoveredSession = replacingRuntimeState(
            of: currentSession,
            runtimeStatus: "idle",
            runtimeStep: nil,
            runtimeError: nil,
            retryable: false
        )
        _ = commitSelectedSessionMetadata(
            recoveredSession,
            authority: .localTransition
        )
    }

    private func replacingRuntimeState(
        of session: MobileSession,
        runtimeStatus: String,
        runtimeStep: String?,
        runtimeError: String?,
        retryable: Bool
    ) -> MobileSession {
        MobileSession(
            id: session.id,
            userId: session.userId,
            agentId: session.agentId,
            title: session.title,
            status: session.status,
            runtimeStatus: runtimeStatus,
            runtimeStep: runtimeStep,
            runtimeError: runtimeError,
            retryable: retryable,
            messageCount: session.messageCount,
            updatedAt: session.updatedAt
        )
    }

    private func replacingRetryDecision(
        of session: MobileSession,
        retryable: Bool?
    ) -> MobileSession {
        MobileSession(
            id: session.id,
            userId: session.userId,
            agentId: session.agentId,
            title: session.title,
            status: session.status,
            runtimeStatus: session.runtimeStatus,
            runtimeStep: session.runtimeStep,
            runtimeError: session.runtimeError,
            failureSource: session.failureSource,
            failureCode: session.failureCode,
            retryable: retryable,
            sideEffects: session.sideEffects,
            messageCount: session.messageCount,
            updatedAt: session.updatedAt
        )
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
        cancelRuntimeRetry()
        cancelSend()
        isSending = false
        resetTranscriptPresentationForSessionChange()
        selectedSession = nil
        runtimeMetadataAuthority.reset(sessionId: nil)
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
            if let first = sessions.first {
                runtimeMetadataAuthority.reset(sessionId: first.id)
                selectedSession = first
                runtimeStatusOverride = first.runtimeStatus
                isAgentRunning = first.runtimeStatus == "running"
            }
            return
        }
        await reloadSessions()
    }

    @MainActor
    private func reloadSessions() async {
        let generation = sessionStateGeneration
        let selectionIdAtRequest = selectedSession?.id
        let authorityToken: RuntimeMetadataAuthorityToken?
        if let selectionIdAtRequest {
            authorityToken = runtimeMetadataAuthority.begin(
                .sessionList,
                sessionId: selectionIdAtRequest
            )
        } else {
            authorityToken = nil
        }
        isLoading = true
        errorText = nil
        defer { isLoading = false }

        do {
            let loaded = visibleSessions(from: try await client.listSessions())
            guard !Task.isCancelled,
                  generation == sessionStateGeneration,
                  selectionIdAtRequest == selectedSession?.id
            else { return }

            let previousSessionId = selectionIdAtRequest
            var resolvedSessions = loaded
            let nextSelection: MobileSession?
            if let current = selectedSession {
                guard let authorityToken,
                      runtimeMetadataAuthority.accepts(authorityToken)
                else {
                    sessions = mergingSessionList(loaded, preserving: current)
                    await applyPendingRouteIfNeeded()
                    return
                }

                if let incoming = loaded.first(where: { $0.id == current.id }) {
                    let reconciled = MobileRuntimeSessionReducer.reconcilingOrdinaryMetadata(
                        current: current,
                        incoming: incoming,
                        allowsRuntimeReplacement: true
                    )
                    guard commitSelectedSessionMetadata(
                        reconciled,
                        authority: .asyncResponse(authorityToken)
                    ) else {
                        sessions = mergingSessionList(loaded, preserving: current)
                        return
                    }
                    nextSelection = selectedSession
                    if let nextSelection {
                        resolvedSessions = mergingSessionList(loaded, preserving: nextSelection)
                    }
                } else {
                    guard runtimeMetadataAuthority.consume(authorityToken) else {
                        sessions = mergingSessionList(loaded, preserving: current)
                        return
                    }
                    nextSelection = loaded.first
                    runtimeMetadataAuthority.reset(sessionId: nextSelection?.id)
                }
            } else {
                nextSelection = loaded.first
                runtimeMetadataAuthority.reset(sessionId: nextSelection?.id)
            }

            sessions = resolvedSessions
            synchronizeSelectedAgent(with: nextSelection)
            let preserveVisibleTranscript = nextSelection.map { next in
                previousSessionId == next.id
            } ?? false
            if previousSessionId != nextSelection?.id {
                cancelRuntimeRetry()
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
            if previousSessionId != nextSelection?.id {
                selectedSession = nextSelection
            }
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
            if isUnauthorized(error) {
                handle(error)
            } else if let authorityToken,
                      runtimeMetadataAuthority.consume(authorityToken) {
                handle(error)
            } else if authorityToken == nil, selectionIdAtRequest == nil {
                handle(error)
            }
        }
    }

    @MainActor
    private func select(_ session: MobileSession) async {
        isLoading = true
        defer { isLoading = false }
        let currentSession = selectedSession
        let isCurrentSession = currentSession?.id == session.id
        let resolvedSession: MobileSession
        if let currentSession, isCurrentSession {
            _ = runtimeMetadataAuthority.recordLocalTransition(sessionId: session.id)
            resolvedSession = MobileRuntimeSessionReducer.reconcilingOrdinaryMetadata(
                current: currentSession,
                incoming: session,
                allowsRuntimeReplacement: false
            )
        } else {
            runtimeMetadataAuthority.reset(sessionId: session.id)
            resolvedSession = session
        }
        let preserveVisibleTranscript = isCurrentSession
            && (shouldPreserveVisibleTranscriptDuringRefresh || resolvedSession.runtimeStatus == "running")
        selectedSession = resolvedSession
        if let selectedAgent = availableAgents.first(where: { $0.id == resolvedSession.agentId }) {
            onChatAgentSelected(selectedAgent)
        }
        isAgentRunning = resolvedSession.runtimeStatus == "running"
        runtimeStatusOverride = resolvedSession.runtimeStatus
        if !isCurrentSession {
            cancelRuntimeRetry()
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
            for: resolvedSession,
            preserveVisibleTranscript: preserveVisibleTranscript
        )
        guard !Task.isCancelled, selectedSession?.id == resolvedSession.id else { return }
        startRealtime(for: resolvedSession)
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
        cancelRuntimeRetry()

        if agent.id != defaultAgent?.id {
            acceptedAgentSelectionID = agent.id
        }
        onChatAgentSelected(agent)
        onNewConversationAgentSelected(agent)
        if agent.id == defaultAgent?.id {
            sessions.removeAll { $0.id == created.id }
            sessions.insert(created, at: 0)
        } else {
            sessions = [created]
        }
        runtimeMetadataAuthority.reset(sessionId: created.id)
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
        let selectionIdAtRequest = selectedSession?.id
        let authorityToken: RuntimeMetadataAuthorityToken?
        if let selectionIdAtRequest {
            authorityToken = runtimeMetadataAuthority.begin(
                .sessionList,
                sessionId: selectionIdAtRequest
            )
        } else {
            authorityToken = nil
        }
        do {
            let loaded = visibleSessions(from: try await client.listSessions())
            guard !Task.isCancelled,
                  generation == sessionStateGeneration,
                  selectionIdAtRequest == selectedSession?.id
            else { return }
            guard let current = selectedSession else {
                sessions = loaded
                return
            }
            guard let authorityToken,
                  runtimeMetadataAuthority.accepts(authorityToken),
                  let incoming = loaded.first(where: { $0.id == current.id })
            else {
                sessions = mergingSessionList(loaded, preserving: current)
                return
            }
            let reconciled = MobileRuntimeSessionReducer.reconcilingOrdinaryMetadata(
                current: current,
                incoming: incoming,
                allowsRuntimeReplacement: true
            )
            if commitSelectedSessionMetadata(
                reconciled,
                authority: .asyncResponse(authorityToken)
            ), let selectedSession {
                sessions = mergingSessionList(loaded, preserving: selectedSession)
            }
        } catch {
            guard !Task.isCancelled else { return }
            if case let MobileApiError.httpStatus(status, _) = error, status == 401 {
                handle(error)
            } else if let authorityToken,
                      runtimeMetadataAuthority.consume(authorityToken) {
                errorText = error.localizedDescription
            } else if authorityToken == nil, selectionIdAtRequest == nil {
                errorText = error.localizedDescription
            } else {
                return
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
        guard runtimeMetadataAuthority.recordLocalTransition(sessionId: session.id) else {
            return
        }
        composerFocused = false
        let previousCount = messages.count
        isSending = true
        isAgentRunning = true
        assistantTurnSequence &+= 1
        awaitingAssistantActivity = true
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
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("--ui-testing-assistant-activity") {
                try? await Task.sleep(for: .seconds(4))
                guard !Task.isCancelled, selectedSession?.id == session.id else { return }
            }
            #endif
            let sentIds = Set(attachments.map(\.id))
            uploadedAttachments.removeAll { sentIds.contains($0.id) }
            clearOptimisticProtection()
            isSending = false
            _ = runtimeMetadataAuthority.recordLocalTransition(sessionId: session.id)
            isAgentRunning = false
            awaitingAssistantActivity = false
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
            awaitingAssistantActivity = false
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
        guard let authorityToken = runtimeMetadataAuthority.begin(
            .ordinaryREST,
            sessionId: selectedSession.id
        ) else { return }
        do {
            let refreshed = try await client.getSession(sessionId: selectedSession.id)
            guard !Task.isCancelled, self.selectedSession?.id == selectedSession.id else { return }
            let reconciled = MobileRuntimeSessionReducer.reconcilingOrdinaryMetadata(
                current: self.selectedSession ?? selectedSession,
                incoming: refreshed,
                allowsRuntimeReplacement: true
            )
            if commitSelectedSessionMetadata(
                reconciled,
                authority: .asyncResponse(authorityToken)
            ) {
                errorText = nil
            }
        } catch {
            guard !Task.isCancelled, self.selectedSession?.id == selectedSession.id else { return }
            if isUnauthorized(error) {
                handle(error)
            } else if runtimeMetadataAuthority.consume(authorityToken) {
                handle(error)
            }
        }
    }

    @MainActor
    private func handle(_ error: Error) {
        if isUnauthorized(error) {
            performFullCleanup()
            appState.resetPairing()
            return
        }
        errorText = error.localizedDescription
    }

    private func isUnauthorized(_ error: Error) -> Bool {
        if case let MobileApiError.httpStatus(status, _) = error {
            return status == 401
        }
        return false
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

        switch interaction.kind {
        case .ask:
            guard let answer,
                  !answer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            else { return }
        case .confirmation:
            guard decision != nil else { return }
        }
        guard let authorityToken = runtimeMetadataAuthority.begin(
            .interactionAcceptance,
            sessionId: session.id
        ) else { return }

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
            guard let current = selectedSession else { return }
            let running = replacingRuntimeState(
                of: current,
                runtimeStatus: "running",
                runtimeStep: nil,
                runtimeError: nil,
                retryable: false
            )
            guard commitSelectedSessionMetadata(
                running,
                authority: .asyncResponse(authorityToken)
            ) else { return }
            await loadMessages(for: session, preserveVisibleTranscript: true)
            await refreshSelectedSessionMetadata()
        } catch {
            guard !Task.isCancelled, selectedSession?.id == session.id else { return }
            if isUnauthorized(error) {
                handle(error)
                return
            }
            guard runtimeMetadataAuthority.consume(authorityToken) else { return }
            if case let MobileApiError.httpStatus(status, _) = error, status == 410 {
                pendingInteractions.removeAll { $0.id == interaction.id }
            }
            pendingInteractionErrorId = interaction.id
            pendingInteractionError = error.localizedDescription
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
        cancelBottomButtonScroll()
        expandedToolCallIDs.removeAll()
        highestAppliedRemoteSeqNo = nil
        isTranscriptPinnedToBottom = true
        scrollFollowState = ChatScrollFollowPolicy.reduce(scrollFollowState, event: .sessionChanged)
        awaitingAssistantActivity = false
        stabilizeStreamingTailIdentity = false
        pendingHandoffLayoutRecovery = false
        pendingAutoScrollAfterKeyboard = false
        pendingSourceMessageSeq = nil
        sourceRouteNotice = nil
    }

    @MainActor
    private func activateSourceRoute(_ sourceMessageSeq: Int64?) {
        sourceRouteNotice = nil
        pendingSourceMessageSeq = sourceMessageSeq
    }

    @MainActor
    @discardableResult
    private func resolvePendingSourceRoute(proxy: ScrollViewProxy) -> Bool {
        switch ChatSourceRoutePolicy.resolve(
            sourceMessageSeq: pendingSourceMessageSeq,
            messages: messages
        ) {
        case .none:
            return false
        case let .target(id):
            pendingSourceMessageSeq = nil
            sourceRouteNotice = nil
            isTranscriptPinnedToBottom = false
            Task { @MainActor in
                await Task.yield()
                if !ChatScrollPolicy.shouldAnimate(
                    requested: true,
                    reduceMotionEnabled: accessibilityReduceMotion
                ) {
                    proxy.scrollTo(id, anchor: .center)
                } else {
                    withAnimation(.snappy(duration: 0.22)) {
                        proxy.scrollTo(id, anchor: .center)
                    }
                }
            }
            return true
        case .missing:
            pendingSourceMessageSeq = nil
            sourceRouteNotice = "The source message is no longer available in this conversation."
            return true
        }
    }

    @MainActor
    private func upsertPendingInteraction(_ interaction: PendingInteraction) {
        pendingInteractions.removeAll { $0.id == interaction.id }
        pendingInteractions.append(interaction)
        pendingInteractionErrorId = nil
        pendingInteractionError = nil
    }

    private func scrollToBottom(proxy: ScrollViewProxy, animated: Bool) {
        guard let bottomScrollTargetID else { return }
        if ChatScrollPolicy.shouldAnimate(
            requested: animated,
            reduceMotionEnabled: accessibilityReduceMotion
        ) {
            withAnimation(.snappy(duration: 0.22)) {
                proxy.scrollTo(bottomScrollTargetID, anchor: .bottom)
            }
        } else {
            proxy.scrollTo(bottomScrollTargetID, anchor: .bottom)
        }
    }

    private func requestAutoScroll(proxy: ScrollViewProxy, animated: Bool) {
        guard scrollFollowState.mode == .following else { return }
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
        #if DEBUG
        if DebugLaunchConfiguration.isChatUITest {
            deterministicBottomButtonTapCount += 1
        }
        #endif
        updateScrollFollow(.bottomButtonTapped)
        switch ChatScrollPolicy.bottomButtonAction(
            isComposerFocused: composerFocused,
            isKeyboardVisible: isKeyboardVisible,
            isKeyboardSettling: isKeyboardSettling
        ) {
        case .dismissKeyboardThenScroll:
            pendingBottomScrollAfterKeyboard = true
            composerFocused = false
            scheduleBottomScrollFallback()
        case .scrollAuthoritatively:
            requestAuthoritativeBottomScroll(
                proxy: proxy,
                sessionID: selectedSession?.id
            )
        }
    }

    @MainActor
    private func updateScrollFollow(_ event: ChatScrollFollowEvent) {
        scrollFollowState = ChatScrollFollowPolicy.reduce(scrollFollowState, event: event)
        isTranscriptPinnedToBottom = scrollFollowState.mode == .following
    }

    @MainActor
    private func requestAuthoritativeBottomScroll(
        proxy: ScrollViewProxy,
        sessionID: String?
    ) {
        guard let bottomScrollTargetID else { return }
        let shouldAnimate = ChatScrollPolicy.shouldAnimate(
            requested: true,
            reduceMotionEnabled: accessibilityReduceMotion
        )
        _ = bottomScrollCoordinator.request(sessionID: sessionID) {
            if shouldAnimate {
                withAnimation(.snappy(duration: 0.22)) {
                    proxy.scrollTo(bottomScrollTargetID, anchor: .bottom)
                }
            } else {
                proxy.scrollTo(bottomScrollTargetID, anchor: .bottom)
            }
        }
    }

    @MainActor
    private func scheduleBottomScrollFallback() {
        bottomButtonKeyboardFallbackTask?.cancel()
        let sessionID = selectedSession?.id
        bottomButtonKeyboardFallbackTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: ChatScrollPolicy.keyboardDismissFallbackNanoseconds)
            guard !Task.isCancelled else { return }
            bottomButtonKeyboardFallbackTask = nil
            guard selectedSession?.id == sessionID else { return }
            guard !isKeyboardVisible else { return }
            completePendingBottomScroll()
        }
    }

    @MainActor
    private func completePendingBottomScroll() {
        let shouldCompleteButtonScroll = pendingBottomScrollAfterKeyboard
        let shouldCompleteAutoScroll = pendingAutoScrollAfterKeyboard
        guard shouldCompleteButtonScroll || shouldCompleteAutoScroll else { return }
        guard !isKeyboardVisible, !isKeyboardSettling else { return }
        bottomButtonKeyboardFallbackTask?.cancel()
        bottomButtonKeyboardFallbackTask = nil
        pendingBottomScrollAfterKeyboard = false
        pendingAutoScrollAfterKeyboard = false
        if shouldCompleteAutoScroll, pendingHandoffLayoutRecovery {
            stabilizeStreamingTailIdentity = false
            pendingHandoffLayoutRecovery = false
        }
        if shouldCompleteButtonScroll, let sessionID = selectedSession?.id {
            nextDeferredAuthoritativeBottomScrollID &+= 1
            deferredAuthoritativeBottomScroll = DeferredAuthoritativeBottomScroll(
                id: nextDeferredAuthoritativeBottomScrollID,
                sessionID: sessionID
            )
        }
        guard shouldCompleteAutoScroll else { return }
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
    private func cancelBottomButtonScroll() {
        bottomButtonKeyboardFallbackTask?.cancel()
        bottomButtonKeyboardFallbackTask = nil
        pendingBottomScrollAfterKeyboard = false
        deferredAuthoritativeBottomScroll = nil
        bottomScrollCoordinator.cancelPendingRequest()
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
        cancelTerminalMetadataCatchUp()
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
            await refreshSelectedSessionMetadata()
            guard !Task.isCancelled, selectedSession?.id == sessionId else { return }
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
        let isSessionMetadataEvent = event.type == "session_status"
            || event.type == "session_updated"
        if !isSessionMetadataEvent {
            guard runtimeMetadataAuthority.recordRealtimeFact(sessionId: event.sessionId) else {
                return
            }
        }
        switch event.type {
        case "session_status", "session_updated":
            guard applyRealtimeSessionFact(event) else { return }
            guard let eventRuntimeStatus = MobileRuntimeSessionReducer.resolvedRuntimeStatus(for: event) else {
                return
            }
            let normalizedStatus = eventRuntimeStatus
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .lowercased()
            if normalizedStatus == "running" || normalizedStatus == "queued" {
                cancelTerminalMetadataCatchUp()
                isAgentRunning = true
            } else if RuntimeMetadataCatchUpPolicy.isTerminalStatus(normalizedStatus) {
                flushStreamingBuffer()
                isAgentRunning = false
                awaitingAssistantActivity = false
                pendingInteractions.removeAll { $0.source == .realtime }
                scheduleTerminalMetadataCatchUp(
                    sessionId: event.sessionId,
                    expectedRuntimeStatus: eventRuntimeStatus
                )
                await reloadRemoteMessagesForRealtime(
                    sessionId: event.sessionId,
                    clearStreamingText: true,
                    clearToolCalls: true
                )
            } else {
                cancelTerminalMetadataCatchUp()
                if normalizedStatus == "waiting_user" {
                    isAgentRunning = false
                    awaitingAssistantActivity = false
                }
            }
        case "ask_user", "confirmation_required":
            if let interaction = PendingInteraction(realtimeEvent: event) {
                cancelTerminalMetadataCatchUp()
                upsertPendingInteraction(interaction)
                isAgentRunning = false
                awaitingAssistantActivity = false
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
    @discardableResult
    private func applyRealtimeSessionFact(_ event: MobileChatEvent) -> Bool {
        guard let selectedSession, selectedSession.id == event.sessionId else { return false }
        let updated = MobileRuntimeSessionReducer.applying(event: event, to: selectedSession)
        return commitSelectedSessionMetadata(updated, authority: .realtime)
    }

    @MainActor
    private func scheduleTerminalMetadataCatchUp(
        sessionId: String,
        expectedRuntimeStatus: String
    ) {
        cancelTerminalMetadataCatchUp()
        guard let current = selectedSession,
              current.id == sessionId,
              RuntimeMetadataCatchUpPolicy.shouldAttemptFetch(
                  current,
                  expectedRuntimeStatus: expectedRuntimeStatus
              )
        else { return }
        let operationId = UUID()
        let apiClient = client
        terminalMetadataCatchUpOperationId = operationId
        terminalMetadataCatchUpTask = Task { @MainActor in
            await catchUpTerminalMetadata(
                sessionId: sessionId,
                expectedRuntimeStatus: expectedRuntimeStatus,
                client: apiClient
            )
            guard terminalMetadataCatchUpOperationId == operationId else { return }
            terminalMetadataCatchUpOperationId = nil
            terminalMetadataCatchUpTask = nil
        }
    }

    @MainActor
    private func cancelTerminalMetadataCatchUp() {
        terminalMetadataCatchUpOperationId = nil
        terminalMetadataCatchUpTask?.cancel()
        terminalMetadataCatchUpTask = nil
    }

    @MainActor
    private func catchUpTerminalMetadata(
        sessionId: String,
        expectedRuntimeStatus: String,
        client: MobileApiClient
    ) async {
        for attempt in 0..<RuntimeMetadataCatchUpPolicy.maximumAttempts {
            guard !Task.isCancelled,
                  let current = selectedSession,
                  current.id == sessionId,
                  RuntimeMetadataCatchUpPolicy.shouldAttemptFetch(
                      current,
                      expectedRuntimeStatus: expectedRuntimeStatus
                  )
            else { return }
            guard let delay = RuntimeMetadataCatchUpPolicy.delayNanoseconds(beforeAttempt: attempt) else {
                return
            }
            if delay > 0 {
                do {
                    try await Task.sleep(nanoseconds: delay)
                } catch {
                    return
                }
                guard !Task.isCancelled,
                      let current = selectedSession,
                      current.id == sessionId,
                      RuntimeMetadataCatchUpPolicy.shouldAttemptFetch(
                          current,
                          expectedRuntimeStatus: expectedRuntimeStatus
                      )
                else { return }
            }

            guard let authorityToken = runtimeMetadataAuthority.begin(
                .terminalCatchUp,
                sessionId: sessionId
            ) else { return }
            do {
                let incoming = try await client.getSession(sessionId: sessionId)
                guard !Task.isCancelled,
                      let current = selectedSession,
                      current.id == sessionId,
                      RuntimeMetadataCatchUpPolicy.shouldAttemptFetch(
                          current,
                          expectedRuntimeStatus: expectedRuntimeStatus
                      )
                else { return }
                let reconciled = MobileRuntimeSessionReducer.reconcilingTerminalMetadata(
                    current: current,
                    incoming: incoming,
                    expectedRuntimeStatus: expectedRuntimeStatus
                )
                guard commitSelectedSessionMetadata(
                    reconciled,
                    authority: .asyncResponse(authorityToken)
                ) else {
                    if let current = selectedSession,
                       current.id == sessionId,
                       RuntimeMetadataCatchUpPolicy.shouldAttemptFetch(
                           current,
                           expectedRuntimeStatus: expectedRuntimeStatus
                       ) {
                        continue
                    }
                    return
                }
                if RuntimeMetadataCatchUpPolicy.isReconciled(
                    reconciled,
                    expectedRuntimeStatus: expectedRuntimeStatus
                ) {
                    errorText = nil
                    return
                }
            } catch {
                guard !Task.isCancelled,
                      let current = selectedSession,
                      current.id == sessionId,
                      RuntimeMetadataCatchUpPolicy.shouldAttemptFetch(
                          current,
                          expectedRuntimeStatus: expectedRuntimeStatus
                      )
                else { return }
                if isUnauthorized(error) {
                    handle(error)
                    return
                }
                _ = runtimeMetadataAuthority.consume(authorityToken)
            }
        }

        guard !Task.isCancelled,
              let current = selectedSession,
              current.id == sessionId,
              RuntimeMetadataCatchUpPolicy.shouldAttemptFetch(
                  current,
                  expectedRuntimeStatus: expectedRuntimeStatus
              )
        else { return }
        errorText = "运行详情同步暂时失败，将在重新连接后继续恢复。"
    }

    @MainActor
    @discardableResult
    private func commitSelectedSessionMetadata(
        _ session: MobileSession,
        authority: RuntimeMetadataCommitAuthority
    ) -> Bool {
        guard selectedSession?.id == session.id else { return false }
        let accepted: Bool
        switch authority {
        case .realtime:
            accepted = runtimeMetadataAuthority.recordRealtimeFact(sessionId: session.id)
        case let .asyncResponse(token):
            accepted = runtimeMetadataAuthority.consume(token)
        case .localTransition:
            accepted = runtimeMetadataAuthority.recordLocalTransition(sessionId: session.id)
        }
        guard accepted else { return false }
        selectedSession = session
        sessions = sessions.map { $0.id == session.id ? session : $0 }
        runtimeStatusOverride = session.runtimeStatus
        isAgentRunning = session.runtimeStatus == "running" || session.runtimeStatus == "queued"
        return true
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
        ChatSessionVisibilityPolicy.visibleSessions(loaded)
    }

    @MainActor
    private func synchronizeSelectedAgent(with session: MobileSession?) {
        guard let session,
              let agent = availableAgents.first(where: { $0.id == session.agentId })
        else { return }
        onChatAgentSelected(agent)
    }

    private func mergingSessionList(
        _ loaded: [MobileSession],
        preserving selected: MobileSession
    ) -> [MobileSession] {
        var merged = loaded
        if let index = merged.firstIndex(where: { $0.id == selected.id }) {
            merged[index] = selected
        } else {
            merged.insert(selected, at: 0)
        }
        return merged
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
            guard !Task.isCancelled,
                  let selectedSession,
                  runtimeMetadataAuthority.recordLocalTransition(sessionId: selectedSession.id)
            else { return }
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

private extension ChatStatusSemantic {
    var color: Color {
        switch self {
        case .connected:
            return .green
        case .running:
            return .blue
        case .waiting:
            return .orange
        case .error:
            return .red
        case .cancelled, .neutral:
            return .gray
        }
    }
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
    let createdAt: Date?
    let reasoningContent: String?
    let isStreaming: Bool
    let remoteSeqNo: Int64?

    init(
        id: String = UUID().uuidString,
        role: Role,
        text: String,
        toolCalls: [ToolCall] = [],
        attachments: [ChatAttachment] = [],
        createdAt: Date? = nil,
        reasoningContent: String? = nil,
        isStreaming: Bool = false,
        remoteSeqNo: Int64? = nil
    ) {
        self.id = id
        self.role = role
        self.text = text
        self.toolCalls = toolCalls
        self.attachments = attachments
        self.createdAt = createdAt
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
                    createdAt: Self.parseCreatedAt(message.createdAt),
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
                createdAt: Self.parseCreatedAt(message.createdAt),
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

    private static func parseCreatedAt(_ value: String?) -> Date? {
        guard let value else { return nil }
        return (try? Date.ISO8601FormatStyle(includingFractionalSeconds: true).parse(value))
            ?? (try? Date.ISO8601FormatStyle().parse(value))
    }
}
