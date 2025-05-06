```kotlin
sealed interface Gen<R> {//
    fun next(random: SplittableRandom): R {
        when (this) {
            is Pure -> return value
            is FromInt -> {
                if (from == to) return f(from)
                return f(random.nextInt(from, to))
            }
            is OneOf -> {
                assert(options.isNotEmpty())
                assert(options.all { it.first >= 0.0 })

                val total = options.sumOf { it.first }
                val r = random.nextDouble() * total
                var sum = 0.0
                for ((weight, gen) in options) {
                    sum += weight
                    if (r < sum)
                        return gen.next(random)
                }

                return options.last().second.next(random)
            }
            is Sequence<*> -> {
                return list.map { it.next(random) } as R
            }
            is Map<*, *> -> {
                return (f as (Any?) -> R)(gen.next(random))
            }
            is FlatMap<*, *> -> {
                return (f as (Any?) -> Gen<R>)(gen.next(random)).next(random)
            }
        }
    }

    fun foreach(random: SplittableRandom, count: Int, f: (R) -> Unit): Unit {
        repeat(count) {
            f(next(random))
        }
    }

    companion object {
        fun <R> pure(value: R): Gen<R> =
            Pure(value)
        fun <R> oneOf(options: List<R>): Gen<R> {
            return Gen.OneOf(options.map { Pair(1.0, Gen.Pure(it)) })
        }
        fun <R> freq(options: List<Pair<Double, R>>): Gen<R> {
            return Gen.OneOf(options.map { Pair(it.first, Gen.Pure(it.second)) })
        }
        fun <R> freq1(options: List<Pair<Double, Gen<R>>>): Gen<R> {
            return Gen.OneOf(options)
        }
        fun <R> sequence(list: List<Gen<R>>): Gen<List<R>> =
            Sequence(list)

        fun <R> repeat(count: Int, gen: Gen<R>): Gen<List<R>> =
            Sequence(List(count) { gen })

        fun int(from: Int, until: Int): Gen<Int> =
            FromInt(from, until) { it }

        fun <R> filter(gen: Gen<R>, f: (R) -> Boolean): Gen<R> {
            return gen.flatMap { r ->
                if (f(r))
                    pure(r)
                else
                    filter(gen, f)
            }
        }

        val anyChar = int(Char.MAX_VALUE.code, Char.MAX_VALUE.code)
        val anyDefinedChar = filter(anyChar) { it.toChar().isDefined() }
    }
}
```

```kotlin
//    data class FromInt<R>(val from: Int, val to: Int, val f: (Int) -> R) : Gen<R> {
//        init {
//            require(from <= to)
//        }
//    }
//
//    data class FromLong<R>(val from: Long, val to: Long, val f: (Long) -> R) : Gen<R> {
//        init {
//            require(from <= to)
//        }
//    }
//
//    data class FromUniform<R>(val f: (Double) -> R) : Gen<R>
//
//    data class OneOf<R>(val options: List<Pair<Double, Gen<R>>>) : Gen<R> {
//        init {
//            assert(options.isNotEmpty())
//            assert(options.all { it.first >= 0.0 })
//        }
//    }
// data class Filter<A>(val value: Gen<A>, val predicate: (A) -> Boolean) : Gen<A>
```

```kotlin
package one.wabbit.random.gen
data class BitVector(val list: List<Boolean>) {
    val zeros: Int = list.count { !it}
    val ones: Int = list.count { it }

    val length: Int = list.size

    fun unconsN(n: Int): Pair<BitVector, BitVector> {
        val h = list.take(n)
        val t = list.drop(n)
        return BitVector(h) to BitVector(t)
    }

    fun pack(): Long = list.foldLeft(0L) { (s, b) -> if (b) s << 1 | 1L else s << 1 }

    fun zipWith(that: BitVector, f: (Boolean, Boolean) -> Boolean): BitVector =
        BitVector((this.list zip that.list).map { case (a, b) => f(a, b) })

    fun zipLongest(that: BitVector, f: (Boolean, Boolean) -> Boolean): BitVector =
        BitVector(this.list.zipAll(that.list, false, false).map { case (a, b) => f(a, b) })

    operator fun plus(that: BitVector): BitVector =
        BitVector(this.list + that.list)

    fun flip(n: Int): BitVector =
        BitVector {
            val r0 = pad(n + 1).list
            r0.updated(n, !r0(n))
        }

    fun pad(length: Int): BitVector =
        BitVector(
            if (list.size >= length) list
            else list ++ Vector.fill(length - list.size)(false))

    override fun toString(): String =
        "BitVector(${list.map(x => if (x) '1' else '0').mkString})"

    companion object {
        val empty: BitVector = BitVector(Vector.empty)
        fun fromLong(l: Long): BitVector =
            BitVector((0 until 64).map { i =>
                ((l >> (63 - i)) & 0x01) == 1
            }.toVector)
    }
}
```
