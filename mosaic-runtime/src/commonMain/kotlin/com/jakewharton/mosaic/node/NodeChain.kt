/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jakewharton.mosaic.node

import androidx.collection.MutableObjectList
import androidx.collection.mutableObjectListOf
import com.jakewharton.mosaic.layout.MosaicNode
import com.jakewharton.mosaic.modifier.CombinedModifier
import com.jakewharton.mosaic.modifier.Modifier

/*
 * Adapted from AndroidX Compose UI's NodeChain. Mosaic keeps legacy modifier elements in its
 * existing layer chain, so elements and optional nodes are stored in parallel instead of wrapping
 * every legacy element in a compatibility node.
 */
internal class NodeChain(
	private val layoutNode: MosaicNode,
) {
	private var elements = mutableObjectListOf<Modifier.Element>()
	private var elementBuffer = mutableObjectListOf<Modifier.Element>()

	/**
	 * Nodes indexed in parallel with [elements].
	 *
	 * A `null` entry means the corresponding legacy modifier element is represented only by a
	 * [com.jakewharton.mosaic.layout.MosaicNodeLayer].
	 */
	private var nodes = mutableObjectListOf<Modifier.Node?>()
	private var nodeBuffer = mutableObjectListOf<Modifier.Node?>()
	private val modifierStack = mutableObjectListOf<Modifier>()

	/** `null` means the current modifier chain contains no node-based elements. */
	private var lastNode: Modifier.Node? = null

	/** `null` means this chain has no node-based modifier elements. */
	val tail: Modifier.Node?
		get() = lastNode

	val lastElementIndex: Int
		get() = elements.lastIndex

	fun requireNodeAt(elementIndex: Int): Modifier.Node {
		return checkNotNull(nodes[elementIndex]) {
			"Modifier element at index $elementIndex does not own a node"
		}
	}

	fun updateFrom(modifier: Modifier) {
		check(elementBuffer.isEmpty())
		check(modifierStack.isEmpty())
		val before = elements
		val beforeNodes = nodes
		val after = modifier.fillVector(elementBuffer, modifierStack)
		modifierStack.clear()

		var unchangedPrefixSize = 0
		if (before.size == after.size) {
			while (unchangedPrefixSize < before.size) {
				val previous = before[unchangedPrefixSize]
				val next = after[unchangedPrefixSize]
				when (actionForModifiers(previous, next)) {
					ActionReplace -> break
					ActionUpdate -> next.updateNode(beforeNodes[unchangedPrefixSize])
					ActionReuse -> Unit
				}
				unchangedPrefixSize++
			}
			if (unchangedPrefixSize == before.size) {
				elements = after
				elementBuffer = before.also { it.clear() }
				return
			}
		}

		updateStructure(
			unchangedPrefixSize = unchangedPrefixSize,
			before = before,
			beforeNodes = beforeNodes,
			after = after,
		)
	}

	private fun updateStructure(
		unchangedPrefixSize: Int,
		before: MutableObjectList<Modifier.Element>,
		beforeNodes: MutableObjectList<Modifier.Node?>,
		after: MutableObjectList<Modifier.Element>,
	) {
		check(nodeBuffer.isEmpty())
		val afterNodes = nodeBuffer
		repeat(unchangedPrefixSize) { index -> afterNodes.add(beforeNodes[index]) }
		val removed = BooleanArray(before.size)
		val inserted = BooleanArray(after.size)
		executeDiff(
			oldSize = before.size - unchangedPrefixSize,
			newSize = after.size - unchangedPrefixSize,
			callback = object : DiffCallback {
				override fun areItemsTheSame(oldIndex: Int, newIndex: Int): Boolean {
					return actionForModifiers(
						before[unchangedPrefixSize + oldIndex],
						after[unchangedPrefixSize + newIndex],
					) != ActionReplace
				}

				override fun insert(newIndex: Int) {
					val absoluteNewIndex = unchangedPrefixSize + newIndex
					val node = after[absoluteNewIndex].createNodeOrNull()
					afterNodes.add(node)
					if (node != null) inserted[absoluteNewIndex] = true
				}

				override fun remove(atIndex: Int, oldIndex: Int) {
					removed[unchangedPrefixSize + oldIndex] = true
				}

				override fun same(oldIndex: Int, newIndex: Int) {
					val absoluteOldIndex = unchangedPrefixSize + oldIndex
					val absoluteNewIndex = unchangedPrefixSize + newIndex
					val node = beforeNodes[absoluteOldIndex]
					val next = after[absoluteNewIndex]
					if (before[absoluteOldIndex] != next) next.updateNode(node)
					afterNodes.add(node)
				}
			},
		)
		check(afterNodes.size == after.size)

		if (layoutNode.isAttached) {
			for (index in beforeNodes.lastIndex downTo 0) {
				if (removed[index]) beforeNodes[index]?.runDetachLifecycle()
			}
			for (index in beforeNodes.lastIndex downTo 0) {
				if (removed[index]) beforeNodes[index]?.markDetached()
			}
		}
		for (index in beforeNodes.lastIndex downTo 0) {
			if (removed[index]) beforeNodes[index]?.unbind()
		}

		relink(afterNodes)
		elements = after
		elementBuffer = before.also { it.clear() }
		nodes = afterNodes
		nodeBuffer = beforeNodes.also { it.clear() }

		if (layoutNode.isAttached) {
			for (index in afterNodes.indices) {
				if (inserted[index]) afterNodes[index]?.markAttached()
			}
			for (index in afterNodes.indices) {
				if (inserted[index]) afterNodes[index]?.runAttachLifecycle()
			}
		}
	}

	/** @return A newly bound node, or `null` when this is a legacy modifier element. */
	private fun Modifier.Element.createNodeOrNull(): Modifier.Node? {
		if (this !is ModifierNodeElement<*>) return null
		return create().also { node -> node.bind(layoutNode) }
	}

	private fun relink(updatedNodes: MutableObjectList<Modifier.Node?>) {
		// `null` means no node-based element has been encountered yet.
		var previous: Modifier.Node? = null
		for (index in updatedNodes.indices) {
			val node = updatedNodes[index] ?: continue
			node.parent = previous
			previous?.child = node
			previous = node
		}
		previous?.child = null
		lastNode = previous
	}

	fun attach() {
		for (index in nodes.indices) nodes[index]?.markAttached()
		for (index in nodes.indices) nodes[index]?.runAttachLifecycle()
	}

	fun detach() {
		for (index in nodes.lastIndex downTo 0) nodes[index]?.runDetachLifecycle()
		for (index in nodes.lastIndex downTo 0) nodes[index]?.markDetached()
	}

	fun reset() {
		for (index in nodes.indices) nodes[index]?.reset()
	}
}

private const val ActionReplace = 0
private const val ActionUpdate = 1
private const val ActionReuse = 2

/**
 * Node reuse follows Compose's rules:
 * 1. Equal elements reuse their node without an update.
 * 2. Elements of the same runtime type reuse and update their node.
 * 3. Elements of different runtime types replace their node.
 */
private fun actionForModifiers(
	previous: Modifier.Element,
	next: Modifier.Element,
): Int {
	return when {
		previous == next -> ActionReuse
		previous::class == next::class -> ActionUpdate
		else -> ActionReplace
	}
}

/**
 * @param node The element's current node, or `null` when this is a legacy modifier element.
 */
private fun Modifier.Element.updateNode(node: Modifier.Node?) {
	if (this is ModifierNodeElement<*>) {
		updateUnsafe(checkNotNull(node) { "Modifier node element is missing its node" })
	} else {
		check(node == null) { "Legacy modifier element unexpectedly owns a node" }
	}
}

@Suppress("UNCHECKED_CAST")
private fun ModifierNodeElement<*>.updateUnsafe(node: Modifier.Node) {
	(this as ModifierNodeElement<Modifier.Node>).update(node)
}

private fun Modifier.fillVector(
	result: MutableObjectList<Modifier.Element>,
	stack: MutableObjectList<Modifier>,
): MutableObjectList<Modifier.Element> {
	stack.add(this)
	// `null` until a non-standard Modifier implementation requires the fallback traversal.
	var unknownModifierPredicate: ((Modifier.Element) -> Boolean)? = null
	while (stack.isNotEmpty()) {
		when (val next = stack.removeAt(stack.lastIndex)) {
			is CombinedModifier -> {
				stack.add(next.inner)
				stack.add(next.outer)
			}

			is Modifier.Element -> result.add(next)

			else -> next.all(
				unknownModifierPredicate
					?: { element: Modifier.Element ->
						result.add(element)
						true
					}.also { unknownModifierPredicate = it },
			)
		}
	}
	return result
}
