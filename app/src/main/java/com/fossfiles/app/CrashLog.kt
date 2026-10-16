package com.fossfiles.app

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Registro local del último error inesperado. NO se envía a ningún lado (la app no tiene permiso de
 * Internet): se guarda en el almacenamiento privado de la app y el usuario puede copiarlo desde
 * Ajustes para pegarlo en un reporte si quiere.
 */
object CrashLog {

    private const val FILE_NAME = "last_crash.txt"
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(appContext, thread, error)
            } catch (e: Throwable) {
                // lo importante es no tapar el error original
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        return if (file.isFile) file.readText() else null
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        val trace = StringWriter()
        error.printStackTrace(PrintWriter(trace))
        val text = "FossFiles $version\n" +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "Hilo: ${thread.name}\n\n$trace"
        File(context.filesDir, FILE_NAME).writeText(text)
    }
}
