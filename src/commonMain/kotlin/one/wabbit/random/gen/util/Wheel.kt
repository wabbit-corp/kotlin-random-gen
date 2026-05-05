// SPDX-License-Identifier: AGPL-3.0-or-later

@file:OptIn(ExperimentalUnsignedTypes::class)

package one.wabbit.random.gen.util

import kotlin.jvm.JvmName
import kotlin.math.roundToLong

/**
 * Weighted selection table used by `Gen.Choose`.
 *
 * @property size number of selectable entries.
 * @property weights positive weights for each entry, or null for an unweighted table.
 * @property item maps a selected index to the corresponding item.
 */
data class Wheel<A>(val size: Int, val weights: ULongArray?, val item: (ULong) -> A) {
    init {
        require(size > 0)
        if (weights != null) {
            require(weights.size == size)
            require(weights.isNotEmpty())
            require(weights.all { it > 0UL })
        }
    }

    /** Total draw range, equal to the sum of weights or [size] for unweighted wheels. */
    val total: ULong = weights?.sum() ?: size.toULong()

    /** Draws an item for [n], where [n] must be in `0 until total`. */
    fun draw(n: ULong): A {
        require(n < total)
        if (weights == null) return item(n % size.toULong())
        var x = n
        for (i in weights.indices) {
            val w = weights[i]
            if (x < w) return item(i.toULong())
            x -= w
        }
        error("unreachable")
    }

    /** Maps the items selected by this wheel while preserving its weights. */
    fun <B> map(f: (A) -> B): Wheel<B> = Wheel(size, weights) { a -> f(item(a)) }

    /** Compares wheel weights and item mapping identity. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Wheel<*>) return false

        if (!weights.contentEquals(other.weights)) return false
        if (item != other.item) return false
        if (total != other.total) return false

        return true
    }

    /** Returns a hash code derived from weights, item mapping identity, and total weight. */
    override fun hashCode(): Int {
        var result = weights.contentHashCode()
        result = 31 * result + item.hashCode()
        result = 31 * result + total.hashCode()
        return result
    }

    /** Constructors for weighted and unweighted wheels. */
    companion object {
        /** Creates an unweighted wheel from an index function. */
        fun <A> unweighted(size: Int, item: (Int) -> A): Wheel<A> {
            require(size > 0)
            return Wheel(size, null) { i -> item(i.toInt()) }
        }

        /** Creates an unweighted wheel over [options]. */
        fun <A> unweighted(options: List<A>): Wheel<A> {
            require(options.isNotEmpty())
            return Wheel(options.size, null) { i -> options[i.toInt()] }
        }

        /** Creates an unweighted wheel over [options]. */
        fun <A> unweighted(vararg options: A): Wheel<A> {
            require(options.isNotEmpty())
            return Wheel(options.size, null) { i -> options[i.toInt()] }
        }

        /** Creates a wheel from positive unsigned weights. */
        @JvmName("weightedULong")
        fun <A> weighted(options: List<Pair<ULong, A>>): Wheel<A> {
            require(options.isNotEmpty())
            require(options.all { it.first > 0UL })
            return Wheel(options.size, options.map { it.first }.toULongArray()) { i ->
                options[i.toInt()].second
            }
        }

        /** Creates a wheel from non-negative integer weights. */
        @JvmName("intWeighted")
        fun <A> weighted(options: List<Pair<Int, A>>): Wheel<A> {
            require(options.isNotEmpty())
            require(options.all { it.first >= 0 })
            val total = options.sumOf { it.first }
            require(total > 0)
            return Wheel(options.size, options.map { it.first.toULong() }.toULongArray()) { i ->
                options[i.toInt()].second
            }
        }

        /** Creates a wheel from non-negative integer weights. */
        @JvmName("intWeighted")
        fun <A> weighted(vararg options: Pair<Int, A>): Wheel<A> = weighted(options.toList())

        /** Creates a wheel from non-negative floating-point weights. */
        @JvmName("doubleWeighted")
        fun <A> weighted(options: List<Pair<Double, A>>): Wheel<A> {
            require(options.isNotEmpty())
            require(options.all { it.first >= 0.0 })
            val total = options.sumOf { it.first }
            require(total > 0.0)
            val scaled =
                options.map { (w, _) -> (w / total * Long.MAX_VALUE).roundToLong().toULong() }
            val values = options.map { it.second }
            val adjusted =
                if (scaled.sum() == 0UL) {
                    ULongArray(scaled.size) { 1UL }
                } else {
                    scaled.toULongArray()
                }
            return Wheel(options.size, adjusted, { i -> values[i.toInt()] })
        }

        /** Creates a wheel from non-negative floating-point weights. */
        @JvmName("doubleWeighted")
        fun <A> weighted(vararg options: Pair<Double, A>): Wheel<A> = weighted(options.toList())
    }
}
