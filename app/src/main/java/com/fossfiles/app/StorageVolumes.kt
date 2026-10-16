package com.fossfiles.app

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

/** Un volumen de almacenamiento visible para el usuario: el interno o una tarjeta SD / USB. */
data class StorageVolumeInfo(val label: String, val root: File, val isPrimary: Boolean)

object StorageVolumes {

    /**
     * El almacenamiento interno primero y después las tarjetas SD o discos USB montados. Para
     * encontrar las tarjetas se usa getExternalFilesDirs (la carpeta privada de la app en cada
     * volumen) y se recorta hasta la raíz: funciona desde Android 8 sin reflexión ni permisos extra.
     */
    fun list(context: Context): List<StorageVolumeInfo> {
        val primary = Environment.getExternalStorageDirectory()
        val result = ArrayList<StorageVolumeInfo>()
        result.add(StorageVolumeInfo("Almacenamiento interno", primary, true))
        try {
            val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            for (dir in context.getExternalFilesDirs(null)) {
                if (dir == null) continue
                val root = VolumePaths.rootFromAppDir(dir) ?: continue
                if (root.path == primary.path || !root.isDirectory) continue
                if (result.any { it.root.path == root.path }) continue
                val label = manager?.getStorageVolume(root)?.getDescription(context) ?: "Tarjeta SD"
                result.add(StorageVolumeInfo(label, root, false))
            }
        } catch (e: Exception) {
            // Si el sistema no responde, al menos queda el almacenamiento interno.
        }
        return result
    }
}
