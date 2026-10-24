package com.example.otgmanager

import android.app.Activity
import android.os.Bundle
import android.content.Context
import android.hardware.usb.UsbManager
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var devices: LinearLayout
    private lateinit var browserButton: Button

    private val usbManager: UsbManager
        get() = getSystemService(Context.USB_SERVICE) as UsbManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createUi()
        scanUsb()
    }

    private fun createUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 20)
        }

        val title = TextView(this).apply {
            text = "OTG ADB FILE MANAGER"
            textSize = 23f
            typeface = Typeface.DEFAULT_BOLD
        }

        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, 20, 0, 12)
        }

        val refresh = Button(this).apply {
            text = "DETEKSI ULANG USB"
            setOnClickListener { scanUsb() }
        }

        browserButton = Button(this).apply {
            text = "BROWSER DEVICE B"
            isEnabled = false
            setOnClickListener {
                status.text =
                    "ADB belum terhubung. Deteksi USB saja belum " +
                    "membuktikan sesi ADB sudah aktif."
            }
        }

        val heading = TextView(this).apply {
            text = "Perangkat USB"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 18, 0, 8)
        }

        devices = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scroll = ScrollView(this).apply {
            addView(devices)
        }

        root.addView(title)
        root.addView(status)
        root.addView(refresh)
        root.addView(browserButton)
        root.addView(heading)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        setContentView(root)
    }

    private fun scanUsb() {
        devices.removeAllViews()
        browserButton.isEnabled = false

        val list = usbManager.deviceList.values.toList()
        status.text = if (list.isEmpty()) {
            "USB belum terdeteksi. Periksa OTG dan kabel."
        } else {
            "${list.size} perangkat USB ditemukan. Status ADB belum diuji."
        }

        list.forEachIndexed { index, device ->
            val row = TextView(this).apply {
                text = """
                    ${index + 1}. ${device.deviceName}
                    Vendor ID: ${device.vendorId}
                    Product ID: ${device.productId}
                    USB permission: ${usbManager.hasPermission(device)}
                """.trimIndent()
                textSize = 14f
                setPadding(12, 14, 12, 14)
            }
            devices.addView(row)
        }
    }
}
