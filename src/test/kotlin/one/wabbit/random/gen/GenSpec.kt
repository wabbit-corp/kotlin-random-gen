package one.wabbit.random.gen

import java.util.*
import kotlin.math.sqrt
import kotlin.test.*

class GenSpec {
    // We’ll reuse this random across many tests (or create new ones as needed).
    private val random = SplittableRandom(42)

    // -------------------------------------------------------------------------
    // 1. Basic distribution property tests
    // -------------------------------------------------------------------------

    @Test
    fun testIntGenDistribution_SmallRanges() {
        // Check uniform distribution of int(0..M-1) for various small M
        // This is similar to your existing test, but we’ll convert to real assertions
        val N = 100_000
        for (M in listOf(3, 4, 5, 7, 11, 13, 16, 17)) {
            val freq = IntArray(M)
            Gen.int(0 until M).foreach(random, N) { freq[it]++ }

            // Each count should be close to N / M
            for (count in freq) {
                val p = count.toDouble() / N
                val expected = 1.0 / M
                // Tolerate e.g. ± 5% of expected. (You can refine this)
                assertTrue(
                    p in expected * 0.95..expected * 1.05,
                    "Expected freq around $expected, got $p in M=$M"
                )
            }
        }
    }

    @Test
    fun testIntGenDistribution_LargeRange() {
        // Checks we can read from the full possible range
        val gen = Gen.int(Int.MIN_VALUE..Int.MAX_VALUE)
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        gen.foreach(random, 10_000) {
            if (it < min) min = it
            if (it > max) max = it
        }
        // We want to confirm we *at least* see negative and positive values.
        assertTrue(min < 0, "Expected to see negative values in min, but min=$min")
        assertTrue(max > 0, "Expected to see positive values in max, but max=$max")
    }

    @Test
    fun testUintGenDistribution() {
        // Similar distribution check for uint
        // Just a small test to ensure code paths for Gen.uint(0u..someRange) are used
        val N = 100_000
        val M = 16u
        val freq = IntArray(M.toInt()) { 0 }
        Gen.uint(0u..(M - 1u)).foreach(random, N) { freq[it.toInt()]++ }

        for (count in freq) {
            val p = count.toDouble() / N
            val expected = 1.0 / M.toDouble()
            assertTrue(p in expected * 0.95..expected * 1.05, "Distribution check for uint(0..$M)")
        }
    }

    @Test
    fun testBoolGenDistribution() {
        val N = 100_000
        var trueCount = 0
        var falseCount = 0

        Gen.bool.foreach(random, N) {
            if (it) trueCount++ else falseCount++
        }
        val trueRatio = trueCount.toDouble() / N
        assertTrue(trueRatio in 0.48..0.52, "Boolean distribution should be close to 50/50, got $trueRatio")
    }

    @Test
    fun testUniformDoubleDistribution() {
        // Check the uniform generator in [0,1]
        val N = 100_000
        var sum = 0.0
        Gen.uniform().foreach(random, N) {
            assertTrue(it in 0.0..1.0, "Double must be in [0,1]")
            sum += it
        }
        val average = sum / N
        // For a uniform(0,1), the mean is ~0.5
        // Check that we are somewhere in 0.49..0.51
        assertTrue(average in 0.49..0.51, "Average of uniform distribution ~ 0.5, got $average")
    }

    // -------------------------------------------------------------------------
    // 2. Test map, flatMap, filter, zip, repeat, sequence, etc.
    // -------------------------------------------------------------------------

    @Test
    fun testMapFlatMap() {
        // Generate pairs of ints, map them, then ensure it matches an expected transformation
        val genPair = Gen.int(0..10).flatMap { x ->
            Gen.int(0..10).map { y -> x to y }
        }
        genPair.foreach(random, 100) { (x, y) ->
            assertTrue(x in 0..10, "x in range")
            assertTrue(y in 0..10, "y in range")
        }
    }

    @Test
    fun testFilter() {
        // Filter out odd numbers
        val genEven = Gen.int(0..100).filter { it % 2 == 0 }
        var count = 0
        genEven.foreach(random, 500) {
            assertTrue(it % 2 == 0)
            count++
        }
        // We should have gotten some even numbers at least
        assertTrue(count > 0, "Expected some generated evens")
    }

    @Test
    fun testZip() {
        val zipped = Gen.int(0..10) zip Gen.bool
        zipped.foreach(random, 100) { (x, b) ->
            assertTrue(x in 0..10)
            // b is bool, no more checks needed
        }
    }

    @Test
    fun testSequence() {
        // Generate a list of 3 integers in range(0..10)
        val genList = Gen.sequence(listOf(Gen.int(0..10), Gen.int(0..10), Gen.int(0..10)))
        genList.foreach(random, 100) {
            assertEquals(3, it.size)
            it.forEach { x ->
                assertTrue(x in 0..10)
            }
        }
    }

    @Test
    fun testRepeat() {
        // repeat(5, Gen.int(0..10)) => always 5 ints
        val repeated = Gen.repeat(5, Gen.int(0..10))
        repeated.foreach(random, 100) {
            assertEquals(5, it.size)
            it.forEach { x -> assertTrue(x in 0..10) }
        }
    }

    @Test
    fun testRecursive() {
        // For coverage. A naive example: a recursively-defined Gen of small-int lists
        val genRecursive = Gen.recursive<List<Int>> { self ->
            Gen.bool.flatMap { branch ->
                if (!branch) {
                    // Base
                    Gen.const(emptyList())
                } else {
                    // Rec step
                    Gen.int(0..5).flatMap { head ->
                        self.map { tail -> listOf(head) + tail }
                    }
                }
            }
        }
        // Just test we can run it
        genRecursive.foreach(random, 100) {
            // Some checks
            assertTrue(it.size <= 1000) // Should never blow up with default depth of short random runs
        }
    }

    // -------------------------------------------------------------------------
    // 3. freq, oneOf, freqGen, etc.
    // -------------------------------------------------------------------------

    @Test
    fun testOneOf() {
        // We pick from a small list of known items
        val choices = listOf("A", "B", "C")
        val gen = Gen.oneOf(choices)
        val counts = mutableMapOf("A" to 0, "B" to 0, "C" to 0)
        val N = 10_000
        gen.foreach(random, N) {
            counts[it] = counts[it]!! + 1
        }
        // All should appear
        assertTrue(counts.values.all { it > 0 }, "All items must appear in 10k tries")
    }

    @Test
    fun testFreq() {
        // Weighted picking. 2 A’s for every 1 B, etc.
        val freqGen = Gen.freq(listOf(2 to "A", 1 to "B"))
        val N = 10_000
        var countA = 0
        var countB = 0
        freqGen.foreach(random, N) {
            if (it == "A") countA++ else countB++
        }
        val ratio = countA.toDouble() / countB
        // Should be close to 2.0
        assertTrue(ratio in 1.8..2.2, "Expected ratio near 2.0, got $ratio")
    }

    // -------------------------------------------------------------------------
    // 4. Testing read-limits, filtering, “Fail” coverage, etc.
    // -------------------------------------------------------------------------

    @Test
    fun testFailCoverage() {
        // This Gen always fails. Should never produce a value => always Filtered
        val failGen = Gen.Fail
        var produced = false
        repeat(100) {
            val res = failGen.sample(random)
            if (res != null) produced = true
        }
        assertFalse(produced, "Fail gen should never produce a value")
    }

    @Test
    fun testReadN_EofCoverage() {
        // Force an artificially tiny tape so we always run out of bits
        val bitInput = BitInput.of(Tape(TapeSeed(1234L, MutableBitDeque())), limit = 2)
        val result = Gen.int(0..100).sampleR(bitInput)
        // Because we want 32 bits for an int in the worst path, we only have 2 => Eof
        assertTrue(result is RunResult.Eof, "Should run out of bits quickly")
    }

    // -------------------------------------------------------------------------
    // 5. Minimization & property-failure tests
    // -------------------------------------------------------------------------

    @Test
    fun testMinimize_FindsSmallerValue() {
        // Suppose we want to find a number divisible by 17 in [0..999].
        // Then we minimize. The minimal number that satisfies % 17 == 0 is 0, but let's see if we can find it.

        val gen = Gen.int(0..<1000)
        // Step 1: find *some* solution
        val tapeVal = gen.satisfy(5000, seed = 0x1234) { it % 17 == 0 }
        if (tapeVal == null) {
            fail("No solution found for it % 17 == 0 (very unlikely with 5000 tries)")
        }

        // Step 2: Minimize it
        val minimized = gen.minimize(tapeVal, iters = 2000, seed = 0x9999) { it % 17 == 0 }
        assertNotNull(minimized, "Should succeed in minimizing")

        // The result should be 0 or 17 or some multiple, but 0 is the actual minimal
        assertEquals(0, minimized.result, "Minimizer should find 0 as minimal solution for x % 17 == 0 in [0..999]")
    }

    @Test
    fun testForeachMin_CatchesExceptionAndMinimizes() {
        // Use foreachMin to generate some random strings that occasionally throw an exception
        // Then see if it catches + tries to minimize

        // We'll pick a string length up to 100, so we might eventually generate "boom" or something
        val genStr = Gen.string(Gen.int(0..100), Gen.range('a'..'z'))
        var caught = false

        try {
            genStr.foreachMin(random, iters = 10000, minimizerSteps=100000) { s ->
                if (s.contains("abc")) {
                    throw IllegalStateException("We do not like 'abc'")
                }
            }
        } catch (e: MinimizedException) {
            caught = true
            println("MinimizedException was thrown, minimized value is: ${e.value}")
            // We can test that the minimized string indeed contains "abc"
            assertTrue(e.value is String && (e.value as String).contains("abc"))
        }

        assertTrue(caught, "We expected to eventually catch an exception with 'abc'")
    }

    // -------------------------------------------------------------------------
    // 6. Test string, repeat, lazy list, etc.
    // -------------------------------------------------------------------------

    @Test
    fun testString_Explicit() {
        // A direct check of Gen.string
        val strGen = Gen.string(Gen.int(5..10), Gen.range('A'..'Z'))
        strGen.foreach(random, 100) {
            assertTrue(it.length in 5..10, "String length in [5..10]")
            assertTrue(it.all { ch -> ch in 'A'..'Z' }, "All chars in 'A'..'Z'")
        }
    }

    @Test
    fun testNullable() {
        // Check the code path for .nullable() => oneOfGen(Done(null), map { it })
        val nullableInt = Gen.int(0..10).nullable().map { it ?: -1 }
        var nullCount = 0
        var nonNullCount = 0
        val N = 1000
        Gen.foreach(nullableInt, N) {
            if (it == -1) nullCount++ else nonNullCount++
        }
        // Should generate both null and non-null
        assertTrue(nullCount in 1..(N-1), "Must produce some null and some non-null. nullCount=$nullCount")
        assertTrue(nonNullCount in 1..(N-1), "Must produce some null and some non-null. nonNullCount=$nonNullCount")
    }

    @Test
    fun testDelay() {
        // For coverage: Gen.delay { ... }. Ensure the function is only evaluated lazily.
        var calls = 0
        val delayed = Gen.delay {
            calls++
            Gen.int(0..10)
        }
        // Check we can sample from it without error
        repeat(100) {
            delayed.sample(random)
        }
        // At least we know it was evaluated at some point.
        // 100 calls to sample => might call the thunk 100 times or fewer, depending on short-circuits.
        assertTrue(calls > 0, "Gen.delay was invoked lazily")
    }

    // -------------------------------------------------------------------------
    // 7. Thorough check for “freqGen” usage with weighting
    // -------------------------------------------------------------------------

    @Test
    fun testFreqGen() {
        val weightedGen = Gen.freqGen(
            5 to Gen.const("HighWeight"),
            1 to Gen.const("LowWeight")
        )
        var highCount = 0
        var lowCount = 0
        val N = 10_000
        weightedGen.foreach(random, N) {
            if (it == "HighWeight") highCount++ else lowCount++
        }
        val ratio = highCount.toDouble() / lowCount
        // We expect ~5 to 1 ratio
        assertTrue(ratio in 4.0..6.0, "Expected ratio near 5.0, got $ratio")
    }

    // -------------------------------------------------------------------------
    // Other tests
    // -------------------------------------------------------------------------

//    @Test
//    fun testIntGenDistribution() {
//        val N = 10000000
//        for (M in listOf(3, 4, 5, 7, 11, 13, 16, 17)) {
//            val random = SplittableRandom()
//            val freq = IntArray(M) { 0 }
//            val list = Gen.int(0..<M).foreach(random, N) { freq[it] += 1 }
//            for (i in 0 until M) {
//                val it = freq[i]
//                val p = it.toDouble() / N
//                val q = 1.0 / freq.size.toDouble()
//                val npq = N * q * (1 - q)
//                val s2 = sqrt(npq) / N
//                val z = (it - N * q) / sqrt(N * q * (1 - q))
//                println("p=$p+-${s2} (z=$z)")
//            }
//            println()
//        }
//    }
}
