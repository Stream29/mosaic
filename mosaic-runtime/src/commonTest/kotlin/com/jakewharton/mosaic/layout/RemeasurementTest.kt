package com.jakewharton.mosaic.layout

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import com.jakewharton.mosaic.MosaicNodeApplier
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Layout
import com.jakewharton.mosaic.ui.NodeFactory
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.Constraints
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class RemeasurementTest {
	@Test fun forceRemeasureCompletesBeforeReturning() = runTest {
		val modifier = RecordingRemeasurementModifier()
		var requestedWidth = 1
		var measuredWidth = 0

		runMosaicTest {
			assertThat(
				setContentAndSnapshot {
					Layout(
						content = { Text("abc") },
						modifier = Modifier
							.then(modifier)
							.clipToBounds(),
					) { measurables, _ ->
						val placeable = measurables.single().measure(Constraints())
						measuredWidth = requestedWidth
						layout(width = requestedWidth, height = 1) {
							placeable.place(0, 0)
						}
					}
				},
			).isEqualTo("a")

			requestedWidth = 3
			modifier.remeasurement.forceRemeasure()

			assertThat(measuredWidth).isEqualTo(3)
			assertThat(awaitSnapshot()).isEqualTo("abc")
		}
	}

	@Test fun remeasurementIsProvidedAfterAttachmentAndOnAttachedUpdates() {
		val modifier = RecordingRemeasurementModifier()
		val node = NodeFactory()
		node.setModifier(Modifier.then(modifier))
		assertThat(modifier.invocationCount).isEqualTo(0)

		MosaicNodeApplier().insertBottomUp(0, node)
		assertThat(modifier.invocationCount).isEqualTo(1)

		node.setModifier(Modifier.then(modifier))
		assertThat(modifier.invocationCount).isEqualTo(2)
	}

	@Test fun ownerReceivesRequestingNode() {
		val modifier = RecordingRemeasurementModifier()
		val node = NodeFactory()
		lateinit var relayoutNode: MosaicNode
		lateinit var measureAndLayoutNode: MosaicNode
		node.setModifier(Modifier.then(modifier))
		node.attachRoot(
			object : MosaicNodeOwner {
				override val coroutineContext: CoroutineContext
					get() = EmptyCoroutineContext

				override fun onRequestRelayout(node: MosaicNode) {
					relayoutNode = node
				}

				override fun measureAndLayout(node: MosaicNode) {
					measureAndLayoutNode = node
				}
			},
		)

		node.requestRelayout()
		modifier.remeasurement.forceRemeasure()

		assertThat(relayoutNode).isSameInstanceAs(node)
		assertThat(measureAndLayoutNode).isSameInstanceAs(node)
	}
}

private class RecordingRemeasurementModifier : RemeasurementModifier {
	lateinit var remeasurement: Remeasurement
	var invocationCount = 0
		private set

	override fun onRemeasurementAvailable(remeasurement: Remeasurement) {
		invocationCount++
		this.remeasurement = remeasurement
	}
}
