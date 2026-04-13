// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.random.gen.util

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

object Codecs {
    fun interface ReadBits {
        /** Read [n] bits (0..64), MSB-first, return them in the low bits of ULong. */
        fun read(n: Int): ULong?
    }

    fun interface WriteBits {
        /** Write [n] bits (0..64), MSB-first, from the low bits of [value]. */
        fun write(value: ULong, n: Int)
    }

    fun readBool(bits: ReadBits): Boolean? {
        val bit = bits.read(1) ?: return null
        return bit == 1uL
    }

    fun writeBool(value: Boolean, bits: WriteBits) {
        bits.write(if (value) 1uL else 0uL, 1)
    }

    // --------------------------
    // Unsigned int (Truncated Binary Encoding)
    // --------------------------

    fun readUintTBE(range: UIntRange, bits: ReadBits): UInt? {
        val first = range.first
        val last = range.last
        require(first <= last)
        if (first == last) return first

        val m = (last - first + 1u)
        // floor(log2(m))
        val k = 31 - m.countLeadingZeroBits()
        val twoK = 1u shl k
        // t = 2^(k+1) - m
        val t = (twoK shl 1) - m

        // always < 2^k
        val u = bits.read(k)?.toUInt() ?: return null
        return if (u < t) {
            first + u // k-bit code
        } else {
            val extra = bits.read(1)?.toUInt() ?: return null
            first + ((u shl 1) + extra - t) // (k+1)-bit code
        }
    }

    /** Inverse of readUintTBE: emit the canonical TBE code for [value] in [range]. */
    fun writeUintTBE(value: UInt, range: UIntRange, bits: WriteBits) {
        val first = range.first
        val last = range.last
        require(first <= last)
        require(value in range)
        if (first == last) return

        val m = last - first + 1u
        val k = 31 - m.countLeadingZeroBits() // floor(log2(m))
        val twoK = 1u shl k
        val t = (twoK shl 1) - m // 2^(k+1) - m

        val d = value - first // 0 .. m-1

        if (d < t) {
            // k-bit code
            bits.write(d.toULong(), k)
        } else {
            // (k+1)-bit code: write floor((d + t)/2) on k bits, then LSB as 1 bit
            val y = d + t
            val u = y shr 1
            val extra = y and 1u
            bits.write(u.toULong(), k)
            bits.write(extra.toULong(), 1)
        }
    }

    fun readUint(range: UIntRange, bits: ReadBits): UInt? =
        readULong(range.first.toULong()..range.last.toULong(), bits)?.toUInt()

    fun writeUint(value: UInt, range: UIntRange, bits: WriteBits) =
        writeULong(value.toULong(), range.first.toULong()..range.last.toULong(), bits)

    private fun ceilLog2ULong(x: ULong): Int {
        require(x > 0UL)
        // ceil(log2(x)) = bitWidth(x-1)
        return 64 - (x - 1UL).countLeadingZeroBits()
    }

    fun readULong(range: ULongRange, bits: ReadBits): ULong? {
        val first = range.first
        val last = range.last
        require(first <= last)

        if (first == last) return first

        if (first == 0UL && last == ULong.MAX_VALUE) {
            // full width fast-path
            return bits.read(64)
        }

        val m: ULong = last - first + 1UL

        // Power of two domain size
        if ((m and (m - 1UL)) == 0UL) {
            val width = 63 - m.countLeadingZeroBits() // == log2(m)
            val v = bits.read(width) ?: return null
            return first + v
        }

        // -------- Non-power-of-two (FDR), with safe L==64 handling --------

        val L = ceilLog2ULong(m) // in 1..64
        val r = bits.read(L) ?: return null

        if (r < m) {
            // Accepted immediately (this is what writeULong emits)
            return first + r
        }

        // Leftover region after the first L bits:
        var n: ULong = if (L == 64) 0UL - m else (1UL shl L) - m // b = 2^L - m
        var x: ULong = r - m // 0 <= x < n

        // Invariant: 0 < n < m, 0 <= x < n
        while (true) {
            // Smallest k0 with (n << k0) >= m, computed without overflow
            val q: ULong = (m - 1UL) / n + 1UL // == ceil(m / n)
            val k0: Int = ceilLog2ULong(q) // in 1..63 (since q <= 2^63)
            val p = bits.read(k0) ?: return null

            n = n shl k0
            x = (x shl k0) + p

            val a = n / m
            val am = a * m
            if (x < am) {
                return first + (x % m)
            }

            x -= am
            n %= m
            // loop continues with 0 < n < m maintained
        }
    }

    /**
     * Inverse of readUint. For non power-of-two [m], the canonical code the reader accepts
     * immediately is the fixed-length binary of (value - first) using L = ceil(log2(m)) bits.
     */
    fun writeULong(value: ULong, range: ULongRange, bits: WriteBits) {
        val first = range.first
        val last = range.last
        require(first <= last)
        require(value in range)

        if (first == last) return

        if (first == 0UL && last == ULong.MAX_VALUE) {
            bits.write(value, 64)
            return
        }

        val m = last - first + 1U
        val d = value - first

        if (m.countOneBits() == 1) {
            // exact power-of-two width
            val width = 63 - m.countLeadingZeroBits() // log2(m)
            bits.write(d, width)
        } else {
            // canonical fixed-length code accepted by the decoder's first step:
            val width = 64 - (m - 1U).countLeadingZeroBits() // ceil(log2(m))
            bits.write(d, width)
        }
    }

    fun readInt(range: IntRange, read: ReadBits): Int? {
        val first = range.first
        val last = range.last
        require(first <= last)
        if (first == last) return first

        // full int range: 32 random bits (unchanged)
        if (first == Int.MIN_VALUE && last == Int.MAX_VALUE) {
            return read.read(32)?.toUInt()?.toInt()
        }
        val m = (last - first + 1)
        val u = readUint(0u..(m - 1).toUInt(), read)?.toInt() ?: return null
        return first + u
    }

    /** Inverse of readInt. */
    fun writeInt(value: Int, range: IntRange, bits: WriteBits) {
        val first = range.first
        val last = range.last
        require(first <= last)
        require(value in range)
        if (first == last) return

        if (first == Int.MIN_VALUE && last == Int.MAX_VALUE) {
            // output raw 32-bit pattern of Int, as readInt expects
            bits.write(value.toUInt().toULong(), 32)
            return
        }

        val m = (last.toLong() - first.toLong() + 1L).toUInt() // avoid Int overflow
        val d = (value - first).toUInt()
        writeUint(d, 0u..(m - 1u), bits)
    }

    fun readDoubleU01(bits: Int, read: ReadBits): Double? {
        val bits = min(max(bits, 1), 53) // clamp to [1..53]
        val v = read.read(bits)
        return v?.let { it.toDouble() / (1L shl bits).toDouble() }
    }

    /** Inverse of readDoubleU01. Rounds toward zero; clamps into [0, 1). */
    fun writeDoubleU01(value: Double, bits: Int, write: WriteBits) {
        val bitsClamped = min(max(bits, 1), 53)
        val scale = 1L shl bitsClamped
        val raw = floor(value * scale.toDouble()).toLong()
        val clamped = raw.coerceIn(0L, scale - 1)
        write.write(clamped.toULong(), bitsClamped)
    }

    fun readBytes(length: Int, read: ReadBits): ByteArray? {
        require(length >= 0)
        return ByteArray(length) { read.read(8)?.toLong()?.toByte() ?: return null }
    }

    fun writeBytes(value: ByteArray, write: WriteBits) {
        for (b in value) {
            write.write((b.toInt() and 0xFF).toULong(), 8)
        }
    }
}
