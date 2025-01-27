package one.wabbit.random.gen

import java.util.SplittableRandom

/**
 * [Tape] is an internal data structure for reading random bits in a deterministic,
 * replayable way. It allows us to reconstruct the entire chain of "random" decisions
 * for a particular generated value, as well as attempt "shrinks" by flipping bits
 * near the beginning of the tape.
 *
 * The design here is inspired by Hedgehog-style property-based testing:
 *   - We store bits in a [MutableBitDeque].
 *   - Each generated value reads some number of bits off the tape.
 *   - If a test fails, we can try toggling bits early in the tape to see if that
 *     produces a "smaller" failing input.
 *
 * Normally, you won't need to use [Tape] directly—just call higher-level functions
 * like [Gen.checkAll], [Gen.foreach], or [Gen.sample]. However, if you need custom
 * manipulations or want to debug the bit-level representation of generated values,
 * you can delve into [Tape], [TapeSeed], and the related classes.
 *
 * NOTE: This class may change in future versions. Rely on it only if you are prepared
 * for potential internal API adjustments.
 */
class Tape(val seed: TapeSeed) {
    var read = 0L
    var read0 = 0L
    var read1 = 0L

    val leftover = MutableBitDeque()
    val generator = SplittableRandom(seed.seed)

    fun read(n: Int): ULong {
        require(n in 0..64) {
            "Tape.read(n): n must be within [0..64], got $n"
        }

        var value = 0UL
        for (i in 0 until n) {
            if (leftover.size == 0L) {
                leftover.addAll(generator.nextLong())
            }

            val b = if (leftover.removeFirst()) 1UL else 0UL
            val f = if (read < seed.flips.size) {
                if (seed.flips[read]) 1UL else 0UL
            } else 0UL
            val v = b xor f
            read += 1

            if (v == 0UL) read0 += 1
            else read1 += 1

            // MSB first!
            value = value or (v shl (n - i - 1))
        }
        return value
    }
}
