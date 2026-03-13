package one.wabbit.random.gen

import kotlin.random.Random
import one.wabbit.data.Need
import one.wabbit.random.L64X128Random
import one.wabbit.random.gen.RunResult.*
import one.wabbit.random.gen.util.Codecs
import one.wabbit.random.gen.util.MutableBitDeque
import one.wabbit.random.gen.util.unsafeCast

interface BitSource {
    fun next(bits: Int): ULong

    fun available(): Long

    val pos: ULong

    fun nextAnnotationId(): ULong

    fun annotate(anno: TapeAnnotation): Unit

    companion object {
        fun of(random: Random): BitSource =
            object : BitSource {
                override var pos: ULong = 0UL

                override fun next(bits: Int): ULong {
                    require(bits in 0..64) { "Requested $bits bits, but must be within [0..64]." }

                    val raw = random.nextLong().toULong()
                    val result =
                        if (bits == 64) {
                            raw
                        } else {
                            raw and ((1UL shl bits) - 1UL)
                        }
                    pos += bits.toULong()
                    return result
                }

                override fun available(): Long = Long.MAX_VALUE

                override fun nextAnnotationId(): ULong = 0uL

                override fun annotate(anno: TapeAnnotation) {}
            }

        fun of(tape: RawTapeReader, limit: Long = Long.MAX_VALUE): BitSource =
            object : BitSource {
                override var pos: ULong = tape.read.toULong()

                override fun next(bits: Int): ULong = tape.read(bits)

                override fun available(): Long = limit - tape.read

                override fun nextAnnotationId(): ULong = 0uL

                override fun annotate(anno: TapeAnnotation) {}
            }
    }
}

sealed interface RunResult<out A> {
    data class Ok<out A>(val value: A) : RunResult<A>

    data object Eof : RunResult<Nothing>

    data object Filtered : RunResult<Nothing>
}

data class WithTape<out A>(val tape: RawTapeReader, val result: A)

fun <A> Gen<A>.sampleR(source: BitSource): RunResult<A> {
    fun readN(n: Int): ULong? {
        require(n in 0..64)
        if (source.available() < n) return null
        if (n == 0) return 0u
        return source.next(n)
    }

    val rdr = Codecs.ReadBits { n -> readN(n) }

    val stack = mutableListOf<(Any?) -> Gen<Any?>?>()
    var current: Gen<Any?> = this

    while (true) {
        val r =
            when (current) {
                is Gen.Fail -> return RunResult.Filtered
                is Gen.Delay -> {
                    current = current.value.value
                    continue
                }
                is Gen.Label -> {
                    current = current.body
                    continue
                }
                is Gen.FlatMap<*, Any?> -> {
                    stack.add(unsafeCast(current.f))
                    current = current.left
                    continue
                }
                is Gen.Choose<*> -> {
                    val w = current.options
                    val n = Codecs.readUint(0U..(w.total - 1UL).toUInt(), rdr) ?: return RunResult.Eof
                    current = w.draw(n.toULong())
                    continue
                }
                is Gen.ListOf<*> -> {
                    val lenGen = current.size
                    val elemGen = current.element
                    current =
                        lenGen.flatMap { len ->
                            if (len < 0) {
                                Gen.const(emptyList())
                            } else {
                                fun go(i: Int, acc: List<Any?>): Gen<List<Any?>> =
                                    if (i >= len) {
                                        Gen.const(acc)
                                    } else {
                                        elemGen.flatMap { e -> go(i + 1, acc + e) }
                                    }
                                go(0, emptyList())
                            }
                        }
                    continue
                }
                is Gen.SeqOf<*> -> {
                    val lenGen = current.size
                    val elemGen: (List<Any?>) -> Gen<Any?> = unsafeCast(current.element)
                    current =
                        lenGen.flatMap { len ->
                            if (len < 0) {
                                Gen.const(emptyList())
                            } else {
                                fun go(i: Int, acc: List<Any?>): Gen<List<Any?>> =
                                    if (i >= len) {
                                        Gen.const(acc)
                                    } else {
                                        elemGen(acc).flatMap { e -> go(i + 1, acc + e) }
                                    }
                                go(0, emptyList())
                            }
                        }
                    continue
                }
                is Gen.Done,
                is Gen.ChooseBool,
                is Gen.ChooseInt,
                is Gen.ChooseUInt,
                is Gen.ChooseBits,
                is Gen.ChooseDouble -> {
                    when (current) {
                        is Gen.Done -> current.value
                        is Gen.ChooseBool -> Codecs.readBool(rdr) ?: return RunResult.Eof
                        is Gen.ChooseInt -> Codecs.readInt(current.range, rdr) ?: return RunResult.Eof
                        is Gen.ChooseUInt -> Codecs.readUint(current.range, rdr) ?: return RunResult.Eof
                        is Gen.ChooseBits -> rdr.read(current.width) ?: return RunResult.Eof
                        is Gen.ChooseDouble -> Codecs.readDoubleU01(current.bits, rdr) ?: return RunResult.Eof
                        else -> error("unreachable")
                    }
                }
            }

        if (stack.isEmpty()) {
            @Suppress("UNCHECKED_CAST")
            return Ok(r as A)
        }

        val f = stack.removeLast()
        val next = f(r)
        if (next == null) {
            return RunResult.Filtered
        }
        current = next
    }
}

sealed interface Run<out A> {
    data class Done<out A>(val value: A) : Run<A>

    data class FlatMap<A, out B>(val fa: Run<A>, val f: (A) -> Run<B>) : Run<B>

    data class Delay<out A>(val thunk: Need<Run<A>>) : Run<A>

    data class ReadN<out B>(val n: Int, val cont: (ULong) -> Run<B>) : Run<B>

    fun <Z> flatMap(f: (A) -> Run<Z>): Run<Z> = FlatMap(this, f)

    fun <Z> map(f: (A) -> Z): Run<Z> = flatMap { Done(f(it)) }

    companion object {
        fun <A> now(value: A): Run<A> = Done(value)

        fun <A> delay(thunk: Need<Run<A>>): Run<A> = Delay(thunk)
    }
}

fun <A : Any> Gen<A>.sample(random: Random): A? =
    when (val r = sampleR(BitSource.of(random))) {
        is RunResult.Ok -> r.value
        is RunResult.Eof -> null
        is RunResult.Filtered -> null
    }

fun <A : Any> Gen<A>.sampleUnbounded(random: Random): A {
    while (true) {
        val r = sample(random)
        if (r != null) return r
    }
}

fun <A : Any> Gen<A>.foreach(count: Int = 100, f: (A) -> Unit) {
    val random = L64X128Random(Random.Default.nextLong())
    repeat(count) {
        val r = this.sample(random)
        if (r != null) {
            f(r)
        }
    }
}

fun <A : Any> Gen<A>.foreach(random: Random, count: Int, f: (A) -> Unit) {
    repeat(count) {
        val r = this.sample(random)
        if (r != null) {
            f(r)
        }
    }
}

fun <A : Any> Gen<A>.satisfy(iters: Int, seed: Long, p: (A) -> Boolean): WithTape<A>? {
    val rng = L64X128Random(seed)

    repeat(iters) {
        val currentSeed = rng.nextLong()
        val tape = RawTapeReader(TapeSeed(currentSeed, MutableBitDeque()))
        when (val result = this.sampleR(BitSource.of(tape))) {
            RunResult.Eof,
            RunResult.Filtered -> Unit
            is RunResult.Ok -> {
                if (p(result.value)) {
                    return WithTape(tape, result.value)
                }
            }
        }
    }

    return null
}

fun <A : Any> Gen<A>.minimize(
    v: WithTape<A>,
    iters: Int,
    seed: Long,
    p: (A) -> Boolean,
): WithTape<A>? {
    if (!p(v.result)) return null

    val random = L64X128Random(seed)

    fun makeNewTape(bestTapes: List<WithTape<A>>): Pair<Long, RawTapeReader> {
        val tapeIndex = random.nextInt(bestTapes.size)
        val selectedTape = bestTapes[tapeIndex]

        val flips = selectedTape.tape.seed.flips.toMutableBitDeque()

        val flipCount = minOf(random.nextInt(selectedTape.tape.read.toInt()), random.nextInt(4) + 1)

        repeat(flipCount) {
            val index =
                minOf(
                    random.nextInt(selectedTape.tape.read.toInt()),
                    random.nextInt(selectedTape.tape.read.toInt()),
                )
            flips.fillAndSet(index.toLong(), random.nextBoolean())
        }

        val newLimit = selectedTape.tape.read * 2
        val newRawTapeReader = RawTapeReader(TapeSeed(selectedTape.tape.seed.seed, flips))
        return newLimit to newRawTapeReader
    }

    val bestTapes = mutableListOf(v)

    repeat(iters) {
        val (testLimit, testTape) = makeNewTape(bestTapes)
        when (val result = sampleR(BitSource.of(testTape, testLimit))) {
            is RunResult.Filtered,
            is RunResult.Eof -> Unit
            is RunResult.Ok -> {
                if (p(result.value)) {
                    val newFTape = WithTape(testTape, result.value)
                    if (bestTapes.none { it.result == newFTape.result }) {
                        bestTapes.add(newFTape)
                        bestTapes.sortBy { RawTapeComplexity.of(it.tape) }
                        while (bestTapes.size >= 10) {
                            bestTapes.removeAt(bestTapes.size - 1)
                        }
                    }
                }
            }
        }
    }

    return bestTapes.minBy { RawTapeComplexity.of(it.tape) }
}
