package com.jakewharton.mosaic.focus

import androidx.compose.runtime.Stable
import androidx.compose.runtime.annotation.RememberInComposition
import com.jakewharton.mosaic.modifier.Modifier
import kotlin.jvm.JvmInline

/** The focus state of a focus target. */
public enum class FocusState {
	/** This target does not own focus. */
	Inactive,

	/** This target owns focus. */
	Active,

	/** A descendant of this target owns focus. */
	ActiveParent,
	;

	/** Whether this target directly owns focus. */
	public val isFocused: Boolean
		get() = this == Active

	/** Whether this target or one of its descendants owns focus. */
	public val hasFocus: Boolean
		get() = this != Inactive
}

/** The direction of a focus request or focus movement. */
@JvmInline
public value class FocusDirection internal constructor(private val value: Int) {
	override fun toString(): String = when (this) {
		Next -> "Next"
		Previous -> "Previous"
		Left -> "Left"
		Right -> "Right"
		Up -> "Up"
		Down -> "Down"
		Enter -> "Enter"
		Exit -> "Exit"
		else -> "Invalid FocusDirection"
	}

	public companion object {
		/** Searches for the next focusable target in traversal order. */
		public val Next: FocusDirection get() = FocusDirection(1)

		/** Searches for the previous focusable target in traversal order. */
		public val Previous: FocusDirection get() = FocusDirection(2)

		/** Searches for a focusable target to the left. */
		public val Left: FocusDirection get() = FocusDirection(3)

		/** Searches for a focusable target to the right. */
		public val Right: FocusDirection get() = FocusDirection(4)

		/** Searches for a focusable target above the current target. */
		public val Up: FocusDirection get() = FocusDirection(5)

		/** Searches for a focusable target below the current target. */
		public val Down: FocusDirection get() = FocusDirection(6)

		/** Requests focus inside a target or focus group. */
		public val Enter: FocusDirection get() = FocusDirection(7)

		/** Requests focus outside a target or focus group. */
		public val Exit: FocusDirection get() = FocusDirection(8)
	}
}

/** Sends programmatic focus requests through associated [Modifier.focusRequester] nodes. */
@Stable
public class FocusRequester @RememberInComposition public constructor() {
	private val nodes = mutableListOf<FocusRequesterNode>()

	/**
	 * Requests focus for the target associated with this requester.
	 *
	 * @param focusDirection The direction from which focus enters the requested target.
	 * @return `false` when the requester is detached or no associated target is currently eligible.
	 */
	public fun requestFocus(focusDirection: FocusDirection = FocusDirection.Enter): Boolean {
		var success = false
		for (node in nodes) {
			if (node.requestFocus(focusDirection)) success = true
		}
		return success
	}

	internal fun attach(node: FocusRequesterNode) {
		check(nodes.none { it === node }) { "FocusRequester node is already attached." }
		nodes += node
	}

	internal fun detach(node: FocusRequesterNode) {
		val index = nodes.indexOfFirst { it === node }
		if (index != -1) nodes.removeAt(index)
	}
}

/** @property owner `null` while this modifier node is detached from a projected focus tree. */
internal class FocusRequesterNode(
	var requester: FocusRequester,
) {
	private var owner: FocusOwner? = null

	fun attach(owner: FocusOwner) {
		check(this.owner == null) { "FocusRequester node is already owned by a focus tree." }
		this.owner = owner
	}

	fun detach(owner: FocusOwner) {
		if (this.owner === owner) this.owner = null
	}

	fun requestFocus(focusDirection: FocusDirection): Boolean = owner?.requestFocus(this, focusDirection) ?: false
}

internal class FocusEventNode(
	var onFocusChanged: (FocusState) -> Unit,
) {
	/** `null` until this node receives its first projected focus state. */
	private var focusState: FocusState? = null

	/** @return `true` when [onFocusChanged] was invoked. */
	fun dispatchFocusState(focusState: FocusState): Boolean {
		if (this.focusState == focusState) return false
		this.focusState = focusState
		onFocusChanged(focusState)
		return true
	}
}

/**
 * Makes the modified component eligible for sequential and directional focus.
 *
 * Focus is selected by the Mosaic runtime. [autoFocus] only affects initial focus when the
 * surrounding focus scope becomes active; it does not steal focus during ordinary recomposition.
 */
public fun Modifier.focusable(
	enabled: Boolean = true,
	autoFocus: Boolean = false,
): Modifier = this then FocusTargetModifierElement(enabled, autoFocus)

/** Associates [requester] with the nearest focus target in this modifier subtree. */
public fun Modifier.focusRequester(requester: FocusRequester): Modifier = this then FocusRequesterModifierElement(requester)

/** Observes the aggregate state of the first focus targets following this modifier. */
public fun Modifier.onFocusChanged(
	onFocusChanged: (FocusState) -> Unit,
): Modifier = this then FocusEventModifierElement(onFocusChanged)

/**
 * Sets the physical terminal cursor anchor for the nearest focus target.
 *
 * [column] and [row] use terminal-cell coordinates local to the modified component.
 */
public fun Modifier.focusCursor(
	column: Int,
	row: Int = 0,
): Modifier = this then FocusCursorModifierElement(column, row)

/** Groups descendant focus targets for hierarchical directional navigation. */
public fun Modifier.focusGroup(enabled: Boolean = true): Modifier = this then FocusScopeModifierElement(enabled, trapsFocus = false)

/**
 * Restricts focus to this modifier subtree while [enabled].
 *
 * Entering the trap focuses its autofocus target or first eligible descendant. Leaving it restores
 * the target which was focused before the trap became active.
 */
public fun Modifier.focusTrap(enabled: Boolean = true): Modifier = this then FocusScopeModifierElement(enabled, trapsFocus = true)

internal sealed interface FocusBoundaryHandle

internal class FocusTargetHandle : FocusBoundaryHandle {
	var enabled: Boolean = true
	var autoFocus: Boolean = false
}

internal class FocusScopeHandle : FocusBoundaryHandle {
	var enabled: Boolean = true
	var trapsFocus: Boolean = false
}

internal interface FocusTargetModifier : Modifier.Element {
	val enabled: Boolean
	val autoFocus: Boolean
}

internal interface FocusScopeModifier : Modifier.Element {
	val enabled: Boolean
	val trapsFocus: Boolean
}

internal interface FocusRequesterModifier : Modifier.Element {
	val focusRequester: FocusRequester
}

internal interface FocusEventModifier : Modifier.Element {
	val onFocusChanged: (FocusState) -> Unit
}

internal interface FocusCursorModifier : Modifier.Element {
	val column: Int
	val row: Int
}

private class FocusTargetModifierElement(
	override val enabled: Boolean,
	override val autoFocus: Boolean,
) : FocusTargetModifier

private class FocusScopeModifierElement(
	override val enabled: Boolean,
	override val trapsFocus: Boolean,
) : FocusScopeModifier

private class FocusRequesterModifierElement(
	override val focusRequester: FocusRequester,
) : FocusRequesterModifier

private class FocusEventModifierElement(
	override val onFocusChanged: (FocusState) -> Unit,
) : FocusEventModifier

private class FocusCursorModifierElement(
	override val column: Int,
	override val row: Int,
) : FocusCursorModifier
