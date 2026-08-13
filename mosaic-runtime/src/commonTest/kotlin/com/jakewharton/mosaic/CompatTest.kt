package com.jakewharton.mosaic

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.terminal.KeyboardEvent
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CompatTest {
	@Test fun unicodeBmpCodepoint() {
		assertThat(KeyboardEvent(0x4f60).toKeyEventOrNull())
			.isEqualTo(KeyEvent("\u4f60"))
	}

	@Test fun unicodeSupplementaryCodepoint() {
		assertThat(KeyboardEvent(0x1f642).toKeyEventOrNull())
			.isEqualTo(KeyEvent("\ud83d\ude42"))
	}

	@Test fun associatedTextTakesPrecedenceOverCodepoint() {
		assertThat(KeyboardEvent(codepoint = 0, text = "\u4f60\u597d").toKeyEventOrNull())
			.isEqualTo(KeyEvent("\u4f60\u597d"))
	}

	@Test fun standaloneModifierKeysAreIgnored() {
		for (codepoint in 57441..57454) {
			assertThat(KeyboardEvent(codepoint).toKeyEventOrNull()).isNull()
		}
	}

	@Test fun associatedTextTakesPrecedenceOverModifierCodepoint() {
		assertThat(
			KeyboardEvent(
				codepoint = 57441,
				modifiers = KeyboardEvent.ModifierShift,
				text = "\ue061",
			).toKeyEventOrNull(),
		).isEqualTo(KeyEvent("\ue061", shift = true))
	}

	@Test fun namedPrivateUseCodepointRetainsKeyName() {
		assertThat(KeyboardEvent(KeyboardEvent.Left).toKeyEventOrNull())
			.isEqualTo(KeyEvent("ArrowLeft"))
	}

	@Test fun capsLockUsesItsKeyName() {
		assertThat(KeyboardEvent(57358).toKeyEventOrNull())
			.isEqualTo(KeyEvent("CapsLock"))
	}

	@Test fun associatedTextTakesPrecedenceOverCapsLockCodepoint() {
		assertThat(KeyboardEvent(codepoint = 57358, text = "\ue00e").toKeyEventOrNull())
			.isEqualTo(KeyEvent("\ue00e"))
	}

	@Test fun menuKeyUsesTheContextMenuName() {
		assertThat(KeyboardEvent(KeyboardEvent.Menu).toKeyEventOrNull())
			.isEqualTo(KeyEvent("ContextMenu"))
	}

	@Test fun nonPressEventsAreIgnored() {
		assertThat(
			KeyboardEvent(codepoint = 'a'.code, eventType = KeyboardEvent.EventTypeRepeat)
				.toKeyEventOrNull(),
		).isNull()
		assertThat(
			KeyboardEvent(codepoint = 'a'.code, eventType = KeyboardEvent.EventTypeRelease)
				.toKeyEventOrNull(),
		).isNull()
	}

	@Test fun invalidUnicodeScalarIsRejected() {
		assertFailsWith<UnsupportedOperationException> {
			KeyboardEvent(0xd800).toKeyEventOrNull()
		}
	}
}
