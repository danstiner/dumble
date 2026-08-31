package me.danielstiner.dumble.mumble.net

import java.lang.management.ManagementFactory
import java.lang.reflect.Modifier
import java.util.Random
import javax.crypto.Cipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Properties of [CryptState] that a hand-written case cannot state: the replay guarantee against a
 * model, the allocation budget the audio path depends on, and the locking the class claims.
 */
class CryptStatePropertyTest {

    private val key = ByteArray(16) { it.toByte() }
    private val sendSeed = ByteArray(16) { (0x40 + it).toByte() }
    private val recvSeed = ByteArray(16) { (0x80 + it).toByte() }

    /**
     * The core security property, against an exact model. A `Set` of every counter the receiver has
     * accepted is the oracle: no counter may ever be accepted twice, no matter how the stream is
     * shuffled, dropped or duplicated. Randomised over many seeds because the interesting states --
     * a bitmap edge landing exactly on a duplicate -- are ones no fixed case thinks to build.
     */
    @Test
    fun noCounterIsEverAcceptedTwice() {
        for (seed in 0 until 60) {
            val rnd = Random(seed.toLong())
            val sender = CryptState().apply { setKeys(key, sendSeed, recvSeed) }
            val receiver = CryptState().apply { setKeys(key, recvSeed, sendSeed) }

            val packets = 1500
            // Filled rather than zeroed: a payload that is all zeros but for byte 0 makes the
            // second-to-last block the shape the XEX* mitigation rewrites, and the receiver then
            // correctly recovers a flipped byte 0, which is not what this property is about.
            val plain = ByteArray(32) { (it * 31 + 7).toByte() }
            // Index i is the sender's i-th packet, so its counter is distinct by construction.
            val wires = (0 until packets).map { i ->
                ByteArray(36).also { w ->
                    plain[0] = i.toByte()
                    sender.encrypt(plain, 32, w)
                }
            }

            val accepted = HashSet<Int>()
            val out = ByteArray(36)
            var i = 0
            while (i < packets) {
                // A group is shuffled, partly dropped, and partly sent twice.
                val group = (i until minOf(i + 1 + rnd.nextInt(40), packets)).toMutableList()
                java.util.Collections.shuffle(group, rnd)
                for (idx in group) {
                    if (rnd.nextInt(100) < 15) continue
                    repeat(1 + rnd.nextInt(3)) {
                        if (receiver.decrypt(wires[idx], 36, out) >= 0) {
                            assertTrue(
                                "seed=$seed: counter for packet $idx accepted twice",
                                accepted.add(idx),
                            )
                            assertEquals("seed=$seed: wrong plaintext for $idx", idx.toByte(), out[0])
                        }
                    }
                }
                i += group.size
            }
            assertEquals("seed=$seed: good must equal the accepted set", accepted.size, receiver.stats().good)
            assertTrue("seed=$seed: lost must not go negative", receiver.stats().lost >= 0)
        }
    }

    /**
     * The steady state must not allocate: per-packet garbage on the voice path shows up as jitter.
     * Measured, not asserted by inspection -- a single 16-byte allocation per call would be 1.6 MB
     * here, three orders of magnitude past the threshold.
     */
    @Test
    fun theSteadyStateDoesNotAllocate() {
        val bean = try {
            ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        } catch (e: Throwable) {
            assumeNoException("this JVM does not expose per-thread allocation counts", e)
            return
        }
        // Disabled measurement returns -1 from both reads, which would pass as zero allocation.
        assumeTrue(
            "per-thread allocation measurement is disabled",
            bean.isThreadAllocatedMemorySupported && bean.isThreadAllocatedMemoryEnabled,
        )
        // Another test class can swap the JCE provider process-wide before this one runs
        // (Robolectric registers Conscrypt first); a JNI-backed cipher allocates at its own rate,
        // and the threshold below was measured against SunJCE.
        val provider = Cipher.getInstance("AES/ECB/NoPadding").provider.name
        assumeTrue("cipher served by $provider, not SunJCE", provider == "SunJCE")
        val id = Thread.currentThread().threadId()
        val sender = CryptState().apply { setKeys(key, sendSeed, recvSeed) }
        val receiver = CryptState().apply { setKeys(key, recvSeed, sendSeed) }
        val plain = ByteArray(160) { it.toByte() }
        val wire = ByteArray(164)
        val out = ByteArray(164)

        // Warm up first: the measurement is of the compiled steady state, not of the interpreter.
        repeat(50_000) { sender.encrypt(plain, 160, wire); receiver.decrypt(wire, 164, out) }

        val rounds = 100_000
        val before = bean.getThreadAllocatedBytes(id)
        for (i in 0 until rounds) {
            sender.encrypt(plain, 160, wire)
            receiver.decrypt(wire, 164, out)
        }
        val allocated = bean.getThreadAllocatedBytes(id) - before
        assertTrue(
            "steady state allocated $allocated bytes over $rounds round trips",
            allocated < 64 * 1024,
        )
    }

    /**
     * CryptState is shared across three threads, so every public method must hold the monitor. A
     * race only shows up by luck; the modifier is checkable outright.
     */
    @Test
    fun everyPublicMethodIsSynchronized() {
        val unguarded = CryptState::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.isBridge }
            .filterNot { Modifier.isSynchronized(it.modifiers) }
            .map { it.name }
            .sorted()
        assertEquals("public methods missing the monitor: $unguarded", emptyList<String>(), unguarded)
    }

    /**
     * The converse of [noCounterIsEverAcceptedTwice], and the half that was only ever argued: a
     * packet delivered while it is still inside the window must never be refused. The schedule
     * stays inside deliberately -- reordering is confined to groups well under the 63 the window
     * tolerates, and dropped runs stay well under the 191 a forward jump can rebuild -- so every
     * first delivery is one the receiver has no licence to reject, and every repeat is a replay.
     */
    @Test
    fun everyPacketDeliveredInsideTheWindowIsAccepted() {
        for (seed in 0 until 60) {
            val rnd = Random(seed.toLong())
            val sender = CryptState().apply { setKeys(key, sendSeed, recvSeed) }
            val receiver = CryptState().apply { setKeys(key, recvSeed, sendSeed) }

            val packets = 1200
            val plain = ByteArray(48) { (it * 31 + 7).toByte() }
            val wires = (0 until packets).map { i ->
                ByteArray(52).also { w ->
                    plain[0] = i.toByte()
                    sender.encrypt(plain, 48, w)
                }
            }

            val out = ByteArray(52)
            var accepted = 0
            var replays = 0
            var highest = 0
            var expectedLate = 0
            var i = 0
            while (i < packets) {
                // Groups are consecutive and disjoint, so every index reaches the receiver at most
                // once here; the repeats below are the only thing the window may refuse.
                val group = (i until minOf(i + 1 + rnd.nextInt(30), packets)).toMutableList()
                java.util.Collections.shuffle(group, rnd)
                // Drop at most a third of a group, so no gap approaches the forward span.
                val kept = group.filter { rnd.nextInt(3) != 0 }
                for (idx in kept) {
                    val result = receiver.decrypt(wires[idx], 52, out)
                    assertEquals("seed=$seed: in-window packet $idx was refused", 48, result)
                    assertEquals("seed=$seed: wrong plaintext for $idx", idx.toByte(), out[0])
                    accepted++
                    // Ground truth for the late count: a delivery is late exactly when a higher
                    // counter was already accepted.
                    if (idx + 1 < highest) expectedLate++
                    highest = maxOf(highest, idx + 1)
                    // A quarter are sent again immediately, so the replay path runs throughout.
                    if (rnd.nextInt(4) == 0) {
                        assertEquals(
                            "seed=$seed: immediate repeat of $idx",
                            -1,
                            receiver.decrypt(wires[idx], 52, out),
                        )
                        replays++
                    }
                }
                i += group.size
            }
            val stats = receiver.stats()
            assertEquals("seed=$seed: good", accepted, stats.good)
            assertEquals("seed=$seed: replay count", replays, stats.replay)
            // Conservation: every counter up to the highest accepted is either good or still
            // missing, and reordering registered as late without inflating either.
            assertEquals("seed=$seed: good + lost", highest, stats.good + stats.lost)
            assertEquals("seed=$seed: late", expectedLate, stats.late)
        }
    }

    /**
     * Everything else here fixes the key and both seeds, so the counter always starts in the same
     * place and only the delivery order varies. These two randomise the key material instead: a
     * random seed puts the low byte, and every carry position above it, somewhere different each
     * iteration, so wraparound and borrow cases turn up incidentally rather than only where a
     * hand-written case aims at them.
     */
    @Test
    fun roundTripsAcrossRandomKeyMaterial() {
        val rnd = Random(4242)
        repeat(400) { iteration ->
            val key = ByteArray(16).also { rnd.nextBytes(it) }
            val ours = ByteArray(16).also { rnd.nextBytes(it) }
            val theirs = ByteArray(16).also { rnd.nextBytes(it) }
            val sender = CryptState().apply { setKeys(key, ours, theirs) }
            val receiver = CryptState().apply { setKeys(key, theirs, ours) }

            repeat(8) { packet ->
                // Up to MTU-scale, so multi-block payloads the size of real voice datagrams run too.
                val len = rnd.nextInt(0, 1500)
                val plain = ByteArray(len).also { rnd.nextBytes(it) }
                val wire = ByteArray(len + CryptState.OVERHEAD)
                val out = ByteArray(len)
                assertEquals(len + CryptState.OVERHEAD, sender.encrypt(plain, len, wire))
                assertEquals(
                    "iteration=$iteration packet=$packet len=$len",
                    len,
                    receiver.decrypt(wire, wire.size, out),
                )
                assertArrayEquals("iteration=$iteration packet=$packet", plain, out)
            }
        }
    }

    /** Any single bit flipped anywhere in a datagram must be refused, whatever the key material. */
    @Test
    fun tamperIsRefusedAcrossRandomKeyMaterial() {
        val rnd = Random(2718)
        repeat(300) { iteration ->
            val key = ByteArray(16).also { rnd.nextBytes(it) }
            val ours = ByteArray(16).also { rnd.nextBytes(it) }
            val theirs = ByteArray(16).also { rnd.nextBytes(it) }
            val sender = CryptState().apply { setKeys(key, ours, theirs) }
            val receiver = CryptState().apply { setKeys(key, theirs, ours) }

            val len = rnd.nextInt(0, 120)
            val plain = ByteArray(len).also { rnd.nextBytes(it) }
            val wire = ByteArray(len + CryptState.OVERHEAD)
            sender.encrypt(plain, len, wire)

            val corrupted = wire.copyOf()
            val bit = rnd.nextInt(corrupted.size * 8)
            corrupted[bit / 8] = (corrupted[bit / 8].toInt() xor (1 shl (bit % 8))).toByte()

            val out = ByteArray(maxOf(len, 1))
            assertEquals(
                "iteration=$iteration len=$len bit=$bit was accepted",
                -1,
                receiver.decrypt(corrupted, corrupted.size, out),
            )
            // And the stream survives it: the next genuine packet still decrypts.
            val next = ByteArray(len + CryptState.OVERHEAD)
            sender.encrypt(plain, len, next)
            assertEquals("iteration=$iteration stream broken", len, receiver.decrypt(next, next.size, out))
        }
    }
}
