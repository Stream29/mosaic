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

	@Test fun namedPrivateUseCodepointRetainsKeyName() {
		assertThat(KeyboardEvent(KeyboardEvent.Left).toKeyEventOrNull())
			.isEqualTo(KeyEvent("ArrowLeft"))
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
