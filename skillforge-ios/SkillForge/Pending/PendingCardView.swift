import SwiftUI

struct PendingCardView: View {
    let interaction: PendingInteraction
    let isSubmitting: Bool
    let errorText: String?
    let onAnswer: (String) -> Void
    let onDecision: (MobileConfirmationDecision) -> Void

    @State private var answerText = ""
    @FocusState private var answerFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Label(cardLabel, systemImage: cardIcon)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)

            Text(interaction.question)
                .font(.headline)
                .fixedSize(horizontal: false, vertical: true)

            if let context = interaction.context, !context.isEmpty {
                Text(context)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if interaction.kind == .ask {
                askControls
            } else {
                confirmationControls
            }

            if let errorText, !errorText.isEmpty {
                Label(errorText, systemImage: "exclamationmark.circle")
                    .font(.footnote)
                    .foregroundStyle(.red)
            }
        }
        .padding(16)
        .background(Color(.secondarySystemBackground))
        .overlay {
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .stroke(Color.orange.opacity(0.45), lineWidth: 1)
        }
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
    }

    private var askControls: some View {
        VStack(spacing: 10) {
            ForEach(interaction.options) { option in
                Button {
                    onAnswer(option.label)
                } label: {
                    HStack(alignment: .top, spacing: 10) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(option.label)
                                .font(.subheadline.weight(.semibold))
                            if let description = option.description, !description.isEmpty {
                                Text(description)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(12)
                    .background(.background)
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                }
                .buttonStyle(.plain)
                .disabled(isSubmitting)
                .accessibilityIdentifier("pending.option.\(interaction.id).\(option.label)")
            }

            if interaction.allowOther {
                HStack(spacing: 8) {
                    TextField("输入回答", text: $answerText, axis: .vertical)
                        .focused($answerFocused)
                        .lineLimit(1...3)
                        .textFieldStyle(.plain)
                        .padding(.horizontal, 11)
                        .padding(.vertical, 9)
                        .background(.background)
                        .overlay {
                            RoundedRectangle(cornerRadius: 8, style: .continuous)
                                .stroke(Color.black.opacity(0.10), lineWidth: 1)
                        }
                        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                        .accessibilityIdentifier("pending.answer.\(interaction.id)")

                    Button {
                        let answer = answerText.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard !answer.isEmpty else { return }
                        answerFocused = false
                        onAnswer(answer)
                    } label: {
                        submissionLabel(icon: "arrow.up")
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(isSubmitting || answerText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .accessibilityLabel("提交回答")
                    .accessibilityIdentifier("pending.submit.\(interaction.id)")
                }
            }
        }
    }

    private var confirmationControls: some View {
        HStack(spacing: 10) {
            Button {
                onDecision(.approved)
            } label: {
                submissionLabel(title: "批准", icon: "checkmark")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(isSubmitting)
            .accessibilityIdentifier("pending.approve.\(interaction.id)")

            Button(role: .destructive) {
                onDecision(.denied)
            } label: {
                submissionLabel(title: "拒绝", icon: "xmark")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .disabled(isSubmitting)
            .accessibilityIdentifier("pending.deny.\(interaction.id)")
        }
    }

    @ViewBuilder
    private func submissionLabel(title: String? = nil, icon: String) -> some View {
        if isSubmitting {
            ProgressView()
                .controlSize(.small)
        } else if let title {
            Label(title, systemImage: icon)
        } else {
            Image(systemName: icon)
        }
    }

    private var cardLabel: String {
        interaction.kind == .ask ? "需要你的回答" : "需要你的确认"
    }

    private var cardIcon: String {
        interaction.kind == .ask ? "questionmark.bubble" : "checkmark.shield"
    }
}
