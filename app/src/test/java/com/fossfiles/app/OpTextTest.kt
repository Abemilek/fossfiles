package com.fossfiles.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** El texto de los errores depende del idioma; la lógica solo depende de los códigos. JVM pura. */
class OpTextTest {

    private val original = OpText.renderer

    @After
    fun restore() { OpText.renderer = original }

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("fossfiles-i18n").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }

    private fun codeOf(block: () -> Unit): FileOpException {
        try {
            block()
        } catch (e: FileOpException) {
            return e
        }
        fail("Se esperaba un FileOpException")
        throw IllegalStateException()
    }

    @Test
    fun loserroresLlevanCodigoYArgumentos() = withTempDir { root ->
        assertEquals(OpMsg.NAME_EMPTY, codeOf { FileRepository.validateName("  ") }.code)
        assertEquals(OpMsg.NAME_HAS_SLASH, codeOf { FileRepository.validateName("a/b") }.code)
        File(root, "dup").mkdir()
        val e = codeOf { FileRepository.createFolder(root, "dup") }
        assertEquals(OpMsg.ALREADY_EXISTS, e.code)
        assertEquals(listOf<Any>("dup"), e.args)
        assertEquals(OpMsg.GONE, codeOf { FileRepository.delete(File(root, "nada")) }.code)
    }

    @Test
    fun elMensajeSeResuelveConElRenderizadorActivo() {
        OpText.renderer = { code, args -> "EN:${code.name}:${args.joinToString("|")}" }
        assertEquals("EN:GONE:foo.txt", FileOpException(OpMsg.GONE, "foo.txt").message)
        OpText.renderer = { code, args -> "ES:${code.name}:${args.joinToString("|")}" }
        assertEquals("ES:GONE:foo.txt", FileOpException(OpMsg.GONE, "foo.txt").message)
        // Sin argumentos y con varios
        assertEquals("ES:CANCELLED:", FileOpException(OpMsg.CANCELLED).message)
        assertEquals("ES:NO_FREE_NAME:a|b", FileOpException(OpMsg.NO_FREE_NAME, "a", "b").message)
    }

    @Test
    fun laCancelacionSeDetectaPorCodigo_noPorTexto() {
        OpText.renderer = { _, _ -> "texto traducido cualquiera" }
        val p = OpProgress()
        p.cancelled = true
        val e = codeOf { p.check() }
        assertEquals(OpMsg.CANCELLED, e.code)
        assertEquals("texto traducido cualquiera", e.message)
    }

    @Test
    fun losNombresPorDefectoSiguenAlIdioma() = withTempDir { root ->
        val a = File(root, "a.txt").also { it.writeText("1") }
        val b = File(root, "b.txt").also { it.writeText("2") }
        OpText.renderer = { code, _ -> if (code == OpMsg.DEFAULT_ARCHIVE_NAME) "Archive" else code.name }
        assertEquals("Archive.zip", FileRepository.zip(listOf(a, b), root).name)
        OpText.renderer = { code, _ -> if (code == OpMsg.DEFAULT_ARCHIVE_NAME) "Archivo" else code.name }
        assertEquals("Archivo.zip", FileRepository.zip(listOf(a, b), root).name)
    }

    @Test
    fun todosLosCodigosSeRenderizanSinFallar_conArgumentosDeEjemplo() {
        for (code in OpMsg.values()) {
            val text = OpText.render(code, listOf("x", 3))
            assertTrue(code.name, text.isNotEmpty())
        }
    }
}
