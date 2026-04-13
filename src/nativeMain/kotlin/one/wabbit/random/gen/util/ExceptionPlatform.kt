// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.random.gen.util

internal actual fun exceptionFrames(e: Throwable): List<ExceptionFrame>? = null

internal actual fun isFatalThrowable(e: Throwable): Boolean = false

internal actual fun platformDefaultExceptionComparisonMode(): ExceptionComparisonMode =
    ExceptionComparisonMode.SAME_CLASS_MESSAGE
