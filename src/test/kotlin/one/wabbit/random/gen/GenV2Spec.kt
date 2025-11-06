// package one.wabbit.random.gen
//
// import java.util.SplittableRandom
// import kotlin.test.Test
// import kotlin.test.assertEquals
// import kotlin.test.assertNotNull
// import kotlin.test.assertTrue
//
// class GenV2Spec {
//    @Test
//    fun phase0R_demo() {
//        // Record
//        val rec = ChoiceIO.Recorder(Entropy(EntropySource.Random(0xDEADBEEF)))
//        beginBlock(rec, BlockKind.TUPLE,"pair")
//        val a = chooseInt(rec, 0..100, "a")
//        val b = chooseUInt(rec, 0u..255u, "b")
//        endBlock(rec, BlockKind.TUPLE, "pair")
//        val d = chooseDouble(rec, label = "u01")
//        val w = chooseBits(rec, 13, "raw13")
//        val bs = chooseBytes(rec, 4, "bytes4")
//
//        val v2 = TapeSeedV2.fromRecorder(0xDEADBEEF, rec)
//
//        // Adaptive replay (consumes tape; ignores prior values)
//        val rep =
// ChoiceIO.ReplayAdaptive(Entropy(EntropySource.Replay(TapeSeedV2.toBitSequence(v2))))
//        beginBlock(rep, BlockKind.TUPLE, "pair")
//        val a2 = chooseInt(rep, 0..100, "a")
//        val b2 = chooseUInt(rep, 0u..255u, "b")
//        endBlock(rep, BlockKind.TUPLE, "pair")
//        val d2 = chooseDouble(rep, label = "u01")
//        val w2 = chooseBits(rep, 13, "raw13")
//        val bs2 = chooseBytes(rep, 4, "bytes4")
//
//        assertEquals(a, a2); assertEquals(b, b2); assertEquals(d, d2, 0.0)
//        assertEquals(w, w2); assertTrue(bs.contentEquals(bs2))
//    }
//
//    @Test
//    fun phase1_typed_nodes_compat() {
//        val g = Gen.int(0..10) zip Gen.bool zip Gen.uniform()
//        // Legacy path (bit budget)
//        val r1 = g.sample(SplittableRandom(12345))
//        // Choice path (hybrid tape)
//        val rec = ChoiceIO.Recorder(Entropy(EntropySource.Random(12345)))
//        val rr = g.sampleC(rec)
//        assertTrue(rr is RunResult.Ok)
//        assertNotNull(r1)
//    }
//
//    @Test
//    fun phase2_minimize_demo() {
//        // Find some failing input first
//        val gen = Gen.int(0..<1000)
//        val target = { x: Int -> x % 17 == 0 }
//        val found = gen.satisfyV2(iters = 5000, seed = 0x1234) { target(it) }
//            ?: error("No solution found")
//
//        // Minimize with the new engine
//        val minimized = gen.minimizeV2(found, iters = 5000, seed = 0x9999) { target(it) }
//            ?: error("minimizeV2 returned null")
//
//        // Expect 0 (global minimum that satisfies x % 17 == 0 in [0,999])
//        kotlin.test.assertEquals(0, minimized.result)
//    }
//
//    @Test
//    fun v2_foreach_min_demo() {
//        val random = SplittableRandom(42)
//        val genStr = Gen.string(Gen.int(0..20), Gen.range('a'..'z'))
//        try {
//            genStr.foreachMinV2(random, iters = 100000) { s ->
//                if ("abc" in s) error("boom: abc")
//            }
//        } catch (e: MinimizedExceptionV2) {
//            println("Minimized to: ${e.value}")
//            println("Seed: ${e.seed.toBase58String().take(80)}...")
//        }
//    }
// }
