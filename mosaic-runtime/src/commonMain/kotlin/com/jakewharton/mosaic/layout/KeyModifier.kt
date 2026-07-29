package com.jakewharton.mosaic.layout

import androidx.compose.runtime.Immutable
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.PasteEvent
import dev.drewhamilton.poko.Poko

public interface KeyModifier : Modifier.Element {
	/**
	 * This function is called when a [KeyEvent] is received by this node during the downward pass.
	 * It gives ancestors of a focused component the chance to intercept an event. Return true to
	 * stop propagation of this event. If you return false, the event will be sent to this
	 * [KeyModifier]'s child. If none of the children consume the event, it will be sent
	 * back up to the root using the [onKeyEvent] function.
	 */
	public fun onPreKeyEvent(event: KeyEvent): Boolean

	/**
	 * This function is called when a [KeyEvent] is received by this node during the upward pass.
	 * While implementing this callback, return true to stop propagation of this event. If you
	 * return false, the key event will be sent to this [KeyModifier]'s parent.
	 */
	public fun onKeyEvent(event: KeyEvent): Boolean

	/**
	 * This function is called when a [PasteEvent] is received by this node during the downward pass.
	 */
	public fun onPrePasteEvent(event: PasteEvent): Boolean = false

	/**
	 * This function is called when a [PasteEvent] is received by this node during the upward pass.
	 */
	public fun onPasteEvent(event: PasteEvent): Boolean = false
}

@[Immutable Poko]
public class KeyEvent(
	public val key: String,
	public val alt: Boolean = false,
	public val ctrl: Boolean = false,
	public val shift: Boolean = false,
)

/**
 * Adding this [modifier][Modifier] to the [modifier][Modifier] parameter of a component will allow
 * it to intercept key events.
 *
 * @param onPreviewKeyEvent This callback is invoked when the user interacts with the
 *   keyboard. It gives ancestors of a focused component the chance to intercept a [KeyEvent].
 *   Return true to stop propagation of this event. If you return false, the key event will be sent
 *   to this [onPreviewKeyEvent]'s child. If none of the children consume the event, it will be sent
 *   back up to the root [KeyModifier] using the `onKeyEvent` callback.
 */
public fun Modifier.onPreviewKeyEvent(
	onPreviewKeyEvent: (event: KeyEvent) -> Boolean,
): Modifier = this then KeyModifierElement(onPreKey = onPreviewKeyEvent)

/**
 * Adding this [modifier][Modifier] to the [modifier][Modifier] parameter of a component will allow
 * it to intercept key events.
 *
 * @param onKeyEvent This callback is invoked when the user interacts with the keyboard.
 *   While implementing this callback, return true to stop propagation of this event. If you return
 *   false, the key event will be sent to this [onKeyEvent]'s parent.
 */
public fun Modifier.onKeyEvent(
	onKeyEvent: (event: KeyEvent) -> Boolean,
): Modifier = this then KeyModifierElement(onKey = onKeyEvent)

/**
 * Adding this [modifier][Modifier] to the [modifier][Modifier] parameter of a component will allow
 * it to intercept pasted text before it reaches a focused descendant.
 */
public fun Modifier.onPreviewPasteEvent(
	onPreviewPasteEvent: (event: PasteEvent) -> Boolean,
): Modifier = this then KeyModifierElement(onPrePaste = onPreviewPasteEvent)

/**
 * Adding this [modifier][Modifier] to the [modifier][Modifier] parameter of a component will allow
 * it to intercept pasted text.
 */
public fun Modifier.onPasteEvent(
	onPasteEvent: (event: PasteEvent) -> Boolean,
): Modifier = this then KeyModifierElement(onPaste = onPasteEvent)

private class KeyModifierElement(
	val onPreKey: ((KeyEvent) -> Boolean)? = null,
	val onKey: ((KeyEvent) -> Boolean)? = null,
	val onPrePaste: ((PasteEvent) -> Boolean)? = null,
	val onPaste: ((PasteEvent) -> Boolean)? = null,
) : KeyModifier {
	override fun onPreKeyEvent(event: KeyEvent) = onPreKey?.invoke(event) ?: false
	override fun onKeyEvent(event: KeyEvent) = onKey?.invoke(event) ?: false
	override fun onPrePasteEvent(event: PasteEvent) = onPrePaste?.invoke(event) ?: false
	override fun onPasteEvent(event: PasteEvent) = onPaste?.invoke(event) ?: false
}
