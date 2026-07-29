package com.jakewharton.mosaic.tty.terminal

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.jakewharton.mosaic.terminal.BracketedPasteEvent
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.PasteEvent
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class EventParserCsiBracketedPasteEventTest : BaseEventParserTest() {
	@Test fun pasteStart() {
		testTerminal.write("${CSI}200~")
		assertThat(parser.next()).isEqualTo(BracketedPasteEvent(start = true))
	}

	@Test fun pasteEnd() {
		testTerminal.write("${CSI}201~")
		assertThat(parser.next()).isEqualTo(BracketedPasteEvent(start = false))
	}

	@Test fun pasteIsAggregatedWhenEnabled() {
		parser.bracketedPasteEnabled = true
		testTerminal.write("${CSI}200~hello\tworld${CSI}201~")
		assertThat(parser.next()).isEqualTo(PasteEvent("hello\tworld"))
	}

	@Test fun largePastePreservesUtf8AndEscapesAcrossReads() = runBlocking {
		parser.bracketedPasteEnabled = true
		val payload = "prefix\t中😀e\u0301\r\n" + "x".repeat(9 * 1024) + "$ESC[31m suffix"
		val end = "${CSI}201~".encodeToByteArray()
		val result = async(Dispatchers.Default) { parser.next() }

		writeFully("${CSI}200~prefix\t中".encodeToByteArray())
		val emoji = "😀".encodeToByteArray()
		writeFully(emoji.copyOfRange(0, 2))
		writeFully(emoji.copyOfRange(2, emoji.size))
		writeFully(("e\u0301\r\n" + "x".repeat(9 * 1024) + "$ESC[31m suffix").encodeToByteArray())
		writeFully(end.copyOfRange(0, end.size - 2))
		writeFully(end.copyOfRange(end.size - 2, end.size))

		assertThat(result.await()).isEqualTo(PasteEvent(payload))

		testTerminal.write("z")
		assertThat(parser.next()).isEqualTo(KeyboardEvent('z'.code))
	}

	private fun writeFully(bytes: ByteArray) {
		var offset = 0
		while (offset < bytes.size) {
			val written = testTerminal.writeTty(bytes, offset, bytes.size - offset)
			check(written > 0)
			offset += written
		}
	}
}
