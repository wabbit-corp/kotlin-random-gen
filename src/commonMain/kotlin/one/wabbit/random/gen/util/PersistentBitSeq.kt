@file:OptIn(ExperimentalUnsignedTypes::class)

package one.wabbit.random.gen.util

import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Immutable, persistent bit sequence backed by ULongArray leaves. Internally a rope of chunks
 * (balanced binary tree).
 *
 * Key ops are O(log n) with very small constant factors for ≤64-bit edits.
 */
@Serializable(with = ImmBitSeqSerializer::class)
class PersistentBitSeq private constructor(internal val root: Node) : BitSequence {
    // ---- Public API ---------------------------------------------------------

    val length: Long
        get() = size

    override val size: Long
        get() = root.size

    override operator fun get(index: Long): Boolean {
        require(index in 0 until size) { "Index $index out of [0,$size)" }
        return root.getBit(index)
    }

    // BitSequence interop
    override fun toMutableBitDeque(): MutableBitDeque {
        val out = MutableBitDeque()
        if (size == 0L) return out
        // Stream bits in leaf order
        root.forEachLeaf { leaf ->
            val n = leaf.bitSize
            // Copy as densely as possible in 64-bit blocks
            var i = 0
            while (i < n) {
                val toTake = min(64, n - i)
                val chunk = leaf.readULong(i, toTake)
                out.ensureSpaceFor(toTake)
                // LSB-first write into deque
                repeat(toTake) { b -> out.add(((chunk shr b) and 1uL) != 0uL) }
                i += toTake
            }
        }
        return out
    }

    // --- Appends & Prepends --------------------------------------------------

    /** Append another immutable sequence (structural sharing). */
    fun append(other: PersistentBitSeq): PersistentBitSeq =
        if (other.size == 0L) {
            this
        } else if (size == 0L) {
            other
        } else {
            PersistentBitSeq(concat(root, other.root))
        }

    /** Prepend another immutable sequence. */
    fun prepend(other: PersistentBitSeq): PersistentBitSeq =
        if (other.size == 0L) {
            this
        } else if (size == 0L) {
            other
        } else {
            PersistentBitSeq(concat(other.root, root))
        }

    /** Append a single bit. */
    fun appendBit(bit: Boolean): PersistentBitSeq = append(fromULong(if (bit) 1uL else 0uL, 1))

    /** Prepend a single bit. */
    fun prependBit(bit: Boolean): PersistentBitSeq = prepend(fromULong(if (bit) 1uL else 0uL, 1))

    /** Append up to 64 bits from [value], respecting [order]. */
    fun append64(
        value: ULong,
        bitCount: Int,
        order: BitOrder = BitOrder.LSB_FIRST,
    ): PersistentBitSeq = append(fromULong(value, bitCount, order))

    /** Prepend up to 64 bits from [value], respecting [order]. */
    fun prepend64(
        value: ULong,
        bitCount: Int,
        order: BitOrder = BitOrder.LSB_FIRST,
    ): PersistentBitSeq = prepend(fromULong(value, bitCount, order))

    // --- Cuts / Slices -------------------------------------------------------

    /** Return a subsequence [start, end) without copying underlying storage. */
    fun slice(start: Long, endExclusive: Long): PersistentBitSeq {
        require(start in 0..size && endExclusive in 0..size && start <= endExclusive)
        if (start == 0L && endExclusive == size) return this
        if (start == endExclusive) return empty()
        val (a, tail) = split(root, start)
        val (mid, _) = split(tail, endExclusive - start)
        return PersistentBitSeq(mid)
    }

    /** Cut out [start, end) and join the rest (delete a subsequence). */
    fun cut(start: Long, endExclusive: Long): PersistentBitSeq =
        replace(start, endExclusive, empty())

    // --- Replace / Flip (≤64 bits window) ------------------------------------

    /**
     * Flip bits in window [offset, offset + windowBits), masked by [mask]. windowBits must be in
     * 1..64. Mask is applied LSB-first to that window.
     */
    fun flipMasked64(offset: Long, windowBits: Int, mask: ULong): PersistentBitSeq {
        require(windowBits in 1..64)
        require(offset >= 0 && offset + windowBits <= size) { "Out of bounds" }
        val (prefix, tail) = split(root, offset)
        val (mid, suffix) = split(tail, windowBits.toLong())

        val current = mid.readAsULong(windowBits) // LSB-first
        val masked = current xor (mask and lowMask(windowBits))
        val mid2 = Leaf(ulongArrayOf(masked), 0, 0, windowBits).normalize()

        return PersistentBitSeq(concat3(prefix, mid2, suffix))
    }

    /**
     * Overwrite up to 64 consecutive bits starting at [offset] with [value]’s low [bitCount] bits.
     * Bits come from [value] LSB-first unless [order] = MSB_FIRST.
     */
    fun write64(
        offset: Long,
        bitCount: Int,
        value: ULong,
        order: BitOrder = BitOrder.LSB_FIRST,
    ): PersistentBitSeq {
        require(bitCount in 0..64)
        require(offset >= 0 && offset + bitCount <= size) { "Out of bounds" }
        if (bitCount == 0) return this
        val (prefix, tail) = split(root, offset)
        val (mid, suffix) = split(tail, bitCount.toLong())

        val v =
            when (order) {
                BitOrder.LSB_FIRST -> value and lowMask(bitCount)
                BitOrder.MSB_FIRST -> shiftDownToLSB(value, bitCount)
            }
        val mid2 = Leaf(ulongArrayOf(v), 0, 0, bitCount).normalize()
        return PersistentBitSeq(concat3(prefix, mid2, suffix))
    }

    /** Replace [start, end) with [replacement]. */
    fun replace(start: Long, endExclusive: Long, replacement: PersistentBitSeq): PersistentBitSeq {
        require(start in 0..size && endExclusive in 0..size && start <= endExclusive)
        val (prefix, tail) = split(root, start)
        val (_, suffix) = split(tail, endExclusive - start)
        return PersistentBitSeq(concat3(prefix, replacement.root, suffix))
    }

    // --- Bulk replace using another sequence (streaming-friendly) ------------

    /** Insert [other] at [offset] (i.e., replace [offset,offset) with other). */
    fun insert(offset: Long, other: PersistentBitSeq): PersistentBitSeq =
        replace(offset, offset, other)

    // --- Bit packing helpers --------------------------------------------------

    /** Read up to 64 bits starting at [offset] (LSB-first). */
    fun read64(offset: Long, bitCount: Int): ULong {
        require(bitCount in 0..64)
        require(offset >= 0 && offset + bitCount <= size)
        if (bitCount == 0) return 0uL
        val (prefix, tail) = split(root, offset)
        val (mid, _) = split(tail, bitCount.toLong())
        return mid.readAsULong(bitCount)
    }

    // ---- Implementation details below ---------------------------------------

    // Tree node types ----------------------------------------------------------

    internal sealed interface Node {
        val size: Long
        val height: Int

        fun getBit(index: Long): Boolean

        fun forEachLeaf(visit: (Leaf) -> Unit)

        fun readAsULong(bitCount: Int): ULong // only valid when size <= 64
    }

    /** Empty node (singleton). */
    internal data object Empty : Node {
        override val size: Long
            get() = 0

        override val height: Int
            get() = 0

        override fun getBit(index: Long): Boolean = error("Empty")

        override fun forEachLeaf(visit: (Leaf) -> Unit) {}

        override fun readAsULong(bitCount: Int): ULong = 0uL
    }

    /**
     * Leaf node referencing a slice of [data]. The slice starts at (startWord, startBit) and spans
     * [bitSize] bits.
     */
    internal data class Leaf(
        val data: ULongArray,
        val startWord: Int,
        val startBit: Int, // 0..63
        val bitSize: Int,
    ) : Node {
        init {
            require(startBit in 0..63)
            require(bitSize >= 0)
            val totalBits = data.size * 64
            require(startWord >= 0 && startWord * 64 + startBit + bitSize <= totalBits) {
                "Leaf slice out of bounds"
            }
        }

        override val size: Long
            get() = bitSize.toLong()

        override val height: Int
            get() = 1

        override fun getBit(index: Long): Boolean {
            val i = index.toInt()
            require(i in 0 until bitSize)
            val absBit = startBit + i
            val w = startWord + (absBit ushr 6)
            val b = absBit and 63
            val word = data[w]
            return ((word shr b) and 1uL) != 0uL
        }

        override fun forEachLeaf(visit: (Leaf) -> Unit) = visit(this)

        override fun readAsULong(bitCount: Int): ULong {
            require(bitCount in 0..64)
            require(bitCount.toLong() <= size)
            if (bitCount == 0) return 0uL
            var out = 0uL
            // Read LSB-first
            var i = 0
            var localBit = startBit
            var wIdx = startWord
            var remaining = bitCount
            var bitsInWord = 64 - localBit

            var word = if (data.isNotEmpty()) data[wIdx] else 0uL
            while (remaining > 0) {
                val take = min(remaining, bitsInWord)
                // take 'take' bits from (word >> localBit), place into out at position i
                val chunk = (word shr localBit) and lowMask(take)
                out = out or (chunk shl i)
                remaining -= take
                i += take
                if (remaining == 0) break
                // advance to next word
                wIdx++
                word = data[wIdx]
                localBit = 0
                bitsInWord = 64
            }
            return out
        }

        /** Tighten a leaf: if [bitSize]==0 -> Empty; if aligned and compactable -> compact. */
        fun normalize(): Node {
            if (bitSize == 0) return Empty
            // If slice is word-aligned and does not waste leading/trailing words, keep as is.
            // Otherwise, for small leaves, pack densely to reduce later work.
            if ((startBit == 0) && (bitSize % 64 == 0)) return this

            // For small leaves, pack into a tiny array
            if (bitSize <= PersistentBitSeq.MAX_BITS_PER_LEAF_TO_PACK) {
                val wordsNeeded = ((bitSize + 63) ushr 6)
                val arr = ULongArray(wordsNeeded)
                // copy bits densely into arr
                var written = 0
                while (written < bitSize) {
                    val take = min(64, bitSize - written)
                    val v = readULong(written, take)
                    arr[written ushr 6] = arr[written ushr 6] or (v shl (written and 63))
                    written += take
                }
                return Leaf(arr, 0, 0, bitSize)
            }
            return this
        }

        /** Read [count] bits within this leaf, starting at local bit offset [off], LSB-first. */
        fun readULong(off: Int, count: Int): ULong {
            require(off >= 0 && count >= 0 && off + count <= bitSize)
            if (count == 0) return 0uL
            val startAbs = startBit + off
            val w0 = startWord + (startAbs ushr 6)
            val b0 = startAbs and 63
            var out = 0uL
            var outPos = 0
            var remaining = count

            var word = data[w0]
            var localBit = b0
            var bitsInWord = 64 - localBit

            while (remaining > 0) {
                val take = min(remaining, bitsInWord)
                val chunk = (word shr localBit) and lowMask(take)
                out = out or (chunk shl outPos)
                remaining -= take
                outPos += take
                if (remaining == 0) break
                // next word
                val nextWord =
                    data[w0 + ((startAbs + (count - remaining)) ushr 6) - (startAbs ushr 6)]
                word = nextWord
                localBit = 0
                bitsInWord = 64
            }
            return out
        }
    }

    /** Internal concat node with cached size & height. */
    internal data class Concat(val left: Node, val right: Node) : Node {
        override val size: Long = left.size + right.size
        override val height: Int = 1 + max(left.height, right.height)

        override fun getBit(index: Long): Boolean =
            if (index < left.size) left.getBit(index) else right.getBit(index - left.size)

        override fun forEachLeaf(visit: (Leaf) -> Unit) {
            left.forEachLeaf(visit)
            right.forEachLeaf(visit)
        }

        override fun readAsULong(bitCount: Int): ULong {
            require(bitCount <= 64 && bitCount.toLong() <= size)
            // stitch at most two parts
            val takeL = min(left.size, bitCount.toLong()).toInt()
            val lpart = if (takeL > 0) left.readAsULong(takeL) else 0uL
            val rpart = if (takeL < bitCount) right.readAsULong(bitCount - takeL) else 0uL
            return lpart or (rpart shl takeL)
        }
    }

    // --- Rope combinators ----------------------------------------------------

    private fun concat(a: Node, b: Node): Node {
        if (a === Empty) return b
        if (b === Empty) return a

        // Merge small neighbors into one leaf to keep depth down.
        if (a is Leaf && b is Leaf) {
            val total = a.bitSize + b.bitSize
            if (total <= MERGE_BITS_THRESHOLD) {
                val arr = ULongArray((total + 63) ushr 6)
                // copy a
                var wrote = 0
                var i = 0
                while (i < a.bitSize) {
                    val take = min(64, a.bitSize - i)
                    val v = a.readULong(i, take)
                    val wi = wrote ushr 6
                    val bo = wrote and 63
                    arr[wi] = arr[wi] or (v shl bo)
                    wrote += take
                    i += take
                }
                // copy b
                i = 0
                while (i < b.bitSize) {
                    val take = min(64, b.bitSize - i)
                    val v = b.readULong(i, take)
                    val wi = wrote ushr 6
                    val bo = wrote and 63
                    arr[wi] = arr[wi] or (v shl bo)
                    wrote += take
                    i += take
                }
                return Leaf(arr, 0, 0, total).normalize()
            }
        }

        // Balance like an AVL/Rope: if height skew is large, rotate.
        val hl = a.height
        val hr = b.height
        if (hl > hr + 1) {
            // left heavy
            if (a is Concat) {
                val aL = a.left
                val aR = a.right
                if (aL.height >= aR.height) {
                    // single rotate: (aL + (aR + b))
                    return Concat(aL, concat(aR, b))
                } else {
                    // double rotate: ((aL.left + aL.right) + (aR + b))…
                    val aRL = (aR as Concat).left
                    val aRR = aR.right
                    return Concat(Concat(aL, aRL), concat(aRR, b))
                }
            }
        } else if (hr > hl + 1) {
            // right heavy
            if (b is Concat) {
                val bL = b.left
                val bR = b.right
                if (bR.height >= bL.height) {
                    // single rotate: ((a + bL) + bR)
                    return Concat(concat(a, bL), bR)
                } else {
                    val bLL = (bL as Concat).left
                    val bLR = bL.right
                    return Concat(concat(a, bLL), Concat(bLR, bR))
                }
            }
        }
        return Concat(a, b)
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun concat3(a: Node, b: Node, c: Node): Node = concat(concat(a, b), c)

    private fun split(n: Node, at: Long): Pair<Node, Node> {
        require(at in 0..n.size)
        if (at == 0L) return Empty to n
        if (at == n.size) return n to Empty
        return when (n) {
            is Empty -> Empty to Empty
            is Leaf -> {
                val leftBits = at.toInt()
                val rightBits = n.bitSize - leftBits
                // left leaf
                val leftLeaf = Leaf(n.data, n.startWord, n.startBit, leftBits).normalize()
                // compute new start for right leaf by advancing leftBits
                val absStart = n.startBit + leftBits
                val rStartWord = n.startWord + (absStart ushr 6)
                val rStartBit = absStart and 63
                val rightLeaf = Leaf(n.data, rStartWord, rStartBit, rightBits).normalize()
                leftLeaf to rightLeaf
            }
            is Concat -> {
                val ls = n.left.size
                if (at < ls) {
                    val (l1, l2) = split(n.left, at)
                    l1 to concat(l2, n.right)
                } else {
                    val (r1, r2) = split(n.right, at - ls)
                    concat(n.left, r1) to r2
                }
            }
        }
    }

    companion object {
        internal const val MERGE_BITS_THRESHOLD = 4096 // merge small neighbor leaves to limit depth
        internal const val MAX_BITS_PER_LEAF_TO_PACK = 1024 // pack small unaligned leaves

        fun empty(): PersistentBitSeq = PersistentBitSeq(Empty)

        /**
         * Construct from a raw ULongArray where all bits up to bitCount are used (LSB-first
         * packing).
         */
        fun fromULongs(words: ULongArray, bitCount: Long): PersistentBitSeq {
            require(bitCount >= 0)
            if (bitCount == 0L) return empty()
            val leaf = Leaf(words, startWord = 0, startBit = 0, bitSize = bitCount.toInt())
            return PersistentBitSeq(leaf.normalize())
        }

        /** Build from a ByteArray; LSB-first inside each byte unless otherwise requested. */
        fun fromBytes(bytes: ByteArray, bitOrder: BitOrder = BitOrder.LSB_FIRST): PersistentBitSeq {
            if (bytes.isEmpty()) return empty()
            val words = packToULongs(bytes, bitOrder)
            val bitCount = bytes.size.toLong() * 8
            return fromULongs(words, bitCount)
        }

        /** Build from a single ULong of lower [bitCount] bits (<=64). */
        fun fromULong(
            value: ULong,
            bitCount: Int,
            order: BitOrder = BitOrder.LSB_FIRST,
        ): PersistentBitSeq {
            require(bitCount in 0..64)
            if (bitCount == 0) return empty()
            val w =
                when (order) {
                    BitOrder.LSB_FIRST -> value
                    BitOrder.MSB_FIRST -> shiftDownToLSB(value, bitCount)
                }
            val arr = ulongArrayOf(w)
            return PersistentBitSeq(Leaf(arr, 0, 0, bitCount).normalize())
        }
    }
}

// ---- Serializer -------------------------------------------------------------

object ImmBitSeqSerializer : KSerializer<PersistentBitSeq> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("ImmBitSeq") {
            element<Long>("bitCount")
            element<ByteArray>("bits") // densely packed, LSB-first per byte
        }

    override fun serialize(encoder: Encoder, value: PersistentBitSeq) {
        val sz = value.size
        val byteCount = ((sz + 7) / 8).toInt()
        val arr = ByteArray(byteCount)
        // Walk leaves, dump bits LSB-first
        var wBit = 0L
        while (wBit < sz) {
            val chunk = minOf(64L, sz - wBit).toInt()
            val v = value.read64(wBit, chunk)
            // place at wBit offset
            var i = 0
            while (i < chunk) {
                if (((v shr i) and 1uL) != 0uL) {
                    val bitIndex = (wBit + i).toInt()
                    val byteIndex = bitIndex ushr 3
                    val bitOffset = bitIndex and 7
                    arr[byteIndex] = (arr[byteIndex].toInt() or (1 shl bitOffset)).toByte()
                }
                i++
            }
            wBit += chunk
        }

        val comp = encoder.beginStructure(descriptor)
        comp.encodeLongElement(descriptor, 0, sz)
        comp.encodeSerializableElement(descriptor, 1, ByteArraySerializer(), arr)
        comp.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): PersistentBitSeq {
        val comp = decoder.beginStructure(descriptor)
        var bitCount = 0L
        var arr = ByteArray(0)
        loop@ while (true) {
            when (val idx = comp.decodeElementIndex(descriptor)) {
                0 -> bitCount = comp.decodeLongElement(descriptor, 0)
                1 -> arr = comp.decodeSerializableElement(descriptor, 1, ByteArraySerializer())
                CompositeDecoder.DECODE_DONE -> break@loop
                else -> error("Unexpected index: $idx")
            }
        }
        comp.endStructure(descriptor)

        if (bitCount == 0L) return PersistentBitSeq.empty()
        val words = packToULongs(arr, BitOrder.LSB_FIRST)
        return PersistentBitSeq.fromULongs(words, bitCount)
    }
}

// ---- Support: packing, masks, shifts ---------------------------------------

private inline fun lowMask(n: Int): ULong =
    when {
        n <= 0 -> 0uL
        n >= 64 -> ULong.MAX_VALUE
        else -> (1uL shl n) - 1uL
    }

/** Convert MSB-first within a [bitCount]-wide value down to LSB-first storage. */
private fun shiftDownToLSB(v: ULong, bitCount: Int): ULong {
    // Take highest bitCount bits (as MSB-first) and map them to [0, bitCount)
    // Example: for bitCount=8, MSB 0x80 -> LSB 0x01
    if (bitCount == 0) return 0uL
    if (bitCount >= 64) return v // already 64 bits; treat as-is
    var out = 0uL
    // MSB-first means bit (bitCount-1-i) maps to i
    for (i in 0 until bitCount) {
        val srcBit = (v shr (64 - bitCount + i)) and 1uL
        out = out or (srcBit shl (bitCount - 1 - i))
    }
    return out and lowMask(bitCount)
}

/** Pack bytes into ULongs; within each byte, choose [order]. */
private fun packToULongs(bytes: ByteArray, order: BitOrder): ULongArray {
    if (bytes.isEmpty()) return ULongArray(0)
    val words = ULongArray((bytes.size + 7) / 8)
    var bi = 0
    var wi = 0
    while (bi < bytes.size) {
        var w = 0uL
        var b = 0
        while (b < 8 && bi < bytes.size) {
            val by = bytes[bi].toInt() and 0xFF
            val v =
                when (order) {
                    BitOrder.LSB_FIRST -> by.toULong() // byte already LSB-first inside byte
                    BitOrder.MSB_FIRST -> reverseBits8(by).toULong()
                }
            w = w or (v shl (8 * b))
            b++
            bi++
        }
        words[wi++] = w
    }
    return words
}

private fun reverseBits8(x: Int): Int {
    // bit reversal 8-bit
    var v = x and 0xFF
    v = ((v and 0xF0) ushr 4) or ((v and 0x0F) shl 4)
    v = ((v and 0xCC) ushr 2) or ((v and 0x33) shl 2)
    v = ((v and 0xAA) ushr 1) or ((v and 0x55) shl 1)
    return v and 0xFF
}
