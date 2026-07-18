import SwiftUI
import UIKit

enum MarkdownPresentationStyle {
    case compact
    case assistantResult
}

@MainActor
enum MarkdownCodeCopyAction {
    static func perform(
        _ source: String,
        write: (String) -> Void = { UIPasteboard.general.string = $0 }
    ) {
        write(source)
    }
}

struct MarkdownBlock: Equatable, Identifiable {
    enum Kind: Equatable {
        case heading(level: Int)
        case paragraph
        case unorderedList
        case orderedList
        case code
        case quote
    }

    let id = UUID()
    let kind: Kind
    let text: String
    let items: [String]
    let language: String?

    init(kind: Kind, text: String = "", items: [String] = [], language: String? = nil) {
        self.kind = kind
        self.text = text
        self.items = items
        self.language = language
    }

    static func parse(_ source: String) -> [MarkdownBlock] {
        let lines = source.replacingOccurrences(of: "\r\n", with: "\n").components(separatedBy: "\n")
        var blocks: [MarkdownBlock] = []
        var paragraph: [String] = []
        var unorderedItems: [String] = []
        var orderedItems: [String] = []
        var quoteLines: [String] = []
        var codeLines: [String] = []
        var codeLanguage: String?
        var inCode = false

        func flushParagraph() {
            let text = paragraph.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
            if !text.isEmpty {
                blocks.append(MarkdownBlock(kind: .paragraph, text: text))
            }
            paragraph.removeAll()
        }

        func flushUnordered() {
            guard !unorderedItems.isEmpty else { return }
            blocks.append(MarkdownBlock(kind: .unorderedList, items: unorderedItems))
            unorderedItems.removeAll()
        }

        func flushOrdered() {
            guard !orderedItems.isEmpty else { return }
            blocks.append(MarkdownBlock(kind: .orderedList, items: orderedItems))
            orderedItems.removeAll()
        }

        func flushQuote() {
            let text = quoteLines.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
            if !text.isEmpty {
                blocks.append(MarkdownBlock(kind: .quote, text: text))
            }
            quoteLines.removeAll()
        }

        func flushInlineBlocks() {
            flushParagraph()
            flushUnordered()
            flushOrdered()
            flushQuote()
        }

        for rawLine in lines {
            let trimmed = rawLine.trimmingCharacters(in: .whitespaces)

            if trimmed.hasPrefix("```") {
                if inCode {
                    blocks.append(MarkdownBlock(
                        kind: .code,
                        text: codeLines.joined(separator: "\n").trimmingCharacters(in: .newlines),
                        language: codeLanguage
                    ))
                    codeLines.removeAll()
                    codeLanguage = nil
                    inCode = false
                } else {
                    flushInlineBlocks()
                    inCode = true
                    let language = String(trimmed.dropFirst(3)).trimmingCharacters(in: .whitespaces)
                    codeLanguage = language.isEmpty ? nil : language
                }
                continue
            }

            if inCode {
                codeLines.append(rawLine)
                continue
            }

            if trimmed.isEmpty {
                flushInlineBlocks()
                continue
            }

            if let heading = heading(from: trimmed) {
                flushInlineBlocks()
                blocks.append(MarkdownBlock(
                    kind: .heading(level: heading.level),
                    text: heading.text
                ))
                continue
            }

            if let item = unorderedItem(from: trimmed) {
                flushParagraph()
                flushOrdered()
                flushQuote()
                unorderedItems.append(item)
                continue
            }

            if let item = orderedItem(from: trimmed) {
                flushParagraph()
                flushUnordered()
                flushQuote()
                orderedItems.append(item)
                continue
            }

            if trimmed.hasPrefix(">") {
                flushParagraph()
                flushUnordered()
                flushOrdered()
                quoteLines.append(String(trimmed.dropFirst()).trimmingCharacters(in: .whitespaces))
                continue
            }

            flushUnordered()
            flushOrdered()
            flushQuote()
            paragraph.append(rawLine)
        }

        if inCode {
            blocks.append(MarkdownBlock(
                kind: .code,
                text: codeLines.joined(separator: "\n").trimmingCharacters(in: .newlines),
                language: codeLanguage
            ))
        } else {
            flushInlineBlocks()
        }

        return blocks
    }

    private static func heading(from line: String) -> (level: Int, text: String)? {
        guard line.hasPrefix("#") else { return nil }
        let hashes = line.prefix { $0 == "#" }
        guard hashes.count <= 3 else { return nil }
        let rest = line.dropFirst(hashes.count)
        guard rest.first == " " else { return nil }
        let text = rest.trimmingCharacters(in: .whitespaces)
        return text.isEmpty ? nil : (hashes.count, text)
    }

    private static func unorderedItem(from line: String) -> String? {
        guard line.hasPrefix("- ") || line.hasPrefix("* ") else { return nil }
        let text = String(line.dropFirst(2)).trimmingCharacters(in: .whitespaces)
        return text.isEmpty ? nil : text
    }

    private static func orderedItem(from line: String) -> String? {
        guard let dotIndex = line.firstIndex(of: ".") else { return nil }
        let prefix = line[..<dotIndex]
        guard !prefix.isEmpty, prefix.allSatisfy(\.isNumber) else { return nil }
        let afterDot = line[line.index(after: dotIndex)...]
        guard afterDot.first == " " else { return nil }
        let text = afterDot.trimmingCharacters(in: .whitespaces)
        return text.isEmpty ? nil : text
    }
}

struct MarkdownText: View {
    let source: String
    let isStreaming: Bool
    let presentation: MarkdownPresentationStyle
    private let blocks: [MarkdownBlock]
    private var accessibilityIdentifierPrefix = "chat.markdown"
    @State private var copiedCodeBlockIndex: Int?

    init(
        _ source: String,
        isStreaming: Bool = false,
        presentation: MarkdownPresentationStyle = .compact
    ) {
        self.source = source
        self.isStreaming = isStreaming
        self.presentation = presentation
        self.blocks = MarkdownBlock.parse(source)
    }

    func accessibilityPrefix(_ prefix: String) -> Self {
        var copy = self
        copy.accessibilityIdentifierPrefix = prefix
        return copy
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { index, block in
                blockView(block, index: index)
            }
        }
    }

    @ViewBuilder
    private func blockView(_ block: MarkdownBlock, index: Int) -> some View {
        switch block.kind {
        case .heading(let level):
            Text(Self.attributedString(from: block.text, isStreaming: isStreaming))
                .font(headingFont(for: level))
                .padding(.top, headingTopPadding(level: level, index: index))
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("\(accessibilityIdentifierPrefix).heading.\(index)")
        case .paragraph:
            Text(Self.attributedString(from: block.text, isStreaming: isStreaming))
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("\(accessibilityIdentifierPrefix).paragraph.\(index)")
        case .unorderedList:
            VStack(alignment: .leading, spacing: 6) {
                ForEach(Array(block.items.enumerated()), id: \.offset) { _, item in
                    HStack(alignment: .top, spacing: 10) {
                        Circle()
                            .fill(listAccent.opacity(presentation == .assistantResult ? 1 : 0.65))
                            .frame(width: 6, height: 6)
                            .padding(.top, 7)
                        Text(Self.attributedString(from: item, isStreaming: isStreaming))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("\(accessibilityIdentifierPrefix).unorderedList.\(index)")
        case .orderedList:
            VStack(alignment: .leading, spacing: 6) {
                ForEach(Array(block.items.enumerated()), id: \.offset) { index, item in
                    HStack(alignment: .top, spacing: 10) {
                        Text("\(index + 1)")
                            .font(.caption.weight(.bold))
                            .foregroundStyle(listAccent)
                            .frame(minWidth: 25, minHeight: 25)
                            .background(listAccent.opacity(0.12))
                            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                        Text(Self.attributedString(from: item, isStreaming: isStreaming))
                            .padding(.top, 2)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("\(accessibilityIdentifierPrefix).orderedList.\(index)")
        case .code:
            VStack(alignment: .leading, spacing: 0) {
                if presentation == .assistantResult {
                    HStack(spacing: 8) {
                        Text(codeLanguageLabel(block.language))
                            .font(.caption2.weight(.semibold).monospaced())
                            .foregroundStyle(Color(red: 0.56, green: 0.62, blue: 0.70))
                            .lineLimit(1)
                            .truncationMode(.tail)
                            .accessibilityIdentifier(
                                "\(accessibilityIdentifierPrefix).codeLanguage.\(index)"
                            )
                        Spacer(minLength: 8)
                        Button {
                            MarkdownCodeCopyAction.perform(block.text)
                            copiedCodeBlockIndex = index
                        } label: {
                            Label(
                                copiedCodeBlockIndex == index ? "已复制" : "复制",
                                systemImage: copiedCodeBlockIndex == index ? "checkmark" : "doc.on.doc"
                            )
                                .font(.caption.weight(.semibold))
                                .lineLimit(1)
                                .padding(.horizontal, 10)
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(Color(red: 0.78, green: 0.82, blue: 0.88))
                        .frame(minWidth: 44, minHeight: 44)
                        .background(Color.white.opacity(0.08))
                        .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
                        .contentShape(Rectangle())
                        .accessibilityLabel(
                            copyAccessibilityLabel(
                                for: block.language,
                                didCopy: copiedCodeBlockIndex == index
                            )
                        )
                        .accessibilityIdentifier(
                            "\(accessibilityIdentifierPrefix).codeCopy.\(index)"
                        )
                    }
                    .padding(.leading, 12)
                    .padding(.trailing, 7)
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)

                    Divider()
                        .overlay(Color.white.opacity(0.08))
                }

                ScrollView(.horizontal, showsIndicators: false) {
                    Text(block.text)
                        .font(.system(.caption, design: .monospaced))
                        .foregroundStyle(Color(red: 0.89, green: 0.92, blue: 0.96))
                        .textSelection(.enabled)
                        .padding(12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .background(Color(red: 0.09, green: 0.11, blue: 0.15))
            .overlay {
                RoundedRectangle(cornerRadius: 13, style: .continuous)
                    .stroke(Color(red: 0.16, green: 0.18, blue: 0.24), lineWidth: 1)
            }
            .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("\(accessibilityIdentifierPrefix).code.\(index)")
        case .quote:
            Text(Self.attributedString(from: block.text, isStreaming: isStreaming))
                .foregroundStyle(.secondary)
                .padding(.horizontal, 12)
                .padding(.vertical, presentation == .assistantResult ? 10 : 2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background {
                    if presentation == .assistantResult {
                        Color(uiColor: .secondarySystemGroupedBackground)
                    }
                }
                .overlay(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 2)
                        .fill(listAccent.opacity(presentation == .assistantResult ? 0.55 : 0.25))
                        .frame(width: 3)
                }
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("\(accessibilityIdentifierPrefix).quote.\(index)")
        }
    }

    private func headingFont(for level: Int) -> Font {
        switch level {
        case 1:
            return .title2.weight(.bold)
        case 2:
            return .title3.weight(.bold)
        default:
            return .headline.weight(.semibold)
        }
    }

    private func headingTopPadding(level: Int, index: Int) -> CGFloat {
        guard index > 0 else { return 0 }
        return level == 1 ? 8 : 4
    }

    private var listAccent: Color {
        Color(red: 0.36, green: 0.56, blue: 0.85)
    }

    private func codeLanguageLabel(_ language: String?) -> String {
        language?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() ?? "CODE"
    }

    private func copyAccessibilityLabel(for language: String?, didCopy: Bool) -> String {
        let action = didCopy ? "已复制" : "复制"
        guard let language, !language.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return "\(action)代码"
        }
        return "\(action) \(language) 代码"
    }

    nonisolated static func attributedString(from source: String, isStreaming: Bool = false) -> AttributedString {
        let options = AttributedString.MarkdownParsingOptions(interpretedSyntax: .full)
        let parseSource = isStreaming ? repairPartialInlineMarkdown(source) : source
        return (try? AttributedString(markdown: parseSource, options: options)) ?? AttributedString(parseSource)
    }

    nonisolated private static func repairPartialInlineMarkdown(_ source: String) -> String {
        var result = source
        for delimiter in ["**", "__", "`"] {
            result = removingLastUnmatchedDelimiter(delimiter, from: result)
        }
        return result
    }

    nonisolated private static func removingLastUnmatchedDelimiter(_ delimiter: String, from source: String) -> String {
        let ranges = delimiterRanges(of: delimiter, in: source)
        guard ranges.count % 2 == 1, let lastRange = ranges.last else {
            return source
        }
        var result = source
        result.removeSubrange(lastRange)
        return result
    }

    nonisolated private static func delimiterRanges(of delimiter: String, in source: String) -> [Range<String.Index>] {
        var ranges: [Range<String.Index>] = []
        var searchStart = source.startIndex
        while searchStart < source.endIndex,
              let range = source.range(of: delimiter, range: searchStart..<source.endIndex) {
            ranges.append(range)
            searchStart = range.upperBound
        }
        return ranges
    }
}
