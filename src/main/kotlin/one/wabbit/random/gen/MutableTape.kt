package one.wabbit.random.gen

import java.util.SplittableRandom

class TapeSeed(val seed: Long, val flips: MutableBitDeque)

class Tape(val seed: TapeSeed) {
    var read = 0L
    var read0 = 0L
    var read1 = 0L

    val leftover = MutableBitDeque()
    val generator = SplittableRandom(seed.seed)

    fun read(n: Int): ULong {
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
