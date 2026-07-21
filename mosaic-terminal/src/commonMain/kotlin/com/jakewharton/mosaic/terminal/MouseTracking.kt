package com.jakewharton.mosaic.terminal

/** Selects which terminal mouse-tracking mode is enabled for an interactive session. */
public enum class MouseTracking {
	/** Do not change terminal mouse-tracking modes. */
	Disabled,

	/** Receive clicks, wheel events, and pointer movement while a mouse button is held. */
	ButtonEvents,

	/** Receive all pointer movement in addition to clicks, wheel events, and drags. */
	AnyEvents,
}
