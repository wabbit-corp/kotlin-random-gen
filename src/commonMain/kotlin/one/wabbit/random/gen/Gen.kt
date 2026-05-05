// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.random.gen

import kotlin.jvm.JvmName
import one.wabbit.data.Need
import one.wabbit.random.gen.util.Wheel

/**
 * Description of a random value generator.
 *
 * `Gen` values are interpreted by the sampling and checking functions in `Check.kt`. The tree keeps
 * generation declarative so values can be replayed from a [TapeSeed] and minimized after failures.
 */
sealed interface Gen<out A> {
    /** Generator that always rejects the current sample. */
    data object Fail : Gen<Nothing>

    /** Generator that has already produced [value]. */
    data class Done<out A>(val value: A) : Gen<A>

    /** Lazily expands to another generator. */
    data class Delay<out A>(val value: Need<Gen<A>>) : Gen<A>

    /** Adds a diagnostic [label] around [body]. */
    data class Label<out A>(val label: String, val body: Gen<A>) : Gen<A>

    /** Sequences [left] and feeds its value to [f]. A null result rejects the sample. */
    data class FlatMap<Z, out A>(val left: Gen<Z>, val f: (Z) -> Gen<A>?) : Gen<A>

    // data class ReadN(val n: Int) : Gen<ULong>
    /** Reads one random bit as a Boolean. */
    data object ChooseBool : Gen<Boolean>

    /** Draws an integer from [range]. */
    data class ChooseInt(val range: IntRange) : Gen<Int>

    /** Draws an unsigned integer from [range]. */
    data class ChooseUInt(val range: UIntRange) : Gen<UInt>

    /** Draws [width] raw random bits as a `ULong`. */
    data class ChooseBits(val width: Int) : Gen<ULong>

    /** Draws a floating-point value in `[0.0, 1.0)` using [bits] precision bits. */
    data class ChooseDouble(val bits: Int) : Gen<Double>

    // Choice among alternatives
    /** Draws one of the weighted generator [options]. */
    data class Choose<A>(val options: Wheel<Gen<A>>) : Gen<A>

    // Order-independent sequences
    /** Generates a list of [size] elements using the same [element] generator. */
    data class ListOf<A>(val size: Gen<Int>, val element: Gen<A>) : Gen<List<A>>

    // Order dependent sequences
    /** Generates a list where each element can depend on the elements generated before it. */
    data class SeqOf<A>(val size: Gen<Int>, val element: (List<A>) -> Gen<A>) : Gen<List<A>>

    /** Transforms values produced by this generator. */
    fun <B> map(f: (A) -> B): Gen<B> = FlatMap(this) { Done(f(it)) }

    /** Sequences this generator with another generator chosen from the produced value. */
    fun <B> flatMap(f: (A) -> Gen<B>): Gen<B> = FlatMap(this, f)

    /** Sequences this generator and keeps both the original value and the generated value. */
    fun <B> flatMapZip(f: (A) -> Gen<B>): Gen<Pair<A, B>> =
        FlatMap(this) { a -> f(a).map { b -> a to b } }

    /** Rejects samples that do not satisfy [p]. */
    fun filter(p: (A) -> Boolean): Gen<A> = FlatMap(this) { if (p(it)) Done(it) else null }

    /** Generates exactly [count] values from this generator. */
    fun repeat(count: Int): Gen<List<A>> = ListOf(Done(count), this)

    /** Generates a list whose length is produced by [count]. */
    fun repeat(count: Gen<Int>): Gen<List<A>> = ListOf(count, this)

    /** Runs this generator, then [that], and returns [that]'s value. */
    infix fun <B> zipRight(that: Gen<B>): Gen<B> = FlatMap(this) { that }

    /** Runs this generator, then [that], and keeps this generator's value. */
    infix fun <B> zipLeft(that: Gen<B>): Gen<A> = FlatMap(this) { a -> FlatMap(that) { Done(a) } }

    /** Generates both values and returns them as a pair. */
    infix fun <B> zip(that: Gen<B>): Gen<Pair<A, B>> =
        FlatMap(this) { a -> FlatMap(that) { b -> Done(a to b) } }

    /** Generates either null or a value from this generator with equal weight. */
    fun nullable(): Gen<A?> = Choose(Wheel.unweighted(listOf(Done(null), this)))

    /** Adds a diagnostic [label] to this generator. */
    fun label(label: String): Gen<A> = Label(label, this)

    /** Factory functions and common generator combinators. */
    companion object {
        /** Generator that produces [Unit]. */
        val unit: Gen<Unit> = Done(Unit)
        /** Generator that always rejects the current sample. */
        val fail: Gen<Nothing> = Fail

        /** Creates a generator that always returns [a]. */
        fun <A> const(a: A): Gen<A> = Done(a)

        /** Evaluates [a] when the generator is sampled. */
        fun <A> apply(a: () -> A): Gen<A> = FlatMap(Done(Unit)) { _ -> Done(a()) }

        /** Lazily constructs a generator when it is sampled. */
        fun <A> delay(a: () -> Gen<A>): Gen<A> = Delay(Need.apply { a() })

        /** Wraps [body] in a diagnostic [label]. */
        fun <A> label(label: String, body: Gen<A>): Gen<A> = Label(label, body)

        /** Lazily constructs [body] and wraps it in a diagnostic [label]. */
        fun <A> label(label: String, body: () -> Gen<A>): Gen<A> = Label(label, delay(body))

        /** Runs [side] whenever this generator produces a value, then returns that same value. */
        fun <A> Gen<A>.tap(side: (A) -> Unit): Gen<A> = flatMap { a ->
            side(a)
            Gen.const(a)
        }

        /** Defines a recursive generator by passing a lazily self-referential generator to [f]. */
        fun <A> recursive(f: (Gen<A>) -> Gen<A>): Gen<A> {
            class Recursive {
                lateinit var gen: Gen<A>
            }
            val r = Recursive()
            r.gen = delay { f(r.gen) }
            return r.gen
        }

        /** Generates a pair by sampling [a] and [b] in order. */
        fun <A, B> zip(a: Gen<A>, b: Gen<B>): Gen<Pair<A, B>> = a zip b

        /** Generates a triple by sampling [a], [b], and [c] in order. */
        fun <A, B, C> zip(a: Gen<A>, b: Gen<B>, c: Gen<C>): Gen<Triple<A, B, C>> =
            a.flatMap { a -> b.flatMap { b -> c.map { c -> Triple(a, b, c) } } }

        /** Maps one generated value through [f]. */
        fun <A, Z> map(a: Gen<A>, f: (A) -> Z): Gen<Z> = a.map(f)

        /** Maps two generated values through [f]. */
        fun <A, B, Z> map(a: Gen<A>, b: Gen<B>, f: (A, B) -> Z): Gen<Z> =
            zip(a, b).map { (a, b) -> f(a, b) }

        /** Maps three generated values through [f]. */
        fun <A, B, C, Z> map(a: Gen<A>, b: Gen<B>, c: Gen<C>, f: (A, B, C) -> Z): Gen<Z> =
            zip(a, b, c).map { (a, b, c) -> f(a, b, c) }

        /** Maps four generated values through [f]. */
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

        /** Flat-maps one generated value through [f]. */
        fun <A, Z> flatMap(a: Gen<A>, f: (A) -> Gen<Z>): Gen<Z> = a.flatMap { a -> f(a) }

        /** Flat-maps two generated values through [f]. */
        fun <A, B, Z> flatMap(a: Gen<A>, b: Gen<B>, f: (A, B) -> Gen<Z>): Gen<Z> =
            zip(a, b).flatMap { (a, b) -> f(a, b) }

        /** Flat-maps three generated values through [f]. */
        fun <A, B, C, Z> flatMap(a: Gen<A>, b: Gen<B>, c: Gen<C>, f: (A, B, C) -> Gen<Z>): Gen<Z> =
            zip(a, b, c).flatMap { (a, b, c) -> f(a, b, c) }

        /** Flat-maps four generated values through [f]. */
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

        /** Selects one value from [options] with equal probability. */
        fun <R> oneOf(options: List<R>): Gen<R> = Choose(Wheel.unweighted(options.map(::Done)))

        /** Selects one value from [options] with equal probability. */
        fun <R> oneOf(vararg options: R): Gen<R> = oneOf(options.toList())

        /** Selects one generator from [options] with equal probability, then samples it. */
        fun <R> oneOfGen(options: List<Gen<R>>): Gen<R> = Choose(Wheel.unweighted(options))

        /** Selects one generator from [options] with equal probability, then samples it. */
        fun <R> oneOfGen(vararg options: Gen<R>): Gen<R> = oneOfGen(options.toList())

        /** Selects a value using non-negative integer weights. */
        @JvmName("freqInt")
        fun <R> freq(options: List<Pair<Int, R>>): Gen<R> =
            Choose(Wheel.weighted(options.map { it.first to Done(it.second) }))

        /** Selects a value using non-negative floating-point weights. */
        @JvmName("freqDouble")
        fun <R> freq(options: List<Pair<Double, R>>): Gen<R> =
            Choose(Wheel.weighted(options.map { it.first to Done(it.second) }))

        /** Selects a generator using non-negative integer weights, then samples it. */
        @JvmName("freqGenInt")
        fun <R> freqGen(vararg options: Pair<Int, Gen<R>>): Gen<R> =
            Choose(Wheel.weighted(options.map { it.first to it.second }))

        /** Selects a generator using non-negative integer weights, then samples it. */
        @JvmName("freqGenInt")
        fun <R> freqGen(options: List<Pair<Int, Gen<R>>>): Gen<R> = Choose(Wheel.weighted(options))

        /** Selects a generator using non-negative floating-point weights, then samples it. */
        @JvmName("freqGenDouble")
        fun <R> freqGen(vararg options: Pair<Double, Gen<R>>): Gen<R> =
            Choose(Wheel.weighted(options.map { it.first to it.second }))

        /** Selects a generator using non-negative floating-point weights, then samples it. */
        @JvmName("freqGenDouble")
        fun <R> freqGen(options: List<Pair<Double, Gen<R>>>): Gen<R> =
            Choose(Wheel.weighted(options))

        /** Generates a list whose length is produced by [length] and elements by [element]. */
        fun <T> listOf(length: Gen<Int>, element: Gen<T>): Gen<List<T>> = ListOf(length, element)

        /** Generates a list with length drawn from [range] and elements from [element]. */
        fun <T> listOf(range: IntRange, element: Gen<T>): Gen<List<T>> = ListOf(int(range), element)

        /** Generates a list with exactly [count] elements from [gen]. */
        fun <R> listOf(count: Int, gen: Gen<R>): Gen<List<R>> = ListOf(Done(count), gen)

        /** Samples each generator in [list] and returns the generated values in order. */
        fun <R> zip(vararg list: Gen<R>): Gen<List<R>> =
            SeqOf(Done(list.size)) { lst -> list[lst.size] }

        /** Samples each generator in [list] and returns the generated values in order. */
        fun <R> zip(list: List<Gen<R>>): Gen<List<R>> =
            SeqOf(Done(list.size)) { lst -> list[lst.size] }

        /** Generates a list where each next element can depend on prior generated elements. */
        fun <R> unfold(length: Gen<Int>, list: (List<R>) -> Gen<R>): Gen<List<R>> =
            SeqOf(length, list)

        /** Generates a dependent list with length drawn from [range]. */
        fun <R> unfold(range: IntRange, list: (List<R>) -> Gen<R>): Gen<List<R>> =
            SeqOf(int(range), list)

        /** Generates a dependent list with exactly [count] elements. */
        fun <R> unfold(count: Int, list: (List<R>) -> Gen<R>): Gen<List<R>> =
            SeqOf(Done(count), list)

        /** Generates a string with length from [length] and characters from [char]. */
        fun string(length: Gen<Int>, char: Gen<Char>): Gen<String> =
            listOf(length, char).map { it.joinToString("") }

        /** Generates a string with length drawn from [range] and characters from [char]. */
        fun string(range: IntRange, char: Gen<Char>): Gen<String> = string(int(range), char)

        /** Generates a string with exactly [count] characters from [char]. */
        fun string(count: Int, char: Gen<Char>): Gen<String> = string(const(count), char)

        /** Generates a Unicode string with length from [length]. */
        fun string(length: Gen<Int>): Gen<String> =
            listOf(length, validUnicodeCodePoint).map { codePoints ->
                buildString {
                    for (cp in codePoints) {
                        append(codePointToString(cp))
                    }
                }
            }

        /** Generates a Unicode string with length drawn from [range]. */
        fun string(range: IntRange): Gen<String> = string(int(range))

        /** Generates a Unicode string with exactly [count] code points. */
        fun string(count: Int): Gen<String> = string(const(count))

        /** Small Unicode string generator with length in `0..8`. */
        val string
            get() = string(0..8)

        /**
         * Generates a deterministic bucketed function from [A] to [Z].
         *
         * Inputs are hashed by [a], mapped into a generated table of [buckets], and return values
         * are drawn from [returning].
         */
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

        /** Generates a deterministic bucketed two-argument function from `(A, B)` to [Z]. */
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
        /** Boolean generator backed by one random bit. */
        val bool: Gen<Boolean> = ChooseBool

        /** Draws [width] bits as a `ULong`. */
        fun bits(width: Int): Gen<ULong> {
            require(width in 1..64)
            return ChooseBits(width)
        }

        /** Draws an `Int` from [range]. */
        fun int(range: IntRange): Gen<Int> = ChooseInt(range)

        /** Draws a `UInt` from [range]. */
        fun uint(range: UIntRange): Gen<UInt> = ChooseUInt(range)

        /** Uniform `Double` in `[0.0, 1.0)` using 53 precision bits. */
        val uniformDouble: Gen<Double> = ChooseDouble(53)

        /** Uniform `Double` in `[0.0, 1.0)` using [bits] precision bits. */
        fun uniformDouble(bits: Int = 53): Gen<Double> = ChooseDouble(bits)

        /** Draws a character from [range]. */
        fun range(range: CharRange): Gen<Char> =
            int(range.first.code..range.last.code).map { it.toChar() }

        /** Draws an `Int` from [range]. */
        fun range(range: IntRange): Gen<Int> = int(range.first..range.last)

        /** Uniform signed byte generator. */
        val byte = int(Byte.MIN_VALUE..Byte.MAX_VALUE).map { it.toByte() }
        /** Uniform signed short generator. */
        val short = int(Short.MIN_VALUE..Short.MAX_VALUE).map { it.toShort() }
        /** Uniform signed int generator. */
        val int = int(Int.MIN_VALUE..Int.MAX_VALUE)
        /** Uniform unsigned int generator. */
        val uint = uint(UInt.MIN_VALUE..UInt.MAX_VALUE)
        /** Positive int generator. */
        val posInt = int(1..Int.MAX_VALUE)
        /** Non-negative int generator. */
        val nonNegInt = int(0..Int.MAX_VALUE)
        /** Printable ASCII character generator. */
        val printableAsciiChar = int(32..126).map { it.toChar() }
        /** Any Kotlin `Char` code-unit generator, including surrogate code units. */
        val anyChar = int(Char.MIN_VALUE.code..Char.MAX_VALUE.code).map { it.toChar() }

        private val validUnicodeCodePoint: Gen<Int> =
            oneOfGen(int(0x0000..0xD7FF), int(0xE000..0xFFFF), int(0x10000..0x10FFFF))

        private fun codePointToString(codePoint: Int): String {
            require(codePoint in 0..0x10FFFF) { "Invalid Unicode code point: $codePoint" }
            require(codePoint !in 0xD800..0xDFFF) {
                "Surrogate code point is not valid scalar value: $codePoint"
            }
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
        /** Generates a random subset of [it], preserving input order. */
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

        /** Builds recursive tree-shaped values from recursive [constructors]. */
        fun <T> tree(constructors: List<(Gen<T>) -> Gen<T>>): Gen<T> = recursive { self ->
            oneOfGen(constructors.map { cons -> cons(self) })
        }

        // Checkers and test runners
        /** Samples [ga] [count] times and runs [block] for successful samples. */
        fun <A : Any> foreach(ga: Gen<A>, count: Int = 100, block: (A) -> Unit) {
            ga.foreach(count = count) { a -> block(a) }
        }

        /** Samples two zipped generators [count] times and runs [block] for successful samples. */
        fun <A : Any, B : Any> foreach(
            ga: Gen<A>,
            gb: Gen<B>,
            count: Int = 100,
            block: (A, B) -> Unit,
        ) {
            (ga zip gb).foreach(count = count) { (a, b) -> block(a, b) }
        }

        /**
         * Samples three zipped generators [count] times and runs [block] for successful samples.
         */
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
