package com.terangbulan.sysrescue.transfer

enum class TransferDirection {
    DEVICE_A_TO_B,
    DEVICE_B_TO_A
}

data class TransferProgress(
    val currentItem: String,
    val completedItems: Int,
    val totalItems: Int,
    val transferredBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val finished: Boolean = false,
    val error: String? = null
)
