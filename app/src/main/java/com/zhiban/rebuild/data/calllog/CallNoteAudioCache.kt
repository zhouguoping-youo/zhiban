package com.zhiban.rebuild.data.calllog

import android.content.Context
import java.io.File

internal object CallNoteAudioCache {
    private const val MAX_AGE_MS = 24L * 60 * 60_000L

    fun purgeExpired(context: Context, nowEpochMs: Long = System.currentTimeMillis()): Int =
        purgeExpiredCallNoteAudio(File(context.cacheDir, "call-notes"), nowEpochMs - MAX_AGE_MS)
}

internal fun purgeExpiredCallNoteAudio(directory: File, cutoffEpochMs: Long): Int {
    if (!directory.isDirectory) return 0
    var deleted = 0
    directory.listFiles().orEmpty().forEach { file ->
        if (file.isFile && file.lastModified() <= cutoffEpochMs && file.delete()) deleted++
    }
    return deleted
}
