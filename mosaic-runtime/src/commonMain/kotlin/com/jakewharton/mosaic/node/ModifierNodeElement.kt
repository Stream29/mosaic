package com.jakewharton.mosaic.node

import com.jakewharton.mosaic.modifier.Modifier

/**
 * A lightweight [Modifier.Element] which creates and updates a stateful [Modifier.Node].
 *
 * Implementations must use structural equality for the inputs consumed by [update]. Mosaic keeps
 * the node when an element of the same runtime type remains at the corresponding position.
 */
public abstract class ModifierNodeElement<N : Modifier.Node> : Modifier.Element {
	/** Creates the node when this element is first applied to a layout. */
	public abstract fun create(): N

	/** Updates an existing node after this element's inputs change. */
	public abstract fun update(node: N)

	public abstract override fun equals(other: Any?): Boolean

	public abstract override fun hashCode(): Int
}
