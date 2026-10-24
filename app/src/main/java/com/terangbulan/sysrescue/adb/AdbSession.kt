package com.terangbulan.sysrescue.adb

/**
 * Kontrak sesi ADB.
 *
 * Implementasi nyata harus menyediakan USB transport,
 * handshake, autentikasi host key, pengelolaan koneksi,
 * serta operasi shell dan file-sync.
 */
interface AdbSession {
    val isConnected: Boolean

    suspend fun connect(): Result<Unit>

    suspend fun disconnect()
}
