// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.random.gen

/**
 * Sort key used to prefer smaller replay tapes during minimization.
 *
 * @property length number of bits read.
 * @property positive number of one bits read.
 */
data class RawTapeComplexity(val length: Long, val positive: Long) : Comparable<RawTapeComplexity> {
    /** Orders by shorter tapes first, then by fewer positive bits. */
    override fun compareTo(other: RawTapeComplexity): Int =
        when {
            this.length < other.length -> -1
            this.length > other.length -> 1
            this.positive < other.positive -> -1
            this.positive > other.positive -> 1
            else -> 0
        }

    override fun toString(): String = "L${length}P$positive"

    /** Constructors for tape complexity values. */
    companion object {
        /** Computes complexity from a [RawTapeReader]. */
        fun of(tape: RawTapeReader): RawTapeComplexity = RawTapeComplexity(tape.read, tape.read1)
    }
}
