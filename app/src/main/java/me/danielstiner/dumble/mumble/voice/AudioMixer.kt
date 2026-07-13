package me.danielstiner.dumble.mumble.voice

/** Sums mono PCM16 streams with saturation. Playback-thread only (no synchronization). */
object AudioMixer {
    fun mixInto(dst: ShortArray, src: ShortArray, n: Int) {
        for (i in 0 until n) {
            val sum = dst[i] + src[i]
            dst[i] = when {
                sum > Short.MAX_VALUE -> Short.MAX_VALUE
                sum < Short.MIN_VALUE -> Short.MIN_VALUE
                else -> sum.toShort()
            }
        }
    }
}
