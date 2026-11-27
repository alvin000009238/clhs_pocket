import Foundation
import SwiftUI

/// Announcement HTML reduced to a whitelisted tree: paragraphs, line breaks, list items, bold,
/// italic, underline and http(s) links. Everything else keeps only its text, and script/style
/// contents are dropped, so no HTML engine ever sees the school's markup.
indirect enum AnnouncementNode: Equatable {
    enum Style: Equatable { case bold, italic, underline }

    case text(String)
    case lineBreak
    case paragraph([AnnouncementNode])
    case listItem([AnnouncementNode])
    case styled(Style, [AnnouncementNode])
    case link(URL, [AnnouncementNode])
}

enum AnnouncementMarkup {
    static func parse(_ html: String) -> [AnnouncementNode] {
        var parser = Parser(html)
        return parser.nodes(until: nil)
    }

    /// The body as SwiftUI text; nil when nothing readable is left.
    static func attributed(html: String) -> AttributedString? {
        var blocks: [(text: AttributedString, isItem: Bool)] = []
        collect(parse(html), listDepth: 0, bullet: false, into: &blocks)
        guard !blocks.isEmpty else { return nil }
        var result = AttributedString()
        for (index, block) in blocks.enumerated() {
            if index > 0 { result += AttributedString(block.isItem && blocks[index - 1].isItem ? "\n" : "\n\n") }
            result += block.text
        }
        result.foregroundColor = PocketColor.onSurface
        for run in result.runs where run.link != nil {
            result[run.range].foregroundColor = PocketColor.primary
            result[run.range].underlineStyle = .single
        }
        return result
    }

    /// Paragraphs and list items as separate blocks; a nested item is indented one step per level and the
    /// text after a nested list continues its item without a second bullet.
    private static func collect(
        _ nodes: [AnnouncementNode], listDepth: Int, bullet: Bool,
        into blocks: inout [(text: AttributedString, isItem: Bool)]
    ) {
        var inline = AttributedString()
        var bullet = bullet
        func flush() {
            defer { inline = AttributedString() }
            guard !inline.characters.allSatisfy(\.isWhitespace) else { return }
            var text = trimmed(inline)
            if listDepth > 0 {
                let indent = String(repeating: "\u{2003}", count: listDepth - 1)
                text = AttributedString(indent + (bullet ? (listDepth == 1 ? "• " : "◦ ") : "\u{2003}")) + text
            }
            blocks.append((text, listDepth > 0))
            bullet = false
        }
        for node in nodes {
            switch node {
            case let .paragraph(children):
                flush()
                let count = blocks.count
                collect(children, listDepth: listDepth, bullet: bullet, into: &blocks)
                if blocks.count > count { bullet = false }
            case let .listItem(children):
                flush()
                collect(children, listDepth: listDepth + 1, bullet: true, into: &blocks)
            default:
                inline += render([node])
            }
        }
        flush()
    }

    private static func render(_ nodes: [AnnouncementNode]) -> AttributedString {
        var out = AttributedString()
        for node in nodes {
            switch node {
            case let .text(text):
                out += AttributedString(text)
            case .lineBreak:
                out += AttributedString("\n")
            case let .paragraph(children), let .listItem(children):
                if !out.characters.isEmpty, out.characters.last != "\n" { out += AttributedString("\n") }
                out += render(children)
                out += AttributedString("\n")
            case let .styled(style, children):
                var inner = render(children)
                switch style {
                case .bold: inner.inlinePresentationIntent = (inner.inlinePresentationIntent ?? []).union(.stronglyEmphasized)
                case .italic: inner.inlinePresentationIntent = (inner.inlinePresentationIntent ?? []).union(.emphasized)
                case .underline: inner.underlineStyle = .single
                }
                out += inner
            case let .link(url, children):
                var inner = render(children)
                inner.link = url
                out += inner
            }
        }
        return out
    }

    private static func trimmed(_ text: AttributedString) -> AttributedString {
        var text = text
        while let first = text.characters.first, first.isWhitespace {
            text.removeSubrange(text.startIndex..<text.characters.index(after: text.startIndex))
        }
        while let last = text.characters.last, last.isWhitespace {
            text.removeSubrange(text.characters.index(before: text.endIndex)..<text.endIndex)
        }
        return text
    }

    private struct Parser {
        private let chars: [Character]
        private var index = 0

        init(_ html: String) { chars = Array(html) }

        private static let dropped: Set<String> = ["script", "style", "head", "title", "noscript", "template", "iframe", "object"]
        private static let blocks: Set<String> = ["p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "table", "tr"]
        private static let voids: Set<String> = ["br", "img", "hr", "input", "meta", "link", "wbr", "col", "source"]

        /// Deeper tags are flattened into their parent, so hostile nesting cannot overflow the stack.
        static let maxDepth = 32

        mutating func nodes(until closing: String?, depth: Int = 0) -> [AnnouncementNode] {
            var result: [AnnouncementNode] = []
            var text = ""
            // Tags opened past the depth limit, by name, so their closing tags are consumed here
            // instead of ending the enclosing element early.
            var skipped: [String: Int] = [:]
            func flushText() {
                let collapsed = text.replacingOccurrences(of: "[ \\t\\r\\n\\u{00A0}]+", with: " ", options: .regularExpression)
                if !collapsed.isEmpty { result.append(.text(Entities.decode(collapsed))) }
                text = ""
            }
            while index < chars.count {
                guard chars[index] == "<", let tag = readTag() else {
                    text.append(chars[index])
                    index += 1
                    continue
                }
                if tag.isClosing {
                    if let count = skipped[tag.name], count > 0 {
                        skipped[tag.name] = count - 1
                        continue
                    }
                    if tag.name == closing { flushText(); return result }
                    if Self.blocks.contains(tag.name) || tag.name == "li" {
                        // An unmatched closing block tag ends the current inline run.
                        flushText()
                    }
                    continue
                }
                flushText()
                if Self.dropped.contains(tag.name) {
                    skip(until: tag.name)
                    continue
                }
                if tag.name == "br" { result.append(.lineBreak); continue }
                if Self.voids.contains(tag.name) || tag.isSelfClosing { continue }
                if depth >= Self.maxDepth {
                    skipped[tag.name, default: 0] += 1
                    continue
                }
                let children = nodes(until: tag.name, depth: depth + 1)
                switch tag.name {
                case "li": result.append(.listItem(children))
                case _ where Self.blocks.contains(tag.name): result.append(.paragraph(children))
                case "b", "strong": result.append(.styled(.bold, children))
                case "i", "em": result.append(.styled(.italic, children))
                case "u", "ins": result.append(.styled(.underline, children))
                case "a":
                    if let href = tag.href.map(Entities.decode).flatMap({ URL(string: $0.trimmingCharacters(in: .whitespaces)) }),
                       let scheme = href.scheme?.lowercased(), scheme == "https" || scheme == "http" {
                        result.append(.link(href, children))
                    } else {
                        result.append(contentsOf: children)
                    }
                default: result.append(contentsOf: children)
                }
            }
            flushText()
            return result
        }

        private struct Tag {
            let name: String
            let isClosing: Bool
            let isSelfClosing: Bool
            let href: String?
        }

        /// Reads `<...>` at the cursor; comments and declarations are consumed and return nil.
        private mutating func readTag() -> Tag? {
            let start = index
            if matches("<!--") {
                index += 4
                while index < chars.count, !matches("-->") { index += 1 }
                index = min(index + 3, chars.count)
                return Tag(name: "", isClosing: false, isSelfClosing: true, href: nil)
            }
            // "a < b" is text: a tag starts with a letter, "/" or "!" right after the "<".
            guard index + 1 < chars.count, chars[index + 1].isLetter || chars[index + 1] == "/" || chars[index + 1] == "!" else {
                return nil
            }
            var end = index + 1
            var quote: Character?
            while end < chars.count {
                let c = chars[end]
                if let open = quote { if c == open { quote = nil } } else if c == "\"" || c == "'" { quote = c } else if c == ">" { break }
                end += 1
            }
            guard end < chars.count else { return nil }
            let body = String(chars[(start + 1)..<end])
            index = end + 1
            let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.hasPrefix("!") || trimmed.hasPrefix("?") {
                return Tag(name: "", isClosing: false, isSelfClosing: true, href: nil)
            }
            let isClosing = trimmed.hasPrefix("/")
            let rest = isClosing ? String(trimmed.dropFirst()) : trimmed
            let name = String(rest.prefix { $0.isLetter || $0.isNumber }).lowercased()
            guard !name.isEmpty, name.first?.isLetter == true else {
                index = start
                return nil
            }
            return Tag(name: name, isClosing: isClosing, isSelfClosing: rest.hasSuffix("/"), href: name == "a" ? Self.attribute("href", in: rest) : nil)
        }

        private mutating func skip(until name: String) {
            while index < chars.count {
                if chars[index] == "<", let tag = readTag() {
                    if tag.isClosing, tag.name == name { return }
                    continue
                }
                index += 1
            }
        }

        private func matches(_ literal: String) -> Bool {
            let needle = Array(literal)
            guard index + needle.count <= chars.count else { return false }
            return Array(chars[index..<(index + needle.count)]) == needle
        }

        private static func attribute(_ name: String, in tag: String) -> String? {
            let pattern = "(?i)(?:^|\\s)\(name)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))"
            guard let regex = try? NSRegularExpression(pattern: pattern),
                  let match = regex.firstMatch(in: tag, range: NSRange(tag.startIndex..., in: tag)) else { return nil }
            for group in 1...3 {
                if let range = Range(match.range(at: group), in: tag) { return String(tag[range]) }
            }
            return nil
        }
    }

    private enum Entities {
        private static let named: [String: String] = [
            "amp": "&", "lt": "<", "gt": ">", "quot": "\"", "apos": "'", "nbsp": " ", "#39": "'",
            "ensp": " ", "emsp": " ", "middot": "·", "hellip": "…", "ndash": "–", "mdash": "—", "times": "×",
            "ldquo": "“", "rdquo": "”", "lsquo": "‘", "rsquo": "’", "bull": "•", "copy": "©",
        ]

        static func decode(_ text: String) -> String {
            guard text.contains("&") else { return text }
            var out = ""
            var rest = Substring(text)
            while let amp = rest.firstIndex(of: "&") {
                out += rest[..<amp]
                let after = rest[rest.index(after: amp)...]
                if let semi = after.prefix(12).firstIndex(of: ";"), let value = value(of: String(after[..<semi])) {
                    out += value
                    rest = after[after.index(after: semi)...]
                } else {
                    out += "&"
                    rest = after
                }
            }
            return out + rest
        }

        private static func value(of entity: String) -> String? {
            if let named = named[entity.lowercased()] { return named }
            guard entity.hasPrefix("#") else { return nil }
            let digits = entity.dropFirst()
            let code = digits.first == "x" || digits.first == "X" ? UInt32(digits.dropFirst(), radix: 16) : UInt32(digits)
            guard let code, code != 0, let scalar = Unicode.Scalar(code) else { return nil }
            return String(Character(scalar))
        }
    }
}
