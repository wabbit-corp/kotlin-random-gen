package one.wabbit.random.gen

import one.wabbit.random.L64X128Random
import kotlin.test.Test
import kotlin.test.assertTrue

class GenJvmSpec {
    private val random = L64X128Random(42)

    @Test
    fun testForeachMin_CatchesExceptionAndMinimizes() {
        val genStr = Gen.string(Gen.int(0..100), Gen.range('a'..'z'))
        var caught = false

        try {
            genStr.foreachMin(random, iters = 1_000_000, minimizerSteps = 1_000_000) { s ->
                if (s.contains("abc")) {
                    throw IllegalStateException("We do not like 'abc'")
                }
            }
        } catch (e: MinimizedException) {
            caught = true
            assertTrue(e.value is String && (e.value as String).contains("abc"))
        }

        assertTrue(caught, "We expected to eventually catch an exception with 'abc'")
    }
}
