package com.jakewharton.mosaic.tty.terminal

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import com.jakewharton.mosaic.terminal.PasteEvent
import kotlin.test.Test

class TtyTerminalBracketedPasteTest {
	@Test fun bracketedPasteIsEnabledDeliveredAndRestored() = terminalTest {
		expect("${CSI}0c", reply = "$CSI?62;22c")
		expect("${CSI}5n", reply = "${CSI}0n")

		val teardown = withTerminal { setup ->
			assertThat(setup).contains(bracketedPasteEnable)

			ptyWrite("${CSI}200~first\tline\r\n$ESC[31msecond${CSI}201~")
			assertThat(events.receive()).isEqualTo(PasteEvent("first\tline\r\n$ESC[31msecond"))
		}

		assertThat(teardown).contains(bracketedPasteDisable)
	}
}
