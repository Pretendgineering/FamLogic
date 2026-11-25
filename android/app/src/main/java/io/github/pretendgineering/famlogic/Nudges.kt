package io.github.pretendgineering.famlogic

/**
 * Nudges: small one-tap messages to one person (also from the Pebble watch, and
 * as a reply to a check-in). The grown-up ones only go between adults (parents
 * and grandparents); the server checks that too.
 */
object Nudges {
    data class Nudge(val kind: String, val emoji: String, val words: String, val adultsOnly: Boolean = false)

    val all = listOf(
        Nudge("thumbs", "👍", "a thumbs up"),
        Nudge("heart", "❤️", "a heart"),
        Nudge("wave", "👋", "a wave"),
        Nudge("smile", "😊", "a smile"),
        Nudge("hug", "🤗", "a hug"),
        Nudge("comehome", "🏠", "come home"),
        Nudge("car", "🚗", "a car"),
        Nudge("cowboy", "🤠", "a cowboy"),
        Nudge("alien", "👽", "an alien"),
        Nudge("rainbow", "🌈", "a rainbow"),
        Nudge("dino", "🦖", "a dinosaur"),
        Nudge("coffee", "☕", "a coffee", adultsOnly = true),
        Nudge("eggplant", "🍆", "an eggplant", adultsOnly = true),
        Nudge("peach", "🍑", "a peach", adultsOnly = true),
    )

    fun byKind(kind: String) = all.find { it.kind == kind }

    private fun adult(role: String) = role == "parent" || role == "grandparent"

    /** The nudges one person can send another. */
    fun available(myRole: String, theirRole: String) = all.filter { !it.adultsOnly || (adult(myRole) && adult(theirRole)) }
}
