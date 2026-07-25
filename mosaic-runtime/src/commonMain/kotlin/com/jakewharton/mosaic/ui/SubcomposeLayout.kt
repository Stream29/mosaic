@file:JvmName("SubcomposeLayout")

package com.jakewharton.mosaic.ui

import androidx.compose.runtime.Applier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNodeLifecycleCallback
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.ReusableComposeNode
import androidx.compose.runtime.ReusableComposition
import androidx.compose.runtime.Stable
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import com.jakewharton.mosaic.MosaicNodeApplier
import com.jakewharton.mosaic.layout.DebugPolicy
import com.jakewharton.mosaic.layout.IntrinsicMeasurable
import com.jakewharton.mosaic.layout.Measurable
import com.jakewharton.mosaic.layout.MeasurePolicy
import com.jakewharton.mosaic.layout.MeasureResult
import com.jakewharton.mosaic.layout.MeasureScope
import com.jakewharton.mosaic.layout.MosaicNode
import com.jakewharton.mosaic.layout.Placeable
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.modifier.materialize
import com.jakewharton.mosaic.ui.unit.Constraints
import kotlin.jvm.JvmField
import kotlin.jvm.JvmName

/**
 * A layout which composes keyed child slots while measuring.
 *
 * This is useful when the incoming [Constraints] determine which children should exist, such as a
 * lazy list. Intrinsic measurement is unsupported because answering it would require eagerly
 * composing an otherwise lazy data set.
 */
@Composable
public fun SubcomposeLayout(
	modifier: Modifier = Modifier,
	measurePolicy: SubcomposeMeasureScope.(Constraints) -> MeasureResult,
) {
	SubcomposeLayout(
		state = remember { SubcomposeLayoutState() },
		modifier = modifier,
		measurePolicy = measurePolicy,
	)
}

/**
 * A layout which composes keyed child slots while measuring.
 *
 * @param state Configures how this layout retains inactive slots for reuse.
 * @param modifier Modifier applied to the resulting layout node.
 * @param measurePolicy Composes, measures, and places the slots needed for the current pass.
 */
@Composable
public fun SubcomposeLayout(
	state: SubcomposeLayoutState,
	modifier: Modifier = Modifier,
	measurePolicy: SubcomposeMeasureScope.(Constraints) -> MeasureResult,
) {
	val compositionContext = rememberCompositionContext()
	val materializedModifier = currentComposer.materialize(modifier)
	ReusableComposeNode<MosaicNode, Applier<Any>>(
		factory = NodeFactory,
		update = {
			set(state, SetSubcomposeLayoutState)
			set(compositionContext, SetSubcomposeCompositionContext)
			set(measurePolicy, SetSubcomposeMeasurePolicy)
			set(materializedModifier, SetModifier)
			set(SubcomposeDebugPolicy, SetDebugPolicy)
		},
	)
}

/** Scope used by [SubcomposeLayout] to create children during measurement. */
public interface SubcomposeMeasureScope : MeasureScope {
	/**
	 * Composes the content identified by [slotId] and returns its top-level measurables.
	 *
	 * [slotId] must be unique within one measure pass. Reusing the same ID on later passes preserves
	 * its composition while the slot remains active or retained.
	 *
	 * @param contentType `null` selects the default reuse class. A retained node may be reused for a
	 * different slot only when both content types are equal; remembered state is reset in that case.
	 */
	public fun subcompose(
		slotId: Any,
		contentType: Any? = null,
		content:
		@Composable @MosaicComposable
		() -> Unit,
	): List<Measurable>
}

/**
 * Configuration for [SubcomposeLayout].
 *
 * Slot compositions belong to the layout node so they follow node reuse and disposal correctly.
 *
 * @param maxSlotsToRetainForReuse Maximum inactive slots kept for later reuse. Exact ID reuse
 * preserves remembered state. Reuse for another ID is limited to an equal `contentType` and clears
 * remembered state and effects before composing the new content.
 */
@Stable
public class SubcomposeLayoutState(
	internal val maxSlotsToRetainForReuse: Int = 0,
) {
	init {
		require(maxSlotsToRetainForReuse >= 0) {
			"maxSlotsToRetainForReuse must be non-negative"
		}
	}
}

/**
 * Subcomposition data owned one-to-one by a [MosaicNode].
 *
 * Keeping this state on the node preserves reusable slots when a new [SubcomposeLayoutState] is
 * supplied or when Compose reuses the node itself.
 *
 * @property compositionContext `null` means Compose has not yet supplied the parent composition
 * context. Slot creation is invalid until the context is available.
 */
internal class SubcomposeLayoutNodeState(
	private val root: MosaicNode,
	private var maxSlotsToRetainForReuse: Int,
) : ComposeNodeLifecycleCallback {
	private var compositionContext: CompositionContext? = null
	private val slots = mutableListOf<Slot>()
	private val slotsByActiveId = mutableMapOf<Any, Slot>()
	private val usedSlotIds = mutableSetOf<Any>()
	private val scope = Scope()
	private var currentIndex = 0
	private var isMeasuring = false
	private var ignoreRelayoutRequests = false

	fun updateMaxSlotsToRetainForReuse(value: Int) {
		if (maxSlotsToRetainForReuse == value) return
		check(!isMeasuring) {
			"SubcomposeLayout retention cannot change during measurement"
		}
		maxSlotsToRetainForReuse = value
		trimReusableSlots()
	}

	fun setCompositionContext(value: CompositionContext) {
		if (compositionContext != null && compositionContext !== value) {
			disposeSlots()
		}
		compositionContext = value
	}

	fun createMeasurePolicy(
		measurePolicy: SubcomposeMeasureScope.(Constraints) -> MeasureResult,
	): MeasurePolicy = object : MeasurePolicy {
		override fun MeasureScope.measure(
			measurables: List<Measurable>,
			constraints: Constraints,
		): MeasureResult {
			val foldedChildren = root.foldedChildren
			check(foldedChildren.size == slots.size && foldedChildren.all { child -> child.isVirtual }) {
				"SubcomposeLayout children do not match its slots"
			}
			check(!isMeasuring) { "SubcomposeLayout cannot be measured recursively" }
			currentIndex = 0
			usedSlotIds.clear()
			scope.measureScope = this
			isMeasuring = true
			return try {
				scope.measurePolicy(constraints)
			} finally {
				isMeasuring = false
				finishMeasure()
			}
		}

		override fun minIntrinsicWidth(
			measurables: List<IntrinsicMeasurable>,
			height: Int,
		): Int = unsupportedIntrinsicMeasurement()

		override fun maxIntrinsicWidth(
			measurables: List<IntrinsicMeasurable>,
			height: Int,
		): Int = unsupportedIntrinsicMeasurement()

		override fun minIntrinsicHeight(
			measurables: List<IntrinsicMeasurable>,
			width: Int,
		): Int = unsupportedIntrinsicMeasurement()

		override fun maxIntrinsicHeight(
			measurables: List<IntrinsicMeasurable>,
			width: Int,
		): Int = unsupportedIntrinsicMeasurement()
	}

	override fun onReuse() {
		markSlotsReusable(deactivate = false)
	}

	override fun onDeactivate() {
		markSlotsReusable(deactivate = true)
	}

	override fun onRelease() {
		disposeSlots()
	}

	private fun disposeSlots() {
		ignoreRelayoutRequests {
			for (slot in slots.asReversed()) {
				slot.composition.dispose()
			}
		}
		root.removeAll()
		slots.clear()
		slotsByActiveId.clear()
		usedSlotIds.clear()
		currentIndex = 0
	}

	private fun markSlotsReusable(deactivate: Boolean) {
		check(!isMeasuring) {
			"SubcomposeLayout lifecycle cannot change during measurement"
		}
		ignoreRelayoutRequests {
			for (slot in slots) {
				when (slot.phase) {
					SlotPhase.Active -> {
						if (deactivate) slot.composition.deactivate()
						slot.slotId = ReusedSlotId
						slot.phase =
							if (deactivate) SlotPhase.Deactivated else SlotPhase.Reusable
					}

					SlotPhase.Reusable -> {
						if (deactivate) {
							slot.composition.deactivate()
							slot.phase = SlotPhase.Deactivated
						}
					}

					SlotPhase.Deactivated -> Unit

					SlotPhase.Uncomposed -> error("Uncomposed slot escaped its measure pass")
				}
				slot.root.deactivateVirtualNode()
			}
		}
		slotsByActiveId.clear()
		usedSlotIds.clear()
		currentIndex = 0
	}

	/**
	 * @param contentType `null` selects the default reuse class.
	 */
	private fun subcompose(
		slotId: Any,
		contentType: Any?,
		content:
		@Composable @MosaicComposable
		() -> Unit,
	): List<Measurable> {
		check(isMeasuring) { "subcompose can only be called from the measure policy" }
		require(usedSlotIds.add(slotId)) {
			"Slot ID $slotId was already used in this measure pass"
		}

		val normalizedContentType = contentType ?: DefaultContentType
		var resetComposition = false
		val slot = slotsByActiveId[slotId]
			?: takeReusableSlot(slotId, normalizedContentType)?.also { reusable ->
				resetComposition = reusable.slotId != slotId
				reusable.slotId = slotId
				slotsByActiveId[slotId] = reusable
			}
			?: createSlot(slotId, normalizedContentType, content)

		slot.contentType = normalizedContentType
		val slotIndex = slots.indexOf(slot)
		check(slotIndex >= currentIndex) {
			"Slot ID $slotId was already used in this measure pass"
		}
		if (slotIndex != currentIndex) {
			moveSlot(slotIndex, currentIndex)
		}

		val requiresInitialComposition = slot.phase == SlotPhase.Uncomposed
		if (
			requiresInitialComposition ||
			resetComposition ||
			slot.content !== content ||
			slot.composition.hasInvalidations
		) {
			slot.content = content
			try {
				ignoreRelayoutRequests {
					if (resetComposition) {
						slot.composition.setContentWithReuse { content() }
					} else {
						slot.composition.setContent { content() }
					}
				}
			} catch (throwable: Throwable) {
				if (requiresInitialComposition) {
					disposeSlotAt(currentIndex)
				}
				throw throwable
			}
		}
		slot.phase = SlotPhase.Active
		slot.root.activateVirtualNode()
		currentIndex++
		return slot.root.children
	}

	/**
	 * @return A matching inactive slot, or `null` when the caller must create one.
	 */
	private fun takeReusableSlot(slotId: Any, contentType: Any): Slot? {
		val exact = slots.firstOrNull { slot ->
			slot.phase.isReusable && slot.slotId == slotId
		}
		if (exact != null) return exact
		return slots.lastOrNull { slot ->
			slot.phase.isReusable && slot.contentType == contentType
		}
	}

	private fun createSlot(
		slotId: Any,
		contentType: Any,
		content:
		@Composable @MosaicComposable
		() -> Unit,
	): Slot {
		val compositionContext = checkNotNull(compositionContext) {
			"SubcomposeLayout has no parent composition context"
		}
		val slotRoot = MosaicNode(
			measurePolicy = VirtualNodeMeasurePolicy,
			debugPolicy = VirtualNodeDebugPolicy,
			isStatic = false,
			isVirtual = true,
		)
		root.insertAt(currentIndex, slotRoot)
		val composition = ReusableComposition(
			applier = MosaicNodeApplier(
				root = slotRoot,
				onChanges = ::onSlotCompositionChanged,
			),
			parent = compositionContext,
		)
		return Slot(slotId, contentType, slotRoot, composition, content).also { slot ->
			slots.add(currentIndex, slot)
			slotsByActiveId[slotId] = slot
		}
	}

	private fun moveSlot(from: Int, to: Int) {
		check(from > to)
		root.move(from, to, 1)
		val slot = slots.removeAt(from)
		slots.add(to, slot)
	}

	private fun finishMeasure() {
		for (index in slots.indices) {
			val slot = slots[index]
			if (index < currentIndex) {
				slot.phase = SlotPhase.Active
				slot.root.activateVirtualNode()
			} else {
				if (slot.phase != SlotPhase.Deactivated) {
					slot.phase = SlotPhase.Reusable
				}
				slot.root.deactivateVirtualNode()
				removeActiveSlot(slot)
			}
		}
		trimReusableSlots()
	}

	private fun trimReusableSlots() {
		var reusableCount = slots.count { slot -> slot.phase.isReusable }
		var index = slots.lastIndex
		while (reusableCount > maxSlotsToRetainForReuse) {
			if (slots[index].phase.isReusable) {
				disposeSlotAt(index)
				reusableCount--
			}
			index--
		}
	}

	private fun disposeSlotAt(index: Int) {
		val slot = slots.removeAt(index)
		removeActiveSlot(slot)
		ignoreRelayoutRequests {
			slot.composition.dispose()
		}
		root.removeAt(index, 1)
	}

	private fun removeActiveSlot(slot: Slot) {
		if (slotsByActiveId[slot.slotId] === slot) {
			slotsByActiveId.remove(slot.slotId)
		}
	}

	private fun onSlotCompositionChanged() {
		if (!ignoreRelayoutRequests) root.requestRelayout()
	}

	private inline fun <T> ignoreRelayoutRequests(block: () -> T): T {
		val wasIgnoring = ignoreRelayoutRequests
		ignoreRelayoutRequests = true
		return try {
			block()
		} finally {
			ignoreRelayoutRequests = wasIgnoring
		}
	}

	private inner class Scope : SubcomposeMeasureScope {
		lateinit var measureScope: MeasureScope

		override fun layout(
			width: Int,
			height: Int,
			placementBlock: Placeable.PlacementScope.() -> Unit,
		): MeasureResult = measureScope.layout(width, height, placementBlock)

		override fun subcompose(
			slotId: Any,
			contentType: Any?,
			content:
			@Composable @MosaicComposable
			() -> Unit,
		): List<Measurable> = this@SubcomposeLayoutNodeState.subcompose(
			slotId,
			contentType,
			content,
		)
	}

	private class Slot(
		var slotId: Any,
		var contentType: Any,
		val root: MosaicNode,
		val composition: ReusableComposition,
		var content:
		@Composable @MosaicComposable
		() -> Unit,
	) {
		var phase = SlotPhase.Uncomposed
	}

	private enum class SlotPhase {
		Uncomposed,
		Active,
		Reusable,
		Deactivated,
		;

		val isReusable: Boolean
			get() = this == Reusable || this == Deactivated
	}

	private companion object {
		private object DefaultContentType
		private object ReusedSlotId
	}
}

private fun unsupportedIntrinsicMeasurement(): Nothing = throw UnsupportedOperationException(
	"Intrinsic measurement is not supported for SubcomposeLayout",
)

private val VirtualNodeMeasurePolicy = MeasurePolicy { _, _ ->
	error("Virtual subcomposition nodes are not measured directly")
}

private val VirtualNodeDebugPolicy = DebugPolicy {
	children.joinToString(separator = "\n")
}

private val SubcomposeDebugPolicy = DebugPolicy {
	buildString {
		append("SubcomposeLayout() x=$x y=$y w=$width h=$height")
		children.joinTo(this, separator = "") { child ->
			"\n" + child.toString().prependIndent("  ")
		}
	}
}

@JvmField
internal val SetSubcomposeLayoutState: MosaicNode.(SubcomposeLayoutState) -> Unit = { state ->
	getOrCreateSubcompositions(state.maxSlotsToRetainForReuse)
}

@JvmField
internal val SetSubcomposeCompositionContext: MosaicNode.(CompositionContext) -> Unit = { context ->
	requireSubcompositions().setCompositionContext(context)
}

@JvmField
internal val SetSubcomposeMeasurePolicy:
	MosaicNode.(SubcomposeMeasureScope.(Constraints) -> MeasureResult) -> Unit = { policy ->
		measurePolicy = requireSubcompositions().createMeasurePolicy(policy)
	}
