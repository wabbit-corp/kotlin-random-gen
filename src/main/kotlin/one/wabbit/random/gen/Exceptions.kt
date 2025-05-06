package one.wabbit.random.gen

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
    SAME_CLASS_MESSAGE_FULL_STACK
}

/**
 * Returns true if [e1] is considered the "same" exception as [e2].
 * By default, it checks the exact class type, message equality, and top stack frame
 * (including line number). You can adjust the comparison mode as needed.
 */
fun compareExceptions(
    e1: Throwable,
    e2: Throwable,
    mode: ExceptionComparisonMode = ExceptionComparisonMode.SAME_CLASS_MESSAGE_TOP_FRAME
): Boolean {
    // 1) Compare types
    if (e1.javaClass != e2.javaClass) return false

    // 2) Compare messages
    if (mode >= ExceptionComparisonMode.SAME_CLASS_MESSAGE) {
        if (e1.message != e2.message) return false
    }

    // 3) Compare stack frames as needed
    if (mode >= ExceptionComparisonMode.SAME_CLASS_MESSAGE_TOP_FRAME_NO_LINE) {
        val st1 = e1.stackTrace
        val st2 = e2.stackTrace

        // Edge case: if one is empty but not the other
        if (st1.isEmpty() && st2.isEmpty()) {
            // Then we consider them matching enough at the top frame level
            return true
        }
        if (st1.isEmpty() || st2.isEmpty()) return false

        // Compare top of stack
        if (st1[0].className != st2[0].className) return false
        if (st1[0].methodName != st2[0].methodName) return false

        // If we also want exact line match:
        if (mode == ExceptionComparisonMode.SAME_CLASS_MESSAGE_TOP_FRAME &&
            st1[0].lineNumber != st2[0].lineNumber
        ) return false

        // If we want full stack:
        if (mode == ExceptionComparisonMode.SAME_CLASS_MESSAGE_FULL_STACK) {
            if (st1.size != st2.size) return false
            for (i in st1.indices) {
                if (st1[i] != st2[i]) return false
            }
        }
    }

    return true
}
