package com.jakewharton.mosaic

internal expect fun env(name: String): String?

internal expect inline fun nanoTime(): Long

internal fun timespecToNanos(seconds: Long, nanoseconds: Long): Long = seconds * NanosecondsPerSecond + nanoseconds

internal expect fun nonInteractiveExit(): Nothing

private const val NanosecondsPerSecond = 1_000_000_000L
