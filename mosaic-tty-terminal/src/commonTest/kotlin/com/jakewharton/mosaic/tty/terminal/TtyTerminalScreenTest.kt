package com.jakewharton.mosaic.tty.terminal

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.jakewharton.mosaic.terminal.TerminalScreen
import kotlin.test.Test
import kotlinx.io.bytestring.encodeToByteString

class TtyTerminalScreenTest {
	@Test fun inlineScreenDoesNotSwitchBuffers() = terminalTest {
		expect("${CSI}0c", reply = "$CSI?1c")

		val teardown = withTerminal(screen = TerminalScreen.Inline) { setup ->
			assertThat(setup).isEqualTo(("${CSI}0c" + modifyOtherKeysEnable).encodeToByteString())
		}

		assertThat(teardown).isEqualTo(modifyOtherKeysReset.encodeToByteString())
	}

	@Test fun alternateScreenIsActivatedAndRestored() = terminalTest {
		expect("${CSI}0c", reply = "$CSI?1c")

		val teardown = withTerminal(screen = TerminalScreen.Alternate) { setup ->
			assertThat(setup).isEqualTo(
				(alternateScreenEnable + "${CSI}0c" + modifyOtherKeysEnable).encodeToByteString(),
			)
		}

		assertThat(teardown).isEqualTo(
			(modifyOtherKeysReset + alternateScreenDisable).encodeToByteString(),
		)
	}
}
