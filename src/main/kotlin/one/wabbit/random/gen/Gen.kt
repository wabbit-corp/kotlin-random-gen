package one.wabbit.random.gen

import one.wabbit.data.ConsList
import one.wabbit.data.LazyConsList
import one.wabbit.data.Need
import one.wabbit.data.consListOf
import java.util.*
import kotlin.math.*

//sealed interface Gen<R> {//
//    fun next(random: SplittableRandom): R {
//        when (this) {
//            is Pure -> return value
//            is FromInt -> {
//                if (from == to) return f(from)
//                return f(random.nextInt(from, to))
//            }
//            is OneOf -> {
//                assert(options.isNotEmpty())
//                assert(options.all { it.first >= 0.0 })
//
//                val total = options.sumOf { it.first }
//                val r = random.nextDouble() * total
//                var sum = 0.0
//                for ((weight, gen) in options) {
//                    sum += weight
//                    if (r < sum)
//                        return gen.next(random)
//                }
//
//                return options.last().second.next(random)
//            }
//            is Sequence<*> -> {
//                return list.map { it.next(random) } as R
//            }
//            is Map<*, *> -> {
//                return (f as (Any?) -> R)(gen.next(random))
//            }
//            is FlatMap<*, *> -> {
//                return (f as (Any?) -> Gen<R>)(gen.next(random)).next(random)
//            }
//        }
//    }
//
//    fun foreach(random: SplittableRandom, count: Int, f: (R) -> Unit): Unit {
//        repeat(count) {
//            f(next(random))
//        }
//    }
//
//    companion object {
//        fun <R> pure(value: R): Gen<R> =
//            Pure(value)
//        fun <R> oneOf(options: List<R>): Gen<R> {
//            return Gen.OneOf(options.map { Pair(1.0, Gen.Pure(it)) })
//        }
//        fun <R> freq(options: List<Pair<Double, R>>): Gen<R> {
//            return Gen.OneOf(options.map { Pair(it.first, Gen.Pure(it.second)) })
//        }
//        fun <R> freq1(options: List<Pair<Double, Gen<R>>>): Gen<R> {
//            return Gen.OneOf(options)
//        }
//        fun <R> sequence(list: List<Gen<R>>): Gen<List<R>> =
//            Sequence(list)
//
//        fun <R> repeat(count: Int, gen: Gen<R>): Gen<List<R>> =
//            Sequence(List(count) { gen })
//
//        fun int(from: Int, until: Int): Gen<Int> =
//            FromInt(from, until) { it }
//
//        fun <R> filter(gen: Gen<R>, f: (R) -> Boolean): Gen<R> {
//            return gen.flatMap { r ->
//                if (f(r))
//                    pure(r)
//                else
//                    filter(gen, f)
//            }
//        }
//
//        val anyChar = int(Char.MAX_VALUE.code, Char.MAX_VALUE.code)
//        val anyDefinedChar = filter(anyChar) { it.toChar().isDefined() }
//    }
//}

sealed interface Gen<out A> {
    data object Fail : Gen<Nothing>
    data class Done<out A>(val value: A) : Gen<A>
    data class Delay<out A>(val value: Need<Gen<A>>) : Gen<A>

//    data class FromInt<R>(val from: Int, val to: Int, val f: (Int) -> R) : Gen<R> {
//        init {
//            require(from <= to)
//        }
//    }
//
//    data class FromLong<R>(val from: Long, val to: Long, val f: (Long) -> R) : Gen<R> {
//        init {
//            require(from <= to)
//        }
//    }
//
//    data class FromUniform<R>(val f: (Double) -> R) : Gen<R>
//
//    data class OneOf<R>(val options: List<Pair<Double, Gen<R>>>) : Gen<R> {
//        init {
//            assert(options.isNotEmpty())
//            assert(options.all { it.first >= 0.0 })
//        }
//    }

    data class ReadN(val n: Int) : Gen<ULong>
    // data class Filter<A>(val value: Gen<A>, val predicate: (A) -> Boolean) : Gen<A>
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

        fun <A> pure(a: A): Gen<A> = Done(a)

        fun <A> apply(a: () -> A): Gen<A> =
            FlatMap(Done(Unit)) { Done(a()) }

        fun <A> delay(a: () -> Gen<A>): Gen<A> =
            Delay(Need.apply { a() })

        fun <R> sequence(list: List<Gen<R>>): Gen<List<R>> {
            val l: Gen<ConsList<R>> = list.foldRight(pure(consListOf())) { gen, acc ->
                gen.flatMap { h -> acc.map { t -> t.cons(h) } }
            }
            return l.map { it.toList() }
        }
        fun <R> sequence(list: LazyConsList<Gen<R>>): Gen<LazyConsList<R>> {
            return Delay(list.thunk.map {
                when (it) {
                    is LazyConsList.Nil ->
                        pure(LazyConsList.Nil)
                    is LazyConsList.Cons ->
                        // it.head : Gen<R>
                        // it.tail : LazyConsList<Gen<R>>
                        it.head.flatMap { h ->
                            sequence(it.tail).map { it.cons(h) }
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
    }
}
