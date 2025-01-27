package one.wabbit.random.gen

/**
 * A reference bit deque storing each bit as Boolean in an ArrayDeque.
 * We use it to validate the behavior of MutableBitDeque.
 */
class ReferenceBitDeque {
    private val buf = ArrayDeque<Boolean>()

    val size: Long
        get() = buf.size.toLong()

    operator fun get(index: Long): Boolean = buf[index.toInt()]

    operator fun set(index: Long, value: Boolean) {
        buf[index.toInt()] = value
    }

    fun add(value: Boolean) {
        buf.add(value)
    }

    /**
     * fillAndSet(index, value): if index < size, set existing;
     * else append false bits until index, then set index-th bit.
     */
    fun fillAndSet(index: Long, value: Boolean) {
        require(index >= 0) { "Index cannot be negative: $index" }
        if (index < size) {
            buf[index.toInt()] = value
            return
        }
        // else append
        val needed = (index + 1) - size
        repeat((needed - 1).toInt()) {
            buf.add(false)
        }
        buf.add(value)
    }

    /**
     * Add 8 bits from a Byte, in MSB_FIRST or LSB_FIRST order.
     * Must cast to Int for the mask to avoid sign issues.
     */
    fun addAll(value: Byte, order: BitOrder) {
        for (i in 0 until 8) {
            val bit = when (order) {
                BitOrder.MSB_FIRST -> (value.toInt() and (1 shl (7 - i))) != 0
                BitOrder.LSB_FIRST -> (value.toInt() and (1 shl i)) != 0
            }
            add(bit)
        }
    }

    /**
     * Add 64 bits from a Long, in MSB_FIRST or LSB_FIRST order.
     */
    fun addAll(value: Long, order: BitOrder) {
        for (i in 0 until 64) {
            val bit = when (order) {
                BitOrder.MSB_FIRST -> (value and (1L shl (63 - i))) != 0L
                BitOrder.LSB_FIRST -> (value and (1L shl i)) != 0L
            }
            add(bit)
        }
    }

    /**
     * Remove the first bit or throw NoSuchElementException if empty.
     */
    fun removeFirst(): Boolean {
        return buf.removeFirst()
    }

    /**
     * Remove the first n bits in the given order, returning them as a Long.
     * If not enough bits remain, this should throw NoSuchElementException.
     */
    fun removeFirst(n: Int, order: BitOrder): Long {
        require(n in 0..64) {
            "removeFirst(n): n must be within [0..64], got $n"
        }
        check(buf.size >= n) { "Not enough bits. size=${buf.size}, requested=$n" }

        var value = 0L
        when (order) {
            BitOrder.MSB_FIRST -> {
                repeat(n) {
                    val b = removeFirst()
                    value = (value shl 1) or if (b) 1L else 0L
                }
            }
            BitOrder.LSB_FIRST -> {
                repeat(n) { i ->
                    if (removeFirst()) {
                        value = value or (1L shl i)
                    }
                }
            }
        }
        return value
    }

    override fun equals(other: Any?): Boolean {
        if (other !is ReferenceBitDeque) return false
        return buf == other.buf
    }

    override fun hashCode(): Int {
        return buf.hashCode()
    }

    override fun toString(): String {
        return buildString {
            append("RefDeque(\"")
            for (bit in buf) append(if (bit) '1' else '0')
            append("\")")
        }
    }
}