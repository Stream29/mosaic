package com.jakewharton.mosaic.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import com.jakewharton.mosaic.TerminalCursorPosition
import com.jakewharton.mosaic.cursorPosition
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.focus.FocusState
import com.jakewharton.mosaic.focus.focusCursor
import com.jakewharton.mosaic.focus.focusRequester
import com.jakewharton.mosaic.focus.focusTrap
import com.jakewharton.mosaic.focus.focusable
import com.jakewharton.mosaic.focus.onFocusChanged
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.MosaicSnapshots
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Layout
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.Constraints
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class ViewportClipTest {
	@Test fun negativePlacementDrawsOnlyTheVisibleText() = runTest {
		runMosaicTest {
			val snapshot = setContentAndSnapshot {
				Viewport(width = 3, childX = -2) {
					Text("ABCDE")
				}
			}

			assertThat(snapshot).isEqualTo("CDE")
		}
	}

	@Test fun nestedViewportsIntersectTheirClipBounds() = runTest {
		runMosaicTest {
			val snapshot = setContentAndSnapshot {
				Viewport(width = 4, childX = 2) {
					Viewport(width = 4, childX = -1) {
						Text("ABCDE")
					}
				}
			}

			assertThat(snapshot).isEqualTo("  BC")
		}
	}

	@Test fun aWideClusterCrossingTheClipBoundaryIsNotPartiallyDrawn() = runTest {
		runMosaicTest {
			val snapshot = setContentAndSnapshot {
				Viewport(width = 2, childX = -1) {
					Text("你A")
				}
			}

			assertThat(snapshot).isEqualTo(" A")
		}
	}

	@Test fun clippedDrawingDoesNotClearAClusterOutsideTheBoundary() = runTest {
		runMosaicTest {
			val snapshot = setContentAndSnapshot {
				Layout(
					content = {
						Text("你")
						Viewport(width = 1) {
							Text("A")
						}
					},
				) { measurables, constraints ->
					val (background, viewport) = measurables.map { measurable -> measurable.measure(constraints) }
					layout(width = 2, height = 1) {
						background.place(0, 0)
						viewport.place(1, 0)
					}
				}
			}

			assertThat(snapshot).isEqualTo("你")
		}
	}

	@Test fun pointerHitTestingUsesTheVisibleIntersection() = runTest {
		var target by mutableStateOf("none")

		runMosaicTest {
			setContentAndSnapshot {
				Column {
					Row(
						modifier = Modifier.onPointerEvent {
							target = "viewport parent"
							true
						},
					) {
						Viewport(width = 3) {
							Text(
								value = "ABCDE",
								modifier = Modifier.onPointerEvent {
									target = "child"
									true
								},
							)
						}
						Text(".....")
					}
					Text(target)
				}
			}

			sendMouseEvent(MouseEvent(4, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
			awaitSnapshot()
			assertThat(target).isEqualTo("viewport parent")

			sendMouseEvent(MouseEvent(1, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
			awaitSnapshot()
			assertThat(target).isEqualTo("child")
		}
	}

	@Test fun aFullyClippedFocusTargetCannotBeRequested() = runTest {
		val requester = FocusRequester()

		runMosaicTest {
			setContentAndSnapshot {
				Row {
					Viewport(width = 3, childX = 3) {
						Text(
							value = "target",
							modifier = Modifier
								.focusRequester(requester)
								.focusable(),
						)
					}
					Text("...")
				}
			}

			assertThat(requester.requestFocus()).isFalse()
		}
	}

	@Test fun aFullyClippedFocusTrapDoesNotBecomeActive() = runTest {
		var focused by mutableStateOf("none")

		runMosaicTest {
			setContentAndSnapshot {
				Column {
					FocusableText("outside") { focused = "outside" }
					Row {
						Viewport(width = 3, childX = 3) {
							Column(Modifier.focusTrap()) {
								FocusableText("inside") { focused = "inside" }
							}
						}
						Text("...")
					}
					Text(focused)
				}
			}

			assertThat(focused).isEqualTo("outside")
		}
	}

	@Test fun aClippedFocusCursorFallsBackToTheVisibleTargetOrigin() = runTest {
		runMosaicTest(MosaicSnapshots) {
			val mosaic = setContentAndSnapshot {
				Viewport(width = 3, childX = -2) {
					Text(
						value = "ABCDE",
						modifier = Modifier
							.focusCursor(column = 0)
							.focusable(),
					)
				}
			}

			assertThat(mosaic.cursorPosition).isEqualTo(
				TerminalCursorPosition(row = 0, column = 0),
			)
		}
	}
}

@Composable
private fun Viewport(
	width: Int,
	height: Int = 1,
	childX: Int = 0,
	childY: Int = 0,
	content: @Composable () -> Unit,
) {
	Layout(
		content = content,
		modifier = Modifier.clipToBounds(),
	) { measurables, _ ->
		val placeables = measurables.map { measurable -> measurable.measure(Constraints()) }
		layout(width, height) {
			for (placeable in placeables) {
				placeable.place(childX, childY)
			}
		}
	}
}

@Composable
private fun FocusableText(label: String, onFocus: () -> Unit) {
	Text(
		value = label,
		modifier = Modifier
			.onFocusChanged { state ->
				if (state == FocusState.Active) onFocus()
			}
			.focusable(),
	)
}
