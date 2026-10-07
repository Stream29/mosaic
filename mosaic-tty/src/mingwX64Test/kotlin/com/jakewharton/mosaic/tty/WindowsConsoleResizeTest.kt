package com.jakewharton.mosaic.tty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.cinterop.ExperimentalForeignApi
import platform.windows.ERROR_INVALID_PARAMETER
import platform.windows.ERROR_PATH_NOT_FOUND
import platform.windows.SetLastError

@OptIn(ExperimentalForeignApi::class)
class WindowsConsoleResizeTest {
	@Test fun successfulResizeDoesNotReturnStaleLastError() {
		TestTerminal.bind().use { terminal ->
			SetLastError(ERROR_PATH_NOT_FOUND.toUInt())
			terminal.resize(80, 24, 0, 0)
			val size = terminal.tty.currentSize()
			assertEquals(80, size[0])
			assertEquals(24, size[1])
		}
	}

	@Test fun failedResizeReportsActualWin32Error() {
		TestTerminal.bind().use { terminal ->
			SetLastError(ERROR_PATH_NOT_FOUND.toUInt())
			val error = assertFailsWith<IOException> {
				terminal.resize(0, 24, 0, 0)
			}
			assertEquals(ERROR_INVALID_PARAMETER.toString(), error.message)
		}
	}
}
