package one.wabbit.random.gen

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class TapeSeedRoundTripTest {
    @Test
    fun testTapeSeedBase58Roundtrip() {
        repeat(100) {
            // Generate a random seed
            val randomSeed = Random.nextLong()

            // Generate a random bit count (here, up to 256 just as a demo)
            val bitCount = Random.nextInt(0, 256)

            // Create a random BitSequence (all Booleans)
            // If your BitSequence is constructed differently,
            // adapt the code to fill it with random bits.
            val randomFlips = MutableBitDeque()
            repeat(bitCount) {
                randomFlips.add(Random.nextBoolean())
            }

            // Create the TapeSeed
            val original = TapeSeed(randomSeed, randomFlips)

            // Encode to Base58
            val encoded = original.toBase58String()

            // Decode back (you need a companion object or top-level fun fromBase58String)
            val decoded = TapeSeed.fromBase58String(encoded)

            // Verify the seed
            assertEquals(original.seed, decoded.seed, "Seeds did not match after round-trip")

            // Verify flips size
            assertEquals(original.flips.size, decoded.flips.size, "Flip size mismatch")

            // Verify each bit
            for (i in 0 until original.flips.size) {
                assertEquals(
                    original.flips[i],
                    decoded.flips[i],
                    "Flip bit mismatch at index $i"
                )
            }
        }
    }
}