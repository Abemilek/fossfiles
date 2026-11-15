package com.fossfiles.app

/**
 * Motivo de un error de una operación de archivos. La lógica (JVM pura) solo conoce estos códigos y sus
 * argumentos; el texto que ve el usuario se resuelve con [OpText], que la app configura con los recursos de
 * Android (inglés / español, según el idioma del sistema). Así el código no depende de ningún idioma y la
 * cancelación se detecta por código, no comparando texto.
 */
enum class OpMsg {
    PROTECTED_FOLDER, CANNOT_READ_FOLDER,
    NAME_EMPTY, NAME_INVALID, NAME_HAS_SLASH, NAME_TOO_LONG, NO_FREE_NAME, ALREADY_EXISTS,
    CREATE_FOLDER_FAILED, RENAME_LOCATION, RENAME_FAILED, GONE, DELETE_FAILED,
    COPY_FAILED, MOVE_FAILED, COPIED_NOT_DELETED, NO_SPACE, NO_PERMISSION,
    DEST_MISSING, SYMLINKS, INTO_ITSELF,
    NOTHING_TO_ZIP, ZIP_DUP_NAMES, ZIP_CREATE_FAILED, NOT_A_FILE, EXTRACT_HERE, ZIP_TOO_MANY,
    DEST_CREATE_FAILED, ZIP_UNSAFE, EXTRACT_NO_SPACE, EXTRACT_FAILED, RESOLVE_PATH_FAILED,
    TRASH_ALREADY, TRASH_CREATE_FAILED, TRASH_USE_FAILED, TRASH_EMPTY_FAILED,
    CANCELLED,
    // Nombres por defecto de archivos que la app crea (no son errores, pero dependen del idioma).
    DEFAULT_ARCHIVE_NAME, DEFAULT_EXTRACT_NAME
}

/** Convierte un [OpMsg] y sus argumentos en texto. La app lo reemplaza por uno basado en recursos de Android. */
object OpText {
    @Volatile
    var renderer: (OpMsg, List<Any>) -> String = { code, args ->
        if (args.isEmpty()) code.name else code.name + ": " + args.joinToString()
    }

    fun render(code: OpMsg, args: List<Any> = emptyList()): String = renderer(code, args)
}

/** Error de una operación, con el mensaje ya listo para mostrarse (en el idioma activo) y su [code] para lógica. */
class FileOpException(val code: OpMsg, val args: List<Any>) : Exception(OpText.render(code, args)) {
    constructor(code: OpMsg, vararg args: Any) : this(code, args.toList())
}
