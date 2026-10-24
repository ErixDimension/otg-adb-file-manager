package com.terangbulan.sysrescue.adb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * USB bulk transport adapter for AdbConnection.create(Socket, AdbCrypto).
 * The caller must obtain USB permission before opening this transport.
 */
class UsbAdbSocket private constructor(
    private val usb: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val inputEndpoint: UsbEndpoint,
    private val outputEndpoint: UsbEndpoint
) : Socket() {

    @Volatile
    private var closed = false

    private val input = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            return if (count < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (offset < 0 || length < 0 || length > buffer.size - offset) {
                throw IndexOutOfBoundsException()
            }
            if (length == 0) return 0
            if (closed) return -1

            val count = usb.bulkTransfer(
                inputEndpoint,
                buffer,
                offset,
                minOf(length, MAX_TRANSFER),
                USB_READ_TIMEOUT_MS
            )

            if (closed) return -1
            if (count < 0) {
                throw IOException("USB ADB read gagal atau koneksi terputus")
            }
            if (count == 0) {
                throw IOException("USB ADB read mengembalikan nol byte")
            }

            return count
        }

        override fun close() {
            this@UsbAdbSocket.close()
        }
    }

    private val output = object : OutputStream() {
        override fun write(value: Int) {
            write(byteArrayOf(value.toByte()), 0, 1)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (offset < 0 || length < 0 || length > buffer.size - offset) {
                throw IndexOutOfBoundsException()
            }
            if (closed) throw IOException("USB ADB socket sudah ditutup")

            var position = offset
            val end = offset + length

            while (position < end) {
                if (closed) throw IOException("USB ADB socket sudah ditutup")

                val size = minOf(end - position, MAX_TRANSFER)
                val count = usb.bulkTransfer(
                    outputEndpoint,
                    buffer,
                    position,
                    size,
                    USB_WRITE_TIMEOUT_MS
                )

                if (count <= 0) {
                    throw IOException("USB ADB write gagal atau timeout")
                }
                position += count
            }
        }

        override fun close() {
            this@UsbAdbSocket.close()
        }
    }

    override fun getInputStream(): InputStream = input

    override fun getOutputStream(): OutputStream = output

    override fun setTcpNoDelay(enabled: Boolean) {
        // USB bulk transport has no TCP_NODELAY setting.
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            usb.releaseInterface(usbInterface)
        } finally {
            usb.close()
        }
    }

    companion object {
        private const val MAX_TRANSFER = 16 * 1024
        // Zero requests a blocking USB transfer on Android.
        private const val USB_READ_TIMEOUT_MS = 0
        private const val USB_WRITE_TIMEOUT_MS = 1000

        @Throws(IOException::class)
        fun open(
            device: UsbDevice,
            managerConnection: UsbDeviceConnection
        ): UsbAdbSocket {
            var claimed: UsbInterface? = null

            try {
                for (index in 0 until device.interfaceCount) {
                    val candidate = device.getInterface(index)

                    if (candidate.interfaceClass != 0xff ||
                        candidate.interfaceSubclass != 0x42 ||
                        candidate.interfaceProtocol != 0x01
                    ) {
                        continue
                    }

                    var input: UsbEndpoint? = null
                    var output: UsbEndpoint? = null

                    for (endpointIndex in 0 until candidate.endpointCount) {
                        val endpoint = candidate.getEndpoint(endpointIndex)
                        if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            continue
                        }

                        when (endpoint.direction) {
                            UsbConstants.USB_DIR_IN -> input = endpoint
                            UsbConstants.USB_DIR_OUT -> output = endpoint
                        }
                    }

                    if (input == null || output == null) continue

                    if (!managerConnection.claimInterface(candidate, true)) {
                        continue
                    }

                    claimed = candidate
                    return UsbAdbSocket(
                        managerConnection,
                        candidate,
                        input,
                        output
                    )
                }

                throw IOException("Interface USB ADB dengan endpoint bulk IN/OUT tidak ditemukan")
            } catch (error: Exception) {
                if (claimed != null) {
                    managerConnection.releaseInterface(claimed)
                }
                if (error is IOException) throw error
                throw IOException("Gagal membuka transport USB ADB", error)
            }
        }
    }
}
