package com.fossfiles.app

import android.os.StatFs
import java.io.File

data class StorageInfo(val usedBytes: Long, val totalBytes: Long) {
    val usedFraction: Float
        get() = if (totalBytes == 0L) 0f else (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
}

object StorageStats {
    fun read(root: File): StorageInfo {
        val stat = StatFs(root.path)
        val total = stat.blockCountLong * stat.blockSizeLong
        val free = stat.availableBlocksLong * stat.blockSizeLong
        return StorageInfo(usedBytes = total - free, totalBytes = total)
    }
}
