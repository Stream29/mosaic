package com.jakewharton.mosaic.layout

import androidx.compose.runtime.Immutable
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.ui.unit.IntOffset
import dev.drewhamilton.poko.Poko

/** Receives pointer events for the hit path containing this modifier. */
public interface PointerModifier : Modifier.Element {
	/**
	 * Invoked while [event] travels from the outermost handler toward the hit target.
	 *
	 * Return `true` to consume the event and stop propagation.
	 */
	public fun onPrePointerEvent(event: PointerEvent): Boolean

	/**
	 * Invoked while an unconsumed [event] travels from the hit target toward the outermost handler.
	 *
	 * Return `true` to consume the event and stop propagation.
	 */
	public fun onPointerEvent(event: PointerEvent): Boolean
}

/** Receives enter and exit transitions for the hit path containing this modifier. */
public interface PointerHoverModifier : Modifier.Element {
	/** Invoked when the pointer enters this modifier's hit path. */
	public fun onPointerEnter(event: PointerEvent)

	/** Invoked when the pointer exits this modifier's hit path. */
	public fun onPointerExit(event: PointerEvent)
}

/**
 * A terminal pointer event.
 *
 * [position] is expressed in terminal cells relative to the modifier receiving the event.
 * A consumed mouse-button press captures subsequent drag and release events for the consuming
 * handler until release.
 */
@[Immutable Poko]
public class PointerEvent(
	public val position: IntOffset,
	public val type: MouseEvent.Type,
	public val button: MouseEvent.Button = MouseEvent.Button.None,
	public val shift: Boolean = false,
	public val alt: Boolean = false,
	public val ctrl: Boolean = false,
)

/** Adds a preview-phase pointer handler to this modifier chain. */
public fun Modifier.onPreviewPointerEvent(
	onPreviewPointerEvent: (event: PointerEvent) -> Boolean,
): Modifier = this then PointerModifierElement(onPreviewPointerEvent, null)

/** Adds a bubble-phase pointer handler to this modifier chain. */
public fun Modifier.onPointerEvent(
	onPointerEvent: (event: PointerEvent) -> Boolean,
): Modifier = this then PointerModifierElement(null, onPointerEvent)

/** Adds pointer enter and exit callbacks to this modifier chain. */
public fun Modifier.onPointerHover(
	onPointerEnter: (event: PointerEvent) -> Unit,
	onPointerExit: (event: PointerEvent) -> Unit,
): Modifier = this then PointerHoverModifierElement(onPointerEnter, onPointerExit)

private class PointerModifierElement(
	private val onPreEvent: ((PointerEvent) -> Boolean)?,
	private val onEvent: ((PointerEvent) -> Boolean)?,
) : PointerModifier {
	override fun onPrePointerEvent(event: PointerEvent): Boolean = onPreEvent?.invoke(event) ?: false

	override fun onPointerEvent(event: PointerEvent): Boolean = onEvent?.invoke(event) ?: false
}

private class PointerHoverModifierElement(
	private val onEnter: (PointerEvent) -> Unit,
	private val onExit: (PointerEvent) -> Unit,
) : PointerHoverModifier {
	override fun onPointerEnter(event: PointerEvent): Unit = onEnter(event)

	override fun onPointerExit(event: PointerEvent): Unit = onExit(event)
}

internal sealed interface PointerNode

internal class PointerInputNode(
	var modifier: PointerModifier,
) : PointerNode

internal class PointerHoverNode(
	var modifier: PointerHoverModifier,
) : PointerNode
