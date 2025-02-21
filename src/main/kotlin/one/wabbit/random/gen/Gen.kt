package one.wabbit.random.gen

import one.wabbit.data.ConsList
import one.wabbit.data.LazyList
import one.wabbit.data.Need
import one.wabbit.data.consListOf
import kotlin.math.*

sealed interface Gen<out A> {
    data object Fail : Gen<Nothing>
    data class Done<out A>(val value: A) : Gen<A>
    data class Delay<out A>(val value: Need<Gen<A>>) : Gen<A>

    data class ReadN(val n: Int) : Gen<ULong>
    data class FlatMap<Z, out A>(val left: Gen<Z>, val f: (Z) -> Gen<A>?) : Gen<A>

    fun <B> map(f: (A) -> B): Gen<B> =
        FlatMap(this) { Done(f(it)) }

    fun <B> flatMap(f: (A) -> Gen<B>): Gen<B> =
        FlatMap(this, f)

    fun <B> flatMapZip(f: (A) -> Gen<B>): Gen<Pair<A, B>> =
        FlatMap(this) { a -> f(a).map { b -> a to b } }

    fun filter(p: (A) -> Boolean): Gen<A> =
        FlatMap(this) { if (p(it)) Done(it) else null }

    fun repeat(count: Int): Gen<List<A>> =
        Gen.repeat(count, this)
    fun repeat(count: Gen<Int>): Gen<List<A>> =
        count.flatMap { count -> Gen.repeat(count, this) }

    infix fun <B> zipLeft(that: Gen<B>): Gen<B> =
        FlatMap(this) { that }

    infix fun <B> zipRight(that: Gen<B>): Gen<A> =
        FlatMap(this) { a -> FlatMap(that) { Done(a) } }

    infix fun <B> zip(that: Gen<B>): Gen<Pair<A, B>> =
        FlatMap(this) { a -> FlatMap(that) { b -> Done(a to b) } }

    fun nullable(): Gen<A?> {
        return oneOfGen(Done(null), map { it })
    }

    companion object {
        val unit: Gen<Unit> = Done(Unit)

        fun <A> const(a: A): Gen<A> = Done(a)

        fun <A> apply(a: () -> A): Gen<A> =
            FlatMap(Done(Unit)) { _ -> Done(a()) }

        fun <A> delay(a: () -> Gen<A>): Gen<A> =
            Delay(Need.apply { a() })

        fun <A> recursive(f: (Gen<A>) -> Gen<A>): Gen<A> {
            class Recursive {
                lateinit var gen: Gen<A>
            }
            val r = Recursive()
            r.gen = delay { f(r.gen) }
            return r.gen
        }

        fun <R> sequence(list: List<Gen<R>>): Gen<List<R>> {
            val l: Gen<ConsList<R>> = list.foldRight(const(consListOf())) { gen, acc ->
                gen.flatMap { h -> acc.map { t -> t.cons(h) } }
            }
            return l.map { it.toList() }
        }
        fun <R> sequence(list: LazyList<Gen<R>>): Gen<LazyList<R>> {
            return Delay(list.thunk.map {
                when (it) {
                    is LazyList.Nil ->
                        const(LazyList.Nil)
                    is LazyList.Cons ->
                        // it.head : Gen<R>
                        // it.tail : LazyConsList<Gen<R>>
                        it.head.flatMap { h ->
                            sequence(it.tail).map { it.prepend(h) }
                        }
                }
            })
        }

        fun <R> repeat(count: Int, gen: Gen<R>): Gen<List<R>> =
            sequence(List(count) { gen })
        fun <R> repeat(count: Gen<Int>, gen: Gen<R>): Gen<List<R>> =
            count.flatMap { count -> sequence(List(count) { gen }) }

        val bool: Gen<Boolean> =
            ReadN(1).map { it % 2UL == 0UL }
            // FromInt(0, 1) { it == 1 }

        fun int(range: IntRange): Gen<Int> {
            val first = range.first
            val last = range.last
            require(first <= last)

            if (first == last)
                return Done(first)

            if (first == Int.MIN_VALUE && last == Int.MAX_VALUE)
                return ReadN(32).map { it.toInt() }

            val m = last.toUInt() - first.toUInt()
            return uint(0u..m).map { first + it.toInt() }
        }

        fun uint(range: UIntRange): Gen<UInt> {
            val first = range.first
            val last = range.last
            require(first <= last)

            if (first == last)
                return Done(first)

            if (first == 0U && last == UInt.MAX_VALUE)
                return ReadN(32).map { it.toUInt() }

            val m = last - first + 1U
            if (m.countOneBits() == 1) {
                // d is a power of 2
                val p = 31 - m.countLeadingZeroBits()
                return ReadN(p).map { first + it.toUInt() }
            } else {
                // Suppose you want to roll d7 using d6.
                // Two rolls give 6×6=5×7+1 possible outcomes. Just split them into 7 equally likely groups except
                // for one outcome and re-roll if you get that.

                // https://math.stackexchange.com/questions/1868680/creating-unusual-probabilities-with-a-single-dice-using-the-minimal-number-of-e/1868685#1868685
                // Set n:=1 and x:=0. [n is the number of branches; x is the branch index (0-based).]
                // Repeat:
                //   Roll the k-dice once and let p be the outcome.
                //   Set n := k n and x := k x + p. [Expand every branch and move down a random one.]
                //   If n ≥ m then: [Enough to have m equal parts.]
                //     Let a, b be integers such that n = a m + b and 0 ≤ b < m. [a is the size of each part.]
                //     If x < a * m then: Return x mod m. [Falls into one of the equal parts.]
                //     Otherwise: Set n := b and x := x − a * m. [Falls into the leftover branches.]

                fun go(n: ULong, x: ULong): Gen<UInt> {
                    // Suppose you want to roll m using 2^n.
                    // We need to choose k0 such that n * 2^k0 >= m.
                    val k0 = ceil(log2(m.toDouble() / n.toDouble())).toInt()
                    val k = 1UL shl k0

                    return ReadN(k0).flatMap { p ->
                        val n0 = n
                        val x0 = x
                        val n = k * n
                        val x = k * x + p.toUInt()

//                        println("m=$m, n0=$n0, n=$n, x0=$x0, x=$x, k0=$k0, k=$k, p=$p")
//                        Thread.sleep(1000)

                        if (n >= m) {
                            val a = n / m
                            val b = n % m
                            if (x < a * m) {
                                Done((first + x % m).toUInt())
                            } else {
                                go(b, x - a * m)
                            }
                        } else {
                            go(n, x)
                        }
                    }
                }

                return go(1U, 0U)
            }
        }

        fun uniform(eps: Double = 0.0): Gen<Double> {
            if (eps == 0.0) {
                return ReadN(53).map { it.toDouble() / (1L shl 53) }
            } else {
                val bits = -(Math.log(eps) / Math.log(2.0)).roundToInt() + 1
                return ReadN(bits).map { it.toDouble() / (1L shl bits) }
            }
        }

        fun range(range: CharRange): Gen<Char> =
            int(range.first.code .. range.last.code).map { it.toChar() }
        fun range(range: IntRange): Gen<Int> =
            int(range.first .. range.last)

        fun <T> listOf(gen: Gen<T>, maxSize: Int = 5, minSize: Int = 0): Gen<List<T>> =
            (int(minSize..maxSize) zip int(minSize..maxSize))
                .flatMap { (s1, s2) -> repeat(minOf(s1, s2), gen) }
        fun <T> listOf(gen: Gen<T>, range: IntRange): Gen<List<T>> =
            (int(range) zip int(range)).flatMap { (s1, s2) -> repeat(minOf(s1, s2), gen) }

        fun <R> oneOf(vararg options: R): Gen<R> =
            oneOf(options.toList())
        fun <R> oneOf(options: List<R>): Gen<R> =
            int(options.indices).map { options[it] }

        fun <R> oneOfGen(vararg options: Gen<R>): Gen<R> =
            oneOf(options.toList()).flatMap { it }
        fun <R> oneOfGen(options: List<Gen<R>>): Gen<R> =
            oneOf(options).flatMap { it }

        @JvmName("freqInt")
        fun <R> freq(options: List<Pair<Int, R>>): Gen<R> {
            require(options.isNotEmpty())
            require(options.all { it.first >= 0 })

            val total = options.sumOf { it.first }
            return int(0..<total).map {
                var i = it
                for ((weight, value) in options) {
                    if (i < weight)
                        return@map value
                    i -= weight
                }
                error("unreachable")
            }
        }

        @JvmName("freqDouble")
        fun <R> freq(options: List<Pair<Double, R>>): Gen<R> {
            require(options.isNotEmpty())
            require(options.all { it.first >= 0.0 })

            val total = options.sumOf { it.first }
            return uniform().map {
                var i = it * total
                for ((weight, value) in options) {
                    if (i < weight)
                        return@map value
                    i -= weight
                }
                // error("unreachable")
                return@map options.last().second
            }
        }

        @JvmName("freqGenInt")
        fun <R> freqGen(vararg options: Pair<Int, Gen<R>>): Gen<R> {
            return freq(options.toList()).flatMap { it }
        }

        @JvmName("freqGenInt")
        fun <R> freqGen(options: List<Pair<Int, Gen<R>>>): Gen<R> {
            return freq(options).flatMap { it }
        }

        @JvmName("freqGenDouble")
        fun <R> freqGen(vararg options: Pair<Double, Gen<R>>): Gen<R> {
            return freq(options.toList()).flatMap { it }
        }

        @JvmName("freqGenDouble")
        fun <R> freqGen(options: List<Pair<Double, Gen<R>>>): Gen<R> {
            return freq(options).flatMap { it }
        }

        fun string(length: Gen<Int>, char: Gen<Char>): Gen<String> =
            length.flatMap { len ->
                sequence(List(len) { char }).map { it.joinToString("") }
            }
        fun string(length: Int, char: Gen<Char>): Gen<String> =
            string(Done(length), char)
        val string = int(0..5).flatMap { repeat(it, anyChar) }.map { it.joinToString("") }

        val byte = int(Byte.MIN_VALUE..Byte.MAX_VALUE).map { it.toByte() }
        val short = int(Short.MIN_VALUE..Short.MAX_VALUE).map { it.toShort() }
        val int = int(Int.MIN_VALUE..Int.MAX_VALUE)
        val uint = uint(UInt.MIN_VALUE..UInt.MAX_VALUE)
        val posInt = int(1..Int.MAX_VALUE)
        val nonNegInt = int(0..Int.MAX_VALUE)
        val anyChar = int(Char.MIN_VALUE.code..Char.MAX_VALUE.code).map { it.toChar() }

        fun <A, Z> map(a: Gen<A>, f: (A) -> Z): Gen<Z> =
            a.map { a -> f(a) }
        fun <A, B, Z> map(a: Gen<A>, b: Gen<B>, f: (A, B) -> Z): Gen<Z> =
            a.flatMap { a -> b.map { b -> f(a, b) } }
        fun <A, B, C, Z> map(a: Gen<A>, b: Gen<B>, c: Gen<C>, f: (A, B, C) -> Z): Gen<Z> =
            a.flatMap { a -> b.flatMap { b -> c.map { c -> f(a, b, c) } } }
        fun <A, B, C, D, Z> map(a: Gen<A>, b: Gen<B>, c: Gen<C>, d: Gen<D>, f: (A, B, C, D) -> Z): Gen<Z> =
            a.flatMap { a -> b.flatMap { b -> c.flatMap { c -> d.map { d -> f(a, b, c, d) } } } }

        fun <A, Z> flatMap(a: Gen<A>, f: (A) -> Gen<Z>): Gen<Z> =
            a.flatMap { a -> f(a) }
        fun <A, B, Z> flatMap(a: Gen<A>, b: Gen<B>, f: (A, B) -> Gen<Z>): Gen<Z> =
            a.flatMap { a -> b.flatMap { b -> f(a, b) } }
        fun <A, B, C, Z> flatMap(a: Gen<A>, b: Gen<B>, c: Gen<C>, f: (A, B, C) -> Gen<Z>): Gen<Z> =
            a.flatMap { a -> b.flatMap { b -> c.flatMap { c -> f(a, b, c) } } }
        fun <A, B, C, D, Z> flatMap(a: Gen<A>, b: Gen<B>, c: Gen<C>, d: Gen<D>, f: (A, B, C, D) -> Gen<Z>): Gen<Z> =
            a.flatMap { a -> b.flatMap { b -> c.flatMap { c -> d.flatMap { d -> f(a, b, c, d) } } } }

        fun <A : Any> foreach(ga: Gen<A>, count: Int = 100, block: (A) -> Unit) {
            ga.foreach(count = count) { a -> block(a) }
        }
        fun <A : Any, B : Any> foreach(ga: Gen<A>, gb: Gen<B>, count: Int = 100, block: (A, B) -> Unit) {
            (ga zip gb).foreach(count = count) { (a, b) -> block(a, b) }
        }
        fun <A : Any, B : Any, C : Any> foreach(ga: Gen<A>, gb: Gen<B>, gc: Gen<C>, count: Int = 100, block: (A, B, C) -> Unit) {
            ((ga zip gb) zip gc).foreach(count = count) { (ab, c) ->
                val (a, b) = ab
                block(a, b, c)
            }
        }
    }
}