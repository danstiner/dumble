package me.danielstiner.dumble.mumble.util

/** Pluggable logger so core classes never import android.util.Log. */
object MumbleLog {
    @Volatile var sink: (tag: String, msg: String, t: Throwable?) -> Unit =
        { tag, msg, t -> println("[$tag] $msg${t?.let { " — $it" } ?: ""}") }

    fun d(tag: String, msg: String) = sink(tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = sink(tag, msg, t)
}
