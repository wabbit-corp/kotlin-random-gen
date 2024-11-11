package one.wabbit.random.gen

import java.util.*
import kotlin.math.sqrt
import kotlin.test.*

class GenSpec {
    val genString by lazy {
        Gen.int(0..20).flatMap { Gen.repeat(it, Gen.int('a'.code..'z'.code).map { it.toChar() }) }
            .map { it.joinToString("") }
    }

    @Test
    fun testIntGenDistribution() {
        val N = 10000000
        for (M in listOf(3, 4, 5, 7, 11, 13, 16, 17)) {
            val random = SplittableRandom()
            val freq = IntArray(M) { 0 }
            val list = Gen.int(0..<M).foreach(random, N) { freq[it] += 1 }
            for (i in 0 until M) {
                val it = freq[i]
                val p = it.toDouble() / N
                val q = 1.0 / freq.size.toDouble()
                val npq = N * q * (1 - q)
                val s2 = sqrt(npq) / N
                val z = (it - N * q) / sqrt(N * q * (1 - q))
                println("p=$p+-${s2} (z=$z)")
            }
            println()
        }
    }

    @Test
    fun testIntGenLarge() {
        Gen.int(0..1)
        println("------------")
        val gen = Gen.int(Int.MIN_VALUE..Int.MAX_VALUE)
        val random = SplittableRandom()
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        gen.foreach(random, 10000) {
            min = min.coerceAtMost(it)
            max = max.coerceAtLeast(it)
        }
        println("min=$min (${Int.MIN_VALUE}) max=$max (${Int.MAX_VALUE})")
    }

    @Test
    fun testMinimize() {
        val gen = Gen.int(0..<1000)

        val t0 = gen.satisfy(10000, 0x4444) { it % 17 == 0 }
        if (t0 == null) {
            println("No solution found")
            return
        }

        println(t0)

        val r1 = gen.minimize(t0, 1000, 0x243532) { it % 17 == 0 }

        println(r1)
    }

    @Test
    fun test() {
        val random = SplittableRandom()
        println("Start")
        genString.foreach(random, 100) {
            println(it)
        }
    }
}
