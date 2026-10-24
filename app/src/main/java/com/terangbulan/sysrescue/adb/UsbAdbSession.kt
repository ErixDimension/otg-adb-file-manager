package com.terangbulan.sysrescue.adb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.tananaev.adblib.AdbConnection
import com.tananaev.adblib.AdbStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sesi ADB melalui USB Host.
 * Stream tambahan memakai koneksi ADB yang sama.
 */
class UsbAdbSession(
    context: Context,
    private val device: UsbDevice
) : AdbSession {

    private val appContext = context.applicationContext
    private val usbManager =
        appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val identity = AdbIdentity(appContext)

    @Volatile
    private var connection: AdbConnection? = null

    @Volatile
    private var connected = false

    override val isConnected: Boolean
        get() = connected

    override suspend fun connect(): Result<Unit> =
        withContext(Dispatchers.IO) {
            var openedConnection: AdbConnection? = null
            var rawSocket: UsbAdbSocket? = null

            try {
                if (connected && connection != null) {
                    return@withContext Result.success(Unit)
                }

                if (!usbManager.hasPermission(device)) {
                    throw SecurityException("Izin USB belum diberikan")
                }

                val usbConnection = usbManager.openDevice(device)
                    ?: throw IOException(
                        "Android gagal membuka perangkat USB"
                    )

                try {
                    rawSocket = UsbAdbSocket.open(device, usbConnection)
                } catch (error: Exception) {
                    usbConnection.close()
                    throw error
                }

                openedConnection = AdbConnection.create(
                    rawSocket,
                    identity.loadOrCreate()
                )

                val success = openedConnection.connect(
                    HANDSHAKE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                    false
                )

                if (!success) {
                    throw IOException(
                        "Handshake ADB timeout. Periksa otorisasi RSA."
                    )
                }

                connection = openedConnection
                connected = true
                Result.success(Unit)
            } catch (error: Exception) {
                connected = false
                connection = null

                try {
                    if (openedConnection != null) {
                        openedConnection.close()
                    } else {
                        rawSocket?.close()
                    }
                } catch (_: Exception) {
                    // Pertahankan error awal.
                }

                Result.failure(error)
            }
        }

    /**
     * Membuka service ADB pada koneksi USB yang sudah terautentikasi.
     * Pemanggil wajib menutup stream setelah selesai.
     */
    suspend fun openStream(destination: String): AdbStream =
        withContext(Dispatchers.IO) {
            require(destination.isNotBlank()) {
                "Destination ADB tidak boleh kosong"
            }

            val activeConnection = connection
                ?: throw IOException("Sesi ADB belum terhubung")

            if (!connected) {
                throw IOException("Sesi ADB sudah terputus")
            }

            activeConnection.open(destination)
        }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            connected = false
            val oldConnection = connection
            connection = null

            try {
                oldConnection?.close()
            } catch (_: Exception) {
                // Koneksi mungkin sudah terputus.
            }
        }
    }

    companion object {
        private const val HANDSHAKE_TIMEOUT_SECONDS = 60L
    }
}
