@file:OptIn(ExperimentalUnsignedTypes::class)

package one.wabbit.random.gen.util

object Hashing {
    /**
     * Best-effort "stable" 32-bit hash for common JVM types. This defines observational equality
     * and possible functions that can be constructed.
     */
    fun stableHash32(x: Any?): Int {
        if (x == null) return 0
        return when (x) {
            is Float -> x.toRawBits()
            is Double -> x.toRawBits().toInt() xor (x.toRawBits() ushr 32).toInt()
            is ByteArray -> x.contentHashCode()
            is ShortArray -> x.contentHashCode()
            is IntArray -> x.contentHashCode()
            is LongArray -> x.contentHashCode()
            is BooleanArray -> x.contentHashCode()
            is CharArray -> x.contentHashCode()
            is FloatArray -> x.contentHashCode()
            is DoubleArray -> x.contentHashCode()
            is Array<*> -> x.contentDeepHashCode()
            else -> x.hashCode() // for data classes, String, List/Map/Set, primitives, etc.
        }
    }

    fun jumpConsistentHash(key: ULong, buckets: Int): Int {
        require(buckets > 0) { "buckets must be > 0" }
        var b = -1L
        var j = 0L
        var k = key
        val two31 = 1L shl 31
        while (j < buckets.toLong()) {
            b = j
            k = k * 2862933555777941757UL + 1UL
            val denom = ((k shr 33) + 1UL).toLong() // in [1, 2^31]
            j = ((b + 1) * two31) / denom // exact integer floor
        }
        return b.toInt()
    }

    /** Mixers (SplitMix64 finalizer). Keep these constants *stable* across versions. */
    fun mix64(z0: ULong): ULong {
        var z: ULong = z0 + 0x9E3779B97F4A7C15UL
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9UL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBUL
        z = z xor (z shr 31)
        return z
    }

    fun rotateLeft(value: ULong, bits: Int): ULong {
        val n = bits and 63
        return (value shl n) or (value shr (64 - n))
    }

    fun mixCombine(h1: ULong, h2: ULong): ULong = mix64(h1 xor h2.rotateLeft(1))

    fun mixCombine(h1: ULong, h2: ULong, h3: ULong): ULong = mixCombine(mixCombine(h1, h2), h3)

    fun mixCombine(vararg hs: ULong): ULong = hs.fold(0UL) { acc, h -> mixCombine(acc, h) }
}
