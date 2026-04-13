// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.random.gen.util

import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodecsTests {
    private class DequeReader(private val dq: MutableBitDeque) : Codecs.ReadBits {
        override fun read(n: Int): ULong? {
            require(n in 0..64) { "read(n): n=$n out of range" }
            if (dq.size < n.toLong()) return null
            return dq.removeFirst(n, BitOrder.MSB_FIRST).toULong()
        }
    }

    private class DequeWriter(private val dq: MutableBitDeque) : Codecs.WriteBits {
        override fun write(value: ULong, n: Int) {
            require(n in 0..64) { "write(n): n=$n out of range" }
            for (i in n - 1 downTo 0) {
                val bit = ((value shr i) and 1uL) == 1uL
                dq.add(bit)
            }
        }
    }

    private fun newRW(): Triple<MutableBitDeque, Codecs.ReadBits, Codecs.WriteBits> {
        val dq = MutableBitDeque()
        return Triple(dq, DequeReader(dq), DequeWriter(dq))
    }

    // ---------------- Bool ----------------

    @Test
    fun bool_roundtrip_and_raw_bits() {
        val (dq, r, w) = newRW()
        Codecs.writeBool(true, w)
        Codecs.writeBool(false, w)

        // Raw bit check: by design, readBool returns true for 0, so writeBool(true) must emit 0.
        assertEquals(2L, dq.size)
        assertTrue(dq[0L]) // first bit is 0 for true
        assertFalse(dq[1L]) // second bit is 1 for false

        // Round-trip check
        assertEquals(true, Codecs.readBool(r))
        assertEquals(false, Codecs.readBool(r))
        assertEquals(0L, dq.size)
    }

    // ------------- UInt (Truncated Binary Encoding) -------------

    private fun tbeBitLength(range: UIntRange, value: UInt): Int {
        val m = range.last - range.first + 1u
        val k = 31 - m.countLeadingZeroBits()
        val twoK = 1u shl k
        val t = (twoK shl 1) - m
        val d = value - range.first
        return if (d < t) k else k + 1
    }

    @Test
    fun uintTBE_exhaustive_small_ranges() {
        val ranges =
            listOf(
                0u..0u,
                0u..1u, // m=2
                0u..2u, // m=3 (truly truncated)
                5u..13u, // m=9 (k=3, t= (16-9)=7)
                123u..145u, // m=23
            )
        for (range in ranges) {
            for (v in range) {
                val (dq, r, w) = newRW()

                // write -> read
                Codecs.writeUintTBE(v, range, w)

                // Check code length is k or k+1 as per TBE theory
                val expectedLen = tbeBitLength(range, v)
                assertEquals(
                    expectedLen.toLong(),
                    dq.size,
                    "Unexpected TBE length for $v in $range",
                )

                val v2 = Codecs.readUintTBE(range, r)
                assertEquals(v, v2, "TBE round-trip failed for $v in $range")

                // no leftover bits
                assertEquals(0L, dq.size)
            }
        }
    }

    @Test
    fun uintTBE_randomized_medium() {
        val rng = Random(1337)
        repeat(200) {
            val base = rng.nextInt(0, 1_000)
            val m = rng.nextInt(1, 200) // size of range
            val range = base.toUInt()..(base + m - 1).toUInt()
            val v = (range.first + rng.nextInt(0, m).toUInt())
            val (dq, r, w) = newRW()
            Codecs.writeUintTBE(v, range, w)
            val v2 = Codecs.readUintTBE(range, r)
            assertEquals(v, v2)
            assertEquals(0L, dq.size)
        }
    }

    // ---------------- UInt (general) ----------------

    @Test
    fun uint_general_various_ranges() {
        // Singleton
        run {
            val range = 7u..7u
            val (dq, r, w) = newRW()
            Codecs.writeUint(7u, range, w)
            assertEquals(0L, dq.size) // writes nothing for singleton
            assertEquals(7u, Codecs.readUint(range, r))
        }

        // Power of two sized (shifted)
        run {
            val start = 5u
            val size = 1u shl 13 // 8192
            val range = start..(start + size - 1u)
            val value = start + 1234u
            val (dq, r, w) = newRW()
            Codecs.writeUint(value, range, w)
            assertEquals(13L, dq.size) // exactly log2(size)
            assertEquals(value, Codecs.readUint(range, r))
            assertEquals(0L, dq.size)
        }

        // Non-power-of-two (should use ceil(log2(m)) bits and be accepted in first pass)
        run {
            val range = 0u..1000u // m=1001, L=10
            val value = 987u
            val (dq, r, w) = newRW()
            Codecs.writeUint(value, range, w)
            assertEquals(10L, dq.size)
            assertEquals(value, Codecs.readUint(range, r))
            assertEquals(0L, dq.size)
        }

        // Full 32-bit range
        run {
            val rng = Random(42)
            repeat(50) {
                val value = rng.nextInt().toUInt()
                val (dq, r, w) = newRW()
                Codecs.writeUint(value, 0u..UInt.MAX_VALUE, w)
                assertEquals(32L, dq.size)
                assertEquals(value, Codecs.readUint(0u..UInt.MAX_VALUE, r))
                assertEquals(0L, dq.size)
            }
        }
    }

    // ---------------- Int ----------------

    @Test
    fun int_roundtrip_edge_cases_and_random() {
        // Singleton
        run {
            val range = 123..123
            val (dq, r, w) = newRW()
            Codecs.writeInt(123, range, w)
            assertEquals(0L, dq.size)
            assertEquals(123, Codecs.readInt(range, r))
        }

        // Spanning negative..positive
        run {
            val range = -17..23
            for (v in range) {
                val (dq, r, w) = newRW()
                Codecs.writeInt(v, range, w)
                val v2 = Codecs.readInt(range, r)
                assertEquals(v, v2)
                assertEquals(0L, dq.size)
            }
        }

        // Full 32-bit range (raw bit identity)
        run {
            val values =
                listOf(Int.MIN_VALUE, -1, 0, 1, 123456789, Int.MAX_VALUE) +
                    List(50) { Random(9).nextInt() }
            for (v in values) {
                val (dq, r, w) = newRW()
                Codecs.writeInt(v, Int.MIN_VALUE..Int.MAX_VALUE, w)
                assertEquals(32L, dq.size)
                val v2 = Codecs.readInt(Int.MIN_VALUE..Int.MAX_VALUE, r)
                assertEquals(v, v2)
                assertEquals(0L, dq.size)
            }
        }
    }

    // ---------------- Double U[0,1) ----------------

    @Test
    fun doubleU01_roundtrip_and_clamping() {
        // Exact fractions at a few bit-widths
        for (bits in listOf(1, 2, 3, 7, 13, 53)) {
            val scale = 1L shl bits
            val (dq1, r1, w1) = newRW()
            val v1 = 0.0
            Codecs.writeDoubleU01(v1, bits, w1)
            val read1 = Codecs.readDoubleU01(bits, r1)
            assertEquals(0.0, read1)
            assertEquals(0L, dq1.size)

            val (dq2, r2, w2) = newRW()
            val v2 = (scale - 1).toDouble() / scale.toDouble()
            Codecs.writeDoubleU01(v2, bits, w2)
            val read2 = Codecs.readDoubleU01(bits, r2)
            assertEquals(v2, read2) // exactly representable binary fraction
            assertEquals(0L, dq2.size)
        }

        // Randomized in-range values
        val rng = Random(2024)
        repeat(100) {
            val bits = rng.nextInt(1, 54)
            val x = rng.nextDouble() // [0,1)
            val (dq, r, w) = newRW()
            Codecs.writeDoubleU01(x, bits, w)
            val y = Codecs.readDoubleU01(bits, r)
            val scale = 1L shl bits
            val expected = kotlin.math.floor(x * scale.toDouble()) / scale.toDouble()
            assertEquals(expected, y)
            assertEquals(0L, dq.size)
        }

        // Out-of-range clamping
        run {
            val bits = 8
            val (dqA, rA, wA) = newRW()
            Codecs.writeDoubleU01(-0.5, bits, wA)
            assertEquals(0.0, Codecs.readDoubleU01(bits, rA))
            assertEquals(0L, dqA.size)

            val (dqB, rB, wB) = newRW()
            Codecs.writeDoubleU01(1.7, bits, wB)
            val y = Codecs.readDoubleU01(bits, rB)!!
            val max = ((1 shl bits) - 1).toDouble() / (1 shl bits).toDouble()
            assertEquals(max, y)
            assertEquals(0L, dqB.size)
        }
    }

    // ---------------- Bytes ----------------

    @Test
    fun bytes_roundtrip_various_lengths() {
        val rng = Random(7)
        val lengths = listOf(0, 1, 2, 7, 8, 15, 16, 31, 32, 100)
        for (len in lengths) {
            val arr = ByteArray(len) { rng.nextInt(0, 256).toByte() }
            val (dq, r, w) = newRW()
            Codecs.writeBytes(arr, w)
            val out = Codecs.readBytes(len, r)
            assertContentEquals(arr, out, "byte round-trip failed for length $len")
            assertEquals(0L, dq.size)
        }
    }

    // ---------------- Mixed stream (integration) ----------------

    @Test
    fun mixed_stream_roundtrip() {
        val (dq, r, w) = newRW()

        // Write a cocktail of values
        Codecs.writeBool(true, w) // 1 bit (0)
        Codecs.writeUint(42u, 0u..1000u, w) // 10 bits
        Codecs.writeUintTBE(9u, 0u..12u, w) // 3 or 4 bits (TBE)
        Codecs.writeInt(-12345, -20000..20000, w) // variable
        Codecs.writeDoubleU01(0.3141592653589793, 17, w) // 17 bits
        Codecs.writeBytes(byteArrayOf(1, 2, 3, -4, 127), w) // 40 bits

        // Now read back in the same order
        assertEquals(true, Codecs.readBool(r))
        assertEquals(42u, Codecs.readUint(0u..1000u, r))
        assertEquals(9u, Codecs.readUintTBE(0u..12u, r))
        assertEquals(-12345, Codecs.readInt(-20000..20000, r))
        assertEquals(
            kotlin.math.floor(0.3141592653589793 * (1 shl 17)) / (1 shl 17).toDouble(),
            Codecs.readDoubleU01(17, r),
        )
        assertContentEquals(byteArrayOf(1, 2, 3, -4, 127), Codecs.readBytes(5, r))

        // Stream fully consumed
        assertEquals(0L, dq.size)
    }

    @Test
    fun roundtrip_random() {
        val rnd = Random(2024)
        val (dq, r, w) = newRW()

        repeat(20000) {
            val m = 1UL + (rnd.nextLong().toULong() and ((1UL shl 20) - 1UL)) // size in [1, ~1e6]
            val first = rnd.nextLong().toULong()
            val last = first + (m - 1UL)
            val value = first + (rnd.nextLong().toULong() % m)

            Codecs.writeULong(value, first..last, w)
            val got = Codecs.readULong(first..last, r)
            assertEquals(value, got)
            assertEquals(0L, dq.size)
        }
    }

    private fun safeRangeOfSize(m: ULong, rng: Random = Random(1)): ULongRange {
        require(m > 0UL)
        if (m == 1UL) {
            val f = rng.nextULong()
            return f..f
        }
        val maxFirst =
            ULong.MAX_VALUE - (m - 1UL) // largest first allowed so that first+(m-1) doesn’t wrap
        val first = rng.nextULong() % (maxFirst + 1UL)
        val last = first + (m - 1UL) // guaranteed not to overflow
        check(first <= last)
        return first..last
    }

    @Test
    fun roundtrip_huge() {
        val (dq, r, w) = newRW()
        val rng = Random(42)
        val sizes =
            listOf(
                1UL shl 63, // 2^63
                (1UL shl 63) + 17UL, // > 2^63  ⇒ triggers L==64 path in reader
                (1UL shl 63) - 3UL, // just below 2^63
                ULong.MAX_VALUE, // 2^64 - 1
                ULong.MAX_VALUE - 1UL, // 2^64 - 2
            )

        for (m in sizes) {
            val range = safeRangeOfSize(m, rng)
            val delta = if (m > 7UL) 7UL else (m - 1UL)
            val value = range.last - delta

            Codecs.writeULong(value, range, w)
            val got = Codecs.readULong(range, r)
            assertEquals(value, got)
            assertEquals(0L, dq.size)
        }
    }

    @Test
    fun roundtrip_full_width() {
        val (dq, r, w) = newRW()
        val range = 0UL..ULong.MAX_VALUE // special-cased by both read/write
        val value = 0xDEADBEEFCAFEBABEUL
        Codecs.writeULong(value, range, w)
        val got = Codecs.readULong(range, r)
        assertEquals(value, got)
        assertEquals(0L, dq.size)
    }

    @Test
    fun roundtrip_random_large() {
        val (dq, r, w) = newRW()
        val rng = Random(7)
        repeat(10_000) {
            val m = (rng.nextULong() shr 1) or 1UL // random odd size up to 2^63
            val range = safeRangeOfSize(m, rng)
            val value = range.first + (rng.nextULong() % m)
            Codecs.writeULong(value, range, w)
            assertEquals(value, Codecs.readULong(range, r))
            assertEquals(0L, dq.size)
        }
    }
}
