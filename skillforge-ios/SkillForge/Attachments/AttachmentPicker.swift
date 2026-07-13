import SwiftUI
import UniformTypeIdentifiers

struct AttachmentPicker: View {
    @State private var importing = false
    let isDisabled: Bool
    let onSelection: (Result<URL, Error>) -> Void

    init(
        isDisabled: Bool = false,
        onSelection: @escaping (Result<URL, Error>) -> Void
    ) {
        self.isDisabled = isDisabled
        self.onSelection = onSelection
    }

    var body: some View {
        Button {
            importing = true
        } label: {
            Image(systemName: "paperclip")
        }
        .disabled(isDisabled)
        .accessibilityLabel("Attach file")
        .accessibilityIdentifier("chat.attachFile")
        .fileImporter(
            isPresented: $importing,
            allowedContentTypes: [.item],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case let .success(urls):
                guard let url = urls.first else { return }
                onSelection(.success(url))
            case let .failure(error):
                onSelection(.failure(error))
            }
        }
    }
}
