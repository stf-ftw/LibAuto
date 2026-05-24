package com.example.androidautodisplay

import java.io.ByteArrayOutputStream

object AawBootstrapProtocol {
    const val MESSAGE_WIFI_START_REQUEST = 1
    const val MESSAGE_WIFI_INFO_REQUEST = 2
    const val MESSAGE_WIFI_INFO_RESPONSE = 3
    const val MESSAGE_WIFI_VERSION_REQUEST = 4
    const val MESSAGE_WIFI_VERSION_RESPONSE = 5
    const val MESSAGE_WIFI_CONNECTION_STATUS = 6
    const val MESSAGE_WIFI_START_RESPONSE = 7

    const val STATUS_SUCCESS = 0
    const val SECURITY_WPA2_PERSONAL = 5
    const val ACCESS_POINT_STATIC = 0
    const val ACCESS_POINT_DYNAMIC = 1

    data class Frame(val messageId: Int, val payload: ByteArray)

    fun encodeFrame(messageId: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 4)
        writeU16(out, payload.size)
        writeU16(out, messageId)
        out.write(payload)
        return out.toByteArray()
    }

    fun decodeFrames(buffer: MutableList<Byte>): List<Frame> {
        val frames = mutableListOf<Frame>()
        while (buffer.size >= 4) {
            val length = readU16(buffer, 0)
            if (buffer.size < length + 4) {
                break
            }
            val messageId = readU16(buffer, 2)
            val payload = ByteArray(length)
            for (index in 0 until length) {
                payload[index] = buffer[index + 4]
            }
            repeat(length + 4) {
                buffer.removeAt(0)
            }
            frames += Frame(messageId, payload)
        }
        return frames
    }

    fun wifiStartRequest(ipAddress: String, port: Int): ByteArray {
        val out = ByteArrayOutputStream()
        writeString(out, 1, ipAddress)
        writeVarintField(out, 2, port.toLong())
        return out.toByteArray()
    }

    fun wifiInfoResponse(
        ssid: String,
        password: String,
        bssid: String,
        dynamicAp: Boolean
    ): ByteArray {
        val out = ByteArrayOutputStream()
        writeString(out, 1, ssid)
        writeString(out, 2, password)
        writeString(out, 3, bssid)
        writeVarintField(out, 4, SECURITY_WPA2_PERSONAL.toLong())
        writeVarintField(out, 5, if (dynamicAp) ACCESS_POINT_DYNAMIC.toLong() else ACCESS_POINT_STATIC.toLong())
        return out.toByteArray()
    }

    fun wifiStartResponseStatus(payload: ByteArray): Int? {
        return readVarintField(payload, 3)?.toInt()
    }

    fun wifiConnectionStatus(payload: ByteArray): Int? {
        return readVarintField(payload, 1)?.toInt()
    }

    fun wifiVersionSummary(payload: ByteArray): String {
        val a = readVarintField(payload, 1)
        val b = readVarintField(payload, 2)
        val d = readVarintField(payload, 4)
        return listOfNotNull(
            a?.let { "a=$it" },
            b?.let { "b=$it" },
            d?.let { "d=$it" }
        ).joinToString(" ").ifBlank { "empty" }
    }

    private fun writeString(out: ByteArrayOutputStream, field: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeVarint(out, ((field shl 3) or 2).toLong())
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun writeVarintField(out: ByteArrayOutputStream, field: Int, value: Long) {
        writeVarint(out, ((field shl 3) or 0).toLong())
        writeVarint(out, value)
    }

    private fun readVarintField(data: ByteArray, wantedField: Int): Long? {
        var index = 0
        while (index < data.size) {
            val keyResult = readVarint(data, index) ?: return null
            val key = keyResult.first
            index = keyResult.second
            val field = (key ushr 3).toInt()
            when ((key and 7L).toInt()) {
                0 -> {
                    val value = readVarint(data, index) ?: return null
                    index = value.second
                    if (field == wantedField) {
                        return value.first
                    }
                }
                2 -> {
                    val length = readVarint(data, index) ?: return null
                    index = length.second + length.first.toInt()
                    if (index > data.size) {
                        return null
                    }
                }
                5 -> index += 4
                1 -> index += 8
                else -> return null
            }
        }
        return null
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int>? {
        var shift = 0
        var value = 0L
        var index = start
        while (index < data.size && shift < 64) {
            val byte = data[index].toInt() and 0xff
            value = value or ((byte and 0x7f).toLong() shl shift)
            index++
            if ((byte and 0x80) == 0) {
                return value to index
            }
            shift += 7
        }
        return null
    }

    private fun writeVarint(out: ByteArrayOutputStream, raw: Long) {
        var value = raw
        while (true) {
            if ((value and -0x80L) == 0L) {
                out.write(value.toInt())
                return
            }
            out.write(((value and 0x7f) or 0x80).toInt())
            value = value ushr 7
        }
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xff)
        out.write(value and 0xff)
    }

    private fun readU16(bytes: List<Byte>, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xff) shl 8) or
            (bytes[offset + 1].toInt() and 0xff)
    }
}
