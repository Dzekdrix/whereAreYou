package io.github.whereareyou.data

import io.github.whereareyou.crypto.Crypto
import io.github.whereareyou.crypto.Secp256k1
import java.text.Normalizer
import java.util.Locale

/**
 * Identity fingerprint shown to the user: [LENGTH] emoji derived from a member id (identity public key),
 * to check in person that "Bob" on the map really is Bob.
 *
 * fingerprint = the first [LENGTH] bytes of taggedHash("whereareyou/fingerprint", public key),
 * each byte picking one of the 256 [EMOJI]. One-way (SHA-256): it gives nothing back about the key,
 * and the key itself is a public key anyway (the secret key never leaves the device).
 * 8 emoji = 64 bits: forging an identity with the same fingerprint as a given member takes ~2^64 key generations.
 */
object Fingerprint {
    const val LENGTH = 8
    private const val DOMAIN = "whereareyou/fingerprint"

    /**
     * 256 emoji, one per byte value: single code point, emoji presentation by default (no U+FE0F needed),
     * available on Android 10, and chosen to look different from each other (one dog, one cat...).
     * The order is part of the protocol: never change it, everyone must see the same fingerprint.
     */
    val EMOJI: List<String> = listOf(
        // animals
        "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯", "🦁", "🐮", "🐷", "🐸", "🐵", "🐔",
        "🐧", "🐦", "🐤", "🦆", "🦅", "🦉", "🦇", "🐺", "🐗", "🐴", "🦄", "🐝", "🐛", "🦋", "🐌", "🐞",
        "🐜", "🦂", "🐢", "🐍", "🦎", "🦖", "🦕", "🐙", "🦑", "🦐", "🦀", "🐡", "🐠", "🐬", "🐳", "🦈",
        "🐊", "🦓", "🦍", "🐘", "🦏", "🐪", "🦒", "🦔", "🐉", "🦌", "🐐", "🐑", "🦃", "🐩", "🦗", "🦚",
        "🦜", "🦢", "🦘", "🦙", "🦛", "🦝",
        // nature
        "🌵", "🎄", "🌳", "🌴", "🌱", "🍀", "🍁", "🍄", "🌷", "🌹", "🌻", "🌼", "🌸", "💐", "🌾", "🌍",
        "🌙", "⭐", "🌞", "🌈", "⚡", "🔥", "💧", "🌊", "⛄", "🌋", "☔",
        // food
        "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓", "🍒", "🍑", "🍍", "🥥", "🥝", "🍅", "🍆", "🥑",
        "🥦", "🥕", "🌽", "🥔", "🥐", "🍞", "🥖", "🥨", "🧀", "🥚", "🍳", "🥞", "🥓", "🍗", "🌭", "🍔",
        "🍟", "🍕", "🥪", "🌮", "🌯", "🍝", "🍜", "🍣", "🍤", "🍙", "🍚", "🍦", "🍩", "🍪", "🎂", "🍰",
        "🍫", "🍬", "🍭", "🍯", "🥛", "☕", "🍵", "🍺", "🍷", "🍸", "🍹", "🥤", "🥜",
        // activities and objects
        "⚽", "🏀", "🏈", "⚾", "🎾", "🏐", "🏉", "🎱", "🏓", "🏸", "🥊", "🎯", "🎳", "⛳", "🎣", "🎿",
        "🛷", "🥌", "🏆", "🏅", "🎮", "🎲", "🧩", "🎨", "🎭", "🎤", "🎧", "🎷", "🎸", "🎹", "🎺", "🎻",
        "🥁", "🎬", "📷", "💡", "🔦", "📚", "📌", "📎", "🔑", "🔒", "🔔", "🎁", "🎈", "🎉", "🎀", "💎",
        "💍", "👑", "🎩", "👓", "👟", "👠", "👢", "👜", "🎒", "⌚", "📱", "💻", "⌛", "⏰", "📡", "🔋",
        "🔌", "🧲", "🔭", "🔬", "💊", "🧪", "🧭", "🧸",
        // transport and places
        "🚗", "🚕", "🚌", "🚓", "🚑", "🚒", "🚜", "🚲", "🛴", "🚂", "🚁", "🚀", "⛵", "🚢", "⚓", "🛸",
        "🏠", "🏰", "🗼", "🗽", "⛺", "🎡", "🎢", "⛲", "🚦", "🛶",
    )

    /** The [LENGTH] emoji of this member id (hex public key). */
    fun of(memberId: String): List<String> {
        val key = runCatching { Crypto.unhex(memberId) }.getOrElse { memberId.toByteArray() }
        val hash = Secp256k1.taggedHash(DOMAIN, key)
        return (0 until LENGTH).map { EMOJI[hash[it].toInt() and 0xff] }
    }
}

/** Two members with the same name but different identities: one of them may be pretending to be the other. */
object NameClash {
    /**
     * Normalized name used for the comparison: case, accents, spaces and invisible characters ignored
     * ("Bob", " bob ", "Bób" and "B​ob" clash). Look-alike letters of other alphabets are not caught:
     * the fingerprint is the real check.
     */
    fun normalize(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFKD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() && Character.getType(it) != Character.FORMAT.toInt() }
            .filterNot { it.isWhitespace() }
            .lowercase(Locale.ROOT)

    /** Ids among [people] (id to name, ourselves included) whose name is also used by another id. */
    fun find(people: Collection<Pair<String, String>>): Set<String> =
        people.distinctBy { it.first }
            .groupBy { normalize(it.second) }
            .filterKeys { it.isNotEmpty() }
            .values.filter { it.size > 1 }
            .flatMap { group -> group.map { it.first } }
            .toSet()
}
