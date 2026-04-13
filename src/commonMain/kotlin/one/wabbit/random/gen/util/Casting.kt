// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.random.gen.util

/**
 * Low-level cast function that bypasses certain runtime checks.
 *
 * Normally, a direct cast in Kotlin (`value as B`) involves an intrinsic that can be expensive in
 * tight loops, due to extra type/variance/null checks. This hack is a small optimization that may
 * help performance-critical code where casts are guaranteed safe and happen frequently.
 *
 * Use with caution. This completely disables Kotlin's usual runtime type-check.
 */
@Suppress("UNCHECKED_CAST") internal fun <A, B> unsafeCast(value: A): B = value as B
