package com.terangbulan.sysrescue.transfer

import com.tananaev.adblib.AdbStream
import com.terangbulan.sysrescue.adb.UsbAdbSession
import com.terangbulan.sysrescue.files.RemoteFile
import com.terangbulan.sysrescue.files.RemoteFileRepository
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Implementasi ADB Sync LIST (protokol Sync v1).
 *
 * Belum melakukan SEND/RECV atau mengubah file pada perangkat.
 */
class AdbSyncRepository(
    private val session: UsbAdbSession
) : RemoteFileRepository {

    override suspend fun list(path: String): List<RemoteFile> =
        withContext(Dispatchers.IO) {
            require(path.isNotBlank()) { "Path remote kosong" }
            require('\u0000' !in path) { "Path mengandung karakter NUL" }

            val normalized = if (path.length > 1) {
                path.trimEnd('/')
            } else {
                path
            }

            val stream = session.openStream("sync:")
            try {
                val request = ByteArrayOutputStream()
                request.write("LIST".toByteArray(ASCII))
                writeIntLE(request, normalized.toByteArray(UTF8).size)
                request.write(normalized.toByteArray(UTF8))
                stream.write(request.toByteArray())

                readListing(stream, normalized)
            } finally {
                try {
                    stream.close()
                } catch (_: Exception) {
                    // Jangan menutupi error utama.
                }
            }
        }

    private fun readListing(
        stream: AdbStream,
        parentPath: String
    ): List<RemoteFile> {
        val input = PacketReader(stream)
        val files = mutableListOf<RemoteFile>()

        while (true) {
            val id = input.readExact(4).toString(ASCII)

            when (id) {
                "DONE" -> {
                    // Header DENT/DONE berukuran 20 byte:
                    // ID 4 byte + empat field 32-bit.
                    input.readExact(16)
                    return files.sortedWith(
                        compareBy<RemoteFile> { !it.isDirectory }
                            .thenBy { it.name.lowercase() }
                    )
                }

                "FAIL" -> {
                    val length = input.readIntLE()
                    if (length < 0 || length > MAX_ERROR_BYTES) {
                        throw IOException("Panjang pesan FAIL tidak valid")
                    }
                    val message = input.readExact(length).toString(UTF8)
                    throw IOException("ADB Sync LIST gagal: $message")
                }

                "DENT" -> {
                    val mode = input.readIntLE()
                    val size = input.readIntLE().toLong() and 0xffffffffL
                    input.readIntLE() // mtime
                    val nameLength = input.readIntLE()

                    if (nameLength < 0 || nameLength > MAX_NAME_BYTES) {
                        throw IOException("Panjang nama file tidak valid")
                    }

                    val name = input.readExact(nameLength).toString(UTF8)
                    if (name.isEmpty() || name == "." || name == "..") {
                        continue
                    }

                    val separator =
                        if (parentPath.endsWith("/")) "" else "/"

                    files += RemoteFile(
                        name = name,
                        path = if (parentPath == "/") {
                            "/$name"
                        } else {
                            "$parentPath$separator$name"
                        },
                        isDirectory = (mode and S_IFMT) == S_IFDIR,
                        sizeBytes = size
                    )
                }

                else -> throw IOException(
                    "Respons ADB Sync tidak dikenal: $id"
                )
            }
        }
    }

    private class PacketReader(private val stream: AdbStream) {
        private var current = ByteArray(0)
        private var position = 0

        fun readExact(length: Int): ByteArray {
            if (length < 0) throw IOException("Panjang data negatif")

            val output = ByteArray(length)
            var written = 0

            while (written < length) {
                if (position >= current.size) {
                    current = try {
                        stream.read()
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("Pembacaan ADB dibatalkan", e)
                    }

                    position = 0
                    if (current.isEmpty()) {
                        throw EOFException("Paket ADB kosong saat membaca respons")
                    }
                }

                val count = minOf(
                    length - written,
                    current.size - position
                )

                System.arraycopy(current, position, output, written, count)
                position += count
                written += count
            }

            return output
        }

        fun readIntLE(): Int {
            val b = readExact(4)
            return (b[0].toInt() and 0xff) or
                ((b[1].toInt() and 0xff) shl 8) or
                ((b[2].toInt() and 0xff) shl 16) or
                ((b[3].toInt() and 0xff) shl 24)
        }
    }

    companion object {
        private val ASCII: Charset = Charsets.US_ASCII
        private val UTF8: Charset = Charsets.UTF_8
        private const val S_IFMT = 0xF000
        private const val S_IFDIR = 0x4000
        private const val MAX_NAME_BYTES = 65536
        private const val MAX_ERROR_BYTES = 65536

        private fun writeIntLE(out: ByteArrayOutputStream, value: Int) {
            out.write(value and 0xff)
            out.write((value ushr 8) and 0xff)
            out.write((value ushr 16) and 0xff)
            out.write((value ushr 24) and 0xff)
        }
    }
}
