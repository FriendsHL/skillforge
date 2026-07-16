import CryptoKit
import SwiftUI
import UIKit
@preconcurrency import UserNotifications

struct SettingsView: View {
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage(AppAppearance.storageKey) private var appearanceRawValue = AppAppearance.system.rawValue

    let endpoint: URL
    let device: MobileDeviceSummary?
    let client: MobileApiClient
    let usesDeterministicFixture: Bool
    let onUnauthorized: () -> Void
    let onDisconnect: () -> Void

    @StateObject private var connectionMonitor: ConnectionHealthMonitor
    @State private var showDisconnectConfirmation = false
    @State private var notificationState: NotificationPermissionState = .loading
    @State private var notificationErrorText: String?

    init(
        endpoint: URL,
        device: MobileDeviceSummary?,
        client: MobileApiClient,
        usesDeterministicFixture: Bool,
        onUnauthorized: @escaping () -> Void,
        onDisconnect: @escaping () -> Void
    ) {
        self.endpoint = endpoint
        self.device = device
        self.client = client
        self.usesDeterministicFixture = usesDeterministicFixture
        self.onUnauthorized = onUnauthorized
        self.onDisconnect = onDisconnect

        let pairingIdentity = Self.pairingIdentity(endpoint: endpoint, device: device, client: client)
        let probe: ConnectionHealthMonitor.Probe
#if DEBUG
        if usesDeterministicFixture {
            let isOffline = ProcessInfo.processInfo.arguments.contains("--connection-health-offline")
            probe = {
                if isOffline {
                    throw URLError(.notConnectedToInternet)
                }
            }
        } else {
            probe = { _ = try await client.me() }
        }
#else
        probe = { _ = try await client.me() }
#endif
        _connectionMonitor = StateObject(
            wrappedValue: ConnectionHealthMonitor(
                pairingIdentity: pairingIdentity,
                probe: probe,
                onUnauthorized: onUnauthorized
            )
        )
    }

    var body: some View {
        NavigationStack {
            List {
                Section("Connection") {
                    NavigationLink {
                        ConnectionHealthDetailView(
                            endpoint: endpoint,
                            health: connectionMonitor.state,
                            onCheckAgain: runConnectionCheck
                        )
                    } label: {
                        HStack(spacing: 12) {
                            connectionHealthIcon
                                .frame(width: 24)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(connectionMonitor.state.phase.summaryTitle)
                                    .font(.body.weight(.medium))
                                Text(connectionSummaryDetail)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    .accessibilityIdentifier("settings.connection.summary")
                }

                Section("Device") {
                    LabeledContent("Name", value: device?.deviceName ?? "iPhone")
                    LabeledContent("Device ID") {
                        Text(device?.id ?? "Unavailable")
                            .font(.caption.monospaced())
                            .multilineTextAlignment(.trailing)
                    }
                    LabeledContent("App Version", value: appVersion)
                }

                Section {
                    HStack(spacing: 12) {
                        Image(systemName: notificationState.symbol)
                            .foregroundStyle(notificationState.color)
                            .frame(width: 24)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(notificationState.title)
                                .font(.body.weight(.medium))
                            Text(notificationState.detail)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }

                    if notificationState.canRequest {
                        Button(action: enableNotifications) {
                            Label("Enable Notifications", systemImage: "bell.badge")
                        }
                        .accessibilityIdentifier("settings.notifications.enable")
                    } else if notificationState.shouldOpenSettings {
                        Button(action: openSystemSettings) {
                            Label("Open System Settings", systemImage: "gear")
                        }
                        .accessibilityIdentifier("settings.notifications.openSettings")
                    }

                    Button(action: refreshNotificationStatus) {
                        Label("Refresh Status", systemImage: "arrow.clockwise")
                    }
                    .disabled(notificationState == .loading)
                    .accessibilityIdentifier("settings.notifications.refresh")

                    if let notificationErrorText {
                        Text(notificationErrorText)
                            .font(.caption)
                            .foregroundStyle(.red)
                    }
                } header: {
                    Text("Notifications")
                } footer: {
                    Text("SkillForge registers this iPhone with the server after iOS grants notification permission.")
                }

                Section("Appearance") {
                    Picker("Appearance", selection: $appearanceRawValue) {
                        ForEach(AppAppearance.allCases) { appearance in
                            Text(appearance.title).tag(appearance.rawValue)
                        }
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("settings.appearance")
                }

                Section {
                    Label("Camera is requested only while scanning a pairing QR code.", systemImage: "camera")
                    Label("Contacts, calendar, and location are not requested.", systemImage: "hand.raised")
                    Label("The device credential is stored securely and is never displayed here.", systemImage: "key")

                    DisclosureGroup("Granted SkillForge Scopes") {
                        if let scopes = device?.scopes.sorted(), !scopes.isEmpty {
                            ForEach(scopes, id: \.self) { scope in
                                Label(scope, systemImage: "checkmark.shield.fill")
                                    .foregroundStyle(.primary)
                            }
                        } else {
                            Text("No scopes reported")
                                .foregroundStyle(.secondary)
                        }
                    }
                } header: {
                    Text("Permissions & Privacy")
                } footer: {
                    Text("SkillForge derives access from this paired device. This page never displays the device token.")
                }

                Section {
                    Button(role: .destructive) {
                        showDisconnectConfirmation = true
                    } label: {
                        Label("Disconnect this iPhone", systemImage: "rectangle.portrait.and.arrow.right")
                    }
                    .accessibilityIdentifier("settings.disconnect")
                } footer: {
                    Text("Disconnecting only clears connection data on this iPhone. Server-side device access is unchanged.")
                }
            }
            .scrollContentBackground(.hidden)
            .background(CompanionStyle.warmBackground)
            .navigationTitle("Settings")
            .alert("Disconnect this iPhone?", isPresented: $showDisconnectConfirmation) {
                Button("Cancel", role: .cancel) {}
                Button("Disconnect locally", role: .destructive, action: onDisconnect)
            } message: {
                Text("This clears the endpoint and device credential stored on this iPhone. Server-side device access is unchanged.")
            }
            .task {
                await loadNotificationStatus()
                runConnectionCheck()
            }
            .onChange(of: scenePhase) { _, phase in
                guard phase == .active else { return }
                refreshNotificationStatus()
                runConnectionCheck()
            }
            .onReceive(NotificationCenter.default.publisher(for: .skillForgePushRegistrationFailed)) { note in
                notificationErrorText = note.object as? String ?? "iOS push registration failed."
            }
            .onReceive(NotificationCenter.default.publisher(for: .skillForgePushTokenUploadFailed)) { note in
                notificationErrorText = note.object as? String ?? "Server push registration failed."
            }
            .onReceive(NotificationCenter.default.publisher(for: .skillForgePushTokenUploadSucceeded)) { _ in
                notificationErrorText = nil
            }
        }
        .onDisappear {
            connectionMonitor.cancel()
        }
    }

    @ViewBuilder
    private var connectionHealthIcon: some View {
        if connectionMonitor.state.phase == .checking {
            ProgressView()
        } else {
            Image(systemName: connectionMonitor.state.phase.symbol)
                .foregroundStyle(connectionMonitor.state.phase.tint)
        }
    }

    private var connectionSummaryDetail: String {
        guard let lastCheckedAt = connectionMonitor.state.lastCheckedAt else {
            return connectionMonitor.state.phase.summaryDetail
        }
        return "\(connectionMonitor.state.phase.summaryDetail) Checked \(lastCheckedAt.formatted(date: .omitted, time: .shortened))."
    }

    private var appVersion: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "Unknown"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String
        return build.map { "\(version) (\($0))" } ?? version
    }

    private func runConnectionCheck() {
        connectionMonitor.check(pairingIdentity: pairingIdentity)
    }

    private var pairingIdentity: String {
        Self.pairingIdentity(endpoint: endpoint, device: device, client: client)
    }

    private static func pairingIdentity(
        endpoint: URL,
        device: MobileDeviceSummary?,
        client: MobileApiClient
    ) -> String {
        let credential = client.deviceToken ?? "missing-credential"
        let fingerprint = SHA256.hash(data: Data(credential.utf8))
            .prefix(12)
            .map { String(format: "%02x", $0) }
            .joined()
        return "\(endpoint.absoluteString)|\(device?.id ?? "unknown-device")|\(fingerprint)"
    }

    private func refreshNotificationStatus() {
        Task { @MainActor in await loadNotificationStatus() }
    }

    @MainActor
    private func loadNotificationStatus() async {
        if usesDeterministicFixture {
            if notificationState == .loading {
                notificationState = .authorization(.notDetermined)
            }
            return
        }
        notificationState = .loading
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        guard !Task.isCancelled else { return }
        notificationState = NotificationPermissionState(settings.authorizationStatus)
    }

    private func enableNotifications() {
        notificationErrorText = nil
        if usesDeterministicFixture {
            notificationState = .authorization(.authorized)
            return
        }
        notificationState = .loading
        Task { @MainActor in
            do {
                _ = try await PushRegistration().requestAuthorizationIfNeeded()
                await loadNotificationStatus()
            } catch {
                notificationErrorText = error.localizedDescription
                await loadNotificationStatus()
            }
        }
    }

    private func openSystemSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }
}

private struct ConnectionHealthDetailView: View {
    let endpoint: URL
    let health: ConnectionHealthState
    let onCheckAgain: () -> Void

    var body: some View {
        List {
            Section("Connection") {
                LabeledContent("Endpoint") {
                    Text(endpoint.absoluteString)
                        .font(.caption.monospaced())
                        .multilineTextAlignment(.trailing)
                        .textSelection(.enabled)
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("Endpoint")
                .accessibilityValue(endpoint.absoluteString)
                .accessibilityIdentifier("settings.connection.endpoint")

                LabeledContent("Endpoint status", value: health.phase.endpointStatus)
                diagnosticRow(
                    "SkillForge service",
                    value: health.phase.serviceStatus,
                    identifier: "settings.connection.service"
                )
                diagnosticRow(
                    "Device authorization",
                    value: health.phase.authorizationStatus,
                    identifier: "settings.connection.authorization"
                )
                diagnosticRow(
                    "Realtime",
                    value: "Managed by foreground Chat",
                    identifier: "settings.connection.realtime"
                )
                diagnosticRow(
                    "Last checked",
                    value: lastCheckedText,
                    identifier: "settings.connection.lastCheck"
                )
            }

            Section {
                Text(health.phase.summaryDetail)
                    .foregroundStyle(.secondary)

                Button(action: onCheckAgain) {
                    Label("Check Again", systemImage: "arrow.clockwise")
                }
                .disabled(health.phase == .checking)
                .accessibilityIdentifier("settings.connection.checkAgain")
            } footer: {
                Text("Realtime connectivity is managed only while Chat is in the foreground. This check does not claim that a WebSocket is currently connected.")
            }
        }
        .navigationTitle("Connection Diagnostics")
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("settings.connection.detail")
    }

    private func diagnosticRow(_ title: String, value: String, identifier: String) -> some View {
        LabeledContent(title, value: value)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(title)
            .accessibilityValue(value)
            .accessibilityIdentifier(identifier)
    }

    private var lastCheckedText: String {
        health.lastCheckedAt?.formatted(date: .abbreviated, time: .standard) ?? "Not checked"
    }
}

private enum NotificationPermissionState: Equatable {
    case loading
    case authorization(UNAuthorizationStatus)

    init(_ status: UNAuthorizationStatus) {
        self = .authorization(status)
    }

    var title: String {
        if let presentation { return presentation.statusText }
        return switch self {
        case .loading: "Checking permission"
        case .authorization: "Notifications"
        }
    }

    var detail: String {
        switch self {
        case .loading: "Reading the current iOS authorization status."
        case let .authorization(status):
            switch status {
            case .notDetermined: "SkillForge has not requested notification permission."
            case .denied: "Enable notifications from the iOS Settings app."
            case .authorized, .provisional, .ephemeral: "iOS permission is available on this iPhone."
            @unknown default: "This iOS notification authorization state is not yet supported."
            }
        }
    }

    var symbol: String {
        switch self {
        case .loading: "clock"
        case let .authorization(status):
            switch status {
            case .notDetermined: "bell"
            case .denied: "bell.slash.fill"
            case .authorized, .provisional, .ephemeral: "bell.badge.fill"
            @unknown default: "questionmark.circle"
            }
        }
    }

    var color: Color {
        switch self {
        case .loading: .secondary
        case let .authorization(status):
            switch status {
            case .notDetermined: .secondary
            case .denied: .red
            case .authorized, .provisional, .ephemeral: .green
            @unknown default: .orange
            }
        }
    }

    var canRequest: Bool { presentation?.canRequestPermission ?? false }
    var shouldOpenSettings: Bool { presentation?.shouldOpenSystemSettings ?? false }

    private var presentation: NotificationPresentation? {
        switch self {
        case .loading: return nil
        case let .authorization(status):
            return NotificationPresentationPolicy.presentation(for: status)
        }
    }
}

private extension ConnectionHealthPhase {
    var tint: Color {
        switch self {
        case .notChecked, .checking: .secondary
        case .healthy: .green
        case .offline: .red
        case .serviceIssue: .orange
        }
    }
}
