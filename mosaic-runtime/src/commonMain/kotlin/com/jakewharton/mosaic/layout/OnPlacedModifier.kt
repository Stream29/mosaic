package com.jakewharton.mosaic.layout

import com.jakewharton.mosaic.modifier.Modifier

/** Receives the final terminal-cell coordinates of a laid-out node. */
internal interface OnPlacedModifier : Modifier.Element {
	fun onPlaced(coordinates: LayoutCoordinates)
}

/**
 * Invokes [onPlaced] after this node is placed with changed [LayoutCoordinates].
 *
 * @param onPlaced Receives coordinates relative to the current Mosaic surface.
 */
public fun Modifier.onPlaced(
	onPlaced: (coordinates: LayoutCoordinates) -> Unit,
): Modifier = this then OnPlacedModifierElement(onPlaced)

private class OnPlacedModifierElement(
	private val callback: (coordinates: LayoutCoordinates) -> Unit,
) : OnPlacedModifier {
	override fun onPlaced(coordinates: LayoutCoordinates) {
		callback(coordinates)
	}
}
