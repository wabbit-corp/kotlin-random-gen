package one.wabbit.random.gen.util

internal actual fun exceptionFrames(e: Throwable): List<ExceptionFrame>? =
    e.stackTrace.map { frame ->
        ExceptionFrame(
            className = frame.className,
            methodName = frame.methodName,
            lineNumber = frame.lineNumber,
        )
    }

internal actual fun isFatalThrowable(e: Throwable): Boolean = e is VirtualMachineError

internal actual fun platformDefaultExceptionComparisonMode(): ExceptionComparisonMode =
    ExceptionComparisonMode.SAME_CLASS_MESSAGE_TOP_FRAME
