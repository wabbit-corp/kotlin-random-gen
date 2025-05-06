package one.wabbit.random.gen

import one.wabbit.base58.Base58

data class TapeSeed(val seed: Long, val flips: BitSequence) {
    // Convert to a single Base58 string:
    fun toBase58String(): String {
        // 1) 8 bytes for seed
        // 2) 4 bytes for bitCount
        // 3) packed bits

        val bitCount = flips.size.toInt()
        require(bitCount >= 0) { "Bit count cannot be negative" }

        val packedFlips = packBits(flips)
        val data = ByteArray(8 + 4 + packedFlips.size)

        putLongBE(data, 0, seed)
        putIntBE(data, 8, bitCount)
        // copy packed flips
        for (i in packedFlips.indices) {
            data[12 + i] = packedFlips[i]
        }

        return Base58.encode(data)
    }

    companion object {
        fun fromBase58String(encoded: String): TapeSeed {
            val data = Base58.decode(encoded)
            require(data.size >= 12) { "Not enough bytes to decode TapeSeed" }

            // read seed
            val seedVal = getLongBE(data, 0)
            // read bitCount
            val bitCount = getIntBE(data, 8)
            require(bitCount >= 0) { "Negative bitCount in serialized TapeSeed" }

            // read flips
            val flipBytes = data.sliceArray(12 until data.size)
            val flips = unpackBits(flipBytes, bitCount)
            return TapeSeed(seedVal, flips)
        }
    }
}

/**
 * Store `value` as 8 big-endian bytes into `dest`, starting at `offset`.
 */
internal fun putLongBE(dest: ByteArray, offset: Int, value: Long) {
    dest[offset]     = (value ushr 56).toByte()
    dest[offset + 1] = (value ushr 48).toByte()
    dest[offset + 2] = (value ushr 40).toByte()
    dest[offset + 3] = (value ushr 32).toByte()
    dest[offset + 4] = (value ushr 24).toByte()
    dest[offset + 5] = (value ushr 16).toByte()
    dest[offset + 6] = (value ushr  8).toByte()
    dest[offset + 7] = (value        ).toByte()
}

/**
 * Read 8 big-endian bytes from `src` (starting at `offset`) into a `Long`.
 */
internal fun getLongBE(src: ByteArray, offset: Int): Long {
    return ((src[offset].toLong() and 0xFF) shl 56) or
            ((src[offset + 1].toLong() and 0xFF) shl 48) or
            ((src[offset + 2].toLong() and 0xFF) shl 40) or
            ((src[offset + 3].toLong() and 0xFF) shl 32) or
            ((src[offset + 4].toLong() and 0xFF) shl 24) or
            ((src[offset + 5].toLong() and 0xFF) shl 16) or
            ((src[offset + 6].toLong() and 0xFF) shl  8) or
            ((src[offset + 7].toLong() and 0xFF)      )
}

/**
 * Store `value` as 4 big-endian bytes into `dest`, starting at `offset`.
 */
internal fun putIntBE(dest: ByteArray, offset: Int, value: Int) {
    dest[offset]     = (value ushr 24).toByte()
    dest[offset + 1] = (value ushr 16).toByte()
    dest[offset + 2] = (value ushr  8).toByte()
    dest[offset + 3] = (value       ).toByte()
}

/**
 * Read 4 big-endian bytes from `src` (starting at `offset`) into an `Int`.
 */
internal fun getIntBE(src: ByteArray, offset: Int): Int {
    return ((src[offset].toInt() and 0xFF) shl 24) or
            ((src[offset + 1].toInt() and 0xFF) shl 16) or
            ((src[offset + 2].toInt() and 0xFF) shl  8) or
            ((src[offset + 3].toInt() and 0xFF))
}

/**
 * Pack the bits of `flips` into a ByteArray, 8 bits per byte (MSB-first).
 * If flips.size is not a multiple of 8, the last byte is padded with zero bits.
 */
internal fun packBits(flips: BitSequence): ByteArray {
    val bitCount = flips.size
    val byteCount = ((bitCount + 7) / 8).toInt()  // round up
    val output = ByteArray(byteCount)
    for (i in 0 until bitCount) {
        if (flips[i]) {
            val byteIndex = (i / 8).toInt()
            val bitIndexInByte = 7 - (i % 8).toInt() // store in MSB-first
            output[byteIndex] = (output[byteIndex].toInt() or (1 shl bitIndexInByte)).toByte()
        }
    }
    return output
}

/**
 * Unpack `totalBits` bits from `bytes` (packed MSB-first) into a new `MutableList<Boolean>`.
 * If `bytes` has extra padding bits, we ignore them beyond `totalBits`.
 */
internal fun unpackBits(bytes: ByteArray, totalBits: Int): MutableBitDeque {
    val out = MutableBitDeque()
    // out.ensureCapacity(totalBits)
    for (i in 0 until totalBits) {
        val byteIndex = i / 8
        val bitIndexInByte = 7 - (i % 8)
        val mask = 1 shl bitIndexInByte
        val bit = (bytes[byteIndex].toInt() and mask) != 0
        out.add(bit)
    }
    return out
}
