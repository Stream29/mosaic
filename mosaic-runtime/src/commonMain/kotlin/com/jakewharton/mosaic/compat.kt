package com.jakewharton.mosaic

import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.terminal.KeyboardEvent
import de.cketti.codepoints.CodePoints

// Kitty reports standalone modifier presses in this range when report-all-keys is enabled.
private const val KittyModifierKeyStart = 57441
private const val KittyModifierKeyEnd = 57454

internal fun KeyboardEvent.toKeyEventOrNull(): KeyEvent? {
	if (eventType != KeyboardEvent.EventTypePress) {
		return null
	}
	if (text == null && codepoint in KittyModifierKeyStart..KittyModifierKeyEnd) {
		return null
	}

	return KeyEvent(
		key = text ?: when (val codepoint = codepoint) {
			9 -> "Tab"
			13 -> "Enter"
			27 -> "Escape"
			127 -> "Backspace"
			57350 -> "ArrowLeft"
			57351 -> "ArrowRight"
			57352 -> "ArrowUp"
			57353 -> "ArrowDown"
			57348 -> "Insert"
			57349 -> "Delete"
			57354 -> "PageUp"
			57355 -> "PageDown"
			57356 -> "Home"
			57357 -> "End"
			57358 -> "CapsLock"
			57363 -> "ContextMenu"
			in 57364..57398 -> "F" + (codepoint - 57363)
			in 0x20..0xd7ff -> codepoint.toChar().toString()
			in 0xe000..0x10ffff -> CodePoints.toChars(codepoint).concatToString()
			else -> throw UnsupportedOperationException(toString())
		},
		alt = alt,
		ctrl = ctrl,
		shift = shift,
	)
}
