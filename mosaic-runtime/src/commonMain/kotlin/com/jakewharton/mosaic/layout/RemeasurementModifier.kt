package com.jakewharton.mosaic.layout

import com.jakewharton.mosaic.modifier.Modifier

/** A [Modifier.Element] that provides the [Remeasurement] associated with its layout node. */
public interface RemeasurementModifier : Modifier.Element {
	/**
	 * Called when this modifier is attached to a layout node.
	 *
	 * @param remeasurement The [Remeasurement] associated with that layout node.
	 */
	public fun onRemeasurementAvailable(remeasurement: Remeasurement)
}

/**
 * Allows additional measure and layout work for an associated layout node.
 *
 * Normal layouts should rely on frame-driven measurement. This escape hatch is for algorithms
 * which must synchronously observe updated layout, such as scrolling and beyond-bounds search.
 */
public interface Remeasurement {
	/**
	 * Remeasures and lays out the associated node synchronously, even when it was not marked as
	 * needing remeasurement.
	 *
	 * Calling this while Mosaic is already measuring is invalid.
	 */
	public fun forceRemeasure()
}
