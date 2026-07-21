package com.jakewharton.mosaic.layout

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.IntOffset
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class PointerInputTest {
	@Test fun previewAndBubbleFollowTheTopmostHitPath() = runTest {
		var events by mutableStateOf(emptyList<String>())

		runMosaicTest {
			setContentAndSnapshot {
				Column {
					Box(
						modifier = Modifier
							.onPreviewPointerEvent {
								events += "outer preview"
								false
							}
							.onPointerEvent {
								events += "outer bubble"
								false
							},
					) {
						Text(
							value = "lower",
							modifier = Modifier.onPointerEvent {
								events += "lower"
								true
							},
						)
						Text(
							value = "upper",
							modifier = Modifier
								.onPreviewPointerEvent {
									events += "upper preview"
									false
								}
								.onPointerEvent {
									events += "upper bubble"
									false
								},
						)
					}
					Text(events.joinToString())
				}
			}

			sendMouseEvent(MouseEvent(1, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
			awaitSnapshot()
		}

		assertThat(events).isEqualTo(
			listOf("outer preview", "upper preview", "upper bubble", "outer bubble"),
		)
	}

	@Test fun consumedPressCapturesDragAndReleaseOutsideTheHitBounds() = runTest {
		var events by mutableStateOf(emptyList<PointerEvent>())

		runMosaicTest {
			setContentAndSnapshot {
				Column {
					Row {
						Text("left")
						Text(
							value = "target",
							modifier = Modifier.onPointerEvent { event ->
								events += event
								event.type == MouseEvent.Type.Press
							},
						)
					}
					Text(events.size.toString())
				}
			}

			sendMouseEvent(MouseEvent(5, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
			awaitSnapshot()
			sendMouseEvent(MouseEvent(20, 3, MouseEvent.Type.Drag, MouseEvent.Button.Left))
			awaitSnapshot()
			sendMouseEvent(MouseEvent(20, 3, MouseEvent.Type.Release))
			awaitSnapshot()
		}

		assertThat(events).isEqualTo(
			listOf(
				PointerEvent(IntOffset(1, 0), MouseEvent.Type.Press, MouseEvent.Button.Left),
				PointerEvent(IntOffset(16, 3), MouseEvent.Type.Drag, MouseEvent.Button.Left),
				PointerEvent(IntOffset(16, 3), MouseEvent.Type.Release),
			),
		)
	}

	@Test fun hoverCallbacksFollowTheTopmostHitPath() = runTest {
		var events by mutableStateOf(emptyList<String>())

		runMosaicTest {
			setContentAndSnapshot {
				Column {
					Row {
						Text(
							value = "left",
							modifier = Modifier.onPointerHover(
								onPointerEnter = { events += "left enter" },
								onPointerExit = { events += "left exit" },
							),
						)
						Text(
							value = "right",
							modifier = Modifier.onPointerHover(
								onPointerEnter = { events += "right enter" },
								onPointerExit = { events += "right exit" },
							),
						)
					}
					Text(events.joinToString())
				}
			}

			sendMouseEvent(MouseEvent(1, 0, MouseEvent.Type.Motion))
			awaitSnapshot()
			sendMouseEvent(MouseEvent(5, 0, MouseEvent.Type.Motion))
			awaitSnapshot()
			sendMouseEvent(MouseEvent(20, 0, MouseEvent.Type.Motion))
			awaitSnapshot()
		}

		assertThat(events).isEqualTo(
			listOf("left enter", "left exit", "right enter", "right exit"),
		)
	}

	@Test fun hoverNodeSurvivesRecompositionAndUsesTheLatestCallbacks() = runTest {
		var generation by mutableIntStateOf(0)
		var events by mutableStateOf(emptyList<String>())

		runMosaicTest {
			setContentAndSnapshot {
				Column {
					Text(
						value = "target",
						modifier = Modifier.onPointerHover(
							onPointerEnter = { events += "enter:$generation" },
							onPointerExit = { events += "exit:$generation" },
						),
					)
					Text("$generation ${events.joinToString()}")
				}
			}

			sendMouseEvent(MouseEvent(1, 0, MouseEvent.Type.Motion))
			awaitSnapshot()
			generation++
			awaitSnapshot()
			sendMouseEvent(MouseEvent(20, 0, MouseEvent.Type.Motion))
			awaitSnapshot()
		}

		assertThat(events).isEqualTo(listOf("enter:0", "exit:1"))
	}

	@Test fun layoutChangesUpdateHoverForAStationaryPointer() = runTest {
		var x by mutableIntStateOf(0)
		var hovered by mutableStateOf(false)

		runMosaicTest {
			setContentAndSnapshot {
				Column {
					Box(Modifier.width(20)) {
						Text(
							value = "target",
							modifier = Modifier
								.offset(x = x)
								.onPointerHover(
									onPointerEnter = { hovered = true },
									onPointerExit = { hovered = false },
								),
						)
					}
					Text(hovered.toString())
				}
			}

			sendMouseEvent(MouseEvent(1, 0, MouseEvent.Type.Motion))
			awaitSnapshot()
			assertThat(hovered).isEqualTo(true)

			x = 10
			awaitSnapshot()
			assertThat(hovered).isEqualTo(false)
		}
	}

	@Test fun hitTestingUsesTerminalCellWidthsForLeadingText() = runTest {
		var hovered by mutableStateOf(false)

		runMosaicTest {
			setContentAndSnapshot {
				Column {
					Row {
						Text("会话")
						Text(
							value = "target",
							modifier = Modifier.onPointerHover(
								onPointerEnter = { hovered = true },
								onPointerExit = { hovered = false },
							),
						)
					}
					Text(hovered.toString())
				}
			}

			sendMouseEvent(MouseEvent(8, 0, MouseEvent.Type.Motion))
			awaitSnapshot()
		}

		assertThat(hovered).isEqualTo(true)
	}
}
