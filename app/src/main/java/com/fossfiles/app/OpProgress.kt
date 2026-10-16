package com.fossfiles.app

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Seguimiento de una operación larga (copiar, mover, comprimir, extraer, borrar): bytes procesados,
 * cancelación cooperativa y las rutas tocadas (para avisar al índice multimedia de Android).
 * Solo usa tipos de Java, así que se puede probar en la JVM.
 */
class OpProgress {

    @Volatile var cancelled = false
    @Volatile var totalBytes = 0L
    @Volatile var itemsTotal = 0
    @Volatile var itemsDone = 0

    private val bytes = AtomicLong(0L)
    private val touchedPaths = ConcurrentLinkedQueue<String>()
    private var touchedCount = 0

    val bytesDone: Long get() = bytes.get()

    fun addBytes(n: Long) {
        bytes.addAndGet(n)
    }

    /** Anota una ruta creada, movida o borrada (se guardan como mucho [MAX_TOUCHED]). */
    fun touch(path: String) {
        if (touchedCount < MAX_TOUCHED) {
            touchedPaths.add(path)
            touchedCount++
        }
    }

    fun touched(): List<String> = touchedPaths.toList()

    /** Lanza [FileOpException] si el usuario canceló: las operaciones lo llaman con frecuencia. */
    fun check() {
        if (cancelled) throw FileOpException(CANCELLED_MESSAGE)
    }

    companion object {
        const val MAX_TOUCHED = 500
        const val CANCELLED_MESSAGE = "Operación cancelada"
    }
}
