package com.jakewharton.mosaic.node

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isFalse
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import com.jakewharton.mosaic.MosaicNodeApplier
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.NodeFactory
import kotlin.test.Test
import kotlinx.coroutines.Job

class ModifierNodeTest {
	@Test fun nodeIsReusedUpdatedAndDetachedWithItsElement() {
		val recording = NodeLifecycleRecording()
		val layoutNode = NodeFactory()
		layoutNode.setModifier(
			Modifier.then(RecordingElement(1, recording)),
		)

		val modifierNode = recording.createdNodes.single()
		assertThat(modifierNode.isAttached).isFalse()

		val applier = MosaicNodeApplier()
		applier.insertBottomUp(0, layoutNode)
		assertThat(modifierNode.isAttached).isTrue()
		assertThat(recording.events).containsExactly("create:1", "attach:1")

		layoutNode.setModifier(
			Modifier.then(RecordingElement(2, recording)),
		)
		assertThat(recording.createdNodes.single()).isSameInstanceAs(modifierNode)
		assertThat(recording.events).containsExactly("create:1", "attach:1", "update:2")

		layoutNode.setModifier(
			Modifier.then(RecordingElement(2, recording)),
		)
		assertThat(recording.events).containsExactly("create:1", "attach:1", "update:2")

		layoutNode.onReuse()
		assertThat(recording.events).containsExactly("create:1", "attach:1", "update:2", "reset:2")

		applier.remove(0, 1)
		assertThat(modifierNode.isAttached).isFalse()
		assertThat(modifierNode.attachmentJob.isActive).isFalse()
		assertThat(recording.events).containsExactly(
			"create:1",
			"attach:1",
			"update:2",
			"reset:2",
			"detach:2",
		)
	}

	@Test fun wholeModifierChainIsAttachedDuringLifecycleCallbacks() {
		val recording = NodeLifecycleRecording()
		val layoutNode = NodeFactory()
		layoutNode.setModifier(
			Modifier
				.then(RecordingElement(1, recording))
				.then(RecordingElement(2, recording)),
		)

		val applier = MosaicNodeApplier()
		applier.insertBottomUp(0, layoutNode)
		applier.remove(0, 1)

		assertThat(recording.events).containsExactly(
			"create:1",
			"create:2",
			"attach:1",
			"attach:2",
			"detach:2",
			"detach:1",
		)
	}

	@Test fun differentElementTypeReplacesNode() {
		val recording = NodeLifecycleRecording()
		val layoutNode = NodeFactory()
		layoutNode.setModifier(Modifier.then(RecordingElement(1, recording)))
		val replacedNode = recording.createdNodes.single()
		val applier = MosaicNodeApplier()
		applier.insertBottomUp(0, layoutNode)

		layoutNode.setModifier(Modifier.then(PrefixElement(recording)))
		assertThat(replacedNode.isAttached).isFalse()
		assertThat(recording.events).containsExactly(
			"create:1",
			"attach:1",
			"prefix:create",
			"detach:1",
			"prefix:attach",
		)

		applier.remove(0, 1)
		assertThat(recording.events).containsExactly(
			"create:1",
			"attach:1",
			"prefix:create",
			"detach:1",
			"prefix:attach",
			"prefix:detach",
		)
	}

	@Test fun legacyElementsParticipateInStructuralDiff() {
		val recording = NodeLifecycleRecording()
		val layoutNode = NodeFactory()
		layoutNode.setModifier(
			Modifier
				.then(RecordingElement(1, recording))
				.then(LeadingLegacyElement)
				.then(RecordingElement(2, recording)),
		)
		val firstNode = recording.createdNodes[0]
		val secondNode = recording.createdNodes[1]
		val applier = MosaicNodeApplier()
		applier.insertBottomUp(0, layoutNode)
		val secondAttachmentJob = secondNode.attachmentJob

		layoutNode.setModifier(
			Modifier
				.then(LeadingLegacyElement)
				.then(RecordingElement(2, recording))
				.then(TrailingLegacyElement),
		)

		val remainingNode = generateSequence(layoutNode.modifierNodeTail) { node -> node.parent }
			.single()
		assertThat(remainingNode).isSameInstanceAs(secondNode)
		assertThat(firstNode.isAttached).isFalse()
		assertThat(secondNode.isAttached).isTrue()
		assertThat(secondNode.attachmentJob).isSameInstanceAs(secondAttachmentJob)
		assertThat(recording.events).containsExactly(
			"create:1",
			"create:2",
			"attach:1",
			"attach:2",
			"detach:1",
		)

		applier.remove(0, 1)
		assertThat(recording.events).containsExactly(
			"create:1",
			"create:2",
			"attach:1",
			"attach:2",
			"detach:1",
			"detach:2",
		)
	}

	@Test fun structuralUpdateKeepsReusableNodesAttached() {
		val recording = NodeLifecycleRecording()
		val element = RecordingElement(1, recording)
		val layoutNode = NodeFactory()
		layoutNode.setModifier(Modifier.then(element))
		val modifierNode = recording.createdNodes.single()
		val applier = MosaicNodeApplier()
		applier.insertBottomUp(0, layoutNode)
		val attachmentJob = modifierNode.attachmentJob

		layoutNode.setModifier(
			Modifier
				.then(PrefixElement(recording))
				.then(element),
		)
		assertThat(modifierNode.isAttached).isTrue()
		assertThat(modifierNode.attachmentJob).isSameInstanceAs(attachmentJob)

		layoutNode.setModifier(Modifier.then(element))
		assertThat(modifierNode.isAttached).isTrue()
		assertThat(modifierNode.attachmentJob).isSameInstanceAs(attachmentJob)
		applier.remove(0, 1)
		assertThat(attachmentJob.isActive).isFalse()
		assertThat(recording.events).containsExactly(
			"create:1",
			"attach:1",
			"prefix:create",
			"prefix:attach",
			"prefix:detach",
			"detach:1",
		)
	}
}

private data object LeadingLegacyElement : Modifier.Element

private data object TrailingLegacyElement : Modifier.Element

private class NodeLifecycleRecording {
	val events = mutableListOf<String>()
	val createdNodes = mutableListOf<RecordingNode>()
}

private data class RecordingElement(
	val value: Int,
	val recording: NodeLifecycleRecording,
) : ModifierNodeElement<RecordingNode>() {
	override fun create(): RecordingNode = RecordingNode(value, recording).also { node ->
		recording.events += "create:$value"
		recording.createdNodes += node
	}

	override fun update(node: RecordingNode) {
		node.value = value
		recording.events += "update:$value"
	}
}

private class RecordingNode(
	var value: Int,
	private val recording: NodeLifecycleRecording,
) : Modifier.Node() {
	lateinit var attachmentJob: Job
		private set

	override fun onAttach() {
		check(recording.createdNodes.filter { node -> node.layoutNode != null }.all(Modifier.Node::isAttached))
		recording.events += "attach:$value"
		attachmentJob = checkNotNull(coroutineScope.coroutineContext[Job])
	}

	override fun onDetach() {
		check(recording.createdNodes.filter { node -> node.layoutNode != null }.all(Modifier.Node::isAttached))
		check(attachmentJob.isActive)
		recording.events += "detach:$value"
	}

	override fun onReset() {
		recording.events += "reset:$value"
	}
}

private data class PrefixElement(
	val recording: NodeLifecycleRecording,
) : ModifierNodeElement<PrefixNode>() {
	override fun create(): PrefixNode = PrefixNode(recording).also {
		recording.events += "prefix:create"
	}

	override fun update(node: PrefixNode): Unit = Unit
}

private class PrefixNode(
	private val recording: NodeLifecycleRecording,
) : Modifier.Node() {
	override fun onAttach() {
		recording.events += "prefix:attach"
	}

	override fun onDetach() {
		recording.events += "prefix:detach"
	}
}
