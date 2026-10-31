package io.github.whereareyou

import io.github.whereareyou.data.Emoji
import org.junit.Assert.assertEquals
import org.junit.Test

class EmojiTest {
    @Test
    fun keepsOnlyTheFirstCharacter() {
        assertEquals("🦊", Emoji.sanitize("🦊🐻 and some text"))
        assertEquals("❤️", Emoji.sanitize("❤️")) // with its variation selector
        assertEquals("🚀", Emoji.sanitize("  🚀  "))
    }

    @Test
    fun emptyMeansNoEmoji() {
        assertEquals("", Emoji.sanitize(""))
        assertEquals("", Emoji.sanitize("   "))
    }
}
