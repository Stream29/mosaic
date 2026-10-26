package com.jakewharton.mosaic.tty

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PosixResizeThreadTest {
	@Test fun callbackUsesReadingThreadNotRegistrationThread() {
		if (isWindows()) return
		TestTerminal.bind().use { terminal ->
			val tty = terminal.tty
			tty.enableRawMode()
			tty.enableWindowResizeEvents()
			val callbackThread = AtomicReference<Thread>()
			val readerThread = AtomicReference<Thread>()
			val delivered = CountDownLatch(1)
			tty.setCallback(object : Tty.Callback {
				override fun onFocus(focused: Boolean) = Unit
				override fun onKey() = Unit
				override fun onMouse() = Unit
				override fun onResize(columns: Int, rows: Int, width: Int, height: Int) {
					callbackThread.set(Thread.currentThread())
					delivered.countDown()
				}
			})
			terminal.resize(101, 41, 0, 0)
			assertEquals(1L, delivered.count)
			val executor = Executors.newSingleThreadExecutor()
			try {
				val read = executor.submit<Int> {
					readerThread.set(Thread.currentThread())
					tty.read(ByteArray(1), 0, 1)
				}
				try {
					assertTrue(delivered.await(5, SECONDS))
					assertSame(readerThread.get(), callbackThread.get())
				} finally {
					tty.interruptRead()
					assertEquals(0, read.get(5, SECONDS))
				}
			} finally {
				executor.shutdownNow()
				assertTrue(executor.awaitTermination(5, SECONDS))
			}
		}
	}

	@Test fun callbackCanCloseItsOwnRead() {
		if (isWindows()) return
		TestTerminal.bind().use { terminal ->
			val tty = terminal.tty
			tty.enableRawMode()
			tty.enableWindowResizeEvents()
			tty.setCallback(object : Tty.Callback {
				override fun onFocus(focused: Boolean) = Unit
				override fun onKey() = Unit
				override fun onMouse() = Unit
				override fun onResize(columns: Int, rows: Int, width: Int, height: Int) = tty.close()
			})
			terminal.resize(102, 42, 0, 0)
			assertEquals(0, tty.readWithTimeout(ByteArray(1), 0, 1, 0))
		}
	}
}
