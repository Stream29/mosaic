package com.jakewharton.mosaic.text

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test

class TerminalTextTest {
	@Test fun calculatesTerminalWidths() {
		assertThat("A你B".terminalCellWidth()).isEqualTo(4)
		assertThat("e\u0301".terminalCellWidth()).isEqualTo(1)
		assertThat("👩‍🔬".terminalCellWidth()).isEqualTo(2)
	}

	@Test fun groupsExtendedGraphemeClusters() {
		assertThat("A你B".terminalTextClusters().toList()).isEqualTo(
			listOf(
				TerminalTextCluster(start = 0, end = 1, cellWidth = 1),
				TerminalTextCluster(start = 1, end = 2, cellWidth = 2),
				TerminalTextCluster(start = 2, end = 3, cellWidth = 1),
			),
		)
		assertThat("e\u0301x".terminalTextClusters().toList()).isEqualTo(
			listOf(
				TerminalTextCluster(start = 0, end = 2, cellWidth = 1),
				TerminalTextCluster(start = 2, end = 3, cellWidth = 1),
			),
		)
		assertThat("👩‍🔬!".terminalTextClusters().toList()).isEqualTo(
			listOf(
				TerminalTextCluster(start = 0, end = 5, cellWidth = 2),
				TerminalTextCluster(start = 5, end = 6, cellWidth = 1),
			),
		)
	}

	@Test fun attachesLeadingZeroWidthClustersToRenderedText() {
		assertThat("\u0301A".terminalTextClusters().toList()).isEqualTo(
			listOf(TerminalTextCluster(start = 0, end = 2, cellWidth = 1)),
		)
	}

	@Test fun takesPrefixesWithoutSplittingGraphemeClusters() {
		assertThat("A你B".takeFirstFittingTerminalWidth(3)).isEqualTo("A你")
		assertThat("e\u0301x".takeFirstFittingTerminalWidth(1)).isEqualTo("e\u0301")
		assertThat("👩‍🔬!".takeFirstFittingTerminalWidth(2)).isEqualTo("👩‍🔬")
		assertThat("你".takeFirstFittingTerminalWidth(1)).isEqualTo("")
		assertThat("A".takeFirstFittingTerminalWidth(0)).isEqualTo("")
	}

	@Test fun takesSuffixesWithoutSplittingGraphemeClusters() {
		assertThat("A你B".takeLastFittingTerminalWidth(3)).isEqualTo("你B")
		assertThat("xe\u0301".takeLastFittingTerminalWidth(1)).isEqualTo("e\u0301")
		assertThat("!👩‍🔬".takeLastFittingTerminalWidth(2)).isEqualTo("👩‍🔬")
		assertThat("你".takeLastFittingTerminalWidth(1)).isEqualTo("")
		assertThat("A".takeLastFittingTerminalWidth(0)).isEqualTo("")
	}
}
