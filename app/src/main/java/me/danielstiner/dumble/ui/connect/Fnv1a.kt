package me.danielstiner.dumble.ui.connect

private const val OFFSET_BASIS = 2166136261u
private const val PRIME = 16777619u

/**
 * FNV-1a, 32-bit, over UTF-8 bytes.
 *
 * The [UByte] step is load-bearing: `Byte` is signed, so widening directly sign-extends every byte
 * above 0x7F into a different hash.
 */
fun fnv1a32(text: String): UInt {
    var hash = OFFSET_BASIS
    for (byte in text.toByteArray(Charsets.UTF_8)) {
        hash = (hash xor byte.toUByte().toUInt()) * PRIME
    }
    return hash
}
