// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.random.gen

data class RawTapeComplexity(val length: Long, val positive: Long) : Comparable<RawTapeComplexity> {
    override fun compareTo(other: RawTapeComplexity): Int =
        when {
            this.length < other.length -> -1
            this.length > other.length -> 1
            this.positive < other.positive -> -1
            this.positive > other.positive -> 1
            else -> 0
        }

    override fun toString(): String = "L${length}P$positive"

    companion object {
        fun of(tape: RawTapeReader): RawTapeComplexity = RawTapeComplexity(tape.read, tape.read1)
    }
}
