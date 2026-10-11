package com.fossfiles.app

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

enum class SortMode { NAME, DATE, SIZE, TYPE }

/** Error con un mensaje ya listo para mostrarse al usuario. */
class FileOpException(message: String) : Exception(message)

data class SearchResult(val items: List<FileItem>, val truncated: Boolean)

/**
 * Operaciones de archivos. Solo usa java.io / java.nio (nada de Android), así que
 * se puede probar en la JVM. Todas las operaciones lanzan [FileOpException] con un
 * mensaje legible si algo falla, en vez de devolver un Boolean mudo.
 */
object FileRepository {

    const val MAX_SEARCH_RESULTS = 5_000
    const val MAX_SEARCH_DEPTH = 6

    // ---------- Listado y orden ----------

    fun listDirectory(dir: File, sortMode: SortMode = SortMode.NAME): List<FileItem> {
        val children = dir.listFiles() ?: throw FileOpException("No se puede leer esta carpeta")
        return sortItems(children.map { FileItem(it) }, sortMode)
    }

    fun sortItems(items: List<FileItem>, sortMode: SortMode): List<FileItem> {
        val byName = compareBy<FileItem> { it.name.lowercase() }
        val comparator: Comparator<FileItem> = when (sortMode) {
            SortMode.NAME -> byName
            SortMode.DATE -> compareByDescending<FileItem> { it.lastModified }.then(byName)
            SortMode.SIZE -> compareByDescending<FileItem> { it.sizeBytes }.then(byName)
            SortMode.TYPE -> compareBy<FileItem> { it.file.extension.lowercase() }.then(byName)
        }
        // Carpetas siempre primero, sea cual sea el orden elegido.
        return items.sortedWith(compareByDescending<FileItem> { it.isDirectory }.then(comparator))
    }

    // ---------- Nombres ----------

    /** Devuelve el nombre limpio o lanza [FileOpException] si no es válido. */
    fun validateName(raw: String): String {
        val name = raw.trim()
        if (name.isEmpty()) throw FileOpException("El nombre no puede estar vacío")
        if (name == "." || name == "..") throw FileOpException("Nombre no válido")
        if (name.any { it == '/' || it == '\u0000' }) throw FileOpException("El nombre no puede contener «/»")
        if (name.toByteArray(Charsets.UTF_8).size > 255) throw FileOpException("El nombre es demasiado largo")
        return name
    }

    /** "foo.txt" -> "foo (1).txt", "foo (2).txt"... Las carpetas no tienen extensión. */
    fun uniqueTarget(dir: File, name: String, isDirectory: Boolean): File {
        val first = File(dir, name)
        if (!existsNoFollow(first)) return first
        val dot = name.lastIndexOf('.')
        val base = if (!isDirectory && dot > 0) name.substring(0, dot) else name
        val ext = if (!isDirectory && dot > 0) name.substring(dot) else ""
        for (n in 1..100_000) {
            val candidate = File(dir, "$base ($n)$ext")
            if (!existsNoFollow(candidate)) return candidate
        }
        throw FileOpException("No se pudo encontrar un nombre libre para «$name»")
    }

    // ---------- Crear / renombrar / borrar ----------

    fun createFolder(parent: File, rawName: String): File {
        val name = validateName(rawName)
        val target = File(parent, name)
        if (existsNoFollow(target)) throw FileOpException("Ya existe «$name»")
        if (!target.mkdir()) throw FileOpException("No se pudo crear la carpeta")
        return target
    }

    fun rename(item: File, rawName: String): File {
        val name = validateName(rawName)
        if (name == item.name) return item
        val parent = item.parentFile ?: throw FileOpException("No se puede renombrar esta ubicación")
        val target = File(parent, name)
        // Si el sistema de archivos ignora mayúsculas, "a" -> "A" apunta al mismo archivo: se permite.
        if (existsNoFollow(target) && !isSameFile(item, target)) throw FileOpException("Ya existe «$name»")
        if (!item.renameTo(target)) throw FileOpException("No se pudo renombrar «${item.name}»")
        return target
    }

    /** Borra un archivo o carpeta. Los enlaces simbólicos se borran como enlace, sin seguirlos. */
    fun delete(item: File) {
        val path = item.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw FileOpException("«${item.name}» ya no existe")
        try {
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (e: IOException) {
            throw FileOpException("No se pudo eliminar «${item.name}»")
        }
    }

    // ---------- Copiar / mover ----------

    /** Copia [source] dentro de [destDir]. Si el nombre ya existe, usa "nombre (1)". */
    fun copy(source: File, destDir: File): File {
        checkTransfer(source, destDir)
        val target = uniqueTarget(destDir, source.name, source.isDirectory)
        try {
            copyTree(source, target)
        } catch (e: IOException) {
            deleteQuietly(target) // target era un nombre libre: todo lo que haya ahí lo creamos nosotros
            throw FileOpException("No se pudo copiar «${source.name}»")
        }
        return target
    }

    /**
     * Mueve [source] a [destDir]. Primero intenta un renombrado (instantáneo en el mismo
     * volumen); solo si falla copia y borra el original. Mover a la misma carpeta no hace nada.
     */
    fun move(source: File, destDir: File): File {
        checkTransfer(source, destDir)
        val parent = source.parentFile
        if (parent != null && canonical(parent) == canonical(destDir)) return source
        val target = uniqueTarget(destDir, source.name, source.isDirectory)
        if (source.renameTo(target)) return target

        // Otro volumen (o renombrado no permitido): copiar y luego borrar el original.
        try {
            copyTree(source, target)
        } catch (e: IOException) {
            deleteQuietly(target)
            throw FileOpException("No se pudo mover «${source.name}»")
        }
        try {
            delete(source)
        } catch (e: FileOpException) {
            throw FileOpException("Se copió «${source.name}» pero no se pudo borrar el original")
        }
        return target
    }

    private fun checkTransfer(source: File, destDir: File) {
        if (!existsNoFollow(source)) throw FileOpException("«${source.name}» ya no existe")
        if (!destDir.isDirectory) throw FileOpException("La carpeta de destino no existe")
        if (Files.isSymbolicLink(source.toPath())) throw FileOpException("No se pueden copiar ni mover enlaces simbólicos")
        if (source.isDirectory) {
            val src = canonical(source)
            val dst = canonical(destDir)
            if (dst == src || dst.path.startsWith(src.path + File.separator)) {
                throw FileOpException("No puedes copiar ni mover una carpeta dentro de sí misma")
            }
        }
    }

    /** Copia un archivo o árbol de carpetas sin seguir enlaces simbólicos (se omiten). */
    private fun copyTree(source: File, target: File) {
        val src = source.toPath()
        val dst = target.toPath()
        Files.walkFileTree(src, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.createDirectory(dst.resolve(src.relativize(dir).toString()))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile) Files.copy(file, dst.resolve(src.relativize(file).toString()))
                return FileVisitResult.CONTINUE
            }
        })
    }

    // ---------- Búsqueda y categorías ----------

    /**
     * Busca por nombre hasta [MAX_SEARCH_DEPTH] niveles. [isActive] se consulta en cada
     * entrada para poder cancelar una búsqueda vieja en cuanto el usuario sigue escribiendo.
     */
    fun search(
        root: File,
        query: String,
        sortMode: SortMode = SortMode.NAME,
        isActive: () -> Boolean = { true }
    ): SearchResult {
        val needle = query.trim()
        val results = ArrayList<FileItem>()
        var truncated = false
        walk(root, MAX_SEARCH_DEPTH, isActive) { f ->
            if (f.name.contains(needle, ignoreCase = true)) {
                if (results.size >= MAX_SEARCH_RESULTS) {
                    truncated = true
                    return@walk false
                }
                results.add(FileItem(f))
            }
            true
        }
        return SearchResult(sortItems(results, sortMode), truncated)
    }

    /**
     * Escanea un set fijo de carpetas conocidas (no todo el almacenamiento) y filtra por
     * extensión (null = todas).
     */
    fun scanCategory(
        dirs: List<File>,
        extensions: Set<String>?,
        maxDepth: Int = 4,
        isActive: () -> Boolean = { true }
    ): List<FileItem> {
        val results = ArrayList<FileItem>()
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            walk(dir, maxDepth, isActive) { f ->
                if (f.isFile && (extensions == null || f.extension.lowercase() in extensions)) {
                    results.add(FileItem(f))
                }
                true
            }
        }
        return results.sortedByDescending { it.lastModified }
    }

    // ---------- Utilidades ----------

    /** Recorre hasta [maxDepth] niveles sin seguir enlaces simbólicos. [visit] devuelve false para parar. */
    private fun walk(root: File, maxDepth: Int, isActive: () -> Boolean, visit: (File) -> Boolean) {
        val stack = java.util.ArrayDeque<Pair<File, Int>>()
        stack.push(root to 0)
        while (stack.isNotEmpty()) {
            if (!isActive()) return
            val (dir, depth) = stack.pop()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (!isActive()) return
                if (!visit(child)) return
                if (depth + 1 < maxDepth && child.isDirectory && !Files.isSymbolicLink(child.toPath())) {
                    stack.push(child to depth + 1)
                }
            }
        }
    }

    private fun existsNoFollow(f: File) = Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun isSameFile(a: File, b: File) =
        try { Files.isSameFile(a.toPath(), b.toPath()) } catch (e: IOException) { false }

    private fun canonical(f: File): File =
        try { f.canonicalFile } catch (e: IOException) { throw FileOpException("No se pudo resolver la ruta de «${f.name}»") }

    private fun deleteQuietly(f: File) {
        try { delete(f) } catch (e: Exception) { /* nada que limpiar */ }
    }
}
