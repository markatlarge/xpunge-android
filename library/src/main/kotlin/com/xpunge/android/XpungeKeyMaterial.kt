package com.xpunge.android

internal object XpungeKeyMaterial {

    internal val partA: ByteArray = byteArrayOf(
        0x05, 0x50, 0x87.toByte(), 0x81.toByte(), 0xD3.toByte(), 0x18, 0x9E.toByte(), 0x6C,
    )

    internal val partD: ByteArray = byteArrayOf(
        0x48, 0xC2.toByte(), 0xFC.toByte(), 0x4D, 0x10, 0x6D, 0x82.toByte(), 0x2A,
    )
}
