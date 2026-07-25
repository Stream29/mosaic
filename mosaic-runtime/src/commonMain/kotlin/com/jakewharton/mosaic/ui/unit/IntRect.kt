package com.jakewharton.mosaic.ui.unit

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable

/** An immutable axis-aligned rectangle measured in terminal cells. */
@Immutable
public data class IntRect(
	/** Offset of the left edge from the x axis. */
	@Stable public val left: Int,
	/** Offset of the top edge from the y axis. */
	@Stable public val top: Int,
	/** Offset of the right edge from the x axis. */
	@Stable public val right: Int,
	/** Offset of the bottom edge from the y axis. */
	@Stable public val bottom: Int,
) {
	/** Distance between [left] and [right]. */
	@Stable
	public val width: Int
		get() = right - left

	/** Distance between [top] and [bottom]. */
	@Stable
	public val height: Int
		get() = bottom - top

	/** Width and height of this rectangle. */
	@Stable
	public val size: IntSize
		get() = IntSize(width, height)

	/** Whether this rectangle encloses no positive area. */
	@Stable
	public val isEmpty: Boolean
		get() = left >= right || top >= bottom

	/** Returns this rectangle translated by [offset]. */
	@Stable
	public fun translate(offset: IntOffset): IntRect = IntRect(
		left = left + offset.x,
		top = top + offset.y,
		right = right + offset.x,
		bottom = bottom + offset.y,
	)

	internal fun intersect(other: IntRect): IntRect = IntRect(
		left = maxOf(left, other.left),
		top = maxOf(top, other.top),
		right = minOf(right, other.right),
		bottom = minOf(bottom, other.bottom),
	)

	internal operator fun contains(offset: IntOffset): Boolean = offset.x in left until right && offset.y in top until bottom

	internal fun contains(offset: IntOffset, size: IntSize): Boolean = size.width > 0 &&
		size.height > 0 &&
		offset.x >= left &&
		offset.y >= top &&
		offset.x + size.width <= right &&
		offset.y + size.height <= bottom

	public companion object {
		/** A rectangle whose edges are all zero. */
		@Stable
		public val Zero: IntRect = IntRect(0, 0, 0, 0)
	}
}

/** Constructs a rectangle from its top-left [offset] and [size]. */
@Stable
public fun IntRect(offset: IntOffset, size: IntSize): IntRect = IntRect(
	left = offset.x,
	top = offset.y,
	right = offset.x + size.width,
	bottom = offset.y + size.height,
)
