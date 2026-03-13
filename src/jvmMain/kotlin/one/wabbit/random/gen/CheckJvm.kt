package one.wabbit.random.gen

import kotlin.random.Random
import one.wabbit.random.gen.util.ExceptionComparisonMode
import one.wabbit.random.gen.util.MutableBitDeque
import one.wabbit.random.gen.util.compareExceptions

class MinimizedException(val original: Throwable, val tape: RawTapeReader, val value: Any) :
    Throwable(original.message, original)

class FailedToMinimizeException(val original: Throwable, val tape: RawTapeReader) :
    Throwable(original.message, original)

object Tests {
    fun <A : Any> foreachMin(
        gen: Gen<A>,
        random: Random,
        iters: Int,
        minimizerSteps: Int = 10000,
        exceptionMode: ExceptionComparisonMode =
            ExceptionComparisonMode.SAME_CLASS_MESSAGE_TOP_FRAME,
        f: (A) -> Unit,
    ) {
        repeat(iters) {
            val currentSeed = random.nextLong()
            val tape = RawTapeReader(TapeSeed(currentSeed, MutableBitDeque()))
            when (val result = gen.sampleR(BitSource.of(tape))) {
                RunResult.Eof,
                RunResult.Filtered -> Unit
                is RunResult.Ok -> {
                    try {
                        f(result.value)
                    } catch (e0: Throwable) {
                        if (e0 is VirtualMachineError) throw e0

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
                        }
                        throw MinimizedException(e0, r.tape, r.result)
                    }
                }
            }
        }
    }
}

fun <A : Any> Gen<A>.foreachMin(
    random: Random,
    iters: Int,
    minimizerSteps: Int = 10000,
    exceptionMode: ExceptionComparisonMode = ExceptionComparisonMode.SAME_CLASS_MESSAGE_TOP_FRAME,
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
                    if (e0 is VirtualMachineError) throw e0

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
                    }
                    throw MinimizedException(e0, r.tape, r.result)
                }
            }
        }
    }
}
