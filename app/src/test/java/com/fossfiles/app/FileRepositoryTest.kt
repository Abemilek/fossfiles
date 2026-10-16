package com.fossfiles.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Pruebas de JVM pura (sin Android): `./gradlew testDebugUnitTest`. */
class FileRepositoryTest {

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("fossfiles-test").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }

    private fun File.file(name: String, text: String = "x"): File =
        File(this, name).also { it.parentFile?.mkdirs(); it.writeText(text) }

    private fun File.dir(name: String): File = File(this, name).also { it.mkdirs() }

    private fun expectError(block: () -> Unit) {
        try {
            block()
            fail("Se esperaba un FileOpException")
        } catch (e: FileOpException) {
            // ok
        }
    }

    @Test
    fun validateName_rechazaNombresInvalidos() {
        listOf("", "   ", ".", "..", "a/b", "../x").forEach { bad ->
            expectError { FileRepository.validateName(bad) }
        }
        assertEquals("ok", FileRepository.validateName("  ok  "))
    }

    @Test
    fun createFolder_yRename_validanNombreYConflictos() = withTempDir { root ->
        FileRepository.createFolder(root, "docs")
        assertTrue(File(root, "docs").isDirectory)
        expectError { FileRepository.createFolder(root, "docs") }
        expectError { FileRepository.createFolder(root, "../escape") }

        val a = root.file("a.txt")
        root.file("b.txt")
        expectError { FileRepository.rename(a, "b.txt") }
        expectError { FileRepository.rename(a, "sub/c.txt") }
        val renamed = FileRepository.rename(a, "c.txt")
        assertTrue(renamed.exists())
        assertFalse(a.exists())
    }

    @Test
    fun copy_enLaMismaCarpeta_creaNombreNuevoEnVezDeFallarEnSilencio() = withTempDir { root ->
        val f = root.file("x.txt", "hola")
        val c1 = FileRepository.copy(f, root)
        val c2 = FileRepository.copy(f, root)
        assertEquals("x (1).txt", c1.name)
        assertEquals("x (2).txt", c2.name)
        assertEquals("hola", c1.readText())

        val d = root.dir("carpeta")
        root.file("carpeta/in.txt")
        assertEquals("carpeta (1)", FileRepository.copy(d, root).name)
        assertTrue(File(root, "carpeta (1)/in.txt").exists())
    }

    @Test
    fun copyYMove_deCarpetaDentroDeSiMisma_fallanSinDejarBasura() = withTempDir { root ->
        val d = root.dir("a")
        val inner = root.dir("a/b")
        root.file("a/f.txt")
        expectError { FileRepository.copy(d, d) }
        expectError { FileRepository.copy(d, inner) }
        expectError { FileRepository.move(d, inner) }
        assertTrue(File(root, "a/f.txt").exists())
        assertEquals(listOf("b", "f.txt"), d.list()!!.sorted()) // nada nuevo dentro de "a"
        assertEquals(listOf<String>(), inner.list()!!.toList())
    }

    @Test
    fun move_mismaCarpeta_noHaceNada() = withTempDir { root ->
        val f = root.file("x.txt")
        assertEquals(f, FileRepository.move(f, root))
        assertEquals(listOf("x.txt"), root.list()!!.toList())
    }

    @Test
    fun move_aOtraCarpeta_muevePeroNoPisaConflictos() = withTempDir { root ->
        val src = root.dir("src")
        val dst = root.dir("dst")
        val f = src.file("x.txt", "nuevo")
        dst.file("x.txt", "viejo")
        val moved = FileRepository.move(f, dst)
        assertEquals("x (1).txt", moved.name)
        assertFalse(f.exists())
        assertEquals("viejo", File(dst, "x.txt").readText())
        assertEquals("nuevo", moved.readText())
    }

    @Test
    fun delete_noSigueEnlacesSimbolicos() = withTempDir { root ->
        val outside = root.dir("outside")
        val keep = outside.file("keep.txt")
        val victim = root.dir("victim")
        victim.file("inner.txt")
        Files.createSymbolicLink(File(victim, "link").toPath(), outside.toPath())
        FileRepository.delete(victim)
        assertFalse(victim.exists())
        assertTrue("el destino del enlace debe seguir intacto", keep.exists())
        expectError { FileRepository.delete(victim) } // ya no existe
    }

    @Test
    fun search_respetaProfundidadYCancelacion() = withTempDir { root ->
        root.file("a/b/c/d/e/needle5.txt")      // dentro de "e" (nivel 6): se encuentra
        root.file("a/b/c/d/e/f/needle7.txt")    // nivel 7: fuera de alcance
        root.file("top-needle.txt")
        val found = FileRepository.search(root, "NEEDLE").items.map { it.name }.sorted()
        assertEquals(listOf("needle5.txt", "top-needle.txt"), found)

        val cancelled = FileRepository.search(root, "needle", isActive = { false })
        assertTrue(cancelled.items.isEmpty())
    }

    @Test
    fun search_noSigueEnlacesSimbolicosYNoSeCicla() = withTempDir { root ->
        val d = root.dir("d")
        d.file("needle.txt")
        Files.createSymbolicLink(File(d, "loop").toPath(), root.toPath())
        val found = FileRepository.search(root, "needle").items
        assertEquals(1, found.size)
    }

    @Test
    fun listDirectory_carpetasPrimeroYOrdenes() = withTempDir { root ->
        root.dir("Zeta")
        root.file("b.txt", "1")
        root.file("a.txt", "123456")
        assertEquals(listOf("Zeta", "a.txt", "b.txt"), FileRepository.listDirectory(root, SortMode.NAME).map { it.name })
        assertEquals(listOf("Zeta", "a.txt", "b.txt"), FileRepository.listDirectory(root, SortMode.SIZE).map { it.name })
        expectError { FileRepository.listDirectory(File(root, "no-existe")) }
    }

    @Test
    fun scanCategory_respetaMaxDepthYExtensiones() = withTempDir { root ->
        root.file("uno.pdf")
        root.file("sub/dos.pdf")
        root.file("nota.xyz")
        val depth1 = FileRepository.scanCategory(listOf(root), setOf("pdf"), maxDepth = 1).map { it.name }
        assertEquals(listOf("uno.pdf"), depth1)
        val depth2 = FileRepository.scanCategory(listOf(root), setOf("pdf"), maxDepth = 2).map { it.name }.sorted()
        assertEquals(listOf("dos.pdf", "uno.pdf"), depth2)
    }

    @Test
    fun formatSize_yKind() {
        assertEquals("0 B", formatSize(0))
        assertEquals("1.0 KB".replace('.', ','), formatSize(1024).replace('.', ','))
        assertEquals(FileKind.IMAGE, FileItem(File("x.JPG"), isDirectory = false, sizeBytes = 0, lastModified = 0).kind())
    }

    @Test
    fun ocultos_seFiltranEnListadoYBusqueda() = withTempDir { root ->
        root.file(".secreto.txt")
        root.file("visible.txt")
        root.file(".oculta/needle.txt")
        root.file("normal/needle.txt")
        assertEquals(listOf("visible.txt"), FileRepository.listDirectory(root, showHidden = false).map { it.name }.filter { it.endsWith(".txt") })
        assertEquals(4, FileRepository.listDirectory(root, showHidden = true).size)
        assertEquals(1, FileRepository.search(root, "needle", showHidden = false).items.size)
        assertEquals(2, FileRepository.search(root, "needle", showHidden = true).items.size)
    }

    @Test
    fun measure_cuentaArchivosCarpetasYBytes() = withTempDir { root ->
        root.file("a/uno.txt", "12345")
        root.file("a/b/dos.txt", "123")
        val stats = FileRepository.measure(root)
        assertEquals(2, stats.files)
        assertEquals(2, stats.folders)
        assertEquals(8L, stats.bytes)
        assertEquals(1, FileRepository.measure(File(root, "a/uno.txt")).files)
    }

    @Test
    fun zip_yUnzip_ciclocompleto() = withTempDir { root ->
        val d = root.dir("proyecto")
        root.file("proyecto/leeme.txt", "hola")
        root.file("proyecto/sub/datos.bin", "0123456789")
        val zip = FileRepository.zip(listOf(d), root)
        assertEquals("proyecto.zip", zip.name)
        assertTrue(zip.length() > 0)

        val out = FileRepository.unzip(zip)
        assertEquals("proyecto (1)", out.name) // "proyecto" ya existe: no se pisa
        assertEquals("hola", File(out, "proyecto/leeme.txt").readText())
        assertEquals("0123456789", File(out, "proyecto/sub/datos.bin").readText())
        assertEquals("proyecto (2)", FileRepository.unzip(zip).name)
    }

    @Test
    fun zip_variosArchivos_yNombreRepetido() = withTempDir { root ->
        val a = root.file("a.txt")
        val b = root.file("b.txt")
        assertEquals("Archivo.zip", FileRepository.zip(listOf(a, b), root).name)
        assertEquals("a.zip", FileRepository.zip(listOf(a), root).name)
        val other = root.dir("otra").file("a.txt")
        expectError { FileRepository.zip(listOf(a, other), root) }
        expectError { FileRepository.zip(emptyList(), root) }
    }

    @Test
    fun unzip_rechazaRutasQueEscapan_yNoDejaBasura() = withTempDir { root ->
        val evil = File(root, "evil.zip")
        ZipOutputStream(evil.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("ok.txt")); z.write("ok".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("../escapado.txt")); z.write("mal".toByteArray()); z.closeEntry()
        }
        expectError { FileRepository.unzip(evil) }
        assertFalse(File(root, "escapado.txt").exists())
        assertFalse(File(root, "evil").exists())
    }

    @Test
    fun filtros_porTipoFechaYTamano() = withTempDir { root ->
        val day = 24L * 60 * 60 * 1000
        val now = System.currentTimeMillis()
        root.file("sub/foto.jpg", "x".repeat(2 * 1024 * 1024)).also { it.setLastModified(now - 2 * day) }
        root.file("video.mp4", "x".repeat(10)).also { it.setLastModified(now - 40 * day) }
        root.file("nota.txt", "hola").also { it.setLastModified(now) }
        root.dir("carpeta")

        fun names(f: SearchFilter) = FileRepository.search(root, "", filter = f).items.map { it.name }.sorted()

        assertEquals(listOf("foto.jpg"), names(SearchFilter(type = TypeFilter.IMAGES)))
        assertEquals(listOf("carpeta", "sub"), names(SearchFilter(type = TypeFilter.FOLDERS)))
        assertEquals(listOf("foto.jpg"), names(SearchFilter(size = SizeFilter.OVER_1MB)))          // carpetas no cuentan
        assertEquals(listOf("carpeta", "foto.jpg", "nota.txt", "sub"), names(SearchFilter(date = DateFilter.WEEK))) // carpetas creadas hoy
        assertEquals(listOf("foto.jpg"), names(SearchFilter(type = TypeFilter.IMAGES, date = DateFilter.WEEK, size = SizeFilter.OVER_1MB)))
        assertEquals(listOf<String>(), names(SearchFilter(type = TypeFilter.VIDEOS, date = DateFilter.WEEK)))
        assertEquals(listOf("video.mp4"), names(SearchFilter(type = TypeFilter.VIDEOS)))
        // texto + filtro a la vez
        assertEquals(listOf("nota.txt"), FileRepository.search(root, "not", filter = SearchFilter(type = TypeFilter.DOCUMENTS)).items.map { it.name })
        assertFalse(SearchFilter().isActive)
        assertTrue(SearchFilter(size = SizeFilter.OVER_1GB).isActive)
    }

    @Test
    fun ordenNatural_sinMayusculas_yCarpetasPrimero() = withTempDir { root ->
        listOf("foto10.jpg", "foto2.jpg", "Foto1.jpg", "arbol2.txt", "arbol.txt", "zeta.txt").forEach { root.file(it) }
        root.dir("Zona")
        val names = FileRepository.listDirectory(root, SortMode.NAME).map { it.name }
        assertEquals(listOf("Zona", "arbol.txt", "arbol2.txt", "Foto1.jpg", "foto2.jpg", "foto10.jpg", "zeta.txt"), names)
        val reversed = FileRepository.listDirectory(root, SortMode.NAME, reverse = true).map { it.name }
        assertEquals("Zona", reversed.first())               // las carpetas siguen primero
        assertEquals(1, reversed.indexOf("zeta.txt"))
    }

    @Test
    fun claveDeOrden_ignoraAcentosYMayusculas() {
        // Se prueba sobre el texto (no sobre archivos en disco) para no depender de la configuración regional.
        assertEquals("arbol", FileRepository.sortKey("Árbol"))
        assertEquals("canon", FileRepository.sortKey("CAÑÓN".replace("Ñ", "N")))
        assertEquals("pingüino".replace("ü", "u"), FileRepository.sortKey("Pingüino"))
        assertEquals("foto", FileRepository.sortKey("FOTO"))
        assertTrue(FileRepository.naturalCompare(FileRepository.sortKey("árbol"), FileRepository.sortKey("Arbol2")) < 0)
    }

    @Test
    fun naturalCompare_ceros_ynumerosLargos() {
        assertTrue(FileRepository.naturalCompare("a2", "a10") < 0)
        assertTrue(FileRepository.naturalCompare("a02", "a3") < 0)
        assertEquals(0, FileRepository.naturalCompare("abc", "abc"))
        assertTrue(FileRepository.naturalCompare("a", "ab") < 0)
        assertTrue(FileRepository.naturalCompare("1", "01x") < 0)
        assertTrue(FileRepository.naturalCompare("x99999999999999999999", "x100000000000000000000") < 0)
    }

    @Test
    fun fileItemOf_coincideConElConstructorYCacheaTipo() = withTempDir { root ->
        val f = root.file("a.APK", "12345")
        val d = root.dir("carpeta")
        val a = FileItem.of(f)
        assertEquals(5L, a.sizeBytes)
        assertEquals(f.lastModified(), a.lastModified)
        assertEquals(FileKind.APK, a.kind())
        assertEquals(f.absolutePath, a.path)
        assertTrue(FileItem.of(d).isDirectory)
        assertEquals(FileKind.ARCHIVE, FileItem.of(root.file("x.zip")).kind())
        // enlace roto: no revienta, se comporta como el constructor normal
        val broken = File(root, "roto")
        Files.createSymbolicLink(broken.toPath(), File(root, "no-existe").toPath())
        assertEquals("roto", FileItem.of(broken).name)
    }

    @Test
    fun copiar_conservaFecha_cuentaBytes_yCancelaSinDejarBasura() = withTempDir { root ->
        val src = root.file("grande.bin", "x".repeat(600_000)).also { it.setLastModified(1_600_000_000_000L) }
        val dest = root.dir("destino")

        val ok = OpProgress()
        val copy = FileRepository.copy(src, dest, ok)
        assertEquals(1_600_000_000_000L, copy.lastModified())
        assertEquals(600_000L, ok.bytesDone)
        assertTrue(ok.touched().contains(copy.path))

        val cancelled = OpProgress().also { it.cancelled = true }
        try {
            FileRepository.copy(src, dest, cancelled)
            fail("Se esperaba cancelación")
        } catch (e: FileOpException) {
            assertEquals(OpProgress.CANCELLED_MESSAGE, e.message)
        }
        assertEquals(listOf("grande.bin"), dest.list()!!.toList())   // la copia cancelada no dejó nada
    }

    @Test
    fun zipYUnzip_cancelados_noDejanRestos() = withTempDir { root ->
        val carpeta = root.dir("datos")
        root.file("datos/a.txt", "hola")
        val cancel = OpProgress().also { it.cancelled = true }
        try { FileRepository.zip(listOf(carpeta), root, cancel); fail("cancelación") } catch (e: FileOpException) { }
        assertFalse(File(root, "datos.zip").exists())

        val zip = FileRepository.zip(listOf(carpeta), root)
        val progress = OpProgress()
        val out = FileRepository.unzip(zip, progress)
        assertTrue(progress.bytesDone >= 4L)
        assertEquals(FileRepository.zipUncompressedSize(zip), 4L)
        FileRepository.delete(out)
        try { FileRepository.unzip(zip, cancel); fail("cancelación") } catch (e: FileOpException) { }
        assertFalse(File(root, "datos (1)").exists())
    }

    @Test
    fun borrar_anotaRutasTocadas_yAvisaSiSeCancela() = withTempDir { root ->
        root.file("d/a.txt")
        root.file("d/sub/b.txt")
        val p = OpProgress()
        FileRepository.delete(File(root, "d"), p)
        assertFalse(File(root, "d").exists())
        assertEquals(2, p.touched().size)
    }

    @Test
    fun carpetaProtegida_dajaUnMensajeClaro() = withTempDir { root ->
        try {
            FileRepository.listDirectory(File(root, "Android/data/com.otra.app"))
            fail("Se esperaba un error")
        } catch (e: FileOpException) {
            assertTrue(e.message!!.contains("Android protege"))
        }
        try {
            FileRepository.listDirectory(File(root, "otra-carpeta-inexistente"))
            fail("Se esperaba un error")
        } catch (e: FileOpException) {
            assertEquals("No se puede leer esta carpeta", e.message)
        }
    }

    @Test
    fun volumenes_rutaRaizYPertenencia() {
        assertEquals(File("/storage/ABCD-1234"), VolumePaths.rootFromAppDir(File("/storage/ABCD-1234/Android/data/com.fossfiles.app/files")))
        assertEquals(null, VolumePaths.rootFromAppDir(File("/data/user/0/com.fossfiles.app/files")))
        val roots = listOf(File("/storage/emulated/0"), File("/storage/ABCD-1234"))
        assertEquals(File("/storage/ABCD-1234"), VolumePaths.volumeOf(File("/storage/ABCD-1234/DCIM/a.jpg"), roots))
        assertEquals(File("/storage/emulated/0"), VolumePaths.volumeOf(File("/storage/emulated/0"), roots))
        assertEquals(null, VolumePaths.volumeOf(File("/storage/ABCD-12345/x"), roots)) // prefijo parecido pero otro volumen
    }

    @Test
    fun contarHijos() = withTempDir { root ->
        root.file("a/1.txt"); root.file("a/2.txt")
        assertEquals(2, FileRepository.countChildren(File(root, "a")))
        assertEquals(-1, FileRepository.countChildren(File(root, "nada")))
    }
}
