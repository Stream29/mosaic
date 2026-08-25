package com.jakewharton.mosaic.tty.terminal

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.KeyboardEvent.Companion.F10
import com.jakewharton.mosaic.terminal.KeyboardEvent.Companion.Menu
import com.jakewharton.mosaic.terminal.KeyboardEvent.Companion.ModifierShift
import com.jakewharton.mosaic.terminal.ResizeEvent
import com.jakewharton.mosaic.terminal.Terminal
import kotlin.test.Test
import kotlinx.coroutines.flow.first

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
			ptyWrite("${CSI}21;2~")
			assertThat(events.receive()).isEqualTo(
				KeyboardEvent(F10, modifiers = ModifierShift),
			)
			ptyWrite("${CSI}29~")
			assertThat(events.receive()).isEqualTo(KeyboardEvent(Menu))
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

	@Test fun burstTextInputPreservesEveryCodepoint() = terminalTest {
		expect("${CSI}0c", reply = "$CSI?62;22c")
		expect("$CSI?2048\$p", reply = "$CSI?2048;1\$y")
		expect("${CSI}5n", reply = "${CSI}0n")
		expect("${CSI}5n", reply = "${CSI}0n")

		withTerminal {
			val text = buildString {
				repeat(96) { append((0x4e00 + it).toChar()) }
			}
			val resizeEvent = ResizeEvent(100, 40, 800, 400)
			val resized = Terminal.Size(100, 40, 800, 400)

			ptyWrite(text + "${CSI}48;40;100;400;800t")
			state.size.first { it == resized }

			text.forEach { character ->
				assertThat(events.receive()).isEqualTo(KeyboardEvent(character.code))
			}
			assertThat(events.receive()).isEqualTo(resizeEvent)
		}
	}
}
