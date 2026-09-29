import Foundation

/// Finds cooking durations in text ("simmer for 45 minutes", "1 hr 15 min", "10-12 min")
/// so steps can offer one-tap timers and metadata can be normalized.
enum DurationParser {
    struct Match: Equatable {
        /// UTF-16 range in the source string (NSRange semantics, shared with Android offsets).
        let location: Int
        let length: Int
        /// Lower bound in seconds; timers start from this.
        let seconds: Int
        /// Upper bound for ranges like "10-12 minutes".
        let upperSeconds: Int?
        /// Short label such as "45 min" or "10–12 min".
        let label: String
    }

    /// Longer durations are misread text (1000 hours).
    private static let maxSeconds = 3_600_000.0

    private static let number = #"(?:\d+\s+\d+/\d+|\d+/\d+|\d+(?:[.,]\d+)?(?:\s?[½⅓⅔¼¾⅛⅜⅝⅞])?|[½⅓⅔¼¾⅛⅜⅝⅞]|an?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty|fifty|sixty|ninety)"#
    private static let unit = #"(hours?|hrs?\.?|minutes?|mins?\.?|seconds?|secs?\.?)"#

    // "10-12 minutes", "1 1/2 hours", "an hour", "45-minute", "20 min."
    private static let spelledOut = TextKit.regex(
        #"(?<![\w/.])("# + number + #")(?:\s*(?:-|–|—|to|or)\s*("# + number + #"))?\s*-?\s*"# + unit + #"(?![a-z])"#
    )
    // Compact forms: "1h", "1h30", "1h 30m", "30m", "45s".
    private static let compact = TextKit.regex(#"(?<![\w.])(\d+)\s?h(?:\s?(\d{1,2})\s?m?)?(?![a-z])|(?<![\w.])(\d{1,3})(m|s)(?![a-z])"#)
    private static let halfHour = TextKit.regex(#"\b(?:half an? hour|a half hour|half-hour)\b"#)
    private static let hourAndHalf = TextKit.regex(#"\b(an?|one)\s+hour\s+and\s+a\s+half\b"#)

    /// Every duration in the text, in order, with adjacent "1 hour 15 minutes" merged.
    static func matches(in text: String) -> [Match] {
        let ns = text as NSString
        var found: [Match] = []

        for m in TextKit.matches(hourAndHalf, in: text) {
            found.append(Match(location: m.range.location, length: m.range.length, seconds: 5400, upperSeconds: nil, label: "1 hr 30 min"))
        }
        for m in TextKit.matches(halfHour, in: text) where !overlaps(m.range, found) {
            found.append(Match(location: m.range.location, length: m.range.length, seconds: 1800, upperSeconds: nil, label: "30 min"))
        }
        for m in TextKit.matches(spelledOut, in: text) where !overlaps(m.range, found) {
            guard let lowText = TextKit.group(m, 1, in: text),
                  let unitText = TextKit.group(m, 3, in: text),
                  let low = TextKit.numberValue(lowText) else { continue }
            let multiplier = unitSeconds(unitText)
            var high: Double?
            if let highText = TextKit.group(m, 2, in: text) { high = TextKit.numberValue(highText) }
            // "a" and "an" only count as one when right next to the unit ("an hour"), not "a few minutes".
            if lowText.lowercased().hasPrefix("a"), high != nil { continue }
            guard low * multiplier <= maxSeconds else { continue }
            let lowSeconds = Int((low * multiplier).rounded())
            let highSeconds = high.flatMap { $0 * multiplier <= maxSeconds ? Int(($0 * multiplier).rounded()) : nil }
            guard lowSeconds > 0 else { continue }
            found.append(Match(
                location: m.range.location, length: m.range.length,
                seconds: lowSeconds, upperSeconds: highSeconds,
                label: label(low: lowSeconds, high: highSeconds)
            ))
        }
        for m in TextKit.matches(compact, in: text) where !overlaps(m.range, found) {
            var seconds = 0
            if let h = TextKit.group(m, 1, in: text), let hours = Int(h), hours <= 1000 {
                seconds = hours * 3600
                if let mm = TextKit.group(m, 2, in: text), let minutes = Int(mm) { seconds += minutes * 60 }
            } else if let v = TextKit.group(m, 3, in: text), let value = Int(v), let u = TextKit.group(m, 4, in: text) {
                seconds = u.lowercased() == "m" ? value * 60 : value
            }
            guard seconds > 0 else { continue }
            found.append(Match(location: m.range.location, length: m.range.length, seconds: seconds, upperSeconds: nil, label: label(low: seconds, high: nil)))
        }

        found.sort { $0.location < $1.location }
        return mergeCompound(found, in: ns)
    }

    /// Minutes for the first duration in the text, or nil.
    static func minutes(in text: String) -> Int? {
        guard !text.isEmpty, let first = matches(in: text).first else {
            // A bare number in a time field ("45") means minutes.
            let trimmed = text.trimmingCharacters(in: .whitespaces)
            if let n = Int(trimmed), n > 0 { return n }
            return nil
        }
        return max(1, Int((Double(first.seconds) / 60).rounded()))
    }

    /// Normalizes time metadata: "1 hour 30 minutes" → "1 hr 30 min". Unparseable text is kept.
    static func normalized(_ text: String) -> String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let found = matches(in: trimmed)
        guard found.count == 1, let only = found.first else { return trimmed }
        return only.label
    }

    static func format(minutes: Int) -> String {
        if minutes < 60 { return "\(minutes) min" }
        let h = minutes / 60, m = minutes % 60
        return m == 0 ? "\(h) hr" : "\(h) hr \(m) min"
    }

    static func format(seconds: Int) -> String {
        if seconds < 60 { return "\(seconds) sec" }
        if seconds % 60 == 0 { return format(minutes: seconds / 60) }
        let m = seconds / 60, s = seconds % 60
        return m < 60 ? "\(m) min \(s) sec" : format(minutes: m)
    }

    /// Countdown text such as "4:05" or "1:02:30".
    static func clock(_ seconds: Int) -> String {
        let s = max(0, seconds)
        let h = s / 3600, m = (s % 3600) / 60, sec = s % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, sec) : String(format: "%d:%02d", m, sec)
    }

    private static func label(low: Int, high: Int?) -> String {
        guard let high, high > low else { return format(seconds: low) }
        if low % 3600 == 0 && high % 3600 == 0 { return "\(low / 3600)–\(high / 3600) hr" }
        if low % 60 == 0 && high % 60 == 0 && high < 3600 { return "\(low / 60)–\(high / 60) min" }
        return "\(format(seconds: low))–\(format(seconds: high))"
    }

    private static func unitSeconds(_ unit: String) -> Double {
        let u = unit.lowercased()
        if u.hasPrefix("h") { return 3600 }
        if u.hasPrefix("m") { return 60 }
        return 1
    }

    private static func overlaps(_ range: NSRange, _ existing: [Match]) -> Bool {
        existing.contains { NSIntersectionRange(range, NSRange(location: $0.location, length: $0.length)).length > 0 }
    }

    /// Joins "1 hour" + "15 minutes" when only "and", "," or spaces separate them.
    private static func mergeCompound(_ matches: [Match], in text: NSString) -> [Match] {
        var result: [Match] = []
        for match in matches {
            if let last = result.last, last.upperSeconds == nil, match.upperSeconds == nil,
               last.seconds % 3600 == 0, match.seconds < 3600 {
                let gapStart = last.location + last.length
                let gap = text.substring(with: NSRange(location: gapStart, length: max(0, match.location - gapStart)))
                let cleaned = gap.lowercased().replacingOccurrences(of: "and", with: "")
                    .trimmingCharacters(in: CharacterSet(charactersIn: " ,&"))
                if cleaned.isEmpty && gap.count <= 6 {
                    let total = last.seconds + match.seconds
                    result[result.count - 1] = Match(
                        location: last.location, length: match.location + match.length - last.location,
                        seconds: total, upperSeconds: nil, label: format(seconds: total)
                    )
                    continue
                }
            }
            result.append(match)
        }
        return result
    }
}
