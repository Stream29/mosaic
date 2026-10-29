package com.jakewharton.mosaic.focus

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.jakewharton.mosaic.TerminalCursorPosition
import com.jakewharton.mosaic.layout.BeyondBoundsLayout
import com.jakewharton.mosaic.layout.BeyondBoundsLayoutProviderModifierNode
import com.jakewharton.mosaic.layout.BringIntoViewModifierNode
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.LayoutCoordinates
import com.jakewharton.mosaic.layout.Remeasurement
import com.jakewharton.mosaic.layout.RemeasurementModifier
import com.jakewharton.mosaic.layout.clipToBounds
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.node.ModifierNodeElement
import com.jakewharton.mosaic.node.requireLayoutCoordinates
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.KeyboardEvent.Companion.ModifierShift
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Layout
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.Constraints
import com.jakewharton.mosaic.ui.unit.IntOffset
import com.jakewharton.mosaic.ui.unit.IntRect
import com.jakewharton.mosaic.ui.unit.IntSize
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class SequentialFocusSearchTest {
	@Test fun tabVisitsNearerHiddenTargetBeforeFartherVisibleTarget() = eagerTraversal(forward = true)

	@Test fun shiftTabVisitsNearerHiddenTargetBeforeFartherVisibleTarget() = eagerTraversal(forward = false)

	// Composition order is 0, 1, 2, outside; placement order is 0, 2, [clipped 1].
	// This exercises real clipping, bring-into-view remeasurement, provider exit and root wrapping.
	private fun eagerTraversal(forward: Boolean) = runTest {
		val scroll = EagerFocusScroll()
		var focused by mutableStateOf("")
		runMosaicTest {
			setContentAndSnapshot {
				Row {
					Layout(
						modifier = Modifier.then(EagerFocusScrollElement(scroll)).clipToBounds(),
						content = {
							repeat(3) { index ->
								Text(
									"$index",
									Modifier
										.onFocusChanged { if (it == FocusState.Active) focused = "$index" }
										.focusable(autoFocus = index == if (forward) 0 else 2),
								)
							}
						},
					) { measurables, _ ->
						val placeables = measurables.map { it.measure(Constraints()) }
						layout(2, 1) {
							val positions = listOf(0, 2, 1)
							placeables.forEachIndexed { index, placeable ->
								placeable.place(positions[index] - scroll.offset, 0)
							}
						}
					}
					Text(
						"outside",
						Modifier
							.onFocusChanged { if (it == FocusState.Active) focused = "outside" }
							.focusable(),
					)
				}
			}
			assertThat(focused).isEqualTo(if (forward) "0" else "2")
			val expected = if (forward) {
				listOf("1", "2", "outside", "1", "2")
			} else {
				listOf("1", "0", "outside", "2", "1")
			}
			for (label in expected) {
				sendKeyEvent(KeyboardEvent(9, modifiers = if (forward) 0 else ModifierShift))
				awaitSnapshot()
				assertThat(focused).isEqualTo(label)
				if (label == "1") assertThat(scroll.offset).isEqualTo(1)
				if (label == "0") assertThat(scroll.offset).isEqualTo(0)
			}
		}
	}

	@Test fun beyondBoundsTabComparesNewVisibleAndHiddenCandidatesByOrder() {
		for (hiddenIsNear in listOf(true, false)) {
			beyondBoundsComparison(forward = true, hiddenIsNear = hiddenIsNear)
		}
	}

	@Test fun beyondBoundsShiftTabComparesNewVisibleAndHiddenCandidatesByOrder() {
		for (hiddenIsNear in listOf(true, false)) {
			beyondBoundsComparison(forward = false, hiddenIsNear = hiddenIsNear)
		}
	}

	private fun beyondBoundsComparison(forward: Boolean, hiddenIsNear: Boolean) {
		val fixture = FocusProjection()
		val provider = ProjectionProvider()
		val source = target(if (forward) 0 else 3, provider, autoFocus = true)
		val near = target(if (forward) 1 else 2, provider)
		val far = target(if (forward) 2 else 1, provider)
		val outside = target(if (forward) 4 else 0)
		fixture.reconcile(listOf(source, outside))
		provider.onLayout = {
			fixture.reconcile(
				visible = listOf(source, if (hiddenIsNear) far else near, outside),
				hidden = listOf(if (hiddenIsNear) near else far),
			)
		}

		fixture.tab(forward)

		fixture.assertFocused(near)
		assertThat(provider.directions).isEqualTo(
			listOf(if (forward) BeyondBoundsLayout.LayoutDirection.After else BeyondBoundsLayout.LayoutDirection.Before),
		)
	}

	@Test fun aCloserVisibleTargetStillWinsOverAHiddenTarget() {
		for (forward in listOf(true, false)) {
			val fixture = FocusProjection()
			val provider = ProjectionProvider()
			val source = target(if (forward) 0 else 3, provider, autoFocus = true)
			val near = target(if (forward) 1 else 2, provider)
			val far = target(if (forward) 2 else 1, provider)
			fixture.reconcile(listOf(source, near), hidden = listOf(far))
			fixture.tab(forward)
			fixture.assertFocused(near)
			assertThat(provider.directions).isEqualTo(emptyList())
		}
	}

	@Test fun hiddenCandidatesAreVisitedBeforeWrappingWithinTheirProvider() {
		for (forward in listOf(true, false)) {
			val fixture = FocusProjection()
			val provider = ProjectionProvider()
			val source = target(if (forward) 0 else 2, provider, autoFocus = true)
			val hidden = target(1, provider)
			fixture.reconcile(listOf(source), hidden = listOf(hidden))
			fixture.tab(forward)
			fixture.assertFocused(hidden)
			assertThat(provider.directions).isEqualTo(emptyList())
		}
	}

	@Test fun exhaustedProviderRetainsVisibleWrapInBothDirections() {
		for (forward in listOf(true, false)) {
			val fixture = FocusProjection()
			val provider = ProjectionProvider()
			val source = target(if (forward) 1 else 0, provider, autoFocus = true)
			val wrapped = target(if (forward) 0 else 1, provider)
			fixture.reconcile(listOf(source, wrapped))
			fixture.tab(forward)
			fixture.assertFocused(wrapped)
			assertThat(provider.directions.size).isEqualTo(1)
		}
	}

	@Test fun disabledAndWrongDirectionHiddenTargetsAreSkippedBeforeExit() {
		for (forward in listOf(true, false)) {
			val fixture = FocusProjection()
			val provider = ProjectionProvider()
			val source = target(2, provider, autoFocus = true)
			val behind = target(if (forward) 1 else 3, provider)
			val disabled = target(if (forward) 3 else 1, provider, enabled = false)
			val outside = target(if (forward) 4 else 0)
			fixture.reconcile(listOf(source, outside), hidden = listOf(behind, disabled))
			fixture.tab(forward)
			fixture.assertFocused(outside)
			assertThat(provider.directions.size).isEqualTo(1)
		}
	}

	@Test fun rootWrapIncludesClippedEndpointInTheCurrentProvider() {
		for (forward in listOf(true, false)) {
			val fixture = FocusProjection()
			val provider = ProjectionProvider()
			val source = target(if (forward) 2 else 0, provider, autoFocus = true)
			val visible = target(1, provider)
			val endpoint = target(if (forward) 0 else 2, provider)
			fixture.reconcile(listOf(source, visible), hidden = listOf(endpoint))
			fixture.tab(forward)
			fixture.assertFocused(endpoint)
			assertThat(provider.directions.size).isEqualTo(1)
		}
	}

	@Test fun rootWrapKeepsAnEarlierVisibleOutsideDestination() {
		for (forward in listOf(true, false)) {
			val fixture = FocusProjection()
			val provider = ProjectionProvider()
			val source = target(if (forward) 2 else 0, provider, autoFocus = true)
			val outside = target(if (forward) 0 else 2)
			val hidden = target(1, provider)
			fixture.reconcile(listOf(source, outside), hidden = listOf(hidden))
			fixture.tab(forward)
			fixture.assertFocused(outside)
		}
	}

	@Test fun rootWrapDoesNotMergeHiddenTargetsFromAnotherProvider() {
		for (forward in listOf(true, false)) {
			val fixture = FocusProjection()
			val provider = ProjectionProvider()
			val source = target(if (forward) 2 else 0, provider, autoFocus = true)
			val visible = target(1, provider)
			val unrelated = target(if (forward) 0 else 2, ProjectionProvider())
			fixture.reconcile(listOf(source, visible), hidden = listOf(unrelated))
			fixture.tab(forward)
			fixture.assertFocused(visible)
		}
	}

	@Test fun rootWrapIgnoresDisabledHiddenEndpoints() {
		for (forward in listOf(true, false)) {
			val fixture = FocusProjection()
			val provider = ProjectionProvider()
			val source = target(if (forward) 2 else 0, provider, autoFocus = true)
			val visible = target(1, provider)
			val disabled = target(if (forward) 0 else 2, provider, enabled = false)
			fixture.reconcile(listOf(source, visible), hidden = listOf(disabled))
			fixture.tab(forward)
			fixture.assertFocused(visible)
		}
	}

	@Test fun hiddenTargetsInAnUnrelatedProviderAreNotGloballyMerged() {
		for (forward in listOf(true, false)) {
			val fixture = FocusProjection()
			val sourceProvider = ProjectionProvider()
			val otherProvider = ProjectionProvider()
			val source = target(if (forward) 0 else 3, sourceProvider, autoFocus = true)
			val hidden = target(if (forward) 1 else 2, otherProvider)
			val visible = target(if (forward) 2 else 1, otherProvider)
			fixture.reconcile(listOf(source, visible), hidden = listOf(hidden))
			fixture.tab(forward)
			fixture.assertFocused(visible)
			assertThat(sourceProvider.directions.size).isEqualTo(1)
			assertThat(otherProvider.directions).isEqualTo(emptyList())
		}
	}

	@Test fun innerProviderIsSearchedBeforeAnOuterVisibleDestination() {
		for (forward in listOf(true, false)) {
			val fixture = FocusProjection()
			val outer = ProjectionProvider()
			val inner = ProjectionProvider()
			val source = target(if (forward) 0 else 3, outer, inner, autoFocus = true)
			val near = target(if (forward) 1 else 2, outer, inner)
			val far = target(if (forward) 2 else 1, outer, inner)
			val outsideInner = target(if (forward) 4 else 0, outer)
			fixture.reconcile(listOf(source, outsideInner))
			inner.onLayout = {
				fixture.reconcile(listOf(source, far, outsideInner), hidden = listOf(near))
			}
			fixture.tab(forward)
			fixture.assertFocused(near)
			assertThat(inner.directions.size).isEqualTo(1)
			assertThat(outer.directions).isEqualTo(emptyList())
		}
	}

	@Test fun outerHiddenTargetDoesNotBypassInnerProviderSearch() {
		val fixture = FocusProjection()
		val outer = ProjectionProvider()
		val inner = ProjectionProvider()
		val source = target(0, outer, inner, autoFocus = true)
		val outerHidden = target(1, outer)
		val innerNew = target(2, outer, inner)
		val outside = target(3, outer)
		fixture.reconcile(listOf(source, outside), hidden = listOf(outerHidden))
		inner.onLayout = {
			fixture.reconcile(listOf(source, innerNew, outside), hidden = listOf(outerHidden))
		}
		fixture.tab()
		fixture.assertFocused(innerNew)
		assertThat(inner.directions.size).isEqualTo(1)
		assertThat(outer.directions).isEqualTo(emptyList())
	}

	@Test fun hiddenCandidatesCannotEscapeAnActiveTrap() {
		for (forward in listOf(true, false)) {
			val fixture = FocusProjection()
			val provider = ProjectionProvider()
			val scope = FocusScopeEntry(
				handle = FocusScopeHandle().apply { trapsFocus = true },
				bounds = FocusBounds(IntOffset.Zero, IntSize(10, 1)),
				parent = null,
				parentFocusBoundary = null,
				order = 0,
				keyModifiers = emptyList(),
			)
			val source = target(if (forward) 1 else 4, provider, autoFocus = true).copy(scope = scope)
			val hiddenOutside = target(if (forward) 2 else 3, provider)
			val inside = target(if (forward) 3 else 2, provider).copy(scope = scope)
			fixture.reconcile(listOf(source, inside), hidden = listOf(hiddenOutside), scopes = listOf(scope))
			fixture.tab(forward)
			fixture.assertFocused(inside)
			fixture.tab(forward)
			fixture.assertFocused(source)
		}
	}

	@Test fun directionalAndPointerSearchRemainVisibleOnly() {
		val fixture = FocusProjection()
		val provider = ProjectionProvider()
		val source = target(0, provider, autoFocus = true)
		val hidden = target(1, provider)
		val visible = target(2, provider)
		fixture.reconcile(listOf(source, visible), hidden = listOf(hidden))

		assertThat(fixture.owner.requestFocusAt(IntOffset(1, 0))).isEqualTo(false)
		fixture.assertFocused(source)
		assertThat(fixture.owner.dispatchKeyEvent(KeyEvent("ArrowRight"))).isTrue()
		fixture.assertFocused(visible)
		assertThat(fixture.owner.requestFocusAt(IntOffset.Zero)).isTrue()
		fixture.assertFocused(source)
		assertThat(provider.directions).isEqualTo(emptyList())
	}

	@Test fun beyondBoundsDirectionalSearchKeepsVisiblePriority() {
		val fixture = FocusProjection()
		val provider = ProjectionProvider()
		val source = target(0, provider, autoFocus = true)
		val hidden = target(1, provider)
		val visible = target(2, provider)
		val outside = target(3)
		fixture.reconcile(listOf(source, outside))
		provider.onLayout = {
			fixture.reconcile(listOf(source, visible, outside), hidden = listOf(hidden))
		}
		assertThat(fixture.owner.dispatchKeyEvent(KeyEvent("ArrowRight"))).isTrue()
		fixture.assertFocused(visible)
		assertThat(provider.directions).isEqualTo(listOf(BeyondBoundsLayout.LayoutDirection.Right))
	}
}

private class FocusProjection {
	private var cursor: TerminalCursorPosition? = null
	val owner = FocusOwner { cursor = it }

	fun reconcile(
		visible: List<FocusTargetEntry>,
		hidden: List<FocusTargetEntry> = emptyList(),
		scopes: List<FocusScopeEntry> = emptyList(),
	) {
		owner.reconcile(
			FocusTree(
				targets = visible.sortedBy(FocusTargetEntry::order),
				hiddenTargets = hidden.sortedBy(FocusTargetEntry::order),
				scopes = scopes,
				requesters = emptyList(),
				events = emptyList(),
			),
		)
	}

	fun tab(forward: Boolean = true) {
		assertThat(owner.dispatchKeyEvent(KeyEvent("Tab", shift = !forward))).isTrue()
	}

	fun assertFocused(target: FocusTargetEntry) {
		assertThat(cursor).isEqualTo(target.bounds.topLeftCursor())
	}
}

private fun target(
	order: Int,
	vararg providers: BeyondBoundsLayout,
	autoFocus: Boolean = false,
	enabled: Boolean = true,
): FocusTargetEntry {
	val node = FocusTargetNode(enabled, autoFocus)
	return FocusTargetEntry(
		node = node,
		handle = node.handle,
		bounds = FocusBounds(IntOffset(order, 0), IntSize(1, 1)),
		scope = null,
		parentFocusBoundary = null,
		order = order,
		keyModifiers = emptyList(),
		cursorPosition = null,
		beyondBoundsLayouts = providers.toList(),
	)
}

// Projection-only tests deliberately control the tree exposed by a beyond-bounds callback.
private class ProjectionProvider : BeyondBoundsLayout {
	val directions = mutableListOf<BeyondBoundsLayout.LayoutDirection>()
	var onLayout: () -> Unit = {}

	override fun <T> layout(
		direction: BeyondBoundsLayout.LayoutDirection,
		block: BeyondBoundsLayout.BeyondBoundsScope.() -> T?,
	): T? {
		directions += direction
		onLayout()
		return block(
			object : BeyondBoundsLayout.BeyondBoundsScope {
				override val hasMoreContent: Boolean get() = false
			},
		)
	}
}

private class EagerFocusScroll : BeyondBoundsLayout {
	var offset = 0
	private lateinit var remeasurement: Remeasurement

	fun onRemeasurementAvailable(value: Remeasurement) {
		remeasurement = value
	}

	override fun <T> layout(
		direction: BeyondBoundsLayout.LayoutDirection,
		block: BeyondBoundsLayout.BeyondBoundsScope.() -> T?,
	): T? = null // All eager children are already placed.

	fun reveal(bounds: IntRect) {
		val delta = when {
			bounds.left < 0 -> bounds.left
			bounds.right > 2 -> bounds.right - 2
			else -> return
		}
		offset += delta
		remeasurement.forceRemeasure()
	}
}

private data class EagerFocusScrollElement(
	val scroll: EagerFocusScroll,
) : ModifierNodeElement<EagerFocusScrollNode>(),
	RemeasurementModifier {
	override fun create(): EagerFocusScrollNode = EagerFocusScrollNode(scroll)

	override fun update(node: EagerFocusScrollNode) {
		node.scroll = scroll
	}

	override fun onRemeasurementAvailable(remeasurement: Remeasurement) {
		scroll.onRemeasurementAvailable(remeasurement)
	}
}

private class EagerFocusScrollNode(
	var scroll: EagerFocusScroll,
) : Modifier.Node(),
	BeyondBoundsLayoutProviderModifierNode,
	BringIntoViewModifierNode {
	override val beyondBoundsLayout: BeyondBoundsLayout get() = scroll

	override suspend fun bringIntoView(
		childCoordinates: LayoutCoordinates,
		boundsProvider: () -> IntRect?,
	) {
		val bounds = boundsProvider() ?: return
		scroll.reveal(bounds.translate(childCoordinates.position - requireLayoutCoordinates().position))
	}
}
