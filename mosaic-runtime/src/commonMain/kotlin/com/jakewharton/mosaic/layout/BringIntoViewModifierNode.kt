package com.jakewharton.mosaic.layout

import com.jakewharton.mosaic.node.DelegatableNode
import com.jakewharton.mosaic.node.nearestAncestor
import com.jakewharton.mosaic.node.requireLayoutCoordinates
import com.jakewharton.mosaic.ui.unit.IntOffset
import com.jakewharton.mosaic.ui.unit.IntRect

/**
 * A node that can respond to [bringIntoView] requests from its children by moving or adjusting its
 * content.
 */
public interface BringIntoViewModifierNode : DelegatableNode {
	/**
	 * Moves or adjusts this node's content until [boundsProvider] is visible.
	 *
	 * Implementations must propagate the request to the parent bring-into-view node. This function
	 * does not return until the request is satisfied or interrupted by a newer request.
	 *
	 * @param childCoordinates Coordinates of the child node making the request.
	 * @param boundsProvider Supplies the requested bounds relative to [childCoordinates]. `null`
	 * means that the bounds cannot currently be calculated.
	 */
	public suspend fun bringIntoView(
		childCoordinates: LayoutCoordinates,
		boundsProvider: () -> IntRect?,
	)
}

/**
 * Requests that ancestor [BringIntoViewModifierNode]s make this node visible.
 *
 * This does nothing while the node is detached. When [bounds] is omitted, the whole node is
 * requested.
 *
 * @param bounds Supplies the requested bounds. `null` selects the whole node.
 */
public suspend fun DelegatableNode.bringIntoView(bounds: (() -> IntRect?)? = null) {
	if (!node.isAttached) return
	val parent = nearestAncestor<BringIntoViewModifierNode>() ?: return
	val layoutCoordinates = requireLayoutCoordinates()
	parent.bringIntoView(layoutCoordinates) {
		bounds?.invoke()
			?: layoutCoordinates
				.takeIf { node.isAttached && it.isAttached }
				?.let { coordinates -> IntRect(IntOffset.Zero, coordinates.size) }
	}
}
