import SwiftUI
import UIKit

struct PairingView: View {
    @EnvironmentObject private var appState: AppState
    @StateObject private var model: PairingFlowModel
    @State private var scannerOpen = false
    @FocusState private var payloadInputFocused: Bool

    init() {
        #if DEBUG
        if DebugLaunchConfiguration.isPairingReviewUITest {
            _model = StateObject(wrappedValue: .reviewUITestFixture())
        } else {
            _model = StateObject(wrappedValue: PairingFlowModel())
        }
        #else
        _model = StateObject(wrappedValue: PairingFlowModel())
        #endif
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    switch model.stage {
                    case .ready:
                        readyContent
                    case let .review(review):
                        reviewContent(review)
                    case .checking:
                        progressContent(title: "Checking server", detail: "Looking for a reachable SkillForge endpoint.")
                    case .claiming:
                        progressContent(title: "Pairing securely", detail: "Completing the one-time pairing request.")
                    case .saving:
                        progressContent(title: "Saving connection", detail: "Securing this device connection.")
                    case .paired:
                        Label("Pairing confirmation received", systemImage: "checkmark.circle.fill")
                            .font(.headline)
                            .foregroundStyle(.green)
                            .accessibilityIdentifier("pairing.confirmed")
                    }
                }
                .frame(maxWidth: 560, alignment: .leading)
                .padding(.horizontal, 20)
                .padding(.vertical, 24)
            }
            .scrollDismissesKeyboard(.interactively)
            .navigationTitle("Pair SkillForge")
            .sheet(isPresented: $scannerOpen) {
                scannerSheet
            }
        }
    }

    private var readyContent: some View {
        Group {
            header
            scanSection
            pasteSection
            if let error = model.error {
                errorBanner(error)
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 10) {
            Image(systemName: "iphone.and.arrow.forward.inward")
                .font(.system(size: 34, weight: .medium))
                .foregroundStyle(.tint)
                .accessibilityHidden(true)
            Text("Connect to your SkillForge server")
                .font(.title2.weight(.semibold))
            Text("Scan the QR shown in Dashboard. You will review the server before this iPhone uses the one-time pairing request.")
                .font(.callout)
                .foregroundStyle(.secondary)
        }
    }

    private var scanSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Button {
                scannerOpen = true
            } label: {
                Label("Scan Dashboard QR", systemImage: "qrcode.viewfinder")
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.borderedProminent)
            .disabled(!PairingScannerAvailability.canUseCameraScanner)
            .accessibilityIdentifier("pairing.scan")
            .accessibilityHint("Opens the camera to scan a SkillForge pairing QR code")

            if !PairingScannerAvailability.canUseCameraScanner {
                Text(PairingScannerAvailability.unavailableMessage)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("pairing.camera.unavailable")
            }
        }
    }

    private var pasteSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Button {
                withAnimation(.easeInOut(duration: 0.2)) {
                    model.isPasteExpanded.toggle()
                }
                if !model.isPasteExpanded {
                    model.payloadText = ""
                    payloadInputFocused = false
                }
            } label: {
                HStack {
                    Label("Paste Pairing Payload", systemImage: "doc.on.clipboard")
                    Spacer(minLength: 12)
                    Image(systemName: model.isPasteExpanded ? "chevron.up" : "chevron.down")
                        .accessibilityHidden(true)
                }
                .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.bordered)
            .accessibilityIdentifier("pairing.paste.toggle")
            .accessibilityValue(model.isPasteExpanded ? "Expanded" : "Collapsed")

            if model.isPasteExpanded {
                VStack(alignment: .leading, spacing: 10) {
                    Text("Complete pairing payload")
                        .font(.subheadline.weight(.medium))
                    TextEditor(text: $model.payloadText)
                        .font(.system(.footnote, design: .monospaced))
                        .frame(minHeight: 120)
                        .padding(6)
                        .background(.quaternary.opacity(0.25), in: RoundedRectangle(cornerRadius: 8))
                        .overlay {
                            RoundedRectangle(cornerRadius: 8)
                                .stroke(.quaternary)
                        }
                        .focused($payloadInputFocused)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .accessibilityIdentifier("pairing.payload.input")

                    Button {
                        pasteFromClipboard()
                    } label: {
                        Label("Paste from Clipboard", systemImage: "clipboard")
                    }
                    .buttonStyle(.borderless)
                    .accessibilityIdentifier("pairing.payload.clipboard")

                    Button {
                        decodePayload(model.payloadText)
                    } label: {
                        Label("Review Pairing", systemImage: "arrow.right.circle")
                            .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(model.payloadText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .accessibilityIdentifier("pairing.payload.review")
                }
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
    }

    private func reviewContent(_ review: PairingReview) -> some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            reviewContent(review, now: context.date)
        }
    }

    private func reviewContent(_ review: PairingReview, now: Date) -> some View {
        VStack(alignment: .leading, spacing: 20) {
            VStack(alignment: .leading, spacing: 8) {
                Label("Review this server", systemImage: "checkmark.shield")
                    .font(.title2.weight(.semibold))
                Text("Confirm only if these details match the SkillForge server you expect.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }

            VStack(alignment: .leading, spacing: 16) {
                reviewRow(title: "Server", value: review.serverName, identifier: "pairing.review.server")
                VStack(alignment: .leading, spacing: 8) {
                    Text("Endpoints")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                    ForEach(review.endpointDisplays, id: \.self) { endpoint in
                        Label(endpoint, systemImage: endpoint.hasPrefix("https://") ? "lock.fill" : "network")
                            .font(.body.monospaced())
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .accessibilityIdentifier("pairing.review.endpoints")
                reviewRow(
                    title: "Expiry",
                    value: expiryLabel(review.expiry, now: now),
                    identifier: "pairing.review.expiry"
                )
            }
            .padding(16)
            .background(.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("pairing.review")

            if let error = model.error {
                errorBanner(error)
            }

            Button {
                Task { await confirmPairing() }
            } label: {
                Label("Confirm and Pair", systemImage: "checkmark.shield.fill")
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.borderedProminent)
            .disabled(review.isExpired(at: now))
            .accessibilityIdentifier("pairing.confirm")

            Button("Use a Different Pairing Request") {
                model.startOver()
            }
            .frame(maxWidth: .infinity)
            .accessibilityIdentifier("pairing.startOver")
        }
    }

    private func reviewRow(title: String, value: String, identifier: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            Text(value)
                .font(.body.weight(.medium))
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier(identifier)
    }

    private func progressContent(title: String, detail: String) -> some View {
        VStack(spacing: 16) {
            ProgressView()
                .controlSize(.large)
            Text(title)
                .font(.headline)
            Text(detail)
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 48)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("pairing.progress")
    }

    private func errorBanner(_ error: PairingErrorPresentation) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Pairing needs attention", systemImage: "exclamationmark.triangle.fill")
                .font(.headline)
                .foregroundStyle(.red)
            Text(error.message)
                .font(.callout)
            Button(error.recoveryTitle) {
                recover(from: error)
            }
            .buttonStyle(.bordered)
            .accessibilityIdentifier("pairing.error.recovery")
        }
        .padding(16)
        .background(.red.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("pairing.error")
    }

    private var scannerSheet: some View {
        ZStack(alignment: .topTrailing) {
            QRScannerView(
                onCode: { code in
                    scannerOpen = false
                    decodePayload(code)
                },
                onFailure: {
                    scannerOpen = false
                    model.recordCameraUnavailable()
                }
            )
            .ignoresSafeArea()

            Button {
                scannerOpen = false
            } label: {
                Image(systemName: "xmark")
                    .font(.headline)
                    .frame(width: 44, height: 44)
                    .background(.ultraThinMaterial, in: Circle())
            }
            .padding()
            .accessibilityLabel("Cancel scanning")
            .accessibilityIdentifier("pairing.scanner.cancel")
        }
    }

    private func decodePayload(_ text: String) {
        payloadInputFocused = false
        do {
            try model.decode(text)
        } catch {
            // The model publishes normalized copy and clears the raw input.
        }
    }

    private func pasteFromClipboard() {
        guard let text = UIPasteboard.general.string?.trimmingCharacters(in: .whitespacesAndNewlines),
              !text.isEmpty else {
            model.payloadText = ""
            return
        }
        model.payloadText = text
    }

    private func expiryLabel(_ expiry: PairingReview.Expiry, now: Date) -> String {
        switch expiry {
        case .expired:
            return "Expired"
        case let .valid(until):
            let seconds = max(0, until.timeIntervalSince(now))
            guard seconds > 0 else { return "Expired" }
            let minutes = max(1, Int(ceil(seconds / 60)))
            return minutes == 1 ? "Valid for 1 minute" : "Valid for \(minutes) minutes"
        }
    }

    private func recover(from error: PairingErrorPresentation) {
        switch error.kind {
        case .unreachableEndpoint, .serverUnavailable:
            Task { await confirmPairing() }
        case .expiredPayload, .pairingRejected, .alreadyUsed:
            model.startOver()
            if PairingScannerAvailability.canUseCameraScanner {
                scannerOpen = true
            } else {
                model.isPasteExpanded = true
            }
        case .cameraUnavailable, .invalidPayload, .credentialSaveFailed:
            model.startOver(openPaste: true)
        }
    }

    @MainActor
    private func confirmPairing() async {
        #if DEBUG
        if DebugLaunchConfiguration.isPairingReviewUITest {
            model.markPaired()
            return
        }
        #endif

        guard let context = await model.prepareClaim() else { return }
        do {
            let client = MobileApiClient(baseURL: context.endpoint)
            let response = try await client.claimPairing(
                payload: context.payload,
                deviceName: UIDevice.current.name,
                appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
            )
            model.markSaving()
            let device = MobileDeviceSummary(
                id: response.deviceId,
                deviceName: UIDevice.current.name,
                scopes: ["chat:read", "chat:write"]
            )
            await appState.completePairing(
                endpoint: context.endpoint,
                endpoints: context.endpoints,
                deviceToken: response.deviceToken,
                device: device,
                defaultAgent: response.defaultAgent
            )
            if case .paired = appState.phase {
                model.markPaired()
            } else {
                appState.resetPairing()
                model.recordCredentialSaveFailure()
            }
        } catch {
            model.recordClaimFailure(error)
        }
    }
}
