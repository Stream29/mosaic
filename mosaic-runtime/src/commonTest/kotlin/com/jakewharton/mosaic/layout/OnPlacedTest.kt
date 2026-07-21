package com.jakewharton.mosaic.layout

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.IntOffset
import com.jakewharton.mosaic.ui.unit.IntSize
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class OnPlacedTest {
	@Test fun reportsSurfaceCoordinates() = runTest {
		lateinit var coordinates: LayoutCoordinates

		runMosaicTest {
			setContent {
				Row {
					Text("会话")
					Text("target", modifier = Modifier.onPlaced { coordinates = it })
				}
			}

			assertThat(awaitSnapshot()).isEqualTo("会话target")
			assertThat(coordinates).isEqualTo(
				LayoutCoordinates(position = IntOffset(4, 0), size = IntSize(6, 1)),
			)
		}
	}

	@Test fun reportsOnlyChangedCoordinates() = runTest {
		var leadingText by mutableStateOf("A")
		val coordinates = mutableListOf<LayoutCoordinates>()

		runMosaicTest {
			setContent {
				Row {
					Text(leadingText)
					Text("target", modifier = Modifier.onPlaced(coordinates::add))
				}
			}

			assertThat(awaitSnapshot()).isEqualTo("Atarget")
			assertThat(coordinates).containsExactly(
				LayoutCoordinates(position = IntOffset(1, 0), size = IntSize(6, 1)),
			)

			leadingText = "B"
			assertThat(awaitSnapshot()).isEqualTo("Btarget")
			assertThat(coordinates).containsExactly(
				LayoutCoordinates(position = IntOffset(1, 0), size = IntSize(6, 1)),
			)

			leadingText = ""
			assertThat(awaitSnapshot()).isEqualTo("target")
			assertThat(coordinates).containsExactly(
				LayoutCoordinates(position = IntOffset(1, 0), size = IntSize(6, 1)),
				LayoutCoordinates(position = IntOffset(0, 0), size = IntSize(6, 1)),
			)
		}
	}
}
