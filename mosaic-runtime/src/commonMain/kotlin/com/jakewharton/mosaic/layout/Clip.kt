package com.jakewharton.mosaic.layout

import androidx.compose.runtime.Stable
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.unit.IntOffset
import com.jakewharton.mosaic.ui.unit.IntSize

/** Clips drawing, pointer hit testing, focus projection, and focus cursor output to these bounds. */
@Stable
public fun Modifier.clipToBounds(): Modifier = this then ClipToBoundsElement

internal interface ViewportClipModifier : Modifier.Element

private object ClipToBoundsElement : ViewportClipModifier {
	override fun toString(): String = "ClipToBounds"
}

internal data class ClipBounds(
	val left: Int,
	val top: Int,
	val right: Int,
	val bottom: Int,
) {
	val hasArea: Boolean
		get() = left < right && top < bottom

	fun intersect(other: ClipBounds): ClipBounds = ClipBounds(
		left = maxOf(left, other.left),
		top = maxOf(top, other.top),
		right = minOf(right, other.right),
		bottom = minOf(bottom, other.bottom),
	)

	fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom

	fun contains(x: Int, y: Int, width: Int, height: Int): Boolean = width > 0 &&
		height > 0 &&
		x >= left &&
		y >= top &&
		x + width <= right &&
		y + height <= bottom

	companion object {
		fun from(position: IntOffset, size: IntSize): ClipBounds = ClipBounds(
			left = position.x,
			top = position.y,
			right = position.x + size.width,
			bottom = position.y + size.height,
		)
	}
}
