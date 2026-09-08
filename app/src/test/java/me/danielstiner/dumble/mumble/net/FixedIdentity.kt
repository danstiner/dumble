package me.danielstiner.dumble.mumble.net

/** A store that always hands out the same identity, for tests that need a known certificate. */
class FixedIdentity(private val identity: ClientIdentity) : ClientIdentityStore {
    override suspend fun load(): ClientIdentity = identity
}
