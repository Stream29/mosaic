package com.jakewharton.mosaic

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.testing.TestTerminal
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class AnsiRenderingTest {
	private val rendering = AnsiRendering(TestTerminal.Capabilities())

	@Test fun firstRender() = runTest {
		runMosaicTest(RenderingSnapshots(rendering)) {
			setContent {
				Column {
					Text("Hello")
					Text("World!")
				}
			}

			assertThat(awaitSnapshot()).isEqualTo(
				"""
				|Hello
				|World!
				|
				""".trimMargin().wrapWithAnsiSynchronizedUpdate().replaceLineEndingsWithCRLF(),
			)
		}
	}

	@Test fun terminalCursorTracksRequestedSurfacePosition() = runTest {
		runMosaicTest(RenderingSnapshots(rendering)) {
			var cursorPosition by mutableStateOf(TerminalCursorPosition(row = 1, column = 3))
			setContent {
				TerminalCursor(cursorPosition)
				Column {
					Text("Hello")
					Text("World!")
				}
			}

			assertThat(awaitSnapshot()).isEqualTo(
				"""
				|Hello
				|World!
				|${CSI}1A${CSI}3C$cursorVisibilityEnable
				""".trimMargin().wrapWithAnsiSynchronizedUpdate().replaceLineEndingsWithCRLF(),
			)

			cursorPosition = TerminalCursorPosition(row = 0, column = 1)
			assertThat(awaitSnapshot()).isEqualTo(
				"""
				|${CSI}1F${clearLine}Hello
				|${clearLine}World!
				|${CSI}2A${CSI}1C
				""".trimMargin().wrapWithAnsiSynchronizedUpdate().replaceLineEndingsWithCRLF(),
			)
		}
	}

	@Test fun terminalCursorIsHiddenWhenNoLongerRequested() = runTest {
		runMosaicTest(RenderingSnapshots(rendering)) {
			setContent {
				TerminalCursor(TerminalCursorPosition(row = 0, column = 1))
				Text("Hello")
			}

			assertThat(awaitSnapshot()).isEqualTo(
				"""
				|Hello
				|${CSI}1A${CSI}1C$cursorVisibilityEnable
				""".trimMargin().wrapWithAnsiSynchronizedUpdate().replaceLineEndingsWithCRLF(),
			)

			setContent {
				TerminalCursor(null)
				Text("Hello")
			}
			assertThat(awaitSnapshot()).isEqualTo(
				("\r${clearLine}Hello\n$cursorVisibilityDisable")
					.wrapWithAnsiSynchronizedUpdate()
					.replaceLineEndingsWithCRLF(),
			)
		}
	}

	@Test fun subsequentLongerRenderClearsRenderedLines() = runTest {
		runMosaicTest(RenderingSnapshots(rendering)) {
			setContent {
				Column {
					Text("Hello")
					Text("World!")
				}
			}

			assertThat(awaitSnapshot()).isEqualTo(
				"""
				|Hello
				|World!
				|
				""".trimMargin().wrapWithAnsiSynchronizedUpdate().replaceLineEndingsWithCRLF(),
			)

			setContent {
				Column {
					Text("Hel")
					Text("lo")
					Text("Wor")
					Text("ld!")
				}
			}

			assertThat(awaitSnapshot()).isEqualTo(
				"""
				|${cursorUp(2)}${clearLine}Hel
				|${clearLine}lo
				|Wor
				|ld!
				|
				""".trimMargin().wrapWithAnsiSynchronizedUpdate().replaceLineEndingsWithCRLF(),
			)
		}
	}

	@Test fun subsequentShorterRenderClearsRenderedLines() = runTest {
		runMosaicTest(RenderingSnapshots(rendering)) {
			setContent {
				Column {
					Text("Hel")
					Text("lo")
					Text("Wor")
					Text("ld!")
				}
			}

			assertThat(awaitSnapshot()).isEqualTo(
				"""
				|Hel
				|lo
				|Wor
				|ld!
				|
				""".trimMargin().wrapWithAnsiSynchronizedUpdate().replaceLineEndingsWithCRLF(),
			)

			setContent {
				Column {
					Text("Hello")
					Text("World!")
				}
			}

			assertThat(awaitSnapshot()).isEqualTo(
				"""
				|${cursorUp(4)}${clearLine}Hello
				|${clearLine}World!
				|$clearDisplay
				""".trimMargin().wrapWithAnsiSynchronizedUpdate().replaceLineEndingsWithCRLF(),
			)
		}
	}

	@Test fun staticRendersFirst() = runTest {
		runMosaicTest(RenderingSnapshots(rendering)) {
			setContent {
				Text("Hello")
				StaticEffect {
					Text("World!")
				}
			}

			assertThat(awaitSnapshot()).isEqualTo(
				"""
				|World!
				|Hello
				|
				""".trimMargin().wrapWithAnsiSynchronizedUpdate().replaceLineEndingsWithCRLF(),
			)
		}
	}

	@Test fun staticLinesNotErased() = runTest {
		runMosaicTest(RenderingSnapshots(rendering)) {
			setContent {
				StaticEffect {
					Text("One")
				}
				Text("Two")
			}

			assertThat(awaitSnapshot()).isEqualTo(
				"""
				|One
				|Two
				|
				""".trimMargin().wrapWithAnsiSynchronizedUpdate().replaceLineEndingsWithCRLF(),
			)

			setContent {
				StaticEffect {
					Text("Three")
				}
				Text("Four")
			}

			assertThat(awaitSnapshot()).isEqualTo(
				"""
				|${cursorUp(1)}${clearDisplay}Three
				|Four
				|
				""".trimMargin().wrapWithAnsiSynchronizedUpdate().replaceLineEndingsWithCRLF(),
			)
		}
	}

	@Test fun staticOrderingIsDfs() = runTest {
		runMosaicTest(RenderingSnapshots(rendering)) {
			setContent {
				StaticEffect {
					Text("One")
				}
				Column {
					StaticEffect {
						Text("Two")
					}
					Row {
						StaticEffect {
							Text("Three")
						}
						Text("Sup")
					}
					StaticEffect {
						Text("Four")
					}
				}
				StaticEffect {
					Text("Five")
				}
			}

			assertThat(awaitSnapshot()).isEqualTo(
				"""
				|One
				|Two
				|Three
				|Four
				|Five
				|Sup
				|
				""".trimMargin().wrapWithAnsiSynchronizedUpdate().replaceLineEndingsWithCRLF(),
			)
		}
	}

	@Test fun staticInPositionedElement() = runTest {
		runMosaicTest(RenderingSnapshots(rendering)) {
			setContent {
				Column {
					Text("TopTopTop")
					Row {
						Text("LeftLeft")
						StaticEffect {
							Text("Static")
						}
					}
				}
			}

			assertThat(awaitSnapshot()).isEqualTo(
				"""
				|Static
				|TopTopTop
				|LeftLeft
				|
				""".trimMargin().wrapWithAnsiSynchronizedUpdate().replaceLineEndingsWithCRLF(),
			)
		}
	}

	@Test fun withoutTrailingSpaces() = runTest {
		runMosaicTest(RenderingSnapshots(rendering)) {
			val snapshot = setContentAndSnapshot {
				Text("OneTwoThree   ")
			}

			assertThat(snapshot).isEqualTo(
				"""
				|OneTwoThree
				|
				""".trimMargin().wrapWithAnsiSynchronizedUpdate().replaceLineEndingsWithCRLF(),
			)
		}
	}

	@Test fun withoutTrailingSpacesInContainer() = runTest {
		runMosaicTest(RenderingSnapshots(rendering)) {
			val snapshot = setContentAndSnapshot {
				Column {
					Text("OneTwoThree")
					Text("OneTwoThreeFour")
				}
			}

			assertThat(snapshot).isEqualTo(
				"""
				|OneTwoThree
				|OneTwoThreeFour
				|
				""".trimMargin().wrapWithAnsiSynchronizedUpdate().replaceLineEndingsWithCRLF(),
			)
		}
	}

	@Test fun withoutTrailingSpacesInContainerWithAnsiNone() = runTest {
		val rendering = AnsiRendering(
			TestTerminal.Capabilities(ansiLevel = AnsiLevel.NONE),
		)
		runMosaicTest(RenderingSnapshots(rendering)) {
			val snapshot = setContentAndSnapshot {
				Column {
					Text("OneTwoThree")
					Text("OneTwoThreeFour")
				}
			}

			assertThat(snapshot).isEqualTo(
				"""
				|OneTwoThree
				|OneTwoThreeFour
				|
				""".trimMargin().wrapWithAnsiSynchronizedUpdate().replaceLineEndingsWithCRLF(),
			)
		}
	}

	@Test fun withColoredTrailingSpacesInContainer() = runTest {
		runMosaicTest(RenderingSnapshots(rendering)) {
			val snapshot = setContentAndSnapshot {
				Column(modifier = Modifier.background(Color.Red)) {
					Text("OneTwoThree")
					Text("OneTwoThreeFour")
				}
			}

			val red = "${CSI}48;2;255;0;0m"
			assertThat(snapshot).isEqualTo(
				"""
				|${red}OneTwoThree    $ansiReset$ansiClosingCharacter
				|${red}OneTwoThreeFour$ansiReset$ansiClosingCharacter
				|
				""".trimMargin().wrapWithAnsiSynchronizedUpdate().replaceLineEndingsWithCRLF(),
			)
		}
	}

	@Test fun boldAndDimTransitionsRestoreSharedTerminalIntensity() {
		val none = TextStyle.Empty
		val bold = TextStyle.Bold
		val dim = TextStyle.Dim
		val boldAndDim = bold + dim
		val transitions = listOf(
			IntensityTransition(none, none, "AB"),
			IntensityTransition(none, bold, "A${CSI}1mB$ansiReset$ansiClosingCharacter"),
			IntensityTransition(none, dim, "A${CSI}2mB$ansiReset$ansiClosingCharacter"),
			IntensityTransition(none, boldAndDim, "A${CSI}1;2mB$ansiReset$ansiClosingCharacter"),
			IntensityTransition(bold, none, "${CSI}1mA${CSI}22mB"),
			IntensityTransition(bold, bold, "${CSI}1mAB$ansiReset$ansiClosingCharacter"),
			IntensityTransition(bold, dim, "${CSI}1mA${CSI}22;2mB$ansiReset$ansiClosingCharacter"),
			IntensityTransition(bold, boldAndDim, "${CSI}1mA${CSI}22;1;2mB$ansiReset$ansiClosingCharacter"),
			IntensityTransition(dim, none, "${CSI}2mA${CSI}22mB"),
			IntensityTransition(dim, bold, "${CSI}2mA${CSI}22;1mB$ansiReset$ansiClosingCharacter"),
			IntensityTransition(dim, dim, "${CSI}2mAB$ansiReset$ansiClosingCharacter"),
			IntensityTransition(dim, boldAndDim, "${CSI}2mA${CSI}22;1;2mB$ansiReset$ansiClosingCharacter"),
			IntensityTransition(boldAndDim, none, "${CSI}1;2mA${CSI}22mB"),
			IntensityTransition(boldAndDim, bold, "${CSI}1;2mA${CSI}22;1mB$ansiReset$ansiClosingCharacter"),
			IntensityTransition(boldAndDim, dim, "${CSI}1;2mA${CSI}22;2mB$ansiReset$ansiClosingCharacter"),
			IntensityTransition(boldAndDim, boldAndDim, "${CSI}1;2mAB$ansiReset$ansiClosingCharacter"),
		)

		for (transition in transitions) {
			val surface = TextSurface(width = 2, height = 1)
			surface.replaceText(row = 0, column = 0, text = "A", cellWidth = 1).apply {
				textStyle = transition.from
			}
			surface.replaceText(row = 0, column = 1, text = "B", cellWidth = 1).apply {
				textStyle = transition.to
			}

			assertThat(surface.render(AnsiLevel.TRUECOLOR, supportsKittyUnderlines = false))
				.isEqualTo(transition.expected)
		}
	}
}

private data class IntensityTransition(
	val from: TextStyle,
	val to: TextStyle,
	val expected: String,
)
