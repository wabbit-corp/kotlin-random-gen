// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.random.gen

import one.wabbit.random.L64X128Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenForeachMinSpec {
    private val random = L64X128Random(42)

    @Test
    fun testForeachMin_CatchesExceptionAndMinimizes() {
        val genStr = Gen.string(3, Gen.oneOf(listOf('a', 'b', 'c')))
        var caught = false

        try {
            genStr.foreachMin(
                random = random,
                iters = 5_000,
                minimizerSteps = 10_000,
            ) { s ->
                if (s.contains("abc")) {
                    throw IllegalStateException("We do not like 'abc'")
                }
            }
        } catch (e: MinimizedException) {
            caught = true
            assertEquals("abc", e.value)
        }

        assertTrue(caught, "We expected to eventually catch an exception with 'abc'")
    }
}
