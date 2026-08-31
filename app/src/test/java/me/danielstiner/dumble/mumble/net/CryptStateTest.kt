package me.danielstiner.dumble.mumble.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class CryptStateTest {
    private val key = ByteArray(16) { (it * 7 + 1).toByte() }

    /** A little-endian 128-bit counter, as the key exchange hands it over. */
    private fun counter(low: Long, high: Long = 0L) = ByteArray(16) { i ->
        if (i < 8) (low ushr (8 * i)).toByte() else (high ushr (8 * (i - 8))).toByte()
    }

    /** Two peers of one conversation: [sendSeed] is a's counter, [peerSeed] is b's. */
    private fun pair(
        sendSeed: ByteArray = counter(0),
        peerSeed: ByteArray = counter(0x5000),
    ): Pair<CryptState, CryptState> {
        val a = CryptState()
        val b = CryptState()
        a.setKeys(key, sendSeed, peerSeed)
        b.setKeys(key, peerSeed, sendSeed)
        return a to b
    }

    private fun payload(seed: Int, len: Int = 12) = ByteArray(len) { (seed + it).toByte() }

    private fun send(sender: CryptState, plain: ByteArray): ByteArray {
        val datagram = ByteArray(plain.size + CryptState.OVERHEAD)
        assertEquals(datagram.size, sender.encrypt(plain, plain.size, datagram))
        return datagram
    }

    /** The plaintext, or null when the datagram was rejected. */
    private fun receive(receiver: CryptState, datagram: ByteArray): ByteArray? {
        val plain = ByteArray(datagram.size)
        val len = receiver.decrypt(datagram, datagram.size, plain)
        return if (len < 0) null else plain.copyOf(len)
    }

    @Test
    fun roundTripsEveryLengthAcrossBlockBoundaries() {
        val (a, b) = pair()
        for (len in 0..80) {
            val plain = payload(len, len)
            val datagram = send(a, plain)
            assertEquals(len + CryptState.OVERHEAD, datagram.size)
            assertArrayEquals("length $len", plain, receive(b, datagram))
        }
        assertEquals(CryptStats(good = 81, late = 0, lost = 0, resync = 0, replay = 0), b.stats())
    }

    @Test
    fun aZeroLengthPayloadIsAFourByteDatagram() {
        val (a, b) = pair()
        val datagram = ByteArray(CryptState.OVERHEAD)
        assertEquals(CryptState.OVERHEAD, a.encrypt(ByteArray(0), 0, datagram))
        assertEquals(0, b.decrypt(datagram, datagram.size, ByteArray(0)))
        assertEquals(1, b.stats().good)
    }

    @Test
    fun theHeaderIsTheCounterByteThenThreeTagBytes() {
        val (a, b) = pair()
        val plain = payload(9)
        val datagram = send(a, plain)
        // The seed is never a nonce: the first packet goes out under seed + 1.
        assertArrayEquals(counter(1), a.encryptNonce())
        assertEquals(1, datagram[0].toInt() and 0xFF)
        assertFalse(plain.contentEquals(datagram.copyOfRange(CryptState.OVERHEAD, datagram.size)))
        assertArrayEquals(plain, receive(b, datagram))
    }

    @Test
    fun decryptWorksInPlace() {
        val (a, b) = pair()
        val datagram = send(a, payload(5, 24))
        assertEquals(24, b.decrypt(datagram, datagram.size, datagram))
        assertArrayEquals(payload(5, 24), datagram.copyOf(24))
    }

    @Test
    fun lossShowsUpAsAForwardJump() {
        val (a, b) = pair()
        val datagrams = (0 until 10).map { send(a, payload(it)) }
        assertNotNull(receive(b, datagrams[0]))
        assertNotNull(receive(b, datagrams[5]))
        assertEquals(CryptStats(good = 2, late = 0, lost = 4, resync = 0, replay = 0), b.stats())
    }

    @Test
    fun reorderingInsideTheWindowIsAcceptedExactlyOnce() {
        val (a, b) = pair()
        val datagrams = (0 until 32).map { send(a, payload(it)) }
        for (i in 31 downTo 0) {
            assertArrayEquals("packet $i", payload(i), receive(b, datagrams[i]))
        }
        // Everything arrived, just backwards, so nothing is still missing.
        assertEquals(CryptStats(good = 32, late = 31, lost = 0, resync = 0, replay = 0), b.stats())
        for (i in 0 until 32) {
            assertNull("replay of $i", receive(b, datagrams[i]))
        }
        assertEquals(CryptStats(good = 32, late = 31, lost = 0, resync = 0, replay = 32), b.stats())
    }

    @Test
    fun aReplayOfAnAcceptedPacketIsRejected() {
        val (a, b) = pair()
        val datagram = send(a, payload(3))
        assertArrayEquals(payload(3), receive(b, datagram))
        val before = b.stats()
        repeat(5) { assertNull(receive(b, datagram)) }
        // Everything but the replay count must be untouched by a rejection.
        assertEquals(before, b.stats().copy(replay = before.replay))
        assertEquals(before.replay + 5, b.stats().replay)
        assertArrayEquals(payload(4), receive(b, send(a, payload(4))))
    }

    @Test
    fun reorderingToTheWindowEdgeIsAcceptedAndOnePastItIsNot() {
        val (a, b) = pair()
        val held = send(a, payload(0))
        repeat(63) { assertNotNull(receive(b, send(a, payload(it + 1)))) }
        // Top is now 63 ahead of the held packet, the furthest back the bitmap reaches.
        assertArrayEquals(payload(0), receive(b, held))

        val (c, d) = pair()
        val tooOld = send(c, payload(0))
        repeat(64) { assertNotNull(receive(d, send(c, payload(it + 1)))) }
        val before = d.stats()
        assertNull(receive(d, tooOld))
        assertEquals(before, d.stats())
        assertNotNull(receive(d, send(c, payload(99))))
    }

    @Test
    fun aBurstOfLossWithinTheReconstructionSpanStillDecodes() {
        val (a, b) = pair()
        repeat(191) { send(a, payload(it)) }
        assertArrayEquals(payload(7), receive(b, send(a, payload(7))))
        assertEquals(CryptStats(good = 1, late = 0, lost = 191, resync = 0, replay = 0), b.stats())
    }

    @Test
    fun aBurstBeyondTheReconstructionSpanNeedsAResync() {
        val (a, b) = pair()
        repeat(192) { send(a, payload(it)) }
        // 193 ahead rebuilds as a packet behind us, and that slot is already spent, so the window
        // refuses it before any decryption -- which is what the replay count records here.
        assertNull(receive(b, send(a, payload(7))))
        assertEquals(CryptStats(good = 0, late = 0, lost = 0, resync = 0, replay = 1), b.stats())

        b.setDecryptNonce(a.encryptNonce())
        assertEquals(1, b.stats().resync)
        assertArrayEquals(payload(8), receive(b, send(a, payload(8))))
        assertEquals(CryptStats(good = 1, late = 0, lost = 0, resync = 1, replay = 1), b.stats())
    }

    @Test
    fun aResyncAtOurOwnTopKeepsAcceptedCountersSpent() {
        val (a, b) = pair()
        val held = send(a, payload(9))
        val datagrams = (0 until 5).map { send(a, payload(it)) }
        datagrams.forEach { assertNotNull(receive(b, it)) }
        // Adopted like any reply, with everything at or below it consumed, so nothing replays. The
        // open slot the held packet occupied is the cost: late packets from before a resync drop.
        b.setDecryptNonce(a.encryptNonce())
        datagrams.forEach { assertNull(receive(b, it)) }
        assertNull("late packets from before the resync are dropped", receive(b, held))
        assertEquals(CryptStats(good = 5, late = 0, lost = 1, resync = 1, replay = 6), b.stats())
        assertArrayEquals(payload(6), receive(b, send(a, payload(6))))
    }

    @Test
    fun aTamperedPacketIsRejectedAndLeavesTheStreamIntact() {
        val (a, b) = pair()
        assertNotNull(receive(b, send(a, payload(1, 40))))
        val before = b.stats()
        val datagram = send(a, payload(2, 40))
        for (i in datagram.indices) {
            val corrupt = datagram.copyOf()
            corrupt[i] = (corrupt[i].toInt() xor 0x40).toByte()
            assertNull("flipped byte $i", receive(b, corrupt))
            assertEquals("flipped byte $i", before, b.stats())
        }
        assertArrayEquals(payload(2, 40), receive(b, datagram))
        assertArrayEquals(payload(3, 40), receive(b, send(a, payload(3, 40))))
    }

    @Test
    fun aTruncatedOrExtendedDatagramIsRejected() {
        val (a, b) = pair()
        val datagram = send(a, payload(1, 32))
        val before = b.stats()
        assertNull(receive(b, datagram.copyOf(datagram.size - 1)))
        assertNull(receive(b, datagram.copyOf(datagram.size + 1)))
        assertEquals(before, b.stats())
        assertArrayEquals(payload(1, 32), receive(b, datagram))
    }

    @Test
    fun datagramsShorterThanTheHeaderAreRejected() {
        val (_, b) = pair()
        for (len in 0 until CryptState.OVERHEAD) {
            assertEquals(-1, b.decrypt(ByteArray(8), len, ByteArray(8)))
        }
        assertEquals(CryptStats(good = 0, late = 0, lost = 0, resync = 0, replay = 0), b.stats())
    }

    @Test
    fun aPacketThatBeatsTheKeyExchangeIsRejected() {
        val fresh = CryptState()
        assertFalse(fresh.isValid())
        assertEquals(-1, fresh.decrypt(ByteArray(64), 64, ByteArray(64)))
        assertEquals(-1, fresh.decrypt(ByteArray(4), 4, ByteArray(0)))
        assertEquals(CryptStats(good = 0, late = 0, lost = 0, resync = 0, replay = 0), fresh.stats())
        assertArrayEquals(counter(0), fresh.encryptNonce())
    }

    @Test
    fun garbageFromASpoofedSourceIsRejectedAndNeverThrows() {
        val (a, b) = pair()
        assertNotNull(receive(b, send(a, payload(1))))
        val before = b.stats()
        val random = Random(11)
        repeat(2000) {
            val junk = ByteArray(random.nextInt(80))
            random.nextBytes(junk)
            assertNull(receive(b, junk))
        }
        // Junk whose low byte happens to rebuild into a spent slot is refused by the window
        // rather than the tag, so the replay count moves; nothing else may.
        assertEquals(before, b.stats().copy(replay = before.replay))
        assertArrayEquals(payload(2), receive(b, send(a, payload(2))))
    }

    @Test
    fun theLowByteWrapsAcrossMoreThanTwoHundredAndFiftySixPackets() {
        val (a, b) = pair()
        for (i in 0 until 1000) {
            assertArrayEquals("packet $i", payload(i), receive(b, send(a, payload(i))))
        }
        assertArrayEquals(counter(1000), a.encryptNonce())
        assertEquals(CryptStats(good = 1000, late = 0, lost = 0, resync = 0, replay = 0), b.stats())
    }

    @Test
    fun carryPropagatesThroughEveryCounterByte() {
        // Seeds one short of a byte-1 boundary, of the 64-bit half, and of the whole 128 bits.
        for (seed in listOf(counter(0xFFFF), counter(-1L), counter(-1L, -1L))) {
            val (a, b) = pair(sendSeed = seed)
            for (i in 0 until 4) {
                assertArrayEquals(payload(i), receive(b, send(a, payload(i))))
            }
        }
    }

    @Test
    fun rebuildingBorrowsAcrossTheSixtyFourBitHalf() {
        val (a, b) = pair(sendSeed = counter(-3L))
        val datagrams = (0 until 4).map { send(a, payload(it)) }
        // Counters straddle the half boundary: the last two carried into the high word.
        assertArrayEquals(payload(3), receive(b, datagrams[3]))
        assertArrayEquals(payload(0), receive(b, datagrams[0]))
        assertArrayEquals(payload(1), receive(b, datagrams[1]))
        assertArrayEquals(payload(2), receive(b, datagrams[2]))
        assertEquals(CryptStats(good = 4, late = 3, lost = 0, resync = 0, replay = 0), b.stats())
    }

    @Test
    fun badKeyMaterialLeavesNoPartialState() {
        val fresh = CryptState()
        assertThrows(IllegalArgumentException::class.java) {
            fresh.setKeys(ByteArray(15), counter(0), counter(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            fresh.setKeys(key, ByteArray(15), counter(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            fresh.setKeys(key, counter(0), ByteArray(17))
        }
        assertFalse(fresh.isValid())
        assertEquals(-1, fresh.decrypt(ByteArray(20), 20, ByteArray(16)))

        val (a, b) = pair()
        assertNotNull(receive(b, send(a, payload(1))))
        val before = b.stats()
        val sendNonce = b.encryptNonce()
        assertThrows(IllegalArgumentException::class.java) {
            b.setKeys(ByteArray(3), counter(0), counter(0))
        }
        assertThrows(IllegalArgumentException::class.java) { b.setDecryptNonce(ByteArray(15)) }
        assertTrue(b.isValid())
        assertEquals(before, b.stats())
        assertArrayEquals(sendNonce, b.encryptNonce())
        assertArrayEquals(payload(2), receive(b, send(a, payload(2))))
    }

    @Test
    fun rekeyingStartsTheStreamOver() {
        val (a, b) = pair()
        val stale = send(a, payload(1))
        assertNotNull(receive(b, stale))
        assertNotNull(receive(b, send(a, payload(2))))

        val rekey = ByteArray(16) { (it * 5 + 3).toByte() }
        b.setKeys(rekey, counter(0x99), counter(0))
        assertEquals(CryptStats(good = 0, late = 0, lost = 0, resync = 0, replay = 0), b.stats())
        assertArrayEquals(counter(0x99), b.encryptNonce())
        assertNull(receive(b, stale))

        val peer = CryptState()
        peer.setKeys(rekey, counter(0), counter(0x99))
        assertArrayEquals(payload(4), receive(b, send(peer, payload(4))))
        assertEquals(CryptStats(good = 1, late = 0, lost = 0, resync = 0, replay = 0), b.stats())

        // The seed itself is never a packet counter -- a sender increments before it seals -- so
        // the value it names stays spent even under a brand new key.
        val atSeed = CryptState()
        // All ones, so incrementing wraps the whole 128-bit counter to exactly zero -- the value
        // b's own seed named. counter(-1L) would only fill the low half and land on 2^64.
        atSeed.setKeys(rekey, ByteArray(16) { 0xFF.toByte() }, counter(0x99))
        assertNull(receive(b, send(atSeed, payload(5))))
    }

    @Test
    fun encryptChecksItsBuffersBeforeMovingTheCounter() {
        val (a, _) = pair()
        val before = a.encryptNonce()
        assertThrows(IllegalArgumentException::class.java) {
            a.encrypt(ByteArray(10), 10, ByteArray(13))
        }
        assertThrows(IllegalArgumentException::class.java) {
            a.encrypt(ByteArray(10), 11, ByteArray(20))
        }
        assertThrows(IllegalArgumentException::class.java) {
            a.encrypt(ByteArray(10), -1, ByteArray(20))
        }
        val shared = ByteArray(20)
        assertThrows(IllegalArgumentException::class.java) { a.encrypt(shared, 10, shared) }
        assertArrayEquals(before, a.encryptNonce())
    }

    @Test
    fun encryptBeforeKeysIsACallerBug() {
        assertThrows(IllegalStateException::class.java) {
            CryptState().encrypt(ByteArray(4), 4, ByteArray(8))
        }
    }

    @Test
    fun decryptChecksItsBuffersBeforeTouchingTheStream() {
        val (a, b) = pair()
        val datagram = send(a, payload(1, 20))
        assertThrows(IllegalArgumentException::class.java) {
            b.decrypt(datagram, datagram.size, ByteArray(19))
        }
        assertThrows(IllegalArgumentException::class.java) {
            b.decrypt(datagram, datagram.size + 1, ByteArray(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            b.decrypt(datagram, -1, ByteArray(64))
        }
        assertEquals(CryptStats(good = 0, late = 0, lost = 0, resync = 0, replay = 0), b.stats())
        assertArrayEquals(payload(1, 20), receive(b, datagram))
    }

    @Test
    fun concurrentSendersShareOneCounter() {
        val (a, _) = pair()
        val threads = List(4) {
            Thread {
                val plain = ByteArray(16)
                val datagram = ByteArray(20)
                repeat(500) { a.encrypt(plain, plain.size, datagram) }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertArrayEquals(counter(2000), a.encryptNonce())
    }

    /**
     * A reply naming a counter we have already passed is adopted like any other: a request only
     * fires from a top that stopped moving, so an honest reply is never actually behind us, and a
     * top pushed ahead by a forged tag is healed by exactly this adoption.
     */
    @Test
    fun aStaleResyncIsAdoptedAndConsumesWhatItNames() {
        val (a, b) = pair(sendSeed = counter(0x11), peerSeed = counter(0x22))
        val early = (0 until 10).map { send(a, payload(it)) }
        early.forEach { assertNotNull(receive(b, it)) }
        val staleNonce = a.encryptNonce() // what a reply built at this moment would carry
        val inFlight = (0 until 20).map { send(a, payload(100 + it)) }
        inFlight.forEach { assertNotNull(receive(b, it)) }

        b.setDecryptNonce(staleNonce)
        assertEquals(1, b.stats().resync)
        // At or below the adopted counter: consumed, not cleared.
        early.forEach { assertNull("a counter at or below the restart must stay spent", receive(b, it)) }
        // Above it: reopened, so a capture of the in-flight stretch decrypts again -- the accepted
        // trade, in practice reached only through the forged top the adoption exists to heal.
        assertArrayEquals(payload(100), receive(b, inFlight[0]))
    }

    /** A resync that genuinely moves the peer forward is adopted, and closes what it skips. */
    @Test
    fun aForwardResyncIsAdoptedAndClosesWhatItSkips() {
        val key = ByteArray(16) { it.toByte() }
        val send = CryptState().apply { setKeys(key, counter(0x33), counter(0x44)) }
        val recv = CryptState().apply { setKeys(key, counter(0x44), counter(0x33)) }

        assertNotNull(receive(recv, send(send, payload(1))))
        // Far beyond what the window can rebuild, so only a resync recovers the stream.
        val skipped = (0 until 400).map { send(send, payload(it)) }

        recv.setDecryptNonce(send.encryptNonce())
        assertEquals(1, recv.stats().resync)

        assertNotNull("the stream resumes after adopting", receive(recv, send(send, payload(9))))
        // What the resync jumped over is spent rather than merely unreachable: the last two of
        // those counters land inside the window and are refused there, before any decryption.
        val before = recv.stats().replay
        assertNull(receive(recv, skipped[399]))
        assertNull(receive(recv, skipped[398]))
        assertEquals(before + 2, recv.stats().replay)
    }

    /**
     * A forward jump of exactly the window size. Kotlin masks a Long shift count to six bits, so
     * `bitmap shl 64` is `shl 0` -- without the guard the bitmap survives the jump unshifted and
     * every slot below the new top reads as spent.
     */
    @Test
    fun aJumpOfExactlyTheWindowLeavesTheSlotsBelowItOpen() {
        val (a, b) = pair()
        val datagrams = (0 until 64).map { send(a, payload(it)) }

        assertArrayEquals("the jump itself", payload(63), receive(b, datagrams[63]))
        assertArrayEquals(
            "the packet one behind the new top must still be open",
            payload(62),
            receive(b, datagrams[62]),
        )
        assertArrayEquals("and one further behind", payload(61), receive(b, datagrams[61]))
    }

    /**
     * The XEX* forgery of eprint 2019/311, delivered as a datagram. [Ocb2] has its own coverage of
     * the countermeasure firing, but nothing pinned that this class acts on it: the forged tag
     * matches what open() recomputes, so dropping the boolean from the check would let a forgery
     * through with the tag comparison alone still satisfied.
     */
    @Test
    fun aForgedDatagramIsRejectedEvenThoughItsTagMatches() {
        val seed = counter(0x11)
        val b = CryptState().apply { setKeys(key, counter(0x77), seed) }

        // The receiver expects seed + 1; build the forgery under exactly that nonce.
        val nonce = counter(0x12)
        val plain = ByteArray(32) { if (it == 15) 0x80.toByte() else if (it >= 16) 0x42 else 0 }
        val cipher = ByteArray(32)
        val tag = ByteArray(16)
        assertFalse(
            "the crafted block must be the exploitable shape",
            Ocb2(key).seal(nonce, plain, 0, 32, cipher, 0, tag, modifyPlainOnXEXStarAttack = false),
        )
        val forgedCipher = ByteArray(16) { (cipher[it].toInt() xor plain[it].toInt()).toByte() }
        val forgedTag = ByteArray(16) { (cipher[16 + it].toInt() xor plain[16 + it].toInt()).toByte() }

        // Confirm the tag really does match, or this proves nothing.
        val recovered = ByteArray(16)
        val recomputed = ByteArray(16)
        Ocb2(key).open(nonce, forgedCipher, 0, 16, recovered, 0, recomputed)
        assertArrayEquals("the forgery must be tag-valid", forgedTag, recomputed)

        val datagram = ByteArray(20)
        datagram[0] = 0x12
        datagram[1] = forgedTag[0]
        datagram[2] = forgedTag[1]
        datagram[3] = forgedTag[2]
        forgedCipher.copyInto(datagram, 4)
        assertNull("a tag-valid forgery must still be refused", receive(b, datagram))
    }

    /**
     * A poisoned top must not wedge the direction. On the wire a top only gets ahead of the peer
     * through a forged tag winning a 2^24 collision; the next truthful reply has to pull us back,
     * which unconditional adoption guarantees.
     */
    @Test
    fun aPoisonedTopIsHealedByTheNextResync() {
        val (a, b) = pair()
        repeat(3) { assertNotNull(receive(b, send(a, payload(it)))) }

        b.setDecryptNonce(counter(0, high = 1L)) // 2^64, far past the peer
        repeat(3) { assertNull("nothing decodes against a poisoned top", receive(b, send(a, payload(it)))) }

        b.setDecryptNonce(a.encryptNonce()) // the peer tells us where it really is
        assertArrayEquals("the stream must come back", payload(9), receive(b, send(a, payload(9))))
    }
}
