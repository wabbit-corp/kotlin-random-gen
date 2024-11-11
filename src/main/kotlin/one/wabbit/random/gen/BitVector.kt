package one.wabbit.random.gen
//
//data class BitVector(val list: List<Boolean>) {
//    val zeros: Int = list.count { !it}
//    val ones: Int = list.count { it }
//
//    val length: Int = list.size
//
//    fun unconsN(n: Int): Pair<BitVector, BitVector> {
//        val h = list.take(n)
//        val t = list.drop(n)
//        return BitVector(h) to BitVector(t)
//    }
//
//    fun pack(): Long = list.foldLeft(0L) { (s, b) -> if (b) s << 1 | 1L else s << 1 }
//
//    fun zipWith(that: BitVector, f: (Boolean, Boolean) -> Boolean): BitVector =
//        BitVector((this.list zip that.list).map { case (a, b) => f(a, b) })
//
//    fun zipLongest(that: BitVector, f: (Boolean, Boolean) -> Boolean): BitVector =
//        BitVector(this.list.zipAll(that.list, false, false).map { case (a, b) => f(a, b) })
//
//    operator fun plus(that: BitVector): BitVector =
//        BitVector(this.list + that.list)
//
//    fun flip(n: Int): BitVector =
//        BitVector {
//            val r0 = pad(n + 1).list
//            r0.updated(n, !r0(n))
//        }
//
//    fun pad(length: Int): BitVector =
//        BitVector(
//            if (list.size >= length) list
//            else list ++ Vector.fill(length - list.size)(false))
//
//    override fun toString(): String =
//        "BitVector(${list.map(x => if (x) '1' else '0').mkString})"
//
//    companion object {
//        val empty: BitVector = BitVector(Vector.empty)
//        fun fromLong(l: Long): BitVector =
//            BitVector((0 until 64).map { i =>
//                ((l >> (63 - i)) & 0x01) == 1
//            }.toVector)
//    }
//}
