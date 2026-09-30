package me.danielstiner.dumble.mumble.net

/** A store that always hands out the same identity, for tests that need a known certificate. */
class FixedIdentity(private val identity: ClientIdentity) : ClientIdentityStore {
    override suspend fun load(): ClientIdentity = identity
}

/**
 * One RSA-3072 identity for the whole test JVM: generating one takes most of a second. Generated,
 * not a stored fixture, because ClientIdentityTest checks what generate() builds through it.
 */
val testIdentity: ClientIdentity by lazy { ClientIdentity.generate() }
