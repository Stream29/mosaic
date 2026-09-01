package com.jakewharton.mosaic.layout

import androidx.compose.runtime.ComposeNodeLifecycleCallback
import com.jakewharton.mosaic.TerminalCursorPosition
import com.jakewharton.mosaic.TextCanvas
import com.jakewharton.mosaic.TextSurface
import com.jakewharton.mosaic.focus.FocusBounds
import com.jakewharton.mosaic.focus.FocusCursorModifier
import com.jakewharton.mosaic.focus.FocusEventModifier
import com.jakewharton.mosaic.focus.FocusEventNode
import com.jakewharton.mosaic.focus.FocusRequesterModifier
import com.jakewharton.mosaic.focus.FocusRequesterNode
import com.jakewharton.mosaic.focus.FocusScopeHandle
import com.jakewharton.mosaic.focus.FocusScopeModifier
import com.jakewharton.mosaic.focus.FocusTargetNode
import com.jakewharton.mosaic.focus.FocusTree
import com.jakewharton.mosaic.focus.FocusTreeCollector
import com.jakewharton.mosaic.layout.Placeable.PlacementScope
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.node.ModifierNodeElement
import com.jakewharton.mosaic.node.NodeChain
import com.jakewharton.mosaic.terminal.PasteEvent
import com.jakewharton.mosaic.ui.SubcomposeLayoutNodeState
import com.jakewharton.mosaic.ui.unit.Constraints
import com.jakewharton.mosaic.ui.unit.IntOffset
import com.jakewharton.mosaic.ui.unit.IntRect
import com.jakewharton.mosaic.ui.unit.IntSize
import kotlin.coroutines.CoroutineContext

internal fun interface DebugPolicy {
	fun MosaicNode.renderDebug(): String
}

internal abstract class MosaicNodeLayer :
	Placeable(),
	Measurable,
	PlacementScope,
	MeasureScope {
	abstract val next: MosaicNodeLayer?

	private var measureResult: MeasureResult = NotMeasured

	final override var parentData: Any? = null

	final override val width get() = measureResult.width
	final override val height get() = measureResult.height

	override fun measure(constraints: Constraints): Placeable = apply {
		measureResult = doMeasure(constraints)
	}

	protected open fun doMeasure(constraints: Constraints): MeasureResult {
		val placeable = next!!.measure(constraints)
		return object : MeasureResult {
			override val width: Int get() = placeable.width
			override val height: Int get() = placeable.height

			override fun placeChildren() {
				placeable.place(0, 0)
			}
		}
	}

	final override var x = 0
		private set
	final override var y = 0
		private set

	override fun placeAt(x: Int, y: Int) {
		this.x = x
		this.y = y
		measureResult.placeChildren()
	}

	internal fun replaceAt(x: Int, y: Int) {
		placeAt(x, y)
	}

	open fun drawTo(canvas: TextSurface) {
		next?.drawTo(canvas)
	}

	open fun sendKeyEvent(keyEvent: KeyEvent): Boolean {
		return next?.sendKeyEvent(keyEvent) ?: false
	}

	open fun sendPasteEvent(pasteEvent: PasteEvent): Boolean {
		return next?.sendPasteEvent(pasteEvent) ?: false
	}

	open fun collectFocus(collector: FocusTreeCollector) {
		next?.collectFocus(collector)
	}

	open fun collectPointer(collector: PointerTreeCollector) {
		next?.collectPointer(collector)
	}

	override fun minIntrinsicWidth(height: Int): Int {
		return next?.minIntrinsicWidth(height) ?: 0
	}

	override fun maxIntrinsicWidth(height: Int): Int {
		return next?.maxIntrinsicWidth(height) ?: 0
	}

	override fun minIntrinsicHeight(width: Int): Int {
		return next?.minIntrinsicHeight(width) ?: 0
	}

	override fun maxIntrinsicHeight(width: Int): Int {
		return next?.maxIntrinsicHeight(width) ?: 0
	}
}

internal object NotMeasured : MeasureResult {
	override val width get() = 0
	override val height get() = 0
	override fun placeChildren() = throw UnsupportedOperationException("Not measured")
}

/** Coordinates layout work requested by nodes in one attached Mosaic tree. */
internal interface MosaicNodeOwner {
	/** Parent context for work owned by attached modifier nodes. */
	val coroutineContext: CoroutineContext

	/** Handles a relayout request from [node]. */
	fun onRequestRelayout(node: MosaicNode)

	/** Measures and lays out the tree affected by [node] before returning. */
	fun measureAndLayout(node: MosaicNode)

}

/**
 * A node in the Mosaic layout tree.
 *
 * @property isVirtual Whether this node is a transparent structural container rather than a
 * measurable and renderable layout node. Virtual nodes hold the content of subcomposition slots;
 * their active children participate in rendering and input traversal as children of the nearest
 * non-virtual ancestor. `false` is the default because ordinary layout nodes are the primary node
 * type.
 * @property isDeactivated Whether this node is retained for later reuse but currently excluded
 * from measurement, intrinsic measurement, rendering, focus, pointer, and keyboard traversal.
 * Compose node lifecycle callbacks maintain this state for ordinary nodes. Subcomposition slot
 * management changes it explicitly for virtual slot roots.
 * @property foldedChildren Physical children managed by the applier. This list retains virtual and
 * deactivated nodes so subcomposition state can manage their lifecycle.
 * @property children Active logical children. Virtual nodes are replaced recursively by their
 * children.
 * @property owner `null` means this node is not attached to a Mosaic node tree.
 * @property subcompositionsState `null` means this node has never been configured as a
 * [com.jakewharton.mosaic.ui.SubcomposeLayout]. Once created, the state remains associated with
 * this node across reuse.
 */
internal class MosaicNode(
	measurePolicy: MeasurePolicy,
	var debugPolicy: DebugPolicy,
	val isStatic: Boolean,
	val isVirtual: Boolean = false,
) : Measurable,
	ComposeNodeLifecycleCallback {
	private val mutableFoldedChildren = ArrayList<MosaicNode>()
	internal var parent: MosaicNode? = null
		private set
	internal val foldedChildren: List<MosaicNode>
		get() = mutableFoldedChildren
	val children: List<MosaicNode>
		get() {
			if (mutableFoldedChildren.none { child -> child.isVirtual || child.isDeactivated }) {
				return mutableFoldedChildren
			}
			return mutableFoldedChildren.asSequence()
				.filterNot { child -> child.isDeactivated }
				.flatMap { child ->
					if (child.isVirtual) child.children.asSequence() else sequenceOf(child)
				}
				.toList()
		}
	internal var isDeactivated = false
		private set
	private var owner: MosaicNodeOwner? = null
	private var subcompositionsState: SubcomposeLayoutNodeState? = null
	private var measurementDirty = true
	private var lastConstraints: Constraints? = null
	private var remeasurementModifiers: List<RemeasurementModifier> = emptyList()
	private val modifierNodeChain = NodeChain(this)
	private val remeasurement = object : Remeasurement {
		override fun forceRemeasure() {
			owner?.measureAndLayout(this@MosaicNode)
		}
	}

	private val bottomLayer: MosaicNodeLayer = BottomLayer(this)
	private val focusScopes = mutableListOf<FocusScopeHandle>()
	private val focusRequesterNodes = mutableListOf<FocusRequesterNode>()
	private val focusEventNodes = mutableListOf<FocusEventNode>()
	private val pointerInputNodes = mutableListOf<PointerInputNode>()
	private val pointerHoverNodes = mutableListOf<PointerHoverNode>()
	private val onPlacedHandles = mutableListOf<OnPlacedHandle>()
	var measurePolicy: MeasurePolicy = measurePolicy
		set(value) {
			if (field === value) return
			field = value
			requestRelayout()
		}
	var topLayer: MosaicNodeLayer = bottomLayer
		private set

	override var parentData: Any? = null
		private set

	var testTag: String? = null
		private set

	internal val isAttached: Boolean
		get() = owner != null

	internal val modifierNodeTail: Modifier.Node?
		get() = modifierNodeChain.tail

	internal val modifierNodeCoroutineContext: CoroutineContext
		get() = checkNotNull(owner) { "Mosaic node is detached" }.coroutineContext

	internal fun attachRoot(owner: MosaicNodeOwner) {
		attach(owner)
	}

	private fun attach(owner: MosaicNodeOwner) {
		check(!isAttached) {
			"Mosaic node is already attached"
		}
		this.owner = owner
		modifierNodeChain.attach()
		for (child in mutableFoldedChildren) {
			child.attach(owner)
		}
		notifyRemeasurementModifiers()
	}

	internal fun requestRelayout() {
		markMeasurementDirty()
		owner?.onRequestRelayout(this)
	}

	internal fun markMeasurementDirty() {
		var node: MosaicNode? = this
		while (node != null) {
			node.measurementDirty = true
			node = node.parent
		}
	}

	internal fun markMeasurementSubtreeDirty() {
		markMeasurementSubtreeDirtyDown()
		parent?.markMeasurementDirty()
	}

	private fun markMeasurementSubtreeDirtyDown() {
		measurementDirty = true
		for (child in mutableFoldedChildren) {
			child.markMeasurementSubtreeDirtyDown()
		}
	}

	internal fun insertAt(index: Int, instance: MosaicNode) {
		check(!instance.isAttached) { "Cannot insert an attached Mosaic node" }
		check(instance.parent == null) { "Cannot insert a Mosaic node which already has a parent" }
		mutableFoldedChildren.add(index, instance)
		instance.parent = this
		owner?.let(instance::attach)
		markMeasurementDirty()
	}

	internal fun removeAt(index: Int, count: Int) {
		require(count >= 0) { "count ($count) must be non-negative" }
		for (childIndex in index + count - 1 downTo index) {
			val child = mutableFoldedChildren[childIndex]
			child.detachFromTree()
			child.parent = null
			mutableFoldedChildren.removeAt(childIndex)
		}
		markMeasurementDirty()
	}

	internal fun removeAll() {
		removeAt(0, mutableFoldedChildren.size)
	}

	internal fun move(from: Int, to: Int, count: Int) {
		if (count == 0 || from == to) return
		val movedChildren = mutableFoldedChildren.subList(from, from + count).toList()
		mutableFoldedChildren.subList(from, from + count).clear()
		mutableFoldedChildren.addAll(if (to > from) to - count else to, movedChildren)
		markMeasurementDirty()
	}

	internal fun activateVirtualNode() {
		check(isVirtual) { "Only virtual nodes can be activated explicitly" }
		isDeactivated = false
		parent?.markMeasurementDirty()
	}

	internal fun deactivateVirtualNode() {
		check(isVirtual) { "Only virtual nodes can be deactivated explicitly" }
		isDeactivated = true
		parent?.markMeasurementDirty()
	}

	internal fun getOrCreateSubcompositions(
		maxSlotsToRetainForReuse: Int,
	): SubcomposeLayoutNodeState {
		val existing = subcompositionsState
		if (existing != null) {
			existing.updateMaxSlotsToRetainForReuse(maxSlotsToRetainForReuse)
			return existing
		}
		return SubcomposeLayoutNodeState(this, maxSlotsToRetainForReuse).also {
			subcompositionsState = it
		}
	}

	internal fun requireSubcompositions(): SubcomposeLayoutNodeState {
		return checkNotNull(subcompositionsState) {
			"Mosaic node is not configured as a SubcomposeLayout"
		}
	}

	private fun detachFromTree() {
		subcompositionsState?.onRelease()
		for (child in mutableFoldedChildren.toList().asReversed()) {
			child.detachFromTree()
		}
		modifierNodeChain.detach()
		owner = null
	}

	override fun onReuse() {
		subcompositionsState?.onReuse()
		modifierNodeChain.reset()
		isDeactivated = false
		parent?.markMeasurementDirty()
	}

	override fun onDeactivate() {
		subcompositionsState?.onDeactivate()
		isDeactivated = true
		parent?.markMeasurementDirty()
	}

	override fun onRelease() {
		subcompositionsState?.onRelease()
		parent?.markMeasurementDirty()
	}

	fun setModifier(modifier: Modifier) {
		markMeasurementDirty()
		modifierNodeChain.updateFrom(modifier)
		var modifierElementIndex = modifierNodeChain.lastElementIndex
		val updatedRemeasurementModifiers = mutableListOf<RemeasurementModifier>()
		var focusScopeIndex = 0
		var focusRequesterIndex = 0
		var focusEventIndex = 0
		var pointerInputIndex = 0
		var pointerHoverIndex = 0
		var onPlacedIndex = 0
		topLayer = modifier.foldOut(bottomLayer) { element, nextLayer ->
			var nextLayer = nextLayer
			val elementIndex = modifierElementIndex--
			// The Modifier class can inherit from several key Modifier types
			// with different processing logic.
			if (element is LayoutModifier) {
				nextLayer = LayoutLayer(element, nextLayer)
			}
			if (element is DrawModifier) {
				nextLayer = DrawLayer(element, nextLayer)
			}
			if (element is ViewportClipModifier) {
				nextLayer = ViewportClipLayer(nextLayer)
			}
			if (element is KeyModifier) {
				nextLayer = KeyLayer(element, nextLayer)
			}
			if (element is ModifierNodeElement<*>) {
				nextLayer = ModifierNodeLayer(
					node = modifierNodeChain.requireNodeAt(elementIndex),
					next = nextLayer,
				)
			}
			if (element is FocusScopeModifier) {
				val handle = focusScopes.getOrNull(focusScopeIndex)
					?: FocusScopeHandle().also(focusScopes::add)
				focusScopeIndex++
				handle.enabled = element.enabled
				handle.trapsFocus = element.trapsFocus
				nextLayer = FocusScopeLayer(handle, nextLayer)
			}
			if (element is FocusRequesterModifier) {
				val node = focusRequesterNodes.getOrNull(focusRequesterIndex)
					?: FocusRequesterNode(element.focusRequester).also(focusRequesterNodes::add)
				focusRequesterIndex++
				node.requester = element.focusRequester
				nextLayer = FocusRequesterLayer(node, nextLayer)
			}
			if (element is FocusEventModifier) {
				val node = focusEventNodes.getOrNull(focusEventIndex)
					?: FocusEventNode(element.onFocusChanged).also(focusEventNodes::add)
				focusEventIndex++
				node.onFocusChanged = element.onFocusChanged
				nextLayer = FocusEventLayer(node, nextLayer)
			}
			if (element is FocusCursorModifier) {
				nextLayer = FocusCursorLayer(element, nextLayer)
			}
			if (element is PointerModifier) {
				val node = pointerInputNodes.getOrNull(pointerInputIndex)
					?: PointerInputNode(element).also(pointerInputNodes::add)
				pointerInputIndex++
				node.modifier = element
				nextLayer = PointerInputLayer(node, nextLayer)
			}
			if (element is PointerHoverModifier) {
				val node = pointerHoverNodes.getOrNull(pointerHoverIndex)
					?: PointerHoverNode(element).also(pointerHoverNodes::add)
				pointerHoverIndex++
				node.modifier = element
				nextLayer = PointerHoverLayer(node, nextLayer)
			}
			if (element is OnPlacedModifier) {
				val handle = onPlacedHandles.getOrNull(onPlacedIndex)
					?: OnPlacedHandle(element).also(onPlacedHandles::add)
				onPlacedIndex++
				handle.element = element
				nextLayer = OnPlacedLayer(handle, nextLayer)
			}
			if (element is RemeasurementModifier) {
				updatedRemeasurementModifiers += element
			}
			if (element is ParentDataModifier) {
				parentData = element.modifyParentData(parentData)
			}
			if (element is TestTagModifier) {
				testTag = element.tag
			}
			nextLayer
		}
		check(modifierElementIndex == -1)
		focusScopes.subList(focusScopeIndex, focusScopes.size).clear()
		focusRequesterNodes.subList(focusRequesterIndex, focusRequesterNodes.size).clear()
		focusEventNodes.subList(focusEventIndex, focusEventNodes.size).clear()
		pointerInputNodes.subList(pointerInputIndex, pointerInputNodes.size).clear()
		pointerHoverNodes.subList(pointerHoverIndex, pointerHoverNodes.size).clear()
		onPlacedHandles.subList(onPlacedIndex, onPlacedHandles.size).clear()
		remeasurementModifiers = updatedRemeasurementModifiers
		if (isAttached) notifyRemeasurementModifiers()
	}

	private fun notifyRemeasurementModifiers() {
		for (remeasurementModifier in remeasurementModifiers) {
			remeasurementModifier.onRemeasurementAvailable(remeasurement)
		}
	}

	override fun measure(constraints: Constraints): Placeable {
		if (!measurementDirty && lastConstraints == constraints) return topLayer
		lastConstraints = constraints
		measurementDirty = false
		return try {
			topLayer.apply { measure(constraints) }
		} catch (throwable: Throwable) {
			measurementDirty = true
			throw throwable
		}
	}

	internal fun forceRemeasureAndReplace(): Boolean {
		val constraints = lastConstraints ?: return false
		val previousWidth = width
		val previousHeight = height
		val previousX = x
		val previousY = y
		measurementDirty = true
		measure(constraints)
		if (width != previousWidth || height != previousHeight) {
			markMeasurementDirty()
			return false
		}
		topLayer.replaceAt(previousX, previousY)
		return true
	}

	val width: Int get() = topLayer.width
	val height: Int get() = topLayer.height
	val x: Int get() = topLayer.x
	val y: Int get() = topLayer.y

	fun measureAndPlace() {
		val placeable = measure(Constraints())
		topLayer.run { placeable.place(0, 0) }
	}

	/**
	 * Draw this node to a [TextSurface].
	 * A call to [measureAndPlace] must precede calls to this function.
	 */
	fun draw(): TextCanvas {
		val surface = TextSurface(width, height)
		topLayer.drawTo(surface)
		return surface
	}

	fun sendKeyEvent(keyEvent: KeyEvent): Boolean {
		return topLayer.sendKeyEvent(keyEvent)
	}

	fun sendPasteEvent(pasteEvent: PasteEvent): Boolean {
		return topLayer.sendPasteEvent(pasteEvent)
	}

	fun collectFocusTree(): FocusTree {
		val collector = FocusTreeCollector()
		topLayer.collectFocus(collector)
		return collector.build()
	}

	fun collectPointerTree(): PointerTree {
		val collector = PointerTreeCollector()
		topLayer.collectPointer(collector)
		return collector.build()
	}

	override fun minIntrinsicWidth(height: Int): Int {
		return topLayer.minIntrinsicWidth(height)
	}

	override fun maxIntrinsicWidth(height: Int): Int {
		return topLayer.maxIntrinsicWidth(height)
	}

	override fun minIntrinsicHeight(width: Int): Int {
		return topLayer.minIntrinsicHeight(width)
	}

	override fun maxIntrinsicHeight(width: Int): Int {
		return topLayer.maxIntrinsicHeight(width)
	}

	override fun toString() = debugPolicy.run { renderDebug() }
}

private class BottomLayer(
	private val node: MosaicNode,
) : MosaicNodeLayer() {
	override val next: MosaicNodeLayer? get() = null

	override fun doMeasure(constraints: Constraints): MeasureResult {
		return node.measurePolicy.run { measure(node.children, constraints) }
	}

	override fun drawTo(canvas: TextSurface) {
		for (child in node.children) {
			if (child.width != 0 && child.height != 0) {
				child.topLayer.drawTo(canvas)
			}
		}
	}

	override fun sendKeyEvent(keyEvent: KeyEvent): Boolean {
		for (child in node.children) {
			if (child.sendKeyEvent(keyEvent)) {
				return true
			}
		}
		return false
	}

	override fun sendPasteEvent(pasteEvent: PasteEvent): Boolean {
		for (child in node.children) {
			if (child.sendPasteEvent(pasteEvent)) {
				return true
			}
		}
		return false
	}

	override fun collectFocus(collector: FocusTreeCollector) {
		for (child in node.children) {
			child.topLayer.collectFocus(collector)
		}
	}

	override fun collectPointer(collector: PointerTreeCollector) {
		for (child in node.children) {
			child.topLayer.collectPointer(collector)
		}
	}

	override fun minIntrinsicWidth(height: Int): Int {
		return node.measurePolicy.run { minIntrinsicWidth(node.children, height) }
	}

	override fun maxIntrinsicWidth(height: Int): Int {
		return node.measurePolicy.run { maxIntrinsicWidth(node.children, height) }
	}

	override fun minIntrinsicHeight(width: Int): Int {
		return node.measurePolicy.run { minIntrinsicHeight(node.children, width) }
	}

	override fun maxIntrinsicHeight(width: Int): Int {
		return node.measurePolicy.run { maxIntrinsicHeight(node.children, width) }
	}
}

private class LayoutLayer(
	private val element: LayoutModifier,
	override val next: MosaicNodeLayer,
) : MosaicNodeLayer() {
	override fun doMeasure(constraints: Constraints): MeasureResult {
		return element.run { measure(next, constraints) }
	}

	override fun minIntrinsicWidth(height: Int): Int {
		return element.minIntrinsicWidth(next, height)
	}

	override fun maxIntrinsicWidth(height: Int): Int {
		return element.maxIntrinsicWidth(next, height)
	}

	override fun minIntrinsicHeight(width: Int): Int {
		return element.minIntrinsicHeight(next, width)
	}

	override fun maxIntrinsicHeight(width: Int): Int {
		return element.maxIntrinsicHeight(next, width)
	}
}

private class DrawLayer(
	private val element: DrawModifier,
	override val next: MosaicNodeLayer,
) : MosaicNodeLayer() {
	override fun drawTo(canvas: TextSurface) {
		val oldX = canvas.translationX
		val oldY = canvas.translationY
		canvas.translationX = x
		canvas.translationY = y
		val scope = object : TextCanvasDrawScope(canvas, width, height), ContentDrawScope {
			override fun drawContent() {
				next.drawTo(canvas)
			}
		}
		element.run { scope.draw() }
		canvas.translationX = oldX
		canvas.translationY = oldY
	}
}

private class ViewportClipLayer(
	override val next: MosaicNodeLayer,
) : MosaicNodeLayer() {
	override fun drawTo(canvas: TextSurface) {
		canvas.withClip(clipBounds()) {
			next.drawTo(canvas)
		}
	}

	override fun collectFocus(collector: FocusTreeCollector) {
		collector.visitClip(clipBounds()) {
			next.collectFocus(collector)
		}
	}

	override fun collectPointer(collector: PointerTreeCollector) {
		collector.visitClip(clipBounds()) {
			next.collectPointer(collector)
		}
	}
}

private class KeyLayer(
	private val element: KeyModifier,
	override val next: MosaicNodeLayer,
) : MosaicNodeLayer() {
	override fun sendKeyEvent(keyEvent: KeyEvent) = element.onPreKeyEvent(keyEvent) ||
		next.sendKeyEvent(keyEvent) ||
		element.onKeyEvent(keyEvent)

	override fun sendPasteEvent(pasteEvent: PasteEvent) = element.onPrePasteEvent(pasteEvent) ||
		next.sendPasteEvent(pasteEvent) ||
		element.onPasteEvent(pasteEvent)

	override fun collectFocus(collector: FocusTreeCollector) {
		collector.visitKeyModifier(element) {
			next.collectFocus(collector)
		}
	}
}

private class ModifierNodeLayer(
	private val node: Modifier.Node,
	override val next: MosaicNodeLayer,
) : MosaicNodeLayer() {
	override fun placeAt(x: Int, y: Int) {
		super.placeAt(x, y)
		val coordinates = layoutCoordinates()
		val existingCoordinates = node.layoutCoordinates
		if (existingCoordinates == null) {
			node.layoutCoordinates = coordinates
		} else {
			existingCoordinates.update(coordinates.position, coordinates.size)
		}
	}

	override fun collectFocus(collector: FocusTreeCollector) {
		val collectNode = {
			val focusTarget = node as? FocusTargetNode
			if (focusTarget == null) {
				next.collectFocus(collector)
			} else {
				collector.visitFocusTarget(focusTarget, focusBounds()) {
					next.collectFocus(collector)
				}
			}
		}
		val beyondBoundsProvider = node as? BeyondBoundsLayoutProviderModifierNode
		if (beyondBoundsProvider == null) {
			collectNode()
		} else {
			collector.visitBeyondBoundsLayout(beyondBoundsProvider.beyondBoundsLayout, collectNode)
		}
	}
}

private class FocusScopeLayer(
	private val handle: FocusScopeHandle,
	override val next: MosaicNodeLayer,
) : MosaicNodeLayer() {
	override fun collectFocus(collector: FocusTreeCollector) {
		collector.visitFocusScope(handle, focusBounds()) {
			next.collectFocus(collector)
		}
	}
}

private class FocusRequesterLayer(
	private val node: FocusRequesterNode,
	override val next: MosaicNodeLayer,
) : MosaicNodeLayer() {
	override fun collectFocus(collector: FocusTreeCollector) {
		collector.visitFocusRequester(node) {
			next.collectFocus(collector)
		}
	}
}

private class FocusEventLayer(
	private val node: FocusEventNode,
	override val next: MosaicNodeLayer,
) : MosaicNodeLayer() {
	override fun collectFocus(collector: FocusTreeCollector) {
		collector.visitFocusEvent(node) {
			next.collectFocus(collector)
		}
	}
}

private class FocusCursorLayer(
	private val element: FocusCursorModifier,
	override val next: MosaicNodeLayer,
) : MosaicNodeLayer() {
	override fun collectFocus(collector: FocusTreeCollector) {
		collector.visitFocusCursor(
			TerminalCursorPosition(
				row = y + element.row,
				column = x + element.column,
			),
		) {
			next.collectFocus(collector)
		}
	}
}

private fun MosaicNodeLayer.focusBounds(): FocusBounds = FocusBounds(
	position = IntOffset(x, y),
	size = IntSize(width, height),
)

private fun MosaicNodeLayer.clipBounds(): IntRect = IntRect(
	offset = IntOffset(x, y),
	size = IntSize(width, height),
)

private fun MosaicNodeLayer.layoutCoordinates(): LayoutCoordinates = LayoutCoordinates(
	position = IntOffset(x, y),
	size = IntSize(width, height),
)

private class PointerInputLayer(
	private val node: PointerInputNode,
	override val next: MosaicNodeLayer,
) : MosaicNodeLayer() {
	override fun collectPointer(collector: PointerTreeCollector) {
		collector.visitPointerInput(node, layoutCoordinates()) {
			next.collectPointer(collector)
		}
	}
}

private class PointerHoverLayer(
	private val node: PointerHoverNode,
	override val next: MosaicNodeLayer,
) : MosaicNodeLayer() {
	override fun collectPointer(collector: PointerTreeCollector) {
		collector.visitPointerHover(node, layoutCoordinates()) {
			next.collectPointer(collector)
		}
	}
}

private class OnPlacedHandle(
	var element: OnPlacedModifier,
) {
	/** `null` until this modifier occurrence has been placed for the first time. */
	var previousCoordinates: LayoutCoordinates? = null
}

private class OnPlacedLayer(
	private val handle: OnPlacedHandle,
	override val next: MosaicNodeLayer,
) : MosaicNodeLayer() {
	override fun placeAt(x: Int, y: Int) {
		super.placeAt(x, y)
		val coordinates = LayoutCoordinates(
			position = IntOffset(this.x, this.y),
			size = IntSize(width, height),
		)
		if (coordinates != handle.previousCoordinates) {
			handle.previousCoordinates = coordinates
			handle.element.onPlaced(coordinates)
		}
	}
}
