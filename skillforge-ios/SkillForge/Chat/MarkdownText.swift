import SwiftUI

struct MarkdownBlock: Equatable, Identifiable {
    enum Kind: Equatable {
        case heading
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

            if let heading = headingText(from: trimmed) {
                flushInlineBlocks()
                blocks.append(MarkdownBlock(kind: .heading, text: heading))
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

    private static func headingText(from line: String) -> String? {
        guard line.hasPrefix("#") else { return nil }
        let hashes = line.prefix { $0 == "#" }
        guard hashes.count <= 3 else { return nil }
        let rest = line.dropFirst(hashes.count)
        guard rest.first == " " else { return nil }
        let text = rest.trimmingCharacters(in: .whitespaces)
        return text.isEmpty ? nil : text
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
    private let blocks: [MarkdownBlock]

    init(_ source: String, isStreaming: Bool = false) {
        self.source = source
        self.isStreaming = isStreaming
        self.blocks = MarkdownBlock.parse(source)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { _, block in
                blockView(block)
            }
        }
    }

    @ViewBuilder
    private func blockView(_ block: MarkdownBlock) -> some View {
        switch block.kind {
        case .heading:
            Text(Self.attributedString(from: block.text, isStreaming: isStreaming))
                .font(.headline.weight(.semibold))
                .fixedSize(horizontal: false, vertical: true)
        case .paragraph:
            Text(Self.attributedString(from: block.text, isStreaming: isStreaming))
                .fixedSize(horizontal: false, vertical: true)
        case .unorderedList:
            VStack(alignment: .leading, spacing: 6) {
                ForEach(Array(block.items.enumerated()), id: \.offset) { _, item in
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text("•")
                            .font(.callout.weight(.semibold))
                        Text(Self.attributedString(from: item, isStreaming: isStreaming))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
        case .orderedList:
            VStack(alignment: .leading, spacing: 6) {
                ForEach(Array(block.items.enumerated()), id: \.offset) { index, item in
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text("\(index + 1).")
                            .font(.callout.weight(.semibold))
                            .foregroundStyle(.secondary)
                        Text(Self.attributedString(from: item, isStreaming: isStreaming))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
        case .code:
            ScrollView(.horizontal, showsIndicators: false) {
                Text(block.text)
                    .font(.system(.caption, design: .monospaced))
                    .textSelection(.enabled)
                    .padding(10)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .background(Color.black.opacity(0.06))
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        case .quote:
            Text(Self.attributedString(from: block.text, isStreaming: isStreaming))
                .foregroundStyle(.secondary)
                .padding(.leading, 10)
                .overlay(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 2)
                        .fill(Color.secondary.opacity(0.25))
                        .frame(width: 3)
                }
                .fixedSize(horizontal: false, vertical: true)
        }
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
