// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.random.gen.util

enum class ExceptionComparisonMode {
    /** Check that exception classes match only. */
    SAME_CLASS,

    /** Check that classes + messages match exactly. */
    SAME_CLASS_MESSAGE,

    /** Check classes + messages + the top stack-frame (method/class) but ignore line number. */
    SAME_CLASS_MESSAGE_TOP_FRAME_NO_LINE,

    /** Check classes + messages + the top stack-frame including line number. */
    SAME_CLASS_MESSAGE_TOP_FRAME,

    /** Check classes + messages + *entire* stack trace. */
    SAME_CLASS_MESSAGE_FULL_STACK,
}

data class ExceptionFrame(
    val className: String?,
    val methodName: String?,
    val lineNumber: Int?,
)

internal expect fun exceptionFrames(e: Throwable): List<ExceptionFrame>?

internal expect fun isFatalThrowable(e: Throwable): Boolean

internal expect fun platformDefaultExceptionComparisonMode(): ExceptionComparisonMode

fun defaultExceptionComparisonMode(): ExceptionComparisonMode = platformDefaultExceptionComparisonMode()

/**
 * Returns true if [e1] is considered the "same" exception as [e2].
 *
 * On platforms without inspectable stack frames, stack-sensitive modes degrade to class/message
 * comparison after those fields have matched.
 */
fun compareExceptions(
    e1: Throwable,
    e2: Throwable,
    mode: ExceptionComparisonMode = defaultExceptionComparisonMode(),
): Boolean {
    if (e1::class != e2::class) return false

    if (mode >= ExceptionComparisonMode.SAME_CLASS_MESSAGE && e1.message != e2.message) {
        return false
    }

    if (mode < ExceptionComparisonMode.SAME_CLASS_MESSAGE_TOP_FRAME_NO_LINE) {
        return true
    }

    val st1 = exceptionFrames(e1)
    val st2 = exceptionFrames(e2)

    if (st1 == null || st2 == null) {
        return true
    }

    if (st1.isEmpty() && st2.isEmpty()) {
        return true
    }
    if (st1.isEmpty() || st2.isEmpty()) return false

    if (st1[0].className != st2[0].className) return false
    if (st1[0].methodName != st2[0].methodName) return false

    if (
        mode == ExceptionComparisonMode.SAME_CLASS_MESSAGE_TOP_FRAME &&
            st1[0].lineNumber != st2[0].lineNumber
    ) {
        return false
    }

    if (mode == ExceptionComparisonMode.SAME_CLASS_MESSAGE_FULL_STACK) {
        if (st1.size != st2.size) return false
        for (i in st1.indices) {
            if (st1[i] != st2[i]) return false
        }
    }

    return true
}
