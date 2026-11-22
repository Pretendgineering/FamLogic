package io.github.spasm0dic.famlogic

/**
 * FamLogic modules. The family's admin switches them on or off on the server
 * (web console → Settings → Modules); the server lists the ones that are on as
 * "module:<id>" features, and the app hides the rest.
 */
object Modules {
    const val THEREABOUTS = "thereabouts" // location sharing, SOS, fences, trips
    const val CHAT = "chat"
    const val CALENDAR = "calendar"
    const val TODOS = "todos"
    const val CHORES = "chores"
    const val MEALS = "meals"
    const val DISPLAY = "display"

    /** A server from before modules (no "modules" feature) is all Thereabouts. */
    fun on(features: Set<String>?, id: String): Boolean =
        features == null || "modules" !in features || "module:$id" in features

    /** For modules newer than Thereabouts: only when the server says it's on. */
    fun has(features: Set<String>?, id: String): Boolean = features != null && "module:$id" in features
}
