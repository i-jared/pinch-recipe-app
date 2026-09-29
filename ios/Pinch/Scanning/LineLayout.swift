import CoreGraphics
import Foundation

/// A piece of recognized text with its normalized bounding box (top-left origin).
struct OCRFragment: Hashable, Sendable {
    var text: String
    var box: CGRect
}

/// Puts OCR fragments from one page into reading order and marks paragraph breaks.
///
/// Recipes are often laid out in columns (ingredients beside the method) or with a
/// quantity column. Fragments are grouped into rows, a gutter is found from rows that
/// split in the same place, and runs of rows that respect the gutter are read column
/// by column. Mirrored by the Android app's `LineLayout.kt`.
enum LineLayout {
    private struct Segment {
        var text: String
        var box: CGRect
    }

    private struct Row {
        var segments: [Segment]
        var box: CGRect { segments.map(\.box).reduce(segments[0].box) { $0.union($1) } }
    }

    private struct Placed {
        var text: String
        var box: CGRect
        var block: Int
        var sameRowAsPrevious: Bool
    }

    static func lines(from fragments: [OCRFragment], page: Int) -> [TextLine] {
        let cleaned = fragments
            .map { OCRFragment(text: TextKit.cleanLine($0.text), box: $0.box) }
            .filter { !$0.text.isEmpty && $0.box.height > 0 }
        guard !cleaned.isEmpty else { return [] }

        let rows = groupIntoRows(cleaned)
        let heights = rows.flatMap { $0.segments.map(\.box.height) }.sorted()
        let medianHeight = heights[heights.count / 2]
        let placed = order(rows)
        return annotate(placed, page: page, medianHeight: medianHeight)
    }

    // MARK: Rows

    private static func groupIntoRows(_ fragments: [OCRFragment]) -> [Row] {
        let sorted = fragments.sorted { $0.box.midY < $1.box.midY }
        var groups: [[OCRFragment]] = []
        for fragment in sorted {
            if let last = groups.last {
                let bounds = last.map(\.box).reduce(last[0].box) { $0.union($1) }
                let averageHeight = last.map(\.box.height).reduce(0, +) / CGFloat(last.count)
                let overlap = min(bounds.maxY, fragment.box.maxY) - max(bounds.minY, fragment.box.minY)
                if overlap >= 0.5 * min(fragment.box.height, averageHeight) {
                    groups[groups.count - 1].append(fragment)
                    continue
                }
            }
            groups.append([fragment])
        }
        return groups.map { group in
            let byX = group.sorted { $0.box.minX < $1.box.minX }
            var segments: [Segment] = []
            for fragment in byX {
                if var last = segments.last, fragment.box.minX - last.box.maxX <= 0.03 {
                    last.text += " " + fragment.text
                    last.box = last.box.union(fragment.box)
                    segments[segments.count - 1] = last
                } else {
                    segments.append(Segment(text: fragment.text, box: fragment.box))
                }
            }
            return Row(segments: segments)
        }
    }

    // MARK: Columns

    private static func findGutter(_ rows: [Row]) -> CGFloat? {
        var candidates: [(x: CGFloat, row: Int)] = []
        for (index, row) in rows.enumerated() where row.segments.count >= 2 {
            for pair in zip(row.segments, row.segments.dropFirst()) {
                let gap = pair.1.box.minX - pair.0.box.maxX
                if gap > 0.03 { candidates.append(((pair.0.box.maxX + pair.1.box.minX) / 2, index)) }
            }
        }
        guard !candidates.isEmpty else { return nil }
        let sorted = candidates.sorted { $0.x < $1.x }
        var clusters: [[(x: CGFloat, row: Int)]] = [[sorted[0]]]
        for candidate in sorted.dropFirst() {
            if let lastX = clusters[clusters.count - 1].last?.x, candidate.x - lastX <= 0.06 {
                clusters[clusters.count - 1].append(candidate)
            } else {
                clusters.append([candidate])
            }
        }
        let best = clusters.max { Set($0.map(\.row)).count < Set($1.map(\.row)).count }
        guard let best, Set(best.map(\.row)).count >= 2 else { return nil }
        let xs = best.map(\.x).sorted()
        return xs[xs.count / 2]
    }

    private static func order(_ rows: [Row]) -> [Placed] {
        guard let gutter = findGutter(rows) else {
            return placeRowMajor(rows, startingBlock: 0)
        }

        func crosses(_ segment: Segment) -> Bool {
            segment.box.minX < gutter - 0.01 && segment.box.maxX > gutter + 0.01
        }

        var placed: [Placed] = []
        var block = 0
        var band: [Row] = []
        var singles: [Row] = []

        func flushSingles() {
            guard !singles.isEmpty else { return }
            placed += placeRowMajor(singles, startingBlock: block)
            block += 1
            singles = []
        }

        func flushBand() {
            guard !band.isEmpty else { return }
            let splitRows = band.filter { row in
                row.segments.contains { $0.box.maxX <= gutter + 0.01 } && row.segments.contains { $0.box.minX >= gutter - 0.01 }
            }
            if splitRows.count >= 2 {
                flushSingles()
                placed += placeColumns(band, gutter: gutter, block: &block)
            } else {
                singles += band
            }
            band = []
        }

        for row in rows {
            if row.segments.contains(where: crosses) {
                flushBand()
                singles.append(row)
            } else {
                if band.isEmpty { flushSingles() }
                band.append(row)
            }
        }
        flushBand()
        flushSingles()
        return placed
    }

    private static func placeRowMajor(_ rows: [Row], startingBlock: Int) -> [Placed] {
        var placed: [Placed] = []
        for row in rows {
            for (i, segment) in row.segments.enumerated() {
                placed.append(Placed(text: segment.text, box: segment.box, block: startingBlock, sameRowAsPrevious: i > 0))
            }
        }
        return placed
    }

    private static func placeColumns(_ band: [Row], gutter: CGFloat, block: inout Int) -> [Placed] {
        var left: [Segment] = []
        var right: [Segment] = []
        var pairs: [(Segment?, Segment?)] = []
        for row in band {
            let l = merge(row.segments.filter { $0.box.maxX <= gutter + 0.01 })
            let r = merge(row.segments.filter { $0.box.minX >= gutter - 0.01 })
            if let l { left.append(l) }
            if let r { right.append(r) }
            pairs.append((l, r))
        }

        // A column of bare quantities beside names is a table: read it row by row.
        let leftQuantities = share(left) { isShortQuantity($0.text) }
        let rightQuantities = share(right) { isShortQuantity($0.text) }
        if (rightQuantities >= 0.6 && leftQuantities < 0.3) || (leftQuantities >= 0.6 && rightQuantities < 0.3) {
            let quantityFirst = leftQuantities >= 0.6
            var placed: [Placed] = []
            for (l, r) in pairs {
                switch (l, r) {
                case let (l?, r?):
                    let text = quantityFirst ? "\(l.text) \(r.text)" : "\(r.text) \(l.text)"
                    placed.append(Placed(text: text, box: l.box.union(r.box), block: block, sameRowAsPrevious: false))
                case let (l?, nil):
                    placed.append(Placed(text: l.text, box: l.box, block: block, sameRowAsPrevious: false))
                case let (nil, r?):
                    placed.append(Placed(text: r.text, box: r.box, block: block, sameRowAsPrevious: false))
                default:
                    break
                }
            }
            block += 1
            return placed
        }

        var placed = left.map { Placed(text: $0.text, box: $0.box, block: block, sameRowAsPrevious: false) }
        block += 1
        placed += right.map { Placed(text: $0.text, box: $0.box, block: block, sameRowAsPrevious: false) }
        block += 1
        return placed
    }

    private static func merge(_ segments: [Segment]) -> Segment? {
        guard let first = segments.first else { return nil }
        return segments.dropFirst().reduce(first) { acc, next in
            Segment(text: acc.text + " " + next.text, box: acc.box.union(next.box))
        }
    }

    private static func share(_ segments: [Segment], where predicate: (Segment) -> Bool) -> Double {
        guard !segments.isEmpty else { return 0 }
        return Double(segments.filter(predicate).count) / Double(segments.count)
    }

    private static func isShortQuantity(_ text: String) -> Bool {
        text.count <= 16 && IngredientParser.parse(text).quantity != nil
            && IngredientParser.parse(text).nameStart >= text.count - 1
    }

    // MARK: Paragraphs

    private static func annotate(_ placed: [Placed], page: Int, medianHeight: CGFloat) -> [TextLine] {
        var blockBounds: [Int: (minX: CGFloat, maxX: CGFloat)] = [:]
        for p in placed {
            let current = blockBounds[p.block] ?? (p.box.minX, p.box.maxX)
            blockBounds[p.block] = (min(current.minX, p.box.minX), max(current.maxX, p.box.maxX))
        }

        // Typical space between lines in the same column, so double-spaced cards don't
        // look like a new paragraph on every line.
        var gaps: [CGFloat] = []
        for i in placed.indices.dropFirst() where placed[i - 1].block == placed[i].block && !placed[i].sameRowAsPrevious {
            let gap = placed[i].box.minY - placed[i - 1].box.maxY
            if gap > 0 { gaps.append(gap) }
        }
        gaps.sort()
        let typicalGap = gaps.isEmpty ? medianHeight * 0.4 : gaps[gaps.count / 2]
        let paragraphGap = max(typicalGap * 1.5, medianHeight * 0.35)

        var lines: [TextLine] = []
        for (i, p) in placed.enumerated() {
            var line = TextLine(text: p.text, box: p.box, page: page, paragraphStart: true, previousLineFull: false)
            if i > 0, placed[i - 1].block == p.block {
                let prev = placed[i - 1]
                if p.sameRowAsPrevious {
                    line.paragraphStart = false
                    line.previousLineFull = true
                } else {
                    let bounds = blockBounds[p.block] ?? (0, 1)
                    let width = max(bounds.maxX - bounds.minX, 0.01)
                    let prevFull = prev.box.maxX >= bounds.maxX - 0.2 * width
                    let gap = p.box.minY - prev.box.maxY
                    let prevEnds = prev.text.last.map { ".!?:".contains($0) } ?? false
                    let startsUpper = p.text.first(where: \.isLetter)?.isUppercase ?? false
                    line.previousLineFull = prevFull
                    line.paragraphStart = gap > paragraphGap || (!prevFull && (prevEnds || startsUpper))
                }
            }
            lines.append(line)
        }
        return lines
    }
}
