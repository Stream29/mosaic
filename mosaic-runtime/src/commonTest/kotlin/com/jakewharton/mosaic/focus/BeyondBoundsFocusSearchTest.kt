package com.jakewharton.mosaic.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.jakewharton.mosaic.layout.BeyondBoundsLayout
import com.jakewharton.mosaic.layout.BeyondBoundsLayoutProviderModifierNode
import com.jakewharton.mosaic.layout.BringIntoViewModifierNode
import com.jakewharton.mosaic.layout.LayoutCoordinates
import com.jakewharton.mosaic.layout.Remeasurement
import com.jakewharton.mosaic.layout.RemeasurementModifier
import com.jakewharton.mosaic.layout.bringIntoView
import com.jakewharton.mosaic.layout.clipToBounds
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.node.ModifierNodeElement
import com.jakewharton.mosaic.node.requireLayoutCoordinates
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.SubcomposeLayout
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.Constraints
import com.jakewharton.mosaic.ui.unit.IntRect
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class BeyondBoundsFocusSearchTest {
	@Test fun focusSearchRemeasuresUntilAHiddenTargetBecomesVisible() = runTest {
		val beyondBoundsLayout = TestBeyondBoundsLayout(lastItemIndex = 2)
		var focused by mutableStateOf("")

		runMosaicTest {
			setContentAndSnapshot {
				Row {
					SubcomposeLayout(
						modifier = Modifier
							.then(TestBeyondBoundsElement(beyondBoundsLayout))
							.clipToBounds(),
					) {
						val items = (beyondBoundsLayout.firstItem..beyondBoundsLayout.lastItem)
							.map { index ->
								index to subcompose(index) {
									if (index == 0 || index == 2) {
										FocusLabel(index.toString()) { focused = index.toString() }
									} else {
										Text(".")
									}
								}.map { measurable -> measurable.measure(Constraints()) }
							}
						layout(width = 1, height = 1) {
							for ((index, placeables) in items) {
								for (placeable in placeables) {
									placeable.place(index - beyondBoundsLayout.firstItem, 0)
								}
							}
						}
					}
					FocusLabel("outside") { focused = "outside" }
				}
			}
			assertThat(focused).isEqualTo("0")

			sendKeyEvent(KeyboardEvent(9))
			awaitSnapshot()

			assertThat(focused).isEqualTo("2")
			assertThat(beyondBoundsLayout.layoutCalls).isEqualTo(1)
			assertThat(beyondBoundsLayout.bringIntoViewCalls).isEqualTo(1)
		}
	}
}

private class TestBeyondBoundsLayout(
	private val lastItemIndex: Int,
) : BeyondBoundsLayout {
	var firstItem = 0
		private set
	var lastItem = 0
		private set
	var layoutCalls = 0
		private set
	var bringIntoViewCalls = 0
		private set

	private lateinit var remeasurement: Remeasurement

	fun onRemeasurementAvailable(remeasurement: Remeasurement) {
		this.remeasurement = remeasurement
	}

	override fun <T> layout(
		direction: BeyondBoundsLayout.LayoutDirection,
		block: BeyondBoundsLayout.BeyondBoundsScope.() -> T?,
	): T? {
		if (direction != BeyondBoundsLayout.LayoutDirection.After) return null
		layoutCalls++
		var result: T? = null
		while (result == null && lastItem < lastItemIndex) {
			lastItem++
			remeasurement.forceRemeasure()
			result = block(
				object : BeyondBoundsLayout.BeyondBoundsScope {
					override val hasMoreContent: Boolean
						get() = lastItem < lastItemIndex
				},
			)
		}
		lastItem = firstItem
		remeasurement.forceRemeasure()
		return result
	}

	fun bringChildIntoView(
		childCoordinates: LayoutCoordinates,
		boundsProvider: () -> IntRect?,
		containerCoordinates: LayoutCoordinates,
	) {
		val bounds = boundsProvider() ?: return
		val localBounds = bounds.translate(childCoordinates.position - containerCoordinates.position)
		if (localBounds.left >= 0 && localBounds.right <= containerCoordinates.size.width) return

		bringIntoViewCalls++
		firstItem = lastItem
		remeasurement.forceRemeasure()
	}
}

private data class TestBeyondBoundsElement(
	private val layout: TestBeyondBoundsLayout,
) : ModifierNodeElement<TestBeyondBoundsNode>(),
	RemeasurementModifier {
	override fun create(): TestBeyondBoundsNode = TestBeyondBoundsNode(layout)

	override fun update(node: TestBeyondBoundsNode) {
		node.layout = layout
	}

	override fun onRemeasurementAvailable(remeasurement: Remeasurement) {
		layout.onRemeasurementAvailable(remeasurement)
	}
}

private class TestBeyondBoundsNode(
	var layout: TestBeyondBoundsLayout,
) : Modifier.Node(),
	BeyondBoundsLayoutProviderModifierNode,
	BringIntoViewModifierNode {
	override val beyondBoundsLayout: BeyondBoundsLayout
		get() = layout

	override suspend fun bringIntoView(
		childCoordinates: LayoutCoordinates,
		boundsProvider: () -> IntRect?,
	) {
		layout.bringChildIntoView(
			childCoordinates = childCoordinates,
			boundsProvider = boundsProvider,
			containerCoordinates = requireLayoutCoordinates(),
		)
		bringIntoView {
			if (!isAttached || !childCoordinates.isAttached) return@bringIntoView null
			boundsProvider()?.translate(
				childCoordinates.position - requireLayoutCoordinates().position,
			)
		}
	}
}

@Composable
private fun FocusLabel(
	label: String,
	onFocus: () -> Unit,
) {
	Text(
		value = label,
		modifier = Modifier
			.onFocusChanged { state ->
				if (state == FocusState.Active) onFocus()
			}
			.focusable(),
	)
}
