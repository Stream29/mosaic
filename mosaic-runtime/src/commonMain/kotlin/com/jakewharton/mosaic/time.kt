package com.jakewharton.mosaic

private const val NanosecondsPerSecond = 1_000_000_000L

internal fun timespecToNanos(
	seconds: Long,
	nanoseconds: Long,
): Long = seconds * NanosecondsPerSecond + nanoseconds
