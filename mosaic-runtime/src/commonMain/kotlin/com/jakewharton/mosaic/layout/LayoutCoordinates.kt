package com.jakewharton.mosaic.layout

import com.jakewharton.mosaic.ui.unit.IntOffset
import com.jakewharton.mosaic.ui.unit.IntSize

/**
 * The position and size of a laid-out node in terminal cells.
 *
 * Coordinates supplied by modifier nodes remain live across layout passes. Callers must check
 * [isAttached] before using coordinates retained across suspension points.
 *
 * @property position Position relative to the current Mosaic surface.
 * @property size Size in terminal cells.
 */
public class LayoutCoordinates internal constructor(
	position: IntOffset,
	size: IntSize,
) {
	private var currentPosition: IntOffset = position
	private var currentSize: IntSize = size
	private var attached: Boolean = true

	public val position: IntOffset
		get() = currentPosition

	public val size: IntSize
		get() = currentSize

	/** Whether these coordinates still belong to an attached node. */
	public val isAttached: Boolean
		get() = attached

	internal fun update(position: IntOffset, size: IntSize) {
		currentPosition = position
		currentSize = size
		attached = true
	}

	internal fun detach() {
		attached = false
	}

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is LayoutCoordinates) return false
		return currentPosition == other.currentPosition &&
			currentSize == other.currentSize &&
			attached == other.attached
	}

	override fun hashCode(): Int {
		var result = currentPosition.hashCode()
		result = 31 * result + currentSize.hashCode()
		result = 31 * result + attached.hashCode()
		return result
	}

	override fun toString(): String {
		return "LayoutCoordinates(position=$currentPosition, size=$currentSize, isAttached=$attached)"
	}
}
