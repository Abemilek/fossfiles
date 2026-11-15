package com.fossfiles.app

import android.content.res.Resources

/**
 * Texto de los errores de operaciones (ver [OpMsg]) tomado de los recursos de Android: el idioma (inglés o
 * español) lo decide el sistema. El `when` es una expresión sobre un enum: si se añade un código nuevo y
 * falta aquí, no compila.
 */
internal fun renderOpMessage(res: Resources, code: OpMsg, args: List<Any>): String {
    val a = args.toTypedArray()
    return when (code) {
        OpMsg.PROTECTED_FOLDER -> res.getString(R.string.err_protected_folder, *a)
        OpMsg.CANNOT_READ_FOLDER -> res.getString(R.string.err_cannot_read_folder, *a)
        OpMsg.NAME_EMPTY -> res.getString(R.string.err_name_empty, *a)
        OpMsg.NAME_INVALID -> res.getString(R.string.err_name_invalid, *a)
        OpMsg.NAME_HAS_SLASH -> res.getString(R.string.err_name_slash, *a)
        OpMsg.NAME_TOO_LONG -> res.getString(R.string.err_name_too_long, *a)
        OpMsg.NO_FREE_NAME -> res.getString(R.string.err_no_free_name, *a)
        OpMsg.ALREADY_EXISTS -> res.getString(R.string.err_already_exists, *a)
        OpMsg.CREATE_FOLDER_FAILED -> res.getString(R.string.err_create_folder, *a)
        OpMsg.RENAME_LOCATION -> res.getString(R.string.err_rename_location, *a)
        OpMsg.RENAME_FAILED -> res.getString(R.string.err_rename, *a)
        OpMsg.GONE -> res.getString(R.string.err_gone, *a)
        OpMsg.DELETE_FAILED -> res.getString(R.string.err_delete, *a)
        OpMsg.COPY_FAILED -> res.getString(R.string.err_copy, *a)
        OpMsg.MOVE_FAILED -> res.getString(R.string.err_move, *a)
        OpMsg.COPIED_NOT_DELETED -> res.getString(R.string.err_copied_not_deleted, *a)
        OpMsg.NO_SPACE -> res.getString(R.string.err_no_space, *a)
        OpMsg.NO_PERMISSION -> res.getString(R.string.err_no_permission, *a)
        OpMsg.DEST_MISSING -> res.getString(R.string.err_dest_missing, *a)
        OpMsg.SYMLINKS -> res.getString(R.string.err_symlinks, *a)
        OpMsg.INTO_ITSELF -> res.getString(R.string.err_into_itself, *a)
        OpMsg.NOTHING_TO_ZIP -> res.getString(R.string.err_nothing_to_zip, *a)
        OpMsg.ZIP_DUP_NAMES -> res.getString(R.string.err_zip_dup_names, *a)
        OpMsg.ZIP_CREATE_FAILED -> res.getString(R.string.err_zip_create, *a)
        OpMsg.NOT_A_FILE -> res.getString(R.string.err_not_a_file, *a)
        OpMsg.EXTRACT_HERE -> res.getString(R.string.err_extract_here, *a)
        OpMsg.ZIP_TOO_MANY -> res.getString(R.string.err_zip_too_many, *a)
        OpMsg.DEST_CREATE_FAILED -> res.getString(R.string.err_dest_create, *a)
        OpMsg.ZIP_UNSAFE -> res.getString(R.string.err_zip_unsafe, *a)
        OpMsg.EXTRACT_NO_SPACE -> res.getString(R.string.err_extract_no_space, *a)
        OpMsg.EXTRACT_FAILED -> res.getString(R.string.err_extract, *a)
        OpMsg.RESOLVE_PATH_FAILED -> res.getString(R.string.err_resolve_path, *a)
        OpMsg.TRASH_ALREADY -> res.getString(R.string.err_trash_already, *a)
        OpMsg.TRASH_CREATE_FAILED -> res.getString(R.string.err_trash_create, *a)
        OpMsg.TRASH_USE_FAILED -> res.getString(R.string.err_trash_use, *a)
        OpMsg.TRASH_EMPTY_FAILED -> {
            val n = (args.firstOrNull() as? Number)?.toInt() ?: 0
            res.getQuantityString(R.plurals.err_trash_empty, n, n)
        }
        OpMsg.CANCELLED -> res.getString(R.string.op_cancelled, *a)
        OpMsg.DEFAULT_ARCHIVE_NAME -> res.getString(R.string.default_archive_name, *a)
        OpMsg.DEFAULT_EXTRACT_NAME -> res.getString(R.string.default_extract_name, *a)
    }
}
