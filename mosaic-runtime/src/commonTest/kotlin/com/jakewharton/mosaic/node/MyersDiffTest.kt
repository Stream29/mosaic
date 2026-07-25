package com.jakewharton.mosaic.node

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test

class MyersDiffTest {
	@Test fun rebuildsEveryShortSequence() {
		val sequences = buildList {
			var sequencesOfSize = listOf(emptyList<Int>())
			addAll(sequencesOfSize)
			repeat(4) {
				sequencesOfSize = sequencesOfSize.flatMap { prefix ->
					Alphabet.map { value -> prefix + value }
				}
				addAll(sequencesOfSize)
			}
		}

		for (before in sequences) {
			for (after in sequences) {
				val rebuilt = mutableListOf<Int>()
				executeDiff(
					oldSize = before.size,
					newSize = after.size,
					callback = object : DiffCallback {
						override fun areItemsTheSame(oldIndex: Int, newIndex: Int): Boolean {
							return before[oldIndex] == after[newIndex]
						}

						override fun insert(newIndex: Int) {
							rebuilt += after[newIndex]
						}

						override fun remove(atIndex: Int, oldIndex: Int): Unit = Unit

						override fun same(oldIndex: Int, newIndex: Int) {
							rebuilt += before[oldIndex]
						}
					},
				)
				assertThat(rebuilt).isEqualTo(after)
			}
		}
	}

	private companion object {
		val Alphabet = listOf(0, 1, 2)
	}
}
