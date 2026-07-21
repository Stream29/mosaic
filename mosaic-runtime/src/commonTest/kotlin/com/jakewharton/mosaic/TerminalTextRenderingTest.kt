package com.jakewharton.mosaic

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.jakewharton.mosaic.layout.drawBehind
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Layout
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.IntOffset
import com.jakewharton.mosaic.ui.unit.IntSize
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class TerminalTextRenderingTest {
	@Test fun measuresAndPlacesTextInTerminalCells() = runTest {
		runMosaicTest(NodeSnapshots) {
			setContent {
				Row {
					Text("你")
					Text("X")
				}
			}

			val row = awaitSnapshot().children.single()
			val wideText = row.children[0]
			val narrowText = row.children[1]
			assertThat(wideText.size).isEqualTo(IntSize(width = 2, height = 1))
			assertThat(narrowText.position).isEqualTo(IntOffset(x = 2, y = 0))
		}
	}

	@Test fun rendersExtendedGraphemeClusters() = runTest {
		runMosaicTest {
			val snapshot = setContentAndSnapshot {
				Text("e\u0301👩‍🔬")
			}

			assertThat(snapshot).isEqualTo("e\u0301👩‍🔬")
		}
	}

	@Test fun constrainedTextDoesNotRenderPartialWideCluster() = runTest {
		runMosaicTest {
			val snapshot = setContentAndSnapshot {
				Text("A你B", modifier = Modifier.width(2))
			}

			assertThat(snapshot).isEqualTo("A")
		}
	}

	@Test fun constrainedTextDoesNotRenderPastItsHeight() = runTest {
		runMosaicTest {
			val snapshot = setContentAndSnapshot {
				Text("A\nB", modifier = Modifier.height(1))
			}

			assertThat(snapshot).isEqualTo("A")
		}
	}

	@Test fun overwritingWideClusterContinuationClearsTheWholeCluster() = runTest {
		runMosaicTest {
			val snapshot = setContentAndSnapshot {
				Layout(
					modifier = Modifier.drawBehind {
						drawText(row = 0, column = 0, string = "你")
						drawText(row = 0, column = 1, string = "A")
					},
				) {
					layout(width = 2, height = 1)
				}
			}

			assertThat(snapshot).isEqualTo(" A")
		}
	}
}
