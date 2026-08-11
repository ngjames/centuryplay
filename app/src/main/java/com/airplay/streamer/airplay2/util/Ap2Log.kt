package com.airplay.streamer.airplay2.util

import com.airplay.streamer.util.LogServer

/**
 * JVM-safe logging for the airplay2 package.
 *
 * On an Android device this delegates to [LogServer] (which feeds both logcat
 * and the in-app HTTP log server at :8080). On the JVM (unit tests) it falls
 * back to [println] so tests can run without the android.* runtime.
 *
 * Platform detection runs once at class-load time. It must never reference
 * android classes directly (that would crash JVM unit tests with
 * NoClassDefFoundError); only the class NAME is probed via reflection, so
 * Ap2Log itself stays loadable on a plain JVM.
 */
internal object Ap2Log {
    private val isJvm: Boolean = try {
        // android.util.Log exists on BOTH a device and an AGP JVM unit test
        // classpath (the "mockable" android.jar). Distinguish them: on a
        // device the class is loaded by the boot classloader (null), in unit
        // tests it comes from the mockable jar on the app classpath.
        val logClass = Class.forName("android.util.Log")
        logClass.classLoader != null
    } catch (_: ClassNotFoundException) {
        true
    }

    fun log(msg: String) {
        if (isJvm) {
            println("AP2: $msg")
        } else {
            LogServer.log(msg)
        }
    }

    fun d(tag: String, msg: String) {
        if (isJvm) {
            println("D/$tag: $msg")
        } else {
            LogServer.d(tag, msg)
        }
    }

    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        if (isJvm) {
            println("E/$tag: $msg")
            throwable?.let { println("E/$tag: ${it.stackTraceToString()}") }
        } else {
            LogServer.e(tag, msg, throwable)
        }
    }
}
