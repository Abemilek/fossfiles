package com.fossfiles.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Pruebas de JVM de la papelera (`./gradlew testDebugUnitTest`), sin Android de por medio. */
class TrashBinTest {

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("fossfiles-trash-test").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }

    private fun File.file(name: String, text: String = "x"): File =
        File(this, name).also { it.parentFile?.mkdirs(); it.writeText(text) }

    private fun expectError(block: () -> Unit) {
        try { block(); fail("Se esperaba un FileOpException") } catch (e: FileOpException) { }
    }

    @Test
    fun aLaPapelera_yRestaurar_devuelveAlOrigen() = withTempDir { root ->
        val bin = TrashBin(File(root, ".trash"))
        val f = root.file("docs/informe.txt", "hola")
        bin.moveToTrash(f, now = 1000L)
        assertFalse(f.exists())

        val entries = bin.list()
        assertEquals(1, entries.size)
        assertEquals("informe.txt", entries[0].name)
        assertEquals(f.absolutePath, entries[0].originalPath)

        val restored = bin.restore(entries[0], root)
        assertEquals(f.absolutePath, restored.absolutePath)
        assertEquals("hola", restored.readText())
        assertTrue(bin.list().isEmpty())
    }

    @Test
    fun carpetaCompleta_seMueveYSeRestaura() = withTempDir { root ->
        val bin = TrashBin(File(root, ".trash"))
        root.file("proyecto/a.txt")
        root.file("proyecto/sub/b.txt")
        bin.moveToTrash(File(root, "proyecto"))
        assertFalse(File(root, "proyecto").exists())
        val e = bin.list().single()
        assertTrue(e.isDirectory)
        bin.restore(e, root)
        assertTrue(File(root, "proyecto/sub/b.txt").exists())
    }

    @Test
    fun restaurar_conConflicto_noPisa_yRecreaCarpetaOriginal() = withTempDir { root ->
        val bin = TrashBin(File(root, ".trash"))
        val f = root.file("a/b/x.txt", "viejo")
        bin.moveToTrash(f)
        // La carpeta original desapareció y además ya hay otro archivo con ese nombre en otra parte
        File(root, "a").deleteRecursively()
        val restored = bin.restore(bin.list().single(), root)
        assertEquals(f.absolutePath, restored.absolutePath) // la carpeta se recreó
        assertEquals("viejo", restored.readText())

        bin.moveToTrash(restored)
        root.file("a/b/x.txt", "nuevo")
        val again = bin.restore(bin.list().single(), root)
        assertEquals("x (1).txt", again.name)
        assertEquals("nuevo", f.readText())
    }

    @Test
    fun noSePuedeMandarALaPapeleraLoQueYaEstaAhi() = withTempDir { root ->
        val bin = TrashBin(File(root, ".trash"))
        bin.moveToTrash(root.file("x.txt"))
        val inside = bin.list().single().file
        expectError { bin.moveToTrash(inside) }
        expectError { bin.moveToTrash(File(root, "no-existe.txt")) }
    }

    @Test
    fun mismoNombreDosVeces_yOrdenDelMasRecienteAlMasAntiguo() = withTempDir { root ->
        val bin = TrashBin(File(root, ".trash"))
        bin.moveToTrash(root.file("x.txt", "uno"), now = 1000L)
        bin.moveToTrash(root.file("x.txt", "dos"), now = 2000L)
        val list = bin.list()
        assertEquals(listOf("dos", "uno"), list.map { it.file.readText() })
        assertEquals(2, list.map { it.id }.toSet().size)
    }

    @Test
    fun purge_borraSoloLoViejo_yDeleteForeverYEmpty() = withTempDir { root ->
        val bin = TrashBin(File(root, ".trash"))
        bin.moveToTrash(root.file("viejo.txt"), now = 1_000L)
        bin.moveToTrash(root.file("nuevo.txt"), now = 9_000L)
        assertEquals(1, bin.purgeOlderThan(maxAgeMs = 5_000L, now = 10_000L))
        assertEquals(listOf("nuevo.txt"), bin.list().map { it.name })

        bin.deleteForever(bin.list().single())
        assertTrue(bin.list().isEmpty())

        bin.moveToTrash(root.file("a.txt"))
        bin.moveToTrash(root.file("b.txt"))
        bin.empty()
        assertTrue(bin.list().isEmpty())
    }

    @Test
    fun enlaceSimbolico_seBorraSinTocarElDestino() = withTempDir { root ->
        val bin = TrashBin(File(root, ".trash"))
        val target = root.file("real.txt")
        val link = File(root, "enlace")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        bin.moveToTrash(link)
        assertFalse(Files.exists(link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(target.exists())
        assertTrue(bin.list().isEmpty())
    }

    @Test
    fun elementoSinInfo_sigueListadoYSeRestauraALaRaiz() = withTempDir { root ->
        val trashDir = File(root, ".trash")
        File(trashDir, "999-0").mkdirs()
        File(trashDir, "999-0/huerfano.txt").writeText("x")
        val bin = TrashBin(trashDir)
        val e = bin.list().single()
        assertEquals(null, e.originalPath)
        val dest = File(root, "destino").also { it.mkdirs() }
        assertEquals("huerfano.txt", bin.restore(e, dest).name)
        assertTrue(File(dest, "huerfano.txt").exists())
    }
}
