package one.wabbit.random.gen

import kotlin.experimental.and

private const val MAX_ARRAY_SIZE = Int.MAX_VALUE - 8

enum class BitOrder {
    MSB_FIRST,
    LSB_FIRST
}

// FIXME: really unoptimized
class MutableBitDeque {
    private var buf = ArrayDeque<Boolean>()

    val length: Long
        get() = buf.size.toLong()

    val size: Long
        get() = buf.size.toLong()

    operator fun get(index: Long): Boolean {
        return buf[index.toInt()]
    }

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

    fun addAll(value: Byte, order: BitOrder = BitOrder.MSB_FIRST) {
        for (i in 0 until 8) {
            val b = when (order) {
                BitOrder.MSB_FIRST -> (value and (1 shl (7 - i)).toByte()) != 0.toByte()
                BitOrder.LSB_FIRST -> (value and (1 shl i).toByte()) != 0.toByte()
            }
            add(b)
        }
    }

    fun addAll(value: Long, order: BitOrder = BitOrder.MSB_FIRST) {
        for (i in 0 until 64) {
            val b = when (order) {
                BitOrder.MSB_FIRST -> (value and (1L shl (63 - i))) != 0L
                BitOrder.LSB_FIRST -> (value and (1L shl i)) != 0L
            }
            add(b)
        }
    }

    fun removeFirst(): Boolean {
        return buf.removeFirst()
    }

    fun removeFirst(n: Int, order: BitOrder = BitOrder.MSB_FIRST): Long {
        var value = 0L
        for (i in 0 until n) {
            val b = removeFirst()
            value = when (order) {
                BitOrder.MSB_FIRST -> (value shl 1) or if (b) 1L else 0L
                BitOrder.LSB_FIRST -> (value shr 1) or if (b) (1L shl (n - 1)) else 0L
            }
        }
        return value
    }

    fun copy(): MutableBitDeque {
        val copy = MutableBitDeque()
        copy.buf = ArrayDeque(buf)
        return copy
    }

    override fun hashCode(): Int {
        return buf.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (other !is MutableBitDeque) {
            return false
        }
        return buf == other.buf
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("MutableBitDeque(\"")
        for (i in 0 until size) {
            sb.append(if (this[i]) '1' else '0')
        }
        sb.append("\")")
        return sb.toString()
    }
}
