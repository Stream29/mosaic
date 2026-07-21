package com.jakewharton.mosaic.text

import io.github.kotlinmania.unicodesegmentation.graphemeIndices
import io.github.kotlinmania.unicodewidth.unicodeWidth

internal data class TerminalTextCluster(
	val start: Int,
	val end: Int,
	val cellWidth: Int,
)

/** Returns the number of terminal cells occupied by this text. */
internal fun String.terminalCellWidth(): Int = terminalTextClusters().sumOf(TerminalTextCluster::cellWidth)

/**
 * Returns the longest prefix that fits within [maximumWidth] terminal cells.
 *
 * Extended grapheme clusters are kept intact. A non-positive width returns an empty string.
 */
internal fun String.takeFirstFittingTerminalWidth(maximumWidth: Int): String {
	return substring(0, firstFittingTerminalEndIndex(maximumWidth))
}

/**
 * Returns the longest suffix that fits within [maximumWidth] terminal cells.
 *
 * Extended grapheme clusters are kept intact. A non-positive width returns an empty string.
 */
internal fun String.takeLastFittingTerminalWidth(maximumWidth: Int): String {
	if (maximumWidth <= 0) return ""

	var width = 0
	var start = length
	for (cluster in terminalTextClusters().toList().asReversed()) {
		if (width + cluster.cellWidth > maximumWidth) break
		width += cluster.cellWidth
		start = cluster.start
	}
	return substring(start)
}

internal fun String.firstFittingTerminalEndIndex(maximumWidth: Int): Int {
	if (maximumWidth <= 0) return 0

	var width = 0
	var end = 0
	for (cluster in terminalTextClusters()) {
		if (width + cluster.cellWidth > maximumWidth) break
		width += cluster.cellWidth
		end = cluster.end
	}
	return end
}

internal fun String.terminalTextClusters(): Sequence<TerminalTextCluster> = sequence {
	var clusterStart = 0
	var clusterEnd = 0
	var clusterWidth = 0

	for ((start, value) in graphemeIndices(isExtended = true)) {
		val end = start + value.length
		val width = value.unicodeWidth()
		if (width == 0) {
			clusterEnd = end
			continue
		}

		if (clusterWidth > 0) {
			yield(TerminalTextCluster(clusterStart, clusterEnd, clusterWidth))
			clusterStart = start
		}
		clusterEnd = end
		clusterWidth = width
	}

	if (clusterWidth > 0) {
		yield(TerminalTextCluster(clusterStart, clusterEnd, clusterWidth))
	}
}
