package com.jakewharton.mosaic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.jakewharton.mosaic.layout.Measurable
import com.jakewharton.mosaic.layout.MeasurePolicy
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.unit.Constraints
import com.jakewharton.mosaic.ui.unit.IntSize
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class SubcomposeLayoutTest {
	@Test fun precomposedAndPremeasuredSlotIsAdoptedWithoutRepeatedWork() = runTest {
		val state = SubcomposeLayoutState()
		val showItem = mutableStateOf(false)
		var compositionCount = 0
		var measureCount = 0
		val itemContent: @Composable @MosaicComposable () -> Unit = {
			compositionCount++
			Layout(
				content = {},
				measurePolicy = MeasurePolicy { _, _ ->
					measureCount++
					layout(1, 1) {}
				},
			)
		}
		val itemConstraints = Constraints(maxWidth = 10, maxHeight = 10)

		runMosaicTest {
			setContent {
				SubcomposeLayout(state) {
					val placeable = if (showItem.value) {
						subcompose("item", content = itemContent).single().measure(itemConstraints)
					} else {
						null
					}
					layout(1, 1) {
						placeable?.place(0, 0)
					}
				}
			}
			awaitSnapshot()

			val handle = state.precompose("item", content = itemContent)
			assertThat(compositionCount).isEqualTo(1)
			assertThat(handle.placeablesCount).isEqualTo(1)
			assertThat(handle.premeasure(0, itemConstraints)).isEqualTo(IntSize(1, 1))
			assertThat(measureCount).isEqualTo(1)

			showItem.value = true
			awaitSnapshot()
			assertThat(compositionCount).isEqualTo(1)
			assertThat(measureCount).isEqualTo(1)
			assertThat(handle.placeablesCount).isEqualTo(0)
			handle.dispose()
		}
	}

	@Test fun disposingPrecomposedSlotDisposesItsEffects() = runTest {
		val state = SubcomposeLayoutState()
		var disposeCount = 0

		runMosaicTest {
			setContent {
				SubcomposeLayout(state) {
					layout(1, 1) {}
				}
			}
			awaitSnapshot()

			val handle = state.precompose("item") {
				DisposableEffect(Unit) {
					onDispose { disposeCount++ }
				}
				Text("item")
			}
			assertThat(handle.placeablesCount).isEqualTo(1)
			handle.dispose()
			handle.dispose()
			assertThat(handle.placeablesCount).isEqualTo(0)
			assertThat(disposeCount).isEqualTo(1)
		}
	}

	@Test fun rendersOnlyRequestedSlots() = runTest {
		val keys = mutableStateOf(listOf("a", "b"))

		runMosaicTest {
			setContent {
				TestSubcomposeColumn(keys.value) { key ->
					Text(key.uppercase())
				}
			}

			assertThat(awaitSnapshot()).isEqualTo("A\nB")
			keys.value = listOf("b")
			assertThat(awaitSnapshot()).isEqualTo("B")
		}
	}

	@Test fun movingStableKeysPreservesRememberedState() = runTest {
		val keys = mutableStateOf(listOf("a", "b"))
		var nextIdentity = 0

		runMosaicTest {
			setContent {
				TestSubcomposeColumn(keys.value) { key ->
					val identity = remember { nextIdentity++ }
					Text("$key:$identity")
				}
			}

			assertThat(awaitSnapshot()).isEqualTo("a:0\nb:1")
			keys.value = listOf("b", "a")
			assertThat(awaitSnapshot()).isEqualTo("b:1\na:0")
		}
	}

	@Test fun reuseForAnotherKeyClearsRememberedStateAndEffects() = runTest {
		val keys = mutableStateOf(listOf("a"))
		val state = SubcomposeLayoutState(maxSlotsToRetainForReuse = 1)
		var nextIdentity = 0
		val disposed = mutableListOf<String>()

		runMosaicTest {
			setContent {
				TestSubcomposeColumn(state, keys.value, contentType = "row") { key ->
					val identity = remember { nextIdentity++ }
					DisposableEffect(key) {
						onDispose { disposed += key }
					}
					Text("$key:$identity")
				}
			}

			assertThat(awaitSnapshot()).isEqualTo("a:0")
			keys.value = emptyList()
			assertThat(awaitSnapshot()).isEqualTo("")
			assertThat(disposed).isEqualTo(emptyList())

			keys.value = listOf("b")
			assertThat(awaitSnapshot()).isEqualTo("b:1")
			assertThat(disposed).isEqualTo(listOf("a"))
		}
	}

	@Test fun changingContentTypeForSameKeyPreservesRememberedState() = runTest {
		val keys = mutableStateOf(listOf("a"))
		val contentType = mutableStateOf("first")
		var nextIdentity = 0

		runMosaicTest {
			setContent {
				TestSubcomposeColumn(
					state = remember { SubcomposeLayoutState(maxSlotsToRetainForReuse = 1) },
					keys = keys.value,
					contentType = contentType.value,
				) { key ->
					val identity = remember { nextIdentity++ }
					Text("$key:$identity")
				}
			}

			assertThat(awaitSnapshot()).isEqualTo("a:0")
			keys.value = emptyList()
			assertThat(awaitSnapshot()).isEqualTo("")
			contentType.value = "second"
			keys.value = listOf("a")
			assertThat(awaitSnapshot()).isEqualTo("a:0")
		}
	}

	@Test fun reuseDisposesNestedSubcomposition() = runTest {
		val keys = mutableStateOf(listOf("a"))
		val state = SubcomposeLayoutState(maxSlotsToRetainForReuse = 1)
		val disposed = mutableListOf<String>()

		runMosaicTest {
			setContent {
				TestSubcomposeColumn(state, keys.value, contentType = "row") { outerKey ->
					TestSubcomposeColumn(listOf("inner")) { innerKey ->
						DisposableEffect(outerKey) {
							onDispose { disposed += outerKey }
						}
						Text("$outerKey/$innerKey")
					}
				}
			}

			assertThat(awaitSnapshot()).isEqualTo("a/inner")
			keys.value = emptyList()
			assertThat(awaitSnapshot()).isEqualTo("")

			keys.value = listOf("b")
			assertThat(awaitSnapshot()).isEqualTo("b/inner")
			assertThat(disposed).isEqualTo(listOf("a"))
		}
	}

	@Test fun replacingConfigurationPreservesRetainedSlot() = runTest {
		val keys = mutableStateOf(listOf("a"))
		val state = mutableStateOf(SubcomposeLayoutState(maxSlotsToRetainForReuse = 1))
		var nextIdentity = 0

		runMosaicTest {
			setContent {
				TestSubcomposeColumn(state.value, keys.value) { key ->
					val identity = remember { nextIdentity++ }
					Text("$key:$identity")
				}
			}

			assertThat(awaitSnapshot()).isEqualTo("a:0")
			keys.value = emptyList()
			assertThat(awaitSnapshot()).isEqualTo("")

			state.value = SubcomposeLayoutState(maxSlotsToRetainForReuse = 1)
			keys.value = listOf("a")
			assertThat(awaitSnapshot()).isEqualTo("a:0")
		}
	}

	@Test fun retentionKeepsMostRecentlyInactiveSlots() = runTest {
		val keys = mutableStateOf(listOf("a", "b", "c"))
		val state = SubcomposeLayoutState(maxSlotsToRetainForReuse = 1)
		var nextIdentity = 0

		runMosaicTest {
			setContent {
				TestSubcomposeColumn(state, keys.value) { key ->
					val identity = remember { nextIdentity++ }
					Text("$key:$identity")
				}
			}

			assertThat(awaitSnapshot()).isEqualTo("a:0\nb:1\nc:2")
			keys.value = listOf("b", "c", "d")
			assertThat(awaitSnapshot()).isEqualTo("b:1\nc:2\nd:3")
			keys.value = listOf("c", "d", "e")
			assertThat(awaitSnapshot()).isEqualTo("c:2\nd:3\ne:4")
			keys.value = listOf("b")
			assertThat(awaitSnapshot()).isEqualTo("b:1")
		}
	}

	@Test fun shrinkingRetentionDisposesExcessSlots() = runTest {
		val keys = mutableStateOf(listOf("a", "b"))
		val state = mutableStateOf(SubcomposeLayoutState(maxSlotsToRetainForReuse = 2))
		val disposed = mutableListOf<String>()

		runMosaicTest {
			setContent {
				TestSubcomposeColumn(state.value, keys.value) { key ->
					DisposableEffect(key) {
						onDispose { disposed += key }
					}
					Text(key)
				}
			}

			assertThat(awaitSnapshot()).isEqualTo("a\nb")
			keys.value = emptyList()
			assertThat(awaitSnapshot()).isEqualTo("")
			assertThat(disposed).isEqualTo(emptyList())

			state.value = SubcomposeLayoutState(maxSlotsToRetainForReuse = 0)
			assertThat(awaitSnapshot()).isEqualTo("")
			assertThat(disposed.sorted()).isEqualTo(listOf("a", "b"))
		}
	}

	@Test fun removingParentDisposesSlotComposition() = runTest {
		val showLayout = mutableStateOf(true)
		var disposeCount = 0

		runMosaicTest {
			setContent {
				if (showLayout.value) {
					TestSubcomposeColumn(listOf("a")) { key ->
						DisposableEffect(key) {
							onDispose { disposeCount++ }
						}
						Text(key)
					}
				} else {
					Text("gone")
				}
			}

			assertThat(awaitSnapshot()).isEqualTo("a")
			showLayout.value = false
			assertThat(awaitSnapshot()).isEqualTo("gone")
			assertThat(disposeCount).isEqualTo(1)
		}
	}

	@Test fun retainedSlotInvalidationRequestsAnotherLayout() = runTest {
		val keys = mutableStateOf(listOf("a"))
		val retainedValue = mutableIntStateOf(0)
		val state = SubcomposeLayoutState(maxSlotsToRetainForReuse = 1)
		var compositionCount = 0

		runMosaicTest {
			setContent {
				TestSubcomposeColumn(state, keys.value) {
					compositionCount++
					Text(retainedValue.intValue.toString())
				}
			}

			assertThat(awaitSnapshot()).isEqualTo("0")
			keys.value = emptyList()
			assertThat(awaitSnapshot()).isEqualTo("")
			val countBeforeInvalidation = compositionCount
			retainedValue.intValue = 1
			awaitSnapshot()
			assertThat(compositionCount).isEqualTo(countBeforeInvalidation + 1)
		}
	}

	@Test fun updatedConstraintsReachSubcompositionMeasurePolicy() = runTest {
		val width = mutableIntStateOf(3)
		val measuredWidths = mutableListOf<Int>()

		runMosaicTest {
			setContent {
				SubcomposeLayout(modifier = Modifier) { constraints ->
					val constrained = constraints.copy(minWidth = width.intValue, maxWidth = width.intValue)
					measuredWidths += constrained.maxWidth
					val placeable = subcompose("item") { Text("x") }.single().measure(constrained)
					layout(placeable.width, placeable.height) {
						placeable.place(0, 0)
					}
				}
			}

			assertThat(awaitSnapshot()).isEqualTo("x")
			width.intValue = 5
			assertThat(awaitSnapshot()).isEqualTo("x")
			assertThat(measuredWidths.takeLast(2)).isEqualTo(listOf(3, 5))
		}
	}
}

@Composable
private fun TestSubcomposeColumn(
	keys: List<String>,
	content: @Composable (String) -> Unit,
) {
	TestSubcomposeColumn(remember { SubcomposeLayoutState() }, keys, content = content)
}

/**
 * @param contentType `null` places every key in the default subcomposition reuse class.
 */
@Composable
private fun TestSubcomposeColumn(
	state: SubcomposeLayoutState,
	keys: List<String>,
	contentType: Any? = null,
	content: @Composable (String) -> Unit,
) {
	SubcomposeLayout(state = state) { constraints ->
		val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
		val placeables = keys.flatMap { key ->
			subcompose(key, contentType) { content(key) }
		}.map { measurable: Measurable -> measurable.measure(childConstraints) }
		val width = placeables.maxOfOrNull { it.width } ?: 1
		val height = placeables.sumOf { it.height }.coerceAtLeast(1)
		layout(width, height) {
			var y = 0
			for (placeable in placeables) {
				placeable.place(0, y)
				y += placeable.height
			}
		}
	}
}
