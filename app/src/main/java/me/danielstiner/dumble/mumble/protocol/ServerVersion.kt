package me.danielstiner.dumble.mumble.protocol

import me.danielstiner.dumble.mumble.proto.MumbleProtos

/** The server's advertised build, decoded from its Version message. Display + future gating. */
data class ServerVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val release: String,
    val os: String,
) {
    override fun toString() = "$major.$minor.$patch"

    companion object {
        fun from(v: MumbleProtos.Version): ServerVersion {
            val maj: Int; val min: Int; val pat: Int
            if (v.hasVersionV2()) {
                val x = v.versionV2
                maj = ((x shr 48) and 0xFFFF).toInt()
                min = ((x shr 32) and 0xFFFF).toInt()
                pat = ((x shr 16) and 0xFFFF).toInt()
            } else {
                val x = v.versionV1
                maj = (x shr 16) and 0xFFFF
                min = (x shr 8) and 0xFF
                pat = x and 0xFF
            }
            return ServerVersion(maj, min, pat, v.release, v.os)
        }
    }
}
