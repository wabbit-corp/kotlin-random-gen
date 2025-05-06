package one.wabbit.random.gen

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

enum class BitOrder {
    MSB_FIRST,
    LSB_FIRST
}

interface BitSequence {
    operator fun get(index: Long): Boolean
    val size: Long

    fun toMutableBitDeque(): MutableBitDeque
}

/**
 * A ring-buffer bit deque, storing bits in a [LongArray].
 * Now annotated with @Serializable, using a custom serializer that
 * efficiently stores only the "used" bits in a ByteArray.
 */
@Serializable(with = MutableBitDequeSerializer::class)
class MutableBitDeque : BitSequence {

    private var backing: LongArray = LongArray(4) // Start with 4 * 64 = 256 bits capacity
    internal var capacityInBits: Int = backing.size * 64

    internal var startBitIndex: Int = 0
    internal var endBitIndex: Int = 0

    override val size: Long
        get() = bitSize().toLong()

    val length: Long
        get() = size

    override operator fun get(index: Long): Boolean {
        require(index >= 0 && index < size) { "Index $index out of bounds for size $size" }
        val globalIndex = (startBitIndex + index.toInt()) % capacityInBits
        return getBitAt(globalIndex)
    }

    operator fun set(index: Long, value: Boolean) {
        require(index >= 0 && index < size) { "Index $index out of bounds for size $size" }
        val globalIndex = (startBitIndex + index.toInt()) % capacityInBits
        setBitAt(globalIndex, value)
    }

    fun add(value: Boolean) {
        ensureSpaceFor(1)
        setBitAt(endBitIndex, value)
        endBitIndex = (endBitIndex + 1) % capacityInBits
    }

    fun fillAndSet(index: Long, value: Boolean) {
        require(index >= 0) { "Index cannot be negative: $index" }
        if (index < size) {
            set(index, value)
            return
        }
        val neededToAdd = (index + 1) - size
        ensureSpaceFor(neededToAdd.toInt())
        // fill with false up to index-1
        repeat((neededToAdd - 1).toInt()) {
            add(false)
        }
        add(value)
    }

    fun addAll(value: Byte, order: BitOrder = BitOrder.MSB_FIRST) {
        ensureSpaceFor(8)
        for (i in 0 until 8) {
            val bit = when (order) {
                BitOrder.MSB_FIRST -> ((value.toInt() and (1 shl (7 - i))) != 0)
                BitOrder.LSB_FIRST -> ((value.toInt() and (1 shl i)) != 0)
            }
            add(bit)
        }
    }

    fun addAll(value: Long, order: BitOrder = BitOrder.MSB_FIRST) {
        ensureSpaceFor(64)
        for (i in 0 until 64) {
            val bit = when (order) {
                BitOrder.MSB_FIRST -> ((value and (1L shl (63 - i))) != 0L)
                BitOrder.LSB_FIRST -> ((value and (1L shl i)) != 0L)
            }
            add(bit)
        }
    }

    fun removeFirst(): Boolean {
        check(bitSize() > 0) { "Cannot remove from empty deque" }
        val bit = getBitAt(startBitIndex)
        startBitIndex = (startBitIndex + 1) % capacityInBits
        return bit
    }

    fun removeFirst(n: Int, order: BitOrder = BitOrder.MSB_FIRST): Long {
        require(n in 0..64) {
            "removeFirst(n): n must be within [0..64], got $n"
        }
        check(bitSize() >= n) { "Not enough bits to remove. size=$size, requested=$n" }

        var value = 0L
        when (order) {
            BitOrder.MSB_FIRST -> {
                repeat(n) {
                    value = (value shl 1) or if (removeFirst()) 1L else 0L
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

    fun copy(): MutableBitDeque {
        val result = MutableBitDeque()
        val sz = bitSize()
        result.ensureSpaceFor(sz)
        for (i in 0 until sz) {
            val bit = getBitAt((startBitIndex + i) % capacityInBits)
            result.setBitAt(i, bit)
        }
        result.startBitIndex = 0
        result.endBitIndex = sz
        return result
    }

    override fun toMutableBitDeque(): MutableBitDeque = copy()

    override fun hashCode(): Int {
        var h = 1
        val sz = bitSize()
        for (i in 0 until sz) {
            val bit = getBitAt((startBitIndex + i) % capacityInBits)
            h = 31 * h + (if (bit) 1 else 0)
        }
        return h
    }

    override fun equals(other: Any?): Boolean {
        if (other !is MutableBitDeque) return false
        if (this.size != other.size) return false
        val sz = this.size.toInt()
        for (i in 0 until sz) {
            if (this[i.toLong()] != other[i.toLong()]) return false
        }
        return true
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("MutableBitDeque(\"")
        val sz = bitSize()
        for (i in 0 until sz) {
            sb.append(if (getBitAt((startBitIndex + i) % capacityInBits)) '1' else '0')
        }
        sb.append("\")")
        return sb.toString()
    }

    // ---------------------- Internal Helpers ---------------------- //

    internal fun bitSize(): Int {
        return if (endBitIndex >= startBitIndex) {
            endBitIndex - startBitIndex
        } else {
            capacityInBits - (startBitIndex - endBitIndex)
        }
    }

    internal fun getBitAt(globalIndex: Int): Boolean {
        val arrIndex = globalIndex ushr 6  // /64
        val bitOffset = globalIndex and 63 // %64
        val mask = 1L shl bitOffset
        return (backing[arrIndex] and mask) != 0L
    }

    internal fun setBitAt(globalIndex: Int, value: Boolean) {
        val arrIndex = globalIndex ushr 6
        val bitOffset = globalIndex and 63
        val mask = 1L shl bitOffset
        if (value) {
            backing[arrIndex] = backing[arrIndex] or mask
        } else {
            backing[arrIndex] = backing[arrIndex] and mask.inv()
        }
    }

    internal fun ensureSpaceFor(n: Int) {
        val sz = bitSize()
        if (sz + n <= capacityInBits) return

        var newCapacityInLongs = backing.size
        while (sz + n > newCapacityInLongs * 64) {
            newCapacityInLongs *= 2
        }
        val newBacking = LongArray(newCapacityInLongs)
        // linearize existing bits into [0..sz)
        for (i in 0 until sz) {
            if (getBitAt((startBitIndex + i) % capacityInBits)) {
                val arrIndex = i ushr 6
                val bitOffset = i and 63
                newBacking[arrIndex] = newBacking[arrIndex] or (1L shl bitOffset)
            }
        }
        backing = newBacking
        capacityInBits = newBacking.size * 64
        startBitIndex = 0
        endBitIndex = sz
    }
}

/**
 * Custom KSerializer for MutableBitDeque that stores only the used bits.
 *
 * We:
 * 1) Read out the total number of bits (size) from [startBitIndex..endBitIndex).
 * 2) Write them into a ByteArray linearly.
 * 3) On decode, we reconstruct a new MutableBitDeque with that data.
 *
 * This is more efficient than storing each bit as a Boolean or trying to store ring-buffer pointers.
 */
object MutableBitDequeSerializer : KSerializer<MutableBitDeque> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("MutableBitDeque") {
        element<Int>("bitCount")
        element<ByteArray>("bits")
    }

    override fun serialize(encoder: Encoder, value: MutableBitDeque) {
        val sz = value.bitSize()
        val byteCount = (sz + 7) / 8
        val arr = ByteArray(byteCount)

        // Linearize from the ring buffer's start..end
        for (i in 0 until sz) {
            val bit = value.getBitAt((value.startBitIndex + i) % value.capacityInBits)
            if (bit) {
                // set i-th bit in arr
                val byteIndex = i ushr 3
                val bitOffset = i and 7
                arr[byteIndex] = (arr[byteIndex].toInt() or (1 shl bitOffset)).toByte()
            }
        }

        val composite = encoder.beginStructure(descriptor)
        composite.encodeIntElement(descriptor, 0, sz)
        composite.encodeSerializableElement(descriptor, 1, ByteArraySerializer(), arr)
        composite.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): MutableBitDeque {
        val comp = decoder.beginStructure(descriptor)
        var bitCount = 0
        var arr = ByteArray(0)
        loop@ while (true) {
            when (val idx = comp.decodeElementIndex(descriptor)) {
                0 -> bitCount = comp.decodeIntElement(descriptor, 0)
                1 -> arr = comp.decodeSerializableElement(descriptor, 1, ByteArraySerializer())
                CompositeDecoder.DECODE_DONE -> break@loop
                else -> error("Unexpected index: $idx")
            }
        }
        comp.endStructure(descriptor)

        val result = MutableBitDeque()
        result.ensureSpaceFor(bitCount)
        // Set each bit from arr
        for (i in 0 until bitCount) {
            val byteIndex = i ushr 3
            val bitOffset = i and 7
            val mask = 1 shl bitOffset
            val bit = ((arr[byteIndex].toInt()) and mask) != 0
            result.setBitAt(i, bit)
        }
        result.startBitIndex = 0
        result.endBitIndex = bitCount
        return result
    }
}
