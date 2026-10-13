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
}
