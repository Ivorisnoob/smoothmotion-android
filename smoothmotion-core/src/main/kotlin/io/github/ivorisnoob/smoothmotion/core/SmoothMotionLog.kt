package io.github.ivorisnoob.smoothmotion.core

import android.util.Log

/**
 * Where the library's log lines go. By default they go to logcat under
 * [TAG], so `adb logcat -s SmoothMotion` shows what the engine is doing.
 *
 * Route them into your own logger (Timber, a crash reporter's breadcrumbs)
 * or silence them:
 *
 * ```kotlin
 * SmoothMotionLog.logger = SmoothMotionLogger { priority, tag, message, error ->
 *     Timber.tag(tag).log(priority, error, message)
 * }
 * SmoothMotionLog.logger = SmoothMotionLogger.NONE
 * ```
 */
public object SmoothMotionLog {
    public const val TAG: String = "SmoothMotion"

    /** Set from any thread; read on the GL thread and the player's thread. */
    @Volatile
    @JvmStatic
    public var logger: SmoothMotionLogger = SmoothMotionLogger.LOGCAT

    @JvmStatic public fun d(tag: String, message: String) = logger.log(Log.DEBUG, tag, message, null)
    @JvmStatic public fun i(tag: String, message: String) = logger.log(Log.INFO, tag, message, null)
    @JvmStatic public fun w(tag: String, message: String, error: Throwable? = null) = logger.log(Log.WARN, tag, message, error)
    @JvmStatic public fun e(tag: String, message: String, error: Throwable? = null) = logger.log(Log.ERROR, tag, message, error)
}

/** One log line. [priority] is an `android.util.Log` level (`Log.DEBUG` ... `Log.ERROR`). */
public fun interface SmoothMotionLogger {
    public fun log(priority: Int, tag: String, message: String, error: Throwable?)

    public companion object {
        @JvmField
        public val LOGCAT: SmoothMotionLogger = SmoothMotionLogger { priority, tag, message, error ->
            if (error == null) Log.println(priority, tag, message)
            else Log.println(priority, tag, message + "\n" + Log.getStackTraceString(error))
        }

        @JvmField
        public val NONE: SmoothMotionLogger = SmoothMotionLogger { _, _, _, _ -> }
    }
}
