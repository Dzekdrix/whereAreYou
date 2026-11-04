package io.github.whereareyou

import io.github.whereareyou.crypto.Crypto
import io.github.whereareyou.data.Backup
import io.github.whereareyou.data.BackupData
import io.github.whereareyou.data.Circle
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
}
