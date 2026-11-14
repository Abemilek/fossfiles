package com.fossfiles.app

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.text.Normalizer
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

enum class SortMode { NAME, DATE, SIZE, TYPE }

/** Error con un mensaje ya listo para mostrarse al usuario. */
class FileOpException(message: String) : Exception(message)

data class SearchResult(val items: List<FileItem>, val truncated: Boolean)

data class TreeStats(val files: Int, val folders: Int, val bytes: Long)

/**
 * Operaciones de archivos. Solo usa java.io / java.nio (nada de Android), así que
 * se puede probar en la JVM. Todas las operaciones lanzan [FileOpException] con un
 * mensaje legible si algo falla, en vez de devolver un Boolean mudo.
 */
object FileRepository {

    const val MAX_SEARCH_RESULTS = 5_000
    const val MAX_SEARCH_DEPTH = 6
    const val MAX_ZIP_ENTRIES = 100_000
    private const val COPY_BUFFER_SIZE = 256 * 1024

    // Android 11+ no deja ver estas carpetas ni siquiera con "acceso a todos los archivos".
    private val PROTECTED_DIR = Regex(".*/Android/(data|obb)(/.*)?")

    // ---------- Listado y orden ----------

    fun listDirectory(
        dir: File,
        sortMode: SortMode = SortMode.NAME,
        showHidden: Boolean = true,
        reverse: Boolean = false
    ): List<FileItem> {
        val children = dir.listFiles() ?: throw FileOpException(unreadableMessage(dir))
        val items = ArrayList<FileItem>(children.size)
        for (child in children) {
            if (!showHidden && child.name.startsWith(".")) continue
            items.add(FileItem.of(child))
        }
        return sortItems(items, sortMode, reverse)
    }

    private fun unreadableMessage(dir: File): String =
        if (PROTECTED_DIR.matches(dir.absolutePath)) {
            "Android protege esta carpeta (Android/data y Android/obb): ninguna app de archivos puede abrirla desde Android 11"
        } else {
            "No se puede leer esta carpeta"
        }

    private class Keyed(
        @JvmField val item: FileItem,
        @JvmField val key: String,      // minúsculas y sin acentos, con los números codificados (ver naturalKey)
        @JvmField val ext: String
    )

    /**
     * Ordena con "orden natural" (foto2 antes que foto10, sin distinguir mayúsculas ni acentos) y con
     * las carpetas siempre primero. [reverse] invierte el orden dentro de carpetas y archivos.
     *
     * Rendimiento: las claves se calculan una sola vez por elemento; carpetas y archivos se separan
     * antes de ordenar (así ninguna comparación gasta tiempo en "¿es carpeta?"), y el nombre se compara
     * con una clave natural precodificada mediante String.compareTo (intrínseco, mucho más rápido que
     * recorrer carácter a carácter en cada comparación). Con nombres ASCII el resultado es idéntico al del
     * algoritmo anterior (lo comprueba una prueba diferencial); es una ordenación estable, como antes.
     *
     * Diferencia deliberada: el comparador anterior ([naturalCompare]) no era transitivo cuando se mezclaban
     * dígitos no ASCII (p. ej. árabe-índicos), dígitos ASCII y letras (٩ < 10 < b < ٩), y en ese caso la
     * ordenación podía lanzar "Comparison method violates its general contract". Ahora todos los dígitos
     * decimales Unicode cuentan por su valor numérico, lo que da siempre un orden total.
     */
    fun sortItems(items: List<FileItem>, sortMode: SortMode, reverse: Boolean = false): List<FileItem> {
        val needExt = sortMode == SortMode.TYPE
        val keyed = ArrayList<Keyed>(items.size)
        for (item in items) {
            keyed.add(Keyed(item, naturalKey(sortKey(item.name)), if (needExt) item.file.extension.lowercase() else ""))
        }

        val byName = Comparator<Keyed> { a, b -> a.key.compareTo(b.key) }
        var comparator: Comparator<Keyed> = when (sortMode) {
            SortMode.NAME -> byName
            SortMode.DATE -> Comparator { a, b ->
                val c = b.item.lastModified.compareTo(a.item.lastModified)
                if (c != 0) c else byName.compare(a, b)
            }
            SortMode.SIZE -> Comparator { a, b ->
                val c = b.item.sizeBytes.compareTo(a.item.sizeBytes)
                if (c != 0) c else byName.compare(a, b)
            }
            SortMode.TYPE -> Comparator { a, b ->
                val c = a.ext.compareTo(b.ext)
                if (c != 0) c else byName.compare(a, b)
            }
        }
        if (reverse) comparator = comparator.reversed()

        // Partición estable: carpetas primero. Equivale a ordenar por (esCarpeta desc, comparador).
        val folders = ArrayList<Keyed>()
        val files = ArrayList<Keyed>(keyed.size)
        for (k in keyed) if (k.item.isDirectory) folders.add(k) else files.add(k)
        java.util.Collections.sort(folders, comparator)
        java.util.Collections.sort(files, comparator)

        val result = ArrayList<FileItem>(keyed.size)
        for (k in folders) result.add(k.item)
        for (k in files) result.add(k.item)
        return result
    }

    /**
     * Codifica una clave (ya en minúsculas y sin acentos) para que comparar con String.compareTo dé el
     * orden natural: cada bloque de dígitos pasa a "0" + (cantidad de cifras significativas) + cifras
     * (como dígitos ASCII, sin ceros a la izquierda). Cualquier dígito decimal Unicode se trata como el
     * dígito ASCII de su mismo valor, así el orden es siempre total (ver [sortItems]).
     * Con nombres sin dígitos devuelve la misma cadena, sin crear nada.
     */
    internal fun naturalKey(key: String): String {
        var hasDigit = false
        for (c in key) {
            if (c in '0'..'9' || (c.code >= 128 && c.isDigit())) {
                hasDigit = true
                break
            }
        }
        if (!hasDigit) return key
        val out = StringBuilder(key.length + 8)
        val n = key.length
        var i = 0
        while (i < n) {
            if (isDecimalDigit(key[i])) {
                var end = i
                while (end < n && isDecimalDigit(key[end])) end++
                var start = i
                while (start < end - 1 && digitValue(key[start]) == 0) start++
                // '0' ordena frente a los no-dígitos igual que cualquier dígito ASCII.
                out.append('0').append((end - start).toChar())
                for (k in start until end) out.append('0' + digitValue(key[k]))
                i = end
            } else {
                out.append(key[i])
                i++
            }
        }
        return out.toString()
    }

    private fun isDecimalDigit(c: Char): Boolean = c in '0'..'9' || (c.code >= 128 && c.isDigit())

    private fun digitValue(c: Char): Int = if (c in '0'..'9') c - '0' else Character.digit(c, 10)

    /** Minúsculas y sin acentos ("Árbol" ordena junto a "arbol"). Solo normaliza si hace falta. */
    internal fun sortKey(name: String): String {
        val lower = name.lowercase()
        if (lower.all { it.code < 128 }) return lower
        val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
        return decomposed.filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
    }

    /** Compara texto tratando los números como números: "a2" < "a10", "a02" == "a2" en valor. */
    internal fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                var si = i
                while (si < ei - 1 && a[si] == '0') si++ // sin ceros a la izquierda (queda al menos un dígito)
                var sj = j
                while (sj < ej - 1 && b[sj] == '0') sj++
                val la = ei - si
                val lb = ej - sj
                if (la != lb) return la.compareTo(lb)
                for (k in 0 until la) {
                    val d = a[si + k].compareTo(b[sj + k])
                    if (d != 0) return d
                }
                i = ei
                j = ej
            } else {
                if (ca != cb) return ca.compareTo(cb)
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }

    /** Cuántos elementos hay dentro de una carpeta, o -1 si no se puede leer. */
    fun countChildren(dir: File): Int = dir.list()?.size ?: -1

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
    fun delete(item: File, progress: OpProgress? = null) {
        val path = item.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw FileOpException("«${item.name}» ya no existe")
        try {
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    progress?.check()
                    progress?.touch(file.toString())
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
    fun copy(source: File, destDir: File, progress: OpProgress? = null): File {
        checkTransfer(source, destDir)
        val target = uniqueTarget(destDir, source.name, source.isDirectory)
        try {
            copyTree(source, target, progress)
        } catch (e: FileOpException) {
            deleteQuietly(target) // cancelado: no se deja una copia a medias
            throw e
        } catch (e: IOException) {
            deleteQuietly(target) // target era un nombre libre: todo lo que haya ahí lo creamos nosotros
            throw FileOpException(describeIo(e, "No se pudo copiar «${source.name}»"))
        }
        return target
    }

    /**
     * Mueve [source] a [destDir]. Primero intenta un renombrado (instantáneo en el mismo
     * volumen); solo si falla copia y borra el original. Mover a la misma carpeta no hace nada.
     */
    fun move(source: File, destDir: File, progress: OpProgress? = null): File {
        checkTransfer(source, destDir)
        val parent = source.parentFile
        if (parent != null && canonical(parent) == canonical(destDir)) return source
        val target = uniqueTarget(destDir, source.name, source.isDirectory)
        if (source.renameTo(target)) {
            progress?.touch(source.path)
            progress?.touch(target.path)
            return target
        }

        // Otro volumen (o renombrado no permitido): copiar y luego borrar el original.
        try {
            copyTree(source, target, progress)
        } catch (e: FileOpException) {
            deleteQuietly(target)
            throw e
        } catch (e: IOException) {
            deleteQuietly(target)
            throw FileOpException(describeIo(e, "No se pudo mover «${source.name}»"))
        }
        try {
            delete(source, progress)
        } catch (e: FileOpException) {
            if (e.message == OpProgress.CANCELLED_MESSAGE) throw e
            throw FileOpException("Se copió «${source.name}» pero no se pudo borrar el original")
        }
        return target
    }

    /** Traduce los errores de disco más comunes a algo que el usuario entienda. */
    private fun describeIo(e: IOException, fallback: String): String {
        val text = e.message ?: ""
        return when {
            text.contains("ENOSPC", ignoreCase = true) || text.contains("No space", ignoreCase = true) ->
                "No hay espacio suficiente en el destino"
            text.contains("EACCES", ignoreCase = true) || text.contains("Permission denied", ignoreCase = true) ->
                "Sin permiso para escribir ahí (¿carpeta protegida o de solo lectura?)"
            else -> fallback
        }
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
    private fun copyTree(source: File, target: File, progress: OpProgress?) {
        val src = source.toPath()
        val dst = target.toPath()
        val buffer = ByteArray(COPY_BUFFER_SIZE) // un solo búfer para todo el árbol
        Files.walkFileTree(src, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                progress?.check()
                Files.createDirectory(dst.resolve(src.relativize(dir).toString()))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile) {
                    copyFile(file, dst.resolve(src.relativize(file).toString()), attrs.lastModifiedTime(), buffer, progress)
                }
                return FileVisitResult.CONTINUE
            }
        })
    }

    /** Copia un archivo por bloques (permite mostrar progreso y cancelar) y conserva su fecha de modificación. */
    private fun copyFile(from: Path, to: Path, modified: FileTime, buffer: ByteArray, progress: OpProgress?) {
        Files.newInputStream(from).use { input ->
            Files.newOutputStream(to, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                while (true) {
                    progress?.check()
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    progress?.addBytes(n.toLong())
                }
            }
        }
        try {
            Files.setLastModifiedTime(to, modified)
        } catch (e: IOException) {
            // algunos sistemas de archivos (p. ej. tarjetas SD) no permiten fijar la fecha: no es un error
        }
        progress?.touch(to.toString())
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
        isActive: () -> Boolean = { true },
        showHidden: Boolean = true,
        filter: SearchFilter = SearchFilter(),
        reverse: Boolean = false
    ): SearchResult {
        val needle = query.trim()
        val results = ArrayList<FileItem>()
        var truncated = false
        val now = System.currentTimeMillis()
        walk(root, MAX_SEARCH_DEPTH, isActive, skipHidden = !showHidden) { f ->
            // Con el texto vacío coincide todo: así se puede buscar solo por tipo, fecha o tamaño.
            if (f.name.contains(needle, ignoreCase = true)) {
                val item = FileItem.of(f)
                if (filter.matches(item, now)) {
                    if (results.size >= MAX_SEARCH_RESULTS) {
                        truncated = true
                        return@walk false
                    }
                    results.add(item)
                }
            }
            true
        }
        return SearchResult(sortItems(results, sortMode, reverse), truncated)
    }

    /**
     * Escanea un set fijo de carpetas conocidas (no todo el almacenamiento) y filtra por
     * extensión (null = todas).
     */
    fun scanCategory(
        dirs: List<File>,
        extensions: Set<String>?,
        maxDepth: Int = 4,
        isActive: () -> Boolean = { true },
        showHidden: Boolean = true
    ): List<FileItem> {
        val results = ArrayList<FileItem>()
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            walk(dir, maxDepth, isActive, skipHidden = !showHidden) { f ->
                // Primero la comprobación barata (extensión) y solo después el acceso al sistema; y UNA
                // sola llamada (stat) en vez de dos (isFile + FileItem.of) por cada coincidencia.
                if (extensions == null || f.extension.lowercase() in extensions) {
                    FileItem.regularFileOrNull(f)?.let { results.add(it) }
                }
                true
            }
        }
        return results.sortedByDescending { it.lastModified }
    }

    // ---------- Tamaño de carpetas ----------

    /**
     * Cuenta archivos, carpetas y bytes de [root] (recursivo, sin seguir enlaces). Para archivos sueltos: 1 archivo.
     * Usa walkFileTree: el sistema entrega los atributos de cada entrada ya leídos, así que hay UNA consulta
     * (lstat) por entrada en vez de dos. Se usa antes de copiar/comprimir (para el porcentaje de progreso) y
     * en Detalles, de modo que la operación empieza antes. Cancelable: devuelve lo contado hasta ese momento.
     */
    fun measure(root: File, isActive: () -> Boolean = { true }): TreeStats {
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) {
            return TreeStats(files = 1, folders = 0, bytes = root.length())
        }
        var files = 0
        var folders = 0
        var bytes = 0L
        val rootPath = root.toPath()
        var atRoot = true
        try {
            Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!isActive()) return FileVisitResult.TERMINATE
                    if (atRoot) atRoot = false else folders++
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!isActive()) return FileVisitResult.TERMINATE
                    files++
                    if (attrs.isRegularFile) bytes += attrs.size()
                    return FileVisitResult.CONTINUE
                }

                // Carpeta que no se puede abrir: cuenta como carpeta (como antes) pero no se entra en ella.
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    if (file != rootPath) {
                        val isDir = try {
                            Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isDirectory
                        } catch (e: IOException) {
                            false
                        }
                        if (isDir) folders++
                    }
                    return if (isActive()) FileVisitResult.CONTINUE else FileVisitResult.TERMINATE
                }
            })
        } catch (e: IOException) {
            // se devuelve lo contado hasta aquí
        }
        return TreeStats(files, folders, bytes)
    }

    // ---------- ZIP (java.util.zip, sin librerías) ----------

    /** Suma de bytes de varios elementos (para calcular el porcentaje de progreso). */
    fun totalBytes(files: List<File>, isActive: () -> Boolean = { true }): Long =
        files.sumOf { measure(it, isActive).bytes }

    /** Tamaño total sin comprimir de un ZIP (0 si no se puede leer). */
    fun zipUncompressedSize(zipFile: File): Long = try {
        ZipFile(zipFile).use { zf ->
            var total = 0L
            val entries = zf.entries()
            while (entries.hasMoreElements()) total += entries.nextElement().size.coerceAtLeast(0L)
            total
        }
    } catch (e: IOException) {
        0L
    }

    /** Comprime [sources] en un .zip dentro de [destDir]. Devuelve el ZIP creado. */
    fun zip(sources: List<File>, destDir: File, progress: OpProgress? = null): File {
        if (sources.isEmpty()) throw FileOpException("No hay nada que comprimir")
        if (!destDir.isDirectory) throw FileOpException("La carpeta de destino no existe")
        if (sources.map { it.name }.toSet().size != sources.size) {
            throw FileOpException("Hay dos elementos con el mismo nombre; comprímelos por separado")
        }
        for (s in sources) if (!existsNoFollow(s)) throw FileOpException("«${s.name}» ya no existe")

        val first = sources.first()
        val dot = first.name.lastIndexOf('.')
        val base = when {
            sources.size > 1 -> "Archivo"
            first.isFile && dot > 0 -> first.name.substring(0, dot)
            else -> first.name
        }
        val target = uniqueTarget(destDir, "$base.zip", false)
        val targetPath = target.toPath()
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { zos ->
                for (src in sources) {
                    val srcPath = src.toPath()
                    val parent = srcPath.parent ?: srcPath
                    Files.walkFileTree(srcPath, object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                            progress?.check()
                            val entry = ZipEntry(entryName(parent, dir) + "/")
                            entry.time = attrs.lastModifiedTime().toMillis()
                            zos.putNextEntry(entry)
                            zos.closeEntry()
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                            if (!attrs.isRegularFile || file == targetPath) return FileVisitResult.CONTINUE
                            val entry = ZipEntry(entryName(parent, file))
                            entry.time = attrs.lastModifiedTime().toMillis()
                            zos.putNextEntry(entry)
                            Files.newInputStream(file).use { input ->
                                while (true) {
                                    progress?.check()
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    zos.write(buffer, 0, n)
                                    progress?.addBytes(n.toLong())
                                }
                            }
                            zos.closeEntry()
                            return FileVisitResult.CONTINUE
                        }
                    })
                }
            }
        } catch (e: FileOpException) {
            deleteQuietly(target) // cancelado: no queda un ZIP incompleto
            throw e
        } catch (e: IOException) {
            deleteQuietly(target)
            throw FileOpException(describeIo(e, "No se pudo crear el ZIP"))
        }
        progress?.touch(target.path)
        return target
    }

    private fun entryName(parent: Path, path: Path): String =
        parent.relativize(path).joinToString("/") { it.toString() }

    /**
     * Extrae [zipFile] en una carpeta nueva junto a él (con el nombre del ZIP). Rechaza rutas
     * que intenten salir de esa carpeta y se detiene si no hay espacio suficiente.
     */
    fun unzip(zipFile: File, progress: OpProgress? = null): File {
        if (!zipFile.isFile) throw FileOpException("«${zipFile.name}» no es un archivo")
        val destParent = zipFile.parentFile ?: throw FileOpException("No se puede extraer aquí")
        val rawBase = if (zipFile.name.endsWith(".zip", ignoreCase = true)) zipFile.name.dropLast(4) else zipFile.name
        val target = uniqueTarget(destParent, rawBase.ifBlank { "extraido" }, true)
        try {
            ZipFile(zipFile).use { zf ->
                if (zf.size() > MAX_ZIP_ENTRIES) throw FileOpException("El ZIP tiene demasiados archivos")
                if (!target.mkdir()) throw FileOpException("No se pudo crear la carpeta de destino")
                val rootPath = target.canonicalPath
                var budget = target.usableSpace
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    progress?.check()
                    val entry = entries.nextElement()
                    val out = File(target, entry.name)
                    val outPath = out.canonicalPath
                    if (outPath != rootPath && !outPath.startsWith(rootPath + File.separator)) {
                        throw FileOpException("El ZIP contiene rutas no seguras y no se extrajo")
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                        continue
                    }
                    out.parentFile?.mkdirs()
                    zf.getInputStream(entry).use { input ->
                        BufferedOutputStream(FileOutputStream(out)).use { output ->
                            while (true) {
                                progress?.check()
                                val n = input.read(buffer)
                                if (n < 0) break
                                budget -= n
                                if (budget < 0) throw FileOpException("No hay espacio suficiente para extraer")
                                output.write(buffer, 0, n)
                                progress?.addBytes(n.toLong())
                            }
                        }
                    }
                    if (entry.time > 0) out.setLastModified(entry.time)
                    progress?.touch(out.path)
                }
            }
        } catch (e: FileOpException) {
            deleteQuietly(target)
            throw e
        } catch (e: IOException) {
            deleteQuietly(target)
            throw FileOpException(describeIo(e, "No se pudo extraer el ZIP (¿está dañado?)"))
        }
        return target
    }

    // ---------- Utilidades ----------

    /**
     * Recorre hasta [maxDepth] niveles sin seguir enlaces simbólicos. [visit] devuelve false para parar.
     * Para saber si cada entrada es una carpeta (y no un enlace) hace una sola consulta al sistema
     * en vez de dos, que en búsquedas por miles de carpetas es lo que más tiempo consume.
     */
    private fun walk(
        root: File,
        maxDepth: Int,
        isActive: () -> Boolean,
        skipHidden: Boolean = false,
        visit: (File) -> Boolean
    ) {
        val stack = java.util.ArrayDeque<Pair<File, Int>>()
        stack.push(root to 0)
        while (stack.isNotEmpty()) {
            if (!isActive()) return
            val (dir, depth) = stack.pop()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (!isActive()) return
                if (skipHidden && child.name.startsWith(".")) continue
                if (!visit(child)) return
                if (depth + 1 < maxDepth && isRealDirectory(child)) stack.push(child to depth + 1)
            }
        }
    }

    /** ¿Es una carpeta de verdad (no un enlace simbólico)? Una sola llamada al sistema (lstat). */
    private fun isRealDirectory(f: File): Boolean = try {
        val attrs = Files.readAttributes(f.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        attrs.isDirectory && !attrs.isSymbolicLink
    } catch (e: IOException) {
        false
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
