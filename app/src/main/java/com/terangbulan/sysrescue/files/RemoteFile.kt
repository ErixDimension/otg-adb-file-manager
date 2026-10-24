package com.terangbulan.sysrescue.files

data class RemoteFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L
)

interface RemoteFileRepository {
    suspend fun list(path: String): List<RemoteFile>
}
