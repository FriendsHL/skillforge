import AVKit
import SwiftUI

struct MediaJobCardView: View {
    let reference: ChatMessage.MediaJobRef
    let sessionID: String
    let client: MobileApiClient
    @ObservedObject var attachmentStore: AttachmentDownloadStore
    let onUnauthorized: @MainActor () -> Void

    @State private var job: MobileMediaJob?
    @State private var error: String?
    @State private var player: AVPlayer?
    @State private var retainedAttachment: ChatAttachment?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Image(systemName: "video.fill")
                Text("Generated video").font(.subheadline.weight(.semibold))
                Spacer()
                Text(job?.status.lowercased() ?? "loading")
                    .font(.caption.weight(.medium)).foregroundStyle(.secondary)
            }
            if let job { Text("\(job.provider) · \(job.model)").font(.caption).foregroundStyle(.secondary) }
            if let player { VideoPlayer(player: player).aspectRatio(16 / 9, contentMode: .fit).clipShape(RoundedRectangle(cornerRadius: 8)) }
            else if let error { Label(error, systemImage: "exclamationmark.triangle").font(.caption).foregroundStyle(.red) }
            else { ProgressView().frame(maxWidth: .infinity, minHeight: 80) }
            if let job, !Self.terminalStatuses.contains(job.status) {
                Button("Cancel") { Task { await cancel() } }.buttonStyle(.bordered)
            }
        }
        .padding(12)
        .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 10))
        .accessibilityIdentifier("media.job.\(reference.id)")
        .task(id: reference.id) { await pollUntilTerminal() }
        .onDisappear {
            player?.pause(); player = nil
            if let retainedAttachment { attachmentStore.release(retainedAttachment) }
            retainedAttachment = nil
        }
    }

    private func pollUntilTerminal() async {
        while !Task.isCancelled {
            do {
                let current = try await client.getMediaJob(sessionId: sessionID, jobId: reference.id)
                job = current
                if current.status == "READY", let attachmentID = current.resultAttachmentId {
                    let attachment = ChatAttachment(id: attachmentID, kind: .video, mimeType: "video/mp4", filename: "generated-video.mp4")
                    retainedAttachment = attachment
                    attachmentStore.retain(attachment)
                    attachmentStore.load(sessionID: sessionID, attachment: attachment, onUnauthorized: onUnauthorized)
                    for _ in 0..<100 {
                        if case let .available(url) = attachmentStore.state(for: attachment) { player = AVPlayer(url: url); return }
                        try await Task.sleep(for: .milliseconds(100))
                    }
                    error = "Video download timed out"; return
                }
                if Self.terminalStatuses.contains(current.status) {
                    error = current.errorMessage ?? "Video generation failed"; return
                }
                try await Task.sleep(for: .seconds(5))
            } catch is CancellationError { return }
            catch { self.error = error.localizedDescription; return }
        }
    }

    private func cancel() async {
        do { job = try await client.cancelMediaJob(sessionId: sessionID, jobId: reference.id) }
        catch { self.error = error.localizedDescription }
    }

    private static let terminalStatuses = Set(["READY", "SUBMIT_FAILED", "SUBMIT_UNKNOWN", "GENERATION_FAILED",
                                               "DOWNLOAD_FAILED", "PROCESSING_FAILED", "CANCELLED", "EXPIRED"])
}
