package io.github.whereareyou

import io.github.whereareyou.crypto.Crypto
import io.github.whereareyou.data.Backup
import io.github.whereareyou.data.BackupData
import io.github.whereareyou.data.Base45
import io.github.whereareyou.data.Circle
import io.github.whereareyou.data.QrBackup
import io.github.whereareyou.data.Store
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    private val data = BackupData(
        seed = Crypto.random(32),
        circles = listOf(Circle("Famille", Crypto.random(32), share = true), Circle("Amis", Crypto.random(32), share = false, visible = false)),
        name = "JD",
        emoji = "🦊",
        markerColor = 0xFF1E88E5.toInt(),
        movingIntervalSeconds = 120,
        stillIntervalSeconds = 3600,
        relays = listOf("wss://nos.lol"),
        shareBattery = true,
    )

    @Test
    fun roundTrip() {
        val file = Backup.export(data, "secret é!", iterations = 1000)
        assertTrue(Backup.isBackup(file))
        val back = Backup.import(file, "secret é!")!!
        assertArrayEquals(data.seed, back.seed)
        assertEquals(data.circles.map { it.tag }, back.circles.map { it.tag })
        assertEquals(listOf(true, false), back.circles.map { it.share })
        assertEquals(listOf(true, false), back.circles.map { it.visible })
        assertEquals("JD", back.name)
        assertEquals("🦊", back.emoji)
        assertEquals(data.markerColor, back.markerColor)
        assertEquals(120, back.movingIntervalSeconds)
        assertEquals(3600, back.stillIntervalSeconds)
        assertEquals(listOf("wss://nos.lol"), back.relays)
        assertTrue(back.shareBattery)
        // The secrets do not appear in clear in the file.
        assertFalse(file.contains(Crypto.hex(data.seed)))
    }

    @Test
    fun wrongPasswordOrFile() {
        val file = Backup.export(data, "right", iterations = 1000)
        assertNull(Backup.import(file, "wrong"))
        assertNull(Backup.import("not json", "right"))
        assertFalse(Backup.isBackup("{\"a\":1}"))
    }

    @Test
    fun generatedPassword() {
        repeat(50) {
            val pw = Backup.generatePassword()
            assertEquals(18, pw.length)
            assertTrue(pw.any { it.isLowerCase() })
            assertTrue(pw.any { it.isUpperCase() })
            assertTrue(pw.any { it.isDigit() })
            assertTrue(pw.any { !it.isLetterOrDigit() })
        }
    }

    @Test
    fun qrRoundTrip() {
        val code = QrBackup.generateCode()
        val text = QrBackup.export(data, code, iterations = 1000)
        assertTrue(QrBackup.isQrBackup(text))
        // Only QR "alphanumeric" characters: the densest QR mode.
        assertTrue(text.all { it in "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:" })
        val back = QrBackup.import(text, code)!!
        assertArrayEquals(data.seed, back.seed)
        assertEquals(data.circles.map { it.tag }, back.circles.map { it.tag })
        assertEquals(data.circles.map { it.name }, back.circles.map { it.name })
        assertEquals(listOf(true, false), back.circles.map { it.share })
        assertEquals(listOf(true, false), back.circles.map { it.visible })
        assertEquals("JD", back.name)
        assertEquals("🦊", back.emoji)
        assertEquals(data.markerColor, back.markerColor)
        assertEquals(120, back.movingIntervalSeconds)
        assertEquals(3600, back.stillIntervalSeconds)
        assertEquals(listOf("wss://nos.lol"), back.relays)
        assertTrue(back.shareBattery)
        assertNull(QrBackup.import(text, "00000000".takeIf { it != code } ?: "11111111"))
    }

    @Test
    fun qrStaysSmall() {
        val defaults = BackupData(
            Crypto.random(32), (1..15).map { Circle("Cercle n°$it", Crypto.random(32), true) },
            "Prénom", "", null, 300, 1800, Store.DEFAULT_RELAYS, false,
        )
        val text = QrBackup.export(defaults, "12345678", iterations = 1000)
        assertTrue(text.length < QrBackup.MAX_TEXT_LENGTH)
        assertEquals(Store.DEFAULT_RELAYS, QrBackup.import(text, "12345678")!!.relays)
        val two = QrBackup.export(defaults.let { BackupData(it.seed, it.circles.take(2), it.name, it.emoji, null, 300, 1800, it.relays, false) }, "1", 1000)
        println("QR text: 2 circles = ${two.length} chars, 15 circles = ${text.length} chars")
    }

    @Test
    fun base45() {
        // RFC 9285 examples
        assertEquals("BB8", Base45.encode("AB".toByteArray()))
        assertEquals("%69 VD92EX0", Base45.encode("Hello!!".toByteArray()))
        assertEquals("ietf!", String(Base45.decode("QED8WEX0")!!))
        repeat(20) { n ->
            val bytes = Crypto.random(n)
            assertArrayEquals(bytes, Base45.decode(Base45.encode(bytes)))
        }
        assertNull(Base45.decode("GGW")) // 65536+: invalid
    }

    @Test
    fun shareCode() {
        repeat(50) {
            val c = QrBackup.generateCode()
            assertEquals(8, c.length)
            assertTrue(c.all { it.isDigit() })
        }
    }
}
