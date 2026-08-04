package com.jakewharton.mosaic

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test

class PlatformTest {
	@Test fun timespecNanosecondsRemainMonotonicAcrossSecondBoundary() {
		val beforeBoundary = timespecToNanos(seconds = 41, nanoseconds = 999_999_999)
		val afterBoundary = timespecToNanos(seconds = 42, nanoseconds = 0)

		assertThat(afterBoundary - beforeBoundary).isEqualTo(1L)
	}
}
