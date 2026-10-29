package com.jakewharton.mosaic.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.jakewharton.mosaic.layout.BeyondBoundsLayout
import com.jakewharton.mosaic.layout.BeyondBoundsLayoutProviderModifierNode
import com.jakewharton.mosaic.layout.BringIntoViewModifierNode
import com.jakewharton.mosaic.layout.LayoutCoordinates
import com.jakewharton.mosaic.layout.Remeasurement
import com.jakewharton.mosaic.layout.RemeasurementModifier
import com.jakewharton.mosaic.layout.bringIntoView
import com.jakewharton.mosaic.layout.clipToBounds
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.layout.onPlaced
import com.jakewharton.mosaic.layout.size
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.node.ModifierNodeElement
import com.jakewharton.mosaic.node.requireLayoutCoordinates
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.KeyboardEvent.Companion.ModifierShift
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Layout
import com.jakewharton.mosaic.ui.SubcomposeLayout
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.Constraints
import com.jakewharton.mosaic.ui.unit.IntOffset
import com.jakewharton.mosaic.ui.unit.IntRect
import kotlin.test.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class SequentialFocusMountTest {
	@Test fun settledSubcomposedEditorTabsToClippedLastStatus() = runTest {
		for (nestedProvider in listOf(false, true)) {
			val fixture = MountFocusFixture()
			runMosaicTest {
				try {
					assertThat(setContentAndSnapshot { fixture.Content(nestedProvider) }).isEqualTo("fourth\n[First]")
					fixture.releaseMount.complete(Unit)
					awaitCondition { fixture.mountCompleted.isCompleted }
					assertThat(fixture.focused).isEqualTo("editor")

					sendKeyEvent(KeyboardEvent(9))
					awaitCondition { fixture.focused == "First" }
					sendKeyEvent(KeyboardEvent(13))
					awaitCondition { fixture.clicks == listOf("First") }
					sendKeyEvent(KeyboardEvent(9))
					awaitCondition { fixture.focused == "Last" }
					assertThat(draw().render(AnsiLevel.NONE, false)).isEqualTo("middle\n[Last]")
					assertThat(fixture.scroll.offset).isEqualTo(2)
					assertThat(fixture.lastPosition).isEqualTo(IntOffset(0, 1))
					sendKeyEvent(KeyboardEvent(13))
					awaitCondition { fixture.clicks == listOf("First", "Last") }

					sendKeyEvent(KeyboardEvent(9))
					awaitCondition { fixture.focused == "editor" }
					assertThat(fixture.scroll.offset).isEqualTo(0)
					sendKeyEvent(KeyboardEvent(9, modifiers = ModifierShift))
					awaitCondition { fixture.focused == "Last" }
					sendKeyEvent(KeyboardEvent(9, modifiers = ModifierShift))
					awaitCondition { fixture.focused == "First" }
					sendKeyEvent(KeyboardEvent(9, modifiers = ModifierShift))
					awaitCondition { fixture.focused == "editor" }
					assertThat(fixture.scroll.offset).isEqualTo(0)
					assertThat(fixture.keyEvents).isEqualTo(
						listOf("editor:Tab", "First:Enter", "First:Tab", "Last:Enter", "Last:Tab", "editor:Tab", "Last:Tab", "First:Tab"),
					)
				} finally {
					cancel()
				}
			}
		}
	}

	@Test fun delayedMountRequestBetweenTabsReturnsToFirstWithoutVisitingLast() = runTest {
		val fixture = MountFocusFixture()
		runMosaicTest {
			try {
				setContentAndSnapshot { fixture.Content(nestedProvider = true) }
				sendKeyEvent(KeyboardEvent(9))
				awaitCondition { fixture.focused == "First" }
				sendKeyEvent(KeyboardEvent(13))
				awaitCondition { fixture.clicks == listOf("First") }
				assertThat(fixture.mountCompleted.isCompleted).isEqualTo(false)

				// Control only the request's timing, not focus search or its layout projection.
				fixture.releaseMount.complete(Unit)
				awaitCondition { fixture.mountCompleted.isCompleted && fixture.focused == "editor" }
				sendKeyEvent(KeyboardEvent(9))
				awaitCondition { fixture.focused == "First" }
				assertThat(fixture.activeTargets).isEqualTo(listOf("editor", "First", "editor", "First"))
				assertThat(fixture.scroll.offset).isEqualTo(0)
				assertThat(fixture.lastPosition).isEqualTo(IntOffset(0, 3))
				assertThat(fixture.keyEvents).isEqualTo(listOf("editor:Tab", "First:Enter", "editor:Tab"))
			} finally {
				cancel()
			}
		}
	}
}

// The gate makes the mount-effect interleaving deterministic. It does not establish the timing
// of any particular product test; the FocusOwner trace must confirm that separately.
private class MountFocusFixture {
	val scroll = MountFocusScroll()
	val releaseMount = CompletableDeferred<Unit>()
	val mountCompleted = CompletableDeferred<Unit>()
	val activeTargets = mutableListOf<String>()
	val clicks = mutableListOf<String>()
	val keyEvents = mutableListOf<String>()
	var focused = ""
	var lastPosition: IntOffset? = null
	private val editor = FocusRequester()
	private val outer = MountFocusScroll()

	@Composable fun Content(nestedProvider: Boolean) {
		val editorContent: @Composable () -> Unit = remember {
			{
				Text(
					"fourth",
					Modifier.focusRequester(editor).onFocusChanged { recordFocus("editor", it) }
						.focusable(autoFocus = true).onKeyEvent {
							keyEvents += "editor:${it.key}"
							false
						},
				)
				LaunchedEffect(Unit) {
					withFrameNanos { }
					releaseMount.await()
					assertThat(editor.requestFocus()).isTrue()
					mountCompleted.complete(Unit)
				}
			}
		}
		val provider = if (nestedProvider) Modifier.then(MountFocusScrollElement(outer)) else Modifier
		Box(Modifier.size(20, 2).clipToBounds().then(provider)) {
			Layout(
				modifier = Modifier.then(MountFocusScrollElement(scroll)).clipToBounds(),
				content = {
					SubcomposeLayout { constraints ->
						val children = subcompose(Unit, content = editorContent).map { it.measure(constraints) }
						layout(20, 1) { children.forEach { it.place(0, 0) } }
					}
					Column {
						Status("First")
						Text("middle")
						Status("Last")
					}
				},
			) { measurables, _ ->
				val children = measurables.map { it.measure(Constraints(maxWidth = 20)) }
				layout(20, 2) {
					var y = -scroll.offset
					for (child in children) {
						child.place(0, y)
						y += child.height
					}
				}
			}
		}
	}

	@Composable private fun Status(label: String) {
		var active by remember { mutableStateOf(false) }
		Box(
			Modifier.onPlaced { if (label == "Last") lastPosition = it.position }
				.onFocusChanged { recordFocus(label, it) }
				.onFocusChanged { active = it == FocusState.Active }
				.focusable().onKeyEvent {
					keyEvents += "$label:${it.key}"
					if (it.key == "Enter") {
						clicks += label
						true
					} else {
						false
					}
				},
		) {
			// Observe focus state like a pressable, including focus-driven recomposition.
			Text("[$label]", textStyle = if (active) TextStyle.Bold else TextStyle.Empty)
		}
	}

	private fun recordFocus(label: String, state: FocusState) {
		if (state == FocusState.Active) {
			focused = label
			activeTargets += label
		}
	}
}

private suspend fun TestMosaic<String>.awaitCondition(matches: () -> Boolean) {
	withTimeout(2_000) {
		while (!matches()) {
			withTimeoutOrNull(50) { awaitSnapshot() }
		}
	}
}

private class MountFocusScroll : BeyondBoundsLayout {
	var offset by mutableIntStateOf(0)
	lateinit var remeasurement: Remeasurement

	override fun <T> layout(
		direction: BeyondBoundsLayout.LayoutDirection,
		block: BeyondBoundsLayout.BeyondBoundsScope.() -> T?,
	): T? = block(
		object : BeyondBoundsLayout.BeyondBoundsScope {
			override val hasMoreContent: Boolean get() = false
		},
	)

	fun reveal(bounds: IntRect) {
		val delta = when {
			bounds.top < 0 -> bounds.top
			bounds.bottom > 2 -> bounds.bottom - 2
			else -> return
		}
		offset += delta
		remeasurement.forceRemeasure()
	}
}

private data class MountFocusScrollElement(
	val scroll: MountFocusScroll,
) : ModifierNodeElement<MountFocusScrollNode>(),
	RemeasurementModifier {
	override fun create(): MountFocusScrollNode = MountFocusScrollNode(scroll)

	override fun update(node: MountFocusScrollNode) {
		node.scroll = scroll
	}

	override fun onRemeasurementAvailable(remeasurement: Remeasurement) {
		scroll.remeasurement = remeasurement
	}
}

private class MountFocusScrollNode(
	var scroll: MountFocusScroll,
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
		bringIntoView {
			boundsProvider()?.translate(childCoordinates.position - requireLayoutCoordinates().position)
		}
	}
}
