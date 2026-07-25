package com.jakewharton.mosaic.layout

import com.jakewharton.mosaic.node.DelegatableNode
import kotlin.jvm.JvmInline

/**
 * Provides a [BeyondBoundsLayout] through the modifier-node hierarchy.
 */
public interface BeyondBoundsLayoutProviderModifierNode : DelegatableNode {
	/** The beyond-bounds layout provided to descendant nodes. */
	public val beyondBoundsLayout: BeyondBoundsLayout
}

/**
 * Temporarily lays out content beyond a container's visible bounds.
 *
 * A lazy container adds one item at a time in the requested direction and invokes [layout]'s block
 * after each addition. Focus search uses this to discover targets which have not yet been laid out.
 */
public interface BeyondBoundsLayout {
	/**
	 * Lays out additional content in [direction] until [block] returns a non-null value.
	 *
	 * Temporary content may be disposed before this function returns. Callers must therefore
	 * perform operations which depend on that content from inside [block].
	 *
	 * @return The non-null value returned by [block], or `null` when no request succeeded.
	 */
	public fun <T> layout(
		direction: LayoutDirection,
		block: BeyondBoundsScope.() -> T?,
	): T?

	/** Scope supplied to each [layout] callback. */
	public interface BeyondBoundsScope {
		/** Whether the provider can lay out more content in the requested direction. */
		public val hasMoreContent: Boolean
	}

	/** Direction from the current visible bounds in which more content is requested. */
	@JvmInline
	public value class LayoutDirection internal constructor(private val value: Int) {
		override fun toString(): String = when (this) {
			Before -> "Before"
			After -> "After"
			Left -> "Left"
			Right -> "Right"
			Above -> "Above"
			Below -> "Below"
			else -> "invalid LayoutDirection"
		}

		public companion object {
			/** Requests content before the current bounds in logical order. */
			public val Before: LayoutDirection get() = LayoutDirection(1)

			/** Requests content after the current bounds in logical order. */
			public val After: LayoutDirection get() = LayoutDirection(2)

			/** Requests content to the left of the current bounds. */
			public val Left: LayoutDirection get() = LayoutDirection(3)

			/** Requests content to the right of the current bounds. */
			public val Right: LayoutDirection get() = LayoutDirection(4)

			/** Requests content above the current bounds. */
			public val Above: LayoutDirection get() = LayoutDirection(5)

			/** Requests content below the current bounds. */
			public val Below: LayoutDirection get() = LayoutDirection(6)
		}
	}
}
