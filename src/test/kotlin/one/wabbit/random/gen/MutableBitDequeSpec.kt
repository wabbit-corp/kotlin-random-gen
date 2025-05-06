package one.wabbit.random.gen

import java.util.*
import kotlin.math.min
import kotlin.test.*
import kotlinx.serialization.json.Json

class MutableBitDequeSpec {
    @Test
    fun test() {
        val buf = MutableBitDeque()
        // assertEquals(buf, serializeDeserialize(buf))
        assertEquals("MutableBitDeque(\"\")", buf.toString())
        buf.add(true)
        assertEquals("MutableBitDeque(\"1\")", buf.toString())
        buf.add(false)
        assertEquals("MutableBitDeque(\"10\")", buf.toString())
        buf.addAll(0xF1.toByte(), BitOrder.LSB_FIRST)
        assertEquals("MutableBitDeque(\"1010001111\")", buf.toString())
        assertEquals(5L, buf.removeFirst(4, BitOrder.LSB_FIRST))
        assertEquals(6L, buf.size)
        // b001111 = 15
        assertEquals(15, buf.removeFirst(6, BitOrder.MSB_FIRST))
    }

    private fun randomBoolean(rng: SplittableRandom): Boolean =
        rng.nextBoolean()

    private fun randomByte(rng: SplittableRandom): Byte =
        rng.nextInt(Byte.MIN_VALUE.toInt(), Byte.MAX_VALUE.toInt() + 1).toByte()

    private fun randomLong(rng: SplittableRandom): Long =
        rng.nextLong()

    /**
     * Verify contents of [actual] vs [reference].
     */
    private fun assertBitDequeEquals(
        actual: MutableBitDeque,
        reference: ReferenceBitDeque,
        message: String = ""
    ) {
        assertEquals(reference.size, actual.size, "Size mismatch. $message")
        for (i in 0 until reference.size) {
            assertEquals(reference[i], actual[i], "Mismatch at index $i. $message")
        }
    }

    /**
     * Basic test with a few known operations (like your original).
     */
    @Test
    fun testBasicOperations() {
        val buf = MutableBitDeque()
        assertEquals("MutableBitDeque(\"\")", buf.toString())

        buf.add(true)
        assertEquals("MutableBitDeque(\"1\")", buf.toString())

        buf.add(false)
        assertEquals("MutableBitDeque(\"10\")", buf.toString())

        buf.addAll(0xF1.toByte(), BitOrder.LSB_FIRST)
        assertEquals("MutableBitDeque(\"1010001111\")", buf.toString())

        // Remove first 4 bits, LSB first => 5
        val removed4 = buf.removeFirst(4, BitOrder.LSB_FIRST)
        assertEquals(5L, removed4)
        assertEquals(6L, buf.size)

        // Remove next 6 bits, MSB first => 15
        val removed6 = buf.removeFirst(6, BitOrder.MSB_FIRST)
        assertEquals(15, removed6)
        assertEquals(0L, buf.size)
    }

    /**
     * Test round-trip of a random Byte for both bit orders.
     */
    @Test
    fun testRoundTripByte() {
        val trials = 10_000
        val rng = SplittableRandom(0xDEADBEEF)
        repeat(trials) {
            val b = randomByte(rng)
            val order = if (rng.nextBoolean()) BitOrder.MSB_FIRST else BitOrder.LSB_FIRST

            val deque = MutableBitDeque()
            deque.addAll(b, order)
            val reconstructed = deque.removeFirst(8, order) // re-read the 8 bits
            val roundTrip = (reconstructed and 0xFF).toByte()
            assertEquals(b, roundTrip, "Byte mismatch with order=$order")
        }
    }

    /**
     * Test round-trip of a random Long for both bit orders.
     */
    @Test
    fun testRoundTripLong() {
        val trials = 5_000
        val rng = SplittableRandom(123456789L)
        repeat(trials) {
            val v = randomLong(rng)
            val order = if (rng.nextBoolean()) BitOrder.MSB_FIRST else BitOrder.LSB_FIRST

            val deque = MutableBitDeque()
            deque.addAll(v, order)

            val reconstructed = deque.removeFirst(64, order)
            assertEquals(v, reconstructed, "Long mismatch with order=$order")
        }
    }

    /**
     * A big randomized test that does many operations in sequence:
     * add, removeFirst, fillAndSet, set(index), addAll(byte/long).
     */
    @Test
    fun testRandomOperationsSequence() {
        val rng = SplittableRandom(0xCAFEBABE)
        val trials = 2_000

        val deque = MutableBitDeque()
        val ref = ReferenceBitDeque()

        repeat(trials) {
            val op = rng.nextInt(6)
            when (op) {
                0 -> {
                    // add
                    val bit = randomBoolean(rng)
                    deque.add(bit)
                    ref.add(bit)
                }
                1 -> {
                    // removeFirst single bit
                    if (ref.size > 0) {
                        val expected = ref.removeFirst()
                        val actual = deque.removeFirst()
                        assertEquals(expected, actual, "removeFirst() mismatch")
                    }
                }
                2 -> {
                    // fillAndSet
                    val index = rng.nextLong(0, 50)
                    val bit = randomBoolean(rng)
                    deque.fillAndSet(index, bit)
                    ref.fillAndSet(index, bit)
                }
                3 -> {
                    // set(index, value)
                    if (ref.size > 0) {
                        val idx = rng.nextLong(ref.size)
                        val bit = randomBoolean(rng)
                        deque[idx] = bit
                        ref[idx] = bit
                    }
                }
                4 -> {
                    // removeFirst(n, order)
                    if (ref.size > 0) {
                        val n = rng.nextInt(1, min(ref.size.toInt(), 16) + 1)
                        val order = if (rng.nextBoolean()) BitOrder.MSB_FIRST else BitOrder.LSB_FIRST
                        val expected = ref.removeFirst(n, order)
                        val actual = deque.removeFirst(n, order)
                        assertEquals(expected, actual, "removeFirst($n, $order) mismatch")
                    }
                }
                5 -> {
                    // addAll(byte or long)
                    if (rng.nextBoolean()) {
                        val b = randomByte(rng)
                        val order = if (rng.nextBoolean()) BitOrder.MSB_FIRST else BitOrder.LSB_FIRST
                        deque.addAll(b, order)
                        // emulate in ref
                        for (i in 0 until 8) {
                            val bit = when (order) {
                                BitOrder.MSB_FIRST -> (b.toInt() and (1 shl (7 - i))) != 0
                                BitOrder.LSB_FIRST -> (b.toInt() and (1 shl i)) != 0
                            }
                            ref.add(bit)
                        }
                    } else {
                        val v = randomLong(rng)
                        val order = if (rng.nextBoolean()) BitOrder.MSB_FIRST else BitOrder.LSB_FIRST
                        deque.addAll(v, order)
                        // emulate in ref
                        for (i in 0 until 64) {
                            val bit = when (order) {
                                BitOrder.MSB_FIRST -> (v and (1L shl (63 - i))) != 0L
                                BitOrder.LSB_FIRST -> (v and (1L shl i)) != 0L
                            }
                            ref.add(bit)
                        }
                    }
                }
            }
            // occasionally check everything
            if (rng.nextInt(20) == 0) {
                assertBitDequeEquals(deque, ref, "After operation $op")
            }
        }
        // final check
        assertBitDequeEquals(deque, ref, "Final check after $trials operations")
    }

    /**
     * Test copy() and structural equality & hashCode in random scenarios.
     */
    @Test
    fun testCopyEqualsAndHashCode() {
        val rng = SplittableRandom(987654321)
        val trials = 2_000
        repeat(trials) {
            val deque = MutableBitDeque()
            // fill with random bits
            val length = rng.nextInt(50)
            repeat(length) {
                deque.add(randomBoolean(rng))
            }
            val copy = deque.copy()

            // copy must equal the original
            assertEquals(deque, copy, "copy should equal original")
            assertEquals(deque.hashCode(), copy.hashCode(), "hashCode mismatch with copy")

            // small chance we tweak one bit in copy
            if (rng.nextInt(10) == 0 && copy.size > 0) {
                val i = rng.nextInt(copy.size.toInt())
                copy[i.toLong()] = !copy[i.toLong()]
                assertNotEquals(deque, copy, "copy with one changed bit should differ")
            }
        }
    }

    /**
     * Test boundary conditions:
     *  - removeFirst from an empty deque
     *  - fillAndSet with index = 0
     *  - fillAndSet with index > size
     *  - get / set out-of-range (expected to throw IndexOutOfBoundsException)
     */
    @Test
    fun testBoundaryConditions() {
        val dq = MutableBitDeque()

        // removing a single bit from empty should throw NoSuchElementException
        assertFailsWith<IllegalStateException> {
            dq.removeFirst()
        }

        // removing multiple bits from empty should also fail
        assertFailsWith<IllegalStateException> {
            dq.removeFirst(5, BitOrder.MSB_FIRST)
        }

        // fillAndSet index = 0 => should directly add one bit at position 0
        dq.fillAndSet(0, true)
        assertEquals(1, dq.size)
        assertTrue(dq[0])

        // fillAndSet an index bigger than current size
        dq.fillAndSet(5, true) // indices 1..4 become false, index 5 is true
        assertEquals(6, dq.size)
        assertFalse(dq[1])
        assertFalse(dq[2])
        assertFalse(dq[3])
        assertFalse(dq[4])
        assertTrue(dq[5])

        // get out-of-range => should throw
        assertFailsWith<IllegalArgumentException> {
            dq[100] // no such index
        }

        // set out-of-range => should throw
        assertFailsWith<IllegalArgumentException> {
            dq[50] = false
        }
    }

    /**
     * Verify equals() with something that's not a MutableBitDeque.
     * Also verify a brand-new empty deque doesn't equal a non-empty one.
     */
    @Test
    fun testEqualsWithOtherTypes() {
        val dq = MutableBitDeque()
        dq.add(true)
        dq.add(false)

        // compare with a string => must be false
        assertFalse(dq.equals("I am not a deque"))

        // compare with null => false
        assertFalse(dq.equals(null))

        // brand-new empty vs dq => not equal
        val empty2 = MutableBitDeque()
        assertNotEquals(dq, empty2)

        // brand-new empty vs empty => equal
        val empty3 = MutableBitDeque()
        assertEquals(empty2, empty3)
    }

    /**
     * Test that length == size in all cases (they're synonyms here).
     */
    @Test
    fun testLengthAndSize() {
        val dq = MutableBitDeque()
        assertEquals(0L, dq.size)
        assertEquals(0L, dq.length)

        dq.add(true)
        assertEquals(1L, dq.size)
        assertEquals(1L, dq.length)

        repeat(5) { dq.add(false) }
        assertEquals(6L, dq.size)
        assertEquals(6L, dq.length)

        dq.removeFirst()
        assertEquals(5L, dq.size)
        assertEquals(5L, dq.length)
    }

    /**
     * Quick test of toString() on various sizes.
     */
    @Test
    fun testToStringVariousSizes() {
        val dq = MutableBitDeque()
        assertEquals("MutableBitDeque(\"\")", dq.toString())

        dq.add(true)
        assertEquals("MutableBitDeque(\"1\")", dq.toString())

        dq.add(false)
        dq.add(true)
        assertEquals("MutableBitDeque(\"101\")", dq.toString())
    }

    @Test
    fun testAllBytesMSBAndLSB() {
        // We'll test the sign-extension correctness by trying all bytes -128..127
        for (b in Byte.MIN_VALUE..Byte.MAX_VALUE) {
            checkByteRoundTrip(b.toByte(), BitOrder.MSB_FIRST)
            checkByteRoundTrip(b.toByte(), BitOrder.LSB_FIRST)
        }
    }

    private fun checkByteRoundTrip(b: Byte, order: BitOrder) {
        // Reference model (ArrayDeque<Boolean>)
        val ref = ReferenceBitDeque()
        // Our optimized LongArray version
        val opt = MutableBitDeque()

        // Add the byte in the given order
        ref.addAll(b, order)
        opt.addAll(b, order)

        // Now remove 8 bits from each in the same order
        val refVal = ref.removeFirst(8, order)
        val optVal = opt.removeFirst(8, order)

        val refByte = (refVal and 0xFF).toByte()
        val optByte = (optVal and 0xFF).toByte()

        assertEquals(
            b, refByte,
            "Reference mismatch: expected byte=$b, got $refByte, order=$order"
        )
        assertEquals(
            b, optByte,
            "Optimized mismatch: expected byte=$b, got $optByte, order=$order"
        )
    }

    /**
     * Remove 0 bits from an empty or non-empty deque. Should return 0L, do nothing.
     */
    @Test
    fun testRemoveFirstZero() {
        val dq = MutableBitDeque()
        // removing 0 bits from empty => no error
        val vEmpty = dq.removeFirst(0, BitOrder.MSB_FIRST)
        assertEquals(0L, vEmpty)
        assertEquals(0L, dq.size)

        // fill some bits
        dq.addAll(0b10101010.toByte()) // 8 bits
        // remove 0 bits from non-empty => should yield 0, not reduce size
        val v2 = dq.removeFirst(0, BitOrder.LSB_FIRST)
        assertEquals(0L, v2)
        assertEquals(8L, dq.size)
    }

    /**
     * fillAndSet(0, true) on empty => we get 1 bit set.
     */
    @Test
    fun testFillAndSetZeroIndexOnEmpty() {
        val dq = MutableBitDeque()
        dq.fillAndSet(0, true)
        assertEquals(1L, dq.size)
        assertTrue(dq[0])
    }

    /**
     * fillAndSet(size, value) => appends exactly one bit.
     */
    @Test
    fun testFillAndSetSize() {
        val dq = MutableBitDeque()
        dq.add(false)  // size=1
        dq.add(true)   // size=2
        assertEquals(2L, dq.size)

        // fillAndSet(2, true) => we want an extra bit at index=2
        dq.fillAndSet(2, true)
        assertEquals(3L, dq.size)
        assertFalse(dq[0])
        assertTrue(dq[1])
        assertTrue(dq[2])
    }

    /**
     * Large expansions: fillAndSet with big index => ensures capacity grows.
     * We'll not go too extreme, but enough to confirm expansions.
     */
    @Test
    fun testFillAndSetLargeIndex() {
        val dq = MutableBitDeque()
        dq.fillAndSet(100, true) // index=100 => 101 bits total
        assertEquals(101L, dq.size)
        // Indices 0..99 => false, 100 => true
        for (i in 0L..99L) {
            assertFalse(dq[i], "Bit at $i should be false.")
        }
        assertTrue(dq[100], "Bit at 100 should be true.")
    }

    /**
     * Quick check that removing 0 from a partially-filled buffer also works.
     * (Non-empty scenario, remove 0 => no change).
     */
    @Test
    fun testRemoveFirstZeroNonEmpty() {
        val dq = MutableBitDeque()
        dq.addAll(0b11110000.toByte()) // 8 bits
        val oldSize = dq.size
        val v = dq.removeFirst(0, BitOrder.MSB_FIRST)
        assertEquals(0L, v)
        assertEquals(oldSize, dq.size, "Size should be unchanged after removeFirst(0).")
    }

    /**
     * Optional: check custom serialization using kotlinx.serialization (KMP-friendly).
     * We'll ensure the bits match after round-trip.
     */
    @Test
    fun testSerializationRoundTrip() {
        val dq = MutableBitDeque()
        dq.add(true)
        dq.addAll(0b10101010.toByte()) // 8 bits
        dq.fillAndSet(10, true) // ensure we have more bits
        // Now let's do a JSON round-trip
        val json = Json.encodeToString(dq)
        val dq2 = Json.decodeFromString<MutableBitDeque>(json)

        // They should be equal
        assertEquals(dq, dq2)
        // Also have same string representation
        assertEquals(dq.toString(), dq2.toString())
    }
}
