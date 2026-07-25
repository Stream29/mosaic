package com.jakewharton.mosaic.node

import com.jakewharton.mosaic.layout.BeyondBoundsLayout
import com.jakewharton.mosaic.layout.BeyondBoundsLayoutProviderModifierNode
import com.jakewharton.mosaic.layout.LayoutCoordinates
import com.jakewharton.mosaic.modifier.Modifier

/**
 * A [Modifier.Node] that can participate in modifier-node ancestor traversal.
 *
 * [Modifier.Node] implements this interface directly. Other node types may delegate their
 * position in the hierarchy to a containing node.
 */
public interface DelegatableNode {
	/** The node which occupies this object's position in the modifier-node hierarchy. */
	public val node: Modifier.Node
}

/** Returns this node's current layout coordinates. */
public fun DelegatableNode.requireLayoutCoordinates(): LayoutCoordinates {
	check(node.isAttached) { "Cannot obtain LayoutCoordinates from a detached modifier node" }
	val coordinates = checkNotNull(node.layoutCoordinates) {
		"Modifier node has not been placed"
	}
	check(coordinates.isAttached) { "Modifier node coordinates are detached" }
	return coordinates
}

/** @return The nearest ancestor's [BeyondBoundsLayout], or `null` when no ancestor provides one. */
public fun DelegatableNode.findNearestBeyondBoundsLayoutAncestor(): BeyondBoundsLayout? {
	return nearestAncestor<BeyondBoundsLayoutProviderModifierNode>()?.beyondBoundsLayout
}

/** @return The nearest matching ancestor, or `null` when this node has none. */
internal inline fun <reified T : DelegatableNode> DelegatableNode.nearestAncestor(): T? {
	check(node.isAttached) { "Cannot visit ancestors of a detached modifier node" }
	var layoutNode = node.requireLayoutNode()
	var ancestor = node.parent
	while (true) {
		while (ancestor != null) {
			if (ancestor is T) return ancestor
			ancestor = ancestor.parent
		}
		layoutNode = layoutNode.parent ?: return null
		ancestor = layoutNode.modifierNodeTail
	}
}
