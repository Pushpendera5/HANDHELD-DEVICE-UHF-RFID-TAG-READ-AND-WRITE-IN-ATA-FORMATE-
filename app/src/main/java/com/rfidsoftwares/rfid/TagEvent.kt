
package com.rfidsoftwares.rfid

data class TagEvent(
    val epc: String,
    val rssi: Int?,
    val source: TagSource,
    val seenAt: Long,
)

enum class TagSource {
    EPC,
    TID,
    USER,
}

