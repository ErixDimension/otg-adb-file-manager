package com.terangbulan.sysrescue

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.database.Cursor
import android.provider.DocumentsContract
import android.net.Uri
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.terangbulan.sysrescue.adb.UsbAdbSession
import com.terangbulan.sysrescue.transfer.AdbSyncRepository
import android.widget.EditText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var devices: LinearLayout
    private lateinit var console: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var fileBrowserHeading: TextView
    private lateinit var filePathInput: EditText
    private lateinit var fileList: LinearLayout
    private lateinit var fileStatus: TextView
    private lateinit var fileScroll: ScrollView
    private var listingFiles = false
    private var pendingFilePath: String? = null

    // SYSRESCUE_SAF_FOUNDATION_V1
    private val safPrefs by lazy {
        getSharedPreferences("local_storage_saf", MODE_PRIVATE)
    }
    private var localTreeUri: Uri? = null

    data class LocalDocumentEntry(
        val name: String,
        val uri: Uri,
        val isDirectory: Boolean,
        val sizeBytes: Long
    )

    private val logLines = ArrayDeque<String>()
    private var permissionPending = false
    private var adbSession: UsbAdbSession? = null
    private val activityScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var connectAdbButton: Button
    private lateinit var disconnectAdbButton: Button

    private val usbManager: UsbManager
        get() = getSystemService(Context.USB_SERVICE) as UsbManager

    companion object {
        private const val ACTION_USB_PERMISSION =
            "com.terangbulan.sysrescue.USB_PERMISSION"
        private const val MAX_LOG_LINES = 200
        private const val REQUEST_LOCAL_TREE = 4107
        private const val KEY_LOCAL_TREE_URI = "tree_uri"
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_PERMISSION) return

            permissionPending = false
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(
                    UsbManager.EXTRA_DEVICE, UsbDevice::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

            val granted = intent.getBooleanExtra(
                UsbManager.EXTRA_PERMISSION_GRANTED, false
            )

            if (device != null) {
                log(
                    if (granted) "USB permission DIIZINKAN"
                    else "USB permission DITOLAK",
                    "Device=${device.deviceName} VID=${device.vendorId} PID=${device.productId}"
                )
            } else {
                log("USB permission callback", "Device tidak dikirim Android")
            }

            scanUsb(requestAutomatically = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createUi()
        restoreSafFolderUri()

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(permissionReceiver, filter)
        }

        log("SYSrescue dimulai", "Menyiapkan USB Host dan console")
        scanUsb(requestAutomatically = true)
    }

    private fun createUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 12)
        }

        val pageScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val title = TextView(this).apply {
            text = "SYSrescue · USB / ADB"
            textSize = 23f
            typeface = Typeface.DEFAULT_BOLD
        }

        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, 12, 0, 10)
        }

        val refresh = Button(this).apply {
            text = "DETEKSI ULANG USB"
            setOnClickListener {
                log("SCAN", "Pemindaian USB manual")
                scanUsb(requestAutomatically = true)
            }
        }

        connectAdbButton = Button(this).apply {
            text = "SAMBUNG ADB"
            setOnClickListener { connectAdb() }
        }

        disconnectAdbButton = Button(this).apply {
            text = "PUTUSKAN ADB"
            isEnabled = false
            setOnClickListener { disconnectAdb() }
        }

        val heading = TextView(this).apply {
            text = "Perangkat USB"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 8, 0, 4)
        }

        devices = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val deviceScroll = ScrollView(this).apply {
            addView(devices)
        }

        fileBrowserHeading = TextView(this).apply {
            text = "Browser File ADB"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 10, 0, 4)
        }

        filePathInput = EditText(this).apply {
            setSingleLine(true)
            hint = "Path perangkat, contoh /sdcard"
            setText("/sdcard")
            textSize = 14f
        }

        fileStatus = TextView(this).apply {
            text = "Hubungkan ADB untuk memuat daftar file."
            textSize = 12f
            setPadding(0, 2, 0, 4)
        }

        fileList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fileScroll = ScrollView(this).apply {
            setBackgroundColor(0xFFF1F3F5.toInt())
            addView(fileList)
        }

        val parentButton = Button(this).apply {
            text = "NAIK FOLDER"
            setOnClickListener {
                val current = filePathInput.text.toString().trim()
                val clean = current.trimEnd('/').ifBlank { "/" }
                val parent = if (clean == "/" || !clean.contains("/")) {
                    "/"
                } else {
                    clean.substringBeforeLast('/').ifBlank { "/" }
                }
                filePathInput.setText(parent)
                loadRemoteFiles()
            }
        }

        val reloadButton = Button(this).apply {
            text = "MUAT ULANG"
            setOnClickListener { loadRemoteFiles() }
        }

        val openPathButton = Button(this).apply {
            text = "BUKA PATH"
            setOnClickListener { loadRemoteFiles() }
        }

        val browserActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(parentButton, LinearLayout.LayoutParams(0, -2, 1f))
            addView(reloadButton, LinearLayout.LayoutParams(0, -2, 1f))
            addView(openPathButton, LinearLayout.LayoutParams(0, -2, 1f))
        }

        // SYSRESCUE_SAF_UI_V1
        val chooseLocalButton = Button(this).apply {
            text = "DEVICE A — PILIH FOLDER LOKAL"
            setOnClickListener { chooseLocalFolder() }
        }

        val showLocalButton = Button(this).apply {
            text = "TAMPILKAN ISI LOKAL"
            setOnClickListener { showLocalDocuments() }
        }

        val consoleHeading = TextView(this).apply {
            text = "Console log"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 8, 0, 4)
        }

        val copyButton = Button(this).apply {
            text = "COPY LOG"
            setOnClickListener {
                val text = logLines.joinToString("\n")
                if (text.isBlank()) {
                    Toast.makeText(
                        this@MainActivity, "Log masih kosong",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    val clipboard =
                        getSystemService(Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("SYSrescue console log", text)
                    )
                    Toast.makeText(
                        this@MainActivity, "Console log disalin",
                        Toast.LENGTH_SHORT
                    ).show()
                    log("INFO", "Log disalin ke clipboard")
                }
            }
        }

        val clearButton = Button(this).apply {
            text = "CLEAR LOG"
            setOnClickListener {
                logLines.clear()
                refreshConsole()
                log("INFO", "Console dibersihkan")
            }
        }

        val logButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(copyButton, LinearLayout.LayoutParams(0, -2, 1f))
            addView(clearButton, LinearLayout.LayoutParams(0, -2, 1f))
        }

        console = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(10, 10, 10, 10)
        }

        logScroll = ScrollView(this).apply {
            setBackgroundColor(0xFF15191E.toInt())
            addView(console)
        }

        val browserRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
        }
        val browserScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                browserRoot,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val backButton = Button(this).apply {
            text = "← KEMBALI KE HALAMAN UTAMA"
            setOnClickListener { setContentView(pageScroll) }
        }
        val browserButton = Button(this).apply {
            text = "BROWSER FILE"
            setOnClickListener {
                setContentView(browserScroll)
                loadRemoteFiles()
            }
        }

        root.addView(title)
        root.addView(status)
        root.addView(refresh)
        root.addView(connectAdbButton)
        root.addView(disconnectAdbButton)
        root.addView(browserButton)
        root.addView(heading)
        root.addView(
            deviceScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(120)
            )
        )
        root.addView(consoleHeading)
        root.addView(logButtons)
        root.addView(
            logScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(190)
            )
        )

        browserRoot.addView(backButton)
        browserRoot.addView(fileBrowserHeading)
        browserRoot.addView(chooseLocalButton)
        browserRoot.addView(showLocalButton)
        browserRoot.addView(filePathInput)
        browserRoot.addView(browserActions)
        browserRoot.addView(fileStatus)
        browserRoot.addView(
            fileScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(480)
            )
        )

        setContentView(pageScroll)
    }

    private fun scanUsb(requestAutomatically: Boolean) {
        devices.removeAllViews()

        val list = usbManager.deviceList.values.toList()
        log("SCAN", "Ditemukan ${list.size} perangkat USB")

        status.text = if (list.isEmpty()) {
            "USB tidak terdeteksi. Periksa OTG, kabel, dan mode USB."
        } else {
            "${list.size} perangkat ditemukan. Periksa status izin di bawah."
        }

        var deviceToRequest: UsbDevice? = null

        list.forEachIndexed { index, device ->
            val granted = usbManager.hasPermission(device)

            log(
                "USB",
                "${device.deviceName} VID=${device.vendorId} " +
                    "PID=${device.productId} permission=$granted"
            )

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(10, 8, 10, 8)
            }

            val details = TextView(this).apply {
                text = """
                    ${index + 1}. ${device.deviceName}
                    Vendor ID: ${device.vendorId}
                    Product ID: ${device.productId}
                    USB permission: $granted
                """.trimIndent()
                textSize = 14f
            }

            val requestButton = Button(this).apply {
                text = if (granted) "IZIN USB AKTIF"
                    else "MINTA IZIN USB"
                isEnabled = !granted
                setOnClickListener {
                    requestUsbPermission(device)
                }
            }

            row.addView(details)
            row.addView(requestButton)
            devices.addView(row)

            if (!granted && deviceToRequest == null) {
                deviceToRequest = device
            }
        }

        if (requestAutomatically &&
            !permissionPending &&
            deviceToRequest != null
        ) {
            requestUsbPermission(deviceToRequest!!)
        }

        if (list.isEmpty()) {
            log("USB", "Tidak ada device untuk dimintai izin")
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            log("USB", "Izin sudah aktif untuk ${device.deviceName}")
            scanUsb(requestAutomatically = false)
            return
        }

        if (permissionPending) {
            log("USB", "Permintaan izin sebelumnya masih menunggu")
            return
        }

        try {
            val intent = Intent(ACTION_USB_PERMISSION).apply {
                setPackage(packageName)
            }

            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags or PendingIntent.FLAG_MUTABLE
            }

            val pendingIntent = PendingIntent.getBroadcast(
                this, device.deviceId, intent, flags
            )

            permissionPending = true
            log(
                "USB",
                "Meminta izin sistem untuk ${device.deviceName} " +
                    "VID=${device.vendorId} PID=${device.productId}"
            )
            usbManager.requestPermission(device, pendingIntent)
            status.text =
                "Permintaan izin USB dikirim. Periksa dialog sistem."
        } catch (e: Exception) {
            permissionPending = false
            log(
                "ERROR",
                "Gagal meminta izin USB: ${e.javaClass.simpleName}: ${e.message}"
            )
            status.text = "Gagal meminta izin USB. Periksa console log."
        }
    }


    private fun chooseLocalFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_LOCAL_TREE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(
        requestCode: Int, resultCode: Int, data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_LOCAL_TREE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val flags = data.flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
            localTreeUri = uri
            safPrefs.edit().putString(KEY_LOCAL_TREE_URI, uri.toString()).apply()
            log("SAF", "Folder lokal dipilih; izin disimpan")
        } catch (e: Exception) {
            localTreeUri = null
            safPrefs.edit().remove(KEY_LOCAL_TREE_URI).apply()
            log("SAF ERROR", "Izin folder gagal disimpan: ${e.message}")
        }
    }

    private fun restoreSafFolderUri() {
        val saved = safPrefs.getString(KEY_LOCAL_TREE_URI, null) ?: return
        val uri = runCatching { Uri.parse(saved) }.getOrNull() ?: return
        if (contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission
            }) {
            localTreeUri = uri
        } else {
            safPrefs.edit().remove(KEY_LOCAL_TREE_URI).apply()
        }
    }

    private fun listLocalDocuments(): List<LocalDocumentEntry> {
        val treeUri = localTreeUri
            ?: throw IllegalStateException("Pilih folder lokal terlebih dahulu")
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, rootId
        )
        val result = mutableListOf<LocalDocumentEntry>()
        contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            ),
            null, null, null
        )?.use { cursor: Cursor ->
            val idCol = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID
            )
            val nameCol = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )
            val mimeCol = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            val sizeCol = cursor.getColumnIndex(
                DocumentsContract.Document.COLUMN_SIZE
            )
            while (cursor.moveToNext()) {
                val id = cursor.getString(idCol) ?: continue
                val name = cursor.getString(nameCol) ?: "(tanpa nama)"
                val mime = cursor.getString(mimeCol)
                val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) {
                    cursor.getLong(sizeCol)
                } else 0L
                result.add(
                    LocalDocumentEntry(
                        name,
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                        mime == DocumentsContract.Document.MIME_TYPE_DIR,
                        size
                    )
                )
            }
        } ?: throw IllegalStateException("Folder SAF tidak dapat dibaca")
        return result.sortedWith(
            compareByDescending<LocalDocumentEntry> { it.isDirectory }
                .thenBy { it.name.lowercase(java.util.Locale.ROOT) }
        )
    }

    private fun showLocalDocuments() {
        try {
            val entries = listLocalDocuments()
            fileList.removeAllViews()

            fileStatus.text =
                "DEVICE A — ${entries.size} item di folder lokal terpilih."

            if (entries.isEmpty()) {
                fileList.addView(TextView(this).apply {
                    text = "Folder kosong."
                    setPadding(12, 12, 12, 12)
                })
                return
            }

            entries.forEach { entry ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(12, 10, 12, 10)
                    setBackgroundColor(
                        if (entry.isDirectory) 0xFFE5EAF0.toInt()
                        else 0xFFFFFFFF.toInt()
                    )
                }

                row.addView(TextView(this).apply {
                    text = (if (entry.isDirectory) "[DIR] " else "[FILE] ") +
                        entry.name
                    textSize = 14f
                })

                if (!entry.isDirectory) {
                    row.addView(TextView(this).apply {
                        text = "${entry.sizeBytes} byte"
                        textSize = 11f
                    })
                }

                fileList.addView(row)
            }
            log("SAF", "Daftar lokal dimuat: ${entries.size} item")
        } catch (e: Exception) {
            fileStatus.text = "Gagal membaca folder lokal: ${e.message}"
            log("SAF ERROR", "Gagal membaca folder: ${e.message}")
        }
    }

    private fun loadRemoteFiles() {
        if (listingFiles) {
            pendingFilePath = filePathInput.text.toString().trim()
            return
        }

        val session = adbSession
        if (session == null || !session.isConnected) {
            fileStatus.text = "ADB belum terhubung."
            log("FILE", "Tidak dapat memuat daftar: sesi ADB belum aktif")
            return
        }

        val path = filePathInput.text.toString().trim()
        if (path.isBlank() || !path.startsWith("/")) {
            fileStatus.text = "Path harus berupa path absolut, misalnya /sdcard."
            return
        }

        listingFiles = true
        fileStatus.text = "Memuat $path ..."
        fileList.removeAllViews()

        activityScope.launch {
            try {
                val result = AdbSyncRepository(session).list(path)
                if (isFinishing || isDestroyed) return@launch

                fileList.removeAllViews()
                if (result.isEmpty()) {
                    fileStatus.text = "Direktori kosong: $path"
                } else {
                    fileStatus.text = "${result.size} item · $path"
                }

                result.forEach { item ->
                    val row = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(12, 8, 12, 8)
                        setBackgroundColor(0xFFFFFFFF.toInt())
                    }

                    val name = TextView(this@MainActivity).apply {
                        text = (if (item.isDirectory) "📁  " else "📄  ") + item.name
                        textSize = 15f
                        typeface = Typeface.DEFAULT_BOLD
                    }

                    val detail = TextView(this@MainActivity).apply {
                        text = if (item.isDirectory) {
                            "Folder · ${item.path}"
                        } else {
                            "${formatBytes(item.sizeBytes)} · ${item.path}"
                        }
                        textSize = 11f
                    }

                    row.addView(name)
                    row.addView(detail)

                    if (item.isDirectory) {
                        row.setOnClickListener {
                            filePathInput.setText(item.path)
                            loadRemoteFiles()
                        }
                    }
                    fileList.addView(row)
                }

                log("FILE", "LIST berhasil: $path (${result.size} item)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isFinishing && !isDestroyed) {
                    fileStatus.text =
                        "Gagal membaca direktori: ${e.javaClass.simpleName}: ${e.message}"
                    log("FILE ERROR", "${e.javaClass.simpleName}: ${e.message}")
                }
            } finally {
                listingFiles = false
                val nextPath = pendingFilePath
                pendingFilePath = null
                if (nextPath != null && !isFinishing && !isDestroyed) {
                    filePathInput.setText(nextPath)
                    loadRemoteFiles()
                }
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
        return String.format(Locale.getDefault(), "%.2f GB", mb / 1024.0)
    }

    private fun connectAdb() {
        if (adbSession?.isConnected == true) {
            log("ADB", "Sesi sudah terhubung")
            return
        }

        val device = usbManager.deviceList.values.firstOrNull {
            usbManager.hasPermission(it)
        }

        if (device == null) {
            log("ADB", "Tidak ada perangkat USB dengan izin aktif")
            status.text = "Pilih perangkat USB dan berikan izin terlebih dahulu."
            return
        }

        val session = UsbAdbSession(this, device)
        adbSession = session
        connectAdbButton.isEnabled = false
        status.text = "Menghubungkan ADB..."

        log(
            "ADB",
            "Memulai handshake: ${device.deviceName}, " +
                "VID=${device.vendorId}, PID=${device.productId}"
        )

        activityScope.launch {
            val result = session.connect()

            if (isFinishing || isDestroyed) {
                session.disconnect()
                return@launch
            }

            result.fold(
                onSuccess = {
                    connectAdbButton.isEnabled = false
                    disconnectAdbButton.isEnabled = true
                    status.text = "Handshake ADB berhasil."
                    log("ADB", "Handshake berhasil; autentikasi diterima")
                    fileStatus.text = "ADB terhubung. Tekan MUAT ULANG untuk membuka /sdcard."
                    loadRemoteFiles()
                },
                onFailure = { error ->
                    adbSession = null
                    connectAdbButton.isEnabled = true
                    disconnectAdbButton.isEnabled = false
                    status.text = "Koneksi ADB gagal. Periksa console log."
                    log(
                        "ADB ERROR",
                        "${error.javaClass.simpleName}: ${error.message}"
                    )
                }
            )
        }
    }

    private fun disconnectAdb() {
        val session = adbSession
        adbSession = null

        disconnectAdbButton.isEnabled = false
        connectAdbButton.isEnabled = true
        status.text = "Memutuskan koneksi ADB..."

        activityScope.launch {
            session?.disconnect()
            if (!isFinishing && !isDestroyed) {
                status.text = "ADB terputus."
                log("ADB", "Sesi diputuskan")
                fileStatus.text = "ADB terputus."
                fileList.removeAllViews()
            }
        }
    }

    private fun log(tag: String, message: String) {
        val time = SimpleDateFormat(
            "HH:mm:ss", Locale.getDefault()
        ).format(Date())

        logLines.addLast("[$time] [$tag] $message")
        while (logLines.size > MAX_LOG_LINES) {
            logLines.removeFirst()
        }
        refreshConsole()
    }

    private fun refreshConsole() {
        if (!::console.isInitialized) return
        console.text = logLines.joinToString("\n")
        if (::logScroll.isInitialized) {
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() {
        val session = adbSession
        adbSession = null

        // Jalankan disconnect pada scope independen agar tidak
        // ikut dibatalkan ketika activityScope dihentikan.
        if (session != null) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                session.disconnect()
            }
        }
        activityScope.cancel()

        try {
            unregisterReceiver(permissionReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
