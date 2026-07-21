package com.jakewharton.mosaic.tty.terminal

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.KeyboardEvent.Companion.ModifierCtrl
import com.jakewharton.mosaic.terminal.KeyboardEvent.Companion.ModifierShift
import com.jakewharton.mosaic.terminal.UnknownEvent
import kotlin.test.Test

class EventParserCsiXtermModifyOtherKeysEventTest : BaseEventParserTest() {
	@Test fun unmodifiedA() {
		testTerminal.write("${CSI}27;1;97~")
		assertThat(parser.next()).isEqualTo(
			KeyboardEvent('a'.code),
		)
	}

	@Test fun shiftEnter() {
		testTerminal.write("${CSI}27;2;13~")
		assertThat(parser.next()).isEqualTo(
			KeyboardEvent(13, modifiers = ModifierShift),
		)
	}

	@Test fun ctrlShiftA() {
		testTerminal.write("${CSI}27;6;65~")
		assertThat(parser.next()).isEqualTo(
			KeyboardEvent(65, modifiers = ModifierCtrl or ModifierShift),
		)
	}

	@Test fun zeroModifier() {
		testTerminal.write("${CSI}27;0;97~")
		assertThat(parser.next()).isEqualTo(
			UnknownEvent("1b5b32373b303b39377e".hexToByteArray()),
		)
	}
}
