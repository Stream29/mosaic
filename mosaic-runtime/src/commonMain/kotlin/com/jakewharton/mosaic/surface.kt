package com.jakewharton.mosaic

import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.TextStyle.Companion.Bold
import com.jakewharton.mosaic.ui.TextStyle.Companion.Dim
import com.jakewharton.mosaic.ui.TextStyle.Companion.Invert
import com.jakewharton.mosaic.ui.TextStyle.Companion.Italic
import com.jakewharton.mosaic.ui.TextStyle.Companion.Strikethrough
import com.jakewharton.mosaic.ui.UnderlineStyle
import com.jakewharton.mosaic.ui.isEmptyTextStyle
import com.jakewharton.mosaic.ui.isNotEmptyTextStyle
import com.jakewharton.mosaic.ui.isSpecifiedColor
import com.jakewharton.mosaic.ui.isSpecifiedUnderlineStyle
import com.jakewharton.mosaic.ui.isUnspecifiedColor
import com.jakewharton.mosaic.ui.isUnspecifiedUnderlineStyle
import com.jakewharton.mosaic.ui.unit.IntOffset
import com.jakewharton.mosaic.ui.unit.IntRect
import com.jakewharton.mosaic.ui.unit.IntSize

private val blankPixel = TextPixel(" ")

public interface TextCanvas {
	public val height: Int
	public val width: Int

	// TODO Hey! These don't go here...
	public fun render(ansiLevel: AnsiLevel, supportsKittyUnderlines: Boolean): String
	public fun appendRowTo(appendable: Appendable, row: Int, ansiLevel: AnsiLevel, supportsKittyUnderlines: Boolean)
}

internal class TextSurface(
	override val width: Int,
	override val height: Int,
) : TextCanvas {
	var translationX = 0
	var translationY = 0

	/** `null` means that no explicit clipping modifier is active and bounds remain strict. */
	private var clipBounds: IntRect? = null

	private val cells = Array(width * height) { TextPixel(" ") }

	fun <T> withClip(bounds: IntRect, block: () -> T): T {
		val previousBounds = clipBounds
		clipBounds = (previousBounds ?: IntRect(0, 0, width, height)).intersect(bounds)
		return try {
			block()
		} finally {
			clipBounds = previousBounds
		}
	}

	operator fun get(row: Int, column: Int): TextPixel {
		val x = translationX + column
		val y = row + translationY
		check(x in 0 until width)
		check(y in 0 until height)
		return cells[y * width + x]
	}

	fun replaceText(
		row: Int,
		column: Int,
		text: String,
		cellWidth: Int,
	): TextPixel {
		require(cellWidth > 0)
		check(column >= 0 && column + cellWidth <= width - translationX)
		for (offset in 0 until cellWidth) {
			clearTextAt(row, column + offset)
		}

		val leader = get(row, column)
		leader.text = text
		leader.cellWidth = cellWidth
		leader.continuationOffset = 0
		for (offset in 1 until cellWidth) {
			get(row, column + offset).apply {
				this.text = ""
				this.cellWidth = 0
				continuationOffset = offset
			}
		}
		return leader
	}

	/**
	 * Replaces one complete terminal text cluster without modifying cells outside the active clip.
	 *
	 * The replacement cluster and every existing cluster it would clear must be wholly contained by
	 * the active clipping bounds. Without an active clip, this delegates to [replaceText] and retains
	 * its strict bounds checks.
	 *
	 * @return the replacement cluster's leader, or `null` when the replacement would cross the clip
	 * boundary or clear part of an existing cluster outside it. A `null` result leaves the surface
	 * unchanged.
	 */
	fun replaceTextWithinClipOrNull(
		row: Int,
		column: Int,
		text: String,
		cellWidth: Int,
	): TextPixel? {
		val bounds = clipBounds ?: return replaceText(row, column, text, cellWidth)
		val y = translationY + row
		if (
			!bounds.contains(
				offset = IntOffset(translationX + column, y),
				size = IntSize(cellWidth, 1),
			)
		) {
			return null
		}
		for (offset in 0 until cellWidth) {
			val targetColumn = column + offset
			val pixel = get(row, targetColumn)
			val leaderColumn = targetColumn - pixel.continuationOffset
			val leader = get(row, leaderColumn)
			if (
				!bounds.contains(
					offset = IntOffset(translationX + leaderColumn, y),
					size = IntSize(leader.cellWidth.coerceAtLeast(1), 1),
				)
			) {
				return null
			}
		}
		return replaceText(row, column, text, cellWidth)
	}

	fun textLeaderAt(row: Int, column: Int): TextPixel {
		val pixel = get(row, column)
		return if (pixel.isContinuation) {
			get(row, column - pixel.continuationOffset)
		} else {
			pixel
		}
	}

	/**
	 * @return the complete cluster's leader, or `null` when the addressed cell or any part of its
	 * cluster lies outside the active clip. Without an active clip, this delegates to [textLeaderAt].
	 */
	fun textLeaderAtOrNull(row: Int, column: Int): TextPixel? {
		val bounds = clipBounds ?: return textLeaderAt(row, column)
		val x = translationX + column
		val y = translationY + row
		if (IntOffset(x, y) !in bounds) return null
		val pixel = get(row, column)
		val leaderColumn = column - pixel.continuationOffset
		val leader = get(row, leaderColumn)
		if (
			!bounds.contains(
				offset = IntOffset(translationX + leaderColumn, y),
				size = IntSize(leader.cellWidth.coerceAtLeast(1), 1),
			)
		) {
			return null
		}
		return leader
	}

	private fun clearTextAt(row: Int, column: Int) {
		val pixel = get(row, column)
		val leaderColumn = column - pixel.continuationOffset
		val occupiedWidth = get(row, leaderColumn).cellWidth.coerceAtLeast(1)
		for (offset in 0 until occupiedWidth) {
			get(row, leaderColumn + offset).apply {
				text = " "
				cellWidth = 1
				continuationOffset = 0
			}
		}
	}

	override fun appendRowTo(appendable: Appendable, row: Int, ansiLevel: AnsiLevel, supportsKittyUnderlines: Boolean) {
		// Reused heap allocation for building ANSI attributes inside the loop.
		val attributes = mutableListOf<String>()

		val rowStart = row * width
		var rowStop = rowStart + width

		while (rowStop > rowStart) {
			val lastIndex = rowStop - 1
			val pixel = cells[lastIndex]
			if (pixel.isEmpty()) {
				rowStop = lastIndex
			} else {
				break
			}
		}

		var lastPixel = blankPixel
		for (columnIndex in rowStart until rowStop) {
			val pixel = cells[columnIndex]
			if (pixel.isContinuation) continue

			if (ansiLevel != AnsiLevel.NONE) {
				if (pixel.foreground != lastPixel.foreground) {
					attributes.addColor(
						pixel.foreground,
						ansiLevel,
						ansiFgColorSelector,
						ansiFgColorReset,
						ansiFgColorOffset,
					)
				}
				if (pixel.background != lastPixel.background) {
					attributes.addColor(
						pixel.background,
						ansiLevel,
						ansiBgColorSelector,
						ansiBgColorReset,
						ansiBgColorOffset,
					)
				}

				fun maybeToggleStyle(style: TextStyle, on: String, off: String) {
					if (style in pixel.textStyle) {
						if (style !in lastPixel.textStyle) {
							attributes += on
						}
					} else if (style in lastPixel.textStyle) {
						attributes += off
					}
				}
				fun maybeToggleIntensity() {
					val isBold = Bold in pixel.textStyle
					val isDim = Dim in pixel.textStyle
					val wasBold = Bold in lastPixel.textStyle
					val wasDim = Dim in lastPixel.textStyle
					if (isBold == wasBold && isDim == wasDim) return

					if (wasBold || wasDim) {
						// SGR 22 clears both bold and dim.
						attributes += "22"
					}
					if (isBold) {
						attributes += "1"
					}
					if (isDim) {
						attributes += "2"
					}
				}
				if (pixel.textStyle != lastPixel.textStyle) {
					maybeToggleIntensity()
					maybeToggleStyle(Italic, "3", "23")
					maybeToggleStyle(Invert, "7", "27")
					maybeToggleStyle(Strikethrough, "9", "29")
				}
				if (pixel.underlineStyle != lastPixel.underlineStyle) {
					attributes += when (pixel.underlineStyle) {
						UnderlineStyle.Unspecified, UnderlineStyle.None -> "24"
						UnderlineStyle.Double if (supportsKittyUnderlines) -> "4:2"
						UnderlineStyle.Curly if (supportsKittyUnderlines) -> "4:3"
						UnderlineStyle.Dotted if (supportsKittyUnderlines) -> "4:4"
						UnderlineStyle.Dashed if (supportsKittyUnderlines) -> "4:5"
						else -> "4"
					}
				}
				if (pixel.underlineColor != lastPixel.underlineColor) {
					attributes.addColor(
						pixel.underlineColor,
						ansiLevel,
						ansiUnderlineColorSelector,
						ansiUnderlineColorReset,
						ansiUnderlineColorOffset,
					)
				}
				if (attributes.isNotEmpty()) {
					appendable.append(CSI)
					attributes.forEachIndexed { index, element ->
						if (index > 0) {
							appendable.append(ansiSeparator)
						}
						appendable.append(element)
					}
					appendable.append(ansiClosingCharacter)
					attributes.clear() // This list is reused!
				}
			}

			appendable.append(pixel.text)
			lastPixel = pixel
		}

		if (
			ansiLevel != AnsiLevel.NONE &&
			(
				lastPixel.background.isSpecifiedColor ||
					lastPixel.foreground.isSpecifiedColor ||
					lastPixel.textStyle.isNotEmptyTextStyle ||
					lastPixel.underlineColor.isSpecifiedColor ||
					lastPixel.underlineStyle.isSpecifiedUnderlineStyle
				)
		) {
			appendable.append(ansiReset)
			appendable.append(ansiClosingCharacter)
		}
	}

	private fun MutableList<String>.addColor(
		color: Color,
		ansiLevel: AnsiLevel,
		select: Int,
		reset: Int,
		offset: Int,
	) {
		if (color.isUnspecifiedColor) {
			add(reset.toString())
			return
		}
		when (ansiLevel) {
			AnsiLevel.NONE -> add(reset.toString())

			AnsiLevel.ANSI16 -> {
				val ansi16Code = color.toAnsi16Code()
				if (ansi16Code == ansiFgColorReset || ansi16Code == ansiBgColorReset) {
					add(reset.toString())
				} else {
					add((ansi16Code + offset).toString())
				}
			}

			AnsiLevel.ANSI256 -> {
				add(select.toString())
				add(ansiSelectorColor256)
				add(color.toAnsi256Code().toString())
			}

			AnsiLevel.TRUECOLOR -> {
				add(select.toString())
				add(ansiSelectorColorRgb)
				add(color.redInt.toString())
				add(color.greenInt.toString())
				add(color.blueInt.toString())
			}
		}
	}

	override fun render(ansiLevel: AnsiLevel, supportsKittyUnderlines: Boolean): String = buildString {
		if (height > 0) {
			for (rowIndex in 0 until height) {
				appendRowTo(this, rowIndex, ansiLevel, supportsKittyUnderlines)
				append("\n")
			}
			// Remove trailing newline.
			setLength(length - 1)
		}
	}
}

internal class TextPixel(var text: String) {
	var cellWidth: Int = 1
	var continuationOffset: Int = 0
	var background: Color = Color.Unspecified
	var foreground: Color = Color.Unspecified
	var textStyle: TextStyle = TextStyle.Empty
	var underlineStyle: UnderlineStyle = UnderlineStyle.Unspecified
	var underlineColor: Color = Color.Unspecified

	val isContinuation: Boolean
		get() = continuationOffset > 0

	fun isEmpty(): Boolean {
		return (isContinuation || text == " ") &&
			background.isUnspecifiedColor &&
			foreground.isUnspecifiedColor &&
			textStyle.isEmptyTextStyle &&
			underlineStyle.isUnspecifiedUnderlineStyle &&
			underlineColor.isUnspecifiedColor
	}

	override fun toString() = buildString {
		append("TextPixel(\"")
		append(text)
		append("\"")
		if (background.isSpecifiedColor) {
			append(" bg=")
			append(background)
		}
		if (foreground.isSpecifiedColor) {
			append(" fg=")
			append(foreground)
		}
		// TODO style
		append(')')
	}
}
