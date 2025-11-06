package one.wabbit.random.gen

import one.wabbit.random.gen.util.Hashing

/** Co-generator: stable 64-bit hash for values of A (used to bucketize inputs). */
fun interface CoGen<A> {
    fun hash(a: A): ULong

    fun <B> contramap(f: (B) -> A): CoGen<B> = CoGen { b -> this.hash(f(b)) }

    fun <B> zip(other: CoGen<B>): CoGen<Pair<A, B>> = CoGen { (x, y) ->
        Hashing.mixCombine(this.hash(x), other.hash(y))
    }

    fun nullable(): CoGen<A?> = CoGen { a ->
        if (a == null) nullHash else Hashing.mixCombine(unitHash, this.hash(a))
    }

    /** Some handy instances + combinators. Extend as needed. */
    companion object {
        private val nullHash = Hashing.mix64(0UL)
        private val unitHash = Hashing.mix64(1UL)
        private val trueHash = Hashing.mix64(2UL)
        private val falseHash = Hashing.mix64(3UL)

        val unit: CoGen<Unit> = CoGen { unitHash }
        val bool: CoGen<Boolean> = CoGen { b -> if (b) trueHash else falseHash }
        val byte: CoGen<Byte> = CoGen { b -> Hashing.mix64(b.toULong()) }
        val short: CoGen<Short> = CoGen { s -> Hashing.mix64(s.toULong()) }
        val int: CoGen<Int> = CoGen { i -> Hashing.mix64(i.toULong()) }
        val long: CoGen<Long> = CoGen { l -> Hashing.mix64(l.toULong()) }
        val char: CoGen<Char> = CoGen { c -> Hashing.mix64(c.code.toULong()) }
        val float: CoGen<Float> = CoGen { f -> Hashing.mix64(f.toRawBits().toULong()) }
        val double: CoGen<Double> = CoGen { d -> Hashing.mix64(d.toRawBits().toULong()) }
        val ubyte: CoGen<UByte> = CoGen { u -> Hashing.mix64(u.toULong()) }
        val ushort: CoGen<UShort> = CoGen { u -> Hashing.mix64(u.toULong()) }
        val uint: CoGen<UInt> = CoGen { u -> Hashing.mix64(u.toULong()) }
        val ulong: CoGen<ULong> = CoGen { u -> Hashing.mix64(u) }

        fun <A> fromBuiltinHashCode(): CoGen<A> = CoGen { a ->
            Hashing.mix64(Hashing.stableHash32(a).toULong())
        }

        fun <A> unsafeFromHashCode(): CoGen<A> = CoGen { a ->
            Hashing.mix64(a.hashCode().toULong())
        }

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
