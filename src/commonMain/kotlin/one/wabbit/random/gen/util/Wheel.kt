// SPDX-License-Identifier: AGPL-3.0-or-later

@file:OptIn(ExperimentalUnsignedTypes::class)

package one.wabbit.random.gen.util

import kotlin.math.roundToLong
import kotlin.jvm.JvmName

data class Wheel<A>(val size: Int, val weights: ULongArray?, val item: (ULong) -> A) {
    init {
        require(size > 0)
        if (weights != null) {
            require(weights.size == size)
            require(weights.isNotEmpty())
            require(weights.all { it > 0UL })
        }
    }

    val total: ULong = weights?.sum() ?: size.toULong()

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

    fun <B> map(f: (A) -> B): Wheel<B> = Wheel(size, weights) { a -> f(item(a)) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Wheel<*>) return false

        if (!weights.contentEquals(other.weights)) return false
        if (item != other.item) return false
        if (total != other.total) return false

        return true
    }

    override fun hashCode(): Int {
        var result = weights.contentHashCode()
        result = 31 * result + item.hashCode()
        result = 31 * result + total.hashCode()
        return result
    }

    companion object {
        fun <A> unweighted(size: Int, item: (Int) -> A): Wheel<A> {
            require(size > 0)
            return Wheel(size, null) { i -> item(i.toInt()) }
        }

        fun <A> unweighted(options: List<A>): Wheel<A> {
            require(options.isNotEmpty())
            return Wheel(options.size, null) { i -> options[i.toInt()] }
        }

        fun <A> unweighted(vararg options: A): Wheel<A> {
            require(options.isNotEmpty())
            return Wheel(options.size, null) { i -> options[i.toInt()] }
        }

        @JvmName("weightedULong")
        fun <A> weighted(options: List<Pair<ULong, A>>): Wheel<A> {
            require(options.isNotEmpty())
            require(options.all { it.first > 0UL })
            return Wheel(options.size, options.map { it.first }.toULongArray()) { i ->
                options[i.toInt()].second
            }
        }

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

        @JvmName("intWeighted")
        fun <A> weighted(vararg options: Pair<Int, A>): Wheel<A> = weighted(options.toList())

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

        @JvmName("doubleWeighted")
        fun <A> weighted(vararg options: Pair<Double, A>): Wheel<A> = weighted(options.toList())
    }
}
