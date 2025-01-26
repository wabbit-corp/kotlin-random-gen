package one.wabbit.random.gen

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.*
import kotlin.collections.ArrayDeque
import kotlin.math.min
import kotlin.test.*

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
     * A simple reference model that parallels MutableBitDeque,
     * using an ArrayDeque<Boolean> internally.
     */
    private class ReferenceBitDeque {
        val buf = ArrayDeque<Boolean>()
        val size: Long get() = buf.size.toLong()

        operator fun get(index: Long): Boolean = buf[index.toInt()]
        operator fun set(index: Long, value: Boolean) {
            buf[index.toInt()] = value
        }

        fun add(value: Boolean) {
            buf.add(value)
        }

        fun fillAndSet(index: Long, value: Boolean) {
            while (buf.size < index - 1) {
                buf.add(false)
            }
            buf.add(value)
        }

        fun removeFirst(): Boolean {
            return buf.removeFirst()
        }

        fun removeFirst(n: Int, order: BitOrder): Long {
            var value = 0L
            for (i in 0 until n) {
                val b = removeFirst()
                when (order) {
                    BitOrder.MSB_FIRST -> {
                        value = (value shl 1) or if (b) 1L else 0L
                    }
                    BitOrder.LSB_FIRST -> {
                        // set the i-th bit in "value" with b
                        if (b) value = value or (1L shl i)
                    }
                }
            }
            return value
        }

        override fun equals(other: Any?): Boolean {
            if (other !is ReferenceBitDeque) return false
            return buf == other.buf
        }

        override fun hashCode(): Int = buf.hashCode()

        override fun toString(): String {
            return buildString {
                append("RefDeque(\"")
                for (bit in buf) append(if (bit) '1' else '0')
                append("\")")
            }
        }
    }

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
                    // pick a random index possibly beyond size
                    val index = rng.nextLong(0, 50)
                    val bit = randomBoolean(rng)
                    deque.fillAndSet(index, bit)
                    ref.fillAndSet(index, bit)
                }
                3 -> {
                    // set(index, value) if index in range
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
            // occasionally check everything matches
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
        assertFailsWith<NoSuchElementException> {
            dq.removeFirst()
        }

        // removing multiple bits from empty should also fail
        assertFailsWith<IllegalArgumentException> {
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
        assertFailsWith<IndexOutOfBoundsException> {
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
}
