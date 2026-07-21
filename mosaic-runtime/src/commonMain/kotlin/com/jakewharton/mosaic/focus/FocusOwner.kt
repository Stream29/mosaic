package com.jakewharton.mosaic.focus

import com.jakewharton.mosaic.TerminalCursorPosition
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.KeyModifier
import com.jakewharton.mosaic.ui.unit.IntOffset
import com.jakewharton.mosaic.ui.unit.IntSize

/**
 * Owns the focus projection derived from the current Mosaic layout tree.
 *
 * @param onCursorChanged Receives `null` when no eligible focus target exists and the runtime must
 * hide its focus-owned terminal cursor.
 * @property focusedTarget `null` means that no target in the current tree owns focus.
 * @property activeTrap `null` means that focus is not currently restricted by a trapping scope.
 * @property rootRememberedTarget `null` means that the root scope has not focused a target yet.
 * @property restoreTargets A `null` value records that a trap was entered while no target owned
 * focus, so leaving that trap must restore the unfocused state.
 */
internal class FocusOwner(
	private val onCursorChanged: (TerminalCursorPosition?) -> Unit,
) {
	private var tree: FocusTree = FocusTree.Empty
	private var focusedTarget: FocusTargetHandle? = null
	private var activeTrap: FocusScopeEntry? = null
	private var activeTrapStack = emptyList<FocusScopeHandle>()
	private var rootRememberedTarget: FocusTargetHandle? = null
	private val rememberedTargets = mutableMapOf<FocusScopeHandle, FocusTargetHandle>()
	private val restoreTargets = mutableMapOf<FocusScopeHandle, FocusTargetHandle?>()
	private var requesterBindings = emptyMap<FocusRequesterNode, FocusRequester>()

	val ownsKeyDispatch: Boolean
		get() = focusedTarget != null || activeTrap != null

	fun clear() {
		reconcile(FocusTree.Empty)
	}

	fun reconcile(updatedTree: FocusTree) {
		val nextTrapStack = updatedTree.activeTrapStack()
		val nextTrap = nextTrapStack.lastOrNull()?.let(updatedTree::scope)
		val sharedTrapCount = activeTrapStack.commonPrefixSize(nextTrapStack)
		var preferredTarget = focusedTarget

		for (index in activeTrapStack.lastIndex downTo sharedTrapCount) {
			preferredTarget = restoreTargets.remove(activeTrapStack[index])
		}

		tree = updatedTree
		activeTrap = nextTrap
		activeTrapStack = nextTrapStack
		bindRequesters()

		for (index in sharedTrapCount until nextTrapStack.size) {
			val scope = tree.scope(nextTrapStack[index]) ?: continue
			restoreTargets[scope.handle] = preferredTarget
			preferredTarget = defaultTarget(scope)
		}

		val target = preferredTarget
			?.takeIf(::isEligible)
			?: defaultTarget(nextTrap)
		select(target)

		val attachedScopes = updatedTree.scopes.mapTo(mutableSetOf(), FocusScopeEntry::handle)
		restoreTargets.keys.retainAll(attachedScopes)
		rememberedTargets.keys.retainAll(attachedScopes)
	}

	fun requestFocus(requester: FocusRequesterNode, focusDirection: FocusDirection): Boolean {
		val entry = tree.requester(requester) ?: return false
		for (destination in entry.destinations) {
			val success = when (destination) {
				is FocusTargetHandle -> requestFocus(destination)
				is FocusScopeHandle -> requestFocus(destination, focusDirection)
			}
			if (success) return true
		}
		return false
	}

	private fun requestFocus(target: FocusTargetHandle): Boolean {
		if (!isEligible(target)) return false
		return select(target)
	}

	private fun requestFocus(scopeHandle: FocusScopeHandle, focusDirection: FocusDirection): Boolean {
		val scope = tree.scope(scopeHandle) ?: return false
		val eligibleTargets = tree.targets.filter { isEligible(it) && it.isWithin(scope) }
		val target = when (focusDirection) {
			FocusDirection.Enter -> eligibleTargets.bestCandidate(
				focused = scope.bounds.entryPoint(FocusDirection.Right),
				direction = FocusDirection.Right,
			)?.handle

			FocusDirection.Next -> eligibleTargets.firstOrNull()?.handle

			FocusDirection.Previous -> eligibleTargets.lastOrNull()?.handle

			FocusDirection.Left,
			FocusDirection.Right,
			FocusDirection.Up,
			FocusDirection.Down,
			-> eligibleTargets.bestCandidate(
				focused = scope.bounds.entryPoint(focusDirection),
				direction = focusDirection,
			)?.handle

			FocusDirection.Exit -> null

			else -> null
		} ?: return false
		return select(target)
	}

	fun requestFocusAt(position: IntOffset): Boolean {
		val target = tree.targets
			.asReversed()
			.firstOrNull { target -> isEligible(target) && target.bounds.contains(position) }
			?: return false
		return select(target.handle)
	}

	fun dispatchKeyEvent(event: KeyEvent): Boolean {
		val keyPath = focusedTarget
			?.let(tree::target)
			?.keyModifiers
			?: activeTrap?.keyModifiers
			?: emptyList()

		for (modifier in keyPath) {
			if (modifier.onPreKeyEvent(event)) return true
		}
		for (index in keyPath.lastIndex downTo 0) {
			if (keyPath[index].onKeyEvent(event)) return true
		}

		return when {
			event == Tab -> moveFocus(FocusDirection.Next)
			event == ShiftTab -> moveFocus(FocusDirection.Previous)
			event.hasNoModifiers() && event.key == "ArrowLeft" -> moveFocus(FocusDirection.Left)
			event.hasNoModifiers() && event.key == "ArrowRight" -> moveFocus(FocusDirection.Right)
			event.hasNoModifiers() && event.key == "ArrowUp" -> moveFocus(FocusDirection.Up)
			event.hasNoModifiers() && event.key == "ArrowDown" -> moveFocus(FocusDirection.Down)
			else -> false
		}
	}

	private fun moveFocus(direction: FocusDirection): Boolean = when (direction) {
		FocusDirection.Next -> moveSequentially(forward = true)

		FocusDirection.Previous -> moveSequentially(forward = false)

		FocusDirection.Left,
		FocusDirection.Right,
		FocusDirection.Up,
		FocusDirection.Down,
		-> moveDirectionally(direction)

		else -> false
	}

	private fun moveSequentially(forward: Boolean): Boolean {
		val targets = tree.targets.filter(::isEligible)
		if (targets.isEmpty()) return false

		val currentIndex = targets.indexOfFirst { it.handle === focusedTarget }
		val nextIndex = when {
			forward -> (currentIndex + 1).mod(targets.size)
			currentIndex <= 0 -> targets.lastIndex
			else -> currentIndex - 1
		}
		select(targets[nextIndex].handle)
		return true
	}

	private fun moveDirectionally(direction: FocusDirection): Boolean {
		val current = focusedTarget?.let(tree::target) ?: return false
		var scope = current.scope

		while (true) {
			val searchScope = scope
			val candidate = tree.targets.bestCandidate(
				focused = current.bounds,
				direction = direction,
			) { target ->
				isEligible(target) &&
					target.handle !== current.handle &&
					(searchScope == null || target.isWithin(searchScope))
			}
			if (candidate != null) {
				select(candidate.handle)
				return true
			}

			if (scope == null || scope.handle.trapsFocus) return false
			scope = scope.parent
		}
	}

	/**
	 * @param scope `null` selects from the root focus scope.
	 * @return `null` when the selected scope contains no eligible target.
	 */
	private fun defaultTarget(scope: FocusScopeEntry?): FocusTargetHandle? {
		val eligibleTargets = tree.targets.filter { target ->
			target.isFocusable() && (scope == null || target.isWithin(scope))
		}
		if (eligibleTargets.isEmpty()) return null

		val rememberedTarget = if (scope == null) {
			rootRememberedTarget
		} else {
			rememberedTargets[scope.handle]
		}
		return eligibleTargets.firstOrNull { it.handle.autoFocus }?.handle
			?: rememberedTarget?.takeIf { remembered -> eligibleTargets.any { it.handle === remembered } }
			?: eligibleTargets.first().handle
	}

	private fun isEligible(target: FocusTargetHandle): Boolean {
		val entry = tree.target(target) ?: return false
		return isEligible(entry)
	}

	private fun isEligible(target: FocusTargetEntry): Boolean {
		if (!target.isFocusable()) return false
		val trap = activeTrap ?: return true
		return target.isWithin(trap)
	}

	/** @param target `null` clears focus because the current tree has no eligible target. */
	private fun select(target: FocusTargetHandle?): Boolean {
		focusedTarget = target
		val entry = target?.let(tree::target)
		if (entry != null) {
			rootRememberedTarget = target
			var scope = entry.scope
			while (scope != null) {
				rememberedTargets[scope.handle] = target
				scope = scope.parent
			}
		}
		onCursorChanged(entry?.cursorPosition ?: entry?.bounds?.topLeftCursor())

		// Focus is committed before callbacks run. A callback may synchronously request another
		// target; stop the obsolete dispatch as soon as that happens.
		val eventStates = tree.events.map { event -> event to tree.focusState(event, target) }
		for (focusState in FocusEventDispatchOrder) {
			for (index in eventStates.lastIndex downTo 0) {
				val (event, eventFocusState) = eventStates[index]
				if (eventFocusState != focusState) continue
				val callbackInvoked = event.node.dispatchFocusState(eventFocusState)
				if (callbackInvoked && focusedTarget !== target) return false
			}
		}
		return focusedTarget === target
	}

	private fun bindRequesters() {
		val updatedBindings = tree.requesters.associate { it.node to it.requester }

		for ((node, requester) in requesterBindings) {
			if (updatedBindings[node] !== requester) {
				requester.detach(node)
				node.detach(this)
			}
		}
		for ((node, requester) in updatedBindings) {
			if (requesterBindings[node] !== requester) {
				node.attach(this)
				requester.attach(node)
			}
		}
		requesterBindings = updatedBindings
	}
}

internal class FocusTreeCollector {
	private val targets = mutableListOf<FocusTargetEntry>()
	private val scopes = mutableListOf<FocusScopeEntry>()
	private val requesters = mutableListOf<FocusRequesterEntry>()
	private val events = mutableListOf<FocusEventEntry>()
	private val keyModifiers = mutableListOf<KeyModifier>()
	private val scopeStack = mutableListOf<FocusScopeEntry>()
	private val focusBoundaryStack = mutableListOf<FocusBoundaryHandle>()
	private val targetStack = mutableListOf<FocusTargetDraft>()
	private val requesterStack = mutableListOf<FocusRequesterDraft>()
	private val eventStack = mutableListOf<FocusEventDraft>()
	private val cursorPositions = mutableListOf<TerminalCursorPosition>()
	private var nextOrder = 0

	fun visitKeyModifier(modifier: KeyModifier, block: () -> Unit) {
		targetStack.lastOrNull()?.keyModifiers?.add(modifier)
		keyModifiers += modifier
		block()
		keyModifiers.removeAt(keyModifiers.lastIndex)
	}

	fun visitFocusTarget(
		handle: FocusTargetHandle,
		bounds: FocusBounds,
		block: () -> Unit,
	) {
		if (!handle.enabled) {
			block()
			return
		}
		recordFocusBoundary(handle)
		val draft = FocusTargetDraft(
			handle = handle,
			bounds = bounds,
			scope = scopeStack.lastOrNull(),
			parentFocusBoundary = focusBoundaryStack.lastOrNull(),
			order = nextOrder++,
			keyModifiers = keyModifiers.toMutableList(),
			cursorPosition = cursorPositions.lastOrNull(),
		)
		targetStack += draft
		focusBoundaryStack += handle
		block()
		focusBoundaryStack.removeAt(focusBoundaryStack.lastIndex)
		targetStack.removeAt(targetStack.lastIndex)
		targets += draft.toEntry()
	}

	fun visitFocusScope(
		handle: FocusScopeHandle,
		bounds: FocusBounds,
		block: () -> Unit,
	) {
		if (!handle.enabled) {
			block()
			return
		}
		recordFocusBoundary(handle)
		val scope = FocusScopeEntry(
			handle = handle,
			bounds = bounds,
			parent = scopeStack.lastOrNull(),
			parentFocusBoundary = focusBoundaryStack.lastOrNull(),
			order = nextOrder++,
			keyModifiers = keyModifiers.toList(),
		)
		scopes += scope
		scopeStack += scope
		focusBoundaryStack += handle
		block()
		focusBoundaryStack.removeAt(focusBoundaryStack.lastIndex)
		scopeStack.removeAt(scopeStack.lastIndex)
	}

	fun visitFocusEvent(node: FocusEventNode, block: () -> Unit) {
		val draft = FocusEventDraft(
			node = node,
			order = nextOrder++,
			focusBoundaryDepth = focusBoundaryStack.size,
		)
		eventStack += draft
		block()
		eventStack.removeAt(eventStack.lastIndex)
		events += draft.toEntry()
	}

	fun visitFocusRequester(node: FocusRequesterNode, block: () -> Unit) {
		val draft = FocusRequesterDraft(
			node = node,
			requester = node.requester,
			order = nextOrder++,
			focusBoundaryDepth = focusBoundaryStack.size,
		)
		requesterStack += draft
		block()
		requesterStack.removeAt(requesterStack.lastIndex)
		requesters += draft.toEntry()
	}

	fun visitFocusCursor(position: TerminalCursorPosition, block: () -> Unit) {
		targetStack.lastOrNull()?.cursorPosition = position
		cursorPositions += position
		block()
		cursorPositions.removeAt(cursorPositions.lastIndex)
	}

	fun build(): FocusTree = FocusTree(
		targets = targets.sortedBy(FocusTargetEntry::order),
		scopes = scopes.sortedBy(FocusScopeEntry::order),
		requesters = requesters.sortedBy(FocusRequesterEntry::order),
		events = events.sortedBy(FocusEventEntry::order),
	)

	private fun recordFocusBoundary(handle: FocusBoundaryHandle) {
		for (requester in requesterStack) {
			if (requester.focusBoundaryDepth == focusBoundaryStack.size) {
				requester.destinations += handle
			}
		}
		for (event in eventStack) {
			if (event.focusBoundaryDepth == focusBoundaryStack.size) {
				event.destinations += handle
			}
		}
	}
}

internal data class FocusTree(
	val targets: List<FocusTargetEntry>,
	val scopes: List<FocusScopeEntry>,
	val requesters: List<FocusRequesterEntry>,
	val events: List<FocusEventEntry>,
) {
	private val targetsByHandle = targets.associateBy(FocusTargetEntry::handle)
	private val scopesByHandle = scopes.associateBy(FocusScopeEntry::handle)
	private val requestersByNode = requesters.associateBy(FocusRequesterEntry::node)

	/** @return `null` when [handle] is not attached to the current layout tree. */
	fun target(handle: FocusTargetHandle): FocusTargetEntry? = targetsByHandle[handle]

	/** @return `null` when [handle] is not attached to the current layout tree. */
	fun scope(handle: FocusScopeHandle): FocusScopeEntry? = scopesByHandle[handle]

	/** @return `null` when [handle] is not attached to the current layout tree. */
	fun requester(node: FocusRequesterNode): FocusRequesterEntry? = requestersByNode[node]

	fun activeTrapStack(): List<FocusScopeHandle> = scopes
		.asSequence()
		.filter { scope -> scope.handle.enabled && scope.handle.trapsFocus && scope.bounds.hasArea() }
		.map(FocusScopeEntry::handle)
		.toList()

	companion object {
		val Empty = FocusTree(emptyList(), emptyList(), emptyList(), emptyList())
	}
}

/** @param focusedTarget `null` means that the focus owner currently has no active target. */
private fun FocusTree.focusState(
	event: FocusEventEntry,
	focusedTarget: FocusTargetHandle?,
): FocusState {
	if (focusedTarget == null) return FocusState.Inactive
	for (destination in event.destinations) {
		if (destination === focusedTarget) return FocusState.Active
		if (focusedTarget.isWithin(destination, this)) return FocusState.ActiveParent
	}
	return FocusState.Inactive
}

private fun FocusTargetHandle.isWithin(
	boundary: FocusBoundaryHandle,
	tree: FocusTree,
): Boolean {
	var parent = tree.target(this)?.parentFocusBoundary
	while (parent != null) {
		if (parent === boundary) return true
		parent = when (parent) {
			is FocusTargetHandle -> tree.target(parent)?.parentFocusBoundary
			is FocusScopeHandle -> tree.scope(parent)?.parentFocusBoundary
		}
	}
	return false
}

/**
 * @property scope `null` means that this target belongs directly to the root focus scope.
 * @property parentFocusBoundary `null` means that no focus target or scope encloses this target.
 * @property cursorPosition `null` means that the target uses its top-left terminal cell as the
 * cursor anchor.
 */
internal data class FocusTargetEntry(
	val handle: FocusTargetHandle,
	val bounds: FocusBounds,
	val scope: FocusScopeEntry?,
	val parentFocusBoundary: FocusBoundaryHandle?,
	val order: Int,
	val keyModifiers: List<KeyModifier>,
	val cursorPosition: TerminalCursorPosition?,
)

internal data class FocusRequesterEntry(
	val node: FocusRequesterNode,
	val requester: FocusRequester,
	val order: Int,
	val destinations: List<FocusBoundaryHandle>,
)

internal data class FocusEventEntry(
	val node: FocusEventNode,
	val order: Int,
	val destinations: List<FocusBoundaryHandle>,
)

/**
 * @property parent `null` means that this scope belongs directly to the root focus scope.
 * @property parentFocusBoundary `null` means that no focus target or scope encloses this scope.
 */
internal data class FocusScopeEntry(
	val handle: FocusScopeHandle,
	val bounds: FocusBounds,
	val parent: FocusScopeEntry?,
	val parentFocusBoundary: FocusBoundaryHandle?,
	val order: Int,
	val keyModifiers: List<KeyModifier>,
)

internal data class FocusBounds(
	val position: IntOffset,
	val size: IntSize,
) {
	private val left: Int get() = position.x
	private val top: Int get() = position.y
	private val right: Int get() = position.x + size.width
	private val bottom: Int get() = position.y + size.height

	fun hasArea(): Boolean = size.width > 0 && size.height > 0

	fun contains(point: IntOffset): Boolean = point.x in left until right && point.y in top until bottom

	fun isBetterCandidateThan(
		currentCandidate: FocusBounds,
		focused: FocusBounds,
		direction: FocusDirection,
	): Boolean = when {
		!isCandidate(focused, direction) -> false

		!currentCandidate.isCandidate(focused, direction) -> true

		beamBeats(currentCandidate, focused, direction) -> true

		currentCandidate.beamBeats(this, focused, direction) -> false

		else ->
			weightedDistanceFrom(focused, direction) <
				currentCandidate.weightedDistanceFrom(focused, direction)
	}

	fun entryPoint(direction: FocusDirection): FocusBounds = when (direction) {
		FocusDirection.Right,
		FocusDirection.Down,
		-> FocusBounds(IntOffset(left, top), IntSize(0, 0))

		FocusDirection.Left,
		FocusDirection.Up,
		-> FocusBounds(IntOffset(right, bottom), IntSize(0, 0))

		else -> error("Not a spatial focus direction: $direction")
	}

	fun topLeftCursor(): TerminalCursorPosition = TerminalCursorPosition(row = top, column = left)

	private fun isCandidate(focused: FocusBounds, direction: FocusDirection): Boolean = when (direction) {
		FocusDirection.Left ->
			(focused.right > right || focused.left >= right) && focused.left > left

		FocusDirection.Right ->
			(focused.left < left || focused.right <= left) && focused.right < right

		FocusDirection.Up ->
			(focused.bottom > bottom || focused.top >= bottom) && focused.top > top

		FocusDirection.Down ->
			(focused.top < top || focused.bottom <= top) && focused.bottom < bottom

		else -> error("Not a spatial focus direction: $direction")
	}

	private fun beamBeats(
		other: FocusBounds,
		focused: FocusBounds,
		direction: FocusDirection,
	): Boolean = when {
		other.isInBeam(focused, direction) || !isInBeam(focused, direction) -> false

		!other.isFullyInDirectionOf(focused, direction) -> true

		direction == FocusDirection.Left || direction == FocusDirection.Right -> true

		else ->
			majorAxisDistanceFrom(focused, direction) <
				other.majorAxisDistanceToFarEdgeFrom(focused, direction)
	}

	private fun isInBeam(focused: FocusBounds, direction: FocusDirection): Boolean = when (direction) {
		FocusDirection.Left,
		FocusDirection.Right,
		-> bottom > focused.top && top < focused.bottom

		FocusDirection.Up,
		FocusDirection.Down,
		-> right > focused.left && left < focused.right

		else -> error("Not a spatial focus direction: $direction")
	}

	private fun isFullyInDirectionOf(
		focused: FocusBounds,
		direction: FocusDirection,
	): Boolean = when (direction) {
		FocusDirection.Left -> focused.left >= right
		FocusDirection.Right -> focused.right <= left
		FocusDirection.Up -> focused.top >= bottom
		FocusDirection.Down -> focused.bottom <= top
		else -> error("Not a spatial focus direction: $direction")
	}

	private fun majorAxisDistanceFrom(
		focused: FocusBounds,
		direction: FocusDirection,
	): Int = when (direction) {
		FocusDirection.Left -> focused.left - right
		FocusDirection.Right -> left - focused.right
		FocusDirection.Up -> focused.top - bottom
		FocusDirection.Down -> top - focused.bottom
		else -> error("Not a spatial focus direction: $direction")
	}.coerceAtLeast(0)

	private fun majorAxisDistanceToFarEdgeFrom(
		focused: FocusBounds,
		direction: FocusDirection,
	): Int = when (direction) {
		FocusDirection.Left -> focused.left - left
		FocusDirection.Right -> right - focused.right
		FocusDirection.Up -> focused.top - top
		FocusDirection.Down -> bottom - focused.bottom
		else -> error("Not a spatial focus direction: $direction")
	}.coerceAtLeast(1)

	private fun weightedDistanceFrom(
		focused: FocusBounds,
		direction: FocusDirection,
	): Long {
		val majorAxisDistance = majorAxisDistanceFrom(focused, direction).toLong()
		val minorAxisDistance = when (direction) {
			FocusDirection.Left,
			FocusDirection.Right,
			-> (focused.top.toLong() + focused.bottom - top - bottom) / 2

			FocusDirection.Up,
			FocusDirection.Down,
			-> (focused.left.toLong() + focused.right - left - right) / 2

			else -> error("Not a spatial focus direction: $direction")
		}
		return 13 * majorAxisDistance * majorAxisDistance + minorAxisDistance * minorAxisDistance
	}
}

/**
 * @property scope `null` means that this target belongs directly to the root focus scope.
 * @property parentFocusBoundary `null` means that no focus target or scope encloses this target.
 * @property cursorPosition `null` means that no explicit cursor modifier has been collected and
 * the completed target will use its top-left terminal cell.
 */
private class FocusTargetDraft(
	val handle: FocusTargetHandle,
	val bounds: FocusBounds,
	val scope: FocusScopeEntry?,
	val parentFocusBoundary: FocusBoundaryHandle?,
	val order: Int,
	val keyModifiers: MutableList<KeyModifier>,
	var cursorPosition: TerminalCursorPosition?,
) {
	fun toEntry(): FocusTargetEntry = FocusTargetEntry(
		handle = handle,
		bounds = bounds,
		scope = scope,
		parentFocusBoundary = parentFocusBoundary,
		order = order,
		keyModifiers = keyModifiers.toList(),
		cursorPosition = cursorPosition,
	)
}

private class FocusRequesterDraft(
	val node: FocusRequesterNode,
	val requester: FocusRequester,
	val order: Int,
	val focusBoundaryDepth: Int,
	val destinations: MutableList<FocusBoundaryHandle> = mutableListOf(),
) {
	fun toEntry(): FocusRequesterEntry = FocusRequesterEntry(
		node = node,
		requester = requester,
		order = order,
		destinations = destinations.toList(),
	)
}

private class FocusEventDraft(
	val node: FocusEventNode,
	val order: Int,
	val focusBoundaryDepth: Int,
	val destinations: MutableList<FocusBoundaryHandle> = mutableListOf(),
) {
	fun toEntry(): FocusEventEntry = FocusEventEntry(
		node = node,
		order = order,
		destinations = destinations.toList(),
	)
}

private fun FocusTargetEntry.isWithin(scope: FocusScopeEntry): Boolean {
	var current = this.scope
	while (current != null) {
		if (current.handle === scope.handle) return true
		current = current.parent
	}
	return false
}

private fun FocusTargetEntry.isFocusable(): Boolean = handle.enabled && bounds.hasArea()

/** @return `null` when this collection contains no eligible candidate in [direction]. */
private inline fun Iterable<FocusTargetEntry>.bestCandidate(
	focused: FocusBounds,
	direction: FocusDirection,
	isEligible: (FocusTargetEntry) -> Boolean = { true },
): FocusTargetEntry? {
	// Null until the first eligible directional candidate is visited.
	var bestCandidate: FocusTargetEntry? = null
	for (candidate in this) {
		if (!isEligible(candidate)) continue
		val currentBest = bestCandidate
		if (currentBest == null || candidate.bounds.isBetterCandidateThan(currentBest.bounds, focused, direction)) {
			bestCandidate = candidate
		}
	}
	return bestCandidate
}

private fun List<FocusScopeHandle>.commonPrefixSize(other: List<FocusScopeHandle>): Int {
	val limit = minOf(size, other.size)
	for (index in 0 until limit) {
		if (this[index] !== other[index]) return index
	}
	return limit
}

private fun KeyEvent.hasNoModifiers(): Boolean = !alt && !ctrl && !shift

private val Tab = KeyEvent("Tab")
private val ShiftTab = KeyEvent("Tab", shift = true)
private val FocusEventDispatchOrder = listOf(FocusState.Inactive, FocusState.ActiveParent, FocusState.Active)
