package me.danielstiner.dumble.mumble.net

/**
 * The packet layer of Mumble's UDP voice channel: [Ocb2] plus the 128-bit counter each peer keeps
 * as its nonce. A datagram is
 *
 *     byte 0      low byte of the sender's counter
 *     bytes 1..3  the first three bytes of the OCB2 tag
 *     bytes 4..   ciphertext, as long as the plaintext
 *
 * so it costs [OVERHEAD] bytes. Only that low byte travels, and the receiver rebuilds the other
 * fifteen from the counter it already holds.
 *
 * Three threads share one instance -- control channel keying and reading stats, send path, receive
 * thread -- so every entry point is synchronized and [Ocb2], which is not thread-safe, is only ever
 * reached from inside that lock. [encrypt] and [decrypt] allocate nothing.
 */
class CryptState {
    private var cipher: Ocb2? = null

    private val encryptCounter = U128()
    private val window = ReplayWindow()

    private var good = 0
    private var late = 0
    private var advanced = 0
    private var resync = 0
    private var replay = 0

    private val nonce = ByteArray(NONCE)
    private val tag = ByteArray(NONCE)

    @Synchronized
    fun isValid(): Boolean = cipher != null

    /**
     * Adopts a key exchange: [encryptNonce] seeds our send counter, [decryptNonce] the peer's.
     * Every argument is checked before anything moves, so a bad exchange cannot leave the cipher
     * half-keyed. Replay state and the counts start over.
     */
    @Synchronized
    fun setKeys(key: ByteArray, encryptNonce: ByteArray, decryptNonce: ByteArray) {
        require(key.size == NONCE) { "key must be 16 bytes, got ${key.size}" }
        require(encryptNonce.size == NONCE) {
            "encrypt nonce must be 16 bytes, got ${encryptNonce.size}"
        }
        require(decryptNonce.size == NONCE) {
            "decrypt nonce must be 16 bytes, got ${decryptNonce.size}"
        }
        val keyed = Ocb2(key)
        cipher = keyed
        encryptCounter.readFrom(encryptNonce)
        window.restartAt(decryptNonce)
        good = 0
        late = 0
        advanced = 0
        resync = 0
        replay = 0
    }

    /** The peer has told us where its counter really is, because we drifted too far to rebuild it. */
    @Synchronized
    fun setDecryptNonce(serverNonce: ByteArray) {
        require(serverNonce.size == NONCE) { "server nonce must be 16 bytes, got ${serverNonce.size}" }
        window.restartAt(serverNonce)
        resync++
    }

    /** A copy of our send counter, as the last packet we sealed left it. */
    @Synchronized
    fun encryptNonce(): ByteArray = ByteArray(NONCE).also { encryptCounter.writeTo(it) }

    @Synchronized
    fun stats(): CryptStats =
        // Every accepted packet either moved the top or filled a slot an earlier move opened, so
        // the top's total travel minus the packets that arrived is exactly what never did.
        CryptStats(good, late, advanced - good, resync, replay)

    /**
     * Encrypts `src[0, len)` into [dst], which must hold `len + OVERHEAD` bytes and be a different
     * array from [src]. Returns the datagram length. Throws if called before [setKeys]: sending
     * unkeyed is a caller bug, not something the wire can cause.
     */
    @Synchronized
    fun encrypt(src: ByteArray, len: Int, dst: ByteArray): Int {
        val keyed = checkNotNull(cipher) { "encrypt before setKeys" }
        // Everything that can throw is checked before the counter moves: a caller bug must not
        // burn a counter value and desynchronise the peer.
        require(len >= 0) { "negative length $len" }
        require(len <= src.size) { "length $len exceeds ${src.size} source bytes" }
        require(len <= dst.size - OVERHEAD) {
            "destination holds ${dst.size}, needs ${len.toLong() + OVERHEAD}"
        }
        require(src !== dst) { "encrypt cannot work in place: the header would land on the plaintext" }

        // The seed is never used as a nonce; the first packet goes out under seed + 1.
        encryptCounter.increment()
        encryptCounter.writeTo(nonce)
        // No return to check: seal only fails with its XEX* countermeasure turned off.
        keyed.seal(nonce, src, 0, len, dst, OVERHEAD, tag)

        dst[0] = encryptCounter.lowByte.toByte()
        dst[1] = tag[0]
        dst[2] = tag[1]
        dst[3] = tag[2]
        return len + OVERHEAD
    }

    /**
     * Decrypts `src[0, len)` into [dst] and returns the plaintext length, or -1 for anything the
     * wire could produce: a runt, a bad tag, a replay, a counter too far out to rebuild, or a
     * packet that beat the key exchange. A rejection leaves the stream state exactly as it was --
     * only [CryptStats.replay] moves -- so the next good packet still decrypts. [dst] is the
     * exception: it holds unverified plaintext whenever -1 comes back, and the caller must
     * discard it.
     *
     * Throws only for caller bugs, and only before any state is read or written. [dst] must hold
     * `len - OVERHEAD` bytes; size it from the datagram buffer, not from an expected frame size, so
     * a peer cannot forge the length that crashes the receive loop.
     */
    @Synchronized
    fun decrypt(src: ByteArray, len: Int, dst: ByteArray): Int {
        require(len >= 0) { "negative length $len" }
        require(len <= src.size) { "length $len exceeds ${src.size} source bytes" }
        if (len < OVERHEAD) return -1
        val plainLen = len - OVERHEAD
        require(plainLen <= dst.size) { "destination holds ${dst.size}, needs $plainLen" }
        val keyed = cipher ?: return -1

        // Read the header out before open(), which fills dst -- and dst is allowed to be src.
        val hint = src[0].toInt() and 0xFF
        val wire0 = src[1]
        val wire1 = src[2]
        val wire2 = src[3]

        val offset = window.offsetFor(hint)
        // A replay costs no AES: the bitmap answers first.
        if (window.alreadyAccepted(offset)) {
            replay++
            return -1
        }

        // Guessing is free: a wrong guess makes the wrong nonce and the tag will not match.
        window.nonceAt(offset, nonce)

        // open() hands back unverified plaintext and a recomputed tag; only this comparison
        // authenticates. Its false return means the final block has the shape the XEX* forgery
        // produces, which counts exactly as a mismatch.
        val unforged = keyed.open(nonce, src, OVERHEAD, plainLen, dst, 0, tag)
        val diff = (tag[0].toInt() xor wire0.toInt()) or
            (tag[1].toInt() xor wire1.toInt()) or
            (tag[2].toInt() xor wire2.toInt())
        if (!unforged || diff != 0) return -1

        window.accept(offset)
        if (offset > 0) advanced += offset else late++
        good++
        return plainLen
    }

    companion object {
        const val OVERHEAD = 4

        private const val NONCE = 16
    }
}
