/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:Suppress("NOTHING_TO_INLINE", "KotlinRedundantDiagnosticSuppress")

package com.jakewharton.mosaic.node

import kotlin.jvm.JvmInline
import kotlin.math.abs
import kotlin.math.min

/*
 * Adapted from AndroidX Compose UI's MyersDiff.kt. The algorithm and primitive storage remain the
 * same; this copy removes dependencies on Compose-internal precondition helpers.
 */
internal interface DiffCallback {
	fun areItemsTheSame(oldIndex: Int, newIndex: Int): Boolean

	fun insert(newIndex: Int)

	fun remove(atIndex: Int, oldIndex: Int)

	fun same(oldIndex: Int, newIndex: Int)
}

internal fun executeDiff(
	oldSize: Int,
	newSize: Int,
	callback: DiffCallback,
) {
	if (oldSize == 0) {
		repeat(newSize, callback::insert)
		return
	}
	if (newSize == 0) {
		repeat(oldSize) { oldIndex -> callback.remove(0, oldIndex) }
		return
	}
	applyDiff(calculateDiff(oldSize, newSize, callback), callback)
}

private fun calculateDiff(
	oldSize: Int,
	newSize: Int,
	callback: DiffCallback,
): IntStack {
	val max = (oldSize + newSize + 1) / 2
	val diagonals = IntStack(max * 3)
	val ranges = IntStack(max * 4)
	ranges.pushRange(0, oldSize, 0, newSize)
	val forward = CenteredArray(IntArray(max * 2 + 1))
	val backward = CenteredArray(IntArray(max * 2 + 1))
	val snake = Snake(IntArray(5))

	while (ranges.isNotEmpty()) {
		val newEnd = ranges.pop()
		val newStart = ranges.pop()
		val oldEnd = ranges.pop()
		val oldStart = ranges.pop()
		if (
			findMidpoint(
				oldStart,
				oldEnd,
				newStart,
				newEnd,
				callback,
				forward,
				backward,
				snake.data,
			)
		) {
			if (snake.diagonalSize > 0) {
				snake.addDiagonalTo(diagonals)
			}
			ranges.pushRange(
				oldStart = oldStart,
				oldEnd = snake.startX,
				newStart = newStart,
				newEnd = snake.startY,
			)
			ranges.pushRange(
				oldStart = snake.endX,
				oldEnd = oldEnd,
				newStart = snake.endY,
				newEnd = newEnd,
			)
		}
	}
	diagonals.sortDiagonals()
	diagonals.pushDiagonal(oldSize, newSize, 0)
	return diagonals
}

private fun applyDiff(
	diagonals: IntStack,
	callback: DiffCallback,
) {
	var oldIndex = 0
	var newIndex = 0
	var diagonalIndex = 0
	while (diagonalIndex < diagonals.size) {
		val diagonalSize = diagonals[diagonalIndex + 2]
		val diagonalOldStart = diagonals[diagonalIndex] - diagonalSize
		val diagonalNewStart = diagonals[diagonalIndex + 1] - diagonalSize
		diagonalIndex += 3
		while (oldIndex < diagonalOldStart) {
			callback.remove(newIndex, oldIndex)
			oldIndex++
		}
		while (newIndex < diagonalNewStart) {
			callback.insert(newIndex)
			newIndex++
		}
		repeat(diagonalSize) {
			callback.same(oldIndex, newIndex)
			oldIndex++
			newIndex++
		}
	}
}

private fun findMidpoint(
	oldStart: Int,
	oldEnd: Int,
	newStart: Int,
	newEnd: Int,
	callback: DiffCallback,
	forward: CenteredArray,
	backward: CenteredArray,
	snake: IntArray,
): Boolean {
	val oldSize = oldEnd - oldStart
	val newSize = newEnd - newStart
	if (oldSize < 1 || newSize < 1) return false

	val max = (oldSize + newSize + 1) / 2
	forward[1] = oldStart
	backward[1] = oldEnd
	for (distance in 0 until max) {
		if (
			searchForward(
				oldStart,
				oldEnd,
				newStart,
				newEnd,
				callback,
				forward,
				backward,
				distance,
				snake,
			)
		) {
			return true
		}
		if (
			searchBackward(
				oldStart,
				oldEnd,
				newStart,
				newEnd,
				callback,
				forward,
				backward,
				distance,
				snake,
			)
		) {
			return true
		}
	}
	return false
}

private fun searchForward(
	oldStart: Int,
	oldEnd: Int,
	newStart: Int,
	newEnd: Int,
	callback: DiffCallback,
	forward: CenteredArray,
	backward: CenteredArray,
	distance: Int,
	snake: IntArray,
): Boolean {
	val oldSize = oldEnd - oldStart
	val newSize = newEnd - newStart
	val checkForSnake = (abs(oldSize - newSize) and 1) == 1
	val delta = oldSize - newSize
	var diagonal = -distance
	while (diagonal <= distance) {
		val startX: Int
		var x: Int
		if (
			diagonal == -distance ||
			(
				diagonal != distance &&
					forward[diagonal + 1] > forward[diagonal - 1]
				)
		) {
			startX = forward[diagonal + 1]
			x = startX
		} else {
			startX = forward[diagonal - 1]
			x = startX + 1
		}
		var y = newStart + (x - oldStart) - diagonal
		val startY = y - ((distance != 0) and (x == startX)).toInt()
		while (x < oldEnd && y < newEnd && callback.areItemsTheSame(x, y)) {
			x++
			y++
		}
		forward[diagonal] = x
		if (checkForSnake) {
			val backwardsDiagonal = delta - diagonal
			if (
				backwardsDiagonal >= -distance + 1 &&
				backwardsDiagonal <= distance - 1 &&
				backward[backwardsDiagonal] <= x
			) {
				fillSnake(startX, startY, x, y, reverse = false, snake)
				return true
			}
		}
		diagonal += 2
	}
	return false
}

private fun searchBackward(
	oldStart: Int,
	oldEnd: Int,
	newStart: Int,
	newEnd: Int,
	callback: DiffCallback,
	forward: CenteredArray,
	backward: CenteredArray,
	distance: Int,
	snake: IntArray,
): Boolean {
	val oldSize = oldEnd - oldStart
	val newSize = newEnd - newStart
	val checkForSnake = ((oldSize - newSize) and 1) == 0
	val delta = oldSize - newSize
	var diagonal = -distance
	while (diagonal <= distance) {
		val startX: Int
		var x: Int
		if (
			diagonal == -distance ||
			(
				diagonal != distance &&
					backward[diagonal + 1] < backward[diagonal - 1]
				)
		) {
			startX = backward[diagonal + 1]
			x = startX
		} else {
			startX = backward[diagonal - 1]
			x = startX - 1
		}
		var y = newEnd - (oldEnd - x - diagonal)
		val startY = y + ((distance != 0) and (x == startX)).toInt()
		while (x > oldStart && y > newStart && callback.areItemsTheSame(x - 1, y - 1)) {
			x--
			y--
		}
		backward[diagonal] = x
		if (checkForSnake) {
			val forwardsDiagonal = delta - diagonal
			if (
				forwardsDiagonal >= -distance &&
				forwardsDiagonal <= distance &&
				forward[forwardsDiagonal] >= x
			) {
				fillSnake(x, y, startX, startY, reverse = true, snake)
				return true
			}
		}
		diagonal += 2
	}
	return false
}

@JvmInline
private value class Snake(
	val data: IntArray,
) {
	val startX: Int
		inline get() = data[0]

	val startY: Int
		inline get() = data[1]

	val endX: Int
		inline get() = data[2]

	val endY: Int
		inline get() = data[3]

	val reverse: Boolean
		inline get() = data[4] != 0

	val diagonalSize: Int
		inline get() = min(endX - startX, endY - startY)

	private val hasAdditionOrRemoval: Boolean
		get() = endY - startY != endX - startX

	private val isAddition: Boolean
		get() = endY - startY > endX - startX

	fun addDiagonalTo(diagonals: IntStack) {
		var x = startX
		var y = startY
		val size: Int
		if (hasAdditionOrRemoval) {
			size = diagonalSize
			x += (!(reverse or isAddition)).toInt()
			y += (!(reverse or !isAddition)).toInt()
		} else {
			size = endX - startX
		}
		diagonals.pushDiagonal(x, y, size)
	}
}

private inline fun Boolean.toInt(): Int = if (this) 1 else 0

private fun fillSnake(
	startX: Int,
	startY: Int,
	endX: Int,
	endY: Int,
	reverse: Boolean,
	data: IntArray,
) {
	data[0] = startX
	data[1] = startY
	data[2] = endX
	data[3] = endY
	data[4] = reverse.toInt()
}

@JvmInline
private value class CenteredArray(
	private val data: IntArray,
) {
	private val midpoint: Int
		get() = data.size / 2

	operator fun get(index: Int): Int = data[index + midpoint]

	operator fun set(index: Int, value: Int) {
		data[index + midpoint] = value
	}
}

private class IntStack(initialCapacity: Int) {
	private var values = IntArray(maxOf(initialCapacity, MINIMUM_CAPACITY))
	private var nextIndex = 0

	val size: Int
		get() = nextIndex

	operator fun get(index: Int): Int = values[index]

	fun pushRange(
		oldStart: Int,
		oldEnd: Int,
		newStart: Int,
		newEnd: Int,
	) {
		ensureCapacity(4)
		values[nextIndex] = oldStart
		values[nextIndex + 1] = oldEnd
		values[nextIndex + 2] = newStart
		values[nextIndex + 3] = newEnd
		nextIndex += 4
	}

	fun pushDiagonal(
		x: Int,
		y: Int,
		size: Int,
	) {
		ensureCapacity(3)
		values[nextIndex] = x + size
		values[nextIndex + 1] = y + size
		values[nextIndex + 2] = size
		nextIndex += 3
	}

	fun pop(): Int = values[--nextIndex]

	fun isNotEmpty(): Boolean = nextIndex != 0

	fun sortDiagonals() {
		check(nextIndex % 3 == 0) { "Array size is not a multiple of three" }
		if (nextIndex > 3) quickSort(0, nextIndex - 3)
	}

	private fun ensureCapacity(additionalSize: Int) {
		val requiredSize = nextIndex + additionalSize
		if (requiredSize > values.size) {
			values = values.copyOf(maxOf(values.size * 2, requiredSize))
		}
	}

	private fun quickSort(start: Int, end: Int) {
		if (start >= end) return
		val pivot = partition(start, end)
		quickSort(start, pivot - DIAGONAL_SIZE)
		quickSort(pivot + DIAGONAL_SIZE, end)
	}

	private fun partition(start: Int, end: Int): Int {
		var partitionIndex = start - DIAGONAL_SIZE
		var candidate = start
		while (candidate < end) {
			if (compareDiagonal(candidate, end)) {
				partitionIndex += DIAGONAL_SIZE
				swapDiagonal(partitionIndex, candidate)
			}
			candidate += DIAGONAL_SIZE
		}
		swapDiagonal(partitionIndex + DIAGONAL_SIZE, end)
		return partitionIndex + DIAGONAL_SIZE
	}

	private fun compareDiagonal(first: Int, second: Int): Boolean {
		val firstX = values[first]
		val secondX = values[second]
		return firstX < secondX ||
			(firstX == secondX && values[first + 1] <= values[second + 1])
	}

	private fun swapDiagonal(first: Int, second: Int) {
		values.swap(first, second)
		values.swap(first + 1, second + 1)
		values.swap(first + 2, second + 2)
	}

	private companion object {
		const val MINIMUM_CAPACITY = 4
		const val DIAGONAL_SIZE = 3
	}
}

private fun IntArray.swap(first: Int, second: Int) {
	val temporary = this[first]
	this[first] = this[second]
	this[second] = temporary
}
