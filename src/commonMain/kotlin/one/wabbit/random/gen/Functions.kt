// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.random.gen

import one.wabbit.random.gen.util.Hashing

/** Co-generator: stable 64-bit hash for values of A (used to bucketize inputs). */
fun interface CoGen<A> {
    /** Returns a stable hash used to choose function-table buckets for [a]. */
    fun hash(a: A): ULong

    /** Builds a co-generator for [B] by mapping values to [A] first. */
    fun <B> contramap(f: (B) -> A): CoGen<B> = CoGen { b -> this.hash(f(b)) }

    /** Combines this co-generator with [other] for pair values. */
    fun <B> zip(other: CoGen<B>): CoGen<Pair<A, B>> = CoGen { (x, y) ->
        Hashing.mixCombine(this.hash(x), other.hash(y))
    }

    /** Extends this co-generator to nullable values. */
    fun nullable(): CoGen<A?> = CoGen { a ->
        if (a == null) nullHash else Hashing.mixCombine(unitHash, this.hash(a))
    }

    /** Some handy instances + combinators. Extend as needed. */
    companion object {
        private val nullHash = Hashing.mix64(0UL)
        private val unitHash = Hashing.mix64(1UL)
        private val trueHash = Hashing.mix64(2UL)
        private val falseHash = Hashing.mix64(3UL)

        /** Co-generator for `Unit`; every value hashes to the same stable bucket. */
        val unit: CoGen<Unit> = CoGen { unitHash }
        /** Co-generator for Boolean values. */
        val bool: CoGen<Boolean> = CoGen { b -> if (b) trueHash else falseHash }
        /** Co-generator for signed byte values. */
        val byte: CoGen<Byte> = CoGen { b -> Hashing.mix64(b.toULong()) }
        /** Co-generator for signed short values. */
        val short: CoGen<Short> = CoGen { s -> Hashing.mix64(s.toULong()) }
        /** Co-generator for signed integer values. */
        val int: CoGen<Int> = CoGen { i -> Hashing.mix64(i.toULong()) }
        /** Co-generator for signed long values. */
        val long: CoGen<Long> = CoGen { l -> Hashing.mix64(l.toULong()) }
        /** Co-generator for UTF-16 character values. */
        val char: CoGen<Char> = CoGen { c -> Hashing.mix64(c.code.toULong()) }
        /** Co-generator for Float bit patterns. */
        val float: CoGen<Float> = CoGen { f -> Hashing.mix64(f.toRawBits().toULong()) }
        /** Co-generator for Double bit patterns. */
        val double: CoGen<Double> = CoGen { d -> Hashing.mix64(d.toRawBits().toULong()) }
        /** Co-generator for unsigned byte values. */
        val ubyte: CoGen<UByte> = CoGen { u -> Hashing.mix64(u.toULong()) }
        /** Co-generator for unsigned short values. */
        val ushort: CoGen<UShort> = CoGen { u -> Hashing.mix64(u.toULong()) }
        /** Co-generator for unsigned integer values. */
        val uint: CoGen<UInt> = CoGen { u -> Hashing.mix64(u.toULong()) }
        /** Co-generator for unsigned long values. */
        val ulong: CoGen<ULong> = CoGen { u -> Hashing.mix64(u) }

        /** Uses [Hashing.stableHash32] before widening and mixing to 64 bits. */
        fun <A> fromBuiltinHashCode(): CoGen<A> = CoGen { a ->
            Hashing.mix64(Hashing.stableHash32(a).toULong())
        }

        /** Uses the platform `hashCode` directly before widening and mixing to 64 bits. */
        fun <A> unsafeFromHashCode(): CoGen<A> = CoGen { a ->
            Hashing.mix64(a.hashCode().toULong())
        }

        /** Co-generator for strings based on the platform String hash contract. */
        val string: CoGen<String> = unsafeFromHashCode()
    }
}

/** A bucketed function: A -> C chosen by hashing inputs into [0, table.size). */
class TabFun1<A, Z>(
    private val salt: ULong,
    private val size: Int,
    private val table: (Int) -> Z,
    private val coA: CoGen<A>,
) : (A) -> Z {
    override fun invoke(a: A): Z {
        val n = size
        if (n <= 1) return table(0)
        val h = index(a, n)
        return table(h)
    }

    private fun index(a: A, n: Int): Int =
        Hashing.jumpConsistentHash(Hashing.mixCombine(salt, coA.hash(a)), n)
}

/** A bucketed function: (A,B) -> C chosen by hashing inputs into [0, table.size). */
class TabFun2<A, B, Z>(
    private val salt: ULong,
    private val size: Int,
    private val table: (Int) -> Z,
    private val coA: CoGen<A>,
    private val coB: CoGen<B>,
) : (A, B) -> Z {
    override fun invoke(a: A, b: B): Z {
        val n = size
        if (n <= 1) return table(0)
        val h = index(a, b, n)
        return table(h)
    }

    private fun index(a: A, b: B, n: Int): Int =
        Hashing.jumpConsistentHash(Hashing.mixCombine(salt, coA.hash(a), coB.hash(b)), n)
}
