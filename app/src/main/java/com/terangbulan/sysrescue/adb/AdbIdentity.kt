package com.terangbulan.sysrescue.adb

import android.content.Context
import android.util.Base64
import com.tananaev.adblib.AdbBase64
import com.tananaev.adblib.AdbCrypto
import java.io.File

/**
 * Persistent ADB host identity stored in the app-private files directory.
 * No Android Keystore, AES, or AtomicFile.
 */
class AdbIdentity(context: Context) {

    private val privateKey = File(context.filesDir, "adbkey")
    private val publicKey = File(context.filesDir, "adbkey.pub")

    private val base64 = object : AdbBase64 {
        override fun encodeToString(data: ByteArray): String =
            Base64.encodeToString(data, Base64.NO_WRAP)
    }

    @Synchronized
    @Throws(Exception::class)
    fun loadOrCreate(): AdbCrypto {
        if (privateKey.isFile && publicKey.isFile) {
            return AdbCrypto.loadAdbKeyPair(
                base64,
                privateKey,
                publicKey
            )
        }

        privateKey.delete()
        publicKey.delete()

        val crypto = AdbCrypto.generateAdbKeyPair(base64)
        crypto.saveAdbKeyPair(privateKey, publicKey)

        privateKey.setReadable(false, false)
        privateKey.setWritable(false, false)
        privateKey.setReadable(true, true)
        privateKey.setWritable(true, true)

        check(privateKey.isFile && publicKey.isFile) {
            "Gagal menyimpan pasangan kunci ADB"
        }

        return AdbCrypto.loadAdbKeyPair(
            base64,
            privateKey,
            publicKey
        )
    }
}
