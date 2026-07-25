package com.jakewharton.mosaic.layout

import assertk.assertThat
import assertk.assertions.containsExactly
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.node.ModifierNodeElement
import com.jakewharton.mosaic.node.requireLayoutCoordinates
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.IntRect
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class BringIntoViewModifierNodeTest {
	@Test fun requestVisitsNearestRespondersAndTranslatesBounds() = runTest {
		val recorder = RequestRecorder()
		val requesterElement = RequesterElement()

		runMosaicTest {
			setContentAndSnapshot {
				Text(
					value = "x",
					modifier = Modifier
						.then(ResponderElement("outer", recorder))
						.padding(left = 1)
						.then(ResponderElement("inner", recorder))
						.padding(left = 2)
						.then(requesterElement),
				)
			}

			requesterElement.node.bringIntoView {
				IntRect(left = 0, top = 0, right = 1, bottom = 1)
			}

			assertThat(recorder.requests).containsExactly(
				RecordedRequest(
					responder = "inner",
					bounds = IntRect(left = 2, top = 0, right = 3, bottom = 1),
				),
				RecordedRequest(
					responder = "outer",
					bounds = IntRect(left = 3, top = 0, right = 4, bottom = 1),
				),
			)
		}
	}
}

private data class RecordedRequest(
	val responder: String,
	val bounds: IntRect,
)

private class RequestRecorder {
	val requests = mutableListOf<RecordedRequest>()
}

private data class ResponderElement(
	val name: String,
	val recorder: RequestRecorder,
) : ModifierNodeElement<ResponderNode>() {
	override fun create(): ResponderNode = ResponderNode(name, recorder)

	override fun update(node: ResponderNode) {
		node.name = name
		node.recorder = recorder
	}
}

private class ResponderNode(
	var name: String,
	var recorder: RequestRecorder,
) : Modifier.Node(),
	BringIntoViewModifierNode {
	override suspend fun bringIntoView(
		childCoordinates: LayoutCoordinates,
		boundsProvider: () -> IntRect?,
	) {
		val localBounds = boundsProvider()
			?.translate(childCoordinates.position - requireLayoutCoordinates().position)
			?: return
		recorder.requests += RecordedRequest(name, localBounds)
		bringIntoView { localBounds }
	}
}

private class RequesterElement : ModifierNodeElement<RequesterNode>() {
	lateinit var node: RequesterNode
		private set

	override fun create(): RequesterNode = RequesterNode().also { node = it }

	override fun update(node: RequesterNode): Unit = Unit

	override fun equals(other: Any?): Boolean = other is RequesterElement

	override fun hashCode(): Int = RequesterElementHashCode
}

private class RequesterNode : Modifier.Node()

private const val RequesterElementHashCode = 1
