/*
 * Copyright 2019 The Android Open Source Project
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

package com.jakewharton.mosaic.modifier

import androidx.compose.runtime.Stable
import com.jakewharton.mosaic.layout.LayoutCoordinates
import com.jakewharton.mosaic.layout.MosaicNode
import com.jakewharton.mosaic.node.DelegatableNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

/**
 * An ordered, immutable collection of [modifier elements][Modifier.Element] that decorate or add
 * behavior to Mosaic elements. For example, backgrounds, padding, and click event listeners
 * decorate or add behavior to rows, text, or buttons.
 *
 * Modifier implementations should offer a fluent factory extension function on [Modifier] for
 * creating combined modifiers by starting from existing modifiers:
 *
 * Modifier elements may be combined using [then]. Order is significant; modifier elements that
 * appear first will be applied first.
 *
 * Composables that accept a [Modifier] as a parameter to be applied to the whole component
 * represented by the composable function should name the parameter `modifier` and
 * assign the parameter a default value of [Modifier]. It should appear as the first
 * optional parameter in the parameter list; after all required parameters (except for trailing
 * lambda parameters) but before any other parameters with default values. Any default modifiers
 * desired by a composable function should come after the `modifier` parameter's value in the
 * composable function's implementation, keeping [Modifier] as the default parameter value.
 *
 * The pattern above allows default modifiers to still be applied as part of the chain
 * if a caller also supplies unrelated modifiers.
 *
 * Composables that accept modifiers to be applied to a specific subcomponent `foo`
 * should name the parameter `fooModifier` and follow the same guidelines above for default values
 * and behavior. Subcomponent modifiers should be grouped together and follow the parent
 * composable's modifier.
 */
@Stable
public interface Modifier {

	/**
	 * Accumulates a value starting with [initial] and applying [operation] to the current value
	 * and each element from outside in.
	 *
	 * Elements wrap one another in a chain from left to right; an [Element] that appears to the
	 * left of another in a `+` expression or in [operation]'s parameter order affects all
	 * of the elements that appear after it. [foldIn] may be used to accumulate a value starting
	 * from the parent or head of the modifier chain to the final wrapped child.
	 */
	public fun <R> foldIn(initial: R, operation: (R, Element) -> R): R

	/**
	 * Accumulates a value starting with [initial] and applying [operation] to the current value
	 * and each element from inside out.
	 *
	 * Elements wrap one another in a chain from left to right; an [Element] that appears to the
	 * left of another in a `+` expression or in [operation]'s parameter order affects all
	 * of the elements that appear after it. [foldOut] may be used to accumulate a value starting
	 * from the child or tail of the modifier chain up to the parent or head of the chain.
	 */
	public fun <R> foldOut(initial: R, operation: (Element, R) -> R): R

	/**
	 * Returns `true` if [predicate] returns true for any [Element] in this [Modifier].
	 */
	public fun any(predicate: (Element) -> Boolean): Boolean

	/**
	 * Returns `true` if [predicate] returns true for all [Element]s in this [Modifier] or if
	 * this [Modifier] contains no [Element]s.
	 */
	public fun all(predicate: (Element) -> Boolean): Boolean

	/**
	 * Concatenates this modifier with another.
	 *
	 * Returns a [Modifier] representing this modifier followed by [other] in sequence.
	 */
	public infix fun then(other: Modifier): Modifier = if (other === Modifier) this else CombinedModifier(this, other)

	/**
	 * A single element contained within a [Modifier] chain.
	 */
	public interface Element : Modifier {
		override fun <R> foldIn(initial: R, operation: (R, Element) -> R): R = operation(initial, this)

		override fun <R> foldOut(initial: R, operation: (Element, R) -> R): R = operation(this, initial)

		override fun any(predicate: (Element) -> Boolean): Boolean = predicate(this)

		override fun all(predicate: (Element) -> Boolean): Boolean = predicate(this)
	}

	/**
	 * A stateful object associated with a [Modifier.Element].
	 *
	 * Nodes are created and updated by a
	 * [com.jakewharton.mosaic.node.ModifierNodeElement]. A node may implement additional node
	 * interfaces to participate in Mosaic subsystems without storing mutable state in the
	 * immutable modifier element.
	 */
	public abstract class Node : DelegatableNode {
		public final override val node: Node
			get() = this

		/** `null` before this attachment first requests a scope and after it is detached. */
		private var attachedScope: CoroutineScope? = null

		/**
		 * A scope whose lifetime matches this node's current attachment.
		 *
		 * Access is valid only between [onAttach] and [onDetach]. Detaching the node cancels the
		 * scope after [onDetach] returns.
		 */
		public val coroutineScope: CoroutineScope
			get() {
				check(isAttached) { "Cannot access coroutineScope while the node is detached" }
				return attachedScope ?: requireLayoutNode().createModifierNodeScope().also {
					attachedScope = it
				}
			}

		/** Whether this node is attached to a Mosaic layout tree. */
		public var isAttached: Boolean = false
			private set

		private var attachLifecyclePending: Boolean = false
		private var detachLifecyclePending: Boolean = false

		/** `null` means this is the outermost node in its local modifier chain. */
		internal var parent: Node? = null

		/** `null` means this is the innermost node in its local modifier chain. */
		internal var child: Node? = null

		/** `null` means this node is not bound to a Mosaic layout node. */
		internal var layoutNode: MosaicNode? = null

		/** `null` means this node has not been placed during its current attachment. */
		internal var layoutCoordinates: LayoutCoordinates? = null

		/** Called after this node becomes attached to a Mosaic layout tree. */
		public open fun onAttach(): Unit = Unit

		/** Called immediately before this node becomes detached from its Mosaic layout tree. */
		public open fun onDetach(): Unit = Unit

		/** Called before an attached layout node is reused for semantically different content. */
		public open fun onReset(): Unit = Unit

		internal fun bind(layoutNode: MosaicNode) {
			check(this.layoutNode == null) { "Modifier node is already bound to a layout node" }
			this.layoutNode = layoutNode
		}

		internal fun unbind() {
			check(!isAttached) { "Cannot unbind an attached modifier node" }
			layoutNode = null
			layoutCoordinates?.detach()
			layoutCoordinates = null
			parent = null
			child = null
		}

		internal fun markAttached() {
			check(!isAttached) { "Modifier node is already attached" }
			check(layoutNode != null) { "Cannot attach an unbound modifier node" }
			isAttached = true
			attachLifecyclePending = true
		}

		internal fun runAttachLifecycle() {
			check(isAttached) { "Cannot run attach lifecycle for a detached modifier node" }
			check(attachLifecyclePending) { "Modifier node attach lifecycle already ran" }
			attachLifecyclePending = false
			onAttach()
			detachLifecyclePending = true
		}

		internal fun runDetachLifecycle() {
			check(isAttached) { "Cannot run detach lifecycle for a detached modifier node" }
			check(detachLifecyclePending) { "Modifier node detach lifecycle already ran" }
			detachLifecyclePending = false
			onDetach()
		}

		internal fun markDetached() {
			check(isAttached) { "Modifier node is already detached" }
			check(!attachLifecyclePending) { "Modifier node attach lifecycle has not run" }
			check(!detachLifecyclePending) { "Modifier node detach lifecycle has not run" }
			isAttached = false
			layoutCoordinates?.detach()
			attachedScope?.cancel()
			attachedScope = null
		}

		internal fun reset() {
			check(isAttached) { "Cannot reset a detached modifier node" }
			onReset()
		}

		internal fun requireLayoutNode(): MosaicNode = checkNotNull(layoutNode) {
			"Modifier node is not bound to a layout node"
		}

		private fun MosaicNode.createModifierNodeScope(): CoroutineScope {
			val parentContext = modifierNodeCoroutineContext
			return CoroutineScope(parentContext + Job(parentContext[Job]))
		}
	}

	/**
	 * The companion object `Modifier` is the empty, default, or starter [Modifier]
	 * that contains no [elements][Element]. Use it to create a new [Modifier] using
	 * modifier extension factory functions or as the default value for [Modifier] parameters.
	 */
	// The companion object implements `Modifier` so that it may be used as the start of a
	// modifier extension factory expression.
	public companion object : Modifier {
		override fun <R> foldIn(initial: R, operation: (R, Element) -> R): R = initial
		override fun <R> foldOut(initial: R, operation: (Element, R) -> R): R = initial
		override fun any(predicate: (Element) -> Boolean): Boolean = false
		override fun all(predicate: (Element) -> Boolean): Boolean = true
		override infix fun then(other: Modifier): Modifier = other
		override fun toString(): String = "Modifier"
	}
}

/**
 * A node in a [Modifier] chain. A CombinedModifier always contains at least two elements;
 * a Modifier [outer] that wraps around the Modifier [inner].
 */
public class CombinedModifier(
	internal val outer: Modifier,
	internal val inner: Modifier,
) : Modifier {
	override fun <R> foldIn(initial: R, operation: (R, Modifier.Element) -> R): R = inner.foldIn(outer.foldIn(initial, operation), operation)

	override fun <R> foldOut(initial: R, operation: (Modifier.Element, R) -> R): R = outer.foldOut(inner.foldOut(initial, operation), operation)

	override fun any(predicate: (Modifier.Element) -> Boolean): Boolean = outer.any(predicate) || inner.any(predicate)

	override fun all(predicate: (Modifier.Element) -> Boolean): Boolean = outer.all(predicate) && inner.all(predicate)

	override fun equals(other: Any?): Boolean = other is CombinedModifier && outer == other.outer && inner == other.inner

	override fun hashCode(): Int = outer.hashCode() + 31 * inner.hashCode()

	override fun toString(): String = "[" + foldIn("") { acc, element ->
		if (acc.isEmpty()) element.toString() else "$acc, $element"
	} + "]"
}
