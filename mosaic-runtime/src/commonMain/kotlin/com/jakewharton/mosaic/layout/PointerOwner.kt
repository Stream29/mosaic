package com.jakewharton.mosaic.layout

import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.ui.unit.IntOffset
import com.jakewharton.mosaic.ui.unit.IntRect

/** Owns pointer hit paths, hover transitions, and press capture for the current layout tree. */
internal class PointerOwner {
	private var tree = PointerTree.Empty
	private var capturedTarget: PointerInputNode? = null
	private var hoveredPath = emptyList<PointerEntry>()
	private var lastEvent: PointerEvent? = null

	fun reconcile(updatedTree: PointerTree) {
		tree = updatedTree
		val capture = capturedTarget
		if (capture != null && tree.entry(capture) == null) {
			capturedTarget = null
		}
		lastEvent?.let(::updateHover)
	}

	fun clear() {
		tree = PointerTree.Empty
		capturedTarget = null
		hoveredPath = emptyList()
		lastEvent = null
	}

	fun dispatch(event: PointerEvent): Boolean {
		lastEvent = event
		updateHover(event)

		if (event.type == MouseEvent.Type.Press) {
			capturedTarget = null
		}
		val path = capturedTarget
			?.let(tree::pathTo)
			?: tree.hitPath(event.position)
		val consumedBy = path.dispatch(event)

		if (
			event.type == MouseEvent.Type.Press &&
			event.button.isCapturable
		) {
			capturedTarget = consumedBy
		}
		if (event.type == MouseEvent.Type.Release) {
			capturedTarget = null
		}
		return consumedBy != null
	}

	private fun updateHover(event: PointerEvent) {
		val nextPath = tree.hitPath(event.position).filter { it.node is PointerHoverNode }
		val sharedCount = hoveredPath.commonNodePrefixSize(nextPath)
		val previousPath = hoveredPath
		hoveredPath = nextPath

		for (index in previousPath.lastIndex downTo sharedCount) {
			previousPath[index].dispatchExit(event)
		}
		for (index in sharedCount until nextPath.size) {
			nextPath[index].dispatchEnter(event)
		}
	}
}

internal class PointerTreeCollector {
	private val entries = mutableListOf<PointerEntry>()
	private val path = mutableListOf<PointerEntry>()
	private val clipStack = mutableListOf<IntRect>()

	fun visitClip(bounds: IntRect, block: () -> Unit) {
		clipStack += clipStack.lastOrNull()?.intersect(bounds) ?: bounds
		block()
		clipStack.removeAt(clipStack.lastIndex)
	}

	fun visitPointerInput(
		node: PointerInputNode,
		coordinates: LayoutCoordinates,
		block: () -> Unit,
	) {
		visit(node, coordinates, block)
	}

	fun visitPointerHover(
		node: PointerHoverNode,
		coordinates: LayoutCoordinates,
		block: () -> Unit,
	) {
		visit(node, coordinates, block)
	}

	fun build(): PointerTree = PointerTree(entries.toList())

	private fun visit(
		node: PointerNode,
		coordinates: LayoutCoordinates,
		block: () -> Unit,
	) {
		val entry = PointerEntry(
			node = node,
			coordinates = coordinates,
			parent = path.lastOrNull(),
			clipBounds = clipStack.lastOrNull(),
		)
		entries += entry
		path += entry
		block()
		path.removeAt(path.lastIndex)
	}
}

internal class PointerTree(
	private val entries: List<PointerEntry>,
) {
	private val entriesByNode = entries.associateBy(PointerEntry::node)

	fun entry(node: PointerNode): PointerEntry? = entriesByNode[node]

	fun hitPath(position: IntOffset): List<PointerEntry> {
		val target = entries.lastOrNull { it.contains(position) } ?: return emptyList()
		return target.pathFromRoot()
	}

	fun pathTo(node: PointerInputNode): List<PointerEntry> = entriesByNode[node]?.pathFromRoot() ?: emptyList()

	companion object {
		val Empty = PointerTree(emptyList())
	}
}

/**
 * @property clipBounds `null` means that no clipping modifier encloses this pointer target.
 */
internal class PointerEntry(
	val node: PointerNode,
	val coordinates: LayoutCoordinates,
	val parent: PointerEntry?,
	val clipBounds: IntRect?,
)

/** @return the input node which consumed [event], or `null` when it remained unconsumed. */
private fun List<PointerEntry>.dispatch(event: PointerEvent): PointerInputNode? {
	for (entry in this) {
		val node = entry.node as? PointerInputNode ?: continue
		if (node.modifier.onPrePointerEvent(event.localTo(entry.coordinates.position))) {
			return node
		}
	}
	for (index in lastIndex downTo 0) {
		val entry = this[index]
		val node = entry.node as? PointerInputNode ?: continue
		if (node.modifier.onPointerEvent(event.localTo(entry.coordinates.position))) {
			return node
		}
	}
	return null
}

private fun PointerEntry.dispatchEnter(event: PointerEvent) {
	(node as PointerHoverNode).modifier.onPointerEnter(event.localTo(coordinates.position))
}

private fun PointerEntry.dispatchExit(event: PointerEvent) {
	(node as PointerHoverNode).modifier.onPointerExit(event.localTo(coordinates.position))
}

private fun PointerEntry.pathFromRoot(): List<PointerEntry> = buildList {
	var entry: PointerEntry? = this@pathFromRoot
	while (entry != null) {
		add(entry)
		entry = entry.parent
	}
	reverse()
}

private fun List<PointerEntry>.commonNodePrefixSize(other: List<PointerEntry>): Int {
	val limit = minOf(size, other.size)
	for (index in 0 until limit) {
		if (this[index].node !== other[index].node) return index
	}
	return limit
}

private fun LayoutCoordinates.contains(position: IntOffset): Boolean = position.x in this.position.x until this.position.x + size.width &&
	position.y in this.position.y until this.position.y + size.height

private fun PointerEntry.contains(position: IntOffset): Boolean = coordinates.contains(position) &&
	(clipBounds?.contains(position) != false)

private fun PointerEvent.localTo(origin: IntOffset): PointerEvent = PointerEvent(
	position = IntOffset(position.x - origin.x, position.y - origin.y),
	type = type,
	button = button,
	shift = shift,
	alt = alt,
	ctrl = ctrl,
)

private val MouseEvent.Button.isCapturable: Boolean
	get() = this != MouseEvent.Button.None &&
		this != MouseEvent.Button.WheelUp &&
		this != MouseEvent.Button.WheelDown &&
		this != MouseEvent.Button.WheelLeft &&
		this != MouseEvent.Button.WheelRight
