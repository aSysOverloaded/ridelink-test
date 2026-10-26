package dev.ridelink.test

import java.util.UUID

/**
 * Message format for the Apache RTR 200 4V (2025, 5" TFT) instrument cluster.
 *
 * Worked out by reading the TVS Connect Android app (decompiled), for
 * interoperability with the owner's own motorcycle. Nothing here talks to any
 * TVS server.
 *
 *  - Bluetooth LE, service 5456534D-..., write 00005352, notify 00005354
 *    (com.tvs.bike.core.bikes.ApacheBLEConnectionConfig).
 *  - Writes are "with response" (write type 2).
 *  - Outgoing frames are 20 bytes. Navigation frames get a checksum
 *    (BluetoothUtil.addChecksum); then every Apache frame is scrambled by
 *    XOR-ing bytes 2..18 with 0xEA (BluetoothUtil.encryptDataApache).
 *  - Incoming frames are unscrambled the same way (decryptDataForApache).
 */
object ClusterProtocol {

    val SERVICE_UUID: UUID = UUID.fromString("5456534D-5647-5341-5342-454E544F5251")
    val WRITE_UUID: UUID = UUID.fromString("00005352-0000-1000-8000-00805f9b34fb")
    val NOTIFY_UUID: UUID = UUID.fromString("00005354-0000-1000-8000-00805f9b34fb")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private const val XOR_KEY = 0xEA

    /** Arrow codes on the Apache cluster (from ApacheUtils.getIceClusterPictogramId). */
    object Picto {
        const val LEFT = 0
        const val SHARP_LEFT = 1
        const val SLIGHT_LEFT = 2
        const val RIGHT = 16
        const val SHARP_RIGHT = 17
        const val SLIGHT_RIGHT = 18
        const val U_TURN = 25
        const val ARRIVED = 34
        const val STRAIGHT = 87
    }

    /** "Prefix" byte (BleNavigationSendData.sendApacheNavigationDataToCluster). */
    object Prefix {
        const val NONE = 0
        const val TURN_AHEAD = 1      // manoeuvre within 1 km
        const val LEFT_NOW = 3        // < 65 m, left
        const val RIGHT_NOW = 4       // < 65 m, right
        const val STRAIGHT_LONG = 5   // next manoeuvre > 1 km away
        const val GPS_OFF = 6
    }

    /**
     * Plain (not yet checksummed or scrambled) 20-byte navigation frame,
     * identical to ApacheClusterNavigationData.toByteArray():
     *
     *  0-1  5A 4E
     *  2-3  distance to manoeuvre, metres, big-endian
     *  4-5  minutes to destination, big-endian
     *  6-8  metres to destination, 3 bytes big-endian
     *  9    pictogram (arrow) code
     *  10   number of text lines (1 or 2)
     *  11   navigation status (1 = navigating, 0 = arrived/stopped)
     *  12   unit (0)
     *  13   alert flag
     *  14   prefix
     *  15-18 reserved (0)
     *  19   FF
     */
    fun navFrame(
        distanceToTurnM: Int,
        minutesLeft: Int,
        metresLeft: Int,
        picto: Int,
        prefix: Int = Prefix.TURN_AHEAD,
        textLines: Int = 1,
        navStatus: Int = 1,
        alert: Int = 0,
    ): ByteArray {
        val f = ByteArray(20)
        f[0] = 0x5A
        f[1] = 0x4E
        val d = when {
            distanceToTurnM <= 0 -> 0
            distanceToTurnM <= 0xFFFF -> distanceToTurnM
            else -> 0xFFFF
        }
        f[2] = (d shr 8).toByte()
        f[3] = d.toByte()
        val m = minutesLeft.coerceIn(0, 0xFFFF)
        f[4] = (m shr 8).toByte()
        f[5] = m.toByte()
        val left = metresLeft.coerceIn(0, 0xFFFFFF)
        f[6] = (left shr 16).toByte()
        f[7] = (left shr 8).toByte()
        f[8] = left.toByte()
        f[9] = picto.toByte()
        f[10] = textLines.toByte()
        f[11] = navStatus.toByte()
        f[12] = 0
        f[13] = alert.toByte()
        f[14] = prefix.toByte()
        f[19] = 0xFF.toByte()
        return f
    }

    /**
     * Plain 20-byte text frame (BluetoothUtil.createMobileToClusterArray):
     * 5B, id, up to 17 ASCII chars, zero padding, FF.
     * id 0x52 ('R') = rider name, 0x4C ('L') = custom text line.
     */
    fun textFrame(id: Int, text: String): ByteArray {
        val clean = text.map { c ->
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == ' ') c else 'X'
        }.joinToString("").take(17).trim()
        val bytes = clean.toByteArray(Charsets.US_ASCII)
        val f = ByteArray(20)
        f[0] = 0x5B
        f[1] = id.toByte()
        bytes.copyInto(f, 2)
        f[19] = 0xFF.toByte()
        return f
    }

    /** BluetoothUtil.addChecksum: replaces byte[len-2] with the checksum, keeps FF. */
    fun withChecksum(data: ByteArray): ByteArray {
        require(data.size >= 3)
        val head = data.copyOfRange(0, data.size - 2)
        var sum = 0
        for (b in head) sum += b.toInt() and 0xFF
        val checksum = 255 - (sum % 256)
        return head + byteArrayOf(checksum.toByte(), 0xFF.toByte())
    }

    /** BluetoothUtil.encryptDataApache / decryptDataForApache (they are the same XOR). */
    fun scramble(data: ByteArray): ByteArray {
        require(data.size >= 3)
        val out = data.copyOf()
        for (i in 2 until data.size - 1) {
            out[i] = ((data[i].toInt() and 0xFF) xor XOR_KEY).toByte()
        }
        out[out.size - 1] = 0xFF.toByte()
        return out
    }

    fun unscramble(data: ByteArray): ByteArray = if (data.size >= 3) scramble(data) else data

    /** What actually goes over the air for a navigation frame. */
    fun navWire(plain: ByteArray): ByteArray = scramble(withChecksum(plain))

    /** What actually goes over the air for a text frame (no checksum, per the official app). */
    fun textWire(plain: ByteArray): ByteArray = scramble(plain)

    fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
