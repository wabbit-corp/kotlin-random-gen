// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.random.gen

import one.wabbit.random.L64X128Random
import one.wabbit.random.gen.util.MutableBitDeque

/**
 * [RawTapeReader] is an internal data structure for reading random bits in a deterministic,
 * replayable way. It allows us to reconstruct the entire chain of "random" decisions for a
 * particular generated value.
 *
 * The design here is inspired by Hedgehog-style property-based testing:
 * - We store bits in a [one.wabbit.random.gen.util.MutableBitDeque].
 * - Each generated value reads some number of bits off the tape.
 * - If a test fails, we can try toggling bits early in the tape to see if that produces a "smaller"
 *   failing input.
 *
 * Normally, you won't need to use [RawTapeReader] directly; use `Gen.sample`, `Gen.foreach`, or
 * `Gen.foreachMin` instead. Direct access is useful when debugging the bit-level representation of a
 * generated value or building custom replay/minimization tooling around [TapeSeed].
 */
class RawTapeReader(
    /** Seed and flip sequence used to replay this tape. */
    val seed: TapeSeed
) {
    /** Total number of bits read. */
    var read = 0L
    /** Number of zero bits read after applying flips. */
    var read0 = 0L
    /** Number of one bits read after applying flips. */
    var read1 = 0L

    /** Buffered PRNG bits not yet consumed by [read]. */
    val leftover = MutableBitDeque()
    /** Underlying deterministic PRNG initialized from [seed]. */
    val generator = L64X128Random(seed.seed)

    /**
     * Reads [n] bits from the replay tape.
     *
     * Bits are returned MSB-first in the low [n] bits of the returned value.
     */
    fun read(n: Int): ULong {
        require(n in 0..64) { "Tape.read(n): n must be within [0..64], got $n" }

        var value = 0UL
        for (i in 0 until n) {
            if (leftover.size == 0L) {
                leftover.addAll(generator.nextLong())
            }

            val b = if (leftover.removeFirst()) 1UL else 0UL
            val f =
                if (read < seed.flips.size) {
                    if (seed.flips[read]) 1UL else 0UL
                } else {
                    0UL
                }
            val v = b xor f
            read += 1

            if (v == 0UL) {
                read0 += 1
            } else {
                read1 += 1
            }

            // MSB first!
            value = value or (v shl (n - i - 1))
        }
        return value
    }
}
