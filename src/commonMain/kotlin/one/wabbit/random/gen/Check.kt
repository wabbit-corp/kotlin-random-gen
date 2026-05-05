// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.random.gen

import kotlin.random.Random
import one.wabbit.data.Need
import one.wabbit.random.L64X128Random
import one.wabbit.random.gen.RunResult.*
import one.wabbit.random.gen.util.Codecs
import one.wabbit.random.gen.util.ExceptionComparisonMode
import one.wabbit.random.gen.util.MutableBitDeque
import one.wabbit.random.gen.util.compareExceptions
import one.wabbit.random.gen.util.defaultExceptionComparisonMode
import one.wabbit.random.gen.util.isFatalThrowable
import one.wabbit.random.gen.util.unsafeCast

/** Source of deterministic bits used by generator interpretation. */
interface BitSource {
    /** Reads [bits] bits and advances the source position. */
    fun next(bits: Int): ULong

    /** Returns the number of bits still available, or [Long.MAX_VALUE] for unbounded sources. */
    fun available(): Long

    /** Current bit position. */
    val pos: ULong

    /** Returns the next annotation identifier for annotated runs. */
    fun nextAnnotationId(): ULong

    /** Records a tape annotation. Current built-in sources ignore annotations. */
    fun annotate(anno: TapeAnnotation): Unit

    /** Constructors for bit sources backed by random streams or replay tapes. */
    companion object {
        /** Creates an unbounded bit source backed by [random]. */
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

        /** Creates a bit source backed by [tape], optionally limited to [limit] consumed bits. */
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

/** Result of interpreting a [Gen] against a [BitSource]. */
sealed interface RunResult<out A> {
    /** Successful generated value. */
    data class Ok<out A>(val value: A) : RunResult<A>

    /** The bit source did not contain enough bits to complete generation. */
    data object Eof : RunResult<Nothing>

    /** The generator rejected the current sample. */
    data object Filtered : RunResult<Nothing>
}

/** Generated result paired with the replay tape that produced it. */
data class WithTape<out A>(
    /** Replay tape that produced [result]. */
    val tape: RawTapeReader,
    /** Generated value produced by [tape]. */
    val result: A,
)

/** Exception thrown after property minimization succeeds. */
class MinimizedException(
    /** Original exception thrown by the property body before minimization. */
    val original: Throwable,
    /** Replay tape for the minimized failing value. */
    val tape: RawTapeReader,
    /** Minimized failing value. */
    val value: Any,
) : Throwable(original.message, original)

/** Exception thrown when a failing value cannot be reproduced during minimization. */
class FailedToMinimizeException(
    /** Original exception thrown by the property body. */
    val original: Throwable,
    /** Replay tape for the unreduced failing value. */
    val tape: RawTapeReader,
) : Throwable(original.message, original)

/**
 * Interprets this generator using [source].
 *
 * @return [RunResult.Ok] for a generated value, [RunResult.Filtered] for rejected samples, or
 *   [RunResult.Eof] when [source] runs out of bits.
 */
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
                    val n =
                        Codecs.readUint(0U..(w.total - 1UL).toUInt(), rdr) ?: return RunResult.Eof
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
                        is Gen.ChooseInt ->
                            Codecs.readInt(current.range, rdr) ?: return RunResult.Eof
                        is Gen.ChooseUInt ->
                            Codecs.readUint(current.range, rdr) ?: return RunResult.Eof
                        is Gen.ChooseBits -> rdr.read(current.width) ?: return RunResult.Eof
                        is Gen.ChooseDouble ->
                            Codecs.readDoubleU01(current.bits, rdr) ?: return RunResult.Eof
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

/** Small interpreter data type used by lower-level bit-reading experiments. */
sealed interface Run<out A> {
    /** Completed interpreter value. */
    data class Done<out A>(val value: A) : Run<A>

    /** Sequenced interpreter step. */
    data class FlatMap<A, out B>(val fa: Run<A>, val f: (A) -> Run<B>) : Run<B>

    /** Delayed interpreter step. */
    data class Delay<out A>(val thunk: Need<Run<A>>) : Run<A>

    /** Bit-read interpreter step. */
    data class ReadN<out B>(val n: Int, val cont: (ULong) -> Run<B>) : Run<B>

    /** Sequences this run with [f]. */
    fun <Z> flatMap(f: (A) -> Run<Z>): Run<Z> = FlatMap(this, f)

    /** Maps the completed value of this run. */
    fun <Z> map(f: (A) -> Z): Run<Z> = flatMap { Done(f(it)) }

    /** Constructors for [Run] values. */
    companion object {
        /** Creates a completed run. */
        fun <A> now(value: A): Run<A> = Done(value)

        /** Creates a delayed run. */
        fun <A> delay(thunk: Need<Run<A>>): Run<A> = Delay(thunk)
    }
}

/** Samples this generator once from [random], returning null for filtered or incomplete samples. */
fun <A : Any> Gen<A>.sample(random: Random): A? =
    when (val r = sampleR(BitSource.of(random))) {
        is RunResult.Ok -> r.value
        is RunResult.Eof -> null
        is RunResult.Filtered -> null
    }

/** Samples this generator until a value is produced. */
fun <A : Any> Gen<A>.sampleUnbounded(random: Random): A {
    while (true) {
        val r = sample(random)
        if (r != null) return r
    }
}

/**
 * Samples this generator up to [count] times with a fresh random seed and runs [f] for successes.
 */
fun <A : Any> Gen<A>.foreach(count: Int = 100, f: (A) -> Unit) {
    val random = L64X128Random(Random.Default.nextLong())
    repeat(count) {
        val r = this.sample(random)
        if (r != null) {
            f(r)
        }
    }
}

/** Samples this generator up to [count] times using [random] and runs [f] for successes. */
fun <A : Any> Gen<A>.foreach(random: Random, count: Int, f: (A) -> Unit) {
    repeat(count) {
        val r = this.sample(random)
        if (r != null) {
            f(r)
        }
    }
}

/** Namespace for property-style test runners. */
object Tests {
    /** Runs [gen] and throws [MinimizedException] if a failing sample can be minimized. */
    fun <A : Any> foreachMin(
        gen: Gen<A>,
        random: Random,
        iters: Int,
        minimizerSteps: Int = 10000,
        exceptionMode: ExceptionComparisonMode = defaultExceptionComparisonMode(),
        f: (A) -> Unit,
    ) {
        gen.foreachMin(
            random = random,
            iters = iters,
            minimizerSteps = minimizerSteps,
            exceptionMode = exceptionMode,
            f = f,
        )
    }
}

/** Runs this generator repeatedly and minimizes the first non-fatal exception thrown by [f]. */
fun <A : Any> Gen<A>.foreachMin(
    random: Random,
    iters: Int,
    minimizerSteps: Int = 10000,
    exceptionMode: ExceptionComparisonMode = defaultExceptionComparisonMode(),
    f: (A) -> Unit,
) {
    repeat(iters) {
        val currentSeed = random.nextLong()
        val tape = RawTapeReader(TapeSeed(currentSeed, MutableBitDeque()))
        when (val result = this.sampleR(BitSource.of(tape))) {
            RunResult.Eof,
            RunResult.Filtered -> Unit
            is RunResult.Ok -> {
                try {
                    f(result.value)
                } catch (e0: Throwable) {
                    if (isFatalThrowable(e0)) throw e0

                    val tape0 = WithTape(tape, result.value)
                    val p: (A) -> Boolean = {
                        try {
                            f(it)
                            false
                        } catch (e1: Throwable) {
                            if (isFatalThrowable(e1)) throw e1
                            compareExceptions(e0, e1, exceptionMode)
                        }
                    }

                    check(p(result.value)) { "Expected exception to be thrown" }

                    val r = this.minimize(tape0, minimizerSteps, random.nextLong(), p)
                    if (r == null) {
                        throw FailedToMinimizeException(e0, tape0.tape)
                    }
                    throw MinimizedException(e0, r.tape, r.result)
                }
            }
        }
    }
}

/** Searches for a generated value satisfying [p]. */
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

/** Attempts to minimize a known failing generated value [v]. */
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
