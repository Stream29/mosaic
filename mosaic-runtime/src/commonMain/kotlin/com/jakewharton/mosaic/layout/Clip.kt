package com.jakewharton.mosaic.layout

import androidx.compose.runtime.Stable
import com.jakewharton.mosaic.modifier.Modifier

/** Clips drawing, pointer hit testing, focus projection, and focus cursor output to these bounds. */
@Stable
public fun Modifier.clipToBounds(): Modifier = this then ClipToBoundsElement

internal interface ViewportClipModifier : Modifier.Element

private object ClipToBoundsElement : ViewportClipModifier {
	override fun toString(): String = "ClipToBounds"
}
