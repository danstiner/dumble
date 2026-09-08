package me.danielstiner.dumble.mumble.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Where the app's [ClientIdentity] comes from. */
interface ClientIdentityStore {
    /** The identity, generated and saved on first call. Null only from [NoClientIdentity]. */
    suspend fun load(): ClientIdentity?
}

/** For tests, and any build that presents no certificate. */
object NoClientIdentity : ClientIdentityStore {
    override suspend fun load(): ClientIdentity? = null
}

/**
 * One identity per install, kept in [file] as the PKCS#12 [ClientIdentity.encode] writes. Read
 * once per process; generated the first time, inside the first connect (3.8 s of RSA on the
 * emulator, unmeasured on a phone).
 *
 * Auto Backup carries the file to a reinstall or a new phone: it must stay under `filesDir` and
 * out of any backup exclusion.
 */
class FileClientIdentityStore(private val file: File) : ClientIdentityStore {

    // Two connects racing on a fresh install would otherwise each generate, and the memo could
    // hold one identity while the file holds the other.
    private val mutex = Mutex()
    @Volatile private var loaded: ClientIdentity? = null

    override suspend fun load(): ClientIdentity = loaded ?: mutex.withLock {
        loaded ?: withContext(Dispatchers.IO) { readOrCreate() }.also { loaded = it }
    }

    private fun readOrCreate(): ClientIdentity {
        if (file.exists()) {
            // Read outside the try: an IOException here is a disk failure, not a bad file, and
            // must propagate rather than get the file rotated away.
            val bytes = file.readBytes()
            try {
                return ClientIdentity.decode(bytes)
            } catch (e: Exception) {
                // Refusing to connect would make the app unusable over a file nobody can repair;
                // moving it aside keeps the bytes for a bug report.
                Log.e(TAG, "client identity at $file does not decode; moving it aside and generating a new one", e)
                Files.move(file.toPath(), File(file.path + ".corrupt").toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        val started = System.nanoTime()
        val identity = ClientIdentity.generate()
        // Synced before the rename so a power loss leaves the old file or none, never a torn
        // file at the final name — which the corrupt path above would then silently replace.
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(identity.encode())
            out.fd.sync()
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Log.i(TAG, "generated client identity sha1=${identity.hash} in ${(System.nanoTime() - started) / 1_000_000} ms")
        return identity
    }

    private companion object {
        const val TAG = "ClientIdentity"
    }
}
