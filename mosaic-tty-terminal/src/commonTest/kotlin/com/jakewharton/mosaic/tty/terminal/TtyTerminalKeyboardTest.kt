package com.jakewharton.mosaic.tty.terminal

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.KeyboardEvent.Companion.ModifierShift
import kotlin.test.Test

class TtyTerminalKeyboardTest {
	@Test fun kittyKeyboardIsEnabledAndRestored() = terminalTest {
		expect("${CSI}0c", reply = "$CSI?62;22c")
		expect("$CSI?u", reply = "$CSI?0u")
		expect("${CSI}5n", reply = "${CSI}0n")

		val teardown = withTerminal { setup ->
			assertThat(capabilities.kittyKeyboard).isTrue()
			assertThat(setup).contains(kittyKeyboardPush)
			assertThat(setup).doesNotContain(modifyOtherKeysEnable)

			ptyWrite("${CSI}13;2u")
			assertThat(events.receive()).isEqualTo(
				KeyboardEvent(13, modifiers = ModifierShift),
			)
		}

		assertThat(teardown).contains(kittyKeyboardPop)
		assertThat(teardown).doesNotContain(modifyOtherKeysReset)
	}

	@Test fun modifyOtherKeysFallbackIsEnabledAndReset() = terminalTest {
		expect("${CSI}0c", reply = "$CSI?62;22c")
		expect("${CSI}5n", reply = "${CSI}0n")

		val teardown = withTerminal { setup ->
			assertThat(capabilities.kittyKeyboard).isFalse()
			assertThat(setup).contains(modifyOtherKeysEnable)
			assertThat(setup).doesNotContain(kittyKeyboardPush)

			ptyWrite("${CSI}27;2;13~")
			assertThat(events.receive()).isEqualTo(
				KeyboardEvent(13, modifiers = ModifierShift),
			)
		}

		assertThat(teardown).contains(modifyOtherKeysReset)
		assertThat(teardown).doesNotContain(kittyKeyboardPop)
	}
}
