package one.wabbit.random.gen

import java.util.SplittableRandom
import one.wabbit.data.Need
import one.wabbit.random.gen.RunResult.*
import one.wabbit.random.gen.util.Codecs
import one.wabbit.random.gen.util.ExceptionComparisonMode
import one.wabbit.random.gen.util.MutableBitDeque
import one.wabbit.random.gen.util.compareExceptions
import one.wabbit.random.gen.util.unsafeCast

interface BitSource {
    fun next(bits: Int): ULong

    fun available(): Long

    val pos: ULong

    fun nextAnnotationId(): ULong

    fun annotate(anno: TapeAnnotation): Unit

    companion object {
        fun of(random: SplittableRandom): BitSource =
            object : BitSource {
                override var pos: ULong = 0UL

                override fun next(bits: Int): ULong {
                    require(bits in 0..64) { "Requested $bits bits, but must be within [0..64]." }

                    val raw = random.nextLong().toULong()
                    val result =
                        if (bits == 64) {
                            // Return all bits unmasked
                            raw
                        } else {
                            // Mask out only the requested bits
                            raw and ((1UL shl bits) - 1UL)
                        }
                    pos += bits.toULong()
                    return result
                }

                override fun available(): Long = Long.MAX_VALUE

                override fun nextAnnotationId(): ULong = 0uL

                override fun annotate(anno: TapeAnnotation) {
                    /* no-op */
                }
            }

        fun of(tape: RawTapeReader, limit: Long = Long.MAX_VALUE): BitSource =
            object : BitSource {
                override var pos: ULong = tape.read.toULong()

                override fun next(bits: Int): ULong = tape.read(bits)

                override fun available(): Long = limit - tape.read

                override fun nextAnnotationId(): ULong = 0uL

                override fun annotate(anno: TapeAnnotation) {
                    /* no-op */
                }
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

    // one adapter, reused by all samplers in this run
    val rdr = Codecs.ReadBits { n -> readN(n) }

    val stack = mutableListOf<(Any?) -> Gen<Any?>?>()
    var current: Gen<Any?> = this

    while (true) {
        val r =
            when (current) {
                is Gen.Fail -> {
                    return RunResult.Filtered
                }
                is Gen.Delay -> {
                    // Evaluate the thunk
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
                    val nextGen = w.draw(n.toULong())
                    current = nextGen
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
        } else {
            val f = stack.removeLast()
            val next = f(r)
            if (next == null) {
                // Filtered
                return RunResult.Filtered
            } else {
                current = next
            }
        }
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

// fun <A> Gen<A>.sampleC(): Run<RunResult<Pair<TapeData, A>>> {
//    when (this) {
//        is Gen.Fail -> return Run.now(RunResult.Filtered)
//        is Gen.Delay -> return Run.delay(this.value.map { it.sampleC() })
//        is Gen.Label -> return this.body.sampleC()
//        is Gen.FlatMap<*, A> -> {
//            this as Gen.FlatMap<Any?, A>
//            return Run.delay(Need.apply { this.left.sampleC() })
//                .flatMap { this.f(it)?.sampleC() ?: Run.now(RunResult.Filtered) }
//        }
//        is Gen.Choose<*> -> {
//
//        }
//        is Gen.ListOf<*> -> {
//            val lenGen = current.size
//            val elemGen = current.element
//            current = lenGen.flatMap { len ->
//                if (len < 0) Gen.const(emptyList())
//                else {
//                    fun go(i: Int, acc: List<Any?>): Gen<List<Any?>> =
//                        if (i >= len) Gen.const(acc)
//                        else elemGen.flatMap { e -> go(i + 1, acc + e) }
//                    go(0, emptyList())
//                }
//            }
//            continue
//        }
//        is Gen.SeqOf<*> -> {
//            val lenGen = current.size
//            val elemGen: (List<Any?>) -> Gen<Any?> = unsafeCast(current.element)
//            current = lenGen.flatMap { len ->
//                if (len < 0) Gen.const(emptyList())
//                else {
//                    fun go(i: Int, acc: List<Any?>): Gen<List<Any?>> =
//                        if (i >= len) Gen.const(acc)
//                        else elemGen(acc).flatMap { e -> go(i + 1, acc + e) }
//                    go(0, emptyList())
//                }
//            }
//            continue
//        }
//
//        is Gen.Done,
//        is Gen.ChooseBool, is Gen.ChooseInt,
//        is Gen.ChooseUInt, is Gen.ChooseBits,
//        is Gen.ChooseDouble
//            -> {
//            when (current) {
//                is Gen.Done -> current.value
//                is Gen.ChooseBool -> Codecs.readBool(rdr) ?: return RunResult.Eof
//                is Gen.ChooseInt -> Codecs.readInt(current.range, rdr) ?: return RunResult.Eof
//                is Gen.ChooseUInt -> Codecs.readUint(current.range, rdr) ?: return RunResult.Eof
//                is Gen.ChooseBits -> rdr.read(current.width) ?: return RunResult.Eof
//                is Gen.ChooseDouble -> Codecs.readDoubleU01(current.bits, rdr) ?: return
// RunResult.Eof
//                else -> error("unreachable")
//            }
//        }
//    }
// }

fun <A : Any> Gen<A>.sample(random: SplittableRandom): A? =
    when (val r = sampleR(BitSource.of(random))) {
        is RunResult.Ok -> r.value
        is RunResult.Eof -> null
        is RunResult.Filtered -> null
    }

fun <A : Any> Gen<A>.sampleUnbounded(random: SplittableRandom): A {
    while (true) {
        val r = sample(random)
        if (r != null) return r
    }
}

// fun <A : Any> Gen<A>.recordOnce(seed: Long): WithV2<A>? {
//    val rec = ChoiceIO.Recorder(Entropy(EntropySource.Random(seed)))
//    return when (val r = sampleC(rec)) {
//        is RunResult.Ok -> {
//            val v2 = TapeSeedV2.fromRecorder(seed, rec)
//            WithV2(seed, v2, r.value)
//        }
//        else -> null
//    }
// }

// fun <A : Any> Gen<A>.replayOnce(v2: TapeSeedV2, strict: Boolean = false): A? {
//    val ent = Entropy(if (strict) EntropySource.Replay(TapeSeedV2.toBitSequence(v2))
//    else EntropySource.Replay(TapeSeedV2.toBitSequence(v2)))
//    val io: ChoiceIO = if (strict) ChoiceIO.ReplayStrict(ent, v2.log) else
// ChoiceIO.ReplayAdaptive(ent)
//    return when (val r = sampleC(io)) {
//        is RunResult.Ok -> r.value
//        else -> null
//    }
// }

fun <A : Any> Gen<A>.foreach(count: Int = 100, f: (A) -> Unit) {
    val random = SplittableRandom()
    repeat(count) {
        val r = this.sample(random)
        if (r != null) {
            f(r)
        }
    }
}

fun <A : Any> Gen<A>.foreach(random: SplittableRandom, count: Int, f: (A) -> Unit) {
    repeat(count) {
        val r = this.sample(random)
        if (r != null) {
            f(r)
        }
    }
}

class MinimizedException(val original: Throwable, val tape: RawTapeReader, val value: Any) :
    Throwable(original.message, original)

class FailedToMinimizeException(val original: Throwable, val tape: RawTapeReader) :
    Throwable(original.message, original)

object Tests {
    fun <A : Any> foreachMin(
        gen: Gen<A>,
        random: SplittableRandom,
        iters: Int,
        minimizerSteps: Int = 10000,
        exceptionMode: ExceptionComparisonMode =
            ExceptionComparisonMode.SAME_CLASS_MESSAGE_TOP_FRAME,
        f: (A) -> Unit,
    ) {
        var discarded = 0

        repeat(iters) {
            val currentSeed = random.nextLong()
            val tape = RawTapeReader(TapeSeed(currentSeed, MutableBitDeque()))
            val result = gen.sampleR(BitSource.of(tape))

            when (result) {
                RunResult.Eof -> {
                    // We ran out of bits, so we need to try again with a new seed
                    // This should be impossible, but we'll handle it anyway
                    discarded += 1
                }
                RunResult.Filtered -> {
                    // We got a value, but it was filtered out
                    discarded += 1
                }
                is RunResult.Ok -> {
                    try {
                        f(result.value)
                    } catch (e0: Throwable) {
                        // Always re-throw certain critical errors:
                        if (e0 is VirtualMachineError) throw e0

                        println("Successfully caught exception $e0")
                        println(result.value)
                        val tape0 = WithTape(tape, result.value)

                        val p: (A) -> Boolean = {
                            try {
                                f(it)
                                false
                            } catch (e1: Throwable) {
                                if (e1 is VirtualMachineError) throw e1
                                compareExceptions(e0, e1, exceptionMode)
                            }
                        }

                        check(p(result.value)) { "Expected exception to be thrown" }

                        val r = gen.minimize(tape0, minimizerSteps, random.nextLong(), p)

                        if (r == null) {
                            throw FailedToMinimizeException(e0, tape0.tape)
                        } else {
                            println("Minimized to ${r.result}")
                            throw MinimizedException(e0, r.tape, r.result)
                        }
                    }
                    discarded += 1
                }
            }
        }
    }
}

fun <A : Any> Gen<A>.foreachMin(
    random: SplittableRandom,
    iters: Int,
    minimizerSteps: Int = 10000,
    exceptionMode: ExceptionComparisonMode = ExceptionComparisonMode.SAME_CLASS_MESSAGE_TOP_FRAME,
    f: (A) -> Unit,
) {
    var discarded = 0

    repeat(iters) {
        val currentSeed = random.nextLong()
        val tape = RawTapeReader(TapeSeed(currentSeed, MutableBitDeque()))
        val result = this.sampleR(BitSource.of(tape))

        when (result) {
            RunResult.Eof -> {
                // We ran out of bits, so we need to try again with a new seed
                // This should be impossible, but we'll handle it anyway
                discarded += 1
            }
            RunResult.Filtered -> {
                // We got a value, but it was filtered out
                discarded += 1
            }
            is RunResult.Ok -> {
                try {
                    f(result.value)
                } catch (e0: Throwable) {
                    val tape0 = WithTape(tape, result.value)

                    val p: (A) -> Boolean = {
                        try {
                            f(it)
                            false
                        } catch (e1: Throwable) {
                            if (e1 is VirtualMachineError) throw e1
                            compareExceptions(e0, e1, exceptionMode)
                        }
                    }

                    check(p(result.value)) { "Expected exception to be thrown" }

                    val r = this.minimize(tape0, minimizerSteps, random.nextLong(), p)

                    if (r == null) {
                        throw FailedToMinimizeException(e0, tape0.tape)
                    } else {
                        throw MinimizedException(e0, r.tape, r.result)
                    }
                }
                discarded += 1
            }
        }
    }
}

/**
 * Attempts to find a tape such that when the tape is read using the generator, the resulting value
 * satisfies the condition.
 */
fun <A : Any> Gen<A>.satisfy(iters: Int, seed: Long, p: (A) -> Boolean): WithTape<A>? {
    val rng = SplittableRandom(seed)
    var discarded = 0

    repeat(iters) {
        val currentSeed = rng.nextLong()
        val tape = RawTapeReader(TapeSeed(currentSeed, MutableBitDeque()))
        val result = this.sampleR(BitSource.of(tape))

        when (result) {
            RunResult.Eof -> {
                // We ran out of bits, so we need to try again with a new seed
                // This should be impossible, but we'll handle it anyway
                discarded += 1
            }
            RunResult.Filtered -> {
                // We got a value, but it was filtered out
                discarded += 1
            }
            is RunResult.Ok -> {
                if (p(result.value)) {
                    return WithTape(tape, result.value)
                } else {
                    discarded += 1
                }
            }
        }
    }

    return null
}

/**
 * Attempts to find a smaller tape & value still satisfying a condition. If the original value does
 * not satisfy the condition, fails immediately. To shrink modify early bits randomly until the
 * consumed number of bits decreases.
 */
fun <A : Any> Gen<A>.minimize(
    v: WithTape<A>,
    iters: Int,
    seed: Long,
    p: (A) -> Boolean,
): WithTape<A>? {
    if (!p(v.result)) return null

    val random = SplittableRandom(seed)

    fun makeNewTape(bestTapes: List<WithTape<A>>): Pair<Long, RawTapeReader> {
        val tapeIndex = random.nextInt(bestTapes.size)
        val selectedTape = bestTapes[tapeIndex]

        val flips = selectedTape.tape.seed.flips.toMutableBitDeque()

        val flipCount =
            minOf(
                //            random.nextInt(selectedTape.tape.read.toInt()),
                random.nextInt(selectedTape.tape.read.toInt()),
                random.nextInt(4) + 1,
            )

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
    var discarded = 0

    repeat(iters) {
        val (testLimit, testTape) = makeNewTape(bestTapes)
        val result = sampleR(BitSource.of(testTape, testLimit))

        when (result) {
            is RunResult.Filtered -> discarded += 1
            is RunResult.Eof -> discarded += 1

            is RunResult.Ok ->
                when (p(result.value)) {
                    false -> {
                        /* do nothing */
                    }
                    true -> {
                        val newFTape = WithTape(testTape, result.value)
                        // Check if the new tape is different from the best tapes
                        if (bestTapes.none { it.result == newFTape.result }) {
                            bestTapes.add(newFTape)
                            bestTapes.sortBy { RawTapeComplexity.of(it.tape) }
                            //                        for (t in bestTapes) {
                            //                            println("Tape: ${t.tape.seed.flips}")
                            //                            println("Value: ${t.result}")
                            //                            println("Complexity:
                            // ${TapeComplexity.of(t.tape)}")
                            //                            println()
                            //                        }
                            // println("Min complexity: ${TapeComplexity.of(bestTapes[0].tape)}")
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

//
// object FuzzCheck {
//    //////////////////////////////////////////////////////////////////////////////
//    // Property checking
//    //////////////////////////////////////////////////////////////////////////////
//
//
//
//    //////////////////////////////////////////////////////////////////////////////
//    // Fuzzing
//    //////////////////////////////////////////////////////////////////////////////
//
// //    final case class Seeds[+Trace, +A](discarded: Int, seeds: HashMap[Trace @uV,
// List[WithTape[A]] @uV])
// //    object Seeds {
// //        def empty: Seeds[Nothing, Nothing] =
// //        Seeds[Nothing, Nothing](0, HashMap.empty)
// //    }
// //
// //    // To fuzz, modify bits in an existing sequence.
// //    def fuzz[Trace: Eq.Univ, Value]
// //    (gen: Gen[Value], seeds: Seeds[Trace, Value], iters: Int, r0: StdRng, alpha: Double)
// //    (p: Value => Trace): Seeds[Trace, Value] = {
// //        @tailrec def go(it: Int, seeds: Seeds[Trace, Value], r0: StdRng): Seeds[Trace, Value] =
// //        if (it >= iters) seeds
// //        else {
// //            val (r1, x0) = r0.nextDouble
// //
// //            val newTape = if (x0 < alpha || seeds.seeds.isEmpty) {
// //                Tape.fromStdRng(r1)
// //            } else {
// //                val map = seeds.seeds
// //                val (r2, x1) = r1.nextInt1(map.size)
// //                val (trace, samples) :: Nil = map.iterator.slice(x1, x1 + 1).toList
// //                val (r3, x2) = r2.nextInt1(samples.size)
// //                val oldTape = samples(x2).state
// //                val len = oldTape.totalRead
// //                val (r4, x3) = r3.nextInt1(len)
// //                val newTape0 = oldTape.reset
// //                val newTape = newTape0.copy(flip=newTape0.flip.flip(x3))
// //                newTape
// //            }
// //
// //            runOnTape(gen, newTape) match {
// //                case WithTape(None, state) =>
// //                go(it + 1, Seeds(seeds.discarded + 1, seeds.seeds), r1.fork._2)
// //                case WithTape(Some(v), tape) =>
// //                val trace = p(v)
// //                go(it + 1, Seeds(
// //                    seeds.discarded,
// //                    seeds.seeds.updated(trace, WithTape(v, tape) ::
// seeds.seeds.getOrElse(trace, Nil))),
// //                    r1)
// //            }
// //        }
// //
// //        go(0, seeds, r0)
// //    }
//
//    // If we have a two-way codec for A, we can write it to the tape.
//    //  def write[A](gen: Gen.Codec[A], seed: StdRng): WithTape[A] = ???
// }
