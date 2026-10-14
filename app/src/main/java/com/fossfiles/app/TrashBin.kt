package com.fossfiles.app

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Properties

/**
 * Papelera local. Cada elemento se guarda en `<papelera>/<id>/<nombre original>` y su origen en
 * `<papelera>/<id>.info`. Mover a la papelera es un renombrado (instantáneo, no copia nada) y
 * restaurar devuelve el elemento a su carpeta original. Todo vive en el propio dispositivo.
 * Solo usa java.io / java.nio, así que se puede probar en la JVM.
 */
class TrashBin(private val dir: File) {

    /** Un elemento guardado: [id] es su carpeta dentro de la papelera y [originalPath] de dónde salió. */
    data class Entry(
        val id: String,
        val name: String,
        val originalPath: String?,
        val deletedAt: Long,
        val file: File,
        val isDirectory: Boolean,
        val sizeBytes: Long
    )

    /** Mueve [item] a la papelera con un renombrado (instantáneo). Los enlaces simbólicos se borran. */
    fun moveToTrash(item: File, now: Long = System.currentTimeMillis()) {
        if (!Files.exists(item.toPath(), LinkOption.NOFOLLOW_LINKS)) throw FileOpException("«${item.name}» ya no existe")
        if (isInside(item)) throw FileOpException("«${item.name}» ya está en la papelera")
        // Un enlace simbólico no tiene contenido que rescatar: se elimina directamente.
        if (Files.isSymbolicLink(item.toPath())) {
            FileRepository.delete(item)
            return
        }
        if (!dir.isDirectory && !dir.mkdirs()) throw FileOpException("No se pudo crear la papelera")
        try { File(dir, ".nomedia").createNewFile() } catch (e: IOException) { /* no es crítico */ }

        val id = newId(now)
        val holder = File(dir, id)
        if (!holder.mkdir()) throw FileOpException("No se pudo usar la papelera")
        try {
            FileRepository.move(item, holder)
        } catch (e: FileOpException) {
            holder.delete() // solo borra si quedó vacía
            throw e
        }
        writeInfo(id, item.absolutePath, now)
    }

    /** Del más reciente al más antiguo. */
    fun list(): List<Entry> {
        val holders = dir.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        val entries = ArrayList<Entry>()
        for (holder in holders) {
            val item = holder.listFiles()?.firstOrNull()
            if (item == null) { // carpeta vacía: resto de algo que falló
                holder.delete()
                File(dir, holder.name + ".info").delete()
                continue
            }
            val info = readInfo(holder.name)
            val isDir = item.isDirectory
            entries.add(
                Entry(
                    id = holder.name,
                    name = item.name,
                    originalPath = info?.first,
                    deletedAt = info?.second ?: holder.lastModified(),
                    file = item,
                    isDirectory = isDir,
                    sizeBytes = if (isDir) 0L else item.length()
                )
            )
        }
        return entries.sortedByDescending { it.deletedAt }
    }

    /**
     * Devuelve el elemento a su carpeta original (la recrea si ya no existe). Si no se puede,
     * lo deja en [fallbackDir]. Si ya hay algo con ese nombre, usa «nombre (1)».
     */
    fun restore(entry: Entry, fallbackDir: File): File {
        val originalParent = entry.originalPath?.let { File(it).parentFile }
        val dest = if (originalParent != null && (originalParent.isDirectory || originalParent.mkdirs())) {
            originalParent
        } else {
            fallbackDir
        }
        val restored = FileRepository.move(entry.file, dest)
        File(dir, entry.id).delete() // ya vacía
        File(dir, entry.id + ".info").delete()
        return restored
    }

    /** Borra un elemento de la papelera sin posibilidad de restaurarlo. */
    fun deleteForever(entry: Entry) {
        FileRepository.delete(File(dir, entry.id))
        File(dir, entry.id + ".info").delete()
    }

    /** Vacía toda la papelera. Lanza [FileOpException] si algo no se pudo borrar. */
    fun empty() {
        val all = dir.listFiles() ?: return
        var failed = 0
        for (f in all) {
            if (f.name == ".nomedia") continue
            try { FileRepository.delete(f) } catch (e: FileOpException) { failed++ }
        }
        if (failed > 0) throw FileOpException("No se pudo borrar $failed elementos de la papelera")
    }

    /** Borra lo que lleva más de [maxAgeMs] en la papelera. Devuelve cuántos borró. */
    fun purgeOlderThan(maxAgeMs: Long, now: Long = System.currentTimeMillis()): Int {
        var count = 0
        for (entry in list()) {
            if (now - entry.deletedAt > maxAgeMs) {
                try {
                    deleteForever(entry)
                    count++
                } catch (e: FileOpException) { /* se reintentará la próxima vez */ }
            }
        }
        return count
    }

    // ---------- Internos ----------

    private fun isInside(item: File): Boolean {
        val trash = try { dir.canonicalFile } catch (e: IOException) { return false }
        val target = try { item.canonicalFile } catch (e: IOException) { return false }
        return target == trash || target.path.startsWith(trash.path + File.separator)
    }

    private fun newId(now: Long): String {
        var n = 0
        while (File(dir, "$now-$n").exists() || File(dir, "$now-$n.info").exists()) n++
        return "$now-$n"
    }

    private fun writeInfo(id: String, originalPath: String, now: Long) {
        val props = Properties()
        props.setProperty("path", originalPath)
        props.setProperty("time", now.toString())
        try {
            OutputStreamWriter(FileOutputStream(File(dir, "$id.info")), Charsets.UTF_8).use { props.store(it, null) }
        } catch (e: IOException) {
            // Sin .info el elemento sigue en la papelera; solo se pierde el origen (se restaura a la raíz).
        }
    }

    private fun readInfo(id: String): Pair<String, Long>? {
        val f = File(dir, "$id.info")
        if (!f.isFile) return null
        return try {
            val props = Properties()
            InputStreamReader(FileInputStream(f), Charsets.UTF_8).use { props.load(it) }
            val path = props.getProperty("path") ?: return null
            val time = props.getProperty("time")?.toLongOrNull() ?: f.lastModified()
            path to time
        } catch (e: IOException) {
            null
        }
    }
}
