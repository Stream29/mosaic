package com.jakewharton.mosaic.layout

import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.testing.SnapshotStrategy
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.Spacer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class DrawTextStyleOverlayTest {
	@Test
	fun stylesExistingTextWithoutRenderingEmptyCells() = runTest {
		val ansiSnapshots = SnapshotStrategy { mosaic ->
			mosaic.draw().render(AnsiLevel.TRUECOLOR, supportsKittyUnderlines = false)
		}

		runMosaicTest(snapshotStrategy = ansiSnapshots) {
			assertEquals(
				"\u001B[2mtext\u001B[0m\n",
				setContentAndSnapshot {
					Box(modifier = Modifier.size(width = 10, height = 2)) {
						Text("text")
						Spacer(Modifier.matchParentSize().drawTextStyleOverlay(TextStyle.Dim))
					}
				},
			)
		}
	}
}
