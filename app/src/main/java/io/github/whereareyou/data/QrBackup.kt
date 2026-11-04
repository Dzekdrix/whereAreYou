package io.github.whereareyou.data

import io.github.whereareyou.crypto.Crypto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.Base64

/**
 * Backup shown as a QR code, to move to another phone. Same content as the file ([Backup]),
 * written compactly so that the QR code stays easy to scan, protected by an 8-digit share code.
 *
 * Text in the QR code: "WAY1:" + Base45(container), Base45 only uses the characters of the QR
 * "alphanumeric" mode, the densest one for this kind of data.
 * container = iterations (4 bytes) || salt (16) || AES-256-GCM(key, payload), key = PBKDF2(code, salt, iterations).
 * payload (binary): version, seed, flags, color, intervals, name, emoji, relays (only if not the default ones), circles.
 */
object QrBackup {
    const val PREFIX = "WAY1:"
    private const val PAYLOAD_VERSION = 1
    private val AAD = "whereareyou-qr".toByteArray()

    /** Above this length the QR code gets too dense to scan reliably from a screen: use the file instead. */
    const val MAX_TEXT_LENGTH = 1600

    private const val FLAG_BATTERY = 1
    private const val FLAG_COLOR = 2
    private const val FLAG_RELAYS = 4
    private const val CIRCLE_SHARE = 1
    private const val CIRCLE_VISIBLE = 2

    fun isQrBackup(text: String) = text.trim().startsWith(PREFIX)

    fun export(data: BackupData, code: String, iterations: Int = Backup.ITERATIONS): String {
        val salt = Crypto.random(16)
        val key = Backup.deriveKey(code, salt, iterations)
        val encrypted = Base64.getDecoder().decode(Crypto.encrypt(key, toBytes(data), AAD))
        val container = ByteArrayOutputStream().apply {
            DataOutputStream(this).writeInt(iterations)
            write(salt)
            write(encrypted)
        }.toByteArray()
        return PREFIX + Base45.encode(container)
    }

    /** Null if this is not a QR backup, or the code is wrong. */
    fun import(text: String, code: String): BackupData? = try {
        val t = text.trim()
        if (!t.startsWith(PREFIX)) {
            null
        } else {
            val container = Base45.decode(t.substring(PREFIX.length)) ?: return null
            val input = DataInputStream(ByteArrayInputStream(container))
            val iterations = input.readInt().coerceIn(1, 10_000_000)
            val salt = ByteArray(16).also { input.readFully(it) }
            val encrypted = input.readBytes()
            val key = Backup.deriveKey(code, salt, iterations)
            Crypto.decrypt(key, Base64.getEncoder().encodeToString(encrypted), AAD)?.let { fromBytes(it) }
        }
    } catch (e: Exception) {
        null
    }

    private val rng = SecureRandom()

    /** Random 8-digit share code. */
    fun generateCode(): String = (1..8).map { '0' + rng.nextInt(10) }.joinToString("")

    // ---- compact binary payload ----

    private fun toBytes(d: BackupData): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            writeByte(PAYLOAD_VERSION)
            write(d.seed)
            val customRelays = d.relays.isNotEmpty() && d.relays != Store.DEFAULT_RELAYS
            writeByte(
                (if (d.shareBattery) FLAG_BATTERY else 0) or
                    (if (d.markerColor != null) FLAG_COLOR else 0) or
                    (if (customRelays) FLAG_RELAYS else 0)
            )
            d.markerColor?.let {
                writeByte(it shr 16)
                writeByte(it shr 8)
                writeByte(it)
            }
            writeShort(d.movingIntervalSeconds.coerceIn(0, 0xFFFF))
            writeShort(d.stillIntervalSeconds.coerceIn(0, 0xFFFF))
            writeUTF(d.name)
            writeUTF(d.emoji)
            if (customRelays) {
                writeByte(d.relays.size.coerceAtMost(255))
                d.relays.take(255).forEach { writeUTF(it) }
            }
            require(d.circles.size <= 255) { "too many circles" }
            writeByte(d.circles.size)
            d.circles.forEach { c ->
                write(c.secret)
                writeByte((if (c.share) CIRCLE_SHARE else 0) or (if (c.visible) CIRCLE_VISIBLE else 0))
                writeUTF(c.name)
            }
        }
        return bytes.toByteArray()
    }

    private fun fromBytes(b: ByteArray): BackupData? {
        val input = DataInputStream(ByteArrayInputStream(b))
        if (input.readUnsignedByte() != PAYLOAD_VERSION) return null
        val seed = ByteArray(32).also { input.readFully(it) }
        val flags = input.readUnsignedByte()
        val color = if (flags and FLAG_COLOR != 0) {
            val r = input.readUnsignedByte()
            val g = input.readUnsignedByte()
            val bl = input.readUnsignedByte()
            (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        } else {
            null
        }
        val moving = input.readUnsignedShort()
        val still = input.readUnsignedShort()
        val name = input.readUTF()
        val emoji = Emoji.sanitize(input.readUTF())
        val relays = if (flags and FLAG_RELAYS != 0) {
            (0 until input.readUnsignedByte()).map { input.readUTF() }
        } else {
            Store.DEFAULT_RELAYS
        }
        val circles = (0 until input.readUnsignedByte()).map {
            val secret = ByteArray(32).also { input.readFully(it) }
            val cf = input.readUnsignedByte()
            Circle(input.readUTF(), secret, cf and CIRCLE_SHARE != 0, cf and CIRCLE_VISIBLE != 0)
        }
        return BackupData(seed, circles, name, emoji, color, moving, still, relays, flags and FLAG_BATTERY != 0)
    }
}

/** Base45 (RFC 9285): binary data written with the 45 characters of the QR code alphanumeric mode. */
object Base45 {
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"

    fun encode(data: ByteArray): String {
        val sb = StringBuilder(data.size / 2 * 3 + 2)
        var i = 0
        while (i + 1 < data.size) {
            var n = (data[i].toInt() and 0xFF) * 256 + (data[i + 1].toInt() and 0xFF)
            repeat(3) {
                sb.append(ALPHABET[n % 45])
                n /= 45
            }
            i += 2
        }
        if (i < data.size) {
            val n = data[i].toInt() and 0xFF
            sb.append(ALPHABET[n % 45]).append(ALPHABET[n / 45])
        }
        return sb.toString()
    }

    /** Null if [text] is not valid Base45. */
    fun decode(text: String): ByteArray? {
        val values = text.map { ALPHABET.indexOf(it).takeIf { v -> v >= 0 } ?: return null }
        if (values.size % 3 == 1) return null
        val out = ByteArrayOutputStream(values.size / 3 * 2 + 1)
        var i = 0
        while (i < values.size) {
            if (values.size - i >= 3) {
                val n = values[i] + values[i + 1] * 45 + values[i + 2] * 45 * 45
                if (n > 0xFFFF) return null
                out.write(n shr 8)
                out.write(n and 0xFF)
                i += 3
            } else {
                val n = values[i] + values[i + 1] * 45
                if (n > 0xFF) return null
                out.write(n)
                i += 2
            }
        }
        return out.toByteArray()
    }
}
