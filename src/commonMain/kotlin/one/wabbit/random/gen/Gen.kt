package one.wabbit.random.gen

import one.wabbit.data.Need
import kotlin.jvm.JvmName
import one.wabbit.random.gen.util.Wheel

sealed interface Gen<out A> {
    data object Fail : Gen<Nothing>

    data class Done<out A>(val value: A) : Gen<A>

    data class Delay<out A>(val value: Need<Gen<A>>) : Gen<A>

    data class Label<out A>(val label: String, val body: Gen<A>) : Gen<A>

    data class FlatMap<Z, out A>(val left: Gen<Z>, val f: (Z) -> Gen<A>?) : Gen<A>

    // data class ReadN(val n: Int) : Gen<ULong>
    data object ChooseBool : Gen<Boolean>

    data class ChooseInt(val range: IntRange) : Gen<Int>

    data class ChooseUInt(val range: UIntRange) : Gen<UInt>

    data class ChooseBits(val width: Int) : Gen<ULong>

    data class ChooseDouble(val bits: Int) : Gen<Double>

    // Choice among alternatives
    data class Choose<A>(val options: Wheel<Gen<A>>) : Gen<A>

    // Order-independent sequences
    data class ListOf<A>(val size: Gen<Int>, val element: Gen<A>) : Gen<List<A>>

    // Order dependent sequences
    data class SeqOf<A>(val size: Gen<Int>, val element: (List<A>) -> Gen<A>) : Gen<List<A>>

    fun <B> map(f: (A) -> B): Gen<B> = FlatMap(this) { Done(f(it)) }

    fun <B> flatMap(f: (A) -> Gen<B>): Gen<B> = FlatMap(this, f)

    fun <B> flatMapZip(f: (A) -> Gen<B>): Gen<Pair<A, B>> =
        FlatMap(this) { a -> f(a).map { b -> a to b } }

    fun filter(p: (A) -> Boolean): Gen<A> = FlatMap(this) { if (p(it)) Done(it) else null }

    fun repeat(count: Int): Gen<List<A>> = ListOf(Done(count), this)

    fun repeat(count: Gen<Int>): Gen<List<A>> = ListOf(count, this)

    infix fun <B> zipRight(that: Gen<B>): Gen<B> = FlatMap(this) { that }

    infix fun <B> zipLeft(that: Gen<B>): Gen<A> = FlatMap(this) { a -> FlatMap(that) { Done(a) } }

    infix fun <B> zip(that: Gen<B>): Gen<Pair<A, B>> =
        FlatMap(this) { a -> FlatMap(that) { b -> Done(a to b) } }

    fun nullable(): Gen<A?> = Choose(Wheel.unweighted(listOf(Done(null), this)))

    fun label(label: String): Gen<A> = Label(label, this)

    companion object {
        val unit: Gen<Unit> = Done(Unit)
        val fail: Gen<Nothing> = Fail

        fun <A> const(a: A): Gen<A> = Done(a)

        fun <A> apply(a: () -> A): Gen<A> = FlatMap(Done(Unit)) { _ -> Done(a()) }

        fun <A> delay(a: () -> Gen<A>): Gen<A> = Delay(Need.apply { a() })

        fun <A> label(label: String, body: Gen<A>): Gen<A> = Label(label, body)

        fun <A> label(label: String, body: () -> Gen<A>): Gen<A> = Label(label, delay(body))

        // Side-effecting combinator
        fun <A> Gen<A>.tap(side: (A) -> Unit): Gen<A> = flatMap { a ->
            side(a)
            Gen.const(a)
        }

        // Recursive definitions
        fun <A> recursive(f: (Gen<A>) -> Gen<A>): Gen<A> {
            class Recursive {
                lateinit var gen: Gen<A>
            }
            val r = Recursive()
            r.gen = delay { f(r.gen) }
            return r.gen
        }

        // Zip combinators
        fun <A, B> zip(a: Gen<A>, b: Gen<B>): Gen<Pair<A, B>> = a zip b

        fun <A, B, C> zip(a: Gen<A>, b: Gen<B>, c: Gen<C>): Gen<Triple<A, B, C>> =
            a.flatMap { a -> b.flatMap { b -> c.map { c -> Triple(a, b, c) } } }

        // Map combinators
        fun <A, Z> map(a: Gen<A>, f: (A) -> Z): Gen<Z> = a.map(f)

        fun <A, B, Z> map(a: Gen<A>, b: Gen<B>, f: (A, B) -> Z): Gen<Z> =
            zip(a, b).map { (a, b) -> f(a, b) }

        fun <A, B, C, Z> map(a: Gen<A>, b: Gen<B>, c: Gen<C>, f: (A, B, C) -> Z): Gen<Z> =
            zip(a, b, c).map { (a, b, c) -> f(a, b, c) }

        fun <A, B, C, D, Z> map(
            a: Gen<A>,
            b: Gen<B>,
            c: Gen<C>,
            d: Gen<D>,
            f: (A, B, C, D) -> Z,
        ): Gen<Z> =
            zip(a, b, zip(c, d)).map { (a, b, cd) ->
                val (c, d) = cd
                f(a, b, c, d)
            }

        // FlatMap combinators
        fun <A, Z> flatMap(a: Gen<A>, f: (A) -> Gen<Z>): Gen<Z> = a.flatMap { a -> f(a) }

        fun <A, B, Z> flatMap(a: Gen<A>, b: Gen<B>, f: (A, B) -> Gen<Z>): Gen<Z> =
            zip(a, b).flatMap { (a, b) -> f(a, b) }

        fun <A, B, C, Z> flatMap(a: Gen<A>, b: Gen<B>, c: Gen<C>, f: (A, B, C) -> Gen<Z>): Gen<Z> =
            zip(a, b, c).flatMap { (a, b, c) -> f(a, b, c) }

        fun <A, B, C, D, Z> flatMap(
            a: Gen<A>,
            b: Gen<B>,
            c: Gen<C>,
            d: Gen<D>,
            f: (A, B, C, D) -> Gen<Z>,
        ): Gen<Z> =
            zip(a, b, zip(c, d)).flatMap { (a, b, cd) ->
                val (c, d) = cd
                f(a, b, c, d)
            }

        // Choice combinators
        fun <R> oneOf(options: List<R>): Gen<R> = Choose(Wheel.unweighted(options.map(::Done)))

        fun <R> oneOf(vararg options: R): Gen<R> = oneOf(options.toList())

        fun <R> oneOfGen(options: List<Gen<R>>): Gen<R> = Choose(Wheel.unweighted(options))

        fun <R> oneOfGen(vararg options: Gen<R>): Gen<R> = oneOfGen(options.toList())

        @JvmName("freqInt")
        fun <R> freq(options: List<Pair<Int, R>>): Gen<R> =
            Choose(Wheel.weighted(options.map { it.first to Done(it.second) }))

        @JvmName("freqDouble")
        fun <R> freq(options: List<Pair<Double, R>>): Gen<R> =
            Choose(Wheel.weighted(options.map { it.first to Done(it.second) }))

        @JvmName("freqGenInt")
        fun <R> freqGen(vararg options: Pair<Int, Gen<R>>): Gen<R> =
            Choose(Wheel.weighted(options.map { it.first to it.second }))

        @JvmName("freqGenInt")
        fun <R> freqGen(options: List<Pair<Int, Gen<R>>>): Gen<R> = Choose(Wheel.weighted(options))

        @JvmName("freqGenDouble")
        fun <R> freqGen(vararg options: Pair<Double, Gen<R>>): Gen<R> =
            Choose(Wheel.weighted(options.map { it.first to it.second }))

        @JvmName("freqGenDouble")
        fun <R> freqGen(options: List<Pair<Double, Gen<R>>>): Gen<R> =
            Choose(Wheel.weighted(options))

        // Sequence combinators
        fun <T> listOf(length: Gen<Int>, element: Gen<T>): Gen<List<T>> = ListOf(length, element)

        fun <T> listOf(range: IntRange, element: Gen<T>): Gen<List<T>> = ListOf(int(range), element)

        fun <R> listOf(count: Int, gen: Gen<R>): Gen<List<R>> = ListOf(Done(count), gen)

        fun <R> zip(vararg list: Gen<R>): Gen<List<R>> =
            SeqOf(Done(list.size)) { lst -> list[lst.size] }

        fun <R> zip(list: List<Gen<R>>): Gen<List<R>> =
            SeqOf(Done(list.size)) { lst -> list[lst.size] }

        fun <R> unfold(length: Gen<Int>, list: (List<R>) -> Gen<R>): Gen<List<R>> =
            SeqOf(length, list)

        fun <R> unfold(range: IntRange, list: (List<R>) -> Gen<R>): Gen<List<R>> =
            SeqOf(int(range), list)

        fun <R> unfold(count: Int, list: (List<R>) -> Gen<R>): Gen<List<R>> =
            SeqOf(Done(count), list)

        fun string(length: Gen<Int>, char: Gen<Char>): Gen<String> =
            listOf(length, char).map { it.joinToString("") }

        fun string(range: IntRange, char: Gen<Char>): Gen<String> = string(int(range), char)

        fun string(count: Int, char: Gen<Char>): Gen<String> = string(const(count), char)

        fun string(length: Gen<Int>): Gen<String> =
            listOf(length, validUnicodeCodePoint).map { codePoints ->
                buildString {
                    for (cp in codePoints) {
                        append(codePointToString(cp))
                    }
                }
            }

        fun string(range: IntRange): Gen<String> = string(int(range))

        fun string(count: Int): Gen<String> = string(const(count))

        val string
            get() = string(0..8)

        // Functions
        fun <A, Z> func(
            returning: Gen<Z>,
            a: CoGen<A> = CoGen.fromBuiltinHashCode(),
            buckets: Gen<Int> = int(1..8),
        ): Gen<(A) -> Z> =
            bits(64)
                .flatMap { salt ->
                    listOf(buckets, returning).map { table ->
                        TabFun1(salt, table.size, table::get, a)
                    }
                }
                .label("func")

        fun <A, B, Z> func2(
            returning: Gen<Z>,
            a: CoGen<A> = CoGen.fromBuiltinHashCode(),
            b: CoGen<B> = CoGen.fromBuiltinHashCode(),
            buckets: Gen<Int> = int(1..8),
        ): Gen<(A, B) -> Z> =
            bits(64)
                .flatMap { salt ->
                    listOf(buckets, returning).map { table ->
                        TabFun2(salt, table.size, table::get, a, b)
                    }
                }
                .label("func2")

        // Primitives
        val bool: Gen<Boolean> = ChooseBool

        fun bits(width: Int): Gen<ULong> {
            require(width in 1..64)
            return ChooseBits(width)
        }

        fun int(range: IntRange): Gen<Int> = ChooseInt(range)

        fun uint(range: UIntRange): Gen<UInt> = ChooseUInt(range)

        val uniformDouble: Gen<Double> = ChooseDouble(53)

        fun uniformDouble(bits: Int = 53): Gen<Double> = ChooseDouble(bits)

        fun range(range: CharRange): Gen<Char> =
            int(range.first.code..range.last.code).map { it.toChar() }

        fun range(range: IntRange): Gen<Int> = int(range.first..range.last)

        val byte = int(Byte.MIN_VALUE..Byte.MAX_VALUE).map { it.toByte() }
        val short = int(Short.MIN_VALUE..Short.MAX_VALUE).map { it.toShort() }
        val int = int(Int.MIN_VALUE..Int.MAX_VALUE)
        val uint = uint(UInt.MIN_VALUE..UInt.MAX_VALUE)
        val posInt = int(1..Int.MAX_VALUE)
        val nonNegInt = int(0..Int.MAX_VALUE)
        val printableAsciiChar = int(32..126).map { it.toChar() }
        val anyChar = int(Char.MIN_VALUE.code..Char.MAX_VALUE.code).map { it.toChar() }

        private val validUnicodeCodePoint: Gen<Int> =
            oneOfGen(int(0x0000..0xD7FF), int(0xE000..0xFFFF), int(0x10000..0x10FFFF))

        private fun codePointToString(codePoint: Int): String {
            require(codePoint in 0..0x10FFFF) { "Invalid Unicode code point: $codePoint" }
            require(codePoint !in 0xD800..0xDFFF) { "Surrogate code point is not valid scalar value: $codePoint" }
            return if (codePoint <= 0xFFFF) {
                codePoint.toChar().toString()
            } else {
                val value = codePoint - 0x10000
                val high = ((value shr 10) + 0xD800).toChar()
                val low = ((value and 0x3FF) + 0xDC00).toChar()
                charArrayOf(high, low).concatToString()
            }
        }

        // Other combinators
        fun <T> subset(it: Iterable<T>): Gen<List<T>> {
            val list = it.toList()
            return Gen.listOf(list.size, bool).map {
                val result = mutableListOf<T>()
                for (i in it.indices) {
                    if (it[i]) {
                        result.add(list[i])
                    }
                }
                result
            }
        }

        fun <T> tree(constructors: List<(Gen<T>) -> Gen<T>>): Gen<T> = recursive { self ->
            oneOfGen(constructors.map { cons -> cons(self) })
        }

        // Checkers and test runners
        fun <A : Any> foreach(ga: Gen<A>, count: Int = 100, block: (A) -> Unit) {
            ga.foreach(count = count) { a -> block(a) }
        }

        fun <A : Any, B : Any> foreach(
            ga: Gen<A>,
            gb: Gen<B>,
            count: Int = 100,
            block: (A, B) -> Unit,
        ) {
            (ga zip gb).foreach(count = count) { (a, b) -> block(a, b) }
        }

        fun <A : Any, B : Any, C : Any> foreach(
            ga: Gen<A>,
            gb: Gen<B>,
            gc: Gen<C>,
            count: Int = 100,
            block: (A, B, C) -> Unit,
        ) {
            ((ga zip gb) zip gc).foreach(count = count) { (ab, c) ->
                val (a, b) = ab
                block(a, b, c)
            }
        }
    }
}
