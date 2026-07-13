import Foundation

struct ChatAttachment: Identifiable, Equatable, Sendable {
    enum Kind: Equatable, Hashable, Sendable {
        case image
        case pdf
        case word
        case excel
        case csv
        case unknown(String)

        init(blockType: String) {
            switch blockType.lowercased() {
            case "image_ref": self = .image
            case "pdf_ref": self = .pdf
            case "word_ref": self = .word
            case "excel_ref": self = .excel
            case "csv_ref": self = .csv
            default: self = .unknown(blockType)
            }
        }

        var label: String {
            switch self {
            case .image: "Image"
            case .pdf: "PDF"
            case .word: "Word document"
            case .excel: "Excel workbook"
            case .csv: "CSV"
            case .unknown: "Attachment"
            }
        }

        var systemImage: String {
            switch self {
            case .image: "photo"
            case .pdf: "doc.richtext"
            case .word: "doc.text"
            case .excel: "tablecells"
            case .csv: "list.bullet.rectangle"
            case .unknown: "paperclip"
            }
        }
    }

    let id: String
    let kind: Kind
    let mimeType: String?
    let filename: String
    let pageCount: Int?
    let sheetCount: Int?
    let byteSize: Int64?
    let caption: String?

    init(
        id: String,
        kind: Kind,
        mimeType: String? = nil,
        filename: String,
        pageCount: Int? = nil,
        sheetCount: Int? = nil,
        byteSize: Int64? = nil,
        caption: String? = nil
    ) {
        self.id = id
        self.kind = kind
        self.mimeType = mimeType
        self.filename = filename
        self.pageCount = pageCount
        self.sheetCount = sheetCount
        self.byteSize = byteSize
        self.caption = caption
    }

    var detailText: String {
        var details: [String] = [kind.label]
        if let byteSize {
            details.append(ByteCountFormatter.string(fromByteCount: byteSize, countStyle: .file))
        }
        if let pageCount {
            details.append("\(pageCount) pages")
        } else if let sheetCount {
            details.append("\(sheetCount) sheets")
        }
        return details.joined(separator: " · ")
    }
}
