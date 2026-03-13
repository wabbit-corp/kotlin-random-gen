// package one.wabbit.random.gen
//
// import one.wabbit.random.gen.util.ExceptionComparisonMode
// import one.wabbit.random.gen.util.MutableBitDeque
// import one.wabbit.random.gen.util.compareExceptions
// import java.util.SplittableRandom
// import kotlin.math.min
//
// /** A result paired with a V2 seed (hybrid tape + choice log). */
// data class WithV2<out A>(val seed: Long, val v2: TapeSeedV2, val result: A)
//
// /** Record one run using the choice/tape engine, returning a V2 seed with result. */
// fun <A : Any> Gen<A>.recordOnce(seed: Long): WithV2<A>? {
//    val rec = ChoiceIO.Recorder(Entropy(EntropySource.Random(seed)))
//    return when (val r = sampleC(rec)) {
//        is RunResult.Ok -> {
//            val consumed = rec.entropy.positionBits.toInt()
//            val bitsPrefix = takePrefix(rec.entropy.consumed, consumed)
//            val v2 = TapeSeedV2(seed, consumed, packBits(bitsPrefix), ChoiceLog(rec.out.toList()))
//            WithV2(seed, v2, r.value)
//        }
//        else -> null
//    }
// }
//
// /** Try to find a run that satisfies predicate, returning its V2 seed. */
// fun <A : Any> Gen<A>.satisfyV2(iters: Int, seed: Long, p: (A) -> Boolean): WithV2<A>? {
//    val rng = SplittableRandom(seed)
//    repeat(iters) {
//        val s = rng.nextLong()
//        val got = recordOnce<A>(s) ?: return@repeat
//        if (p(got.result)) return got
//    }
//    return null
// }
//
//
// /**
// * Hypothesis-style greedy shrink (Phase 3):
// * Pass A: value-aware for ints/uints (0 -> bisection -> bounds) by encoding target values into
// spans.
// * Pass B: chunked zeroing within spans (halve, quarter, ...), then single-bit 1→0.
// * Pass C: block deletion (ddmin) over interior bit ranges.
// * After each accept: replay and re-record so spans realign.
// */
// fun <A : Any> Gen<A>.minimizeV2(
//    failing: WithV2<A>,
//    iters: Int = 10_000,
//    seed: Long = 0xBEEF,
//    predicate: (A) -> Boolean
// ): WithV2<A>? {
//    if (!predicate(failing.result)) return null
//
//    var tape = TapeSeedV2.toBitSequence(failing.v2) // current tape
//    var log  = failing.v2.log                        // current choice log
//    var cur  = failing                               // current best
//    var steps = 0
//
//    fun replayAndRecord(bits: MutableBitDeque): WithV2<A>? {
//        val ent = Entropy(EntropySource.Replay(bits))
//        val rec = ChoiceIO.Recorder(ent)
//        return when (val r = this.sampleC(rec)) {
//            is RunResult.Ok -> {
//                val consumed = rec.entropy.positionBits.toInt()
//                val prefix  = takePrefix(bits, consumed)
//                val v2 = TapeSeedV2(
//                    seed = cur.seed,
//                    bitCount = consumed,
//                    bits = packBits(prefix),
//                    log = ChoiceLog(rec.out.toList())
//                )
//                WithV2(cur.seed, v2, r.value)
//            }
//            else -> null
//        }
//    }
//
//    fun accept(newState: WithV2<A>) {
//        tape = TapeSeedV2.toBitSequence(newState.v2)
//        log  = newState.v2.log
//        cur  = newState
//    }
//
//    fun tryAccept(candidateTape: MutableBitDeque): Boolean {
//        val replayed = replayAndRecord(candidateTape)
//        if (replayed != null && predicate(replayed.result)) {
//            accept(replayed)
//            return true
//        }
//        return false
//    }
//
//    var changed = true
//    while (changed && steps < iters) {
//        steps++
//        changed = false
//
//        // ---------------- Pass A: value-aware (Int/UInt) ----------------
//        var idx = 0
//        outer@ while (idx < log.choices.size) {
//            val c = log.choices[idx]
//            val span = c.span
//            if (span.lengthBits <= 0) { idx++; continue }
//
//            when (val tag = c.tag) {
//                is Choice.Tag.Block -> { /* skip here; handled in Pass C */ }
//
//                is Choice.Tag.IntRange -> {
//                    val range  = tag.first .. tag.last
//                    for (v in intCandidates(range, tag.value)) {
//                        val cand = encodeIntIntoSpanTBE(tape, span, range, v)
//                        if (tryAccept(cand)) { changed = true; break@outer }
//                    }
//                }
//
//                is Choice.Tag.UIntRange -> {
//                    val range = tag.first .. tag.last
//                    for (v in uintCandidates(range, tag.value)) {
//                        val cand = encodeUIntIntoSpanTBE(tape, span, tag.first, tag.last, v)
//                        if (tryAccept(cand)) { changed = true; break@outer }
//                    }
//                }
//
//                else -> { /* other tags handled in Pass B */ }
//            }
//            idx++
//        }
//        if (changed) continue
//
//        // ---------------- Pass B: span-level chunk zeroing, then single bits ----------------
//        idx = 0
//        outer2@ while (idx < log.choices.size) {
//            val c = log.choices[idx]
//            val span = c.span
//            if (span.lengthBits <= 0 || c.tag is Choice.Tag.Block) { idx++; continue }
//
//            // Try big chunks first (delta-halving within span)
//            var chunk = span.lengthBits
//            while (chunk >= 8) { // don't be silly; 8 is a good lower bound
//                // zero each chunk in MSB-first order
//                var start = span.startBits.toInt()
//                var improved = false
//                while (start < span.startBits + span.lengthBits) {
//                    val end = min(start + chunk, span.startBits.toInt() + span.lengthBits)
//                    val cand = zeroRange(tape, start, end)
//                    if (tryAccept(cand)) { changed = true; improved = true; break@outer2 }
//                    start = end
//                }
//                if (improved) break
//                chunk /= 2
//            }
//
//            // Fallback: single-bit MSB->LSB flips 1→0 inside span
//            var bit = span.startBits.toInt()
//            while (bit < span.startBits + span.lengthBits) {
//                if (tape[bit.toLong()]) {
//                    val cand = tape.copy(); cand[bit.toLong()] = false
//                    if (tryAccept(cand)) { changed = true; break@outer2 }
//                }
//                bit++
//            }
//
//            idx++
//        }
//        if (changed) continue
//
//        // Pass S: SEQ element deletion with LEN co-shrink (generic)
//        run {
//            val seqs = blocksByKind(log, BlockKind.SEQ)
//            for ((sIx, eIx) in seqs) {
//                val lenInfo = findLenBefore(log, sIx) ?: continue
//                val (lenSpan, curLen, lenFirst, lenLast) = lenInfo
//                val elems = blocksByKind(log, BlockKind.ELEM).filter { it.first > sIx && it.second
// < eIx }
//
//                for ((elStart, elEnd) in elems) {
//                    val interior = interiorBitRange(log, elStart, elEnd) ?: continue
//                    val (from, to) = interior
//                    var cand = removeBitRange(tape, from, to)
//                    val newLen = (curLen - 1).coerceAtLeast(lenFirst).coerceAtMost(lenLast)
//                    cand = encodeIntIntoSpanTBE(cand, lenSpan, lenFirst..lenLast, newLen)
//                    if (tryAccept(cand)) { changed = true; return@run }
//                }
//            }
//        }
//
//        // ---------------- Pass C: block deletion (ddmin) ----------------
//        val del = firstRemovableBlock(log)
//        if (del != null) {
//            val (from, to) = del
//            if (ddminDeleteRange(this, tape, from, to, ::tryAccept)) {
//                changed = true
//                continue
//            }
//        }
//    }
//
//    return cur
// }
//
// // --------------------------- V2 foreachMin loop (exception-driven) ---------------------------
//
// class MinimizedExceptionV2(val original: Throwable, val seed: TapeSeedV2, val value: Any)
//    : Throwable(original.message, original)
//
// class FailedToMinimizeExceptionV2(val original: Throwable, val seed: TapeSeedV2)
//    : Throwable(original.message, original)
//
// /**
// * Generate values; on first exception that matches [exceptionMode], minimize with V2 shrinker,
// * then throw MinimizedExceptionV2 carrying the minimized value and a reproducible TapeSeedV2.
// */
// fun <A : Any> Gen<A>.foreachMinV2(
//    random: SplittableRandom,
//    iters: Int,
//    minimizerSteps: Int = 10_000,
//    exceptionMode: ExceptionComparisonMode = ExceptionComparisonMode.SAME_CLASS_MESSAGE_TOP_FRAME,
//    f: (A) -> Unit
// ) {
//    repeat(iters) {
//        val seed = random.nextLong()
//        val got = recordOnce<A>(seed) ?: return@repeat
//        try {
//            f(got.result)
//        } catch (e0: Throwable) {
//            if (e0 is VirtualMachineError) throw e0
//            // Predicate: reproduces the "same" exception
//            val p: (A) -> Boolean = { value ->
//                try {
//                    f(value); false
//                } catch (e1: Throwable) {
//                    if (e1 is VirtualMachineError) throw e1
//                    compareExceptions(e0, e1, exceptionMode)
//                }
//            }
//            check(p(got.result)) { "Expected exception to be thrown" }
//
//            val shrunk = minimizeV2(got, minimizerSteps, random.nextLong(), p)
//                ?: throw FailedToMinimizeExceptionV2(e0, got.v2)
//            throw MinimizedExceptionV2(e0, shrunk.v2, shrunk.result)
//        }
//    }
// }
//
// // --------------------------- Helpers ---------------------------
//
// // All blocks of a given kind, as (startIx, endIx)
// private fun blocksByKind(log: ChoiceLog, kind: BlockKind): List<Pair<Int, Int>> {
//    val out = mutableListOf<Pair<Int, Int>>()
//    val stack = ArrayDeque<Int>()
//    for ((i, c) in log.choices.withIndex()) {
//        val t = c.tag
//        if (t is Choice.Tag.Block && t.kind == kind) {
//            if (t.edge == Choice.Tag.Block.Edge.START) stack.addLast(i)
//            else if (t.edge == Choice.Tag.Block.Edge.END && stack.isNotEmpty()) out +=
// stack.removeLast() to i
//        }
//    }
//    return out
// }
//
// private fun interiorBitRange(log: ChoiceLog, startIx: Int, endIx: Int): Pair<Int, Int>? {
//    var lo: Int? = null; var hi: Int? = null
//    for (j in (startIx + 1) until endIx) {
//        val s = log.choices[j].span; if (s.lengthBits <= 0) continue
//        val a = s.startBits.toInt(); val b = a + s.lengthBits
//        lo = if (lo == null || a < lo!!) a else lo
//        hi = if (hi == null || b > hi!!) b else hi
//    }
//    return if (lo != null && hi != null && hi!! > lo!!) lo!! to hi!! else null
// }
//
// private fun intCandidates(range: IntRange, current: Int): Sequence<Int> = sequence {
//    val target = if (0 in range) 0 else range.first
//    if (current != target) yield(target)
//    var v = current
//    val seen = HashSet<Int>()
//    while (v != target) {
//        val mid = target + (v - target) / 2
//        if (mid == v || !seen.add(mid)) break
//        yield(mid)
//        v = mid
//    }
//    if (range.first != target) yield(range.first)
//    if (range.last != target && range.last != range.first) yield(range.last)
// }
//
// private fun uintCandidates(range: UIntRange, current: UInt): Sequence<UInt> = sequence {
//    val target = if (range.first == 0u) 0u else range.first
//    if (current != target) yield(target)
//    var v = current
//    val seen = HashSet<UInt>()
//    while (v != target) {
//        val mid = target + ((v - target) shr 1)
//        if (mid == v || !seen.add(mid)) break
//        yield(mid)
//        v = mid
//    }
//    if (range.first != target) yield(range.first)
//    if (range.last != target && range.last != range.first) yield(range.last)
// }
//
// private fun ceilLog2(m: Int): Int {
//    require(m > 0)
//    var x = m - 1
//    var p = 0
//    while (x > 0) { p++; x = x ushr 1 }
//    return p
// }
//
// private fun floorLog2(m: Int): Int {
//    require(m > 0)
//    var x = m
//    var p = -1
//    while (x > 0) { x = x ushr 1; p++ }
//    return p
// }
//
// fun <A : Any> Gen<A>.replayV2(v2: TapeSeedV2): A? {
//    val ent = Entropy(EntropySource.Replay(TapeSeedV2.toBitSequence(v2)))
//    val io = ChoiceIO.ReplayAdaptive(ent)
//    return when (val r = sampleC(io)) {
//        is RunResult.Ok -> r.value
//        else -> null
//    }
// }
//
// /**
// * Write `value` in the span using the same TBE (truncated-binary) scheme as Uniforms.uint.
// * Respects the currently recorded span length L (k or k+1). Never *extends* the span.
// * If the requested value cannot be represented with L bits (rare when L==k and value >= t),
// * we return the original tape (no-op) so the caller can try another candidate (e.g., 0).
// */
// private fun encodeUIntIntoSpanTBE(
//    tape: MutableBitDeque,
//    span: BitSpan,
//    first: UInt,
//    last: UInt,
//    value: UInt
// ): MutableBitDeque {
//    require(first <= value && value <= last)
//    val m = (last - first + 1u).toInt()
//    require(m > 0)
//
//    val k  = floorLog2(m)                // floor(log2 m)
//    val t  = (1 shl (k + 1)) - m         // t = 2^(k+1) - m
//    val L  = span.lengthBits
//    val u  = (value - first).toInt()
//
//    val out = tape.copy()
//    val start = span.startBits.toInt()
//
//    fun writeBits(bits: Int, producer: (Int) -> Boolean) {
//        for (i in 0 until bits) out[(start + i).toLong()] = producer(i)
//        // zero any trailing slack in the span
//        for (i in bits until L) out[(start + i).toLong()] = false
//    }
//
//    return when {
//        L < k -> {
//            // Shouldn't happen with our sampler, but be conservative: no-op
//            tape
//        }
//        L == k -> {
//            // Only u < t are encodable with k bits. If not, bail (caller will try a smaller
// candidate).
//            if (u >= t) return tape
//            writeBits(k) { i -> ((u ushr (k - 1 - i)) and 1) == 1 }
//            out
//        }
//        else -> {
//            // L >= k+1: We can encode either the short or long code.
//            if (u < t) {
//                // short code (k bits), pad remaining bits with 0 to keep lexicographically
// minimal
//                writeBits(k) { i -> ((u ushr (k - 1 - i)) and 1) == 1 }
//                out
//            } else {
//                // long code (k+1 bits)
//                val q = u + t                 // q in [t .. 2^(k+1)-1]
//                val hi = q ushr 1             // k bits
//                val lo = q and 1              // 1 bit
//                writeBits(k + 1) { i ->
//                    if (i < k) ((hi ushr (k - 1 - i)) and 1) == 1 else lo == 1
//                }
//                out
//            }
//        }
//    }
// }
//
// private fun encodeIntIntoSpanTBE(
//    tape: MutableBitDeque,
//    span: BitSpan,
//    range: IntRange,
//    value: Int
// ): MutableBitDeque {
//    require(value in range)
//    val first = range.first
//    val last  = range.last
//    val uMax  = (last - first + 1)
//    val u     = (value - first).coerceIn(0, uMax - 1).toUInt()
//    return encodeUIntIntoSpanTBE(tape, span, 0u, (uMax - 1).toUInt(), u)
// }
//
// private fun zeroRange(src: MutableBitDeque, from: Int, to: Int): MutableBitDeque {
//    val out = src.copy()
//    var i = from
//    while (i < to) { out[i.toLong()] = false; i++ }
//    return out
// }
//
// private fun takePrefix(src: MutableBitDeque, count: Int): MutableBitDeque {
//    val out = MutableBitDeque()
//    val n = min(count, src.size.toInt())
//    var i = 0
//    while (i < n) { out.add(src[i.toLong()]); i++ }
//    return out
// }
//
// /** Identify earliest BlockStart..BlockEnd pair with non-empty interior; return absolute bit
// range to delete. */
// private fun firstRemovableBlock(log: ChoiceLog): Pair<Int, Int>? {
//    val stack = ArrayDeque<Int>()
//    for (i in log.choices.indices) {
//        val c = log.choices[i]
//        if (c.tag is Choice.Tag.Block && c.tag.edge == Choice.Tag.Block.Edge.START)
// stack.addLast(i)
//        else if (c.tag is Choice.Tag.Block && c.tag.edge == Choice.Tag.Block.Edge.END) {
//            if (stack.isNotEmpty()) {
//                val startIx = stack.removeLast()
//                val interior = (startIx + 1) until i
//                var minBit: Int? = null
//                var maxBit: Int? = null
//                for (j in interior) {
//                    val s = log.choices[j].span
//                    if (s.lengthBits <= 0) continue
//                    if (minBit == null || s.startBits.toInt() < minBit!!) minBit =
// s.startBits.toInt()
//                    val end = s.startBits.toInt() + s.lengthBits
//                    if (maxBit == null || end > maxBit!!) maxBit = end
//                }
//                if (minBit != null && maxBit != null && maxBit!! > minBit!!) return minBit!! to
// maxBit!!
//            }
//        }
//    }
//    return null
// }
//
// /** ddmin: try removing progressively smaller partitions of [fromBit, toBit) until something
// sticks. */
// private fun <A : Any> ddminDeleteRange(
//    gen: Gen<A>,
//    baseTape: MutableBitDeque,
//    fromBit: Int,
//    toBit: Int,
//    tryAccept: (MutableBitDeque) -> Boolean
// ): Boolean {
//    val length = toBit - fromBit
//    if (length <= 0) return false
//
//    var n = 2
//    while (n <= length) {
//        val partSize = (length + n - 1) / n
//        var removedOne = false
//        var i = 0
//        while (i < n) {
//            val s = fromBit + i * partSize
//            val e = min(fromBit + (i + 1) * partSize, toBit)
//            if (s >= e) { i++; continue }
//            val cand = removeBitRange(baseTape, s, e)
//            if (tryAccept(cand)) {
//                // Accepted: restart ddmin on the *new* tape with same n
//                removedOne = true
//                break
//            }
//            i++
//        }
//        if (!removedOne) {
//            if (partSize <= 1) return false
//            n = min(n * 2, length)
//        }
//    }
//    return false
// }
//
// private fun removeBitRange(src: MutableBitDeque, fromBit: Int, toBit: Int): MutableBitDeque {
//    val out = MutableBitDeque()
//    var i = 0
//    while (i < fromBit && i < src.size.toInt()) { out.add(src[i.toLong()]); i++ }
//    var j = toBit
//    while (j < src.size) { out.add(src[j.toLong()]); j++ }
//    return out
// }
//
// // --------------------------- Optional: pretty trace ---------------------------
//
// fun formatChoiceLog(log: ChoiceLog): String = buildString {
//    for (c in log.choices) {
//        append("#").append(c.ix).append(" ")
//        when (val t = c.tag) {
//            is Choice.Tag.Block      -> append("Block(").append(t.edge).append(")")
//            is Choice.Tag.Bool       -> append("Bool=").append(t.value)
//            is Choice.Tag.IntRange   ->
// append("Int[").append(t.first).append("..").append(t.last).append("]=").append(t.value)
//            is Choice.Tag.UIntRange  ->
// append("UInt[").append(t.first).append("..").append(t.last).append("]=").append(t.value)
//            is Choice.Tag.Bits       -> append("Bits(width=").append(t.width).append(")")
//            is Choice.Tag.DoubleU01  ->
// append("Double(eps=").append(t.eps).append(")=").append(t.value)
//            is Choice.Tag.Bytes      -> append("Bytes(len=").append(t.length).append(")")
//        }
//        append("  span=L").append(c.span.lengthBits).append("@").append(c.span.startBits)
//        c.label?.let { append("  label=").append(it) }
//        append('\n')
//    }
// }
//
// private data class LengthInfo(val span: BitSpan, val value: Int, val first: Int, val last: Int)
//
// private fun findLenBefore(log: ChoiceLog, seqStartIx: Int): LengthInfo? {
//    var i = seqStartIx - 1
//    var lenEnd = -1
//    while (i >= 0) {
//        val c = log.choices[i]
//        if (c.tag is Choice.Tag.Block && c.tag.kind == BlockKind.LEN && c.tag.edge ==
// Choice.Tag.Block.Edge.END) {
//            lenEnd = i; break
//        }
//        i--
//    }
//    if (lenEnd < 0) return null
//    var lenStart = -1
//    i = lenEnd - 1
//    while (i >= 0) {
//        val c = log.choices[i]
//        if (c.tag is Choice.Tag.Block && c.tag.kind == BlockKind.LEN && c.tag.edge ==
// Choice.Tag.Block.Edge.START) {
//            lenStart = i; break
//        }
//        i--
//    }
//    if (lenStart < 0) return null
//
//    for (j in (lenStart + 1) until lenEnd) {
//        val cj = log.choices[j]
//        val t = cj.tag
//        if (t is Choice.Tag.IntRange) {
//            return LengthInfo(cj.span, t.value, t.first, t.last)
//        }
//    }
//    return null
// }
