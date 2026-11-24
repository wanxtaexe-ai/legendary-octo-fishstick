package com.animeext.downloader.parser

import java.nio.charset.StandardCharsets

object Base64Codec {
    private val decodeTable = IntArray(256) { -1 }.apply {
        for (i in 0..25) {
            this['A'.code + i] = i
            this['a'.code + i] = i + 26
        }
        for (i in 0..9) {
            this['0'.code + i] = i + 52
        }
        this['+'.code] = 62
        this['-'.code] = 62
        this['/'.code] = 63
        this['_'.code] = 63
    }

    fun decodeToBytes(input: String): ByteArray? {
        val clean = input.trim().replace("\r", "").replace("\n", "")
        if (clean.isEmpty()) return null
        val out = ArrayList<Byte>((clean.length * 3) / 4)
        var acc = 0
        var bits = 0
        for (ch in clean) {
            if (ch == '=') break
            val code = ch.code
            if (code >= 256) return null
            val v = decodeTable[code]
            if (v < 0) return null
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((acc ushr bits) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }

    fun decodeToUtf8(input: String): String? =
        decodeToBytes(input)?.let { String(it, StandardCharsets.UTF_8) }
}
