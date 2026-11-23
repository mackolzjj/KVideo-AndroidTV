package com.kvideo.tv

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 只做一件事：把未捕获异常的堆栈落到 filesDir/last_crash.txt。
 * 之前闪退只能靠猜，有了这个文件，下次直接看「高级设置 → 上次崩溃」就知道行号。
 */
class KvApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val head = buildString {
                    appendLine("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("time=${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
                    appendLine("thread=${thread.name}")
                    appendLine("sdk=${android.os.Build.VERSION.SDK_INT} ${android.os.Build.MODEL}")
                }
                File(filesDir, "last_crash.txt").writeText(head + sw.toString())
            }
            prev?.uncaughtException(thread, error)
        }
    }

    companion object {
        fun lastCrash(ctx: Context): String =
            runCatching { File(ctx.filesDir, "last_crash.txt").readText() }.getOrDefault("")

        fun clearCrash(ctx: Context) {
            runCatching { File(ctx.filesDir, "last_crash.txt").delete() }
        }
    }
}
