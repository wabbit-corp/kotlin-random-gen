package one.wabbit.random.gen

import java.util.SplittableRandom

interface BitInput {
    fun next(bits: Int): ULong
    fun available(): Long

    companion object {
        fun of(random: SplittableRandom): BitInput =
            object : BitInput {
                override fun next(bits: Int): ULong =
                    random.nextLong().toULong() and ((1UL shl bits) - 1UL)
                override fun available(): Long =
                    Long.MAX_VALUE
            }

        fun of(tape: Tape, limit: Long = Long.MAX_VALUE): BitInput =
            object : BitInput {
                override fun next(bits: Int): ULong =
                    tape.read(bits)
                override fun available(): Long =
                    limit - tape.read
            }
    }
}

sealed interface RunResult<out A> {
    data class Ok<out A>(val value: A) : RunResult<A>
    data object Eof : RunResult<Nothing>
    data object Filtered : RunResult<Nothing>
}

private fun <A, B> unsafeCast(value: A): B = value as B

fun <A : Any> Gen<A>.sampleR(random: BitInput): RunResult<A> {
    val stack = mutableListOf<(Any) -> Gen<Any>?>()
    var current: Gen<Any> = this

    while (true) {
        when (current) {
            is Gen.Fail -> {
                return RunResult.Filtered
            }
            is Gen.Delay -> {
                // Evaluate the thunk
                current = current.value.value
            }
            is Gen.Done, is Gen.ReadN -> {
                val r = when (current) {
                    is Gen.Done -> current.value
                    is Gen.ReadN -> {
                        assert(current.n >= 0)
                        if (current.n == 0) 0
                        else if (random.available() >= current.n) random.next(current.n)
                        else return RunResult.Eof
                    }
                    else -> error("unreachable")
                }

                if (stack.isEmpty()) {
                    return RunResult.Ok(r as A)
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
            is Gen.FlatMap<*, Any> -> {
                stack.add(unsafeCast(current.f))
                current = current.left as Gen<Any>
            }
        }
    }
}

fun <A : Any> Gen<A>.sample(random: SplittableRandom): A? =
    when (val r = sampleR(BitInput.of(random))) {
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

fun <A : Any> Gen<A>.foreach(count: Int = 100, f: (A) -> Unit): Unit {
    val random = SplittableRandom()
    repeat(count) {
        val r = this.sample(random)
        if (r != null) {
            f(r)
        }
    }
}

fun <A : Any> Gen<A>.foreach(random: SplittableRandom, count: Int, f: (A) -> Unit): Unit {
    repeat(count) {
        val r = this.sample(random)
        if (r != null) {
            f(r)
        }
    }
}

class MinimizedException(val original: Throwable, val tape: Tape, val value: Any)
    : Throwable(original.message, original)

class FailedToMinimizeException(val original: Throwable, val tape: Tape)
    : Throwable(original.message, original)

object Tests {
    fun <A : Any> foreachMin(gen: Gen<A>, random: SplittableRandom, iters: Int, minimizerSteps: Int = 10000, f: (A) -> Unit): Unit {
        var discarded = 0

        repeat(iters) {
            val currentSeed = random.nextLong()
            val tape = Tape(TapeSeed(currentSeed, MutableBitDeque()))
            val result = gen.sampleR(BitInput.of(tape))

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
                        if (e0 is VirtualMachineError) throw e0

                        println("Successfully caught exception $e0")
                        println(result.value)
                        val tape0 = WithTape(tape, result.value)

                        val p : (A) -> Boolean = p@{
                            try {
                                f(it)
                                return@p false
                            } catch (e1: Throwable) {
                                if (e1 is VirtualMachineError) throw e1
                                if (e1.javaClass !== e0.javaClass) return@p false
                                // if (e1.message != e0.message) return@p false
                                // Compare the stack traces
                                val st1 = e1.stackTrace
                                val st0 = e0.stackTrace
                                if (st1.isEmpty() && st0.isEmpty()) return@p true
                                if (st1[0].className != st0[0].className) return@p false
                                if (st1[0].methodName != st0[0].methodName) return@p false
                                if (st1[0].lineNumber != st0[0].lineNumber) return@p false
                                return@p true
                            }
                        }

                        check(p(result.value)) { "Expected exception to be thrown" }

                        val r = gen.minimize(tape0, minimizerSteps, random.nextLong(), p)

                        if (r == null) throw FailedToMinimizeException(e0, tape0.tape)
                        else {
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

fun <A : Any> Gen<A>.foreachMin(random: SplittableRandom, iters: Int, f: (A) -> Unit): Unit {
    var discarded = 0

    repeat(iters) {
        val currentSeed = random.nextLong()
        val tape = Tape(TapeSeed(currentSeed, MutableBitDeque()))
        val result = this.sampleR(BitInput.of(tape))

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

                    val p : (A) -> Boolean = p@{
                        try {
                            f(it)
                            return@p false
                        } catch (e1: Throwable) {
                            if (e1.javaClass !== e0.javaClass) return@p false
                            if (e1.message != e0.message) return@p false
                            // Compare the stack traces
                            val st1 = e1.stackTrace
                            val st0 = e0.stackTrace
                            if (st1.isEmpty() && st0.isEmpty()) return@p true
                            if (st1[0].className != st0[0].className) return@p false
                            if (st1[0].methodName != st0[0].methodName) return@p false
                            if (st1[0].lineNumber != st0[0].lineNumber) return@p false
                            return@p true
                        }
                    }

                    check(p(result.value)) { "Expected exception to be thrown" }

                    val r = this.minimize(tape0, 10000, random.nextLong(), p)

                    if (r == null) throw FailedToMinimizeException(e0, tape0.tape)
                    else throw MinimizedException(e0, r.tape, r.result)
                }
                discarded += 1
            }
        }
    }
}

data class WithTape<out A>(val tape: Tape, val result: A)

/**
 * Attempts to find a tape such that when the tape is read using
 * the generator, the resulting value satisfies the condition.
 */
fun <A : Any> Gen<A>.satisfy(iters: Int, seed: Long, p: (A) -> Boolean): WithTape<A>? {
    val rng = SplittableRandom(seed)
    var discarded = 0

    repeat(iters) {
        val currentSeed = rng.nextLong()
        val tape = Tape(TapeSeed(currentSeed, MutableBitDeque()))
        val result = this.sampleR(BitInput.of(tape))

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

data class TapeComplexity(val length: Long, val positive: Long) : Comparable<TapeComplexity> {
    override fun compareTo(other: TapeComplexity): Int =
        when {
            this.length < other.length -> -1
            this.length > other.length -> 1
            this.positive < other.positive -> -1
            this.positive > other.positive -> 1
            else -> 0
        }

    override fun toString(): String {
        return "L${length}P$positive"
    }

    companion object {
        fun of(tape: Tape): TapeComplexity =
            TapeComplexity(tape.read, tape.read1)
    }
}

/**
 * Attempts to find a smaller tape & value still satisfying a condition.
 * If the original value does not satisfy the condition, fails immediately.
 * To shrink modify early bits randomly until the consumed number
 * of bits decreases.
 */
fun <A : Any> Gen<A>.minimize(v: WithTape<A>, iters: Int, seed: Long, p: (A) -> Boolean): WithTape<A>? {
    if (!p(v.result)) return null

    val random = SplittableRandom(seed)

    fun makeNewTape(bestTapes: List<WithTape<A>>): Pair<Long, Tape> {
        val tapeIndex = random.nextInt(bestTapes.size)
        val selectedTape = bestTapes[tapeIndex]

        val flips = selectedTape.tape.seed.flips.copy()

        val flipCount = minOf(
//            random.nextInt(selectedTape.tape.read.toInt()),
            random.nextInt(selectedTape.tape.read.toInt()),
            random.nextInt(4) + 1)
        repeat(flipCount) {
            val index = minOf(random.nextInt(selectedTape.tape.read.toInt()), random.nextInt(selectedTape.tape.read.toInt()))
            flips.fillAndSet(index.toLong(), random.nextBoolean())
        }

        val newLimit = selectedTape.tape.read * 2
        val newTape = Tape(TapeSeed(selectedTape.tape.seed.seed, flips))
        return newLimit to newTape
    }

    val bestTapes = mutableListOf(v)
    var discarded = 0

    repeat(iters) {
        val (testLimit, testTape) = makeNewTape(bestTapes)
        val result = sampleR(BitInput.of(testTape, testLimit))

        when (result) {
            is RunResult.Filtered ->
                discarded += 1
            is RunResult.Eof ->
                discarded += 1

            is RunResult.Ok ->
                when (p(result.value)) {
                    false -> { /* do nothing */ }
                    true -> {
                        val newFTape = WithTape(testTape, result.value)
                        bestTapes.add(newFTape)
                        bestTapes.sortBy { TapeComplexity.of(it.tape) }
//                        for (t in bestTapes) {
//                            println("Tape: ${t.tape.seed.flips}")
//                            println("Value: ${t.result}")
//                            println("Complexity: ${TapeComplexity.of(t.tape)}")
//                            println()
//                        }
                        println("Min complexity: ${TapeComplexity.of(bestTapes[0].tape)}")
                        while (bestTapes.size >= 10)
                            bestTapes.removeAt(bestTapes.size - 1)
                    }
                }
        }
    }

    return bestTapes.minBy { TapeComplexity.of(it.tape) }
}

//
//object FuzzCheck {
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
////    final case class Seeds[+Trace, +A](discarded: Int, seeds: HashMap[Trace @uV, List[WithTape[A]] @uV])
////    object Seeds {
////        def empty: Seeds[Nothing, Nothing] =
////        Seeds[Nothing, Nothing](0, HashMap.empty)
////    }
////
////    // To fuzz, modify bits in an existing sequence.
////    def fuzz[Trace: Eq.Univ, Value]
////    (gen: Gen[Value], seeds: Seeds[Trace, Value], iters: Int, r0: StdRng, alpha: Double)
////    (p: Value => Trace): Seeds[Trace, Value] = {
////        @tailrec def go(it: Int, seeds: Seeds[Trace, Value], r0: StdRng): Seeds[Trace, Value] =
////        if (it >= iters) seeds
////        else {
////            val (r1, x0) = r0.nextDouble
////
////            val newTape = if (x0 < alpha || seeds.seeds.isEmpty) {
////                Tape.fromStdRng(r1)
////            } else {
////                val map = seeds.seeds
////                val (r2, x1) = r1.nextInt1(map.size)
////                val (trace, samples) :: Nil = map.iterator.slice(x1, x1 + 1).toList
////                val (r3, x2) = r2.nextInt1(samples.size)
////                val oldTape = samples(x2).state
////                val len = oldTape.totalRead
////                val (r4, x3) = r3.nextInt1(len)
////                val newTape0 = oldTape.reset
////                val newTape = newTape0.copy(flip=newTape0.flip.flip(x3))
////                newTape
////            }
////
////            runOnTape(gen, newTape) match {
////                case WithTape(None, state) =>
////                go(it + 1, Seeds(seeds.discarded + 1, seeds.seeds), r1.fork._2)
////                case WithTape(Some(v), tape) =>
////                val trace = p(v)
////                go(it + 1, Seeds(
////                    seeds.discarded,
////                    seeds.seeds.updated(trace, WithTape(v, tape) :: seeds.seeds.getOrElse(trace, Nil))),
////                    r1)
////            }
////        }
////
////        go(0, seeds, r0)
////    }
//
//    // If we have a two-way codec for A, we can write it to the tape.
//    //  def write[A](gen: Gen.Codec[A], seed: StdRng): WithTape[A] = ???
//}
