package com.jakewharton.mosaic.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.jakewharton.mosaic.TerminalCursor
import com.jakewharton.mosaic.TerminalCursorPosition
import com.jakewharton.mosaic.cursorPosition
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.offset
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.layout.onPreviewKeyEvent
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.modifier.composed
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.KeyboardEvent.Companion.ModifierShift
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.MosaicSnapshots
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.IntOffset
import com.jakewharton.mosaic.ui.unit.IntSize
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class FocusTest {
	@Test fun sequentialAndDirectionalNavigationFollowLayoutOrder() = runTest {
		var focused by mutableStateOf("")

		runMosaicTest {
			setContentAndSnapshot {
				Row {
					FocusText("first", onFocus = { focused = "first" })
					FocusText("second", onFocus = { focused = "second" })
					FocusText("third", onFocus = { focused = "third" })
				}
			}
			assertThat(focused).isEqualTo("first")

			sendKeyEvent(KeyboardEvent(9))
			awaitSnapshot()
			assertThat(focused).isEqualTo("second")

			sendKeyEvent(KeyboardEvent(9, modifiers = ModifierShift))
			awaitSnapshot()
			assertThat(focused).isEqualTo("first")

			sendKeyEvent(KeyboardEvent(KeyboardEvent.Right))
			awaitSnapshot()
			assertThat(focused).isEqualTo("second")

			sendKeyEvent(KeyboardEvent(KeyboardEvent.Left))
			awaitSnapshot()
			assertThat(focused).isEqualTo("first")
		}
	}

	@Test fun keyEventsOnlyReachTheFocusedPath() = runTest {
		var firstEvents by mutableIntStateOf(0)
		var secondEvents by mutableIntStateOf(0)

		runMosaicTest {
			setContentAndSnapshot {
				Column {
					Row {
						FocusText(
							label = "first",
							modifier = Modifier.onKeyEvent { event ->
								if (event.key == "Enter") firstEvents++
								false
							},
						)
						FocusText(
							label = "second",
							modifier = Modifier.onKeyEvent { event ->
								if (event.key == "Enter") secondEvents++
								false
							},
						)
					}
					Text("$firstEvents/$secondEvents")
				}
			}

			sendKeyEvent(KeyboardEvent(13))
			awaitSnapshot()
			assertThat(firstEvents).isEqualTo(1)
			assertThat(secondEvents).isEqualTo(0)

			sendKeyEvent(KeyboardEvent(9))
			awaitSnapshot()
			sendKeyEvent(KeyboardEvent(13))
			awaitSnapshot()
			assertThat(firstEvents).isEqualTo(1)
			assertThat(secondEvents).isEqualTo(1)
		}
	}

	@Test fun focusedKeyPathIncludesAncestorModifiers() = runTest {
		var events by mutableStateOf(emptyList<String>())

		runMosaicTest {
			setContentAndSnapshot {
				Box(
					modifier = Modifier
						.onPreviewKeyEvent {
							events += "preview"
							false
						}
						.onKeyEvent {
							events += "bubble"
							true
						},
				) {
					FocusText(
						label = "target:${events.joinToString()}",
						modifier = Modifier.onKeyEvent {
							events += "target"
							false
						},
					)
				}
			}

			sendKeyEvent(KeyboardEvent('x'.code))
			awaitSnapshot()
			assertThat(events).isEqualTo(listOf("preview", "target", "bubble"))
		}
	}

	@Test fun focusedComponentCanConsumeDirectionalNavigation() = runTest {
		var focused by mutableStateOf("")
		var directionEvents by mutableIntStateOf(0)

		runMosaicTest {
			setContentAndSnapshot {
				Row {
					FocusText(
						label = "first:$directionEvents",
						modifier = Modifier.onKeyEvent { event ->
							if (event.key != "ArrowRight") return@onKeyEvent false
							directionEvents++
							true
						},
						onFocus = { focused = "first" },
					)
					FocusText("second", onFocus = { focused = "second" })
				}
			}
			assertThat(focused).isEqualTo("first")

			sendKeyEvent(KeyboardEvent(KeyboardEvent.Right))
			awaitSnapshot()
			assertThat(focused).isEqualTo("first")
			assertThat(directionEvents).isEqualTo(1)

			sendKeyEvent(KeyboardEvent(9))
			awaitSnapshot()
			assertThat(focused).isEqualTo("second")
		}
	}

	@Test fun leftPointerPressFocusesTheHitTarget() = runTest {
		var focused by mutableStateOf("")

		runMosaicTest {
			setContentAndSnapshot {
				Row {
					FocusText("first", onFocus = { focused = "first" })
					FocusText("second", onFocus = { focused = "second" })
				}
			}
			assertThat(focused).isEqualTo("first")

			sendMouseEvent(MouseEvent(6, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
			awaitSnapshot()
			assertThat(focused).isEqualTo("second")
		}
	}

	@Test fun pointerFocusUsesLaidOutBounds() = runTest {
		var focused by mutableStateOf("")

		runMosaicTest {
			setContentAndSnapshot {
				Column(Modifier.width(30)) {
					Text(
						value = "first",
						modifier = Modifier
							.onFocusChanged { state ->
								if (state.isFocused) focused = "first"
							}
							.focusable()
							.fillMaxWidth(),
					)
					FocusText("second", onFocus = { focused = "second" })
				}
			}
			assertThat(focused).isEqualTo("first")

			sendKeyEvent(KeyboardEvent(9))
			awaitSnapshot()
			assertThat(focused).isEqualTo("second")

			sendMouseEvent(MouseEvent(20, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
			awaitSnapshot()
			assertThat(focused).isEqualTo("first")
		}
	}

	@Test fun pointerFocusSelectsTheTopmostOverlappingTarget() = runTest {
		var focused by mutableStateOf("")

		runMosaicTest {
			setContentAndSnapshot {
				Column {
					Box {
						FocusText("lower", onFocus = { focused = "lower" })
						FocusText("upper", onFocus = { focused = "upper" })
					}
					Text(focused)
				}
			}
			assertThat(focused).isEqualTo("lower")

			sendMouseEvent(MouseEvent(1, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
			awaitSnapshot()
			assertThat(focused).isEqualTo("upper")
		}
	}

	@Test fun pointerFocusCannotEscapeAnActiveTrap() = runTest {
		var focused by mutableStateOf("")
		var frame by mutableIntStateOf(0)

		runMosaicTest {
			setContentAndSnapshot {
				Column {
					FocusText("outside", onFocus = { focused = "outside" })
					Column(Modifier.focusTrap()) {
						FocusText("inside", onFocus = { focused = "inside" })
					}
					Text(frame.toString())
				}
			}
			assertThat(focused).isEqualTo("inside")

			sendMouseEvent(MouseEvent(1, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
			frame++
			awaitSnapshot()
			assertThat(focused).isEqualTo("inside")
		}
	}

	@Test fun nestedFocusTrapsRestoreEachPreviousTarget() = runTest {
		var focused by mutableStateOf("")
		var showDialog by mutableStateOf(false)
		var showMenu by mutableStateOf(false)

		runMosaicTest {
			setContentAndSnapshot {
				Column {
					FocusText("outside", onFocus = { focused = "outside" })
					if (showDialog) {
						Column(Modifier.focusTrap()) {
							FocusText("dialog", onFocus = { focused = "dialog" })
							if (showMenu) {
								Column(Modifier.focusTrap()) {
									FocusText("menu", onFocus = { focused = "menu" })
								}
							}
						}
					}
				}
			}
			assertThat(focused).isEqualTo("outside")

			showDialog = true
			awaitSnapshot()
			assertThat(focused).isEqualTo("dialog")

			showMenu = true
			awaitSnapshot()
			assertThat(focused).isEqualTo("menu")

			showMenu = false
			awaitSnapshot()
			assertThat(focused).isEqualTo("dialog")

			showDialog = false
			awaitSnapshot()
			assertThat(focused).isEqualTo("outside")
		}
	}

	@Test fun siblingOverlayTrapRestoresTheExactParentTarget() = runTest {
		var focused by mutableStateOf("")
		var showChildMenu by mutableStateOf(false)

		runMosaicTest {
			setContentAndSnapshot {
				Column {
					Column(Modifier.focusTrap()) {
						FocusText("first", autoFocus = true, onFocus = { focused = "first" })
						FocusText("second", onFocus = { focused = "second" })
					}
					if (showChildMenu) {
						Column(Modifier.focusTrap()) {
							FocusText("child", onFocus = { focused = "child" })
						}
					}
				}
			}
			assertThat(focused).isEqualTo("first")

			sendKeyEvent(KeyboardEvent(9))
			awaitSnapshot()
			assertThat(focused).isEqualTo("second")

			showChildMenu = true
			awaitSnapshot()
			assertThat(focused).isEqualTo("child")

			showChildMenu = false
			awaitSnapshot()
			assertThat(focused).isEqualTo("second")
		}
	}

	@Test fun disabledFocusTrapIsTransparentToDirectionalNavigation() = runTest {
		var focused by mutableStateOf("")

		runMosaicTest {
			setContentAndSnapshot {
				Column {
					FocusText("outside", onFocus = { focused = "outside" })
					Column(Modifier.focusTrap(enabled = false)) {
						FocusText("inside", onFocus = { focused = "inside" })
					}
				}
			}

			sendKeyEvent(KeyboardEvent(9))
			awaitSnapshot()
			assertThat(focused).isEqualTo("inside")

			sendKeyEvent(KeyboardEvent(KeyboardEvent.Up))
			awaitSnapshot()
			assertThat(focused).isEqualTo("outside")
		}
	}

	@Test fun disabledFocusableIsTransparentToRequesterSearch() = runTest {
		val requester = FocusRequester()
		var focused by mutableStateOf("")

		runMosaicTest {
			setContentAndSnapshot {
				Column {
					FocusText("outside", onFocus = { focused = "outside" })
					Column(
						Modifier
							.focusRequester(requester)
							.focusable(enabled = false),
					) {
						FocusText("inside", onFocus = { focused = "inside" })
					}
				}
			}
			assertThat(focused).isEqualTo("outside")

			assertThat(requester.requestFocus()).isTrue()
			awaitSnapshot()
			assertThat(focused).isEqualTo("inside")
		}
	}

	@Test fun focusEventOnlyObservesTargetsAfterItInTheModifierChain() = runTest {
		val states = mutableListOf<FocusState>()

		runMosaicTest {
			setContentAndSnapshot {
				Text(
					value = "target",
					modifier = Modifier
						.focusable()
						.onFocusChanged(states::add),
				)
			}

			assertThat(states).isEqualTo(listOf(FocusState.Inactive))
		}
	}

	@Test fun focusEventAggregatesSiblingTargetsWithoutSpuriousChanges() = runTest {
		val states = mutableListOf<FocusState>()

		runMosaicTest {
			setContentAndSnapshot {
				Row(Modifier.onFocusChanged(states::add)) {
					FocusText("first")
					FocusText("second")
				}
			}
			assertThat(states).isEqualTo(listOf(FocusState.Active))

			sendKeyEvent(KeyboardEvent(9))
			awaitSnapshot()
			assertThat(states).isEqualTo(listOf(FocusState.Active))
		}
	}

	@Test fun focusEventReportsActiveParentForFocusedDescendant() = runTest {
		val states = mutableListOf<FocusState>()

		runMosaicTest {
			setContentAndSnapshot {
				Column(
					Modifier
						.onFocusChanged(states::add)
						.focusGroup(),
				) {
					FocusText("child")
				}
			}

			assertThat(states).isEqualTo(listOf(FocusState.ActiveParent))
			assertThat(states.single().isFocused).isFalse()
			assertThat(states.single().hasFocus).isTrue()
		}
	}

	@Test fun reentrantFocusRequestFromCallbackWins() = runTest {
		val secondRequester = FocusRequester()
		val thirdRequester = FocusRequester()
		var redirected = false
		var focused = ""
		val activations = mutableListOf<String>()

		runMosaicTest {
			setContentAndSnapshot {
				Row {
					Text(
						value = "first",
						modifier = Modifier
							.onFocusChanged { state ->
								if (state.isFocused) {
									focused = "first"
									activations += "first"
								} else if (!redirected) {
									redirected = true
									thirdRequester.requestFocus()
								}
							}
							.focusable(),
					)
					FocusText(
						label = "second",
						modifier = Modifier.focusRequester(secondRequester),
						onFocus = {
							focused = "second"
							activations += "second"
						},
					)
					FocusText(
						label = "third",
						modifier = Modifier.focusRequester(thirdRequester),
						onFocus = {
							focused = "third"
							activations += "third"
						},
					)
				}
			}
			assertThat(focused).isEqualTo("first")

			assertThat(secondRequester.requestFocus()).isFalse()
			awaitSnapshot()
			assertThat(focused).isEqualTo("third")
			assertThat(activations).isEqualTo(listOf("first", "third"))
		}
	}

	@Test fun directionalSearchAcceptsPartiallyOverlappingCandidate() = runTest {
		var focused by mutableStateOf("")

		runMosaicTest {
			setContentAndSnapshot {
				Box {
					Text("             ")
					FocusText("source", onFocus = { focused = "source" })
					FocusText(
						label = "candidate",
						modifier = Modifier.offset(x = 4),
						onFocus = { focused = "candidate" },
					)
				}
			}
			assertThat(focused).isEqualTo("source")

			sendKeyEvent(KeyboardEvent(KeyboardEvent.Right))
			awaitSnapshot()
			assertThat(focused).isEqualTo("candidate")
		}
	}

	@Test fun directionalSearchUsesWeightedDistanceOutsideTheBeam() {
		val focused = FocusBounds(IntOffset(0, 0), IntSize(2, 2))
		val nearButFarOffAxis = FocusBounds(IntOffset(3, 100), IntSize(2, 2))
		val fartherButNearAxis = FocusBounds(IntOffset(12, 3), IntSize(2, 2))

		assertThat(
			fartherButNearAxis.isBetterCandidateThan(
				currentCandidate = nearButFarOffAxis,
				focused = focused,
				direction = FocusDirection.Right,
			),
		).isTrue()
	}

	@Test fun unrelatedModifierChangesKeepTheFocusedTargetIdentity() = runTest {
		var focused by mutableStateOf("")
		var decorateSecond by mutableStateOf(false)

		runMosaicTest {
			setContentAndSnapshot {
				Column(Modifier.focusTrap()) {
					FocusText("first", autoFocus = true, onFocus = { focused = "first" })
					FocusText(
						label = "second",
						modifier = if (decorateSecond) Modifier.composed { this } else Modifier,
						onFocus = { focused = "second" },
					)
				}
			}
			assertThat(focused).isEqualTo("first")

			sendKeyEvent(KeyboardEvent(9))
			awaitSnapshot()
			assertThat(focused).isEqualTo("second")

			decorateSecond = true
			awaitSnapshot()
			assertThat(focused).isEqualTo("second")
		}
	}

	@Test fun focusRequesterMovesFocusAsAnExplicitEscapeHatch() = runTest {
		val requester = FocusRequester()
		var focused by mutableStateOf("")

		runMosaicTest {
			setContentAndSnapshot {
				Row {
					FocusText("first", onFocus = { focused = "first" })
					FocusText(
						label = "second",
						modifier = Modifier.focusRequester(requester),
						onFocus = { focused = "second" },
					)
				}
			}
			assertThat(focused).isEqualTo("first")

			assertThat(requester.requestFocus(FocusDirection.Enter)).isTrue()
			awaitSnapshot()
			assertThat(focused).isEqualTo("second")
		}
	}

	@Test fun focusRequesterSearchesItsModifierDescendants() = runTest {
		val requester = FocusRequester()
		var focused by mutableStateOf("")

		runMosaicTest {
			setContentAndSnapshot {
				Column {
					FocusText("outside", onFocus = { focused = "outside" })
					Row(Modifier.focusRequester(requester)) {
						FocusText("first", onFocus = { focused = "first" })
						FocusText("second", onFocus = { focused = "second" })
					}
				}
			}
			assertThat(focused).isEqualTo("outside")

			assertThat(requester.requestFocus()).isTrue()
			awaitSnapshot()
			assertThat(focused).isEqualTo("first")
		}
	}

	@Test fun focusRequesterUsesDirectionWhenEnteringAFocusGroup() = runTest {
		val requester = FocusRequester()
		var focused by mutableStateOf("")

		runMosaicTest {
			setContentAndSnapshot {
				Column {
					FocusText("outside", onFocus = { focused = "outside" })
					Row(
						Modifier
							.focusRequester(requester)
							.focusGroup(),
					) {
						FocusText("left", onFocus = { focused = "left" })
						FocusText("right", onFocus = { focused = "right" })
					}
				}
			}

			assertThat(requester.requestFocus(FocusDirection.Exit)).isFalse()
			assertThat(focused).isEqualTo("outside")

			assertThat(requester.requestFocus(FocusDirection.Right)).isTrue()
			awaitSnapshot()
			assertThat(focused).isEqualTo("left")

			assertThat(requester.requestFocus(FocusDirection.Left)).isTrue()
			awaitSnapshot()
			assertThat(focused).isEqualTo("right")

			assertThat(requester.requestFocus(FocusDirection.Enter)).isTrue()
			awaitSnapshot()
			assertThat(focused).isEqualTo("left")
		}
	}

	@Test fun focusRequesterCanBeAssociatedWithMultipleModifierNodes() = runTest {
		val requester = FocusRequester()
		var focused by mutableStateOf("")

		runMosaicTest {
			setContentAndSnapshot {
				Column {
					FocusText("outside", onFocus = { focused = "outside" })
					FocusText(
						label = "first",
						modifier = Modifier.focusRequester(requester),
						onFocus = { focused = "first" },
					)
					FocusText(
						label = "second",
						modifier = Modifier.focusRequester(requester),
						onFocus = { focused = "second" },
					)
				}
			}
			assertThat(focused).isEqualTo("outside")

			assertThat(requester.requestFocus()).isTrue()
			awaitSnapshot()
			assertThat(focused).isEqualTo("second")
		}
	}

	@Test fun focusRequesterOnlySearchesForwardInTheModifierChain() = runTest {
		val requester = FocusRequester()

		runMosaicTest {
			setContentAndSnapshot {
				Text(
					value = "target",
					modifier = Modifier
						.focusable()
						.focusRequester(requester),
				)
			}

			assertThat(requester.requestFocus()).isFalse()
		}
	}

	@Test fun changingTheRequesterDetachesThePreviousInstance() = runTest {
		val firstRequester = FocusRequester()
		val secondRequester = FocusRequester()
		var useSecondRequester by mutableStateOf(false)

		runMosaicTest {
			setContentAndSnapshot {
				Text(
					value = "target",
					modifier = Modifier
						.focusRequester(if (useSecondRequester) secondRequester else firstRequester)
						.focusable(),
				)
			}
			assertThat(firstRequester.requestFocus()).isTrue()

			useSecondRequester = true
			awaitSnapshot()
			assertThat(firstRequester.requestFocus()).isFalse()
			assertThat(secondRequester.requestFocus()).isTrue()
		}
	}

	@Test fun cancellingTheCompositionDetachesTheRequesterNode() = runTest {
		val requester = FocusRequester()

		runMosaicTest {
			setContentAndSnapshot {
				Text(
					value = "target",
					modifier = Modifier
						.focusRequester(requester)
						.focusable(),
				)
			}
			assertThat(requester.requestFocus()).isTrue()
		}

		assertThat(requester.requestFocus()).isFalse()
	}

	@Test fun focusCursorUsesTerminalCellCoordinatesRelativeToItsTarget() = runTest {
		runMosaicTest(MosaicSnapshots) {
			val mosaic = setContentAndSnapshot {
				Row {
					Text("xx")
					FocusText(
						label = "target",
						modifier = Modifier.focusCursor(column = 2),
					)
				}
			}

			assertThat(mosaic.cursorPosition).isEqualTo(
				TerminalCursorPosition(row = 0, column = 4),
			)
		}
	}

	@Test fun focusCursorTemporarilyOverridesTheExplicitCursor() = runTest {
		var focusEnabled by mutableStateOf(true)

		runMosaicTest(MosaicSnapshots) {
			val explicitPosition = TerminalCursorPosition(row = 2, column = 4)
			val mosaic = setContentAndSnapshot {
				TerminalCursor(explicitPosition)
				Row {
					Text("xx")
					Text(
						value = "target",
						modifier = Modifier
							.focusCursor(column = 1)
							.focusable(enabled = focusEnabled),
					)
				}
			}
			assertThat(mosaic.cursorPosition).isEqualTo(
				TerminalCursorPosition(row = 0, column = 3),
			)

			focusEnabled = false
			awaitSnapshot()
			assertThat(mosaic.cursorPosition).isEqualTo(explicitPosition)
		}
	}
}

@Composable
private fun FocusText(
	label: String,
	modifier: Modifier = Modifier,
	autoFocus: Boolean = false,
	onFocus: () -> Unit = {},
) {
	Text(
		value = label,
		modifier = modifier
			.onFocusChanged { state ->
				if (state == FocusState.Active) onFocus()
			}
			.focusable(autoFocus = autoFocus),
	)
}
