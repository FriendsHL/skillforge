import SwiftUI

struct ComposerView: View {
    @Binding var text: String

    let isSending: Bool
    let isUploading: Bool
    let attachments: [MobileUploadedAttachment]
    let focus: FocusState<Bool>.Binding
    let onSelectAttachment: (Result<URL, Error>) -> Void
    let onRemoveAttachment: (String) -> Void
    let onSend: (String, String) -> Void

    var body: some View {
        VStack(spacing: 8) {
            if !attachments.isEmpty || isUploading {
                attachmentStrip
            }

            HStack(alignment: .bottom, spacing: 9) {
                AttachmentPicker(
                    isDisabled: isSending || isUploading,
                    onSelection: onSelectAttachment
                )
                .font(.title3)
                .foregroundStyle(.blue)
                .frame(width: 44, height: 44)
                .background(Color.blue.opacity(0.10))
                .clipShape(Circle())

                HStack(alignment: .bottom, spacing: 8) {
                    TextField("Message SkillForge", text: $text, axis: .vertical)
                        .focused(focus)
                        .accessibilityIdentifier("chat.composer")
                        .textFieldStyle(.plain)
                        .lineLimit(1...4)
                        .submitLabel(.send)
                        .font(.body)
                        .padding(.vertical, 8)
                        .onSubmit(send)

                    Button(action: send) {
                        Group {
                            if isSending {
                                ProgressView()
                                    .controlSize(.small)
                            } else {
                                Image(systemName: "arrow.up")
                                    .font(.headline.weight(.bold))
                            }
                        }
                        .foregroundStyle(.white)
                        .frame(width: 36, height: 36)
                        .background(sendEnabled ? Color.blue : Color.gray.opacity(0.35))
                        .clipShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .frame(width: 44, height: 44)
                    .disabled(!sendEnabled)
                    .accessibilityLabel("Send message")
                    .accessibilityIdentifier("chat.send")
                }
                .padding(.leading, 13)
                .padding(.trailing, 5)
                .padding(.vertical, 5)
                .background(.white)
                .overlay {
                    RoundedRectangle(cornerRadius: 20, style: .continuous)
                        .stroke(Color.black.opacity(0.08), lineWidth: 1)
                }
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, 8)
        .padding(.bottom, 10)
        .background(Color(.systemBackground))
        .overlay(alignment: .top) {
            Rectangle()
                .fill(Color.black.opacity(0.06))
                .frame(height: 1)
        }
    }

    private var attachmentStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(attachments) { attachment in
                    HStack(spacing: 7) {
                        Image(systemName: attachmentIcon(for: attachment))
                            .foregroundStyle(.blue)
                        Text(attachment.filename)
                            .font(.caption)
                            .lineLimit(1)
                        Button {
                            onRemoveAttachment(attachment.id)
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("移除 \(attachment.filename)")
                        .accessibilityIdentifier("chat.attachment.remove.\(attachment.id)")
                    }
                    .padding(.horizontal, 10)
                    .frame(height: 34)
                    .background(Color.blue.opacity(0.08))
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                }

                if isUploading {
                    HStack(spacing: 7) {
                        ProgressView()
                            .controlSize(.small)
                        Text("正在上传")
                            .font(.caption)
                    }
                    .padding(.horizontal, 10)
                    .frame(height: 34)
                    .background(Color(.secondarySystemBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                    .accessibilityIdentifier("chat.attachmentUploading")
                }
            }
        }
        .frame(height: 34)
    }

    private var sendEnabled: Bool {
        ChatComposerPolicy.canSend(
            text: text,
            attachmentCount: attachments.count,
            isSending: isSending,
            isUploading: isUploading
        )
    }

    private func send() {
        guard sendEnabled else { return }
        let submittedDraft = text
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        text = ChatComposerPolicy.draftAfterSendAccepted()
        onSend(trimmed, submittedDraft)
    }

    private func attachmentIcon(for attachment: MobileUploadedAttachment) -> String {
        attachment.kind == "image" ? "photo" : "doc"
    }
}
