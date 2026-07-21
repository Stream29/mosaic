package com.jakewharton.mosaic.terminal

/** Selects which terminal buffer is used for an interactive session. */
public enum class TerminalScreen {
	/** Render in the normal terminal buffer. */
	Inline,

	/** Render in the alternate terminal buffer and restore the normal buffer when the session ends. */
	Alternate,
}
