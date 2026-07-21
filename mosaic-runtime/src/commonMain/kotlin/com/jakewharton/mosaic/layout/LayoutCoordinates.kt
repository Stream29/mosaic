package com.jakewharton.mosaic.layout

import androidx.compose.runtime.Immutable
import com.jakewharton.mosaic.ui.unit.IntOffset
import com.jakewharton.mosaic.ui.unit.IntSize
import dev.drewhamilton.poko.Poko

/**
 * The position and size of a laid-out node in terminal cells.
 *
 * @property position Position relative to the current Mosaic surface.
 * @property size Size in terminal cells.
 */
@[Immutable Poko]
public class LayoutCoordinates internal constructor(
	public val position: IntOffset,
	public val size: IntSize,
)
