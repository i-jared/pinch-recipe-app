package com.example.pinch.parsing

/** A piece of recognized text with its normalized bounding box (top-left origin). */
data class OcrFragment(val text: String, val box: Box)

/**
 * Puts OCR fragments from one page into reading order and marks paragraph breaks.
 * Fragments are grouped into rows, a gutter is found from rows that split in the same
 * place, and runs of rows that respect the gutter are read column by column.
 * Mirrors the iOS `LineLayout`.
 */
object LineLayout {
    private data class Segment(val text: String, val box: Box)
    private class Row(val segments: List<Segment>)
    private data class Placed(val text: String, val box: Box, val block: Int, val sameRowAsPrevious: Boolean)

    fun lines(fragments: List<OcrFragment>, page: Int): List<TextLine> {
        val cleaned = fragments
            .map { it.copy(text = TextKit.cleanLine(it.text)) }
            .filter { it.text.isNotEmpty() && it.box.height > 0 }
        if (cleaned.isEmpty()) return emptyList()

        val rows = groupIntoRows(cleaned)
        val heights = rows.flatMap { r -> r.segments.map { it.box.height } }.sorted()
        val medianHeight = heights[heights.size / 2]
        return annotate(order(rows), page, medianHeight)
    }

    // Rows

    private fun groupIntoRows(fragments: List<OcrFragment>): List<Row> {
        val sorted = fragments.sortedBy { it.box.midY }
        val groups = ArrayList<MutableList<OcrFragment>>()
        for (fragment in sorted) {
            val last = groups.lastOrNull()
            if (last != null) {
                val bounds = last.map { it.box }.reduce { a, b -> a.union(b) }
                val averageHeight = last.sumOf { it.box.height } / last.size
                val overlap = minOf(bounds.maxY, fragment.box.maxY) - maxOf(bounds.minY, fragment.box.minY)
                if (overlap >= 0.5 * minOf(fragment.box.height, averageHeight)) {
                    last.add(fragment)
                    continue
                }
            }
            groups.add(mutableListOf(fragment))
        }
        return groups.map { group ->
            val segments = ArrayList<Segment>()
            for (fragment in group.sortedBy { it.box.minX }) {
                val last = segments.lastOrNull()
                if (last != null && fragment.box.minX - last.box.maxX <= 0.03) {
                    segments[segments.size - 1] = Segment(last.text + " " + fragment.text, last.box.union(fragment.box))
                } else {
                    segments.add(Segment(fragment.text, fragment.box))
                }
            }
            Row(segments)
        }
    }

    // Columns

    private fun findGutter(rows: List<Row>): Double? {
        val candidates = ArrayList<Pair<Double, Int>>()
        for ((index, row) in rows.withIndex()) {
            if (row.segments.size < 2) continue
            for (pair in row.segments.zipWithNext()) {
                val gap = pair.second.box.minX - pair.first.box.maxX
                if (gap > 0.03) candidates.add((pair.first.box.maxX + pair.second.box.minX) / 2 to index)
            }
        }
        if (candidates.isEmpty()) return null
        val sorted = candidates.sortedBy { it.first }
        val clusters = ArrayList<MutableList<Pair<Double, Int>>>()
        clusters.add(mutableListOf(sorted[0]))
        for (candidate in sorted.drop(1)) {
            if (candidate.first - clusters.last().last().first <= 0.06) clusters.last().add(candidate) else clusters.add(mutableListOf(candidate))
        }
        // The first cluster with the most distinct rows wins, as on iOS.
        var best: MutableList<Pair<Double, Int>>? = null
        for (cluster in clusters) {
            if (best == null || cluster.map { it.second }.toSet().size > best.map { it.second }.toSet().size) best = cluster
        }
        if (best == null || best.map { it.second }.toSet().size < 2) return null
        val xs = best.map { it.first }.sorted()
        return xs[xs.size / 2]
    }

    private fun order(rows: List<Row>): List<Placed> {
        val gutter = findGutter(rows) ?: return placeRowMajor(rows, 0)

        fun crosses(segment: Segment) = segment.box.minX < gutter - 0.01 && segment.box.maxX > gutter + 0.01

        val placed = ArrayList<Placed>()
        var block = 0
        val band = ArrayList<Row>()
        val singles = ArrayList<Row>()

        fun flushSingles() {
            if (singles.isEmpty()) return
            placed.addAll(placeRowMajor(singles, block))
            block++
            singles.clear()
        }

        fun flushBand() {
            if (band.isEmpty()) return
            val splitRows = band.count { row ->
                row.segments.any { it.box.maxX <= gutter + 0.01 } && row.segments.any { it.box.minX >= gutter - 0.01 }
            }
            if (splitRows >= 2) {
                flushSingles()
                val (columns, next) = placeColumns(band, gutter, block)
                placed.addAll(columns)
                block = next
            } else {
                singles.addAll(band)
            }
            band.clear()
        }

        for (row in rows) {
            if (row.segments.any(::crosses)) {
                flushBand()
                singles.add(row)
            } else {
                if (band.isEmpty()) flushSingles()
                band.add(row)
            }
        }
        flushBand()
        flushSingles()
        return placed
    }

    private fun placeRowMajor(rows: List<Row>, startingBlock: Int): List<Placed> =
        rows.flatMap { row -> row.segments.mapIndexed { i, s -> Placed(s.text, s.box, startingBlock, i > 0) } }

    private fun placeColumns(band: List<Row>, gutter: Double, startBlock: Int): Pair<List<Placed>, Int> {
        var block = startBlock
        val left = ArrayList<Segment>()
        val right = ArrayList<Segment>()
        val pairs = ArrayList<Pair<Segment?, Segment?>>()
        for (row in band) {
            val l = merge(row.segments.filter { it.box.maxX <= gutter + 0.01 })
            val r = merge(row.segments.filter { it.box.minX >= gutter - 0.01 })
            l?.let { left.add(it) }
            r?.let { right.add(it) }
            pairs.add(l to r)
        }

        // A column of bare quantities beside names is a table: read it row by row.
        val leftQuantities = share(left) { isShortQuantity(it.text) }
        val rightQuantities = share(right) { isShortQuantity(it.text) }
        if ((rightQuantities >= 0.6 && leftQuantities < 0.3) || (leftQuantities >= 0.6 && rightQuantities < 0.3)) {
            val quantityFirst = leftQuantities >= 0.6
            val placed = pairs.mapNotNull { (l, r) ->
                when {
                    l != null && r != null -> Placed(if (quantityFirst) "${l.text} ${r.text}" else "${r.text} ${l.text}", l.box.union(r.box), block, false)
                    l != null -> Placed(l.text, l.box, block, false)
                    r != null -> Placed(r.text, r.box, block, false)
                    else -> null
                }
            }
            return placed to block + 1
        }

        val placed = ArrayList<Placed>()
        left.forEach { placed.add(Placed(it.text, it.box, block, false)) }
        block++
        right.forEach { placed.add(Placed(it.text, it.box, block, false)) }
        block++
        return placed to block
    }

    private fun merge(segments: List<Segment>): Segment? =
        segments.reduceOrNull { acc, next -> Segment(acc.text + " " + next.text, acc.box.union(next.box)) }

    private fun share(segments: List<Segment>, predicate: (Segment) -> Boolean): Double =
        if (segments.isEmpty()) 0.0 else segments.count(predicate).toDouble() / segments.size

    private fun isShortQuantity(text: String): Boolean {
        if (text.length > 16) return false
        val parts = IngredientParser.parse(text)
        return parts.quantity != null && parts.nameStart >= text.length - 1
    }

    // Paragraphs

    private fun annotate(placed: List<Placed>, page: Int, medianHeight: Double): List<TextLine> {
        val bounds = HashMap<Int, Pair<Double, Double>>()
        for (p in placed) {
            val current = bounds[p.block] ?: (p.box.minX to p.box.maxX)
            bounds[p.block] = minOf(current.first, p.box.minX) to maxOf(current.second, p.box.maxX)
        }

        // Typical space between lines in the same column, so double-spaced cards don't
        // look like a new paragraph on every line.
        val gaps = ArrayList<Double>()
        for (i in 1 until placed.size) {
            if (placed[i - 1].block == placed[i].block && !placed[i].sameRowAsPrevious) {
                val gap = placed[i].box.minY - placed[i - 1].box.maxY
                if (gap > 0) gaps.add(gap)
            }
        }
        gaps.sort()
        val typicalGap = if (gaps.isEmpty()) medianHeight * 0.4 else gaps[gaps.size / 2]
        val paragraphGap = maxOf(typicalGap * 1.5, medianHeight * 0.35)

        return placed.mapIndexed { i, p ->
            var line = TextLine(p.text, p.box, page, paragraphStart = true, previousLineFull = false)
            if (i > 0 && placed[i - 1].block == p.block) {
                val prev = placed[i - 1]
                line = if (p.sameRowAsPrevious) {
                    line.copy(paragraphStart = false, previousLineFull = true)
                } else {
                    val (minX, maxX) = bounds[p.block] ?: (0.0 to 1.0)
                    val width = maxOf(maxX - minX, 0.01)
                    val prevFull = prev.box.maxX >= maxX - 0.2 * width
                    val gap = p.box.minY - prev.box.maxY
                    val prevEnds = prev.text.lastOrNull()?.let { it in ".!?:" } ?: false
                    val startsUpper = p.text.firstOrNull { it.isLetter() }?.isUpperCase() ?: false
                    line.copy(
                        previousLineFull = prevFull,
                        paragraphStart = gap > paragraphGap || (!prevFull && (prevEnds || startsUpper)),
                    )
                }
            }
            line
        }
    }
}
