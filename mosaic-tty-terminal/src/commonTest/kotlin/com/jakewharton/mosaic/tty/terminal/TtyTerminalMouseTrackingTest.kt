package com.jakewharton.mosaic.tty.terminal

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.terminal.MouseTracking
import kotlin.test.Test

class TtyTerminalMouseTrackingTest {
	@Test fun disabledLeavesMouseModesUnchanged() = terminalTest {
		expect("${CSI}0c", reply = "$CSI?62;22c")
		expect("${CSI}5n", reply = "${CSI}0n")

		val teardown = withTerminal { setup ->
			assertThat(setup).doesNotContain("$CSI?1002")
			assertThat(setup).doesNotContain("$CSI?1003")
			assertThat(setup).doesNotContain("$CSI?1006")
		}

		assertThat(teardown).doesNotContain("$CSI?1002")
		assertThat(teardown).doesNotContain("$CSI?1003")
		assertThat(teardown).doesNotContain("$CSI?1006")
	}

	@Test fun noModeReplyFallsBackToButtonEventTracking() = terminalTest {
		expect("${CSI}0c", reply = "$CSI?62;22c")
		expect("${CSI}5n", reply = "${CSI}0n")

		val teardown = withTerminal(MouseTracking.ButtonEvents) { setup ->
			assertThat(setup).contains("$CSI?${mouseButtonEventMode}h")
			assertThat(setup).contains(mouseSgrCoordinatesEnable)
		}

		assertThat(teardown).contains(mouseSgrCoordinatesDisable)
		assertThat(teardown).contains("$CSI?${mouseButtonEventMode}l")
	}

	@Test fun resetReplyEnablesButtonEventTrackingAndRestoresItOnClose() = terminalTest {
		expect("${CSI}0c", reply = "$CSI?62;22c")
		expect("$CSI?1002\$p", reply = "$CSI?1002;2\$y")
		expect("$CSI?1006\$p", reply = "$CSI?1006;2\$y")
		expect("${CSI}5n", reply = "${CSI}0n")

		val teardown = withTerminal(MouseTracking.ButtonEvents) { setup ->
			assertThat(setup).contains("$CSI?${mouseButtonEventMode}h")
			assertThat(setup).contains(mouseSgrCoordinatesEnable)

			ptyWrite("$CSI<0;2;1M")
			assertThat(events.receive()).isEqualTo(
				MouseEvent(1, 0, MouseEvent.Type.Press, MouseEvent.Button.Left),
			)
		}

		assertThat(teardown).contains(mouseSgrCoordinatesDisable)
		assertThat(teardown).contains("$CSI?${mouseButtonEventMode}l")
	}

	@Test fun anyEventTrackingUsesItsOwnMode() = terminalTest {
		expect("${CSI}0c", reply = "$CSI?62;22c")
		expect("$CSI?1003\$p", reply = "$CSI?1003;2\$y")
		expect("$CSI?1006\$p", reply = "$CSI?1006;2\$y")
		expect("${CSI}5n", reply = "${CSI}0n")

		val teardown = withTerminal(MouseTracking.AnyEvents) { setup ->
			assertThat(setup).contains("$CSI?${mouseAnyEventMode}h")
			assertThat(setup).doesNotContain("$CSI?${mouseButtonEventMode}h")
		}

		assertThat(teardown).contains("$CSI?${mouseAnyEventMode}l")
		assertThat(teardown).doesNotContain("$CSI?${mouseButtonEventMode}l")
	}

	@Test fun setReplyPreservesExistingMouseModes() = terminalTest {
		expect("${CSI}0c", reply = "$CSI?62;22c")
		expect("$CSI?1002\$p", reply = "$CSI?1002;1\$y")
		expect("$CSI?1006\$p", reply = "$CSI?1006;1\$y")
		expect("${CSI}5n", reply = "${CSI}0n")

		val teardown = withTerminal(MouseTracking.ButtonEvents) { setup ->
			assertThat(setup).doesNotContain("$CSI?${mouseButtonEventMode}h")
			assertThat(setup).doesNotContain(mouseSgrCoordinatesEnable)
		}

		assertThat(teardown).doesNotContain("$CSI?${mouseButtonEventMode}l")
		assertThat(teardown).doesNotContain(mouseSgrCoordinatesDisable)
	}
}
