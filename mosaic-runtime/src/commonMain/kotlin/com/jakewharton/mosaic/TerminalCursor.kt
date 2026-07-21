package com.jakewharton.mosaic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.drewhamilton.poko.Poko

/** A zero-based terminal-cell position within the current Mosaic surface. */
@[Immutable Poko]
public class TerminalCursorPosition(
	public val row: Int,
	public val column: Int,
)

/**
 * Arbitrates the explicit cursor API and the cursor anchor owned by the focus runtime.
 *
 * @param onPositionChanged Receives `null` when neither source provides a cursor and the terminal
 * cursor must be hidden.
 * @property explicitPosition `null` means that [TerminalCursor] currently provides no position.
 * @property focusPosition `null` means that no focused target currently provides a cursor anchor.
 */
@Stable
internal class TerminalCursorController(
	private val onPositionChanged: (TerminalCursorPosition?) -> Unit,
) {
	private var explicitPosition: TerminalCursorPosition? = null
	private var focusPosition: TerminalCursorPosition? = null

	/** @param position `null` removes the cursor supplied by [TerminalCursor]. */
	fun update(position: TerminalCursorPosition?) {
		explicitPosition = position
		publish()
	}

	/** @param position `null` means that no focus target currently owns the cursor. */
	fun updateFocus(position: TerminalCursorPosition?) {
		focusPosition = position
		publish()
	}

	private fun publish() {
		onPositionChanged(focusPosition ?: explicitPosition)
	}
}

internal val LocalTerminalCursorController: ProvidableCompositionLocal<TerminalCursorController> =
	staticCompositionLocalOf {
		throw AssertionError("No terminal cursor controller provided")
	}

/**
 * Positions the terminal's physical cursor within the current Mosaic surface.
 *
 * Call this once from the root that owns keyboard input. This is useful for integrations such as
 * input method editors, which use the terminal cursor as the anchor for their candidate window.
 *
 * @param position `null` hides the explicit cursor unless the focus runtime currently owns one.
 */
@Composable
public fun TerminalCursor(position: TerminalCursorPosition?) {
	val controller = LocalTerminalCursorController.current
	SideEffect {
		controller.update(position)
	}
	DisposableEffect(controller) {
		onDispose {
			controller.update(null)
		}
	}
}
